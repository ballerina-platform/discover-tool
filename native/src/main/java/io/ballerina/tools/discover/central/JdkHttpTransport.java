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
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.UnresolvedAddressException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLException;

/**
 * The real transport, on the JDK's own client.
 *
 * <p>The client is built once per process because a fresh one per attempt would throw away the connection pool
 * between the two requests a lookup makes.
 *
 * @since 0.1.0
 */
public final class JdkHttpTransport implements HttpTransport {

    // The JDK reports a proxy's error answer to a CONNECT only in this message.
    private static final Pattern TUNNEL_FAILED = Pattern.compile("Tunnel failed, got: (\\d+)");

    static final String TUNNELING_DISABLED_SCHEMES = "jdk.http.auth.tunneling.disabledSchemes";

    private final HttpClient client;
    private final long archiveLimit;
    private final ProxyAuthenticator authenticator;

    public JdkHttpTransport() {
        this(Bala.MAX_ARCHIVE_BYTES, null);
    }

    /** A transport sending every request through {@code proxy}. */
    public JdkHttpTransport(ProxySettings proxy) {
        this(Bala.MAX_ARCHIVE_BYTES, proxy);
    }

    /**
     * A transport refusing any download past {@code archiveLimit} bytes, through {@code proxy} unless it is
     * {@code null}.
     */
    JdkHttpTransport(long archiveLimit, ProxySettings proxy) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30));
        ProxyAuthenticator answering = null;
        if (proxy != null) {
            builder.proxy(ProxySelector.of(InetSocketAddress.createUnresolved(proxy.host(), proxy.port())));
            allowBasicInTunnels(System.getProperties(), proxy);
            if (proxy.authenticates()) {
                answering = new ProxyAuthenticator(proxy);
                builder.authenticator(answering);
            }
        }
        this.client = builder.build();
        this.archiveLimit = archiveLimit;
        this.authenticator = answering;
    }

    /**
     * Lets Basic credentials answer a proxy's challenge to a {@code CONNECT}, which Central's HTTPS always goes
     * through and the JDK refuses them in by default; {@code bal pull} sends them. Only a proxy with credentials
     * needs it, and a value the user set is kept.
     */
    static void allowBasicInTunnels(Properties properties, ProxySettings proxy) {
        if (proxy.authenticates()) {
            properties.putIfAbsent(TUNNELING_DISABLED_SCHEMES, "");
        }
    }

    /**
     * Answers the proxy's challenge, once per request; a server asking for credentials gets none.
     *
     * <p>The JDK asks again after each rejected answer, up to three more times, so answering every ask would send
     * a wrong password four times a request, and a proxy counting failed logins locks the account.
     */
    private static final class ProxyAuthenticator extends Authenticator {

        private final ProxySettings proxy;
        private final Set<String> answered = ConcurrentHashMap.newKeySet();
        private final Set<String> rejected = ConcurrentHashMap.newKeySet();

        ProxyAuthenticator(ProxySettings proxy) {
            this.proxy = proxy;
        }

        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            if (getRequestorType() != RequestorType.PROXY) {
                return null;
            }
            String request = String.valueOf(getRequestingURL());
            if (answered.add(request)) {
                return new PasswordAuthentication(proxy.username(), proxy.password().toCharArray());
            }
            rejected.add(request);
            return null;
        }

        boolean rejected(URI request) {
            return rejected.contains(request.toString());
        }

        void finished(URI request) {
            answered.remove(request.toString());
            rejected.remove(request.toString());
        }
    }

    private void finished(HttpRequest request) {
        if (authenticator != null) {
            authenticator.finished(request.uri());
        }
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
            return new Reply.Failed(Reply.Problem.BAD_URL, describe(malformed));
        }

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String retryAfter = response.headers().firstValue("retry-after").orElse(null);
            return new Reply.Answered(response.statusCode(), response.body(), retryAfter);
        } catch (HttpTimeoutException timedOut) {
            return new Reply.TimedOut();
        } catch (IOException failed) {
            return authenticator != null && authenticator.rejected(request.uri())
                    ? new Reply.Failed(Reply.Problem.PROXY_REJECTED, describe(failed))
                    : failure(failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Reply.Failed(Reply.Problem.OTHER, "interrupted");
        } finally {
            finished(request);
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
            finished(request);
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

    static Reply.Failed failure(IOException failed) {
        for (Throwable cause = failed; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnresolvedAddressException || cause instanceof UnknownHostException) {
                return new Reply.Failed(Reply.Problem.UNRESOLVED, describe(cause));
            }
            if (cause instanceof SSLException) {
                return new Reply.Failed(Reply.Problem.TLS, certificateProblem(cause).orElse(deepest(cause)));
            }
        }
        String message = describe(failed);
        Matcher tunnel = TUNNEL_FAILED.matcher(message);
        if (tunnel.find()) {
            return new Reply.Failed(Reply.Problem.TUNNEL, message, Integer.valueOf(tunnel.group(1)));
        }
        for (Throwable cause = failed; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException) {
                return new Reply.Failed(Reply.Problem.UNCONNECTED, messageIn(failed)
                        .map(text -> text.toLowerCase(Locale.ROOT)).orElse("connection refused"));
            }
        }
        return new Reply.Failed(Reply.Problem.OTHER, deepest(failed));
    }

    private static Optional<String> certificateProblem(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CertificateExpiredException) {
                return Optional.of("its certificate has expired");
            }
            if (cause instanceof CertificateNotYetValidException) {
                return Optional.of("its certificate is not valid yet");
            }
        }
        return Optional.empty();
    }

    private static String deepest(Throwable failure) {
        return messageIn(failure).orElseGet(() -> describe(failure));
    }

    private static Optional<String> messageIn(Throwable failure) {
        String message = null;
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && !cause.getMessage().isEmpty()) {
                message = cause.getMessage();
            }
        }
        return Optional.ofNullable(message);
    }

    private static String describe(Throwable cause) {
        String message = cause.getMessage();
        return message == null || message.isEmpty() ? cause.getClass().getSimpleName() : message;
    }
}
