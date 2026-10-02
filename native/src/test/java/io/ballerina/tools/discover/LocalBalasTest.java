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

package io.ballerina.tools.discover;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.ballerina.tools.discover.cache.DocsCache;
import io.ballerina.tools.discover.central.Bala;
import io.ballerina.tools.discover.central.CentralClient;
import io.ballerina.tools.discover.central.CentralRepository;
import io.ballerina.tools.discover.central.HttpOptions;
import io.ballerina.tools.discover.central.JdkHttpTransport;
import io.ballerina.tools.discover.central.LocalBalas;
import io.ballerina.tools.discover.central.ModuleSources;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Module sources read from the packages already on this machine — the Ballerina home repository, then the
 * distribution's own — before Central's archive is downloaded, and the download itself kept bounded. Every path
 * here is a temporary directory this test makes and removes; no real home or distribution is read.
 *
 * @since 0.1.0
 */
public class LocalBalasTest {

    private static final QualifiedName HTTP = QualifiedName.parse("ballerina/http").value();

    private static final Version VERSION = Version.parse("2.16.6").value();

    private static final CentralClient.ResolvedVersion RESOLVED = new CentralClient.ResolvedVersion(VERSION, false);

    private static final String BALA_URL = "https://files.example/http.bala";

    private final List<Path> made = new ArrayList<>();

    @AfterMethod
    public void removeTemporaryDirectories() throws IOException {
        for (Path root : made) {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        made.clear();
    }

    private Path temporary() throws IOException {
        Path root = Files.createTempDirectory("discover-local");
        made.add(root);
        return root;
    }

    private static Map<String, String> httpSources() {
        return FixtureCorpus.recordedSources("ballerina__http").orElseThrow();
    }

    /** {@code ballerina/http}'s module unpacked under {@code baladir}, as {@code bal pull} or a distribution has it. */
    private static void unpack(Path baladir, String version, String platform, String module) throws IOException {
        Path moduleDir = baladir.resolve("ballerina/http").resolve(version).resolve(platform).resolve("modules")
                .resolve(module);
        Files.createDirectories(moduleDir);
        for (Map.Entry<String, String> source : httpSources().entrySet()) {
            Files.writeString(moduleDir.resolve(source.getKey()), source.getValue());
        }
    }

    private Path home(String version, String platform, String module) throws IOException {
        Path home = temporary();
        unpack(home.resolve("repositories/central.ballerina.io/bala"), version, platform, module);
        return home;
    }

    private Path distribution(String version) throws IOException {
        Path distribution = temporary();
        unpack(distribution.resolve("repo/bala"), version, "java21", "http");
        return distribution;
    }

    private static byte[] zip(Map<String, byte[]> entries) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
    }

    private static byte[] bala() {
        Map<String, byte[]> entries = new java.util.TreeMap<>();
        httpSources().forEach((file, text) ->
                entries.put("modules/http/" + file, text.getBytes(StandardCharsets.UTF_8)));
        return zip(entries);
    }

    private static FakeTransport central() {
        JsonObject entry = new JsonObject();
        entry.addProperty("balaURL", BALA_URL);
        return FakeTransport.routing(url -> url.endsWith("/registry/packages/ballerina/http/2.16.6")
                ? FakeTransport.ok(entry.toString())
                : FakeTransport.status(404))
                .downloading(url -> BALA_URL.equals(url) ? Optional.of(bala()) : Optional.empty());
    }

    private static HttpOptions options(FakeTransport transport) {
        return HttpOptions.builder().transport(transport).cache(DocsCache.NULL).baseDelayMs(1)
                .sleeper(millis -> {
                })
                .build();
    }

    private static Optional<Map<String, String>> fetch(
            List<ModuleSources> local, CentralClient.ResolvedVersion resolved, FakeTransport transport) {
        return CentralRepository.withLocalSources(local).fetchModuleSources(HTTP, resolved, "http",
                options(transport));
    }

    @Test
    public void anExactVersionInTheHomeIsReadWithoutTheNetwork() throws IOException {
        Assert.assertEquals(fetch(List.of(LocalBalas.homeRepository(home("2.16.6", "java21", "http"))), RESOLVED,
                FakeTransport.never()), Optional.of(httpSources()));
    }

    @Test
    public void aPlatformOtherThanJava21IsFoundToo() throws IOException {
        Assert.assertEquals(fetch(List.of(LocalBalas.homeRepository(home("2.16.6", "any", "http"))), RESOLVED,
                FakeTransport.never()), Optional.of(httpSources()));
    }

    @Test
    public void theDistributionsOwnRepositoryIsReadWhenTheHomeLacksTheVersion() throws IOException {
        List<ModuleSources> local = List.of(LocalBalas.homeRepository(home("2.16.5", "java21", "http")),
                LocalBalas.distributionRepository(distribution("2.16.6")));
        Assert.assertEquals(fetch(local, RESOLVED, FakeTransport.never()), Optional.of(httpSources()));
    }

