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
import io.ballerina.tools.discover.central.CentralClient;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The command's contract, under the package-first grammar: {@code bal discover <org>/<name> [bucket] [name ...]}.
 *
 * <p>stdout carries the requested document and nothing else, stderr carries one JSON failure, and the exit code
 * says only whether stdout is complete, and if not, whether the command line itself was malformed (2). Every
 * mistyped or version-skewed call must fail LOUDLY as {@code validation} rather than resolving as something else
 * and reporting a Central failure the agent will retry.
 *
 * <p>Every bucket wired into {@link Cli} is covered here, {@code readme} included: it is not built on
 * {@code Containers}, so its dispatch branch gets its own tests.
 *
 * @since 0.1.0
 */
public class CliTest {

    /** Captures both streams, so a test can assert stdout stayed empty on failure. */
    static final class Capture {

        private final StringBuilder out = new StringBuilder();
        private final StringBuilder err = new StringBuilder();

        Cli.Streams streams() {
            return new Cli.Streams(out::append, err::append);
        }

        String stdout() {
            return out.toString();
        }

        String stderr() {
            return err.toString();
        }

        JsonObject failure() {
            JsonElement parsed = JsonParser.parseString(err.toString());
            Assert.assertTrue(parsed.isJsonObject(), "stderr is not one JSON object: " + err);
            return parsed.getAsJsonObject();
        }

        String field(String name) {
            JsonElement value = failure().get(name);
            return value == null ? null : value.getAsString();
        }
    }

    static HttpOptions centralFor(String slug, String version) {
        String docs = FixtureCorpus.loadRawFixture(slug).toString();
        return options(FakeTransport.routing(url -> url.contains("/docs/")
                ? FakeTransport.ok(docs)
                : FakeTransport.ok("[\"" + version + "\"]")));
    }

    private static final String GRAPHQL_VERSION = "1.17.0";

    private static final String SUBGRAPH_UNCONFIRMED = "Central's page for 'subgraph' at " + GRAPHQL_VERSION
            + " cannot be confirmed as a submodule of this package. Pass another of its submodules (dataloader, "
            + "subgraph), or drop --module for the default module.";

    private static FakeTransport graphqlCentral(Map<String, JsonElement> modulePages) {
        return graphqlCentral(FixtureCorpus.loadRawFixture("ballerina__graphql"), modulePages);
    }

    private static FakeTransport graphqlCentral(JsonElement packagePage, Map<String, JsonElement> modulePages) {
        Map<String, String> pages = new HashMap<>();
        modulePages.forEach((module, page) -> pages.put(
                "/docs/ballerina/graphql." + module + "/" + GRAPHQL_VERSION, page.toString()));
        pages.put("/docs/ballerina/graphql/" + GRAPHQL_VERSION, packagePage.toString());
        pages.put("/registry/packages/ballerina/graphql", "[\"" + GRAPHQL_VERSION + "\"]");
        return FakeTransport.routing(url -> pages.entrySet().stream()
                .filter(page -> url.endsWith(page.getKey()))
                .findFirst()
                .map(page -> FakeTransport.ok(page.getValue()))
                .orElse(FakeTransport.status(404)));
    }

    static FakeTransport graphqlCentral() {
        return graphqlCentral(Map.of(
                "dataloader", FixtureCorpus.loadRawModulePage("ballerina__graphql.dataloader"),
                "subgraph", FixtureCorpus.loadRawModulePage("ballerina__graphql.subgraph")));
    }

    static HttpOptions options(HttpTransport transport) {
        return HttpOptions.builder()
                .transport(transport)
                .baseDelayMs(1)
                .sleeper(millis -> {
                    // No test here asserts wall-clock behaviour.
                })
                .build();
    }

    static HttpOptions never() {
        return options(FakeTransport.never());
    }

