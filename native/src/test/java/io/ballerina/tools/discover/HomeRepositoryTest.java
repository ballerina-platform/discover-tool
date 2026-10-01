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
import io.ballerina.tools.discover.cache.DocsCache;
import io.ballerina.tools.discover.central.CentralClient;
import io.ballerina.tools.discover.central.CentralRepository;
import io.ballerina.tools.discover.central.HomeRepository;
import io.ballerina.tools.discover.central.HttpOptions;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Module sources read from the user's Ballerina home before any archive is downloaded — an injected home, never
 * the real one (the build also points {@code BALLERINA_HOME_DIR} at a scratch directory for every test).
 *
 * @since 0.1.0
 */
public class HomeRepositoryTest {

    private static final QualifiedName HTTP = QualifiedName.parse("ballerina/http").value();

    private static final Version VERSION = Version.parse("2.16.6").value();

    private static final String BALA_URL = "https://files.example/http.bala";

    private static Map<String, String> httpSources() {
        return FixtureCorpus.recordedSources("ballerina__http").orElseThrow();
    }

    /** A home with ballerina/http unpacked as {@code bal pull} leaves it, under {@code platform}. */
    private static Path homeWith(String version, String platform, String module) throws IOException {
        Path home = Files.createTempDirectory("discover-home");
        Path moduleDir = home.resolve("repositories/central.ballerina.io/bala/ballerina/http")
                .resolve(version).resolve(platform).resolve("modules").resolve(module);
        Files.createDirectories(moduleDir);
        for (Map.Entry<String, String> source : httpSources().entrySet()) {
            Files.writeString(moduleDir.resolve(source.getKey()), source.getValue());
        }
        return home;
    }

    private static byte[] bala() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> source : httpSources().entrySet()) {
                zip.putNextEntry(new ZipEntry("modules/http/" + source.getKey()));
                zip.write(source.getValue().getBytes(StandardCharsets.UTF_8));
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
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

    private static Optional<Map<String, String>> fetch(Path home, FakeTransport transport) {
        return CentralRepository.withLocalSources(HomeRepository.at(home))
                .fetchModuleSources(HTTP, VERSION, "http", options(transport));
    }

    @Test
    public void anExactVersionAlreadyInTheHomeIsReadWithoutTheNetwork() throws IOException {
        Optional<Map<String, String>> sources = fetch(homeWith("2.16.6", "java21", "http"), FakeTransport.never());
        Assert.assertEquals(sources, Optional.of(httpSources()));
    }

    @Test
    public void aPlatformOtherThanJava21IsFoundToo() throws IOException {
        Optional<Map<String, String>> sources = fetch(homeWith("2.16.6", "any", "http"), FakeTransport.never());
        Assert.assertEquals(sources, Optional.of(httpSources()));
    }

    @Test
    public void anotherVersionInTheHomeIsNotUsed() throws IOException {
        FakeTransport transport = central();
        Assert.assertEquals(fetch(homeWith("2.16.5", "java21", "http"), transport), Optional.of(httpSources()));
        Assert.assertTrue(transport.urls().contains(BALA_URL), "the archive was downloaded instead");
    }

    @Test
    public void aMissingModuleFallsBackToTheDownload() throws IOException {
        FakeTransport transport = central();
        Assert.assertEquals(fetch(homeWith("2.16.6", "java21", "http.httpscerr"), transport),
                Optional.of(httpSources()));
        Assert.assertTrue(transport.urls().contains(BALA_URL));
    }

    @Test
    public void anUnreadableHomeFallsBackToTheDownload() throws IOException {
        Path home = Files.createTempDirectory("discover-home");
        Path version = home.resolve("repositories/central.ballerina.io/bala/ballerina/http/2.16.6");
        Files.createDirectories(version.getParent());
        Files.writeString(version, "a file where the version directory belongs");
        FakeTransport transport = central();
        Assert.assertEquals(fetch(home, transport), Optional.of(httpSources()));
        Assert.assertTrue(transport.urls().contains(BALA_URL));
    }

    @Test
    public void theHomeServesNoVersionAndNoDocs() throws IOException {
        HomeRepository home = HomeRepository.at(homeWith("2.16.6", "java21", "http"));
        HttpOptions options = options(FakeTransport.never());
        Assert.assertFalse(home.resolveVersion(HTTP, options).isOk());
        Assert.assertFalse(home.fetchDocs(HTTP, new CentralClient.ResolvedVersion(VERSION, false), options).isOk());
    }

    @Test
    public void theEnvironmentsHomeIsBalsOwn() {
        // The build points BALLERINA_HOME_DIR at an empty scratch directory, so this never reads the real home.
        Assert.assertTrue(HomeRepository.fromEnvironment().fetchModuleSources(
                HTTP, VERSION, "http", options(FakeTransport.never())).isEmpty());
        Assert.assertTrue(HomeRepository.fromEnvironment().describe().contains("test-ballerina-home"),
                HomeRepository.fromEnvironment().describe());
    }
}
