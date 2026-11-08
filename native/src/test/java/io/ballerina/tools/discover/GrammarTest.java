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
import io.ballerina.tools.discover.central.HttpOptions;
import io.ballerina.tools.discover.cli.Cli;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The noun-first grammar: every noun with and without a name, the container flags, and every usage error.
 */
public class GrammarTest {

    private static final String KAFKA = "ballerinax/kafka";

    private static final int USAGE = Failure.USAGE;

    private static final int NOT_FOUND = Failure.RUNTIME;

    private static final String GITHUB = "ballerinax/github";

    private static JsonObject answer(String slug, String... argv) {
        CliTest.Capture capture = new CliTest.Capture();
        int code = Cli.run(List.of(argv), capture.streams(), central(slug));
        Assert.assertEquals(code, 0, String.join(" ", argv) + ": " + capture.stderr());
        return JsonParser.parseString(capture.stdout()).getAsJsonObject();
    }

    private static CliTest.Capture failure(int expected, String slug, String... argv) {
        CliTest.Capture capture = new CliTest.Capture();
        HttpOptions http = slug == null ? CliTest.never() : central(slug);
        Assert.assertEquals(Cli.run(List.of(argv), capture.streams(), http), expected, String.join(" ", argv)
                + ": " + capture.stderr());
        Assert.assertEquals(capture.stdout(), "", String.join(" ", argv));
        if (expected == Failure.USAGE) {
            Assert.assertTrue(List.of("usage", "ambiguous").contains(capture.field("kind")), String.join(" ", argv));
        }
        return capture;
    }

    private static HttpOptions central(String slug) {
        return CliTest.centralFor(slug, FixtureCorpus.FIXTURE_VERSION.text());
    }

    @DataProvider(name = "listings")
    public Object[][] listings() {
        return new Object[][] {
                {"ballerinax__kafka", List.of("package", KAFKA), "clients"},
                {"ballerinax__kafka", List.of("search", "send", "-p", KAFKA), "remote"},
                {"ballerinax__kafka", List.of("client", "-p", KAFKA), "containers"},
                {"ballerinax__kafka", List.of("service", "-p", KAFKA), "container"},
                {"ballerinax__kafka", List.of("class", "-p", KAFKA), "containers"},
                {"ballerina__log", List.of("function", "-p", "ballerina/log"), "methods"},
                {"ballerinax__kafka", List.of("type", "-p", KAFKA), "sections"},
                {"ballerinax__kafka", List.of("method", "-p", KAFKA, "--client", "Producer"), "methods"},
                {"ballerinax__github", List.of("resource", "-p", GITHUB, "--client", "Client"), "groups"},
                {"ballerinax__github", List.of("resource", "-p", GITHUB), "groups"},
                {"ballerinax__kafka", List.of("readme", "-p", KAFKA), "readme"}};
    }

    @Test(dataProvider = "listings")
    public void aNounWithoutANameIsAListing(String slug, List<String> argv, String key) {
        JsonObject json = answer(slug, argv.toArray(String[]::new));
        Assert.assertTrue(json.has(key), argv + ": " + json);
    }

    @DataProvider(name = "leaves")
    public Object[][] leaves() {
        return new Object[][] {
                {"ballerinax__kafka", List.of("client", "Producer", "-p", KAFKA), "container", "Producer"},
                {"ballerinax__kafka", List.of("service", "Service", "-p", KAFKA), "container", "Service"},
                {"ballerinax__kafka", List.of("class", "AvroSerializer", "-p", KAFKA), "container",
                        "AvroSerializer"},
                {"ballerina__log", List.of("function", "printInfo", "-p", "ballerina/log"), "name", "printInfo"},
                {"ballerinax__kafka", List.of("type", "TopicPartition", "-p", KAFKA), "name", "TopicPartition"},
                {"ballerinax__kafka", List.of("method", "send", "-p", KAFKA, "--client", "Producer"), "kind",
                        "remote"},
                {"ballerinax__kafka", List.of("method", "send", "-p", KAFKA), "container", "Producer"},
                {"ballerinax__kafka", List.of("method", "init", "-p", KAFKA, "--client", "Producer"), "kind",
                        "constructor"},
                {"ballerinax__github", List.of("resource", "gists/public", "get", "-p", GITHUB, "--client",
                        "Client"), "path", "gists/public"},
                {"ballerinax__github", List.of("resource", "gists", "-p", GITHUB), "container", "Client"},
                {"ballerinax__kafka", List.of("readme", "1", "-p", KAFKA), "section", "1"}};
    }

