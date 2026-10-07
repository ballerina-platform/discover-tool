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

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import io.ballerina.tools.discover.central.schema.CentralDocs;
import io.ballerina.tools.discover.central.schema.Schema;
import io.ballerina.tools.discover.model.Bindings;
import io.ballerina.tools.discover.model.FromCentral;
import io.ballerina.tools.discover.model.Library;
import io.ballerina.tools.discover.model.ObjectInclusions;
import io.ballerina.tools.discover.model.Pipeline;
import io.ballerina.tools.discover.render.Documents;
import io.ballerina.tools.discover.source.SourceInclusions;
import io.ballerina.tools.discover.views.Readmes;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * The fixture corpus: recorded Central payloads and the Ballerina they render to.
 *
 * <p>The risk this exists to manage is invisible without it — the renderer mangles some library's shape (a union,
 * a nested record default, a resource path with a quoted identifier, an annotation), the agent writes code
 * against a wrong signature, and {@code bal build} fails for reasons nobody traces back here.
 *
 * <p>The payloads and the {@code .bal} snapshots are the SAME BYTES the TypeScript reader was verified against.
 * They are language-agnostic by construction — a recorded HTTP response and the Ballerina it renders to — which
 * is what makes them an oracle for this port rather than a fresh set of expectations written to match whatever
 * it happens to produce.
 *
 * @since 0.1.0
 */
public final class FixtureCorpus {

    /**
     * A fixed version, so a snapshot is deterministic.
     *
     * <p>The live value moves with every release, so a view snapshot that encoded it would fail on the next
     * publish rather than on a regression.
     */
    public static final Version FIXTURE_VERSION = Version.parse("0.0.0-fixture").value();

    /** Set to {@code 1} to rewrite the report snapshots after an intentional rendering change. */
    private static final String UPDATE_SNAPSHOTS = "UPDATE_SNAPSHOTS";

    private static final String UPDATE_KEYSPACE = "BAL_DISCOVER_UPDATE_KEYSPACE";

    /**
     * Resolved against the module directory rather than the classpath, because the snapshot tests can WRITE
     * here — a 45KB report is not something a reviewer hand-edits after an intentional change.
     */
    private static final Path RESOURCES = Path.of("src", "test", "resources");

    public static final Path FIXTURES_DIR = RESOURCES.resolve("fixtures");

    public static final Path SNAPSHOTS_DIR = RESOURCES.resolve("snapshots");

    /**
     * Recorded package SOURCE, for the fixtures whose service bindings depend on it — the docs payload publishes
     * no inclusion for a service type, so which ones bind to a listener is read from the {@code .bal} files.
     *
     * <p>Captured from the same versions as the payloads (http 2.16.6, graphql 1.17.0): the bala named by
     * {@code GET https://api.central.ballerina.io/2.0/registry/packages/<org>/<name>/<version>}'s
     * {@code balaURL}, unzipped, and only the files under {@code modules/<name>/} that declare the service object
     * types kept, verbatim — http's {@code http_types.bal} and {@code http_interceptors.bal}, graphql's
     * {@code types.bal}. Every other file of those modules declares no service type, so dropping them changes no
     * answer.
     */
    public static final Path SOURCES_DIR = RESOURCES.resolve("sources");

    public static final Path KEYSPACE_SNAPSHOT = SNAPSHOTS_DIR.resolve("keyspace.txt");

    /**
     * Fixtures are immutable on disk and the pipeline above them is pure, so a fixture is decoded and
     * transformed once per test process.
     *
     * <p>Not a micro-optimisation: {@code ballerinax/github} is 12.4MB of JSON, and the oracle tests ask for its
     * library once per tree path. Re-deriving it each time is the difference between a suite that runs in
     * seconds and one that runs in minutes.
     */
    private static final Map<String, JsonElement> RAW_CACHE = new ConcurrentHashMap<>();

    private static final Map<String, Library> LIBRARY_CACHE = new ConcurrentHashMap<>();

    private FixtureCorpus() {
    }

    /**
     * Every recorded PACKAGE page — the corpus every view, snapshot and the key-space snapshot runs over.
     *
     * <p>Top level only, deliberately: {@code fixtures/modules/} holds submodule pages and
     * {@code fixtures/registry/} registry version rows, neither of which is a package a view renders or a
     * snapshot names. Module pages are schema-checked on their own by {@code KeySpaceTest}; registry rows are
     * not docs payloads at all.
     */
    public static List<String> listFixtures() {
        return slugsIn(FIXTURES_DIR);
    }

    /** Every recorded submodule page, as {@link #loadRawModulePage} takes it. */
    public static List<String> listModulePages() {
        return slugsIn(FIXTURES_DIR.resolve("modules"));
    }

