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

import java.util.List;

/**
 * The noun-first grammar: every noun with and without a name, the container flags, and every usage error.
 */
public class GrammarTest {

    private static final String KAFKA = "ballerinax/kafka";

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
        if (expected == 2) {
            Assert.assertEquals(capture.field("kind"), "validation", String.join(" ", argv));
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
        Assert.assertEquals(Cli.run(List.of("--output", "text", "--package=" + KAFKA, "client", "Producer"),
                text.streams(), central("ballerinax__kafka")), 0, text.stderr());
        Assert.assertTrue(text.stdout().contains("Producer"), text.stdout());
        Assert.assertFalse(text.stdout().startsWith("{"), text.stdout());
    }

    @Test
    public void aContainerFlagBeforeTheNounNamesTheContainerOnlyForAMember() {
        JsonObject leading = answer("ballerinax__kafka", "--client", "Producer", "method", "send", "-p", KAFKA);
        Assert.assertEquals(leading.get("container").getAsString(), "Producer", leading.toString());
        Assert.assertEquals(failure(2, null, "--client", "Producer", "client", "-p", KAFKA).field("message"),
                "Unknown option '--client'.");
        Assert.assertTrue(failure(2, null, "--client", "A", "method", "-p", KAFKA, "--client", "B").field("message")
                .endsWith(" is given both before the noun and after it."));
    }

    @Test
    public void aMethodListingLeavesResourcesToResourceAndAResourceListingLeavesMethodsToMethod() {
        JsonObject methods = answer("ballerina__http", "method", "-p", "ballerina/http", "--client", "Client");
        Assert.assertFalse(methods.has("resources"), methods.toString());
        Assert.assertTrue(methods.has("remote") && methods.has("normal"), methods.toString());
        JsonObject resources = answer("ballerina__http", "resource", "-p", "ballerina/http", "--client", "Client");
        Assert.assertFalse(resources.has("remote") || resources.has("methods"), resources.toString());
        Assert.assertTrue(resources.has("resources"), resources.toString());
        CliTest.Capture none = failure(1, "ballerinax__github", "method", "-p", GITHUB, "--client", "Client");
        Assert.assertEquals(none.field("message"), "Client declares no methods in " + GITHUB + ":"
                + FixtureCorpus.FIXTURE_VERSION.text() + ".");
        Assert.assertEquals(none.field("command"), "bal discover resource -p " + GITHUB + " --client Client");
    }

    @Test
    public void aContainerFlagOfTheWrongKindFailsWithTheCommandThatHasTheRightOne() {
        CliTest.Capture wrong = failure(1, "ballerinax__kafka", "method", "send", "-p", KAFKA, "--class",
                "Producer");
        Assert.assertEquals(wrong.field("kind"), "symbol-not-found");
        Assert.assertEquals(wrong.field("command"), "bal discover method send -p " + KAFKA + " --client Producer");
        CliTest.Capture none = failure(1, "ballerinax__kafka", "method", "send", "-p", KAFKA, "--client", "Nope");
        Assert.assertEquals(none.field("command"), "bal discover client -p " + KAFKA);
    }

    @Test
    public void aMemberSeveralContainersDeclareListsEachOwnersCommand() {
        CliTest.Capture several = failure(Failure.AMBIGUOUS, "ballerinax__kafka", "method", "-p", KAFKA);
        Assert.assertEquals(several.failure().getAsJsonArray("owners").size(), 7, several.stderr());
        Assert.assertTrue(several.stderr().contains("bal discover method -p " + KAFKA + " --client Producer"),
                several.stderr());
    }

    @DataProvider(name = "oldForms")
    public Object[][] oldForms() {
        return new Object[][] {
                {List.of(KAFKA), "bal discover package " + KAFKA},
                {List.of(KAFKA, "--module", "sub"), "bal discover package " + KAFKA + " --module sub"},
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
                {List.of(KAFKA, "type", "TopicPartition"), "bal discover type TopicPartition -p " + KAFKA},
                {List.of(KAFKA, "4.6.6", "client", "Producer"), "bal discover client Producer -p " + KAFKA + ":4.6.6"},
                {List.of(KAFKA, "clients", "Producer"), "bal discover client Producer -p " + KAFKA},
                {List.of(KAFKA, "producers", "Producer"), "bal discover search producers -p " + KAFKA}};
    }