    @Test(dataProvider = "leaves")
    public void aNounWithANameIsOneThing(String slug, List<String> argv, String key, String value) {
        JsonObject json = answer(slug, argv.toArray(String[]::new));
        Assert.assertEquals(json.has(key) ? json.get(key).getAsString() : null, value, argv + ": " + json);
    }

    @Test
    public void flagsBeforeTheNounReadTheSameAsAfterIt() {
        JsonObject leading = answer("ballerinax__kafka", "-p", KAFKA, "method", "send");
        Assert.assertEquals(leading, answer("ballerinax__kafka", "method", "send", "-p", KAFKA));
        CliTest.Capture text = new CliTest.Capture();
        Assert.assertEquals(Cli.run(List.of("--output-format", "text", "--package=" + KAFKA, "client", "Producer"),
                text.streams(), central("ballerinax__kafka")), 0, text.stderr());
        Assert.assertTrue(text.stdout().contains("Producer"), text.stdout());
        Assert.assertFalse(text.stdout().startsWith("{"), text.stdout());
    }

    @Test
    public void aContainerFlagBeforeTheNounNamesTheContainerOnlyForAMember() {
        JsonObject leading = answer("ballerinax__kafka", "--client", "Producer", "method", "send", "-p", KAFKA);
        Assert.assertEquals(leading.get("container").getAsString(), "Producer", leading.toString());
        Assert.assertEquals(failure(USAGE, null, "--client", "Producer", "client", "-p", KAFKA).field("message"),
                "unknown option '--client'");
        Assert.assertTrue(failure(USAGE, null, "--client", "A", "method", "-p", KAFKA, "--client", "B").field("message")
                .endsWith(" is given both before the noun and after it"));
    }

    @Test
    public void aMethodListingLeavesResourcesToResourceAndAResourceListingLeavesMethodsToMethod() {
        JsonObject methods = answer("ballerina__http", "method", "-p", "ballerina/http", "--client", "Client");
        Assert.assertFalse(methods.has("resources"), methods.toString());
        Assert.assertTrue(methods.has("remote") && methods.has("normal"), methods.toString());
        JsonObject resources = answer("ballerina__http", "resource", "-p", "ballerina/http", "--client", "Client");
        Assert.assertFalse(resources.has("remote") || resources.has("methods"), resources.toString());
        Assert.assertTrue(resources.has("resources"), resources.toString());
        CliTest.Capture none = failure(NOT_FOUND, "ballerinax__github", "method", "-p", GITHUB, "--client", "Client");
        Assert.assertEquals(none.field("message"), "Client declares no methods in " + GITHUB + ":"
                + FixtureCorpus.FIXTURE_VERSION.text());
        Assert.assertEquals(none.field("command"), "bal discover resource -p " + GITHUB + " --client Client");
    }

    @Test
    public void aContainerFlagOfTheWrongKindFailsWithTheCommandThatHasTheRightOne() {
        CliTest.Capture wrong = failure(NOT_FOUND, "ballerinax__kafka", "method", "send", "-p", KAFKA, "--class",
                "Producer");
        Assert.assertEquals(wrong.field("kind"), "symbol-not-found");
        Assert.assertEquals(wrong.field("command"), "bal discover method send -p " + KAFKA + " --client Producer");
        CliTest.Capture none = failure(NOT_FOUND, "ballerinax__kafka", "method", "send", "-p", KAFKA, "--client",
                "Nope");
        Assert.assertFalse(none.failure().has("command"), none.stderr());
        Assert.assertEquals(none.failure().getAsJsonArray("candidates").toString(),
                "[\"Caller\",\"Consumer\",\"Producer\"]");
    }

    @Test
    public void aMemberSeveralContainersDeclareListsEachOwnersCommand() {
        CliTest.Capture several = failure(Failure.USAGE, "ballerinax__kafka", "method", "-p", KAFKA);
        Assert.assertEquals(several.failure().getAsJsonArray("owners").size(), 7, several.stderr());
        Assert.assertTrue(several.stderr().contains("bal discover method -p " + KAFKA + " --client Producer"),
                several.stderr());
    }

