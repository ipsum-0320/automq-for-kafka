/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kafka.log.es;

import com.automq.elasticstream.client.api.AppendResult;
import com.automq.elasticstream.client.api.Client;
import com.automq.elasticstream.client.api.CreateStreamOptions;
import com.automq.elasticstream.client.api.FetchResult;
import com.automq.elasticstream.client.api.KVClient;
import com.automq.elasticstream.client.api.OpenStreamOptions;
import com.automq.elasticstream.client.api.RecordBatch;
import com.automq.elasticstream.client.api.Stream;
import com.automq.elasticstream.client.api.StreamClient;
import org.apache.kafka.common.utils.ThreadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AlwaysSuccessClient implements Client {
    private static final Logger LOGGER = LoggerFactory.getLogger(AlwaysSuccessClient.class);

    // cause of rust frontend is single thread, so we use thread executor free callback overhead.
    // caution: it should call another stream method in one method callback to avoid deadlock, if these method callback
    // executor is same.
    // TODO: change some api to sync call to avoid deadlock.
    private static final ScheduledExecutorService STREAM_MANAGER_RETRY_SCHEDULER = Executors.newScheduledThreadPool(1,
            ThreadUtils.createThreadFactory("stream-manager-retry-%d", true));
    private static final ExecutorService STREAM_MANAGER_CALLBACK_EXECUTORS = Executors.newFixedThreadPool(1,
            ThreadUtils.createThreadFactory("stream-manager-callback-executor-%d", true));
    private static final ScheduledExecutorService FETCH_RETRY_SCHEDULER = Executors.newScheduledThreadPool(1,
            ThreadUtils.createThreadFactory("fetch-retry-scheduler-%d", true));
    private static final ExecutorService APPEND_CALLBACK_EXECUTORS = Executors.newFixedThreadPool(4,
            ThreadUtils.createThreadFactory("append-callback-scheduler-%d", true));
    private static final ExecutorService FETCH_CALLBACK_EXECUTORS = Executors.newFixedThreadPool(4,
            ThreadUtils.createThreadFactory("fetch-callback-scheduler-%d", true));
    private final StreamClient streamClient;
    private final KVClient kvClient;

    public AlwaysSuccessClient(Client client) {
        this.streamClient = new StreamClientImpl(client.streamClient());
        this.kvClient = client.kvClient();
    }

    @Override
    public StreamClient streamClient() {
        return streamClient;
    }

    @Override
    public KVClient kvClient() {
        return kvClient;
    }

    static class StreamClientImpl implements StreamClient {
        private final StreamClient streamClient;

        public StreamClientImpl(StreamClient streamClient) {
            this.streamClient = streamClient;
        }

        @Override
        public CompletableFuture<Stream> createAndOpenStream(CreateStreamOptions options) {
            CompletableFuture<Stream> cf = new CompletableFuture<>();
            createAndOpenStream0(options, cf);
            return cf;
        }

        private void createAndOpenStream0(CreateStreamOptions options, CompletableFuture<Stream> cf) {
            streamClient.createAndOpenStream(options).whenCompleteAsync((stream, ex) -> {
                if (ex != null) {
                    LOGGER.error("Create and open stream fail, retry later", ex);
                    STREAM_MANAGER_RETRY_SCHEDULER.schedule(() -> createAndOpenStream0(options, cf), 3, TimeUnit.SECONDS);
                } else {
                    cf.complete(new StreamImpl(stream));
                }
            }, STREAM_MANAGER_CALLBACK_EXECUTORS);
        }

        @Override
        public CompletableFuture<Stream> openStream(long streamId, OpenStreamOptions options) {
            CompletableFuture<Stream> cf = new CompletableFuture<>();
            openStream0(streamId, options, cf);
            return cf;
        }

        private void openStream0(long streamId, OpenStreamOptions options, CompletableFuture<Stream> cf) {
            streamClient.openStream(streamId, options).whenCompleteAsync((stream, ex) -> {
                if (ex != null) {
                    LOGGER.error("Create open stream[{}] fail, retry later", streamId, ex);
                    STREAM_MANAGER_RETRY_SCHEDULER.schedule(() -> openStream0(streamId, options, cf), 3, TimeUnit.SECONDS);
                } else {
                    cf.complete(new StreamImpl(stream));
                }
            }, APPEND_CALLBACK_EXECUTORS);
        }
    }

    static class StreamImpl implements Stream {
        private final Stream stream;
        private volatile boolean closed = false;

        public StreamImpl(Stream stream) {
            this.stream = stream;
        }

        @Override
        public long streamId() {
            return stream.streamId();
        }

        @Override
        public long startOffset() {
            return stream.startOffset();
        }

        @Override
        public long nextOffset() {
            return stream.nextOffset();
        }

        @Override
        public CompletableFuture<AppendResult> append(RecordBatch recordBatch) {
            CompletableFuture<AppendResult> cf = new CompletableFuture<>();
            stream.append(recordBatch)
                    .whenComplete((rst, ex) -> {
                        if (ex != null) {
                            cf.completeExceptionally(ex);
                        } else {
                            cf.complete(rst);
                        }
                    });
            return cf;
        }


        @Override
        public CompletableFuture<FetchResult> fetch(long startOffset, long endOffset, int maxBytesHint) {
            CompletableFuture<FetchResult> cf = new CompletableFuture<>();
            fetch0(startOffset, endOffset, maxBytesHint, cf);
            return cf;
        }

        private void fetch0(long startOffset, long endOffset, int maxBytesHint, CompletableFuture<FetchResult> cf) {
            stream.fetch(startOffset, endOffset, maxBytesHint).whenCompleteAsync((rst, ex) -> {
                if (ex != null) {
                    LOGGER.error("Fetch stream[{}] [{},{}) fail, retry later", streamId(), startOffset, endOffset);
                    if (!closed) {
                        FETCH_RETRY_SCHEDULER.schedule(() -> fetch0(startOffset, endOffset, maxBytesHint, cf), 3, TimeUnit.SECONDS);
                    } else {
                        cf.completeExceptionally(new IllegalStateException("stream already closed"));
                    }
                } else {
                    cf.complete(rst);
                }
            }, FETCH_CALLBACK_EXECUTORS);
        }

        @Override
        public CompletableFuture<Void> trim(long newStartOffset) {
            CompletableFuture<Void> cf = new CompletableFuture<>();
            stream.trim(newStartOffset).whenCompleteAsync((rst, ex) -> {
                if (ex != null) {
                    cf.completeExceptionally(ex);
                } else {
                    cf.complete(rst);
                }
            }, APPEND_CALLBACK_EXECUTORS);
            return cf;
        }

        @Override
        public CompletableFuture<Void> close() {
            closed = true;
            CompletableFuture<Void> cf = new CompletableFuture<>();
            stream.close().whenCompleteAsync((rst, ex) -> {
                if (ex != null) {
                    cf.completeExceptionally(ex);
                } else {
                    cf.complete(rst);
                }
            }, APPEND_CALLBACK_EXECUTORS);
            return cf;
        }

        @Override
        public CompletableFuture<Void> destroy() {
            CompletableFuture<Void> cf = new CompletableFuture<>();
            stream.destroy().whenCompleteAsync((rst, ex) -> {
                if (ex != null) {
                    cf.completeExceptionally(ex);
                } else {
                    cf.complete(rst);
                }
            }, APPEND_CALLBACK_EXECUTORS);
            return cf;
        }
    }
}
