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

import com.sun.net.httpserver.HttpServer;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLHandshakeException;

/**
 * A download past the transport's limit is no answer, whether the server declares its length up front or only
 * reveals it by sending too much. A configured proxy carries every request, with credentials only when asked.
 *
 * @since 0.1.0
 */
public class JdkHttpTransportTest {

    private static final int LIMIT = 1024;

    // A declared length past `bytes` holds the response open, so a client waiting on the full body hits the
    // deadline.
    private static Optional<InputStream> download(int status, long declaredLength, int bytes, long timeoutMs)
            throws IOException {
        CountDownLatch released = new CountDownLatch(1);
        ExecutorService handlers = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(handlers);
        server.createContext("/package.bala", exchange -> {
            exchange.sendResponseHeaders(status, declaredLength);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(new byte[bytes]);
                body.flush();
                if (declaredLength > bytes) {
                    released.await(30, TimeUnit.SECONDS);
                }
            } catch (IOException | InterruptedException closed) {
                // The client closed the connection without reading the rest, as a refused body does.
            }
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/package.bala";
            return new JdkHttpTransport(LIMIT, null).openStream(url, timeoutMs);
        } finally {
            released.countDown();
            server.stop(0);
            handlers.shutdownNow();
        }
    }

    private static Optional<InputStream> download(long declaredLength, int bytes) throws IOException {
        return download(200, declaredLength, bytes, 5000);
    }

    @Test
    public void aDeclaredLengthPastTheLimitIsNoAnswerWithoutReadingTheBody() throws IOException {
        long started = System.nanoTime();
        Assert.assertTrue(download(200, LIMIT * 64L, 16, 30_000).isEmpty());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        Assert.assertTrue(elapsedMs < 2000, "took " + elapsedMs + " ms; the declared length alone should refuse it");
    }

    @Test
    public void aChunkedBodyPastTheLimitIsNoAnswer() throws IOException {
        Assert.assertTrue(download(0, LIMIT * 4).isEmpty());
    }

    @Test
    public void aBodyAtTheLimitIsTheAnswer() throws IOException {
        Optional<InputStream> body = download(0, LIMIT);
        Assert.assertTrue(body.isPresent());
        try (InputStream stream = body.get()) {
            Assert.assertEquals(stream.readAllBytes().length, LIMIT);
        }
    }

    @Test
    public void aDeclaredLengthAtTheLimitIsTheAnswer() throws IOException {
        Optional<InputStream> body = download(LIMIT, LIMIT);
        Assert.assertTrue(body.isPresent());
        try (InputStream stream = body.get()) {
            Assert.assertEquals(stream.readAllBytes().length, LIMIT);
        }
    }

    @Test
    public void anErrorStatusIsNoAnswer() throws IOException {
        Assert.assertTrue(download(404, 16, 16, 5000).isEmpty());
    }

    private record Proxied(HttpTransport.Reply reply, List<String> requests, List<String> credentials) { }

