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
import com.google.gson.JsonParser;
import io.ballerina.tools.discover.cache.DiskCache;
import io.ballerina.tools.discover.cache.DocsCache;
import io.ballerina.tools.discover.central.CentralClient;
import io.ballerina.tools.discover.central.CentralRepository;
import io.ballerina.tools.discover.central.HttpOptions;
import io.ballerina.tools.discover.central.PackageRepository;
import io.ballerina.tools.discover.central.schema.CentralDocs;
import io.ballerina.tools.discover.model.ObjectInclusions;
import io.ballerina.tools.discover.source.SourceInclusions;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Inclusions read out of a package's published source: the parse, the archive it comes from, and the cache that
 * keeps the derived answer so the archive is fetched once per version.
 *
 * @since 0.1.0
 */
public class SourceInclusionsTest {

    private static final QualifiedName HTTP = QualifiedName.parse("ballerina/http").value();

    private static final Version VERSION = Version.parse("2.16.6").value();

    private static final CentralClient.ResolvedVersion RESOLVED = new CentralClient.ResolvedVersion(VERSION, false);

    /** Central with nothing local to look in — no test here reads a real Ballerina home or distribution. */
    private static final CentralRepository CENTRAL = CentralRepository.withLocalSources(List.of());

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

    private DocsCache diskCache() throws IOException {
        Path root = Files.createTempDirectory("discover-inclusions");
        made.add(root);
        return DiskCache.at(root, 0700);
    }

    private static final String BALA_URL = "https://files.example/ballerina-http-java21-2.16.6.bala";

    private static Map<String, String> httpSources() {
        return FixtureCorpus.recordedSources("ballerina__http").orElseThrow();
    }

