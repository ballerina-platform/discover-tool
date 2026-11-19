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
import io.ballerina.tools.discover.central.HttpOptions;
import io.ballerina.tools.discover.cli.Cli;
import io.ballerina.tools.discover.render.DiscoverResult;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * What an answer prints: one declaration alone by default, the declarations it includes, {@code --expand-types},
 * {@code --no-docs}, lists of names and the {@code partial} failure a list can end with.
 */
public class ShapeAndListsTest {

    private static final String HTTP = "ballerina/http";

    private static final String KAFKA = "ballerinax/kafka";

    private static HttpOptions http() {
        return CliTest.centralFor("ballerina__http", "2.16.6");
    }

    private static HttpOptions kafka() {
        return CliTest.centralFor("ballerinax__kafka", "4.6.5");
    }

    private static CliTest.Capture run(HttpOptions options, int expected, String... argv) {
        CliTest.Capture capture = new CliTest.Capture();
        int code = Cli.run(List.of(argv), capture.streams(), options, null, false);
        Assert.assertEquals(code, expected, String.join(" ", argv) + " -> " + capture.stderr());
        return capture;
    }

    private static JsonObject json(CliTest.Capture capture) {
        return JsonParser.parseString(capture.stdout()).getAsJsonObject();
    }

    private static List<String> names(JsonArray array) {
        List<String> names = new ArrayList<>();
        array.forEach(entry -> names.add(entry.getAsJsonObject().get("name").getAsString()));
        return names;
    }

    private static DiscoverResult.TypeDeclaration declaration(String... argv) {
        DiscoverResult answer = Asked.of(FixtureCorpus.loadedFixture("ballerina__http"), argv).value();
        Assert.assertTrue(answer instanceof DiscoverResult.TypeDeclaration, answer.toString());
        return (DiscoverResult.TypeDeclaration) answer;
    }

    private static List<String> typeNames(List<DiscoverResult.Signature.Type> types) {
        return types.stream().map(DiscoverResult.Signature.Type::name).toList();
    }

    @Test
    public void aTypePrintsItsInclusionsAndListsEveryOtherTypeItNames() {
        DiscoverResult.TypeDeclaration config = declaration("type", "ClientConfiguration");
        Assert.assertEquals(typeNames(config.included()), List.of("CommonClientConfiguration"));
        Assert.assertTrue(config.included().get(0).declaration().contains("HttpVersion httpVersion"),
                "the included record's fields are the declaration's own fields");
        Assert.assertTrue(config.declaration().contains("*CommonClientConfiguration;"), config.declaration());
        Assert.assertTrue(config.types().isEmpty(), typeNames(config.types()).toString());
        List<String> omitted = config.omitted().stream().map(DiscoverResult.Method::name).toList();
        Assert.assertTrue(omitted.containsAll(List.of("HttpVersion", "ClientSecureSocket")), omitted.toString());
        Assert.assertFalse(omitted.contains("CommonClientConfiguration"), omitted.toString());
        config.omitted().forEach(row -> Assert.assertEquals(row.command(),
                "bal discover type " + row.name() + " -p ballerina/http:0.0.0-fixture"
                        .replace(":0.0.0-fixture", ""), row.command()));
    }

    @Test
    public void expandTypesPrintsTheUsedTypesAsBeforeAndKeepsInclusionsApart() {
        DiscoverResult.TypeDeclaration config = declaration("type", "ClientConfiguration", "--expand-types");
        Assert.assertEquals(typeNames(config.included()), List.of("CommonClientConfiguration"));
        List<String> used = typeNames(config.types());
        Assert.assertTrue(used.contains("HttpVersion"), used.toString());
        Assert.assertTrue(used.contains("ClientSecureSocket"), used.toString());
        Assert.assertFalse(used.contains("CommonClientConfiguration"), "an inclusion is not a used type: " + used);
        Assert.assertFalse(used.contains("ClientConfiguration"), used.toString());
        Assert.assertEquals(config.declaration(), declaration("type", "ClientConfiguration").declaration());
    }

