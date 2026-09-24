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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ballerina.tools.discover.central.DependenciesToml;
import io.ballerina.tools.discover.central.HttpOptions;
import io.ballerina.tools.discover.central.HttpTransport;
import io.ballerina.tools.discover.cli.Cli;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The command's contract, under the package-first grammar: {@code bal discover <org/name> [bucket] [args...]}.
 *
 * <p>stdout carries the requested document and nothing else, stderr carries one JSON failure, and the exit code
 * says only whether stdout is complete. Every mistyped or version-skewed call must fail LOUDLY as
 * {@code validation} rather than resolving as something else and reporting a Central failure the agent will
 * retry.
 *
 * <p>Buckets covered here are {@code client}/{@code class}/{@code funcs} — the ones actually wired into
 * {@link Cli} so far. {@code service} and {@code readme} join this file's coverage as their own items land.
 *
 * @since 0.1.0
 */
public class CliTest {

    /** Captures both streams, so a test can assert stdout stayed empty on failure. */
    private static final class Capture {

        private final StringBuilder out = new StringBuilder();
        private final StringBuilder err = new StringBuilder();

        private Cli.Streams streams() {
            return new Cli.Streams(out::append, err::append);
        }

        private String stdout() {
            return out.toString();
        }

        private String stderr() {
            return err.toString();
        }

        /** The one JSON object a failing run writes to stderr. */
        private JsonObject failure() {
            JsonElement parsed = JsonParser.parseString(err.toString());
            Assert.assertTrue(parsed.isJsonObject(), "stderr is not one JSON object: " + err);
            return parsed.getAsJsonObject();
        }

        private String field(String name) {
            JsonElement value = failure().get(name);
            return value == null ? null : value.getAsString();
        }
    }

    /**
     * A document opens on its own marker, then on the heading or comment given.
     *
     * <p>Asserted through a helper rather than as a literal prefix because line one now carries the document's
     * LENGTH, which is content-dependent — see {@code Documents.withLength}.
     */
    private static void opensWith(String stdout, String marker, String then) {
        Assert.assertTrue(stdout.startsWith(marker), "expected to open on " + marker + ", got:\n" + stdout);
        String firstLine = stdout.lines().findFirst().orElse("");
        Assert.assertTrue(firstLine.matches(".*· \\d+ lines.*"),
                "line one states no length: " + firstLine);
        Assert.assertTrue(stdout.lines().skip(1).findFirst().orElse("").equals(then)
                        || stdout.substring(firstLine.length()).startsWith("\n" + then),
                "expected " + then + " after the marker, got:\n" + stdout);
    }

    /** Central, replayed: the versions endpoint then the docs endpoint. */
    private static HttpOptions centralFor(String slug, String version) {
        String docs = FixtureCorpus.loadRawFixture(slug).toString();
        return options(FakeTransport.routing(url -> url.contains("/docs/")
                ? FakeTransport.ok(docs)
                : FakeTransport.ok("[\"" + version + "\"]")));
    }

    private static HttpOptions options(HttpTransport transport) {
        return HttpOptions.builder()
                .transport(transport)
                .baseDelayMs(1)
                .sleeper(millis -> {
                    // No test here asserts wall-clock behaviour.
                })
                .build();
    }

    private static HttpOptions never() {
        return options(FakeTransport.never());
    }

    // -----------------------------------------------------------------------
    // Usage and argument errors
    // -----------------------------------------------------------------------

