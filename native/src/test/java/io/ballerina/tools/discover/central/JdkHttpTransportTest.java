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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password)
                .getBytes(StandardCharsets.UTF_8));
    }

    private record Proxied(HttpTransport.Reply reply, List<String> requests, List<String> credentials) { }

    // A plain-HTTP origin is reached through the proxy without a tunnel, so the proxy sees the absolute URI.
    private static Proxied throughProxy(String username, String password) throws IOException {
        List<String> requests = new ArrayList<>();
        List<String> credentials = new ArrayList<>();
        String expected = basic("alice", "secret");
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
        Assert.assertEquals(proxied.credentials().get(proxied.credentials().size() - 1), basic("alice", "secret"));
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

    /**
     * A proxy that reads one {@code CONNECT} per connection, answers {@code refusal} (a 407 by default) unless it
     * carries {@code expected}, and otherwise opens a tunnel it closes at once: enough to see what the client sent,
     * with no origin behind it.
     */
    private static final class ConnectProxy implements AutoCloseable {

        private final ServerSocket socket = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
        private final List<String> requests = new ArrayList<>();
        private final Thread acceptor;

        ConnectProxy(String expected) throws IOException {
            this(expected, "HTTP/1.1 407 Proxy Authentication Required\r\n"
                    + "Proxy-Authenticate: Basic realm=\"test\"\r\nContent-Length: 0\r\n\r\n");
        }

        ConnectProxy(String expected, String refusal) throws IOException {
            acceptor = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (Socket connection = socket.accept()) {
                        String head = readHead(connection.getInputStream());
                        String request = head.lines().findFirst().orElse("");
                        String credential = head.lines()
                                .filter(line -> line.toLowerCase(Locale.ROOT).startsWith("proxy-authorization:"))
                                .map(line -> line.substring(line.indexOf(':') + 1).trim())
                                .findFirst().orElse("");
                        synchronized (requests) {
                            requests.add(request + " " + credential);
                        }
                        String reply = expected.equals(credential)
                                ? "HTTP/1.1 200 Connection Established\r\n\r\n"
                                : refusal;
                        connection.getOutputStream().write(reply.getBytes(StandardCharsets.ISO_8859_1));
                        connection.getOutputStream().flush();
                    } catch (IOException closed) {
                        // The test is over, or the client gave up on this connection.
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private static String readHead(InputStream in) throws IOException {
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int matched = 0;
            byte[] end = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
            for (int next = in.read(); next >= 0; next = in.read()) {
                head.write(next);
                matched = next == end[matched] ? matched + 1 : (next == end[0] ? 1 : 0);
                if (matched == end.length) {
                    break;
                }
            }
            return head.toString(StandardCharsets.ISO_8859_1);
        }

        int port() {
            return socket.getLocalPort();
        }

        List<String> requests() {
            synchronized (requests) {
                return List.copyOf(requests);
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private record Tunnelled(HttpTransport.Reply reply, List<String> requests) { }

    private static Tunnelled tunnel(String password) throws IOException {
        try (ConnectProxy proxy = new ConnectProxy(basic("alice", "secret"))) {
            ProxySettings settings = new ProxySettings("127.0.0.1", proxy.port(), "alice", password);
            HttpTransport.Reply reply = new JdkHttpTransport(LIMIT, settings)
                    .get("https://central.invalid/2.0/docs/ballerina/http", 5000);
            return new Tunnelled(reply, proxy.requests());
        }
    }

    // The tunnel opened is closed at once, so the request fails after it; the JDK may retry it once, with the
    // credentials it now holds.
    @Test
    public void anHttpsRequestAnswersTheTunnelsChallengeWithTheConfiguredCredentials() throws IOException {
        List<String> requests = tunnel("secret").requests();
        Assert.assertTrue(requests.size() >= 2, requests.toString());
        Assert.assertEquals(requests.get(0), "CONNECT central.invalid:443 HTTP/1.1 ");
        Assert.assertTrue(requests.subList(1, requests.size()).stream()
                .allMatch(("CONNECT central.invalid:443 HTTP/1.1 " + basic("alice", "secret"))::equals),
                requests.toString());
    }

    @Test
    public void aRejectedPasswordIsSentOnceARequestAndSaysSo() throws IOException {
        Tunnelled tunnelled = tunnel("wrong");
        Assert.assertEquals(tunnelled.requests(), List.of(
                "CONNECT central.invalid:443 HTTP/1.1 ",
                "CONNECT central.invalid:443 HTTP/1.1 " + basic("alice", "wrong")));
        Assert.assertEquals(((HttpTransport.Reply.Failed) tunnelled.reply()).problem(),
                HttpTransport.Reply.Problem.PROXY_REJECTED, tunnelled.reply().toString());
    }

    @Test
    public void aSecondRequestSendsTheRejectedPasswordOnceMore() throws IOException {
        try (ConnectProxy proxy = new ConnectProxy(basic("alice", "secret"))) {
            JdkHttpTransport transport =
                    new JdkHttpTransport(LIMIT, new ProxySettings("127.0.0.1", proxy.port(), "alice", "wrong"));
            transport.get("https://central.invalid/2.0/docs/ballerina/http", 5000);
            HttpTransport.Reply second = transport.get("https://central.invalid/2.0/docs/ballerina/http", 5000);
            Assert.assertEquals(((HttpTransport.Reply.Failed) second).problem(),
                    HttpTransport.Reply.Problem.PROXY_REJECTED);
            Assert.assertEquals(proxy.requests().stream().filter(request -> request.endsWith(basic("alice", "wrong")))
                    .count(), 2, proxy.requests().toString());
        }
    }

    @Test
    public void aProxyThatCannotOpenTheTunnelIsReportedWithItsStatus() throws IOException {
        String badGateway = "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n";
        try (ConnectProxy proxy = new ConnectProxy("never", badGateway)) {
            ProxySettings settings = new ProxySettings("127.0.0.1", proxy.port(), "", "");
            HttpTransport.Reply reply =
                    new JdkHttpTransport(LIMIT, settings).get("https://central.invalid/2.0/docs/ballerina/http", 5000);
            Assert.assertEquals(reply, new HttpTransport.Reply.Failed(HttpTransport.Reply.Problem.TUNNEL, "502"));
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

    @Test
    public void basicCredentialsAreAllowedInTunnelsOnlyForAProxyWithCredentialsAndNeverOverAUserValue() {
        Properties none = new Properties();
        JdkHttpTransport.allowBasicInTunnels(none, new ProxySettings("127.0.0.1", 3128, "", ""));
        Assert.assertNull(none.getProperty(JdkHttpTransport.TUNNELING_DISABLED_SCHEMES));

        Properties unset = new Properties();
        JdkHttpTransport.allowBasicInTunnels(unset, new ProxySettings("127.0.0.1", 3128, "alice", "secret"));
        Assert.assertEquals(unset.getProperty(JdkHttpTransport.TUNNELING_DISABLED_SCHEMES), "");

        Properties user = new Properties();
        user.setProperty(JdkHttpTransport.TUNNELING_DISABLED_SCHEMES, "Basic");
        JdkHttpTransport.allowBasicInTunnels(user, new ProxySettings("127.0.0.1", 3128, "alice", "secret"));
        Assert.assertEquals(user.getProperty(JdkHttpTransport.TUNNELING_DISABLED_SCHEMES), "Basic");
    }
}