    // A plain-HTTP origin is reached through the proxy without a tunnel, so the proxy sees the absolute URI.
    private static Proxied throughProxy(String username, String password) throws IOException {
        List<String> requests = new ArrayList<>();
        List<String> credentials = new ArrayList<>();
        String expected = ConnectProxy.basic("alice", "secret");
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            String credential = exchange.getRequestHeaders().getFirst("Proxy-Authorization");
            synchronized (requests) {
                requests.add(exchange.getRequestURI().toString());
                credentials.add(credential == null ? "" : credential);
            }
            byte[] body = "{\"via\":\"proxy\"}".getBytes(StandardCharsets.UTF_8);
            if (!expected.equals(credential)) {
                exchange.getResponseHeaders().add("Proxy-Authenticate", "Basic realm=\"test\"");
                exchange.sendResponseHeaders(407, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        proxy.start();
        try {
            ProxySettings settings = new ProxySettings("127.0.0.1", proxy.getAddress().getPort(), username, password);
            HttpTransport.Reply reply = new JdkHttpTransport(LIMIT, settings)
                    .get("http://central.invalid/2.0/docs/ballerina/http", 5000);
            synchronized (requests) {
                return new Proxied(reply, List.copyOf(requests), List.copyOf(credentials));
            }
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    public void aRequestGoesThroughTheProxyAndAnswersItsChallengeWithTheConfiguredCredentials() throws IOException {
        Proxied proxied = throughProxy("alice", "secret");
        Assert.assertEquals(proxied.reply(), new HttpTransport.Reply.Answered(200, "{\"via\":\"proxy\"}", null));
        Assert.assertTrue(proxied.requests().stream()
                .allMatch("http://central.invalid/2.0/docs/ballerina/http"::equals), proxied.requests().toString());
        Assert.assertEquals(proxied.credentials().get(proxied.credentials().size() - 1),
                ConnectProxy.basic("alice", "secret"));
    }

    @Test
    public void aProxyWithoutCredentialsIsSentNone() throws IOException {
        Proxied proxied = throughProxy("", "");
        Assert.assertEquals(proxied.requests(), List.of("http://central.invalid/2.0/docs/ballerina/http"));
        Assert.assertEquals(proxied.credentials(), List.of(""));
        Assert.assertTrue(proxied.reply() instanceof HttpTransport.Reply.Answered answered
                && answered.status() == 407, proxied.reply().toString());
    }

    @Test
    public void aServerAskingForCredentialsIsSentNone() throws IOException {
        List<String> authorizations = new ArrayList<>();
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.createContext("/", exchange -> {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            synchronized (authorizations) {
                authorizations.add(authorization == null ? "" : authorization);
            }
            exchange.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"origin\"");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        proxy.start();
        try {
            ProxySettings settings = new ProxySettings("127.0.0.1", proxy.getAddress().getPort(), "alice", "secret");
            HttpTransport.Reply reply = new JdkHttpTransport(LIMIT, settings)
                    .get("http://central.invalid/2.0/docs/ballerina/http", 5000);
            Assert.assertFalse(reply instanceof HttpTransport.Reply.Answered answered && answered.isOk(),
                    reply.toString());
            synchronized (authorizations) {
                Assert.assertEquals(authorizations, List.of(""));
            }
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    public void aProxyThatCannotOpenTheTunnelIsReportedWithItsStatus() throws IOException {
        String badGateway = "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n";
        try (ConnectProxy proxy = new ConnectProxy("never", badGateway)) {
            ProxySettings settings = new ProxySettings("127.0.0.1", proxy.port(), "", "");
            HttpTransport.Reply reply =
                    new JdkHttpTransport(LIMIT, settings).get("https://central.invalid/2.0/docs/ballerina/http", 5000);
            HttpTransport.Reply.Failed failed = (HttpTransport.Reply.Failed) reply;
            Assert.assertEquals(failed.problem(), HttpTransport.Reply.Problem.TUNNEL);
            Assert.assertEquals(failed.status(), Integer.valueOf(502));
        }
    }

    @Test
    public void aProxyThatIsNotListeningIsAConnectionNotMade() throws IOException {
        int port;
        try (ServerSocket closed = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = closed.getLocalPort();
        }
        HttpTransport.Reply reply = new JdkHttpTransport(LIMIT, new ProxySettings("127.0.0.1", port, "", ""))
                .get("https://central.invalid/2.0/docs/ballerina/http", 5000);
        Assert.assertEquals(((HttpTransport.Reply.Failed) reply).problem(), HttpTransport.Reply.Problem.UNCONNECTED,
                reply.toString());
    }

    private static HttpTransport.Reply.Failed classified(Throwable cause) {
        return JdkHttpTransport.failure(new IOException("request failed", cause));
    }

    @Test
    public void eachTransportFailureIsClassifiedByItsCause() {
        Assert.assertEquals(classified(new UnresolvedAddressException()).problem(),
                HttpTransport.Reply.Problem.UNRESOLVED);
        Assert.assertEquals(classified(new UnknownHostException("central.invalid")).problem(),
                HttpTransport.Reply.Problem.UNRESOLVED);
        Assert.assertEquals(classified(new ConnectException("Connection refused")),
                new HttpTransport.Reply.Failed(HttpTransport.Reply.Problem.UNCONNECTED, "connection refused"));
        Assert.assertEquals(classified(new ClosedChannelException()).problem(), HttpTransport.Reply.Problem.OTHER,
                "a connection made and then closed was not refused");
        Assert.assertEquals(classified(new SSLHandshakeException("PKIX path building failed")),
                new HttpTransport.Reply.Failed(HttpTransport.Reply.Problem.TLS, "PKIX path building failed"));
    }

    @Test
    public void aCertificateOutsideItsValidityIsNamedAsSuch() {
        SSLHandshakeException expired = new SSLHandshakeException("handshake failed");
        expired.initCause(new CertificateExpiredException("NotAfter: 2020"));
        Assert.assertEquals(classified(expired),
                new HttpTransport.Reply.Failed(HttpTransport.Reply.Problem.TLS, "its certificate has expired"));

        SSLHandshakeException early = new SSLHandshakeException("handshake failed");
        early.initCause(new CertificateNotYetValidException("NotBefore: 2099"));
        Assert.assertEquals(classified(early),
                new HttpTransport.Reply.Failed(HttpTransport.Reply.Problem.TLS, "its certificate is not valid yet"));
    }
}