    @Test
    public void noArgumentsPrintsUsageOnStdoutAndSucceeds() {
        // Usage is a document, not a failure: it goes where every document goes, under the code that says
        // stdout is complete. A caller redirecting stdout gets the text rather than an empty file.
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of(), capture.streams(), never()), 0);
        Assert.assertEquals(capture.stderr(), "");
        Assert.assertTrue(capture.stdout().contains("\nUsage: bal discover "));
    }

    @Test
    public void helpIsTheSameAsNoArguments() {
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("--help"), capture.streams(), never()), 0);
        Assert.assertTrue(capture.stdout().contains("\nUsage: bal discover "));
        Assert.assertEquals(Cli.run(List.of("-h"), capture.streams(), never()), 0);
    }

    @Test
    public void helpBypassesAMissingPackage() {
        Capture capture = new Capture();
        Assert.assertEquals(
                Cli.run(List.of("package", "ballerinax/github", "--help"), capture.streams(), never()), 0);
        Assert.assertTrue(capture.stdout().contains("\nUsage: bal discover "));
    }

    @Test
    public void flagsWithNoNounIsAUsageErrorNamingTheNouns() {
        // Distinct from truly empty argv: a caller who passed a flag clearly attempted something, so "name what to
        // read" is more useful than the whole usage block.
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("--refresh"), capture.streams(), never()), 2);
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.field("kind"), "usage");
        Assert.assertTrue(capture.field("message").startsWith("name what to read: package, search, client"),
                capture.stderr());
    }

    @Test
    public void aVersionInTheCoordinateIsReadExactlyWithoutAskingTheRegistryForTheLatest() {
        FakeTransport transport = FakeTransport.routing(url -> url.contains("/docs/")
                ? FakeTransport.status(404)
                : FakeTransport.status(500));
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("package", "ballerinax/github:6.0.0"), capture.streams(),
                options(transport)), 1);
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.field("kind"), "package-not-found");
        Assert.assertTrue(transport.urls().get(0).endsWith("/docs/ballerinax/github/6.0.0"),
                transport.urls().toString());
        Assert.assertEquals(capture.field("suggestion"), "Central does not publish 'ballerinax/github' at 6.0.0. "
                + "Write a published version after the package: ballerinax/github:<version>", capture.stderr());
    }

    @Test
    public void anUnrecognisedFlagIsAUsageErrorNotAVersionCentralIsAskedAbout() {
        // A flag resolved as the VERSION would report `package-not-found`, which an agent retries although the
        // command can never succeed.
        Capture capture = new Capture();
        Assert.assertEquals(
                Cli.run(List.of("package", "ballerina/http", "--nonesuch"), capture.streams(), never()), 2);
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.field("kind"), "usage");
        Assert.assertTrue(capture.field("message").contains("--nonesuch"), capture.stderr());
    }

    @Test
    public void anUnknownNounNamesTheOnesThatExistAndSearchesForTheWord() {
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("nosuchnoun", "-p", "ballerinax/kafka"), capture.streams(), never()), 2);
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.field("kind"), "usage");
        Assert.assertTrue(capture.field("message").contains("nosuchnoun"), capture.stderr());
        Assert.assertTrue(capture.field("message").contains("client, service, class, function, type, method"),
                capture.stderr());
        Assert.assertEquals(capture.field("command"), "bal discover search nosuchnoun -p ballerinax/kafka",
                "a name typed where the noun goes is found by the search");
    }

    // A selector the container does not read, with the command that drops it.
    private record Unread(String slug, List<String> argv, String unread, String command) { }

    @Test
    public void moreNamesThanANounTakesIsAUsageErrorWithTheCommandThatReadsThem() {
        record Case(List<String> argv, String command) { }
        for (Case each : List.of(
                new Case(List.of("client", "Producer", "send", "-p", "ballerinax/kafka"),
                        "bal discover method send -p ballerinax/kafka --client Producer"),
                new Case(List.of("client", "Client", "gists/public", "get", "-p", "ballerinax/github"),
                        "bal discover resource gists/public get -p ballerinax/github --client Client"),
                new Case(List.of("class", "Cookie", "isValid", "-p", "ballerina/http", "--filter", "x"),
                        "bal discover method isValid -p ballerina/http --class Cookie --filter x"),
                new Case(List.of("type", "Error", "Response", "-p", "ballerina/http"),
                        "bal discover type Error -p ballerina/http"),
                new Case(List.of("function", "a", "b", "-p", "ballerina/log"),
                        "bal discover function a -p ballerina/log"),
                new Case(List.of("readme", "Data", "serialization", "-p", "ballerinax/kafka"),
                        "bal discover readme \"Data serialization\" -p ballerinax/kafka"),
                new Case(List.of("package", "ballerinax/kafka", "client"), "bal discover package ballerinax/kafka"))) {
            String label = String.join(" ", each.argv());
            Capture capture = new Capture();
            Assert.assertEquals(Cli.run(each.argv(), capture.streams(), never()), 2, label);
            Assert.assertEquals(capture.stdout(), "", label);
            Assert.assertEquals(capture.field("kind"), "usage", label);
            Assert.assertEquals(capture.field("command"), each.command(), label);
            Assert.assertFalse(capture.field("suggestion").contains("`"), capture.stderr());
        }
    }

    @Test
    public void moreNamesThanAMemberTakesAreReadAgainstThePackageForTheCommandTheyMeant() {
        record Case(String slug, List<String> argv, String command) { }
        String version = FixtureCorpus.FIXTURE_VERSION.text();
        for (Case each : List.of(
                new Case("ballerinax__kafka", List.of("method", "Producer", "send", "-p", "ballerinax/kafka"),
                        "bal discover method send -p ballerinax/kafka --client Producer"),
                new Case("ballerinax__kafka", List.of("method", "send", "extra", "-p", "ballerinax/kafka",
                        "--client", "Producer"), "bal discover method send -p ballerinax/kafka --client Producer"),
                new Case("ballerinax__github", List.of("resource", "gists", "public", "get", "-p",
                        "ballerinax/github"), "bal discover resource gists/public get -p ballerinax/github"),
                new Case("ballerinax__github", List.of("resource", "Client", "gists/public", "get", "-p",
                        "ballerinax/github"), "bal discover resource gists/public get -p ballerinax/github --client "
                        + "Client"),
                new Case("ballerinax__github", List.of("resource", "gists/public", "get", "extra", "-p",
                        "ballerinax/github", "--client", "Client"),
                        "bal discover resource gists/public get -p ballerinax/github --client Client"),
                new Case("ballerinax__github", List.of("resource", "no", "such", "get", "-p", "ballerinax/github"),
                        "bal discover resource -p ballerinax/github"))) {
            String label = String.join(" ", each.argv());
            Capture capture = new Capture();
            Assert.assertEquals(Cli.run(each.argv(), capture.streams(), centralFor(each.slug(), version)), 2,
                    label + ": " + capture.stderr());
            Assert.assertEquals(capture.field("kind"), "usage", label);
            Assert.assertEquals(capture.field("command"), each.command(), label);
            Capture followed = new Capture();
            Assert.assertEquals(Cli.run(List.of(each.command().substring(Command.PREFIX.length()).replace("\"", "")
                    .split(" ")), followed.streams(), centralFor(each.slug(), version)), 0,
                    each.command() + ": " + followed.stderr());
        }
    }

    @Test
    public void theHeaderSpellsATypedEscapedPathAsItIsPrinted() {
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("resource", "gists/'public", "get", "-p", "ballerinax/github", "--client",
                "Client"),
                capture.streams(), centralFor("ballerinax__github", FixtureCorpus.FIXTURE_VERSION.text()), null,
                true), 0, capture.stderr());
        Assert.assertTrue(capture.stdout().startsWith("ballerinax/github:" + FixtureCorpus.FIXTURE_VERSION.text()
                + " · resource · Client · gists/public · get\n"), capture.stdout());
    }

    @Test
    public void aMemberItsOneContainerDeclaresIsAnsweredWithoutTheContainer() {
        Capture send = new Capture();
        Assert.assertEquals(Cli.run(List.of("method", "send", "-p", "ballerinax/kafka"), send.streams(),
                centralFor("ballerinax__kafka", "4.6.5"), null, true), 0, send.stderr());
        Assert.assertTrue(send.stdout().startsWith("ballerinax/kafka:4.6.5 · method · Producer · send\n"),
                send.stdout());

        Capture close = new Capture();
        Assert.assertEquals(Cli.run(List.of("method", "close", "-p", "ballerinax/kafka"), close.streams(),
                centralFor("ballerinax__kafka", "4.6.5")), Failure.USAGE, close.stderr());
        Assert.assertEquals(close.field("kind"), "ambiguous", close.stderr());
        Assert.assertFalse(close.failure().has("command"), "no one owner is preferred: " + close.stderr());
        Assert.assertFalse(close.failure().has("candidates"), close.stderr());
        JsonArray owners = close.failure().getAsJsonArray("owners");
        Assert.assertEquals(owners.size(), 2, close.stderr());
        Assert.assertEquals(owners.get(0).toString(), "{\"name\":\"Consumer\",\"command\":\"bal discover method "
                + "close -p ballerinax/kafka --client Consumer\"}");
        Assert.assertEquals(owners.get(1).getAsJsonObject().get("command").getAsString(),
                "bal discover method close -p ballerinax/kafka --client Producer");

        Capture asContainer = new Capture();
        Assert.assertEquals(Cli.run(List.of("client", "send", "-p", "ballerinax/kafka"), asContainer.streams(),
                centralFor("ballerinax__kafka", "4.6.5")), 1, asContainer.stderr());
        Assert.assertEquals(asContainer.field("command"), "bal discover method send -p ballerinax/kafka --client "
                + "Producer", "a method asked as a container is pointed at its method");
    }

    @Test
    public void aResourceAndAMethodOfOneNameAreAskedApartByTheirNouns() {
        for (List<String> selectors : List.of(List.of(":...path", "get"), List.of("get", ":...path"))) {
            List<String> argv = new ArrayList<>(List.of("resource"));
            argv.addAll(selectors);
            argv.addAll(List.of("-p", "ballerina/http", "--client", "Client"));
            JsonObject json = JsonParser.parseString(
                    run(argv, "ballerina__http", FixtureCorpus.FIXTURE_VERSION.text(), false).stdout())
                    .getAsJsonObject();
            Assert.assertEquals(json.get("kind").getAsString(), "resource", String.join(" ", argv));
            Assert.assertEquals(json.get("accessor").getAsString(), "get", String.join(" ", argv));
        }
        JsonObject method = JsonParser.parseString(run(List.of("method", "get", "-p", "ballerina/http", "--client",
                "Client"), "ballerina__http", FixtureCorpus.FIXTURE_VERSION.text(), false).stdout()).getAsJsonObject();
        Assert.assertEquals(method.get("kind").getAsString(), "remote");
    }

    @Test
    public void aPathAndAccessorThatResolveNothingFailAtThePathTheyReached() {
        String under = "bal discover resource %s -p ballerinax/github --client Client";
        for (List<String> selectors : List.of(List.of("gists/'public", "head"), List.of("gists/'public", "GET"),
                List.of("head", "gists/'public"), List.of("gists", "gett"), List.of("gists", "zzz"),
                List.of("gists", "starred"), List.of("gists", ":gistId"), List.of("gists", "public"))) {
            List<String> argv = new ArrayList<>(List.of("resource"));
            argv.addAll(selectors);
            argv.addAll(List.of("-p", "ballerinax/github", "--client", "Client"));
            String label = String.join(" ", argv);
            Capture capture = new Capture();
            Assert.assertEquals(Cli.run(argv, capture.streams(),
                    centralFor("ballerinax__github", FixtureCorpus.FIXTURE_VERSION.text())), 1, label);
            Assert.assertEquals(capture.stdout(), "", label);
            Assert.assertEquals(capture.field("kind"), "symbol-not-found", label);
            String reached = selectors.contains("gists") ? "gists" : "gists/public";
            if (capture.failure().has("candidates")) {
                Assert.assertFalse(capture.failure().has("command"), capture.stderr());
                capture.failure().getAsJsonArray("candidates").forEach(candidate ->
                        Assert.assertTrue(candidate.getAsString().startsWith(reached + "/")
                                || candidate.getAsString().startsWith(reached + " "), capture.stderr()));
            } else {
                Assert.assertEquals(capture.field("command"), under.formatted(reached), label);
            }
        }
        Capture split = new Capture();
        Assert.assertEquals(Cli.run(List.of("resource", "repos", ":owner", "-p", "ballerinax/github", "--client",
                "Client"), split.streams(), centralFor("ballerinax__github", FixtureCorpus.FIXTURE_VERSION.text())),
                1);
        Assert.assertEquals(split.field("message"),
                "'repos :owner' is not a resource method of Client in ballerinax/github:"
                        + FixtureCorpus.FIXTURE_VERSION.text());
        Assert.assertEquals(split.field("command"), under.formatted("repos"));
    }

    @Test
    public void aPathWithAnAccessorItDoesNotDeclareFailsWithThatPathsListing() {
        for (List<String> selectors : List.of(List.of("user/keys/:keyId", "post"),
                List.of("post", "user/keys/:keyId"))) {
            List<String> argv = new ArrayList<>(List.of("resource"));
            argv.addAll(selectors);
            argv.addAll(List.of("-p", "ballerinax/github", "--client", "Client"));
            Capture capture = new Capture();
            Assert.assertEquals(Cli.run(argv, capture.streams(),
                    centralFor("ballerinax__github", FixtureCorpus.FIXTURE_VERSION.text())), 1);
            Assert.assertEquals(capture.field("suggestion"),
                    "Name one of the resource methods under user/keys/:keyId below.");
            Assert.assertEquals(capture.failure().getAsJsonArray("candidates").toString(),
                    "[\"user/keys/:keyId delete\",\"user/keys/:keyId get\"]");
        }
    }

    @Test
    public void aFilterMatchingOnlyDocumentationListsThoseMatchesAndNothingElse() {
        List<String> argv = List.of("client", "Client", "-p", "ballerina/http", "--filter", "payload");
        JsonObject json = JsonParser.parseString(
                run(argv, "ballerina__http", FixtureCorpus.FIXTURE_VERSION.text(), false).stdout())
                .getAsJsonObject();
        Assert.assertEquals(json.get("total").getAsInt(), 0, json.toString());
        Assert.assertFalse(json.getAsJsonArray("documented").isEmpty(), json.toString());
        Assert.assertFalse(json.has("next"), json.toString());

        String text = run(argv, "ballerina__http", FixtureCorpus.FIXTURE_VERSION.text(), true).stdout();
        List<String> next = text.lines().filter(line -> line.strip().startsWith("Next:")).toList();
        Assert.assertEquals(next.size(), new HashSet<>(next).size(), text);
    }

    @Test
    public void aPathAndItsAccessorAreBothReadInEitherOrderButNeverAsOneArgument() {
        for (List<String> selectors : List.of(List.of("gists/'public", "get"), List.of("get", "gists/public"))) {
            List<String> argv = new ArrayList<>(List.of("resource"));
            argv.addAll(selectors);
            argv.addAll(List.of("-p", "ballerinax/github", "--client", "Client"));
            JsonObject json = JsonParser.parseString(
                    run(argv, "ballerinax__github", FixtureCorpus.FIXTURE_VERSION.text(), false).stdout())
                    .getAsJsonObject();
            Assert.assertEquals(json.get("path").getAsString(), "gists/public", String.join(" ", argv));
            Assert.assertEquals(json.get("accessor").getAsString(), "get", String.join(" ", argv));
        }
        Capture one = new Capture();
        Assert.assertEquals(Cli.run(List.of("resource", "get gists/'public", "-p", "ballerinax/github", "--client",
                "Client"), one.streams(), centralFor("ballerinax__github", FixtureCorpus.FIXTURE_VERSION.text())), 1);
        Assert.assertEquals(one.field("kind"), "symbol-not-found", one.stderr());
    }

    @Test
    public void anUnknownFlagListsEveryFlagTheGrammarAccepts() {
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("package", "ballerina/http", "--nonesuch"), capture.streams(), never()), 2);
        Assert.assertEquals(capture.field("message"), "unknown option '--nonesuch'");
        Assert.assertEquals(capture.field("suggestion"), "Run 'bal discover package --help' for the usage.");
    }

    @Test
    public void theRenamedOutputFlagIsUnknown() {
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("package", "ballerina/http", "--output", "json"), capture.streams(),
                never()), 2);
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.field("kind"), "usage");
        Assert.assertTrue(capture.field("message").contains("'--output'"), capture.stderr());
        Assert.assertEquals(capture.field("suggestion"), "Run 'bal discover package --help' for the usage.");
    }

    @Test
    public void theToolsVersionIsPrintedOnStdoutAtTheRootOnly() {
        for (String flag : List.of("-V", "--version")) {
            Capture capture = new Capture();
            Assert.assertEquals(Cli.run(List.of(flag), capture.streams(), never()), 0, flag);
            Assert.assertEquals(capture.stderr(), "", flag);
            Assert.assertTrue(capture.stdout().matches("\\d+\\.\\d+\\.\\d+\n"), capture.stdout());
        }
        for (List<String> argv : List.of(List.of("client", "-p", "ballerina/http", "-V"),
                List.of("package", "ballerina/http", "--version", "2.15.7"))) {
            Capture capture = new Capture();
            Assert.assertEquals(Cli.run(argv, capture.streams(), never()), 2, argv.toString());
            Assert.assertEquals(capture.stdout(), "");
            Assert.assertTrue(capture.field("message").startsWith("unknown option '-"), capture.stderr());
        }
        Capture valued = new Capture();
        Assert.assertEquals(Cli.run(List.of("--version=2.15.7"), valued.streams(), never()), 2);
        Assert.assertEquals(valued.field("message"), "--version takes no value");
    }

    @Test
    public void aSwitchGivenAValueIsAUsageErrorEvenWhenTheValueIsABoolean() {
        for (String value : List.of("true", "false")) {
            Capture capture = new Capture();
            Assert.assertEquals(Cli.run(List.of("client", "-p", "ballerinax/kafka", "--refresh=" + value),
                    capture.streams(), never()), 2, value);
            Assert.assertEquals(capture.field("message"), "--refresh takes no value");
        }
    }

    @Test
    public void aModuleNameBallerinaWouldRejectIsAUsageErrorBeforeAnythingIsFetched() {
        for (String module : List.of("bad!name", "a b", "")) {
            Capture capture = new Capture();
            Assert.assertEquals(Cli.run(List.of("client", "-p", "ballerina/graphql", "--module", module),
                    capture.streams(), never()), 2, module);
            Assert.assertEquals(capture.field("message"), "'" + module + "' is not a valid --module value");
        }
        Capture abbreviated = new Capture();
        Assert.assertEquals(Cli.run(List.of("client", "-p", "ballerina/graphql", "-m", "subgraph"),
                abbreviated.streams(), never()), 2);
        Assert.assertEquals(abbreviated.field("message"), "unknown option '-m'");
    }

    @Test
    public void aFlagWithoutItsValueIsNamedWithBothSpellings() {
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("package", "ballerina/http", "--module"), capture.streams(), never()), 2);
        Assert.assertEquals(capture.field("message"), "the --module option needs a value");
        Assert.assertEquals(capture.field("suggestion"), "Write --module <name> or --module=<name>.");
    }

    @Test
    public void aFlagWithAValueOfTheWrongTypeIsAUsageError() {
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("package", "ballerina/http", "--page", "x"), capture.streams(), never()),
                2);
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.field("kind"), "usage");
        Assert.assertEquals(capture.field("message"), "'x' is not a valid --page value");
        Assert.assertEquals(capture.field("suggestion"), "Write --page <n>.");
    }

    @Test
    public void anIncompleteVersionIsAValidationFailureThatFetchesNothing() {
        for (String coordinate : List.of("ballerina/http:2.15", "ballerina/http:latest")) {
            Capture capture = new Capture();
            Assert.assertEquals(Cli.run(List.of("client", "-p", coordinate), capture.streams(), never()), 2,
                    coordinate);
            Assert.assertEquals(capture.stdout(), "");
            Assert.assertEquals(capture.field("kind"), "usage", coordinate);
            Assert.assertTrue(capture.field("message").contains("is not a complete version"), capture.stderr());
            Assert.assertTrue(capture.field("suggestion").contains("ballerina/http:<major>.<minor>.<patch>"),
                    capture.stderr());
        }
    }

    @Test
    public void aVersionShapedArgumentIsRejectedWithTheRuleThatReplacedIt() {
        for (List<String> argv : List.of(
                List.of("client", "4.6.5", "-p", "ballerinax/kafka"),
                List.of("service", "4.6.5", "-p", "ballerinax/kafka"),
                List.of("class", "4.6.5", "-p", "ballerinax/kafka"),
                List.of("function", "4.6.5", "-p", "ballerinax/kafka"))) {
            Capture capture = new Capture();
            Assert.assertEquals(Cli.run(argv, capture.streams(), never()), 2, String.join(" ", argv));
            Assert.assertEquals(capture.stdout(), "", String.join(" ", argv));
            Assert.assertEquals(capture.field("kind"), "usage", String.join(" ", argv));
            Assert.assertEquals(capture.field("message"), "'4.6.5' looks like a version", capture.stderr());
            String command = "bal discover " + argv.get(0) + " -p ballerinax/kafka:4.6.5";
            Assert.assertEquals(capture.field("suggestion"), "To read a specific version, write it after the package.",
                    capture.stderr());
            Assert.assertEquals(capture.field("command"), command, capture.stderr());
        }
        Capture pkg = new Capture();
        Assert.assertEquals(Cli.run(List.of("package", "ballerina/http", "2.15.0"), pkg.streams(), never()), 2);
        Assert.assertEquals(pkg.field("command"), "bal discover package ballerina/http:2.15.0", pkg.stderr());
    }

    @Test
    public void aFixIsTheCallersWholeCommandWithItApplied() {
        record Case(List<String> argv, String command) { }
        for (Case each : List.of(
                new Case(List.of("method", "4.6.5", "-p", "ballerinax/kafka", "--client", "Producer", "--filter",
                        "send"), "bal discover method -p ballerinax/kafka:4.6.5 --client Producer --filter send"),
                new Case(List.of("client", "-p", "ballerinax/kafka@4.6.5", "--module", "sub", "--page", "2"),
                        "bal discover client -p ballerinax/kafka:4.6.5 --module sub --page 2"))) {
            Capture capture = new Capture();
            Assert.assertEquals(Cli.run(each.argv(), capture.streams(), never()), 2, each.argv().toString());
            Assert.assertEquals(capture.field("command"), each.command(), capture.stderr());
            Assert.assertFalse(capture.field("suggestion").contains("`"), capture.stderr());
        }
    }

    @Test
    public void aModuleThePackageDoesNotPublishOffersTheWholeCommandOnItsDefaultModule() {
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("type", "-p", "ballerina/graphql", "--module", "nosuch", "--filter", "x"),
                capture.streams(), options(graphqlCentral())), 1, capture.stderr());
        Assert.assertEquals(capture.field("command"), "bal discover type -p ballerina/graphql --filter x");
        Assert.assertEquals(capture.field("suggestion"), "ballerina/graphql publishes these submodules: dataloader, "
                + "subgraph. Pass one of them to --module, or drop --module for the default module.");
        Assert.assertEquals(capture.failure().get("candidates").toString(), "[\"dataloader\",\"subgraph\"]");
    }

    @Test
    public void aModuleCoordinateFailsWithTheCommandThatReadsItAndFetchesNoDocs() {
        FakeTransport transport = FakeTransport.routing(url -> {
            if (url.endsWith("/registry/packages/ballerinax/aws/1.0.2")) {
                return FakeTransport.ok("{\"modules\":[{\"name\":\"aws\"},{\"name\":\"aws.auth\"}]}");
            }
            return url.endsWith("/registry/packages/ballerinax/aws")
                    ? FakeTransport.ok("[\"1.0.2\"]")
                    : FakeTransport.status(404);
        });
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("package", "ballerinax/aws.auth"), capture.streams(), options(transport)),
                1);
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.field("kind"), "package-not-found");
        Assert.assertEquals(capture.field("suggestion"), "'ballerinax/aws.auth' is not a package: it is the 'auth' "
                + "module of the ballerinax/aws package. Read it with --module.");
        Assert.assertEquals(capture.field("command"), "bal discover package ballerinax/aws --module auth");
        Assert.assertTrue(transport.urls().stream().noneMatch(url -> url.contains("/docs/")),
                transport.urls().toString());
    }

    @Test
    public void shortHelpWorksWhereverItAppears() {
        for (List<String> argv : List.of(
                List.of("-h"),
                List.of("package", "ballerinax/github", "-h"),
                List.of("client", "-p", "ballerinax/github", "-h"))) {
            Capture capture = new Capture();
            Assert.assertEquals(Cli.run(argv, capture.streams(), never()), 0, String.join(" ", argv));
            Assert.assertTrue(capture.stdout().contains("\nUsage: bal discover "), String.join(" ", argv));
        }
    }

    @Test
    public void aVersionIsResolvedFromTheProjectAndIsNotAnArgument() {
        Path projectDir = tempDir();
        write(projectDir.resolve("Ballerina.toml"), "[package]\norg = \"acme\"\nname = \"app\"\n");
        write(projectDir.resolve("Dependencies.toml"),
                "[[package]]\norg = \"ballerinax\"\nname = \"kafka\"\nversion = \"4.6.5\"\n");

        for (List<String> argv : List.of(
                List.of("package", "ballerinax/kafka"),
                List.of("client", "-p", "ballerinax/kafka"),
                List.of("service", "-p", "ballerinax/kafka"),
                List.of("class", "-p", "ballerinax/kafka"),
                List.of("function", "-p", "ballerinax/kafka"))) {
            Capture capture = new Capture();
            // The transport asserts the registry is never reached, which is what proves the lock was used.
            Assert.assertEquals(
                    Cli.run(argv, capture.streams(), docsOnlyFor("ballerinax__kafka"), projectDir.toString()),
                    0, String.join(" ", argv) + " -> " + capture.stderr());
            Assert.assertFalse(capture.stdout().isEmpty(), String.join(" ", argv));
        }
    }

    @Test
    public void aPinnedVersionBeatsALockedOneAndIsCarriedIntoEveryPrintedCommand() {
        Path projectDir = tempDir();
        write(projectDir.resolve("Ballerina.toml"), "[package]\norg = \"acme\"\nname = \"app\"\n");
        write(projectDir.resolve("Dependencies.toml"),
                "[[package]]\norg = \"ballerinax\"\nname = \"kafka\"\nversion = \"4.6.4\"\n");
        String docs = FixtureCorpus.loadRawFixture("ballerinax__kafka").toString();
        FakeTransport transport = FakeTransport.routing(url -> url.contains("/docs/")
                ? FakeTransport.ok(docs)
                : FakeTransport.status(404));

        Capture capture = new Capture();
        List<String> argv = List.of("client", "-p", "ballerinax/kafka:4.6.5");
        Assert.assertEquals(Cli.run(argv, capture.streams(), options(transport), projectDir.toString()), 0,
                capture.stderr());
        List<String> urls = transport.urls();
        Assert.assertTrue(urls.stream().noneMatch(url -> url.contains("4.6.4")), urls.toString());
        Assert.assertTrue(urls.stream().anyMatch(url -> url.contains("4.6.5")), urls.toString());
        String pinned = "\"bal discover client Producer -p ballerinax/kafka:4.6.5\"";
        Assert.assertTrue(capture.stdout().contains(pinned), capture.stdout());
    }

    @Test
    public void aPinnedVersionAndAModuleAreBothCarriedIntoEveryPrintedCommand() {
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(
                List.of("type", "-p", "ballerina/graphql:" + GRAPHQL_VERSION, "--module", "subgraph",
                        "--output-format", "json"),
                capture.streams(), options(graphqlCentral())), 0, capture.stderr());
        String carried = "bal discover type FederatedEntity -p ballerina/graphql:" + GRAPHQL_VERSION
                + " --module subgraph";
        Assert.assertTrue(capture.stdout().contains(carried), capture.stdout());
        Assert.assertTrue(capture.stdout().startsWith("{\"qualified\":\"ballerina/graphql\",\"version\":\""
                + GRAPHQL_VERSION + "\",\"module\":\"subgraph\","), capture.stdout());
    }

    @Test
    public void everyAnswerSaysTheVersionItReadWhereverItCameFrom() {
        Path projectDir = tempDir();
        write(projectDir.resolve("Ballerina.toml"), "[package]\norg = \"acme\"\nname = \"app\"\n");
        write(projectDir.resolve("Dependencies.toml"),
                "[[package]]\norg = \"ballerinax\"\nname = \"kafka\"\nversion = \"4.6.5\"\n");
        HttpOptions http = centralFor("ballerinax__kafka", "9.9.9");
        Capture json = new Capture();
        Assert.assertEquals(Cli.run(List.of("client", "-p", "ballerinax/kafka"), json.streams(), http,
                projectDir.toString()), 0, json.stderr());
        JsonObject answer = JsonParser.parseString(json.stdout()).getAsJsonObject();
        Assert.assertEquals(answer.get("version").getAsString(), "4.6.5");
        Assert.assertEquals(answer.get("qualified").getAsString(), "ballerinax/kafka",
                "an answer names its package as a failure does: " + json.stdout());
        Assert.assertFalse(answer.has("module"), json.stdout());
        Capture text = new Capture();
        Cli.run(List.of("client", "-p", "ballerinax/kafka"), text.streams(), http, projectDir.toString(), true);
        Assert.assertTrue(text.stdout().startsWith("ballerinax/kafka:4.6.5 · client\n"), text.stdout());
        Assert.assertFalse(text.stdout().contains("bal discover package ballerinax/kafka:4.6.5"),
                "a locked version is read again, not pinned: " + text.stdout());
    }

    @Test
    public void aMalformedLockIsAValidationFailureNamingTheLockAndTheCoordinate() {
        for (String locked : List.of("2 15", "1.6", "2.15.7..")) {
            Path projectDir = tempDir();
            write(projectDir.resolve("Ballerina.toml"), "[package]\norg = \"acme\"\nname = \"app\"\n");
            write(projectDir.resolve("Dependencies.toml"),
                    "[[package]]\norg = \"ballerina\"\nname = \"http\"\nversion = \"" + locked + "\"\n");
            Capture capture = new Capture();
            Assert.assertEquals(Cli.run(List.of("package", "ballerina/http"), capture.streams(), never(),
                    projectDir.toString()), Failure.USAGE, locked);
            Assert.assertEquals(capture.field("kind"), "usage", locked);
            Path lock = projectDir.resolve("Dependencies.toml");
            Assert.assertEquals(capture.field("message"), lock + " locks ballerina/http at '" + locked
                    + "', which is not a complete version");
            Assert.assertEquals(capture.field("suggestion"), "Fix the ballerina/http entry in " + lock + ", or "
                    + "delete the file and run 'bal build' to regenerate it; or write a version after the package: "
                    + "ballerina/http:<version>.");
        }
    }

    @Test
    public void aVersionInTheCoordinateIsReadWhateverTheLockAndADottedNameKeepsItsDots() {
        Path projectDir = tempDir();
        write(projectDir.resolve("Ballerina.toml"), "[package]\norg = \"acme\"\nname = \"app\"\n");
        write(projectDir.resolve("Dependencies.toml"),
                "[[package]]\norg = \"ballerinax\"\nname = \"googleapis.gmail\"\nversion = \"4.0.0\"\n");
        FakeTransport transport = FakeTransport.always(FakeTransport.status(404));
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("package", "ballerinax/googleapis.gmail:4.2.1"), capture.streams(),
                options(transport), projectDir.toString()), 1);
        Assert.assertTrue(transport.urls().stream().anyMatch(url -> url.endsWith("/googleapis.gmail/4.2.1")),
                transport.urls().toString());
        Assert.assertTrue(transport.urls().stream().noneMatch(url -> url.contains("4.0.0")),
                transport.urls().toString());
        Assert.assertEquals(capture.field("kind"), "package-not-found");
        Assert.assertEquals(capture.field("qualified"), "ballerinax/googleapis.gmail");
        Assert.assertEquals(capture.field("version"), "4.2.1");
    }

    @Test
    public void aFailureNamesThePackageAndTheVersionApartAndOmitsAVersionItNeverReached() {
        Capture missing = new Capture();
        Assert.assertEquals(Cli.run(List.of("client", "NoSuchContainer", "-p", "ballerinax/kafka"),
                missing.streams(), centralFor("ballerinax__kafka", "4.6.5")), 1);
        Assert.assertEquals(missing.field("qualified"), "ballerinax/kafka");
        Assert.assertEquals(missing.field("version"), "4.6.5");

        Capture unknown = new Capture();
        Assert.assertEquals(Cli.run(List.of("package", "ballerina/htp"), unknown.streams(),
                options(FakeTransport.always(FakeTransport.status(404)))), 1);
        Assert.assertEquals(unknown.field("kind"), "package-not-found");
        Assert.assertEquals(unknown.field("qualified"), "ballerina/htp");
        Assert.assertFalse(unknown.failure().has("version"), unknown.stderr());
    }

    @Test
    public void aFailureUnderAModuleNamesThePackageAndTheModuleApart() {
        Capture json = new Capture();
        Assert.assertEquals(Cli.run(List.of("type", "Nope", "-p", "ballerina/graphql", "--module", "subgraph"),
                json.streams(), options(graphqlCentral())), 1);
        Assert.assertEquals(json.field("kind"), "symbol-not-found");
        Assert.assertEquals(json.field("qualified"), "ballerina/graphql");
        Assert.assertEquals(json.field("version"), GRAPHQL_VERSION);
        Assert.assertEquals(json.field("module"), "subgraph");

        Capture text = new Capture();
        Assert.assertEquals(Cli.run(List.of("type", "Nope", "-p", "ballerina/graphql", "--module", "subgraph",
                "--output-format", "text"), text.streams(), options(graphqlCentral())), 1);
        Assert.assertTrue(text.stderr().startsWith("bal discover: error: 'Nope' is not a type in ballerina/graphql:"
                + GRAPHQL_VERSION + ", module subgraph\n"), text.stderr());

        Capture plain = new Capture();
        Assert.assertEquals(Cli.run(List.of("type", "Nope", "-p", "ballerina/graphql"), plain.streams(),
                options(graphqlCentral())), 1);
        Assert.assertFalse(plain.failure().has("module"), plain.stderr());
    }

    @Test
    public void aCommandIsPinnedOnlyWhenTheCallerWroteAVersion() {
        Path projectDir = tempDir();
        write(projectDir.resolve("Ballerina.toml"), "[package]\norg = \"acme\"\nname = \"app\"\n");
        write(projectDir.resolve("Dependencies.toml"),
                "[[package]]\norg = \"ballerinax\"\nname = \"kafka\"\nversion = \"4.6.5\"\n");
        String docs = FixtureCorpus.loadRawFixture("ballerinax__kafka").toString();
        FakeTransport transport = FakeTransport.routing(url -> url.contains("/docs/")
                ? FakeTransport.ok(docs)
                : FakeTransport.status(404));
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("client", "-p", "ballerinax/kafka"), capture.streams(),
                options(transport), projectDir.toString()), 0, capture.stderr());
        Assert.assertTrue(transport.urls().stream().anyMatch(url -> url.endsWith("/docs/ballerinax/kafka/4.6.5")),
                transport.urls().toString());
        Assert.assertTrue(transport.urls().stream()
                .noneMatch(url -> url.endsWith("/registry/packages/ballerinax/kafka")), transport.urls().toString());
        Assert.assertTrue(capture.stdout().contains("\"bal discover client Producer -p ballerinax/kafka\""),
                capture.stdout());
        Assert.assertFalse(capture.stdout().contains(":4.6.5"), capture.stdout());
    }

    @Test
    public void anEnumMemberIsAnsweredByItsEnumWithANoteSayingSo() {
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("type", "HTTP_1_1", "-p", "ballerina/http", "--output-format", "json"),
                capture.streams(), centralFor("ballerina__http", "2.16.6")), 0, capture.stderr());
        JsonObject answer = JsonParser.parseString(capture.stdout()).getAsJsonObject();
        Assert.assertEquals(answer.get("name").getAsString(), "HttpVersion");
        Assert.assertEquals(answer.get("kind").getAsString(), "enum");
        Assert.assertTrue(answer.get("note").getAsString().startsWith("'HTTP_1_1' is a member of the enum HttpVersion"),
                capture.stdout());
    }

    @Test
    public void aListingWithLaterPagesPointsAtFilterAndALastOrFilteredOneDoesNot() {
        HttpOptions kafka = centralFor("ballerinax__kafka", "4.6.5");
        String hint = "Next: bal discover type -p ballerinax/kafka --filter <keyword>";
        Assert.assertTrue(text(kafka, "type", "-p", "ballerinax/kafka").contains(hint));
        Assert.assertFalse(text(kafka, "type", "-p", "ballerinax/kafka", "--page", "2").contains("--filter <keyword>"));
        Assert.assertFalse(text(kafka, "type", "-p", "ballerinax/kafka", "--filter", "Config").contains("<keyword>"));
    }

    private static String text(HttpOptions http, String... argv) {
        Capture capture = new Capture();
        List<String> withOutput = new java.util.ArrayList<>(List.of(argv));
        withOutput.addAll(List.of("--output-format", "text"));
        Assert.assertEquals(Cli.run(withOutput, capture.streams(), http), 0, capture.stderr());
        return capture.stdout();
    }

    @Test
    public void aRosterAndAResourceListingAreAlphabetical() {
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("client", "-p", "ballerina/http", "--output-format", "json"),
                capture.streams(), centralFor("ballerina__http", "2.16.6")), 0, capture.stderr());
        List<String> names = new java.util.ArrayList<>();
        JsonParser.parseString(capture.stdout()).getAsJsonObject().getAsJsonArray("containers")
                .forEach(each -> names.add(each.getAsJsonObject().get("name").getAsString()));
        List<String> sorted = new java.util.ArrayList<>(names);
        sorted.sort(Texts.LOCALE_ORDER);
        Assert.assertEquals(names, sorted);
        Assert.assertTrue(names.size() > 1);
    }

    private static HttpOptions docsOnlyFor(String slug) {
        String docs = FixtureCorpus.loadRawFixture(slug).toString();
        return options(FakeTransport.routing(url -> url.contains("/docs/")
                ? FakeTransport.ok(docs)
                : FakeTransport.status(404)));
    }

    @Test
    public void aProjectIsReadOnlyFromTheDirectoryTheProcessStandsInNeverAParent() {
        Path projectDir = tempDir();
        write(projectDir.resolve("Ballerina.toml"), "[package]\nname = \"app\"\n");
        Path nested = projectDir.resolve("modules").resolve("deep");
        mkdirs(nested);
        Assert.assertEquals(DependenciesToml.projectAt(projectDir), projectDir);
        Assert.assertNull(DependenciesToml.projectAt(nested));
        Assert.assertNull(DependenciesToml.projectAt(tempDir()));
    }

    @Test
    public void aContainerVerbNavigatesAClientsPathsAsStructuredJson() {
        // JSON is the default off a TTY.
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("client", "-p", "ballerinax/googleapis.gmail"), capture.streams(),
                centralFor("ballerinax__googleapis.gmail", "4.2.0"));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertTrue(capture.stdout().contains("\"resources\":["), capture.stdout());
        Assert.assertTrue(capture.stdout().contains("\"path\":"), capture.stdout());
    }

    @Test
    public void aPackageWithSeveralClientsIsARosterRatherThanAFailure() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("client", "-p", "ballerina/http"), capture.streams(),
                centralFor("ballerina__http", "2.16.6"));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        for (String name : List.of("Client", "FailoverClient", "LoadBalanceClient", "StatusCodeClient")) {
            Assert.assertTrue(capture.stdout().contains("\"name\":\"" + name + "\""), name);
            Assert.assertTrue(
                    capture.stdout().contains("\"command\":\"bal discover client " + name + " -p ballerina/http\""),
                    name);
        }
    }

    @Test
    public void namingAContainerResolvesIt() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("client", "FailoverClient", "-p", "ballerina/http"),
                capture.streams(), centralFor("ballerina__http", "2.16.6"));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        JsonObject json = JsonParser.parseString(capture.stdout()).getAsJsonObject();
        Assert.assertTrue(json.has("resources") && json.has("remote"), capture.stdout());
        Assert.assertTrue(json.getAsJsonArray("remote").toString().contains("\"submit\""), capture.stdout());
    }

    @Test
    public void namingSomethingNoBucketHoldsFailsWithThePackagesSearchAndNoCandidates() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("client", "FailoverClientt", "-p", "ballerina/http"), capture.streams(),
                centralFor("ballerina__http", "2.16.6"));
        Assert.assertEquals(exitCode, 1);
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.field("kind"), "symbol-not-found");
        Assert.assertFalse(capture.failure().has("command"), capture.stderr());
        Assert.assertTrue(capture.failure().getAsJsonArray("candidates").toString().contains("\"FailoverClient\""),
                capture.stderr());
        Assert.assertEquals(capture.field("suggestion"), "Name one of the clients below, or search the package with "
                + "'bal discover search FailoverClientt -p ballerina/http'.");
        Assert.assertTrue(capture.failure().getAsJsonArray("requested").toString().contains("FailoverClientt"),
                capture.stderr());
    }

    @Test
    public void aPackageWithNoModuleFunctionsGetsAnHonestEmptyAnswerAtExit0() {
        Capture json = run(List.of("function", "-p", "ballerinax/kafka"), "ballerinax__kafka", "4.6.5", false);
        JsonObject empty = JsonParser.parseString(json.stdout()).getAsJsonObject();
        Assert.assertEquals(empty.get("bucket").getAsString(), "function");
        Assert.assertEquals(empty.get("total").getAsInt(), 0);
        Assert.assertTrue(json.stdout().contains("\"command\":\"bal discover client -p ballerinax/kafka\""),
                json.stdout());

        Capture text = run(List.of("function", "-p", "ballerinax/kafka"), "ballerinax__kafka", "4.6.5", true);
        Assert.assertTrue(text.stdout().startsWith("ballerinax/kafka:4.6.5 · function\n"
                        + "ballerinax/kafka has no functions.\n\n"
                        + "Elsewhere in the package\n"
                        + "  client    3  bal discover client -p ballerinax/kafka\n"),
                text.stdout());
    }

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
        List<String> argv = List.of("method", "send", "-p", "ballerinax/kafka", "--client", "Producer");
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
        Assert.assertTrue(text.startsWith("ballerinax/kafka:4.6.5 · method · Producer · send\n\n# "),
                "the header, then the declaration's own doc comment: " + text);
        Assert.assertTrue(text.contains("remote function send("), text);
        Assert.assertTrue(text.contains("\n\nTypes it uses (2)\n  # "), text);
    }

    @Test
    public void aResourceSignatureNamesItsPathAndAccessorInJson() {
        List<String> argv = List.of("resource", "repos/:owner/:repo/actions/caches", "delete", "-p",
                "ballerinax/github", "--client", "Client");
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
            List<String> argv = new ArrayList<>(listing[2] == null
                    ? List.of("client", "-p", listing[0])
                    : List.of("resource", listing[2], "-p", listing[0], "--client", "Client"));
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
        List<String> argv = List.of("client", "Client", "-p", "ballerina/http");
        JsonObject json = JsonParser.parseString(run(argv, "ballerina__http", "2.16.6", false).stdout())
                .getAsJsonObject();
        Assert.assertFalse(json.getAsJsonArray("resources").isEmpty(), json.toString());
        Assert.assertTrue(json.getAsJsonArray("remote").toString().contains("\"execute\""), json.toString());
        Assert.assertTrue(json.getAsJsonArray("normal").toString().contains("\"getCookieStore\""), json.toString());
        Assert.assertEquals(json.get("shown").getAsInt(), json.get("total").getAsInt());

        String text = run(argv, "ballerina__http", "2.16.6", true).stdout();
        Assert.assertTrue(text.startsWith("ballerina/http:2.16.6 · client · Client\n"), text);
        Assert.assertTrue(text.contains("\n\nResource methods (->)\n  :...path  get, "), text);
        Assert.assertTrue(text.contains("\n\nRemote methods (->)\n  delete\n  execute\n"), text);
        Assert.assertTrue(text.contains("\n\nMethods (.)\n  circuitBreakerForceClose\n"), text);
    }

    @Test
    public void aSelectorThatMatchesNothingFailsInEitherRenderingWithTheListingToRunNext() {
        List<String> argv = List.of("method", "sendd", "-p", "ballerinax/kafka", "--client", "Producer");
        Capture json = new Capture();
        Assert.assertEquals(Cli.run(argv, json.streams(), centralFor("ballerinax__kafka", "4.6.5")), 1);
        Assert.assertEquals(json.stdout(), "");
        Assert.assertEquals(json.stderr(), "{\"severity\":\"error\",\"kind\":\"symbol-not-found\",\"message\":"
                + "\"'sendd' is not a method of Producer in ballerinax/kafka:4.6.5\","
                + "\"qualified\":\"ballerinax/kafka\",\"version\":\"4.6.5\",\"requested\":[\"sendd\"],"
                + "\"suggestion\":\"Name one of the methods of "
                + "Producer below.\",\"candidates\":[\"close\",\"flush\",\"getTopicPartitions\",\"send\","
                + "\"sendWithMetadata\"]}\n");

        Capture text = new Capture();
        Assert.assertEquals(Cli.run(argv, text.streams(), centralFor("ballerinax__kafka", "4.6.5"), null, true), 1);
        Assert.assertEquals(text.stdout(), "");
        Assert.assertEquals(text.stderr(), "bal discover: error: 'sendd' is not a method of Producer in "
                + "ballerinax/kafka:4.6.5\n  Name one of the methods of Producer below.\n  candidates:\n    close\n"
                + "    flush\n    getTopicPartitions\n    send\n    sendWithMetadata\n");
    }

    @Test
    public void aFilterThatMatchesNothingIsExitZeroWithAnEmptyListing() {
        List<String> argv = List.of("client", "-p", "ballerinax/github", "--filter", "zzznopealsonope");
        JsonObject json = JsonParser.parseString(run(argv, "ballerinax__github", "6.0.0", false).stdout())
                .getAsJsonObject();
        Assert.assertEquals(json.get("total").getAsInt(), 0, json.toString());
        Assert.assertEquals(json.getAsJsonArray("resources").size(), 0, json.toString());
    }

    @Test
    public void aMemberOnSeveralContainersFailsWithTheBucketsListing() {
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("method", "'commit", "-p", "ballerinax/kafka"), capture.streams(),
                centralFor("ballerinax__kafka", "4.6.5"), null, true), Failure.USAGE);
        Assert.assertEquals(capture.stderr(), "bal discover: error: 'commit' is declared by Caller and Consumer in "
                + "ballerinax/kafka:4.6.5\n"
                + "  Name the one you mean with --client, --class, --service:\n"
                + "    Caller    bal discover method commit -p ballerinax/kafka --client Caller\n"
                + "    Consumer  bal discover method commit -p ballerinax/kafka --client Consumer\n"
                + "Usage: bal discover method [<name>] -p <org>/<name>[:<version>] [--client|--class|--service <name>] "
                + "[options]\n"
                + "Try 'bal discover method --help' for more information.\n");
    }

    @Test
    public void noContainerAnswerIsMarkdownInEitherRendering() {
        for (List<String> argv : List.of(
                List.of("method", "send", "-p", "ballerinax/kafka", "--client", "Producer"),
                List.of("client", "Producer", "-p", "ballerinax/kafka", "--filter", "nosuch"),
                List.of("function", "-p", "ballerinax/kafka"))) {
            for (boolean interactive : List.of(true, false)) {
                String out = run(argv, "ballerinax__kafka", "4.6.5", interactive).stdout();
                Assert.assertFalse(out.contains("| | |"), out);
                Assert.assertFalse(out.contains("\n```"), out);
            }
        }
    }

    @Test
    public void theReadmeBucketIsTheWholeReadmeVerbatimAtATerminal() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("readme", "-p", "ballerinax/kafka"), capture.streams(),
                centralFor("ballerinax__kafka", "4.6.5"), null, true);
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertTrue(capture.stdout().length() > 500, capture.stdout());
    }

    @Test
    public void theReadmeBucketIsJsonOffATerminal() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("readme", "-p", "ballerinax/kafka"), capture.streams(),
                centralFor("ballerinax__kafka", "4.6.5"));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertTrue(capture.stdout().contains("\"readme\":"), capture.stdout());
        Assert.assertTrue(capture.stdout().contains("\"lines\":"), capture.stdout());
        Assert.assertFalse(capture.stdout().contains("\"section\":"), "the whole readme names no section");
    }

    @Test
    public void aReadmeSectionSelectorNarrowsToOneSection() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("readme", "1", "-p", "ballerinax/kafka"), capture.streams(),
                centralFor("ballerinax__kafka", "4.6.5"));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertTrue(capture.stdout().contains("\"section\":1"), capture.stdout());
        Assert.assertTrue(capture.stdout().contains("\"of\":"), capture.stdout());
    }

    @Test
    public void aReadmeSectionThatDoesNotExistFailsWithCandidates() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("readme", "999", "-p", "ballerinax/kafka"), capture.streams(),
                centralFor("ballerinax__kafka", "4.6.5"));
        Assert.assertEquals(exitCode, 1);
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.field("kind"), "symbol-not-found");
    }

    @Test
    public void theModuleFlagReadsTheSubmodulesOwnPageAndNeverThePackages() {
        FakeTransport transport = graphqlCentral();
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("package", "ballerina/graphql", "--module", "dataloader"), capture.streams(),
                options(transport));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertEquals(transport.urls(), List.of(
                CentralClient.CENTRAL_BASE_URL + "registry/packages/ballerina/graphql",
                CentralClient.CENTRAL_BASE_URL + "docs/ballerina/graphql.dataloader/" + GRAPHQL_VERSION));
        JsonObject overview = JsonParser.parseString(capture.stdout()).getAsJsonObject();
        Assert.assertEquals(overview.getAsJsonObject("classes").get("command").getAsString(),
                "bal discover class -p ballerina/graphql --module dataloader", capture.stdout());
        Assert.assertEquals(overview.getAsJsonObject("types").get("command").getAsString(),
                "bal discover type -p ballerina/graphql --module dataloader", capture.stdout());
        Assert.assertFalse(overview.has("clients"), capture.stdout());
    }

    @Test
    public void theTypeBucketCarriesTheModuleIntoEveryCommandItPrints() {
        Capture roster = new Capture();
        int exitCode = Cli.run(List.of("type", "-p", "ballerina/graphql", "--module", "subgraph"), roster.streams(),
                options(graphqlCentral()));
        Assert.assertEquals(exitCode, 0, roster.stderr());
        Assert.assertEquals(roster.stdout().strip(), "{\"qualified\":\"ballerina/graphql\",\"version\":\""
                + GRAPHQL_VERSION + "\",\"module\":\"subgraph\","
                + "\"sections\":{\"records\":[{\"name\":\"FederatedEntity\","
                + "\"command\":\"bal discover type FederatedEntity -p ballerina/graphql --module subgraph\"},"
                + "{\"name\":\"Representation\",\"command\":\"bal discover type Representation -p ballerina/graphql "
                + "--module subgraph\"}],\"aliases\":[{\"name\":\"ReferenceResolver\",\"command\":"
                + "\"bal discover type ReferenceResolver -p ballerina/graphql --module subgraph\"}],\"constants\":"
                + "[{\"name\":\"ANY\",\"command\":\"bal discover type ANY -p ballerina/graphql --module subgraph\"}],"
                + "\"annotations\":[{\"name\":\"Entity\",\"command\":\"bal discover type Entity -p ballerina/graphql "
                + "--module subgraph\"},{\"name\":\"Subgraph\",\"command\":\"bal discover type Subgraph -p "
                + "ballerina/graphql --module subgraph\"}]},\"counts\":{\"records\":2,\"aliases\":1,\"constants\":1,"
                + "\"annotations\":2},\"shown\":6,\"total\":6}");

        Capture leaf = new Capture();
        Cli.run(List.of("type", "Entity", "-p", "ballerina/graphql", "--module", "subgraph"), leaf.streams(),
                options(graphqlCentral()));
        JsonObject answer = JsonParser.parseString(leaf.stdout()).getAsJsonObject();
        Assert.assertEquals(answer.get("kind").getAsString(), "annotation");
        Assert.assertEquals(answer.getAsJsonArray("types").size(), 2);
        Assert.assertEquals(answer.getAsJsonArray("types").get(0).getAsJsonObject().get("name").getAsString(),
                "FederatedEntity");
    }

    @Test
    public void aTypeFromASiblingSubmoduleIsReachedThroughModuleThoughOnlyChildrenAreListed() {
        JsonElement page = FixtureCorpus.loadRawModulePage("ballerina__graphql.subgraph");
        JsonObject typename = page.getAsJsonObject().getAsJsonObject("docsData").getAsJsonArray("modules").get(0)
                .getAsJsonObject().getAsJsonArray("records").get(1).getAsJsonObject().getAsJsonArray("fields")
                .get(0).getAsJsonObject().getAsJsonObject("type");
        typename.addProperty("name", "DataLoader");
        typename.addProperty("category", "classes");
        typename.addProperty("orgName", "ballerina");
        typename.addProperty("moduleName", "graphql.dataloader");
        typename.addProperty("version", GRAPHQL_VERSION);
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("type", "Representation", "-p", "ballerina/graphql", "--module", "subgraph"),
                capture.streams(), options(graphqlCentral(Map.of("subgraph", page))));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        JsonObject foreign = JsonParser.parseString(capture.stdout()).getAsJsonObject().getAsJsonArray("foreign")
                .get(0).getAsJsonObject();
        Assert.assertEquals(foreign.get("command").getAsString(),
                "bal discover type DataLoader -p ballerina/graphql --module dataloader");
    }

    @Test
    public void theModuleFlagTargetsTheSubmodulesOwnReadme() {
        JsonElement page = FixtureCorpus.loadRawModulePage("ballerina__graphql.dataloader");
        page.getAsJsonObject().getAsJsonObject("docsData").getAsJsonArray("modules").get(0).getAsJsonObject()
                .addProperty("description", "This is graphql.dataloader's own readme.");
        Capture capture = new Capture();
        Cli.run(List.of("readme", "-p", "ballerina/graphql", "--module", "dataloader"), capture.streams(),
                options(graphqlCentral(Map.of("dataloader", page))), null, true);
        Assert.assertTrue(capture.stdout().contains("This is graphql.dataloader's own readme."), capture.stdout());
    }

    @Test
    public void aValidModuleThatPublishesNoReadmeFailsLoudlyRatherThanAnEmptyBody() {
        // As Central really serves graphql's submodules: each page's readme is empty.
        for (List<String> narrowed : List.of(List.<String>of(), List.of("--filter", "kafka"), List.of("1"))) {
            List<String> argv = new ArrayList<>(List.of("readme", "-p", "ballerina/graphql", "--module", "dataloader"));
            argv.addAll(narrowed);
            Capture capture = new Capture();
            int exitCode = Cli.run(argv, capture.streams(), options(graphqlCentral()));
            Assert.assertEquals(exitCode, Failure.RUNTIME, argv + " -> " + capture.stdout());
            Assert.assertEquals(capture.stdout(), "");
            Assert.assertEquals(capture.field("kind"), "symbol-not-found");
            Assert.assertTrue(capture.field("message").contains("publishes no readme for module dataloader"),
                    argv + " -> " + capture.stderr());
            // Under --module the loaded submodules are that module's children, never its siblings or the
            // default module, so their absence says nothing about what else the package publishes.
            Assert.assertFalse(capture.field("suggestion").contains("no other module"), capture.stderr());
            Assert.assertTrue(capture.field("suggestion").contains("'bal discover package ballerina/graphql'"),
                    capture.stderr());
            Assert.assertEquals(capture.field("command"), "bal discover readme -p ballerina/graphql", capture.stderr());
        }
    }

    @Test
    public void aReadmeFilterMatchingNoSectionIsAnEmptyListing() {
        Capture json = new Capture();
        int exitCode = Cli.run(List.of("readme", "-p", "ballerinax/kafka", "--filter", "zzzz"), json.streams(),
                centralFor("ballerinax__kafka", FixtureCorpus.FIXTURE_VERSION.text()));
        Assert.assertEquals(exitCode, 0, json.stderr());
        JsonObject answer = JsonParser.parseString(json.stdout()).getAsJsonObject();
        Assert.assertEquals(answer.get("total").getAsInt(), 0, answer.toString());
        Assert.assertEquals(answer.getAsJsonArray("sections").size(), 0, answer.toString());

        Capture text = new Capture();
        Cli.run(List.of("readme", "-p", "ballerinax/kafka", "--filter", "zzzz"), text.streams(),
                centralFor("ballerinax__kafka", FixtureCorpus.FIXTURE_VERSION.text()), null, true);
        Assert.assertTrue(text.stdout().endsWith("No matching sections.\n"), text.stdout());
    }

    @Test
    public void aReadmeSuggestionQuotesTheSelectorItRepeats() {
        Capture capture = new Capture();
        Cli.run(List.of("readme", "1", "-p", "ballerinax/kafka", "--filter", "zzzz"), capture.streams(),
                centralFor("ballerinax__kafka", FixtureCorpus.FIXTURE_VERSION.text()));
        Assert.assertEquals(capture.field("command"), "bal discover readme 1 -p ballerinax/kafka", capture.stderr());

        Capture sections = new Capture();
        Cli.run(List.of("readme", "-p", "ballerinax/kafka", "--filter", "kafka"), sections.streams(),
                centralFor("ballerinax__kafka", FixtureCorpus.FIXTURE_VERSION.text()));
        String title = null;
        for (JsonElement section : JsonParser.parseString(sections.stdout()).getAsJsonObject()
                .getAsJsonArray("sections")) {
            String candidate = section.getAsJsonObject().get("title").getAsString();
            if (candidate.contains(" ")) {
                title = candidate;
                break;
            }
        }
        Assert.assertNotNull(title, sections.stdout());
        Capture titled = new Capture();
        Cli.run(List.of("readme", title, "-p", "ballerinax/kafka", "--filter", "zzzz"), titled.streams(),
                centralFor("ballerinax__kafka", FixtureCorpus.FIXTURE_VERSION.text()));
        Assert.assertTrue(titled.field("command").endsWith(" readme " + Texts.shellWord(title)
                        + " -p ballerinax/kafka"),
                titled.stderr());
    }

    @Test
    public void aSearchUnderModuleSearchesThatModuleAndAFilteredBarePackagePages() {
        Capture subgraph = new Capture();
        Assert.assertEquals(Cli.run(List.of("search", "entity", "-p", "ballerina/graphql", "--module", "subgraph"),
                subgraph.streams(), options(graphqlCentral())), 0, subgraph.stderr());
        JsonObject found = JsonParser.parseString(subgraph.stdout()).getAsJsonObject();
        Assert.assertEquals(found.get("module").getAsString(), "subgraph");
        Assert.assertTrue(found.get("total").getAsInt() > 0, found.toString());
        Assert.assertTrue(found.toString().contains(" -p ballerina/graphql --module subgraph\""),
                found.toString());

        String version = FixtureCorpus.FIXTURE_VERSION.text();
        Capture second = new Capture();
        Assert.assertEquals(Cli.run(List.of("package", "ballerinax/github", "--filter", "issue", "--page", "2"),
                second.streams(), centralFor("ballerinax__github", version)), 0, second.stderr());
        JsonObject page = JsonParser.parseString(second.stdout()).getAsJsonObject();
        Assert.assertEquals(page.get("page").getAsInt(), 2, page.toString());
        Assert.assertEquals(page.get("keywords").toString(), "[\"issue\"]");
    }

    @Test
    public void aSearchOfTheDefaultModuleListsASubmoduleNamedForTheKeyword() {
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("search", "loader", "-p", "ballerina/graphql"), capture.streams(),
                options(graphqlCentral())), 0, capture.stderr());
        JsonObject found = JsonParser.parseString(capture.stdout()).getAsJsonObject();
        Assert.assertEquals(found.getAsJsonArray("submodules").get(0).toString(),
                "{\"name\":\"dataloader\",\"command\":\"bal discover package ballerina/graphql --module dataloader\"}");
    }

    @Test
    public void aTypeAskedInAContainerBucketIsPointedAtWithItsPlainName() {
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("client", "'ProducerConfiguration", "-p", "ballerinax/kafka"),
                capture.streams(), centralFor("ballerinax__kafka", "4.6.5")), 1, capture.stderr());
        Assert.assertEquals(capture.field("command"), "bal discover type ProducerConfiguration -p ballerinax/kafka");
    }

    @Test
    public void aPageBelowOneIsRejectedBeforeAnythingIsFetched() {
        for (String page : List.of("0", "-1")) {
            Capture capture = new Capture();
            int exitCode = Cli.run(List.of("client", "-p", "ballerinax/github", "--page", page), capture.streams(),
                    never());
            Assert.assertEquals(exitCode, 2);
            Assert.assertEquals(capture.field("kind"), "usage");
            Assert.assertTrue(capture.field("message").contains("numbered from 1"), capture.stderr());
            Assert.assertEquals(capture.field("command"), "bal discover client -p ballerinax/github", capture.stderr());
        }
        Capture search = new Capture();
        Assert.assertEquals(Cli.run(List.of("search", "kafka send", "--page", "0"), search.streams(), never()), 2);
        Assert.assertEquals(search.field("command"), "bal discover search \"kafka send\"", search.stderr());
    }

    @Test
    public void aPageAgainstAnAnswerThatDoesNotPageIsRejectedRatherThanServedAsPageOne() {
        String github = "ballerinax__github";
        List<List<String>> unpaged = List.of(
                List.of("package", "ballerinax/github", "--page", "5"),
                List.of("readme", "-p", "ballerinax/github", "--page", "5"),
                List.of("resource", "gists/'public", "get", "-p", "ballerinax/github", "--client", "Client", "--page",
                        "2"));
        for (List<String> argv : unpaged) {
            Capture capture = new Capture();
            int exitCode = Cli.run(argv, capture.streams(), centralFor(github, FixtureCorpus.FIXTURE_VERSION.text()));
            Assert.assertEquals(exitCode, Failure.USAGE, argv + " -> " + capture.stdout());
            Assert.assertEquals(capture.stdout(), "");
            Assert.assertEquals(capture.field("kind"), "usage");
            Assert.assertTrue(capture.field("message").contains("only one page"), argv + " -> " + capture.stderr());
            Assert.assertEquals(capture.field("suggestion"), "Drop --page.", capture.stderr());
            Assert.assertFalse(capture.field("command").contains("--page"), capture.stderr());
        }
        Capture quoted = new Capture();
        Cli.run(unpaged.get(2), quoted.streams(), centralFor(github, FixtureCorpus.FIXTURE_VERSION.text()));
        Assert.assertEquals(quoted.field("command"),
                "bal discover resource gists/public get -p ballerinax/github --client Client");

        Capture oneGroupedPage = new Capture();
        Assert.assertEquals(Cli.run(List.of("client", "Client", "-p", "ballerinax/github", "--page", "2"),
                oneGroupedPage.streams(), centralFor(github, FixtureCorpus.FIXTURE_VERSION.text())), Failure.RUNTIME);
        Assert.assertTrue(oneGroupedPage.field("message").contains("on 1 page"), oneGroupedPage.stderr());

        Capture emptySearch = new Capture();
        Assert.assertEquals(Cli.run(List.of("client", "Client", "-p", "ballerinax/github", "--filter", "zzzq",
                "--page", "2"), emptySearch.streams(), centralFor(github, FixtureCorpus.FIXTURE_VERSION.text())),
                Failure.RUNTIME);
        Assert.assertEquals(emptySearch.field("message"),
                "page 2 is out of range: this listing has 0 entries on 1 page");
    }

    @Test
    public void aPageOutOfRangeNamesThePackageVersionAndTheCommandThatWorks() {
        String github = "ballerinax__github";
        Capture beyond = new Capture();
        Assert.assertEquals(Cli.run(List.of("client", "Client", "-p", "ballerinax/twilio", "--page", "99"),
                beyond.streams(), centralFor("ballerinax__twilio", FixtureCorpus.FIXTURE_VERSION.text())),
                Failure.RUNTIME);
        Assert.assertEquals(beyond.field("kind"), "page-not-found");
        Assert.assertEquals(beyond.field("qualified"), "ballerinax/twilio");
        Assert.assertEquals(beyond.field("version"), FixtureCorpus.FIXTURE_VERSION.text());
        Assert.assertEquals(beyond.field("command"), "bal discover client Client -p ballerinax/twilio --page 5");

        Capture single = new Capture();
        Assert.assertEquals(Cli.run(List.of("client", "Client", "-p", "ballerinax/github", "--filter", "zzzq",
                "--page", "2"), single.streams(), centralFor(github, FixtureCorpus.FIXTURE_VERSION.text())),
                Failure.RUNTIME);
        Assert.assertTrue(single.field("suggestion").startsWith("This listing has only one page"),
                single.stderr());
        Assert.assertEquals(single.field("command"), "bal discover client Client -p ballerinax/github --filter zzzq");
    }

    @Test
    public void aWholeReadmeInTextFollowsTheHeaderLineEveryAnswerHas() {
        String text = text(centralFor("ballerinax__kafka", "4.6.5"), "readme", "-p", "ballerinax/kafka");
        Assert.assertTrue(text.startsWith("ballerinax/kafka:4.6.5 · readme\n\n"), text);
    }

    @Test
    public void aBlankFilterIsAUsageError() {
        for (String argument : List.of("--filter=", "--filter= ")) {
            Capture capture = new Capture();
            Assert.assertEquals(Cli.run(List.of("client", "-p", "ballerinax/kafka", argument), capture.streams(),
                    never()),
                    2, argument);
            Assert.assertEquals(capture.field("message"), "the --filter option needs a keyword");
        }
    }

    /** A member found in another bucket on several containers keeps the note saying so. */
    @Test
    public void aNameAskedInTheWrongBucketFailsWithTheCommandInTheBucketThatHoldsIt() {
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("client", "Response", "-p", "ballerina/http"), capture.streams(),
                centralFor("ballerina__http", "2.16.6"), null, true), 1);
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.stderr(), "bal discover: error: 'Response' is not a client in "
                + "ballerina/http:2.16.6\n  It is a class.\n  Try: bal discover class Response -p ballerina/http\n");
    }

    @Test
    public void theBarePackageListsItsSubmodulesAlongsideItsOwnBucketsInText() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("package", "ballerina/graphql"), capture.streams(),
                options(graphqlCentral()), null, true);
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertTrue(capture.stdout().contains("\n\nSubmodules\n"), capture.stdout());
        Assert.assertTrue(capture.stdout().contains(
                "\n  dataloader  bal discover package ballerina/graphql --module dataloader  "
                        + "This module provides a way to load data from a data source with batching and caching"),
                capture.stdout());
        Assert.assertTrue(capture.stdout().contains(
                "\n  subgraph    bal discover package ballerina/graphql --module subgraph    "
                        + "This module provides a way to create a subgraphs for a federated GraphQL service"),
                capture.stdout());
        // The default module addresses itself through its own buckets, never through the submodule list.
        Assert.assertFalse(capture.stdout().contains("--module graphql"), capture.stdout());
    }

    @Test
    public void theBarePackageListsItsSubmodulesAsJsonOffATerminal() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("package", "ballerina/graphql"), capture.streams(), options(graphqlCentral()));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertTrue(capture.stdout().contains("\"submodules\":["), capture.stdout());
        Assert.assertTrue(
                capture.stdout().contains("\"command\":\"bal discover package ballerina/graphql --module dataloader\""),
                capture.stdout());
        Assert.assertTrue(
                capture.stdout().contains("\"command\":\"bal discover package ballerina/graphql --module subgraph\""),
                capture.stdout());
    }

    @Test
    public void aSubmoduleViewNeverListsItselfOrItsSiblingsAmongItsOwnSubmodules() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("package", "ballerina/graphql", "--module", "dataloader"), capture.streams(),
                options(graphqlCentral()));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertFalse(capture.stdout().contains("\"name\":\"dataloader\""), capture.stdout());
        Assert.assertFalse(capture.stdout().contains("subgraph"), capture.stdout());
        Assert.assertFalse(capture.stdout().contains("submodules"), capture.stdout());
    }

    @Test
    public void aModuleFlagThatNamesNoSubmoduleFailsWithCandidates() {
        FakeTransport transport = graphqlCentral();
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("package", "ballerina/graphql", "--module", "nosuch"), capture.streams(),
                options(transport));
        Assert.assertEquals(exitCode, 1);
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.field("kind"), "package-not-found");
        Assert.assertEquals(capture.field("suggestion"), "ballerina/graphql publishes these submodules: dataloader, "
                + "subgraph. Pass one of them to --module, or drop --module for the default module.");
        // The submodule's own page first; the package's only once that is missing, to name what it does publish.
        Assert.assertEquals(transport.urls(), List.of(
                CentralClient.CENTRAL_BASE_URL + "registry/packages/ballerina/graphql",
                CentralClient.CENTRAL_BASE_URL + "docs/ballerina/graphql.nosuch/" + GRAPHQL_VERSION,
                CentralClient.CENTRAL_BASE_URL + "docs/ballerina/graphql/" + GRAPHQL_VERSION));
    }

    @Test
    public void aModuleFlagOnAPackageWithNoSubmodulesSaysSo() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("package", "ballerinax/kafka", "--module", "nope"), capture.streams(),
                centralFor("ballerinax__kafka", "4.6.5"));
        Assert.assertEquals(exitCode, Failure.RUNTIME);
        Assert.assertEquals(capture.field("kind"), "package-not-found");
        Assert.assertTrue(capture.field("suggestion").contains("publishes no submodules"), capture.stderr());
    }

    @Test
    public void aModulePageCentralCannotServeIsATransportFailureNotAMissingModule() {
        FakeTransport transport = FakeTransport.routing(url -> url.contains("/docs/")
                ? FakeTransport.status(503)
                : FakeTransport.ok("[\"" + GRAPHQL_VERSION + "\"]"));
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("package", "ballerina/graphql", "--module", "subgraph"), capture.streams(),
                options(transport));
        Assert.assertEquals(exitCode, Failure.RUNTIME);
        Assert.assertEquals(capture.field("kind"), "upstream", capture.stderr());
    }

    @Test
    public void aSeparatelyPublishedDottedPackageIsNotTakenForASubmodule() {
        // `ballerinax/aws --module s3` names docs/ballerinax/aws.s3/<aws's version>, which can answer with the
        // aws.s3 PACKAGE's page — its own default module, not a module of aws.
        JsonObject page = FixtureCorpus.loadRawModulePage("ballerina__graphql.subgraph").getAsJsonObject();
        page.getAsJsonObject("docsData").getAsJsonArray("modules").get(0).getAsJsonObject()
                .addProperty("isDefaultModule", true);
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("package", "ballerina/graphql", "--module", "subgraph"), capture.streams(),
                options(graphqlCentral(Map.of("subgraph", page))));
        Assert.assertEquals(exitCode, 1, capture.stdout());
        Assert.assertEquals(capture.field("kind"), "package-not-found");
        Assert.assertEquals(capture.field("suggestion"), SUBGRAPH_UNCONFIRMED, capture.stderr());
    }

    private static JsonObject subgraphPageWithRelatedModules(JsonElement relatedModules) {
        JsonObject page = FixtureCorpus.loadRawModulePage("ballerina__graphql.subgraph").getAsJsonObject();
        JsonObject module = page.getAsJsonObject("docsData").getAsJsonArray("modules").get(0).getAsJsonObject();
        module.add("relatedModules", relatedModules);
        return page;
    }

    @Test
    public void aModulePageWithNoReadableRelatedModulesStandsOnItsOwnIdAndFlag() {
        for (String related : List.of("\"not an array\"", "[]", "[{\"id\":1,\"orgName\":2}, null, 7]")) {
            JsonObject page = subgraphPageWithRelatedModules(JsonParser.parseString(related));
            Capture capture = new Capture();
            int exitCode = Cli.run(List.of("package", "ballerina/graphql", "--module", "subgraph"), capture.streams(),
                    options(graphqlCentral(Map.of("subgraph", page))));
            Assert.assertEquals(exitCode, 0, related + " -> " + capture.stderr());
        }
    }

    @Test
    public void aModulePageWhoseReadableRelatedModulesLackThisPackageIsStillRejected() {
        JsonObject page = subgraphPageWithRelatedModules(JsonParser.parseString(
                "[{\"id\":42}, {\"id\":\"graphql.other\",\"orgName\":\"ballerina\",\"isDefaultModule\":true}]"));
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("package", "ballerina/graphql", "--module", "subgraph"), capture.streams(),
                options(graphqlCentral(Map.of("subgraph", page))));
        Assert.assertEquals(exitCode, 1, capture.stdout());
        Assert.assertEquals(capture.field("suggestion"), SUBGRAPH_UNCONFIRMED, capture.stderr());
    }

    @Test
    public void aSubmoduleOfAnotherPackageSharingThePrefixIsNotTakenForThisPackages() {
        JsonObject page = FixtureCorpus.loadRawModulePage("ballerina__graphql.subgraph").getAsJsonObject();
        JsonObject module = page.getAsJsonObject("docsData").getAsJsonArray("modules").get(0).getAsJsonObject();
        for (JsonElement related : module.getAsJsonArray("relatedModules")) {
            JsonObject entry = related.getAsJsonObject();
            if (entry.get("id").getAsString().equals("graphql")) {
                entry.addProperty("id", "graphql.other");
            }
        }
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("package", "ballerina/graphql", "--module", "subgraph"), capture.streams(),
                options(graphqlCentral(Map.of("subgraph", page))));
        Assert.assertEquals(exitCode, 1, capture.stdout());
        Assert.assertEquals(capture.field("kind"), "package-not-found");
        Assert.assertEquals(capture.field("suggestion"), SUBGRAPH_UNCONFIRMED, capture.stderr());
    }

    @Test
    public void aMalformedRelatedModuleOnlyShortensTheSubmoduleList() {
        JsonObject page = FixtureCorpus.loadRawFixture("ballerina__graphql").getAsJsonObject();
        JsonObject module = page.getAsJsonObject("docsData").getAsJsonArray("modules").get(0).getAsJsonObject();
        for (JsonElement related : module.getAsJsonArray("relatedModules")) {
            JsonObject entry = related.getAsJsonObject();
            if (entry.get("id").getAsString().equals("graphql.dataloader")) {
                entry.addProperty("id", 42);
            }
        }

        Capture client = new Capture();
        Assert.assertEquals(Cli.run(List.of("client", "-p", "ballerina/graphql"), client.streams(),
                options(graphqlCentral(page, Map.of()))), 0, client.stderr());

        Capture bare = new Capture();
        Assert.assertEquals(Cli.run(List.of("package", "ballerina/graphql"), bare.streams(),
                options(graphqlCentral(page, Map.of()))), 0, bare.stderr());
        Assert.assertTrue(bare.stdout().contains("\"name\":\"subgraph\""), bare.stdout());
        Assert.assertFalse(bare.stdout().contains("dataloader"), bare.stdout());
    }

    private static FakeTransport kanishkaCentral(JsonElement webhook) {
        return FakeTransport.routing(url -> {
            if (url.endsWith("/docs/kanishka/github.webhook/0.99.18")) {
                return FakeTransport.ok(webhook.toString());
            }
            return url.endsWith("/registry/packages/kanishka/github")
                    ? FakeTransport.ok("[\"0.99.18\"]")
                    : FakeTransport.status(404);
        });
    }

    @Test
    public void aModulePageFromBeforeCentralFlaggedModulesIsReadWhenItsModulesNameThisPackage() {
        // kanishka/github 0.99.18's pages carry no isDefaultModule at all, on the module or its relatedModules.
        JsonElement page = FixtureCorpus.loadRawModulePage("kanishka__github.webhook");
        JsonObject module = page.getAsJsonObject().getAsJsonObject("docsData").getAsJsonArray("modules").get(0)
                .getAsJsonObject();
        Assert.assertFalse(module.has("isDefaultModule"));
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("type", "Issue", "-p", "kanishka/github:0.99.18", "--module", "webhook"),
                capture.streams(), options(kanishkaCentral(page))), 0, capture.stderr());
        Assert.assertTrue(capture.stdout().contains("\"name\":\"Issue\""), capture.stdout());

        JsonObject foreign = page.deepCopy().getAsJsonObject();
        JsonObject foreignModule = foreign.getAsJsonObject("docsData").getAsJsonArray("modules").get(0)
                .getAsJsonObject();
        for (JsonElement related : foreignModule.getAsJsonArray("relatedModules")) {
            JsonObject entry = related.getAsJsonObject();
            if (entry.get("id").getAsString().equals("github")) {
                entry.addProperty("id", "github.other");
            }
        }
        Capture rejected = new Capture();
        Assert.assertEquals(Cli.run(List.of("package", "kanishka/github:0.99.18", "--module", "webhook"),
                rejected.streams(),
                options(kanishkaCentral(foreign))), 1, rejected.stdout());
        Assert.assertEquals(rejected.field("kind"), "package-not-found", rejected.stderr());
    }

    @Test
    public void aModulePagePublishedBeforeApiDocsVersionExistedIsStillRead() {
        // graphql 1.8.0's pages carry only docsData and searchData, and a Dependencies.toml lock can still name
        // that version.
        JsonElement page = FixtureCorpus.loadRawModulePage("ballerina__graphql.subgraph-1.8.0");
        Assert.assertFalse(page.getAsJsonObject().has("apiDocsVersion"));
        FakeTransport transport = FakeTransport.routing(url -> {
            if (url.endsWith("/docs/ballerina/graphql.subgraph/1.8.0")) {
                return FakeTransport.ok(page.toString());
            }
            return url.endsWith("/registry/packages/ballerina/graphql")
                    ? FakeTransport.ok("[\"1.8.0\"]")
                    : FakeTransport.status(404);
        });
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("package", "ballerina/graphql", "--module", "subgraph"), capture.streams(),
                options(transport));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertTrue(capture.stdout().startsWith("{\"qualified\":\"ballerina/graphql\",\"version\":\"1.8.0\","
                + "\"module\":\"subgraph\",\"types\":"), capture.stdout());
    }

    @Test
    public void commandsPrintedUnderAModuleCarryItForward() {
        // The whole point of LoadedPackage.pkgArgument(): a caller drilling further from here must not silently
        // fall back to the default module.
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("class", "-p", "ballerina/graphql", "--module", "dataloader"),
                capture.streams(), options(graphqlCentral()));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertTrue(capture.stdout().contains("--module dataloader"), capture.stdout());
    }

    @Test
    public void aBarePackageAnswersWithItsOverviewAsJsonOffATty() {
        // Not interactive (the default for this overload), so JSON — the RFC's agent-facing default, no flag
        // required.
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("package", "ballerinax/kafka"), capture.streams(),
                centralFor("ballerinax__kafka", "4.6.5"));
        Assert.assertEquals(exitCode, 0, capture.stderr());
        String out = capture.stdout();
        Assert.assertTrue(out.contains("\"clients\":{"), out);
        Assert.assertTrue(out.contains("\"classes\":{"), out);
        Assert.assertFalse(out.contains("\"functions\""), "kafka declares no module functions: " + out);
    }

    private static final String KAFKA_OVERVIEW = String.join("\n",
            "ballerinax/kafka:4.6.5",
            "",
            "Clients (3)",
            "  Caller     3 remote methods",
            "  Consumer  24 remote methods",
            "  Producer   5 remote methods",
            "Service types (1)",
            "  Service",
            "Classes (4)",
            "  AvroDeserializer  1 method",
            "  AvroSerializer    1 method",
            "  Deserializer      1 method",
            "  Serializer        1 method",
            "Types   bal discover type -p ballerinax/kafka    14 records, 3 enums, 3 errors, 8 aliases, 29 constants, "
                    + "1 annotation, 1 listener",
            "Readme  bal discover readme -p ballerinax/kafka  11 sections",
            "",
            "Next: bal discover <noun> <name> -p ballerinax/kafka",
            "Next: bal discover search <keyword> -p ballerinax/kafka",
            "");

    @Test
    public void aBarePackageAnswersWithItsOverviewAsTextAtATerminal() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("package", "ballerinax/kafka"), capture.streams(),
                centralFor("ballerinax__kafka", "4.6.5"), null, true);
        Assert.assertEquals(exitCode, 0, capture.stderr());
        Assert.assertEquals(capture.stdout(), KAFKA_OVERVIEW);
    }

    @Test
    public void outputOverridesTheTtyDefaultInEitherDirection() {
        Capture asText = new Capture();
        Assert.assertEquals(Cli.run(List.of("package", "ballerinax/kafka", "--output-format", "text"), asText.streams(),
                centralFor("ballerinax__kafka", "4.6.5")), 0, asText.stderr());
        Assert.assertEquals(asText.stdout(), KAFKA_OVERVIEW);

        Capture asJson = new Capture();
        Assert.assertEquals(Cli.run(List.of("package", "ballerinax/kafka", "--output-format", "json"), asJson.streams(),
                centralFor("ballerinax__kafka", "4.6.5"), null, true), 0, asJson.stderr());
        Assert.assertTrue(asJson.stdout().contains("\"clients\":{"), asJson.stdout());
    }

    @Test
    public void anInvalidOutputValueIsAValidationFailure() {
        Capture capture = new Capture();
        Assert.assertEquals(
                Cli.run(List.of("package", "ballerinax/kafka", "--output-format", "xml"), capture.streams(), never()),
                2);
        Assert.assertEquals(capture.field("kind"), "usage");
        Assert.assertTrue(capture.field("message").contains("xml"), capture.stderr());
    }

    @Test
    public void findIsGoneAsAVerb() {
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("find", "kafka"), capture.streams(), never()), 2);
        // `find` parses as a package-shaped positional (no `/`), so it fails the qualified-name pattern.
        Assert.assertEquals(capture.field("kind"), "usage");
    }

    @Test
    public void anUnknownPackageExits1AndNamesItself() {
        Capture capture = new Capture();
        int exitCode = Cli.run(List.of("package", "ballerinax/nope"), capture.streams(),
                HttpOptions.builder()
                        .transport(FakeTransport.always(FakeTransport.ok("[]")))
                        .maxAttempts(1).baseDelayMs(1).sleeper(millis -> { }).build());
        Assert.assertEquals(exitCode, 1);
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.field("kind"), "package-not-found");
    }

    @Test
    public void aFailingRunLeavesStdoutEmptyAndStderrHoldingExactlyOneJsonObject() {
        List<List<String>> failures = List.of(
                List.of("not-a-package"),
                List.of("nosuchnoun", "-p", "ballerinax/kafka"),
                List.of("ballerinax/kafka", "client"),
                List.of("--nonesuch"));
        for (List<String> argv : failures) {
            Capture capture = new Capture();
            int exitCode = Cli.run(argv, capture.streams(), never());
            Assert.assertEquals(exitCode, 2, String.join(" ", argv));
            Assert.assertEquals(capture.stdout(), "", String.join(" ", argv));
            Assert.assertEquals(capture.field("kind"), "usage", String.join(" ", argv));
            Assert.assertTrue(capture.stderr().endsWith("}\n"), String.join(" ", argv));
        }
    }

    @Test
    public void extraNamesNoPackageCanReadAreAUsageErrorNamingTheCommandToRunInstead() {
        Capture funcs = new Capture();
        Assert.assertEquals(Cli.run(List.of("function", "println", "extra", "-p", "ballerina/io:1.8.2", "--module",
                "sub", "--filter", "x"), funcs.streams(), never()), 2);
        Assert.assertEquals(funcs.field("message"), "function takes one name; got 2");
        Assert.assertEquals(funcs.field("command"),
                "bal discover function println -p ballerina/io:1.8.2 --module sub --filter x");

        Capture types = new Capture();
        Assert.assertEquals(Cli.run(List.of("type", "A", "B", "-p", "ballerina/io"), types.streams(), never()), 2);
        Assert.assertEquals(types.field("command"), "bal discover type A -p ballerina/io");

        Capture filtered = new Capture();
        Assert.assertEquals(Cli.run(List.of("type", "Error", "-p", "ballerina/io", "--filter", "x"),
                filtered.streams(), never()), 2);
        Assert.assertEquals(filtered.field("suggestion"), "Drop --filter, or drop the name: "
                + "'bal discover type -p ballerina/io --filter x'.");
        Assert.assertEquals(filtered.field("command"), "bal discover type Error -p ballerina/io");
    }

    @Test
    public void theFailureTextIsUnchanged() {
        record Case(String name, List<String> argv) { }
        List<Case> cases = List.of(
                new Case("unknown-noun", List.of("nosuchnoun", "-p", "ballerinax/kafka")),
                new Case("old-form", List.of("ballerinax/kafka", "client", "Producer", "send")),
                new Case("partial-version", List.of("package", "ballerinax/github:6.0")),
                new Case("version-flag", List.of("package", "ballerinax/github", "--version", "6.0.0")),
                new Case("version-argument", List.of("client", "6.0.0", "-p", "ballerinax/github")),
                new Case("unknown-flag", List.of("package", "ballerina/http", "--nonesuch")),
                new Case("no-package", List.of("--refresh")));

        for (Case failure : cases) {
            Capture capture = new Capture();
            Assert.assertEquals(Cli.run(failure.argv(), capture.streams(), never()), 2,
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
    public void aFailureIsWrittenInTheRunsOutputModeEvenWhenTheArgumentsDoNotParse() {
        record Case(List<String> argv, boolean interactive, boolean json) { }
        for (Case each : List.of(
                new Case(List.of("nosuchnoun", "-p", "ballerina/http"), true, false),
                new Case(List.of("nosuchnoun", "-p", "ballerina/http"), false, true),
                new Case(List.of("nosuchnoun", "-p", "ballerina/http", "--output-format", "json"), true, true),
                new Case(List.of("nosuchnoun", "-p", "ballerina/http", "--output-format", "text"), false, false),
                new Case(List.of("package", "ballerina/http", "--nonesuch", "--output-format", "text"), false, false),
                new Case(List.of("package", "ballerina/http", "--output-format=text", "--nonesuch"), false, false),
                new Case(List.of("package", "ballerina/http", "--nonesuch", "--output-format", "xml"), true, false),
                new Case(List.of("package", "ballerina/http", "--output-format", "xml"), false, true),
                new Case(List.of("nosuchnoun", "-p", "ballerina/http", "--filter", "--output-format", "text"),
                        false, true),
                new Case(List.of("package", "ballerina/http", "--module", "--output-format", "text"), false, true))) {
            Capture capture = new Capture();
            Assert.assertEquals(Cli.run(each.argv(), capture.streams(), never(), null, each.interactive()), 2,
                    each.toString());
            Assert.assertEquals(capture.stdout(), "", each.toString());
            if (each.json()) {
                Assert.assertEquals(capture.field("kind"), "usage", each.toString());
            } else {
                List<String> lines = List.of(capture.stderr().split("\n", -1));
                int last = lines.size() - 1;
                Assert.assertTrue(lines.get(0).startsWith("bal discover: error: "), capture.stderr());
                lines.subList(1, last - 2).forEach(line ->
                        Assert.assertTrue(line.startsWith("  ") && !line.startsWith("   "), capture.stderr()));
                Assert.assertTrue(lines.get(last - 2).startsWith("Usage: bal discover "), capture.stderr());
                Assert.assertTrue(lines.get(last - 1).matches("Try 'bal discover (\\w+ )?--help' for more "
                        + "information\\."), capture.stderr());
                Assert.assertEquals(lines.get(last), "", capture.stderr());
            }
        }
    }

    @Test
    public void anOptionGivenTwiceOrAFlagGivenAValueIsAUsageErrorInTheToolsOwnWords() {
        record Case(List<String> argv, String message, String suggestion) { }
        for (Case each : List.of(
                new Case(List.of("package", "ballerina/io", "--output-format", "MODE", "--output-format", "MODE"),
                        "--output-format is given more than once", "Pass --output-format once."),
                new Case(List.of("package", "ballerina/io", "--page", "1", "--page", "2"),
                        "--page is given more than once", "Pass --page once."),
                new Case(List.of("package", "ballerina/io", "--refresh=yes"), "--refresh takes no value",
                        "Write --refresh."))) {
            Capture json = new Capture();
            List<String> jsonArgv = each.argv().stream().map(word -> word.replace("MODE", "json")).toList();
            Assert.assertEquals(Cli.run(jsonArgv, json.streams(), never()), 2, jsonArgv.toString());
            Assert.assertEquals(json.field("message"), each.message(), json.stderr());
            Assert.assertEquals(json.field("suggestion"), each.suggestion(), json.stderr());

            Capture text = new Capture();
            List<String> textArgv = each.argv().stream().map(word -> word.replace("MODE", "text")).toList();
            Assert.assertEquals(Cli.run(textArgv, text.streams(), never(), null, true), 2, textArgv.toString());
            Assert.assertEquals(text.stderr(), "bal discover: error: " + each.message() + "\n  " + each.suggestion()
                    + "\nUsage: bal discover package <org>/<name>[:<version>] [options]\n"
                    + "Try 'bal discover package --help' for more information.\n");
        }
    }

    @Test
    public void aTextFailureIndentsItsSuggestionUnderTheMessage() {
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("type", "NoSuchType", "-p", "ballerinax/kafka", "--output-format", "text"),
                capture.streams(), centralFor("ballerinax__kafka", "4.6.5")), 1);
        Assert.assertEquals(capture.stdout(), "");
        FixtureCorpus.matchesSnapshot(
                Path.of("src", "test", "resources", "command-outputs", "unix", "failure-symbol-not-found.txt"),
                capture.stderr(),
                "symbol-not-found failure text, text mode");
    }

    @Test
    public void theSymbolNotFoundJsonIsUnchanged() {
        Capture capture = new Capture();
        Assert.assertEquals(Cli.run(List.of("client", "NoSuchContainer", "-p", "ballerinax/kafka"),
                capture.streams(), centralFor("ballerinax__kafka", "4.6.5")), 1);
        Assert.assertEquals(capture.stdout(), "");
        FixtureCorpus.matchesSnapshot(
                Path.of("src", "test", "resources", "command-outputs", "unix", "failure-symbol-not-found.json"),
                capture.stderr(),
                "symbol-not-found failure text");
    }

    static Path tempDir() {
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

    static void write(Path path, String contents) {
        try {
            Files.writeString(path, contents, StandardCharsets.UTF_8);
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
    }
}