    @Test
    public void anotherVersionAnywhereLocalStillDownloads() throws IOException {
        FakeTransport transport = central();
        List<ModuleSources> local = List.of(LocalBalas.homeRepository(home("2.16.5", "java21", "http")),
                LocalBalas.distributionRepository(distribution("2.16.7")));
        Assert.assertEquals(fetch(local, RESOLVED, transport), Optional.of(httpSources()));
        Assert.assertTrue(transport.urls().contains(BALA_URL), "the archive was downloaded instead");
    }

    @Test
    public void aMissingModuleFallsBackToTheDownload() throws IOException {
        FakeTransport transport = central();
        Assert.assertEquals(fetch(List.of(LocalBalas.homeRepository(home("2.16.6", "java21", "http.httpscerr"))),
                RESOLVED, transport), Optional.of(httpSources()));
        Assert.assertTrue(transport.urls().contains(BALA_URL));
    }

    @Test
    public void anUnreadableHomeFallsBackToTheDownload() throws IOException {
        Path home = temporary();
        Path version = home.resolve("repositories/central.ballerina.io/bala/ballerina/http/2.16.6");
        Files.createDirectories(version.getParent());
        Files.writeString(version, "a file where the version directory belongs");
        FakeTransport transport = central();
        Assert.assertEquals(fetch(List.of(LocalBalas.homeRepository(home)), RESOLVED, transport),
                Optional.of(httpSources()));
        Assert.assertTrue(transport.urls().contains(BALA_URL));
    }

    @Test
    public void anUnverifiedVersionNeverReachesTheNetwork() throws IOException {
        CentralClient.ResolvedVersion offline = new CentralClient.ResolvedVersion(VERSION, true);
        Assert.assertTrue(fetch(List.of(), offline, FakeTransport.never()).isEmpty());
        Assert.assertEquals(fetch(List.of(LocalBalas.homeRepository(home("2.16.6", "java21", "http"))), offline,
                FakeTransport.never()), Optional.of(httpSources()), "a local copy is still read");
    }

    @Test
    public void theRegistryIsAskedOnceWithoutRetrying() {
        FakeTransport transport = FakeTransport.always(FakeTransport.status(503));
        Assert.assertTrue(fetch(List.of(), RESOLVED, transport).isEmpty());
        Assert.assertEquals(transport.calls(), 1);
    }

    @Test
    public void anArchiveOrASourceFileOverItsLimitIsNoAnswer() {
        byte[] bala = bala();
        Assert.assertTrue(Bala.moduleSources(new ByteArrayInputStream(bala), "http", 100,
                Bala.MAX_SOURCE_BYTES).isEmpty());
        Assert.assertTrue(Bala.moduleSources(new ByteArrayInputStream(bala), "http", Bala.MAX_ARCHIVE_BYTES, 100)
                .isEmpty());
        Assert.assertEquals(Bala.moduleSources(new ByteArrayInputStream(bala), "http"), Optional.of(httpSources()));
    }

    @Test
    public void aSlowBodyIsCutOffAtTheTimeout() throws IOException {
        CountDownLatch released = new CountDownLatch(1);
        assertCutOff(released, exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream body = exchange.getResponseBody()) {
                for (int index = 0; index < 50 && !released.await(100, TimeUnit.MILLISECONDS); index++) {
                    body.write(new byte[16]);
                    body.flush();
                }
            } catch (IOException | InterruptedException closed) {
                // The client gave up, which is what this test is waiting for.
            }
        });
    }

    @Test
    public void aBodyThatStallsAfterItsHeadersIsCutOffAtTheTimeout() throws IOException {
        CountDownLatch released = new CountDownLatch(1);
        assertCutOff(released, exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(new byte[16]);
                body.flush();
                released.await(30, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException closed) {
                // The client gave up, which is what this test is waiting for.
            }
        });
    }

    @Test
    public void aWholeBodyInTimeIsTheAnswerAndAnErrorStatusIsNone() throws IOException {
        byte[] bala = bala();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/http.bala", exchange -> {
            exchange.sendResponseHeaders(200, bala.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(bala);
            }
        });
        server.createContext("/missing.bala", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Optional<InputStream> found = new JdkHttpTransport().openStream(base + "/http.bala", 5000);
            Assert.assertTrue(found.isPresent());
            try (InputStream stream = found.get()) {
                Assert.assertEquals(stream.readAllBytes(), bala);
            }
            Assert.assertTrue(new JdkHttpTransport().openStream(base + "/missing.bala", 5000).isEmpty());
        } finally {
            server.stop(0);
        }
    }

    private static void assertCutOff(CountDownLatch released, HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        server.createContext("/slow.bala", handler);
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/slow.bala";
            long started = System.nanoTime();
            Optional<InputStream> body = new JdkHttpTransport().openStream(url, 300);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            Assert.assertTrue(body.isEmpty(), "a body not complete by the deadline is no answer");
            Assert.assertTrue(elapsedMs < 2000, "took " + elapsedMs + " ms against a 300 ms deadline");
        } finally {
            released.countDown();
            server.stop(0);
            handlers.shutdownNow();
        }
    }
}