    @Test
    public void anInclusionIsPrintedInTheTextAnswerUnderItsOwnHeading() {
        String text = run(http(), 0, "type", "ClientConfiguration", "-p", HTTP, "--output-format", "text").stdout();
        Assert.assertTrue(text.contains("\n\nIncluded types (1)\n  # Common client configurations"), text);
        Assert.assertTrue(text.contains("\n\nOther types it uses, not printed ("), text);
        Assert.assertFalse(text.contains("Types it uses ("), text);
        String expanded = run(http(), 0, "type", "ClientConfiguration", "-p", HTTP, "--expand-types",
                "--output-format", "text").stdout();
        Assert.assertTrue(expanded.contains("Included types (1)"), expanded);
        Assert.assertTrue(expanded.contains("\n\nTypes it uses ("), expanded);
    }

    @Test
    public void jsonCarriesIncludedOnlyWhenThereIsOneAndTypesIsEmptyByDefault() {
        JsonObject config = json(run(http(), 0, "type", "ClientConfiguration", "-p", HTTP));
        Assert.assertEquals(names(config.getAsJsonArray("included")), List.of("CommonClientConfiguration"));
        Assert.assertEquals(config.getAsJsonArray("types").size(), 0);
        Assert.assertTrue(config.has("omitted"), config.toString());
        JsonObject plain = json(run(http(), 0, "type", "HttpVersion", "-p", HTTP));
        Assert.assertFalse(plain.has("included"), plain.toString());
        Assert.assertEquals(plain.getAsJsonArray("types").size(), 0);
    }

    @Test
    public void noDocsLeavesOutEveryDocCommentAndNothingElse() {
        DiscoverResult.TypeDeclaration with = declaration("type", "ClientConfiguration", "--expand-types");
        DiscoverResult.TypeDeclaration without = declaration("type", "ClientConfiguration", "--expand-types",
                "--no-docs");
        Assert.assertTrue(with.included().get(0).declaration().contains("# "), "the control has docs");
        List<DiscoverResult.Signature.Type> all = new ArrayList<>(without.included());
        all.addAll(without.types());
        all.forEach(type -> Assert.assertFalse(type.declaration().lines().anyMatch(
                line -> line.strip().startsWith("#")), type.declaration()));
        Assert.assertFalse(without.declaration().lines().anyMatch(line -> line.strip().startsWith("#")));
        Assert.assertEquals(without.declaration(), with.declaration().lines()
                .filter(line -> !line.strip().startsWith("#")).collect(Collectors.joining("\n")));
        Assert.assertEquals(typeNames(without.types()), typeNames(with.types()));
        Assert.assertEquals(without.omitted(), with.omitted());
        // Independent of --expand-types.
        Assert.assertFalse(declaration("type", "ClientConfiguration", "--no-docs").included().get(0)
                .declaration().contains("# "));
    }

    @Test
    public void noDocsDropsTheDescriptionOfEachParameterInJson() {
        JsonObject documented = json(run(kafka(), 0, "method", "send", "-p", KAFKA, "--client", "Producer"));
        JsonObject bare = json(run(kafka(), 0, "method", "send", "-p", KAFKA, "--client", "Producer", "--no-docs"));
        Assert.assertTrue(documented.getAsJsonArray("params").get(0).getAsJsonObject().has("description"));
        Assert.assertFalse(bare.getAsJsonArray("params").get(0).getAsJsonObject().has("description"),
                bare.toString());
        Assert.assertFalse(bare.get("declaration").getAsString().contains("# "), bare.toString());
        Assert.assertTrue(bare.get("declaration").getAsString().contains("remote function send("));
        Assert.assertEquals(bare.getAsJsonArray("omitted"), documented.getAsJsonArray("omitted"));
    }

    @Test
    public void aMethodPrintsItsDeclarationAloneByDefaultAndItsTypesUnderExpandTypes() {
        JsonObject alone = json(run(kafka(), 0, "method", "send", "-p", KAFKA, "--client", "Producer"));
        Assert.assertEquals(alone.getAsJsonArray("types").size(), 0);
        Assert.assertEquals(names(alone.getAsJsonArray("omitted")), List.of("AnydataProducerRecord", "Error"));
        Assert.assertEquals(alone.getAsJsonArray("omitted").get(0).getAsJsonObject().get("command").getAsString(),
                "bal discover type AnydataProducerRecord -p ballerinax/kafka");
        JsonObject expanded = json(run(kafka(), 0, "method", "send", "-p", KAFKA, "--client", "Producer",
                "--expand-types"));
        Assert.assertEquals(names(expanded.getAsJsonArray("types")), List.of("AnydataProducerRecord", "Error"));
        Assert.assertFalse(expanded.has("omitted"), expanded.toString());
    }