    @Test(dataProvider = "oldForms")
    public void thePackageFirstFormIsAUsageErrorWithTheSameRequestInTheNewOrder(List<String> argv, String command) {
        CliTest.Capture old = failure(2, null, argv.toArray(String[]::new));
        Assert.assertEquals(old.field("command"), command, old.stderr());
        Assert.assertEquals(old.field("suggestion"), "Run `" + command + "`.");
        Assert.assertEquals(old.field("message"), "'" + argv.get(0) + "' is not a noun; name the package with -p.");
    }

    @Test
    public void aNounWithoutThePackageIsAUsageErrorWithItsSynopsis() {
        for (String noun : List.of("client", "service", "class", "function", "type", "method", "resource",
                "readme")) {
            CliTest.Capture json = failure(2, null, noun);
            Assert.assertTrue(json.field("message").startsWith(noun + " needs the package it reads: -p "),
                    json.stderr());
            CliTest.Capture text = new CliTest.Capture();
            Assert.assertEquals(Cli.run(List.of(noun, "--output", "text"), text.streams(), CliTest.never()), 2);
            Assert.assertTrue(text.stderr().contains("\nUsage: bal discover " + noun + " "), text.stderr());
            Assert.assertTrue(text.stderr().contains(" -p <org>/<name>[:<version>] "), text.stderr());
            Assert.assertTrue(text.stderr().endsWith("Run `bal discover " + noun + " --help` for details.\n"),
                    text.stderr());
        }
    }

    @Test
    public void packageTakesTheCoordinateAsItsNameAndOneOnly() {
        Assert.assertTrue(failure(2, null, "package").field("message").startsWith("package needs the package"));
        CliTest.Capture flag = failure(2, null, "package", "-p", KAFKA);
        Assert.assertEquals(flag.field("command"), "bal discover package " + KAFKA);
    }