    @DataProvider(name = "oldForms")
    public Object[][] oldForms() {
        return new Object[][] {
                {List.of(KAFKA, "client"), "bal discover client -p " + KAFKA},
                {List.of(KAFKA, "client", "Producer"), "bal discover client Producer -p " + KAFKA},
                {List.of(KAFKA, "client", "Producer", "send"),
                        "bal discover method send -p " + KAFKA + " --client Producer"},
                {List.of(GITHUB, "client", "Client", "gists/'public", "get", "--filter", "x"),
                        "bal discover resource gists/public get -p " + GITHUB + " --client Client --filter x"},
                {List.of("ballerina/log", "funcs", "printInfo"), "bal discover function printInfo -p ballerina/log"},
                {List.of(KAFKA, "readme", "Data", "serialization"),
                        "bal discover readme \"Data serialization\" -p " + KAFKA},
                {List.of(KAFKA, "search", "send", "--page", "2"), "bal discover search send -p " + KAFKA
                        + " --page 2"},
                {List.of(KAFKA, "search", "send", "close"), "bal discover search \"send close\" -p " + KAFKA},
                {List.of(KAFKA, "type", "TopicPartition"), "bal discover type TopicPartition -p " + KAFKA},
                {List.of(KAFKA, "4.6.6", "client", "Producer"), "bal discover client Producer -p " + KAFKA + ":4.6.6"},
                {List.of(KAFKA, "clients", "Producer"), "bal discover client Producer -p " + KAFKA},
                {List.of(KAFKA, "producers", "Producer"), "bal discover search producers -p " + KAFKA}};
    }

    @Test(dataProvider = "oldForms")
    public void thePackageFirstFormIsAUsageErrorWithTheSameRequestInTheNewOrder(List<String> argv, String command) {
        CliTest.Capture old = failure(USAGE, null, argv.toArray(String[]::new));
        Assert.assertEquals(old.field("command"), command, old.stderr());
        Assert.assertEquals(old.field("suggestion"), "Name the noun first and the package with -p.");
        Assert.assertEquals(old.field("message"), "'" + argv.get(0) + "' is not a noun; name the package with -p");
    }

    private static CliTest.Capture run(HttpOptions http, String... argv) {
        CliTest.Capture capture = new CliTest.Capture();
        Assert.assertEquals(Cli.run(List.of(argv), capture.streams(), http), 0, String.join(" ", argv) + ": "
                + capture.stderr());
        return capture;
    }

    @DataProvider(name = "bareCoordinates")
    public Object[][] bareCoordinates() {
        return new Object[][] {
                {List.of(KAFKA), List.of("package", KAFKA)},
                {List.of(KAFKA + ":" + FixtureCorpus.FIXTURE_VERSION.text()),
                        List.of("package", KAFKA + ":" + FixtureCorpus.FIXTURE_VERSION.text())},
                {List.of(KAFKA, "--output-format", "json"), List.of("package", KAFKA, "--output-format", "json")},
                {List.of(KAFKA, "--output-format", "text"), List.of("package", KAFKA, "--output-format", "text")},
                {List.of("--output-format", "text", KAFKA), List.of("--output-format", "text", "package", KAFKA)},
                {List.of(KAFKA, "--refresh"), List.of("package", KAFKA, "--refresh")}};
    }

    @Test(dataProvider = "bareCoordinates")
    public void aBarePackageCoordinateIsThePackageOverview(List<String> bare, List<String> overview) {
        CliTest.Capture typed = run(central("ballerinax__kafka"), bare.toArray(String[]::new));
        CliTest.Capture named = run(central("ballerinax__kafka"), overview.toArray(String[]::new));
        Assert.assertEquals(typed.stdout(), named.stdout());
        Assert.assertEquals(typed.stderr(), named.stderr());
    }

    @Test
    public void aBareCoordinateWithAModuleReadsTheSameAnswerAsPackage() {
        CliTest.Capture typed = new CliTest.Capture();
        CliTest.Capture named = new CliTest.Capture();
        int bare = Cli.run(List.of(KAFKA, "--module", "sub"), typed.streams(), central("ballerinax__kafka"));
        int overview = Cli.run(List.of("package", KAFKA, "--module", "sub"), named.streams(),
                central("ballerinax__kafka"));
        Assert.assertEquals(bare, overview);
        Assert.assertEquals(typed.stdout(), named.stdout());
        Assert.assertEquals(typed.stderr(), named.stderr());
    }