    @Test
    public void aFunctionAndAResourceTakeTheFlagsToo() {
        JsonObject resource = json(run(CliTest.centralFor("ballerinax__github", "6.0.0"), 0, "resource",
                "repos/:owner/:repo/actions/caches", "--accessor", "delete", "-p", "ballerinax/github", "--client",
                "Client"));
        Assert.assertEquals(resource.getAsJsonArray("types").size(), 0, resource.toString());
        Assert.assertTrue(names(resource.getAsJsonArray("omitted")).contains("ActionsDeleteActionsCacheByKeyQueries"),
                resource.toString());
        JsonObject expanded = json(run(CliTest.centralFor("ballerinax__github", "6.0.0"), 0, "resource",
                "repos/:owner/:repo/actions/caches", "--accessor", "delete", "-p", "ballerinax/github", "--client",
                "Client", "--expand-types"));
        Assert.assertTrue(names(expanded.getAsJsonArray("types")).contains("ActionsDeleteActionsCacheByKeyQueries"),
                expanded.toString());
    }

    @Test
    public void theFlagsAreAUsageFailureWhereNoDeclarationIsOpened() {
        for (String[] argv : new String[][] {
                {"client", "Producer", "-p", KAFKA, "--no-docs"},
                {"service", "-p", KAFKA, "--expand-types"},
                {"readme", "-p", KAFKA, "--no-docs"},
                {"package", KAFKA, "--expand-types"},
                {"search", "send", "-p", KAFKA, "--no-docs"},
                {"--expand-types", "client", "-p", KAFKA},
                {"type", "-p", KAFKA, "--no-docs"},
                {"method", "-p", KAFKA, "--client", "Producer", "--expand-types"},
                {"type", "-p", KAFKA, "TopicPartition", "--expand-types=true"}}) {
            CliTest.Capture capture = run(kafka(), Failure.USAGE, argv);
            Assert.assertEquals(capture.stdout(), "", String.join(" ", argv));
            Assert.assertEquals(capture.field("kind"), "usage", String.join(" ", argv));
            String fix = capture.field("command");
            Assert.assertTrue(fix == null || String.join(" ", argv).contains("=true")
                    || !fix.contains("--expand-types") && !fix.contains("--no-docs"),
                    String.join(" ", argv) + " -> " + fix);
        }
    }

    @Test
    public void theFixCommandOfAMisusedShapeFlagDropsTheFlag() {
        Assert.assertEquals(run(http(), Failure.USAGE, "type", "--expand-types", "-p", HTTP).field("command"),
                "bal discover type -p ballerina/http");
        Assert.assertEquals(run(http(), Failure.USAGE, "type", "HttpVersion,Client", "--no-docs", "--expand-types",
                "-p", HTTP).field("command"), "bal discover type \"HttpVersion,Client\" -p ballerina/http");
    }

    @Test
    public void aListAnswersEachNameInTheOrderTypedAndOnceEach() {
        CliTest.Capture capture = run(http(), 0, "type", "HttpVersion,ClientConfiguration,HttpVersion", "-p", HTTP);
        JsonObject answer = json(capture);
        Assert.assertEquals(answer.get("qualified").getAsString(), HTTP);
        Assert.assertTrue(answer.has("version"), answer.toString());
        JsonArray answers = answer.getAsJsonArray("answers");
        Assert.assertEquals(answers.size(), 2);
        Assert.assertEquals(answers.get(0).getAsJsonObject().get("name").getAsString(), "HttpVersion");
        Assert.assertEquals(answers.get(1).getAsJsonObject().get("name").getAsString(), "ClientConfiguration");
        JsonObject alone = json(run(http(), 0, "type", "ClientConfiguration", "-p", HTTP));
        JsonObject second = answers.get(1).getAsJsonObject();
        alone.remove("qualified");
        alone.remove("version");
        Assert.assertEquals(second, alone, "each answer is the answer of its name alone");
        Assert.assertEquals(capture.stderr(), "");
    }

