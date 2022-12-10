/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.apache.hugegraph.computer.core.receiver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hugegraph.computer.core.common.exception.ComputerException;
import org.apache.hugegraph.computer.core.config.ComputerOptions;
import org.apache.hugegraph.computer.core.config.Config;
import org.apache.hugegraph.computer.core.network.buffer.FileRegionBuffer;
import org.apache.hugegraph.computer.core.network.buffer.NetworkBuffer;
import org.apache.hugegraph.computer.core.sort.flusher.OuterSortFlusher;
import org.apache.hugegraph.computer.core.sort.flusher.PeekableIterator;
import org.apache.hugegraph.computer.core.sort.sorting.SortManager;
import org.apache.hugegraph.computer.core.store.SuperstepFileGenerator;
import org.apache.hugegraph.computer.core.store.entry.KvEntry;
import org.apache.hugegraph.computer.core.util.FileUtil;
import org.apache.hugegraph.util.Log;
import org.slf4j.Logger;

/**
 * Manage the buffers received for a partition and the files generated by
 * sorting the buffers to file. The type of data may be VERTEX, EDGE, and
 * MESSAGE.
 */
public abstract class MessageRecvPartition {

    public static final Logger LOG = Log.logger(MessageRecvPartition.class);

    private MessageRecvBuffers recvBuffers;
    /*
     * Used to sort the buffers that reached threshold
     * ComputerOptions.WORKER_RECEIVED_BUFFERS_BYTES_LIMIT.
     */
    private MessageRecvBuffers sortBuffers;
    private final SortManager sortManager;

    private List<String> outputFiles;
    private final SuperstepFileGenerator fileGenerator;
    private final boolean withSubKv;
    private final int mergeFileNum;
    private long totalBytes;
    private final boolean useFileRegion;

    private final AtomicReference<Throwable> exception;

    public MessageRecvPartition(Config config,
                                SuperstepFileGenerator fileGenerator,
                                SortManager sortManager,
                                boolean withSubKv) {
        this.fileGenerator = fileGenerator;
        this.sortManager = sortManager;
        this.withSubKv = withSubKv;
        long buffersLimit = config.get(
             ComputerOptions.WORKER_RECEIVED_BUFFERS_BYTES_LIMIT);

        long waitSortTimeout = config.get(
                               ComputerOptions.WORKER_WAIT_SORT_TIMEOUT);
        this.mergeFileNum = config.get(ComputerOptions.HGKV_MERGE_FILES_NUM);
        this.useFileRegion = config.get(
                             ComputerOptions.TRANSPORT_RECV_FILE_MODE);
        if (!this.useFileRegion) {
            this.recvBuffers = new MessageRecvBuffers(buffersLimit,
                                                      waitSortTimeout);
            this.sortBuffers = new MessageRecvBuffers(buffersLimit,
                                                      waitSortTimeout);
        }

        this.outputFiles = new ArrayList<>();
        this.totalBytes = 0L;
        this.exception = new AtomicReference<>();
    }

    /**
     * Only one thread can call this method.
     */
    public synchronized void addBuffer(NetworkBuffer buffer) {
        this.totalBytes += buffer.length();
        if (buffer instanceof FileRegionBuffer) {
            String path = ((FileRegionBuffer) buffer).path();
            this.outputFiles.add(path);
            return;
        }
        this.recvBuffers.addBuffer(buffer);
        if (this.recvBuffers.full()) {
            // Wait for the previous sorting
            this.sortBuffers.waitSorted();
            // Transfer recvBuffers to sortBuffers, then sort and flush
            this.swapReceiveAndSortBuffers();
            this.flushSortBuffersAsync();
        }
    }

    public synchronized PeekableIterator<KvEntry> iterator() {
        /*
         * TODO: create iterator directly from buffers if there is no
         *       outputFiles.
         */
        if (!this.useFileRegion) {
            this.flushAllBuffersAndWaitSorted();
        }
        this.mergeOutputFilesIfNeeded();
        if (this.outputFiles.size() == 0) {
            return PeekableIterator.emptyIterator();
        }
        return this.sortManager.iterator(this.outputFiles, this.withSubKv);
    }

    public synchronized long totalBytes() {
        return this.totalBytes;
    }

    public synchronized MessageStat messageStat() {
        // TODO: count the message received
        return new MessageStat(0L, this.totalBytes);
    }

    protected abstract OuterSortFlusher outerSortFlusher();

    protected abstract String type();

    /**
     * Flush the receive buffers to file, and wait both recvBuffers and
     * sortBuffers to finish sorting.
     * After this method be called, can not call
     * {@link #addBuffer(NetworkBuffer)} any more.
     */
    private void flushAllBuffersAndWaitSorted() {
        this.sortBuffers.waitSorted();
        if (this.recvBuffers.totalBytes() > 0) {
            // Transfer recvBuffers to sortBuffers, then sort and flush
            this.swapReceiveAndSortBuffers();
            this.flushSortBuffersAsync();
            this.sortBuffers.waitSorted();
        }
        this.checkException();
    }

    private void flushSortBuffersAsync() {
        String path = this.genOutputPath();
        this.mergeBuffersAsync(this.sortBuffers, path);
        this.outputFiles.add(path);
    }

    private void mergeBuffersAsync(MessageRecvBuffers buffers, String path) {
        this.checkException();
        this.sortManager.mergeBuffers(buffers.buffers(), path,
                                      this.withSubKv, this.outerSortFlusher())
                        .whenComplete((r , e) -> {
            if (e != null) {
                LOG.error("Failed to merge buffers", e);
                // Just record the first error
                this.exception.compareAndSet(null, e);
            }
            // Signal the buffers to prevent other thread wait indefinitely.
            buffers.signalSorted();
        });
    }

    private void swapReceiveAndSortBuffers() {
        assert this.recvBuffers.totalBytes() > 0;
        MessageRecvBuffers oldRecvBuffers = this.recvBuffers;
        this.recvBuffers = this.sortBuffers;
        this.sortBuffers = oldRecvBuffers;

        // Prepare for the next buffer-adding/sorting
        this.recvBuffers.prepareSort();
    }

    /**
     * Merge outputFiles if needed, like merge 10000 files into 100 files.
     */
    private void mergeOutputFilesIfNeeded() {
        if (this.outputFiles.size() <= 1) {
            return;
        }

        /*
         * TODO Restore genOutputFileNames(sqrt(outputFiles.size()))
         *  after add Sorter#iterator() of subkv
         */
        int mergeFileNum = this.mergeFileNum;
        mergeFileNum = 1;
        List<String> newOutputs = this.genOutputFileNames(mergeFileNum);
        this.sortManager.mergeInputs(this.outputFiles, newOutputs,
                                     this.withSubKv, this.outerSortFlusher());
        FileUtil.deleteFilesQuietly(this.outputFiles);
        this.outputFiles = newOutputs;
    }

    public String genOutputPath() {
        return this.fileGenerator.nextPath(this.type());
    }

    private List<String> genOutputFileNames(int targetSize) {
        List<String> files = new ArrayList<>(targetSize);
        for (int i = 0; i < targetSize; i++) {
            files.add(this.genOutputPath());
        }
        return files;
    }

    private void checkException() {
        Throwable t = this.exception.get();
        if (t != null) {
            throw new ComputerException(t.getMessage(), t);
        }
    }
}