    @Test
    public void aBareCoordinateOfAMissingPackageFailsAsPackageDoes() {
        CliTest.Capture typed = new CliTest.Capture();
        CliTest.Capture named = new CliTest.Capture();
        int bare = Cli.run(List.of("ballerinax/nonesuch"), typed.streams(), central("ballerinax__kafka"));
        int overview = Cli.run(List.of("package", "ballerinax/nonesuch"), named.streams(),
                central("ballerinax__kafka"));
        Assert.assertNotEquals(bare, 0);
        Assert.assertEquals(bare, overview);
        Assert.assertEquals(typed.stderr(), named.stderr());
    }

    @Test
    public void aBareCoordinateGivenWithThePackageFlagIsStillAUsageError() {
        CliTest.Capture both = failure(USAGE, null, KAFKA, "-p", KAFKA);
        Assert.assertEquals(both.field("message"), "'" + KAFKA + "' is not a noun; name the package with -p");
    }

    @Test
    public void anUnknownFlagAfterABareCoordinateIsAnUnknownOption() {
        for (List<String> argv : List.of(List.of(KAFKA, "--nonesuch"), List.of(KAFKA, "--output", "json"))) {
            CliTest.Capture unknown = failure(USAGE, null, argv.toArray(String[]::new));
            Assert.assertEquals(unknown.field("message"), "unknown option '" + argv.get(1) + "'", unknown.stderr());
            Assert.assertEquals(unknown.field("suggestion"), "Run 'bal discover --help' for the usage.");
        }
    }

    @Test
    public void aBareWordThatIsNotACoordinateIsStillAnUnknownNoun() {
        CliTest.Capture word = failure(USAGE, null, "kafka");
        Assert.assertTrue(word.field("message").startsWith("'kafka' is not a noun;"), word.stderr());
    }

    @Test
    public void aNounWithoutThePackageIsAUsageErrorWithItsSynopsis() {
        for (String noun : List.of("client", "service", "class", "function", "type", "method", "resource",
                "readme")) {
            CliTest.Capture json = failure(USAGE, null, noun);
            Assert.assertTrue(json.field("message").startsWith(noun + " needs the package it reads: -p "),
                    json.stderr());
            CliTest.Capture text = new CliTest.Capture();
            Assert.assertEquals(Cli.run(List.of(noun, "--output-format", "text"), text.streams(), CliTest.never()),
                    USAGE);
            Assert.assertTrue(text.stderr().contains("\nUsage: bal discover " + noun + " "), text.stderr());
            Assert.assertTrue(text.stderr().contains(" -p <org>/<name>[:<version>] "), text.stderr());
            Assert.assertTrue(text.stderr().endsWith("Try 'bal discover " + noun + " --help' for more information.\n"),
                    text.stderr());
        }
    }

    @Test
    public void packageTakesTheCoordinateAsItsNameAndOneOnly() {
        Assert.assertTrue(failure(USAGE, null, "package").field("message").startsWith("package needs the package"));
        CliTest.Capture flag = failure(USAGE, null, "package", "-p", KAFKA);
        Assert.assertEquals(flag.field("command"), "bal discover package " + KAFKA);
    }

    @Test
    public void oneContainerFlagAtATimeAndOnlyOnMethodAndResource() {
        CliTest.Capture two = failure(USAGE, null, "method", "send", "-p", KAFKA, "--client", "A", "--class", "B");
        Assert.assertEquals(two.field("message"), "a method has one container; pass one of --client, --class, "
                + "--service");
        Assert.assertEquals(two.failure().getAsJsonArray("owners").get(1).getAsJsonObject().get("command")
                .getAsString(), "bal discover method send -p " + KAFKA + " --class B");
        CliTest.Capture misplaced = failure(USAGE, null, "client", "Producer", "-p", KAFKA, "--client", "Producer");
        Assert.assertEquals(misplaced.field("message"), "unknown option '--client'");
        Assert.assertFalse(misplaced.field("suggestion").contains("--client"), misplaced.stderr());
    }