    @Test
    public void aListOfTextAnswersGivesEachItsOwnHeaderAndOneBlankLineBetween() {
        String text = run(http(), 0, "type", "HttpVersion,ClientConfiguration", "-p", HTTP, "--output-format", "text")
                .stdout();
        int second = text.indexOf("ballerina/http:2.16.6 · type · ClientConfiguration");
        Assert.assertTrue(text.startsWith("ballerina/http:2.16.6 · type · HttpVersion\n"), text);
        Assert.assertTrue(second > 0, text);
        Assert.assertTrue(text.substring(0, second).endsWith("\n\n") && !text.substring(0, second).endsWith("\n\n\n"),
                text);
    }

    @Test
    public void methodsOfOneContainerAndReadmeSectionsTakeAList() {
        JsonObject methods = json(run(kafka(), 0, "method", "init,send", "-p", KAFKA, "--client", "Producer"));
        JsonArray answers = methods.getAsJsonArray("answers");
        Assert.assertEquals(answers.size(), 2);
        Assert.assertEquals(answers.get(0).getAsJsonObject().get("kind").getAsString(), "constructor");
        Assert.assertEquals(answers.get(1).getAsJsonObject().get("name").getAsString(), "send");
        JsonObject sections = json(run(kafka(), 0, "readme", "7,3", "-p", KAFKA));
        JsonArray each = sections.getAsJsonArray("answers");
        Assert.assertEquals(each.size(), 2);
        Assert.assertEquals(each.get(0).getAsJsonObject().get("section").getAsInt(), 7);
        Assert.assertEquals(each.get(1).getAsJsonObject().get("section").getAsInt(), 3);
    }

    @Test
    public void aReadmeValueIsAListOnlyWhenItIsSectionNumbers() {
        // A title that holds a comma is one title: a miss of that title, not a list and not a usage failure.
        CliTest.Capture title = run(kafka(), Failure.RUNTIME, "readme", "Setup, part 2", "-p", KAFKA);
        Assert.assertEquals(title.field("kind"), "symbol-not-found");
        Assert.assertEquals(title.stdout(), "");
        Assert.assertFalse(title.stderr().contains("partial"), title.stderr());
        // Numbers and commas are a list, and an empty element is the usage failure it is for any list.
        Assert.assertEquals(run(kafka(), Failure.USAGE, "readme", "7,", "-p", KAFKA).field("kind"), "usage");
        Assert.assertEquals(run(kafka(), Failure.USAGE, "readme", "3,,7", "-p", KAFKA).field("kind"), "usage");
    }

    @Test
    public void aListHoldsAtMostTwentyNamesAndNoEmptyName() {
        String twenty = IntStream.rangeClosed(1, 20).mapToObj(n -> "T" + n).collect(Collectors.joining(","));
        // 20 names are a list; none exists, so each is a miss, which is partial and not usage.
        CliTest.Capture ok = run(http(), Failure.RUNTIME, "type", twenty, "-p", HTTP);
        Assert.assertEquals(ok.field("kind"), "partial");
        Assert.assertEquals(ok.failure().getAsJsonArray("failures").size(), 20);
        for (String list : new String[] {twenty + ",T21", "A,,B", "A,", ",A", ","}) {
            CliTest.Capture capture = run(CliTest.never(), Failure.USAGE, "type", list, "-p", HTTP);
            Assert.assertEquals(capture.field("kind"), "usage", list);
            Assert.assertEquals(capture.stdout(), "", list);
        }
        Assert.assertTrue(run(CliTest.never(), Failure.USAGE, "type", twenty + ",T21", "-p", HTTP)
                .field("message").contains("20"));
    }