    private static byte[] bala(Map<String, String> sources) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("bala.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("modules/http.httpscerr/init.bal"));
            zip.write("public type Service distinct service object {};".getBytes(StandardCharsets.UTF_8));
            for (Map.Entry<String, String> source : sources.entrySet()) {
                zip.putNextEntry(new ZipEntry("modules/http/" + source.getKey()));
                zip.write(source.getValue().getBytes(StandardCharsets.UTF_8));
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
    }

    private static FakeTransport central(Optional<byte[]> archive) {
        JsonObject entry = new JsonObject();
        entry.addProperty("balaURL", BALA_URL);
        return FakeTransport.routing(url -> url.endsWith("/registry/packages/ballerina/http/2.16.6")
                ? FakeTransport.ok(entry.toString())
                : FakeTransport.status(404))
                .downloading(url -> BALA_URL.equals(url) ? archive : Optional.empty());
    }

    private static HttpOptions options(FakeTransport transport, DocsCache cache) {
        return HttpOptions.builder()
                .transport(transport)
                .cache(cache)
                .baseDelayMs(1)
                .sleeper(millis -> {
                })
                .build();
    }

    @Test
    public void httpsRecordedSourceShowsWhichServiceTypesIncludeService() {
        ObjectInclusions inclusions = SourceInclusions.parse(httpSources()).orElseThrow();
        Assert.assertEquals(inclusions.byType().get("ServiceContract"), List.of("Service"));
        Assert.assertEquals(inclusions.byType().get("InterceptableService"), List.of("Service"));
        for (String interceptor : List.of("RequestInterceptor", "ResponseInterceptor", "RequestErrorInterceptor",
                "ResponseErrorInterceptor")) {
            Assert.assertFalse(inclusions.byType().containsKey(interceptor), interceptor);
        }
        Assert.assertFalse(inclusions.byType().containsKey("DefaultErrorInterceptor"), "a class is not a type");
    }

    @Test
    public void everyObjectFormAndQualifiedNameIsRead() {
        ObjectInclusions inclusions = SourceInclusions.parse(Map.of("types.bal", """
                import ballerinax/cdc;
                public type Plain object { *Base; };
                public type Wrapped readonly & (distinct service object { *cdc:Service; });
                public type Record record {| *Base; |};
                """)).orElseThrow();
        Assert.assertEquals(inclusions.byType(),
                Map.of("Plain", List.of("Base"), "Wrapped", List.of("ballerinax/cdc:Service")));
    }

    /** What a prefix stands for is the file's own import, alias included; a prefix nothing imports is dropped. */
    @Test
    public void aPrefixIsResolvedThroughItsFilesImports() {
        ObjectInclusions inclusions = SourceInclusions.parse(Map.of(
                "a.bal", """
                        import ballerinax/cdc as c;
                        public type Audit distinct service object { *c:Service; };
                        """,
                "b.bal", """
                        import ballerinax/googleapis.gmail;
                        public type Mail service object { *gmail:Service; *unknown:Service; };
                        """)).orElseThrow();
        Assert.assertEquals(inclusions.byType(), Map.of(
                "Audit", List.of("ballerinax/cdc:Service"),
                "Mail", List.of("ballerinax/googleapis.gmail:Service")));
    }

    @Test
    public void theArchiveIsReadForOneModuleOnly() {
        Optional<Map<String, String>> sources = CentralClient.fetchModuleSources(
                HTTP, VERSION, "http", options(central(Optional.of(bala(httpSources()))), DocsCache.NULL));
        Assert.assertEquals(sources.map(Map::keySet), Optional.of(httpSources().keySet()));
    }

    @Test
    public void anUnreachableOrUnreadableArchiveIsNoSource() {
        Assert.assertTrue(CentralClient.fetchModuleSources(
                HTTP, VERSION, "http", options(central(Optional.empty()), DocsCache.NULL)).isEmpty());
        Assert.assertTrue(CentralClient.fetchModuleSources(HTTP, VERSION, "http",
                options(central(Optional.of(new byte[] {1, 2, 3})), DocsCache.NULL)).isEmpty());
        Assert.assertTrue(CentralClient.fetchModuleSources(HTTP, Version.parse("9.9.9").value(), "http",
                options(central(Optional.of(bala(httpSources()))), DocsCache.NULL)).isEmpty());
        Assert.assertTrue(CentralClient.fetchModuleSources(HTTP, VERSION, "http", options(
                FakeTransport.always(FakeTransport.ok("[\"2.16.6\"]")), DocsCache.NULL)).isEmpty(),
                "a registry answer without a balaURL");
    }

    @Test
    public void theDerivedAnswerIsCachedSoTheArchiveIsFetchedOnce() throws IOException {
        DocsCache cache = diskCache();
        FakeTransport transport = central(Optional.of(bala(httpSources())));

        Optional<ObjectInclusions> first = SourceInclusions.load(
                CENTRAL, HTTP, RESOLVED, "http", options(transport, cache));
        int calls = transport.calls();
        Optional<ObjectInclusions> second = SourceInclusions.load(
                CENTRAL, HTTP, RESOLVED, "http", options(transport, cache));

        Assert.assertTrue(first.isPresent());
        Assert.assertEquals(second, first);
        Assert.assertEquals(transport.calls(), calls, "the second load reached the network");
        DocsCache.DocsKey key = new DocsCache.DocsKey("central", "ballerina", "http", "2.16.6");
        Assert.assertEquals(cache.readInclusions(key, "http", SourceInclusions.DERIVATION).getAsJsonObject()
                .getAsJsonArray("ServiceContract").get(0).getAsString(), "Service");
    }

    /** An answer an older derivation cached is never served: what the reader records may have changed since. */
    @Test
    public void anEntryAnOlderDerivationCachedIsNotRead() throws IOException {
        DocsCache cache = diskCache();
        DocsCache.DocsKey key = new DocsCache.DocsKey("central", "ballerina", "http", "2.16.6");
        cache.writeInclusions(key, "http", "0", JsonParser.parseString("{}"));
        FakeTransport transport = central(Optional.of(bala(httpSources())));

        Optional<ObjectInclusions> read = SourceInclusions.load(
                CENTRAL, HTTP, RESOLVED, "http", options(transport, cache));

        Assert.assertTrue(read.orElseThrow().byType().containsKey("ServiceContract"));
        Assert.assertTrue(transport.urls().contains(BALA_URL));
    }

    @Test
    public void aDamagedCacheEntryIsAMissAndAFailedFetchIsNotCached() throws IOException {
        DocsCache cache = diskCache();
        DocsCache.DocsKey key = new DocsCache.DocsKey("central", "ballerina", "http", "2.16.6");
        String derivation = SourceInclusions.DERIVATION;
        cache.writeInclusions(key, "http", derivation, JsonParser.parseString("{\"ServiceContract\":[1]}"));

        Assert.assertTrue(SourceInclusions.load(CENTRAL, HTTP, RESOLVED, "http",
                options(central(Optional.empty()), cache)).isEmpty());
        Assert.assertEquals(cache.readInclusions(key, "http", derivation).toString(), "{\"ServiceContract\":[1]}");

        Assert.assertTrue(SourceInclusions.load(CENTRAL, HTTP, RESOLVED, "http",
                options(central(Optional.of(bala(httpSources()))), cache)).isPresent());
        Assert.assertTrue(cache.readInclusions(key, "http", derivation).getAsJsonObject()
                .has("InterceptableService"));
    }

    @Test
    public void refreshRereadsTheSource() {
        AtomicInteger fetched = new AtomicInteger();
        PackageRepository counting = new PackageRepository() {
            @Override
            public Result<CentralClient.ResolvedVersion> resolveVersion(QualifiedName qualified, HttpOptions http) {
                throw new AssertionError("not asked");
            }

            @Override
            public Result<CentralDocs> fetchDocs(
                    QualifiedName qualified, CentralClient.ResolvedVersion resolved, HttpOptions http) {
                throw new AssertionError("not asked");
            }

            @Override
            public Optional<Map<String, String>> fetchModuleSources(QualifiedName qualified,
                    CentralClient.ResolvedVersion resolved, String moduleId, HttpOptions http) {
                fetched.incrementAndGet();
                return Optional.of(httpSources());
            }

            @Override
            public Result<CentralDocs> fetchModuleDocs(QualifiedName qualified, String submodule,
                    CentralClient.ResolvedVersion resolved, HttpOptions http) {
                throw new AssertionError("not asked");
            }

            @Override
            public String id() {
                return "counting";
            }

            @Override
            public String describe() {
                return "counting";
            }
        };
        DocsCache cache = new MemoryCache();
        HttpOptions cached = HttpOptions.builder().cache(cache).build();
        HttpOptions refreshing = HttpOptions.builder().cache(cache).refresh(true).build();
        SourceInclusions.load(counting, HTTP, RESOLVED, "http", cached);
        SourceInclusions.load(counting, HTTP, RESOLVED, "http", cached);
        SourceInclusions.load(counting, HTTP, RESOLVED, "http", refreshing);
        Assert.assertEquals(fetched.get(), 2);
    }

    /** Only the inclusions entry, in memory — the rest of the cache is not under test here. */
    private static final class MemoryCache implements DocsCache {

        private final Map<String, com.google.gson.JsonElement> entries = new java.util.HashMap<>();

        @Override
        public com.google.gson.JsonElement readDocs(DocsKey key) {
            return null;
        }

        @Override
        public void writeDocs(DocsKey key, com.google.gson.JsonElement payload) {
        }

        @Override
        public void removeDocs(DocsKey key) {
        }

        @Override
        public com.google.gson.JsonElement readModuleDocs(ModuleKey key) {
            return null;
        }

        @Override
        public void writeModuleDocs(ModuleKey key, com.google.gson.JsonElement payload) {
        }

        @Override
        public void removeModuleDocs(ModuleKey key) {
        }

        @Override
        public com.google.gson.JsonElement readInclusions(DocsKey key, String module, String derivation) {
            return entries.get(key + module + derivation);
        }

        @Override
        public void writeInclusions(
                DocsKey key, String module, String derivation, com.google.gson.JsonElement inclusions) {
            entries.put(key + module + derivation, inclusions);
        }

        @Override
        public LatestEntry readLatest(PackageKey key) {
            return null;
        }

        @Override
        public void writeLatest(PackageKey key, LatestEntry entry) {
        }

        @Override
        public List<String> listVersions(PackageKey key) {
            return List.of();
        }

        @Override
        public String describe() {
            return "memory";
        }
    }
}