    @Test
    public void eachNounHasItsOwnHelpAndTheRootListsThemAll() {
        CliTest.Capture root = new CliTest.Capture();
        Assert.assertEquals(Cli.run(List.of("--help"), root.streams(), CliTest.never()), 0);
        for (String noun : Command.NOUNS) {
            Assert.assertTrue(root.stdout().contains("\n  " + noun + " "), noun + ": " + root.stdout());
            CliTest.Capture help = new CliTest.Capture();
            Assert.assertEquals(Cli.run(List.of(noun, "--help"), help.streams(), CliTest.never()), 0, noun);
            String text = help.stdout();
            Assert.assertTrue(text.contains("\n\nUsage: bal discover " + noun), text);
            Assert.assertTrue(text.indexOf("\nUsage: ") < text.indexOf("\nOptions:\n")
                    && text.indexOf("\nOptions:\n") < text.indexOf("\nExamples:\n  # "), text);
            Assert.assertTrue(text.contains("\n  --refresh "), "every option a noun takes is on its page: " + noun);
            Assert.assertFalse(text.contains("bal discover --help"), text);
            Assert.assertFalse(text.contains("Exit codes"), text);
            text.lines().forEach(line -> Assert.assertTrue(line.length() <= 80, noun + ": " + line));
        }
        Assert.assertTrue(root.stdout().contains("\n  --client|--class|--service <name>\n"), root.stdout());
        Assert.assertFalse(root.stdout().contains("Exit codes"), root.stdout());
        root.stdout().lines().forEach(line -> Assert.assertTrue(line.length() <= 80, line));
        CliTest.Capture method = new CliTest.Capture();
        Assert.assertEquals(Cli.run(List.of("method", "-h"), method.streams(), CliTest.never()), 0);
        Assert.assertTrue(method.stdout().contains("\nUsage: bal discover method [<name>] -p <org>/<name>"
                + "[:<version>]\n                    [--client|--class|--service <name>] [options]\n"),
                method.stdout());
    }

    @Test
    public void everyOptionANounsHelpListsIsOneTheNounTakes() {
        Map<String, List<String>> values = Map.of(Command.FILTER_FLAG, List.of("send"),
                Command.PAGE_FLAG, List.of("1"), Command.MODULE_FLAG, List.of("nosuch"),
                "--output-format", List.of("json"), "--refresh", List.of(), "--help", List.of());
        for (String noun : Command.NOUNS) {
            CliTest.Capture help = new CliTest.Capture();
            Assert.assertEquals(Cli.run(List.of(noun, "--help"), help.streams(), CliTest.never()), 0, noun);
            String options = help.stdout().substring(help.stdout().indexOf("\nOptions:\n"),
                    help.stdout().indexOf("\nExamples:\n"));
            List<String> listed = options.lines()
                    .filter(line -> line.startsWith("  -"))
                    .map(line -> line.strip().split("[ ,|]")[0])
                    .map(flag -> flag.equals("-h") ? "--help" : flag)
                    .filter(flag -> !flag.equals(Command.PACKAGE_FLAG) && !Command.OWNER_FLAGS.contains(flag))
                    .toList();
            Assert.assertEquals(listed.size(), values.size() - (Command.SEARCH.equals(noun) ? 1 : 0),
                    noun + " lists " + listed);
            String slug = Command.RESOURCE.equals(noun) ? "ballerinax__github" : "ballerinax__kafka";
            for (String flag : listed) {
                Assert.assertTrue(values.containsKey(flag), noun + " lists an option this test does not know: " + flag);
                List<String> argv = new ArrayList<>(optionBase(noun));
                argv.add(flag);
                argv.addAll(values.get(flag));
                CliTest.Capture run = new CliTest.Capture();
                int code = Cli.run(argv, run.streams(), central(slug));
                Assert.assertNotEquals(code, USAGE, String.join(" ", argv) + " -> " + run.stderr());
            }
        }
    }

    // The listing each noun answers, so an option is tried where it applies.
    private static List<String> optionBase(String noun) {
        return switch (noun) {
            case Command.PACKAGE -> List.of(noun, KAFKA);
            case Command.SEARCH -> List.of(noun, "send", "-p", KAFKA);
            case Command.METHOD -> List.of(noun, "-p", KAFKA, "--client", "Producer");
            case Command.RESOURCE -> List.of(noun, "-p", GITHUB, "--client", "Client");
            default -> List.of(noun, "-p", KAFKA);
        };
    }