    @Test
    public void aListOnANounThatTakesNoneIsAUsageFailureBeforeAnythingIsFetched() {
        for (String[] argv : new String[][] {
                {"client", "A,B", "-p", KAFKA},
                {"service", "A,B", "-p", KAFKA},
                {"class", "A,B", "-p", KAFKA},
                {"function", "a,b", "-p", KAFKA},
                {"resource", "a,b", "-p", KAFKA, "--client", "Producer"}}) {
            CliTest.Capture capture = run(CliTest.never(), Failure.USAGE, argv);
            Assert.assertEquals(capture.field("kind"), "usage", String.join(" ", argv));
            Assert.assertEquals(capture.stdout(), "");
        }
        // A query is a query, whatever it holds.
        Assert.assertEquals(run(kafka(), 0, "search", "send,poll", "-p", KAFKA).stderr(), "");
    }

    @Test
    public void aMissingNameIsOnePartialFailureAfterTheAnswersThatResolved() {
        CliTest.Capture capture = run(http(), Failure.RUNTIME, "type", "ClientConfiguration,NoSuch,HttpVersion", "-p",
                HTTP);
        JsonArray answers = json(capture).getAsJsonArray("answers");
        Assert.assertEquals(names(answers), List.of("ClientConfiguration", "HttpVersion"));
        JsonObject failure = capture.failure();
        Assert.assertEquals(failure.get("kind").getAsString(), "partial");
        Assert.assertEquals(failure.get("severity").getAsString(), "error");
        Assert.assertEquals(failure.get("message").getAsString(), "1 of 3 names did not resolve");
        Assert.assertFalse(failure.has("command"), failure.toString());
        Assert.assertEquals(failure.get("qualified").getAsString(), HTTP);
        JsonArray failures = failure.getAsJsonArray("failures");
        Assert.assertEquals(failures.size(), 1);
        JsonObject missed = failures.get(0).getAsJsonObject();
        Assert.assertEquals(missed.get("name").getAsString(), "NoSuch");
        Assert.assertEquals(missed.get("kind").getAsString(), "symbol-not-found");
        Assert.assertTrue(missed.has("message") && missed.has("suggestion"), missed.toString());
        Assert.assertEquals(capture.stderr().lines().count(), 1, "exactly one failure on standard error");
    }