    @Test
    public void oneContainerFlagAtATimeAndOnlyOnMethodAndResource() {
        CliTest.Capture two = failure(2, null, "method", "send", "-p", KAFKA, "--client", "A", "--class", "B");
        Assert.assertEquals(two.field("message"), "A method has one container; pass one of --client, --class, "
                + "--service.");
        Assert.assertEquals(two.failure().getAsJsonArray("owners").get(1).getAsJsonObject().get("command")
                .getAsString(), "bal discover method send -p " + KAFKA + " --class B");
        CliTest.Capture misplaced = failure(2, null, "client", "Producer", "-p", KAFKA, "--client", "Producer");
        Assert.assertEquals(misplaced.field("message"), "Unknown option '--client'.");
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
            Assert.assertTrue(help.stdout().startsWith("Usage: bal discover " + noun), help.stdout());
            Assert.assertTrue(help.stdout().contains("\nExamples:\n  bal discover " + noun), help.stdout());
            Assert.assertFalse(help.stdout().contains("\n  --refresh "), "shared flags are the root's: " + noun);
            Assert.assertTrue(help.stdout().endsWith("Every noun also takes the flags `bal discover --help` lists.\n"),
                    help.stdout());
        }
        Assert.assertTrue(root.stdout().contains("\n  --client|--class|--service <name> "), root.stdout());
        CliTest.Capture method = new CliTest.Capture();
        Assert.assertEquals(Cli.run(List.of("method", "-h"), method.streams(), CliTest.never()), 0);
        Assert.assertTrue(method.stdout().startsWith("Usage: bal discover method [<name>] -p <org>/<name>"
                + "[:<version>]\n                    [--client|--class|--service <name>]"), method.stdout());
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
        CliTest.Capture misplaced = failure(2, null, argv.toArray(String[]::new));
        Assert.assertEquals(misplaced.field("command"), command, misplaced.stderr());
    }

    @Test
    public void aMissingPackageIsNeverInventedButFoundBySearch() {
        CliTest.Capture named = failure(2, null, "method", "send", "--client", "Producer");
        Assert.assertEquals(named.field("command"), "bal discover search send");
        Assert.assertEquals(named.field("suggestion"), "Write `bal discover method send -p <org>/<name> --client "
                + "Producer`; to find the package that declares 'send': `bal discover search send`.");
        CliTest.Capture bare = failure(2, null, "client");
        Assert.assertFalse(bare.failure().has("command"), bare.stderr());
        Assert.assertFalse(bare.stderr().contains("github"), bare.stderr());
        Assert.assertTrue(bare.field("suggestion").contains("`bal discover search <keyword>`"), bare.stderr());
        Assert.assertTrue(failure(2, null, "resource", "gists/public", "get").field("suggestion")
                .startsWith("Write `bal discover resource gists/public get -p <org>/<name>`"));
    }

    @DataProvider(name = "aliases")
    public Object[][] aliases() {
        return new Object[][] {
                {List.of("funcs", "-p", "ballerina/log"), "bal discover function -p ballerina/log"},
                {List.of("functions", "printInfo", "-p", "ballerina/log"),
                        "bal discover function printInfo -p ballerina/log"},
                {List.of("methods", "send", "-p", KAFKA, "--client", "Producer"),
                        "bal discover method send -p " + KAFKA + " --client Producer"},
                {List.of("-p", KAFKA, "clients"), "bal discover client -p " + KAFKA}};
    }

    @Test(dataProvider = "aliases")
    public void anOldBucketOrAPluralAsTheNounNamesTheNounItMeant(List<String> argv, String command) {
        CliTest.Capture alias = failure(2, null, argv.toArray(String[]::new));
        Assert.assertEquals(alias.field("command"), command, alias.stderr());
        Assert.assertTrue(alias.field("message").startsWith("'" + argv.stream().filter(word -> !word.startsWith("-")
                && !word.contains("/")).findFirst().orElseThrow() + "' is not a noun; the noun is "), alias.stderr());
    }

    @Test
    public void aFlagGivenBeforeAndAfterTheNounIsAUsageError() {
        for (List<String> argv : List.of(List.of("-p", "a/b", "client", "-p", "c/d"),
                List.of("--page", "2", "client", "-p", "a/b", "--page", "3"),
                List.of("--output", "json", "client", "-p", "a/b", "--output", "text"))) {
            CliTest.Capture repeated = failure(2, null, argv.toArray(String[]::new));
            Assert.assertTrue(repeated.field("message").endsWith(" is given both before the noun and after it."),
                    repeated.stderr());
        }
        CliTest.Capture both = failure(2, null, "-p", "a/b", "package", "c/d");
        Assert.assertEquals(both.field("command"), "bal discover package c/d");
    }

    @Test
    public void flagValuesAttachedWithEqualsAndWordsAfterTheEndOfOptionsRead() {
        JsonObject attached = answer("ballerinax__kafka", "method", "send", "--package=" + KAFKA, "--client=Producer");
        Assert.assertEquals(attached.get("container").getAsString(), "Producer", attached.toString());
        CliTest.Capture dashed = failure(1, "ballerinax__kafka", "method", "-p", KAFKA, "--", "-send");
        Assert.assertTrue(dashed.field("message").startsWith("'-send' is not a method"), dashed.stderr());
    }

    @Test
    public void aResourceMissedWithoutItsContainerPointsAtTheDeepestRealPath() {
        CliTest.Capture typo = failure(1, "ballerinax__github", "resource", "gists/nope", "get", "-p", GITHUB);
        Assert.assertEquals(typo.field("command"), "bal discover resource gists -p " + GITHUB + " --client Client",
                typo.stderr());
    }

    @Test
    public void aResourceMissedAmongSeveralContainersSearchesItsFirstSegmentOrListsTheResources() {
        CliTest.Capture slash = failure(1, "ballerina__http", "resource", "/", "get", "-p", "ballerina/http");
        Assert.assertEquals(slash.field("command"), "bal discover resource -p ballerina/http", slash.stderr());
        CliTest.Capture typo = failure(1, "ballerina__http", "resource", ":id/nope", "get", "-p", "ballerina/http");
        Assert.assertEquals(typo.field("command"), "bal discover search nope -p ballerina/http", typo.stderr());
    }

    @Test
    public void aMethodAskedOfAPackageWithOnlyResourcesPointsAtTheResources() {
        CliTest.Capture none = failure(1, "ballerinax__github", "method", "-p", GITHUB);
        Assert.assertEquals(none.field("command"), "bal discover resource -p " + GITHUB, none.stderr());
    }

    @Test
    public void aMethodNamedAsCodeCallsItPointsAtItsContainerFlag() {
        CliTest.Capture dotted = failure(1, "ballerinax__kafka", "method", "Producer.send", "-p", KAFKA);
        Assert.assertEquals(dotted.field("command"), "bal discover method send -p " + KAFKA + " --client Producer",
                dotted.stderr());
    }
}