    @DataProvider(name = "packageAsName")
    public Object[][] packageAsName() {
        return new Object[][] {
                {List.of("client", KAFKA), "bal discover client -p " + KAFKA},
                {List.of("method", "send", KAFKA), "bal discover method send -p " + KAFKA},
                {List.of("method", "send", KAFKA + ":4.6.6", "--client", "Producer"),
                        "bal discover method send -p " + KAFKA + ":4.6.6 --client Producer"},
                {List.of("resource", GITHUB, "gists/public", "get"), "bal discover resource gists/public get -p "
                        + GITHUB},
                {List.of("resource", "gists/public", "get", GITHUB), "bal discover resource gists/public get -p "
                        + GITHUB},
                {List.of("client", KAFKA, "-p", KAFKA), "bal discover client -p " + KAFKA}};
    }

    @Test(dataProvider = "packageAsName")
    public void aPackageTypedAsANameIsMovedToItsFlag(List<String> argv, String command) {
        CliTest.Capture misplaced = failure(USAGE, null, argv.toArray(String[]::new));
        Assert.assertEquals(misplaced.field("command"), command, misplaced.stderr());
    }

    @Test
    public void aMissingPackageIsNeverInventedButFoundBySearch() {
        CliTest.Capture named = failure(USAGE, null, "method", "send", "--client", "Producer");
        Assert.assertEquals(named.field("command"), "bal discover search send");
        Assert.assertEquals(named.field("suggestion"), "Search Central for the package that declares 'send', then "
                + "write 'bal discover method send -p <org>/<name> --client Producer'.");
        CliTest.Capture bare = failure(USAGE, null, "client");
        Assert.assertFalse(bare.failure().has("command"), bare.stderr());
        Assert.assertFalse(bare.stderr().contains("github"), bare.stderr());
        Assert.assertTrue(bare.field("suggestion").contains("'bal discover search <query>'"), bare.stderr());
        Assert.assertTrue(failure(USAGE, null, "resource", "gists/public", "get").field("suggestion")
                .endsWith("then write 'bal discover resource gists/public get -p <org>/<name>'."));
    }

    @DataProvider(name = "aliases")
    public Object[][] aliases() {
        return new Object[][] {
                {List.of("funcs", "-p", "ballerina/log"), "bal discover function -p ballerina/log"},
                {List.of("functions", "printInfo", "-p", "ballerina/log"),
                        "bal discover function printInfo -p ballerina/log"},
                {List.of("methods", "send", "-p", KAFKA, "--client", "Producer"),
                        "bal discover method send -p " + KAFKA + " --client Producer"},
                {List.of("-p", KAFKA, "clients"), "bal discover client -p " + KAFKA},
                {List.of("packages", "-p", KAFKA), "bal discover package " + KAFKA},
                {List.of("classes", "Producer", "send", "-p", KAFKA),
                        "bal discover method send -p " + KAFKA + " --class Producer"}};
    }

    @Test(dataProvider = "aliases")
    public void anOldBucketOrAPluralAsTheNounNamesTheNounItMeant(List<String> argv, String command) {
        CliTest.Capture alias = failure(USAGE, null, argv.toArray(String[]::new));
        Assert.assertEquals(alias.field("command"), command, alias.stderr());
        Assert.assertTrue(alias.field("message").startsWith("'" + argv.stream().filter(word -> !word.startsWith("-")
                && !word.contains("/")).findFirst().orElseThrow() + "' is not a noun; the noun is "), alias.stderr());
    }

    @Test
    public void aFlagGivenBeforeAndAfterTheNounIsAUsageError() {
        for (List<String> argv : List.of(List.of("-p", "a/b", "client", "-p", "c/d"),
                List.of("--page", "2", "client", "-p", "a/b", "--page", "3"),
                List.of("--output-format", "json", "client", "-p", "a/b", "--output-format", "text"))) {
            CliTest.Capture repeated = failure(USAGE, null, argv.toArray(String[]::new));
            Assert.assertTrue(repeated.field("message").endsWith(" is given both before the noun and after it"),
                    repeated.stderr());
        }
        CliTest.Capture both = failure(USAGE, null, "-p", "a/b", "package", "c/d");
        Assert.assertEquals(both.field("command"), "bal discover package c/d");
    }

    @Test
    public void flagValuesAttachedWithEqualsAndWordsAfterTheEndOfOptionsRead() {
        JsonObject attached = answer("ballerinax__kafka", "method", "send", "--package=" + KAFKA, "--client=Producer");
        Assert.assertEquals(attached.get("container").getAsString(), "Producer", attached.toString());
        CliTest.Capture dashed = failure(NOT_FOUND, "ballerinax__kafka", "method", "-p", KAFKA, "--", "-send");
        Assert.assertTrue(dashed.field("message").startsWith("'-send' is not a method"), dashed.stderr());
    }