    @Test
    public void whenNoNameResolvesStandardOutputIsEmptyAndEveryNameIsListedInTheOrderTyped() {
        CliTest.Capture capture = run(http(), Failure.RUNTIME, "type", "Zed,Alpha", "-p", HTTP);
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.field("message"), "2 of 2 names did not resolve");
        Assert.assertEquals(names(capture.failure().getAsJsonArray("failures")), List.of("Zed", "Alpha"));
    }

    @Test
    public void aPartialFailureReadsInTextBeneathTheExistingFailureFormat() {
        CliTest.Capture capture = run(http(), Failure.RUNTIME, "type", "ClientConfiguration,NoSuch", "-p", HTTP,
                "--output-format", "text");
        Assert.assertTrue(capture.stdout().startsWith("ballerina/http:2.16.6 · type · ClientConfiguration"),
                capture.stdout());
        Assert.assertTrue(capture.stderr().startsWith("bal discover: error: 1 of 2 names did not resolve\n  "),
                capture.stderr());
        Assert.assertTrue(capture.stderr().contains("\n    NoSuch: 'NoSuch' is not a type in"), capture.stderr());
        Assert.assertFalse(capture.stderr().contains("Usage: "), capture.stderr());
    }

    @Test
    public void aMissAmongSeveralOwnersCarriesItsOwnersInItsPartialEntry() {
        CliTest.Capture capture = run(kafka(), Failure.RUNTIME, "method", "send,commit", "-p", KAFKA);
        JsonObject failure = capture.failure();
        Assert.assertEquals(failure.get("kind").getAsString(), "partial");
        Assert.assertEquals(names(json(capture).getAsJsonArray("answers")), List.of("send"));
        JsonObject commit = failure.getAsJsonArray("failures").get(0).getAsJsonObject();
        Assert.assertEquals(commit.get("name").getAsString(), "commit");
        Assert.assertEquals(commit.get("kind").getAsString(), "ambiguous");
        Assert.assertEquals(names(commit.getAsJsonArray("owners")), List.of("Caller", "Consumer"));
    }

    @Test
    public void aFailureOfTheWholeCommandIsNotPartial() {
        CliTest.Capture missing = run(CliTest.options(FakeTransport.always(FakeTransport.status(404))),
                Failure.RUNTIME, "type", "A,B", "-p", "no-such-org/no-such-pkg");
        Assert.assertEquals(missing.field("kind"), "package-not-found");
        Assert.assertEquals(missing.stdout(), "");
        CliTest.Capture usage = run(CliTest.never(), Failure.USAGE, "type", "A,B");
        Assert.assertEquals(usage.field("kind"), "usage");
        Assert.assertEquals(usage.stdout(), "");
        CliTest.Capture filter = run(CliTest.never(), Failure.USAGE, "type", "A,B", "-p", HTTP, "--filter", "x");
        Assert.assertEquals(filter.field("kind"), "usage");
    }

    @Test
    public void underExpandTypesAUsedTypeIsPrintedOnceAndLaterAnswersListIt() {
        JsonArray answers = json(run(http(), 0, "type", "ClientConfiguration,ListenerConfiguration", "-p", HTTP,
                "--expand-types")).getAsJsonArray("answers");
        JsonObject first = answers.get(0).getAsJsonObject();
        JsonObject second = answers.get(1).getAsJsonObject();
        List<String> printedFirst = names(first.getAsJsonArray("types"));
        List<String> printedSecond = names(second.getAsJsonArray("types"));
        Assert.assertTrue(printedFirst.contains("HttpVersion"), printedFirst.toString());
        Assert.assertFalse(printedSecond.contains("HttpVersion"), printedSecond.toString());
        Assert.assertTrue(names(second.getAsJsonArray("omitted")).contains("HttpVersion"), second.toString());
        for (String name : printedSecond) {
            Assert.assertFalse(printedFirst.contains(name), name + " is printed under two answers");
        }
        JsonObject row = null;
        for (JsonElement each : second.getAsJsonArray("omitted")) {
            if (each.getAsJsonObject().get("name").getAsString().equals("HttpVersion")) {
                row = each.getAsJsonObject();
            }
        }
        Assert.assertEquals(row.get("command").getAsString(), "bal discover type HttpVersion -p ballerina/http");
        // Without the flag nothing is printed, so there is nothing to share.
        JsonArray plain = json(run(http(), 0, "type", "ClientConfiguration,ListenerConfiguration", "-p", HTTP))
                .getAsJsonArray("answers");
        Assert.assertEquals(plain.get(1).getAsJsonObject().getAsJsonArray("types").size(), 0);
    }

    @Test
    public void theOtherOptionsApplyToEveryNameOfAList() {
        JsonArray answers = json(run(kafka(), 0, "method", "init,send", "-p", KAFKA, "--client", "Producer",
                "--no-docs")).getAsJsonArray("answers");
        for (JsonElement each : answers) {
            Assert.assertFalse(each.getAsJsonObject().get("declaration").getAsString().contains("# "),
                    each.toString());
        }
    }

    @Test
    public void aMissingContainerIsTheOneFailureNotPartial() {
        CliTest.Capture capture = run(kafka(), Failure.RUNTIME, "method", "init,run", "--class", "Nope", "-p", KAFKA);
        Assert.assertEquals(capture.field("kind"), "symbol-not-found");
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertFalse(capture.stderr().contains("partial"), capture.stderr());
        Assert.assertEquals(capture.stderr().lines().count(), 1);
    }

    @Test
    public void namesAllDeclaredBySeveralContainersMakeTheWholeCommandAmbiguous() {
        CliTest.Capture capture = run(kafka(), Failure.USAGE, "method", "commit,commitOffset", "-p", KAFKA);
        Assert.assertEquals(capture.field("kind"), "ambiguous", capture.stderr());
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertFalse(capture.stderr().contains("partial"), capture.stderr());
    }

    @Test
    public void aPartialFailureInTextPrintsTheOwnersOfAnAmbiguousName() {
        CliTest.Capture capture = run(kafka(), Failure.RUNTIME, "method", "send,commit", "-p", KAFKA,
                "--output-format", "text");
        Assert.assertTrue(capture.stderr().contains("Caller") && capture.stderr().contains("Consumer"),
                capture.stderr());
        Assert.assertTrue(capture.stderr().contains("bal discover method commit"), capture.stderr());
        CliTest.Capture miss = run(kafka(), Failure.RUNTIME, "method", "send,Nope", "--client", "Producer", "-p",
                KAFKA, "--output-format", "text");
        Assert.assertTrue(miss.stderr().contains("candidates:"), miss.stderr());
        Assert.assertTrue(miss.stderr().contains("        init") || miss.stderr().contains("        send"),
                miss.stderr());
    }

    @Test
    public void aPrintedFixCommandKeepsTheShapeFlags() {
        CliTest.Capture capture = run(kafka(), Failure.USAGE, "method", "Producer", "send", "--expand-types",
                "--no-docs", "-p", KAFKA);
        Assert.assertEquals(capture.field("command"),
                "bal discover method send -p ballerinax/kafka --client Producer --expand-types --no-docs");
    }

    @Test
    public void pageDoesNotApplyToAList() {
        CliTest.Capture capture = run(CliTest.never(), Failure.USAGE, "type", "A,B", "-p", HTTP, "--page", "2");
        Assert.assertEquals(capture.field("kind"), "usage");
        Assert.assertEquals(capture.field("message"), "--page does not apply to a list of names");
        Assert.assertEquals(capture.stdout(), "");
    }

    @Test
    public void shapeFlagsOnAListThatOpensNoDeclarationFailTheWholeCommand() {
        CliTest.Capture capture = run(kafka(), Failure.USAGE, "type", "Producer,Consumer", "-p", KAFKA,
                "--expand-types");
        Assert.assertEquals(capture.stdout(), "");
        Assert.assertEquals(capture.field("kind"), "usage");
        CliTest.Capture none = run(kafka(), Failure.USAGE, "readme", "3,7", "-p", KAFKA, "--no-docs");
        Assert.assertEquals(none.field("kind"), "usage");
        Assert.assertEquals(none.stdout(), "");
    }

    @Test
    public void aFilterThatMatchesOneSectionOfAReadmeListIsAPerNameFailure() {
        CliTest.Capture capture = run(http(), Failure.RUNTIME, "readme", "3,1", "--filter", "Resiliency", "-p", HTTP);
        JsonArray answers = json(capture).getAsJsonArray("answers");
        Assert.assertEquals(answers.size(), 1, capture.stdout());
        JsonObject failure = capture.failure();
        Assert.assertEquals(failure.get("kind").getAsString(), "partial");
        JsonArray failures = failure.getAsJsonArray("failures");
        Assert.assertEquals(names(failures), List.of("1"));
        Assert.assertEquals(failures.get(0).getAsJsonObject().get("kind").getAsString(), "filter-no-match");
    }

    @Test
    public void aListOfOneNameRepeatedIsReadAsThatName() {
        CliTest.Capture seven = run(http(), 0, "readme", "7,7", "-p", HTTP);
        Assert.assertEquals(seven.stdout(), run(http(), 0, "readme", "7", "-p", HTTP).stdout());
        Assert.assertFalse(json(seven).has("answers"), seven.stdout());
        CliTest.Capture type = run(http(), 0, "type", "HttpVersion,HttpVersion", "-p", HTTP);
        Assert.assertEquals(type.stdout(), run(http(), 0, "type", "HttpVersion", "-p", HTTP).stdout());
        Assert.assertFalse(json(type).has("answers"), type.stdout());
    }

    @Test
    public void aNameOfAListIsTrimmedAndADuplicateKeepsItsFirstPlace() {
        Assert.assertEquals(names(json(run(http(), 0, "type", "HttpVersion, ClientConfiguration", "-p", HTTP))
                .getAsJsonArray("answers")), List.of("HttpVersion", "ClientConfiguration"));
        Assert.assertEquals(names(json(run(http(), 0, "type", "HttpVersion,ClientConfiguration,HttpVersion", "-p",
                HTTP)).getAsJsonArray("answers")), List.of("HttpVersion", "ClientConfiguration"));
    }
}
