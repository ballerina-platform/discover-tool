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

import com.google.gson.JsonArray;
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
import java.util.ArrayList;
import java.util.List;

/**
 * The command's contract, under the package-first grammar: {@code bal discover <org/name> [bucket] [args...]}.
 *
 * <p>stdout carries the requested document and nothing else, stderr carries one JSON failure, and the exit code
 * says only whether stdout is complete. Every mistyped or version-skewed call must fail LOUDLY as
 * {@code validation} rather than resolving as something else and reporting a Central failure the agent will
 * retry.
 *
 * <p>Every bucket wired into {@link Cli} is covered here: {@code client}/{@code service}/{@code class}/
 * {@code funcs} through {@code Containers}, and {@code readme} through its own dispatch branch — the one bucket
 * not built on {@code Containers} at all, so its own end-to-end wiring gets its own test below rather than
 * riding along with the other four's.
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

    /** Central, replayed: the versions endpoint then the docs endpoint. */
    private static HttpOptions centralFor(String slug, String version) {
        String docs = FixtureCorpus.loadRawFixture(slug).toString();
        return options(FakeTransport.routing(url -> url.contains("/docs/")
                ? FakeTransport.ok(docs)
                : FakeTransport.ok("[\"" + version + "\"]")));
    }

    /** Central, replayed against an assembled multi-module payload rather than a recorded one. */
    private static HttpOptions centralForDocs(JsonObject docs, String version) {
        String text = docs.toString();
        return options(FakeTransport.routing(url -> url.contains("/docs/")
                ? FakeTransport.ok(text)
                : FakeTransport.ok("[\"" + version + "\"]")));
    }

    /**
     * A synthetic multi-module payload — every recorded fixture is single-module, so {@code --module} and the
     * bare package's own submodule listing cannot be tested against the corpus by construction. kafka's own
     * module is the template, cloned once per id, since it carries every array the schema requires; each clone
     * gets its OWN summary and readme so a test can tell which one actually got loaded.
     */
    private static JsonObject multiModuleDocs(List<String> ids, String org) {
        JsonObject raw = FixtureCorpus.loadRawFixture("ballerinax__kafka").getAsJsonObject();
        JsonElement template = raw.getAsJsonObject("docsData").getAsJsonArray("modules").get(0);

        JsonArray modules = new JsonArray();
        for (String id : ids) {
            JsonObject module = template.deepCopy().getAsJsonObject();
            module.addProperty("id", id);
            module.addProperty("orgName", org);
            module.addProperty("summary", "load data from " + id);
            module.addProperty("description", ("This is " + id + "'s own readme. ").repeat(20));
            modules.add(module);
        }
        JsonObject docsData = new JsonObject();
        docsData.add("modules", modules);
        JsonObject wrapper = new JsonObject();
        wrapper.add("docsData", docsData);
        return wrapper;
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
        Assert.assertTrue(capture.field("suggestion").contains("client, service, class, funcs"), capture.stderr());
    }

    @Test
    public void aVersionShapedArgumentIsRejectedWithTheRuleThatReplacedIt() {
        for (List<String> argv : List.of(
                List.of("ballerinax/kafka", "client", "4.6.5"),
                List.of("ballerinax/kafka", "service", "4.6.5"),
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
                List.of("ballerinax/kafka", "service"),
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
    public void aContainerVerbNavigatesAClientsPathsAsStructuredJson() {
        // gmail's Client has enough resource paths that the bare `client` bucket lands on the RFC's structured
        // IR rather than the legacy Markdown report — this is the JSON default off a TTY.
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("ballerinax/googleapis.gmail", "client"), capture.streams(),
                centralFor("ballerinax__googleapis.gmail", "4.2.0"));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertTrue(capture.stdout().contains("\"resources\":["), capture.stdout());
        Assert.assertTrue(capture.stdout().contains("\"path\":"), capture.stdout());
    }

    @Test
    public void aPackageWithSeveralClientsIsARosterRatherThanAFailure() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("ballerina/http", "client"), capture.streams(),
                centralFor("ballerina__http", "2.16.6"));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        for (String name : List.of("Client", "FailoverClient", "LoadBalanceClient", "StatusCodeClient")) {
            Assert.assertTrue(capture.stdout().contains("\"name\":\"" + name + "\""), name);
            Assert.assertTrue(
                    capture.stdout().contains("\"command\":\"bal discover ballerina/http client " + name + "\""),
                    name);
        }
    }

    @Test
    public void namingAContainerResolvesIt() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("ballerina/http", "client", "FailoverClient"),
                capture.streams(), centralFor("ballerina__http", "2.16.6"));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        JsonObject json = JsonParser.parseString(capture.stdout()).getAsJsonObject();
        Assert.assertTrue(json.has("resources") && json.has("remote"), capture.stdout());
        Assert.assertTrue(json.getAsJsonArray("remote").toString().contains("\"submit\""), capture.stdout());
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
    public void aPackageWithNoModuleFunctionsGetsAnHonestEmptyAnswerAtExit0() {
        Capture json = run(List.of("ballerinax/kafka", "funcs"), "ballerinax__kafka", "4.6.5", false);
        JsonObject empty = JsonParser.parseString(json.stdout()).getAsJsonObject();
        Assert.assertEquals(empty.get("bucket").getAsString(), "funcs");
        Assert.assertEquals(empty.get("total").getAsInt(), 0);
        Assert.assertTrue(json.stdout().contains("\"command\":\"bal discover ballerinax/kafka client\""),
                json.stdout());

        Capture text = run(List.of("ballerinax/kafka", "funcs"), "ballerinax__kafka", "4.6.5", true);
        Assert.assertTrue(text.stdout().startsWith("ballerinax/kafka · funcs\n"
                        + "This package declares nothing in funcs.\n\n"
                        + "Elsewhere\n"
                        + "  client   3  bal discover ballerinax/kafka client\n"),
                text.stdout());
    }

    // -----------------------------------------------------------------------
    // Every container answer honours --output
    // -----------------------------------------------------------------------

    private static Capture run(List<String> argv, String slug, String version, boolean interactive) {
        Capture capture = new Capture();
        int exitCode = Cli.run(argv, capture.streams(), centralFor(slug, version), null, interactive);
        Assert.assertEquals(exitCode, 0, String.join(" ", argv) + " -> " + capture.stderr());
        Assert.assertEquals(capture.stderr(), "", String.join(" ", argv));
        Assert.assertTrue(capture.stdout().endsWith("\n"), capture.stdout());
        return capture;
    }

    @Test
    public void aSingleSignatureIsStructuredJsonOffATerminalAndTheBareDeclarationAtOne() {
        List<String> argv = List.of("ballerinax/kafka", "client", "Producer", "send");
        JsonObject json = JsonParser.parseString(run(argv, "ballerinax__kafka", "4.6.5", false).stdout())
                .getAsJsonObject();
        Assert.assertEquals(json.get("container").getAsString(), "Producer");
        Assert.assertEquals(json.get("kind").getAsString(), "remote");
        Assert.assertEquals(json.get("name").getAsString(), "send");
        Assert.assertEquals(json.get("form").getAsString(), "->");
        Assert.assertTrue(json.get("declaration").getAsString().contains("remote function send("), json.toString());
        Assert.assertFalse(json.getAsJsonArray("params").isEmpty(), json.toString());
        Assert.assertTrue(json.has("returns"), json.toString());
        Assert.assertFalse(json.getAsJsonArray("types").isEmpty(), json.toString());

        String text = run(argv, "ballerinax__kafka", "4.6.5", true).stdout();
        Assert.assertTrue(text.startsWith("ballerinax/kafka · client · Producer · send\n\n# "),
                "the header, then the declaration's own doc comment: " + text);
        Assert.assertTrue(text.contains("remote function send("), text);
        Assert.assertTrue(text.contains("\n\nTypes it names (2)\n  # "), text);
    }

    @Test
    public void aResourceSignatureNamesItsPathAndAccessorInJson() {
        List<String> argv = List.of("ballerinax/github", "client", "repos/:owner/:repo/actions/caches", "delete");
        JsonObject json = JsonParser.parseString(run(argv, "ballerinax__github", "6.0.0", false).stdout())
                .getAsJsonObject();
        Assert.assertTrue(json.has("kind"), json.toString());
        Assert.assertEquals(json.get("kind").getAsString(), "resource");
        Assert.assertEquals(json.get("accessor").getAsString(), "delete");
        Assert.assertEquals(json.get("path").getAsString(), "repos/:owner/:repo/actions/caches");
        Assert.assertFalse(json.has("name"), json.toString());
    }

    /**
     * The RFC's own worked example: every entry under github's {@code gists} carries a {@code commands} object, one
     * command per accessor in {@code accessors} order and never a flat {@code command}, and running each opens exactly
     * that resource and accessor's signature — never the listing it came from again.
     */
    @Test
    public void everyResourceCommandOpensExactlyThatSignature() {
        for (String[] listing : new String[][] {
                {"ballerinax/github", "ballerinax__github", "gists"},
                {"ballerinax/googleapis.gmail", "ballerinax__googleapis.gmail", null}}) {
            List<String> argv = listing[2] == null
                    ? List.of(listing[0], "client")
                    : List.of(listing[0], "client", listing[2]);
            JsonArray resources = JsonParser.parseString(run(argv, listing[1], "1.0.0", false).stdout())
                    .getAsJsonObject().getAsJsonArray("resources");
            int followed = 0;
            boolean several = false;
            for (JsonElement element : resources) {
                JsonObject resource = element.getAsJsonObject();
                Assert.assertFalse(resource.has("command"), resource.toString());
                JsonObject commands = resource.getAsJsonObject("commands");
                List<String> accessors = new java.util.ArrayList<>();
                resource.getAsJsonArray("accessors").forEach(accessor -> accessors.add(accessor.getAsString()));
                Assert.assertEquals(List.copyOf(commands.keySet()), accessors, resource.toString());
                several |= accessors.size() > 1;
                for (String accessor : accessors) {
                    List<String> command = argv(commands.get(accessor).getAsString());
                    JsonObject signature = JsonParser.parseString(run(command, listing[1], "1.0.0", false).stdout())
                            .getAsJsonObject();
                    Assert.assertEquals(signature.get("path").getAsString(), resource.get("path").getAsString(),
                            String.join(" ", command));
                    Assert.assertEquals(signature.get("accessor").getAsString(), accessor, String.join(" ", command));
                    followed++;
                }
            }
            Assert.assertTrue(several, String.join(" ", argv) + " listed no multi-accessor path");
            Assert.assertTrue(followed > 0, String.join(" ", argv) + " printed no call");
        }
    }

    /** A printed command as argv: {@code bal discover} dropped, double quotes honoured. */
    private static List<String> argv(String command) {
        List<String> tokens = new java.util.ArrayList<>();
        java.util.regex.Matcher token = java.util.regex.Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(command);
        while (token.find()) {
            tokens.add(token.group(1) != null ? token.group(1) : token.group(2));
        }
        Assert.assertEquals(tokens.subList(0, 2), List.of("bal", "discover"), command);
        return tokens.subList(2, tokens.size());
    }

    @Test
    public void aMixedContainerIsSplitByCallFormInBothRenderings() {
        List<String> argv = List.of("ballerina/http", "client", "Client");
        JsonObject json = JsonParser.parseString(run(argv, "ballerina__http", "2.16.6", false).stdout())
                .getAsJsonObject();
        Assert.assertFalse(json.getAsJsonArray("resources").isEmpty(), json.toString());
        Assert.assertTrue(json.getAsJsonArray("remote").toString().contains("\"execute\""), json.toString());
        Assert.assertTrue(json.getAsJsonArray("normal").toString().contains("\"getCookieStore\""), json.toString());
        Assert.assertEquals(json.get("shown").getAsInt(), json.get("total").getAsInt());

        String text = run(argv, "ballerina__http", "2.16.6", true).stdout();
        Assert.assertTrue(text.startsWith("ballerina/http · client · Client\n"), text);
        Assert.assertTrue(text.contains("\n\nResources (->)\n  :...path  get, "), text);
        Assert.assertTrue(text.contains("\n\nRemote (->)\n  delete\n  execute\n"), text);
        Assert.assertTrue(text.contains("\n\nNormal (.)\n  circuitBreakerForceClose\n"), text);
    }

    @Test
    public void aSelectorThatMatchesNothingIsExitZeroWithWhatIsThereInBothRenderings() {
        List<String> argv = List.of("ballerinax/kafka", "client", "Producer", "sendd");
        JsonObject json = JsonParser.parseString(run(argv, "ballerinax__kafka", "4.6.5", false).stdout())
                .getAsJsonObject();
        Assert.assertEquals(json.get("requested").getAsString(), "sendd");
        Assert.assertEquals(json.get("container").getAsString(), "Producer");
        Assert.assertTrue(json.getAsJsonArray("candidates").toString().contains("\"send\""), json.toString());
        Assert.assertTrue(json.getAsJsonObject("available").has("methods"), json.toString());
        Assert.assertEquals(json.get("next").getAsString(), "bal discover ballerinax/kafka client Producer");

        String text = run(argv, "ballerinax__kafka", "4.6.5", true).stdout();
        Assert.assertTrue(text.startsWith("ballerinax/kafka · client · Producer · sendd\n"
                + "Nothing on Producer matches 'sendd'.\n\nDid you mean\n  send\n"), text);
        Assert.assertTrue(text.contains("\n\nAvailable\n  5 methods\n\n    'flush              "
                + "bal discover ballerinax/kafka client Producer \"'flush\"\n    close\n"), text);
    }

    @Test
    public void aFilterThatMatchesNothingIsExitZeroWithWhatIsThere() {
        List<String> argv = List.of("ballerinax/github", "client", "--filter", "zzznopealsonope");
        JsonObject json = JsonParser.parseString(run(argv, "ballerinax__github", "6.0.0", false).stdout())
                .getAsJsonObject();
        Assert.assertEquals(json.get("requested").getAsString(), "zzznopealsonope");
        Assert.assertTrue(json.getAsJsonObject("available").has("groups"), json.toString());
    }

    @Test
    public void aMemberOnSeveralContainersListsTheOwnersInBothRenderings() {
        List<String> argv = List.of("ballerinax/kafka", "client", "commit");
        JsonObject json = JsonParser.parseString(run(argv, "ballerinax__kafka", "4.6.5", false).stdout())
                .getAsJsonObject();
        Assert.assertEquals(json.get("requested").getAsString(), "commit");
        Assert.assertTrue(json.toString().contains(
                "\"command\":\"bal discover ballerinax/kafka client Consumer commit\""), json.toString());
        Assert.assertEquals(json.get("shown").getAsInt(), json.get("total").getAsInt());

        String text = run(argv, "ballerinax__kafka", "4.6.5", true).stdout();
        Assert.assertTrue(text.startsWith("ballerinax/kafka · client · commit\n'commit' is declared on "), text);
        Assert.assertTrue(text.contains(
                "\n  Consumer  3 matches  bal discover ballerinax/kafka client Consumer commit"), text);
    }

    @Test
    public void noContainerAnswerIsMarkdownInEitherRendering() {
        for (List<String> argv : List.of(
                List.of("ballerinax/kafka", "client", "Producer", "send"),
                List.of("ballerinax/kafka", "client", "Producer", "nosuch"),
                List.of("ballerinax/kafka", "client", "commit"),
                List.of("ballerinax/kafka", "funcs"))) {
            for (boolean interactive : List.of(true, false)) {
                String out = run(argv, "ballerinax__kafka", "4.6.5", interactive).stdout();
                Assert.assertFalse(out.contains("<!-- bal discover"), out);
                Assert.assertFalse(out.contains("| | |"), out);
                Assert.assertFalse(out.contains("\n```"), out);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Bucket dispatch — readme
    //
    // Not built on Containers at all — see Readme's own class comment — so its dispatch is exercised here on
    // its own rather than riding along with the other four buckets' tests above.
    // -----------------------------------------------------------------------

    @Test
    public void theReadmeBucketIsTheWholeReadmeVerbatimAtATerminal() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("ballerinax/kafka", "readme"), capture.streams(),
                centralFor("ballerinax__kafka", "4.6.5"), null, true);
        Assert.assertEquals(exitCode, 0, capture.stderr());
        // Verbatim means no furniture at all, not even a format marker.
        Assert.assertFalse(capture.stdout().startsWith("<!-- bal discover"), capture.stdout());
        Assert.assertTrue(capture.stdout().length() > 500, capture.stdout());
    }

    @Test
    public void theReadmeBucketIsJsonOffATerminal() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("ballerinax/kafka", "readme"), capture.streams(),
                centralFor("ballerinax__kafka", "4.6.5"));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertTrue(capture.stdout().contains("\"readme\":"), capture.stdout());
        Assert.assertTrue(capture.stdout().contains("\"lines\":"), capture.stdout());
        Assert.assertFalse(capture.stdout().contains("\"chunk\":"), "the whole readme names no chunk");
    }

    @Test
    public void aReadmeChunkSelectorNarrowsToOneSection() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("ballerinax/kafka", "readme", "1"), capture.streams(),
                centralFor("ballerinax__kafka", "4.6.5"));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertTrue(capture.stdout().contains("\"chunk\":1"), capture.stdout());
        Assert.assertTrue(capture.stdout().contains("\"of\":"), capture.stdout());
    }

    @Test
    public void aReadmeChunkThatDoesNotExistFailsWithCandidates() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("ballerinax/kafka", "readme", "999"), capture.streams(),
                centralFor("ballerinax__kafka", "4.6.5"));
        Assert.assertEquals(exitCode, 1);
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.field("kind"), "symbol-not-found");
    }

    // -----------------------------------------------------------------------
    // --module — synthetic multi-module payloads only, see multiModuleDocs
    // -----------------------------------------------------------------------

    @Test
    public void theModuleFlagTargetsADifferentModulesOwnReadme() {
        JsonObject docs = multiModuleDocs(List.of("graphql", "graphql.dataloader"), "ballerina");

        Capture withoutModule = new Capture();
        Cli.run(List.of("ballerina/graphql", "readme"), withoutModule.streams(),
                centralForDocs(docs, "1.0.0"), null, true);
        Assert.assertTrue(withoutModule.stdout().contains("This is graphql's own readme."),
                withoutModule.stdout());

        Capture withModule = new Capture();
        Cli.run(List.of("ballerina/graphql", "--module", "dataloader", "readme"), withModule.streams(),
                centralForDocs(docs, "1.0.0"), null, true);
        Assert.assertTrue(withModule.stdout().contains("This is graphql.dataloader's own readme."),
                withModule.stdout());
    }

    @Test
    public void aValidModuleThatPublishesNoReadmeFailsLoudlyRatherThanAnEmptyBody() {
        JsonObject docs = multiModuleDocs(List.of("graphql", "graphql.dataloader"), "ballerina");
        docs.getAsJsonObject("docsData").getAsJsonArray("modules")
                .get(1).getAsJsonObject().addProperty("description", "   ");

        for (List<String> narrowed : List.of(List.<String>of(), List.of("--filter", "kafka"), List.of("1"))) {
            List<String> argv = new ArrayList<>(List.of("ballerina/graphql", "--module", "dataloader", "readme"));
            argv.addAll(narrowed);
            Capture capture = new Capture();
            int exitCode = Cli.run(argv, capture.streams(), centralForDocs(docs, "1.0.0"));
            Assert.assertEquals(exitCode, 1, argv + " -> " + capture.stdout());
            Assert.assertEquals(capture.stdout(), "");
            Assert.assertEquals(capture.field("kind"), "validation");
            Assert.assertTrue(capture.field("message").contains("publishes no readme for module dataloader"),
                    argv + " -> " + capture.stderr());
        }
    }

    @Test
    public void aReadmeFilterMatchingNoSectionIsANoMatchPointingAtTheWholeReadme() {
        Capture json = new Capture();
        int exitCode = Cli.run(List.of("ballerinax/kafka", "readme", "--filter", "zzzz"), json.streams(),
                centralFor("ballerinax__kafka", FixtureCorpus.FIXTURE_VERSION.text()));
        Assert.assertEquals(exitCode, 0, json.stderr());
        JsonObject answer = JsonParser.parseString(json.stdout()).getAsJsonObject();
        Assert.assertEquals(answer.get("requested").getAsString(), "zzzz");
        Assert.assertEquals(answer.get("next").getAsString(), "bal discover ballerinax/kafka readme");
        Assert.assertFalse(answer.has("chunks"), answer.toString());

        Capture text = new Capture();
        Cli.run(List.of("ballerinax/kafka", "readme", "--filter", "zzzz"), text.streams(),
                centralFor("ballerinax__kafka", FixtureCorpus.FIXTURE_VERSION.text()), null, true);
        Assert.assertTrue(text.stdout().contains("Nothing matches 'zzzz'."), text.stdout());
        Assert.assertTrue(text.stdout().endsWith("Next: bal discover ballerinax/kafka readme\n"), text.stdout());
    }

    @Test
    public void aReadmeSuggestionQuotesTheSelectorItRepeats() {
        Capture capture = new Capture();
        Cli.run(List.of("ballerinax/kafka", "readme", "1", "--filter", "zzzz"), capture.streams(),
                centralFor("ballerinax__kafka", FixtureCorpus.FIXTURE_VERSION.text()));
        Assert.assertTrue(capture.field("suggestion").endsWith("`bal discover ballerinax/kafka readme 1`."),
                capture.stderr());

        Capture chunks = new Capture();
        Cli.run(List.of("ballerinax/kafka", "readme", "--filter", "kafka"), chunks.streams(),
                centralFor("ballerinax__kafka", FixtureCorpus.FIXTURE_VERSION.text()));
        String title = null;
        for (JsonElement chunk : JsonParser.parseString(chunks.stdout()).getAsJsonObject().getAsJsonArray("chunks")) {
            String candidate = chunk.getAsJsonObject().get("title").getAsString();
            if (candidate.contains(" ")) {
                title = candidate;
                break;
            }
        }
        Assert.assertNotNull(title, chunks.stdout());
        Capture titled = new Capture();
        Cli.run(List.of("ballerinax/kafka", "readme", title, "--filter", "zzzz"), titled.streams(),
                centralFor("ballerinax__kafka", FixtureCorpus.FIXTURE_VERSION.text()));
        Assert.assertTrue(titled.field("suggestion").endsWith(" readme " + Texts.shellWord(title) + "`."),
                titled.stderr());
    }

    // -----------------------------------------------------------------------
    // --page against what does and does not page
    // -----------------------------------------------------------------------

    @Test
    public void aPageBelowOneIsRejectedBeforeAnythingIsFetched() {
        for (String page : List.of("0", "-1")) {
            Capture capture = new Capture();
            int exitCode = Cli.run(List.of("ballerinax/github", "client", "--page", page), capture.streams(), never());
            Assert.assertEquals(exitCode, 1);
            Assert.assertEquals(capture.field("kind"), "validation");
            Assert.assertTrue(capture.field("message").contains("numbered from 1"), capture.stderr());
        }
    }

    @Test
    public void aPageAgainstAnAnswerThatDoesNotPageIsRejectedRatherThanServedAsPageOne() {
        String github = "ballerinax__github";
        List<List<String>> unpaged = List.of(
                List.of("ballerinax/github", "--page", "5"),
                List.of("ballerinax/github", "readme", "--page", "5"),
                List.of("ballerinax/github", "client", "Client", "gists/'public", "get", "--page", "2"),
                List.of("ballerinax/github", "client", "Client", "--filter", "zzzq", "--page", "2"));
        for (List<String> argv : unpaged) {
            Capture capture = new Capture();
            int exitCode = Cli.run(argv, capture.streams(), centralFor(github, FixtureCorpus.FIXTURE_VERSION.text()));
            Assert.assertEquals(exitCode, 1, argv + " -> " + capture.stdout());
            Assert.assertEquals(capture.stdout(), "");
            Assert.assertEquals(capture.field("kind"), "validation");
            Assert.assertTrue(capture.field("message").contains("not paged"), argv + " -> " + capture.stderr());
            String suggestion = capture.field("suggestion");
            Assert.assertFalse(suggestion.substring(suggestion.indexOf('`')).contains("--page"), suggestion);
        }
        Capture quoted = new Capture();
        Cli.run(unpaged.get(2), quoted.streams(), centralFor(github, FixtureCorpus.FIXTURE_VERSION.text()));
        Assert.assertEquals(quoted.field("suggestion"),
                "Drop --page: `bal discover ballerinax/github client Client \"gists/'public\" get`.");

        Capture oneGroupedPage = new Capture();
        Assert.assertEquals(Cli.run(List.of("ballerinax/github", "client", "Client", "--page", "2"),
                oneGroupedPage.streams(), centralFor(github, FixtureCorpus.FIXTURE_VERSION.text())), 1);
        Assert.assertTrue(oneGroupedPage.field("message").contains("on 1 page"), oneGroupedPage.stderr());
    }

    // -----------------------------------------------------------------------
    // --filter
    // -----------------------------------------------------------------------

    @Test
    public void aBlankFilterIsNoFilterInEitherRendering() {
        HttpOptions http = centralFor("ballerinax__kafka", FixtureCorpus.FIXTURE_VERSION.text());
        Capture plain = new Capture();
        Cli.run(List.of("ballerinax/kafka", "client"), plain.streams(), http, null, true);
        for (String blank : List.of("", " ")) {
            Capture text = new Capture();
            Cli.run(List.of("ballerinax/kafka", "client", "--filter", blank), text.streams(), http, null, true);
            Assert.assertEquals(text.stdout(), plain.stdout(), "'" + blank + "'");
            Assert.assertFalse(text.stdout().contains("--filter"), text.stdout());
        }
    }

    /** A member found in another bucket on several containers keeps the note saying so. */
    @Test
    public void ownersReachedByKindToleranceCarryTheNoteInBothRenderings() {
        HttpOptions http = centralFor("ballerinax__kafka", FixtureCorpus.FIXTURE_VERSION.text());
        Capture json = new Capture();
        Assert.assertEquals(Cli.run(List.of("ballerinax/kafka", "class", "commit"), json.streams(), http), 0,
                json.stderr());
        JsonObject owners = JsonParser.parseString(json.stdout()).getAsJsonObject();
        Assert.assertTrue(owners.has("owners"), owners.toString());
        Assert.assertTrue(owners.get("note").getAsString().startsWith("'commit' is addressed by client"),
                owners.toString());

        Capture text = new Capture();
        Cli.run(List.of("ballerinax/kafka", "class", "commit"), text.streams(), http, null, true);
        Assert.assertTrue(text.stdout().contains("\nNote: 'commit' is addressed by client"), text.stdout());
    }

    @Test
    public void theBarePackageListsItsSubmodulesAlongsideItsOwnBucketsInText() {
        JsonObject docs = multiModuleDocs(List.of("graphql", "graphql.dataloader", "graphql.subgraph"), "ballerina");
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("ballerina/graphql"), capture.streams(),
                centralForDocs(docs, "1.0.0"), null, true);
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertTrue(capture.stdout().contains("\n\nSubmodules\n"), capture.stdout());
        Assert.assertTrue(capture.stdout().contains(
                "\n  dataloader  bal discover ballerina/graphql --module dataloader  "
                        + "load data from graphql.dataloader"),
                capture.stdout());
        Assert.assertTrue(capture.stdout().contains(
                "\n  subgraph    bal discover ballerina/graphql --module subgraph    "
                        + "load data from graphql.subgraph"),
                capture.stdout());
        // The default module addresses itself through its own buckets, never through the submodule list.
        Assert.assertFalse(capture.stdout().contains("load data from graphql\n"), capture.stdout());
    }

    @Test
    public void theBarePackageListsItsSubmodulesAsJsonOffATerminal() {
        JsonObject docs = multiModuleDocs(List.of("graphql", "graphql.dataloader"), "ballerina");
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("ballerina/graphql"), capture.streams(), centralForDocs(docs, "1.0.0"));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertTrue(capture.stdout().contains("\"submodules\":["), capture.stdout());
        Assert.assertTrue(
                capture.stdout().contains("\"command\":\"bal discover ballerina/graphql --module dataloader\""),
                capture.stdout());
    }

    @Test
    public void theSelectedModuleNeverListsItselfAmongItsOwnOtherSubmodules() {
        JsonObject docs = multiModuleDocs(
                List.of("graphql", "graphql.dataloader", "graphql.subgraph"), "ballerina");
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("ballerina/graphql", "--module", "dataloader"), capture.streams(),
                centralForDocs(docs, "1.0.0"));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertFalse(capture.stdout().contains("\"name\":\"dataloader\""), capture.stdout());
        Assert.assertTrue(capture.stdout().contains("\"name\":\"subgraph\""), capture.stdout());
    }

    @Test
    public void aModuleFlagThatNamesNoSubmoduleFailsWithCandidates() {
        JsonObject docs = multiModuleDocs(List.of("graphql", "graphql.dataloader"), "ballerina");
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("ballerina/graphql", "--module", "nosuch"), capture.streams(),
                centralForDocs(docs, "1.0.0"));
        Assert.assertEquals(exitCode, 1);
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.field("kind"), "symbol-not-found");
        Assert.assertTrue(capture.failure().getAsJsonArray("candidates").toString().contains("dataloader"),
                capture.stderr());
    }

    @Test
    public void commandsPrintedUnderAModuleCarryItForward() {
        // The whole point of LoadedPackage.pkgArgument(): a caller drilling further from here must not silently
        // fall back to the default module.
        JsonObject docs = multiModuleDocs(List.of("graphql", "graphql.dataloader"), "ballerina");
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("ballerina/graphql", "--module", "dataloader", "client"),
                capture.streams(), centralForDocs(docs, "1.0.0"));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertTrue(capture.stdout().contains("--module dataloader"), capture.stdout());
    }

    @Test
    public void aBarePackageListsItsBucketsAsJsonOffATty() {
        // Not interactive (the default for this overload), so JSON — the RFC's agent-facing default, no flag
        // required.
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("ballerinax/kafka"), capture.streams(),
                centralFor("ballerinax__kafka", "4.6.5"));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        String out = capture.stdout();
        Assert.assertTrue(out.contains("\"client\""), out);
        Assert.assertTrue(out.contains("\"class\""), out);
        Assert.assertFalse(out.contains("\"funcs\""), "kafka declares no module functions: " + out);
        // No Markdown-report furniture: this response is on the new result IR, not `Containers`' shape.
        Assert.assertFalse(out.contains("<!-- bal discover"), out);
    }

    private static final String KAFKA_BUCKETS = String.join("\n",
            "ballerinax/kafka",
            "4 buckets",
            "",
            "  client",
            "  service",
            "  class",
            "  readme",
            "",
            "Next: bal discover ballerinax/kafka <bucket>",
            "");

    @Test
    public void aBarePackageListsItsBucketsAsTextAtATerminal() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("ballerinax/kafka"), capture.streams(),
                centralFor("ballerinax__kafka", "4.6.5"), null, true);
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertEquals(capture.stdout(), KAFKA_BUCKETS);
    }

    @Test
    public void outputOverridesTheTtyDefaultInEitherDirection() {
        Capture asText = new Capture();
        Assert.assertEquals(Cli.run(List.of("ballerinax/kafka", "--output", "text"), asText.streams(),
                centralFor("ballerinax__kafka", "4.6.5")), 0, asText.stderr());
        Assert.assertEquals(asText.stdout(), KAFKA_BUCKETS);

        Capture asJson = new Capture();
        Assert.assertEquals(Cli.run(List.of("ballerinax/kafka", "--output", "json"), asJson.streams(),
                centralFor("ballerinax__kafka", "4.6.5"), null, true), 0, asJson.stderr());
        Assert.assertTrue(asJson.stdout().contains("\"client\""), asJson.stdout());
    }

    @Test
    public void anInvalidOutputValueIsAValidationFailure() {
        Capture capture = new Capture();
        Assert.assertEquals(
                Cli.run(List.of("ballerinax/kafka", "--output", "xml"), capture.streams(), never()), 1);
        Assert.assertEquals(capture.field("kind"), "validation");
        Assert.assertTrue(capture.field("message").contains("xml"), capture.stderr());
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