    @Test
    public void aResourceMissedWithoutItsContainerPointsAtTheDeepestRealPath() {
        CliTest.Capture typo = failure(NOT_FOUND, "ballerinax__github", "resource", "gists/nope", "get", "-p", GITHUB);
        Assert.assertFalse(typo.failure().has("command"), typo.stderr());
        typo.failure().getAsJsonArray("candidates").forEach(candidate ->
                Assert.assertTrue(candidate.getAsString().startsWith("gists"), typo.stderr()));
    }

    @Test
    public void aResourceMissedAmongSeveralContainersSearchesItsFirstSegmentOrListsTheResources() {
        // Few enough to list: every resource method any container declares, each once.
        CliTest.Capture slash = failure(NOT_FOUND, "ballerina__http", "resource", "/", "get", "-p", "ballerina/http");
        Assert.assertFalse(slash.failure().has("command"), slash.stderr());
        Assert.assertEquals(slash.failure().getAsJsonArray("candidates").get(0).getAsString(), ":...path get",
                slash.stderr());
        CliTest.Capture typo = failure(NOT_FOUND, "ballerina__http", "resource", ":id/nope", "get", "-p",
                "ballerina/http");
        Assert.assertFalse(typo.failure().has("command"), typo.stderr());
        Assert.assertTrue(typo.field("suggestion").endsWith("search the package with "
                + "'bal discover search nope -p ballerina/http'."), typo.stderr());
    }

    @Test
    public void aMethodAskedOfAPackageWithOnlyResourcesPointsAtTheirContainers() {
        CliTest.Capture none = failure(USAGE, "ballerinax__github", "method", "-p", GITHUB);
        Assert.assertEquals(none.field("command"), "bal discover client -p " + GITHUB, none.stderr());
    }

    @Test
    public void aPathTypedWithoutThePackageIsNeverTakenForThePackage() {
        for (String noun : List.of("resource", "client")) {
            CliTest.Capture path = failure(USAGE, null, noun, "gists/public", "get");
            Assert.assertTrue(path.field("suggestion").endsWith("then write 'bal discover " + noun
                    + " gists/public get -p <org>/<name>'."), path.stderr());
            Assert.assertEquals(path.field("command"), "bal discover search gists", path.stderr());
        }
        CliTest.Capture given = failure(NOT_FOUND, "ballerinax__github", "client", "gist/public", "-p", GITHUB);
        Assert.assertEquals(given.field("suggestion"), "Name one of the clients below, or search the package with "
                + "'bal discover search gist -p " + GITHUB + "'.", given.stderr());
    }

    @Test
    public void aContainerNounRecoversADottedMemberOrAStrayWordInOneHop() {
        for (String noun : List.of("client", "class")) {
            CliTest.Capture dotted = failure(NOT_FOUND, "ballerinax__kafka", noun, "Producer.send", "-p", KAFKA);
            Assert.assertEquals(dotted.field("command"), "bal discover method send -p " + KAFKA
                    + " --client Producer", dotted.stderr());
        }
        CliTest.Capture extra = failure(USAGE, null, "client", "Producer", "send", "extra", "-p", KAFKA);
        Assert.assertEquals(extra.field("command"), "bal discover method send -p " + KAFKA + " --client Producer",
                extra.stderr());
    }

    @Test
    public void aPluralNounWithNoPackageGetsTheShapeAndTheSearch() {
        CliTest.Capture plural = failure(USAGE, null, "functions", "printInfo");
        Assert.assertTrue(plural.field("message").startsWith("'functions' is not a noun; the noun is function; "),
                plural.stderr());
        Assert.assertEquals(plural.field("command"), "bal discover search printInfo", plural.stderr());
    }

    @Test
    public void aMethodNamedAsCodeCallsItPointsAtItsContainerFlag() {
        CliTest.Capture dotted = failure(NOT_FOUND, "ballerinax__kafka", "method", "Producer.send", "-p", KAFKA);
        Assert.assertEquals(dotted.field("command"), "bal discover method send -p " + KAFKA + " --client Producer",
                dotted.stderr());
    }
}
