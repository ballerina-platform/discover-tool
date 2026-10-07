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
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A download past the transport's limit is no answer, whether the server declares its length up front or only
 * reveals it by sending too much.
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
            return new JdkHttpTransport(LIMIT).openStream(url, timeoutMs);
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
}
