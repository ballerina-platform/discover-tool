/*
 *  Copyright (c) 2026, WSO2 LLC. (http://www.wso2.com)
 *
 *  WSO2 LLC. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package io.ballerina.tools.discover.central;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The real transport, on the JDK's own client.
 *
 * <p>The client is built once per process because a fresh one per attempt would throw away the connection pool
 * between the two requests a lookup makes.
 *
 * @since 0.1.0
 */
public final class JdkHttpTransport implements HttpTransport {

    private final HttpClient client;
    private final long archiveLimit;

    public JdkHttpTransport() {
        this(Bala.MAX_ARCHIVE_BYTES);
    }

    /** A transport refusing any download past {@code archiveLimit} bytes. */
    JdkHttpTransport(long archiveLimit) {
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.archiveLimit = archiveLimit;
    }

    @Override
    public Reply get(String url, long timeoutMs) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Accept", "application/json")
                    .build();
        } catch (IllegalArgumentException malformed) {
            return new Reply.Failed("bad url: " + malformed.getMessage());
        }

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String retryAfter = response.headers().firstValue("retry-after").orElse(null);
            return new Reply.Answered(response.statusCode(), response.body(), retryAfter);
        } catch (HttpTimeoutException timedOut) {
            return new Reply.TimedOut();
        } catch (IOException failed) {
            return new Reply.Failed("network error: " + describe(failed));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Reply.Failed("network error: interrupted");
        }
    }

    @Override
    public Optional<InputStream> openStream(String url, long timeoutMs) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .GET()
                    .timeout(Duration.ofMillis(timeoutMs))
                    .build();
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        CompletableFuture<HttpResponse<InputStream>> download = client.sendAsync(request, info -> {
            long declared = info.headers().firstValueAsLong("content-length").orElse(-1);
            return info.statusCode() >= 200 && info.statusCode() < 300 && declared <= archiveLimit
                    ? new CappedBody(archiveLimit, declared)
                    : new NoBody();
        });
        try {
            return Optional.ofNullable(download.get(timeoutMs, TimeUnit.MILLISECONDS).body());
        } catch (TimeoutException | ExecutionException failed) {
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            download.cancel(true);
        }
    }

    /**
     * A body collected whole, up to {@code limit} bytes; a body past the limit is {@code null}. Waiting on a
     * complete body is what lets the caller's deadline bound the transfer: a stream handed out early would leave a
     * stalled server holding a {@code read()} no timeout reaches.
     */
    private static final class CappedBody implements HttpResponse.BodySubscriber<InputStream> {

        private static final int UNDECLARED_INITIAL_BYTES = 64 * 1024;
        private static final int MAX_INITIAL_BYTES = 4 * 1024 * 1024;

        private final CompletableFuture<InputStream> body = new CompletableFuture<>();
        private final Collected buffer;
        private final long limit;
        private Flow.Subscription subscription;

        /** {@code declared} is the response's {@code Content-Length}, or negative when it has none. */
        CappedBody(long limit, long declared) {
            this.limit = limit;
            long expected = declared >= 0 ? declared : UNDECLARED_INITIAL_BYTES;
            this.buffer = new Collected((int) Math.min(Math.min(expected, limit), MAX_INITIAL_BYTES));
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                if (body.isDone()) {
                    return;
                }
                if (buffer.size() + (long) item.remaining() > limit) {
                    if (subscription != null) {
                        subscription.cancel();
                    }
                    body.complete(null);
                    return;
                }
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                buffer.write(chunk, 0, chunk.length);
            }
        }

        @Override
        public void onError(Throwable failure) {
            body.completeExceptionally(failure);
        }

        @Override
        public void onComplete() {
            body.complete(buffer.asInputStream());
        }

        @Override
        public CompletionStage<InputStream> getBody() {
            return body;
        }
    }

    /** A buffer read back in place, rather than through the full copy {@link #toByteArray()} makes. */
    private static final class Collected extends ByteArrayOutputStream {

        Collected(int initialBytes) {
            super(initialBytes);
        }

        InputStream asInputStream() {
            return new ByteArrayInputStream(buf, 0, count);
        }
    }

    /** A body refused unread: an error status, or a {@code Content-Length} already past the limit. */
    private static final class NoBody implements HttpResponse.BodySubscriber<InputStream> {

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.cancel();
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
        }

        @Override
        public void onError(Throwable failure) {
        }

        @Override
        public void onComplete() {
        }

        @Override
        public CompletionStage<InputStream> getBody() {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static String describe(Throwable cause) {
        String message = cause.getMessage();
        return message == null || message.isEmpty() ? cause.getClass().getSimpleName() : message;
    }
}