    private static List<String> slugsIn(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(file -> file.endsWith(".json.gz"))
                    .map(file -> file.substring(0, file.length() - ".json.gz".length()))
                    .sorted()
                    .toList();
        } catch (IOException cause) {
            throw new UncheckedIOException("the recorded corpus is missing", cause);
        }
    }

    /**
     * The recorded payload, still unvalidated — parsing it is what a test asserts.
     *
     * <p>Returned as a deep copy: several tests mutate the payload to build a negative case, and a shared tree
     * would let one test corrupt the next.
     */
    public static JsonElement loadRawFixture(String slug) {
        return RAW_CACHE.computeIfAbsent(slug, FixtureCorpus::readGzippedJson).deepCopy();
    }

    /**
     * A submodule's own docs page ({@code docs/<org>/<package>.<module>/<version>}), recorded at the version of
     * the package fixture it belongs to — kept out of the top-level corpus, which holds packages only.
     */
    public static JsonElement loadRawModulePage(String slug) {
        return loadRawFixture("modules/" + slug);
    }

    /** One package version's registry row ({@code registry/packages/<org>/<name>/<version>}), recorded alike. */
    public static JsonElement loadRawRegistryRow(String slug) {
        return loadRawFixture("registry/" + slug);
    }

    private static JsonElement readGzippedJson(String slug) {
        Path path = FIXTURES_DIR.resolve(slug + ".json.gz");
        try (InputStream in = Files.newInputStream(path);
                Reader reader = new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        } catch (IOException cause) {
            throw new UncheckedIOException("fixture " + slug + " is unreadable", cause);
        }
    }

    public static CentralDocs loadFixture(String slug) {
        Result<CentralDocs> parsed = Schema.parse(loadRawFixture(slug), slug);
        if (!parsed.isOk()) {
            throw new AssertionError("fixture " + slug + " no longer parses: " + parsed.failure().describe());
        }
        return parsed.value();
    }

    /**
     * {@code ballerinax__github} → the coordinates a caller would have typed. The reader selects its module by
     * requested name rather than taking the first one, so the corpus has to supply the same argument the CLI
     * would.
     */
    public static QualifiedName qualifiedForSlug(String slug) {
        Result<QualifiedName> parsed = QualifiedName.parse(slug.replaceFirst("__", "/"));
        if (!parsed.isOk()) {
            throw new AssertionError("fixture slug " + slug + " is not a package name");
        }
        return parsed.value();
    }

    /** The IR every view is rendered from — the pipeline up to but not including a document. */
    public static Library libraryFor(String slug) {
        return LIBRARY_CACHE.computeIfAbsent(slug, key -> {
            Result<CentralDocs.Module> module =
                    FromCentral.selectModule(loadFixture(key), qualifiedForSlug(key));
            if (!module.isOk()) {
                throw new AssertionError("fixture " + key + " has no module named after it: "
                        + module.failure().describe());
            }
            Optional<ObjectInclusions> inclusions = Bindings.needsSource(module.value())
                    ? recordedSources(key).flatMap(SourceInclusions::parse)
                    : Optional.empty();
            return Pipeline.build(module.value(), inclusions);
        });
    }

    /** The recorded {@code .bal} files for one fixture, by file name, or empty when none were recorded. */
    public static Optional<Map<String, String>> recordedSources(String slug) {
        Path directory = SOURCES_DIR.resolve(slug);
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        try (Stream<Path> files = Files.list(directory)) {
            Map<String, String> sources = new TreeMap<>();
            for (Path file : files.filter(path -> path.toString().endsWith(".bal")).toList()) {
                sources.put(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
            }
            return Optional.of(sources);
        } catch (IOException cause) {
            throw new UncheckedIOException("recorded sources for " + slug + " are unreadable", cause);
        }
    }

    /** A fixture as the views receive it, under a FIXED version and a verified load. */
    public static LoadedPackage loadedFixture(String slug) {
        Result<CentralDocs.Module> module =
                FromCentral.selectModule(loadFixture(slug), qualifiedForSlug(slug));
        if (!module.isOk()) {
            throw new AssertionError("fixture " + slug + " has no module named after it: "
                    + module.failure().describe());
        }
        Library library = libraryFor(slug);
        return new LoadedPackage(
                qualifiedForSlug(slug),
                FIXTURE_VERSION,
                library,
                Readmes.of(module.value()),
                null,
                List.of(),
                List.of(),
                Loader.unverifiedWarning(false),
                null,
                () -> library);
    }

    /**
     * The whole pipeline, in process. Deliberately not a subprocess: a rendering regression should surface as a
     * diff in a test, not as an exit code from a command whose output nobody kept.
     */
    public static String renderFixture(String slug) {
        return Documents.toSyntaxString(libraryFor(slug));
    }

    // -----------------------------------------------------------------------
    // Snapshots
    // -----------------------------------------------------------------------

    public static Path snapshotPath(String slug) {
        return SNAPSHOTS_DIR.resolve(slug + ".bal");
    }

    public static String readSnapshot(String slug) {
        return read(snapshotPath(slug));
    }

    public static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException cause) {
            throw new UncheckedIOException("snapshot " + path + " is unreadable", cause);
        }
    }

    /**
     * Compare against the committed document, or write it when {@code UPDATE_SNAPSHOTS=1}.
     *
     * <p>The escape hatch is deliberate and narrow: these documents are 8 to 45KB and hand-editing them after an
     * intentional change is not review, it is transcription.
     */
    public static void matchesSnapshot(Path path, String rendered, String label) {
        if ("1".equals(System.getenv(UPDATE_SNAPSHOTS))) {
            write(path, rendered);
            return;
        }
        String difference = firstDifference(read(path), rendered);
        if (difference != null) {
            throw new AssertionError(label + " changed\n" + difference);
        }
    }

    public static boolean updatingKeyspace() {
        return "1".equals(System.getenv(UPDATE_KEYSPACE));
    }

    public static void write(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException cause) {
            throw new UncheckedIOException("cannot write " + path, cause);
        }
    }

    /**
     * The first place two texts differ, as {@code line N: expected … / actual …}.
     *
     * <p>Worth the twenty lines: the snapshots run to 20,000 lines and a bare "strings are not equal" from the
     * assertion library would print both of them.
     */
    public static String firstDifference(String expected, String actual) {
        if (expected.equals(actual)) {
            return null;
        }
        String[] expectedLines = expected.split("\n", -1);
        String[] actualLines = actual.split("\n", -1);
        int limit = Math.max(expectedLines.length, actualLines.length);
        for (int index = 0; index < limit; index++) {
            String left = index < expectedLines.length ? expectedLines[index] : null;
            String right = index < actualLines.length ? actualLines[index] : null;
            if (!java.util.Objects.equals(left, right)) {
                return "line " + (index + 1) + " of " + expectedLines.length
                        + " (actual has " + actualLines.length + ")\n"
                        + "  expected: " + quote(left) + "\n"
                        + "  actual:   " + quote(right);
            }
        }
        return "texts differ in length only";
    }

    private static String quote(String line) {
        return line == null ? "null" : new com.google.gson.JsonPrimitive(line).toString();
    }

    // -----------------------------------------------------------------------
    // The drift detector
    // -----------------------------------------------------------------------

    /**
     * Every distinct object shape in a payload, as sorted key lists.
     *
     * <p>This is the drift detector. The schema makes a RENAMED or REMOVED field a loud failure, because those
     * change what gets rendered; an ADDED field is harmless to a reader that ignores it, and failing a lookup
     * over one would take the capability away for a cosmetic upstream change. So additions are caught here
     * instead — as a reviewable diff in a snapshot, at no run-time cost. Recorded per fixture because a shape
     * only some packages use (a listener, an inline record) is exactly where drift hides.
     */
    public static List<String> keySpace(JsonElement payload) {
        Set<String> signatures = new TreeSet<>();
        walk(payload, signatures);
        return List.copyOf(signatures);
    }

    private static void walk(JsonElement node, Set<String> signatures) {
        if (node == null || node.isJsonNull() || node.isJsonPrimitive()) {
            return;
        }
        if (node.isJsonArray()) {
            for (JsonElement item : node.getAsJsonArray()) {
                walk(item, signatures);
            }
            return;
        }
        List<String> keys = new ArrayList<>(node.getAsJsonObject().keySet());
        if (!keys.isEmpty()) {
            // Natural order, matching the bare `.sort()` the recorded snapshot was generated with.
            keys.sort(String::compareTo);
            signatures.add(String.join(",", keys));
        }
        for (Map.Entry<String, JsonElement> entry : node.getAsJsonObject().entrySet()) {
            walk(entry.getValue(), signatures);
        }
    }

    public static String renderKeySpace() {
        List<String> lines = new ArrayList<>();
        for (String slug : listFixtures()) {
            for (String signature : keySpace(loadRawFixture(slug))) {
                lines.add(slug + "\t" + signature);
            }
        }
        return String.join("\n", lines) + "\n";
    }

    /** Slugs as a TestNG data provider payload. */
    public static Object[][] fixtureRows() {
        List<String> slugs = listFixtures();
        Object[][] rows = new Object[slugs.size()][1];
        for (int index = 0; index < slugs.size(); index++) {
            rows[index][0] = slugs.get(index);
        }
        return rows;
    }
}