    @Test
    public void noArgumentsPrintsUsageOnStdoutAndSucceeds() {
        // Usage is a document, not a failure: it goes where every document goes, under the code that says
        // stdout is complete. A caller redirecting stdout gets the text rather than an empty file.
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of(), capture.streams(), never()), 0);
        Assert.assertEquals(capture.stderr(), "");
        Assert.assertTrue(capture.stdout().startsWith("Usage: bal discover"));
    }

    @Test
    public void helpIsTheSameAsNoArguments() {
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("--help"), capture.streams(), never()), 0);
        Assert.assertTrue(capture.stdout().startsWith("Usage: bal discover"));
        Assert.assertEquals(Cli.run(List.of("-h"), capture.streams(), never()), 0);
    }

    @Test
    public void helpBypassesAMissingPackage() {
        // `--help` answers even with no package and no bucket — the same bypass the grammar's earlier, verb-first
        // shape relied on, now on the one flat command.
        Capture capture = new Capture();
        Assert.assertEquals(
                Cli.run(List.of("ballerinax/github", "--help"), capture.streams(), never()), 0);
        Assert.assertTrue(capture.stdout().startsWith("Usage: bal discover"));
    }

    @Test
    public void flagsWithNoPackageIsAValidationFailureNotUsage() {
        // Distinct from truly empty argv: a caller who passed a flag clearly attempted something, so "needs a
        // package" is more useful than the whole usage block.
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("--refresh"), capture.streams(), never()), 1);
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.field("kind"), "validation");
        Assert.assertTrue(capture.field("message").contains("package is required"), capture.stderr());
    }

    @Test
    public void aVersionSuffixInThePackageNameIsRejectedBeforeAnyRequest() {
        Capture capture = new Capture();
        Assert.assertEquals(
                Cli.run(List.of("ballerinax/github:6.0.0"), capture.streams(), never()), 1);
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.field("kind"), "validation");
        Assert.assertTrue(capture.field("suggestion").contains("Drop any ':version' suffix"));
    }

    @Test
    public void anUnrecognisedFlagIsAUsageErrorNotAVersionCentralIsAskedAbout() {
        // The regression this pins: `--refresh` on a stale binary used to resolve as the VERSION, so it reported
        // `package-not-found` at exit 1 — which the skill teaches means "Central could not answer, run it once
        // more". The agent then retried a command that could never succeed.
        Capture capture = new Capture();
        Assert.assertEquals(
                Cli.run(List.of("ballerina/http", "--nonesuch"), capture.streams(), never()), 1);
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.field("kind"), "validation");
        Assert.assertTrue(capture.field("message").contains("--nonesuch"), capture.stderr());
    }

    @Test
    public void anUnknownBucketNamesTheOnesThatExist() {
        Capture capture = new Capture();
        Assert.assertEquals(
                Cli.run(List.of("ballerinax/kafka", "nosuchbucket"), capture.streams(),
                        centralFor("ballerinax__kafka", "4.6.5")), 1);
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.field("kind"), "validation");
        Assert.assertTrue(capture.field("message").contains("nosuchbucket"), capture.stderr());
        Assert.assertTrue(capture.field("suggestion").contains("client, class, funcs"), capture.stderr());
    }

    @Test
    public void aVersionShapedArgumentIsRejectedWithTheRuleThatReplacedIt() {
        for (List<String> argv : List.of(
                List.of("ballerinax/kafka", "client", "4.6.5"),
                List.of("ballerinax/kafka", "class", "4.6.5"),
                List.of("ballerinax/kafka", "funcs", "4.6.5"))) {
            Capture capture = new Capture();
            Assert.assertEquals(Cli.run(argv, capture.streams(), never()), 1, String.join(" ", argv));
            Assert.assertEquals(capture.stdout(), "", String.join(" ", argv));
            Assert.assertEquals(capture.field("kind"), "validation", String.join(" ", argv));
            Assert.assertTrue(capture.field("message").contains("looks like a version"), capture.stderr());
            Assert.assertTrue(capture.field("suggestion").contains("Dependencies.toml"), capture.stderr());
        }
    }

    @Test
    public void shortHelpWorksWhereverItAppears() {
        for (List<String> argv : List.of(
                List.of("-h"),
                List.of("ballerinax/github", "-h"),
                List.of("ballerinax/github", "client", "-h"))) {
            Capture capture = new Capture();
            Assert.assertEquals(Cli.run(argv, capture.streams(), never()), 0, String.join(" ", argv));
            Assert.assertTrue(capture.stdout().startsWith("Usage: bal discover"), String.join(" ", argv));
        }
    }

    @Test
    public void aVersionIsResolvedFromTheProjectAndIsNotAnArgument() {
        Path projectDir = tempDir();
        write(projectDir.resolve("Ballerina.toml"), "[package]\norg = \"acme\"\nname = \"app\"\n");
        write(projectDir.resolve("Dependencies.toml"),
                "[[package]]\norg = \"ballerinax\"\nname = \"kafka\"\nversion = \"4.6.5\"\n");

        for (List<String> argv : List.of(
                List.of("ballerinax/kafka"),
                List.of("ballerinax/kafka", "client"),
                List.of("ballerinax/kafka", "class"),
                List.of("ballerinax/kafka", "funcs"))) {
            Capture capture = new Capture();
            // The transport asserts the registry is never reached, which is what proves the lock was used.
            Assert.assertEquals(
                    Cli.run(argv, capture.streams(), docsOnlyFor("ballerinax__kafka"), projectDir.toString()),
                    0, String.join(" ", argv) + " -> " + capture.stderr());
            Assert.assertFalse(capture.stdout().isEmpty(), String.join(" ", argv));
        }
    }

    /**
     * A transport that answers the docs endpoint and FAILS the registry, which is what a locked version has to
     * make unnecessary.
     */
    private static HttpOptions docsOnlyFor(String slug) {
        String docs = FixtureCorpus.loadRawFixture(slug).toString();
        return options(FakeTransport.routing(url -> url.contains("/docs/")
                ? FakeTransport.ok(docs)
                : FakeTransport.status(404)));
    }

    @Test
    public void aProjectIsFoundByWalkingUpFromWhereTheProcessStands() {
        Path projectDir = tempDir();
        write(projectDir.resolve("Ballerina.toml"), "[package]\nname = \"app\"\n");
        Path nested = projectDir.resolve("modules").resolve("deep");
        mkdirs(nested);
        Assert.assertEquals(DependenciesToml.discoverProject(nested), projectDir);
        Assert.assertNull(DependenciesToml.discoverProject(tempDir()));
    }

    // -----------------------------------------------------------------------
    // Bucket dispatch
    // -----------------------------------------------------------------------

    @Test
    public void aContainerVerbNavigatesAClientsPathsAsMarkdown() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("ballerinax/googleapis.gmail", "client"), capture.streams(),
                centralFor("ballerinax__googleapis.gmail", "4.2.0"));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        opensWith(capture.stdout(), "<!-- bal discover client v1 ·",
                "# Clients — ballerinax/googleapis.gmail `Client`");
    }

    @Test
    public void aPackageWithSeveralClientsIsARosterRatherThanAFailure() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("ballerina/http", "client"), capture.streams(),
                centralFor("ballerina__http", "2.16.6"));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        for (String name : List.of("Client", "FailoverClient", "LoadBalanceClient", "StatusCodeClient")) {
            Assert.assertTrue(capture.stdout().contains("`" + name + "`"), name);
            Assert.assertTrue(capture.stdout().contains("`bal discover ballerina/http client " + name + "`"),
                    name);
        }
    }

    @Test
    public void namingAContainerResolvesIt() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("ballerina/http", "client", "FailoverClient"),
                capture.streams(), centralFor("ballerina__http", "2.16.6"));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertTrue(capture.stdout().contains("`FailoverClient`"));
    }

    @Test
    public void namingSomethingNoBucketHoldsFailsWithCandidates() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("ballerina/http", "client", "FailoverClientt"), capture.streams(),
                centralFor("ballerina__http", "2.16.6"));
        Assert.assertEquals(exitCode, 1);
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.field("kind"), "symbol-not-found");
        Assert.assertTrue(capture.failure().getAsJsonArray("candidates").toString()
                .contains("FailoverClient"), capture.stderr());
        // The failed argument is never dropped from the failure object, even though the suggestion no longer
        // echoes it into a runnable command — there is no search flag left to embed it in.
        Assert.assertTrue(capture.failure().getAsJsonArray("requested").toString().contains("FailoverClientt"),
                capture.stderr());
    }

    @Test
    public void aPackageWithNoModuleFunctionsGetsAnHonestEmptyReportAtExit0() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("ballerinax/kafka", "funcs"), capture.streams(),
                centralFor("ballerinax__kafka", "4.6.5"));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertTrue(capture.stdout().contains("| Module functions | this package declares none |"),
                capture.stdout());
        Assert.assertTrue(capture.stdout().contains("`bal discover ballerinax/kafka client`"),
                capture.stdout());
    }

    @Test
    public void aBarePackageListsItsBuckets() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("ballerinax/kafka"), capture.streams(),
                centralFor("ballerinax__kafka", "4.6.5"));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertTrue(capture.stdout().contains("| Buckets | `client`, `class` |"), capture.stdout());
        Assert.assertTrue(capture.stdout().contains("`bal discover ballerinax/kafka client`"),
                capture.stdout());
        Assert.assertTrue(capture.stdout().contains("`bal discover ballerinax/kafka class`"),
                capture.stdout());
        Assert.assertFalse(capture.stdout().contains("funcs`"), "kafka declares no module functions");
    }

    @Test
    public void findIsGoneAsAVerb() {
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("find", "kafka"), capture.streams(), never()), 1);
        // `find` parses as a package-shaped positional (no `/`), so it fails the qualified-name pattern.
        Assert.assertEquals(capture.field("kind"), "validation");
    }

    // -----------------------------------------------------------------------
    // Version resolution
    // -----------------------------------------------------------------------

    @Test
    public void anUnknownPackageExits1AndNamesItself() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("ballerinax/nope"), capture.streams(),
                HttpOptions.builder()
                        .transport(FakeTransport.always(FakeTransport.ok("[]")))
                        .maxAttempts(1).baseDelayMs(1).sleeper(millis -> { }).build());
        Assert.assertEquals(exitCode, 1);
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.field("kind"), "package-not-found");
    }

    // -----------------------------------------------------------------------
    // The stream contract
    // -----------------------------------------------------------------------

    @Test
    public void aFailingRunLeavesStdoutEmptyAndStderrHoldingExactlyOneJsonObject() {
        List<List<String>> failures = List.of(
                List.of("not-a-package"),
                List.of("ballerinax/kafka", "nosuchbucket"),
                List.of("--nonesuch"));
        for (List<String> argv : failures) {
            Capture capture = new Capture();
            int exitCode = Cli.run(argv, capture.streams(), never());
            Assert.assertEquals(exitCode, 1, String.join(" ", argv));
            Assert.assertEquals(capture.stdout(), "", String.join(" ", argv));
            Assert.assertEquals(capture.field("kind"), "validation", String.join(" ", argv));
            Assert.assertTrue(capture.stderr().endsWith("}\n"), String.join(" ", argv));
        }
    }

    @Test
    public void everyFailureIsExitOneAndTheKindSaysWhatWentWrong() {
        record Case(String kind, List<String> argv, HttpOptions http) { }
        HttpOptions missing = HttpOptions.builder()
                .transport(FakeTransport.always(FakeTransport.status(400)))
                .maxAttempts(1).baseDelayMs(1).sleeper(millis -> { }).build();
        HttpOptions broken = HttpOptions.builder()
                .transport(FakeTransport.always(FakeTransport.status(500)))
                .maxAttempts(1).baseDelayMs(1).sleeper(millis -> { }).build();
        HttpOptions kafka = centralFor("ballerinax__kafka", "4.6.5");

        Assert.assertEquals(Cli.run(List.of("ballerinax/kafka"), new Capture().streams(), kafka), 0);
        Assert.assertEquals(Cli.run(List.of("--help"), new Capture().streams(), never()), 0);

        List<Case> failures = List.of(
                new Case("package-not-found", List.of("no-such-org/no-such-pkg"), missing),
                new Case("upstream", List.of("ballerinax/kafka"), broken),
                new Case("validation", List.of("ballerina/http:2.16.6"), never()),
                new Case("validation", List.of("nonsense"), never()),
                new Case("symbol-not-found",
                        List.of("ballerinax/kafka", "client", "NoSuchContainer"), kafka));
        for (Case failure : failures) {
            Capture capture = new Capture();
            String label = String.join(" ", failure.argv());
            Assert.assertEquals(Cli.run(failure.argv(), capture.streams(), failure.http()), 1, label);
            Assert.assertEquals(capture.field("kind"), failure.kind(), label);
        }
    }

    // -----------------------------------------------------------------------
    // Failure text as golden files
    // -----------------------------------------------------------------------

    @Test
    public void theFailureTextIsUnchanged() {
        record Case(String name, List<String> argv) { }
        List<Case> cases = List.of(
                new Case("unknown-bucket", List.of("ballerinax/kafka", "nosuchbucket")),
                new Case("version-suffix", List.of("ballerinax/github:6.0.0")),
                new Case("unknown-flag", List.of("ballerina/http", "--nonesuch")),
                new Case("no-package", List.of("--refresh")));

        for (Case failure : cases) {
            Capture capture = new Capture();
            Assert.assertEquals(Cli.run(failure.argv(), capture.streams(), never()), 1,
                    String.join(" ", failure.argv()));
            Assert.assertEquals(capture.stdout(), "", String.join(" ", failure.argv()));
            FixtureCorpus.matchesSnapshot(
                    Path.of("src", "test", "resources", "command-outputs", "unix",
                            "failure-" + failure.name() + ".json"),
                    capture.stderr(),
                    failure.name() + " failure text");
        }
    }

    @Test
    public void theSymbolNotFoundTextIsUnchanged() {
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("ballerinax/kafka", "client", "NoSuchContainer"),
                capture.streams(), centralFor("ballerinax__kafka", "4.6.5")), 1);
        Assert.assertEquals(capture.stdout(), "");
        FixtureCorpus.matchesSnapshot(
                Path.of("src", "test", "resources", "command-outputs", "unix", "failure-symbol-not-found.json"),
                capture.stderr(),
                "symbol-not-found failure text");
    }

    // -----------------------------------------------------------------------

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("bal-discover-");
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
    }

    private static void mkdirs(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
    }

    private static void write(Path path, String contents) {
        try {
            Files.writeString(path, contents, StandardCharsets.UTF_8);
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
    }
}
