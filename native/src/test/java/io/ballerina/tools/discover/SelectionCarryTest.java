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
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every follow-up command a listing prints re-selects exactly what the listing was a window onto — the selector,
 * the accessor and the filter all ride along, and none of them is ever re-spelled into something else.
 *
 * <p>Driven through {@link Cli#run} against the recorded payloads, so each case is the command an agent types
 * and the command it is told to type next.
 *
 * @since 0.1.0
 */
public class SelectionCarryTest {

    private static final String GITHUB = "bal discover ballerinax/github client Client";

    private record Run(int code, String out, String err) {

        JsonObject json() {
            return JsonParser.parseString(out).getAsJsonObject();
        }
    }

    private static HttpOptions centralFor(String docs) {
        return HttpOptions.builder()
                .transport(FakeTransport.routing(url -> url.contains("/docs/")
                        ? FakeTransport.ok(docs)
                        : FakeTransport.ok("[\"" + FixtureCorpus.FIXTURE_VERSION.text() + "\"]")))
                .baseDelayMs(1)
                .sleeper(millis -> {
                    // No test here asserts wall-clock behaviour.
                })
                .build();
    }

    private static Run run(String slug, String command) {
        return run(centralFor(FixtureCorpus.loadRawFixture(slug).toString()), command);
    }

    private static Run run(HttpOptions http, String command) {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        int code = Cli.run(argv(command), new Cli.Streams(out::append, err::append), http);
        return new Run(code, out.toString(), err.toString());
    }

    private static JsonObject answer(String slug, String command) {
        Run run = run(slug, command);
        Assert.assertEquals(run.code(), 0, command + " -> " + run.err());
        return run.json();
    }

    /** A printed command as argv, honouring the quoting {@link Texts#shellWord} produces. */
    private static List<String> argv(String command) {
        List<String> tokens = new ArrayList<>();
        Matcher token = Pattern.compile("'([^']*)'|\"([^\"]*)\"|(\\S+)").matcher(command);
        while (token.find()) {
            tokens.add(token.group(1) != null ? token.group(1)
                    : token.group(2) != null ? token.group(2) : token.group(3));
        }
        Assert.assertEquals(tokens.subList(0, 2), List.of("bal", "discover"), command);
        return tokens.subList(2, tokens.size());
    }

    // -----------------------------------------------------------------------
    // Paging keeps the selector
    // -----------------------------------------------------------------------

    @Test
    public void turningThePageOfASelectedMethodListKeepsTheSelector() {
        String command = "bal discover ballerinax/twilio client Client list";
        JsonObject first = answer("ballerinax__twilio", command);
        Assert.assertEquals(first.get("total").getAsInt(), 84, first.toString());
        Assert.assertEquals(first.get("next").getAsString(), command + " --page 2");

        JsonObject second = answer("ballerinax__twilio", first.get("next").getAsString());
        Assert.assertEquals(second.get("total").getAsInt(), 84, "page 2 widened back out to the whole client");
        JsonObject third = answer("ballerinax__twilio", second.get("next").getAsString());
        Assert.assertEquals(third.get("shown").getAsInt(), 4);
        Assert.assertEquals(third.get("page").getAsInt(), 3);
        Assert.assertFalse(third.has("next"), third.toString());
    }

    @Test
    public void aLaterPageCountsWhatIsLeftNotWhatWasShownInText() {
        Run text = run("ballerinax__twilio", "bal discover ballerinax/twilio client Client --page 2 --output text");
        Assert.assertTrue(text.out().contains("\n... 119 more (page 2 of 5)\n"
                + "Next: bal discover ballerinax/twilio client Client --page 3\n"), text.out());
    }

    @Test
    public void aPageOutsideTheListingIsAUsageFailureNamingTheRange() {
        for (String page : List.of("6", "0")) {
            Run run = run("ballerinax__twilio", "bal discover ballerinax/twilio client Client --page " + page);
            Assert.assertEquals(run.code(), 1, run.out());
            Assert.assertEquals(run.out(), "");
            JsonObject failure = JsonParser.parseString(run.err()).getAsJsonObject();
            Assert.assertEquals(failure.get("kind").getAsString(), "validation");
            Assert.assertTrue(failure.get("message").getAsString().contains("5 pages"), run.err());
            Assert.assertTrue(failure.get("suggestion").getAsString()
                    .contains("bal discover ballerinax/twilio client Client --page 5"), run.err());
        }
        Run readme = run("ballerinax__kafka", "bal discover ballerinax/kafka readme --filter kafka --page 9");
        Assert.assertEquals(readme.code(), 1, readme.out());
        Assert.assertEquals(JsonParser.parseString(readme.err()).getAsJsonObject().get("kind").getAsString(),
                "validation");
    }

    // -----------------------------------------------------------------------
    // Which container answered, and how to name it again
    // -----------------------------------------------------------------------

    @Test
    public void anAutoSelectedContainerIsNamedInBothRenderings() {
        JsonObject service = answer("ballerinax__kafka", "bal discover ballerinax/kafka service");
        Assert.assertEquals(service.get("container").getAsString(), "Service", service.toString());
        Run text = run("ballerinax__kafka", "bal discover ballerinax/kafka service --output text");
        Assert.assertTrue(text.out().startsWith("ballerinax/kafka · service · Service\n"), text.out());

        JsonObject groups = answer("ballerinax__github", "bal discover ballerinax/github client");
        Assert.assertEquals(groups.get("container").getAsString(), "Client", groups.toString());
    }

    @Test
    public void aKindToleranceNoteNamesTheWholeCanonicalCommandRunnably() {
        String asked = "bal discover ballerinax/github class Client \"gists/'public\" get";
        JsonObject signature = answer("ballerinax__github", asked);
        String note = signature.get("note").getAsString();
        String canonical = GITHUB + " \"gists/'public\" get";
        Assert.assertTrue(note.endsWith("Canonical: " + canonical), note);
        Assert.assertFalse(note.contains("`"), note);
        JsonObject followed = answer("ballerinax__github", canonical);
        Assert.assertEquals(followed.get("path").getAsString(), "gists/'public");
        Assert.assertEquals(followed.get("accessor").getAsString(), "get");
    }

    @Test
    public void aRosterEntryOpensItsContainerUnderTheSameFilter() {
        JsonObject roster = answer("ballerinax__kafka", "bal discover ballerinax/kafka client --filter commit");
        for (JsonElement element : roster.getAsJsonArray("containers")) {
            Assert.assertTrue(element.getAsJsonObject().get("call").getAsString().endsWith(" --filter commit"),
                    element.toString());
        }
    }

    // -----------------------------------------------------------------------
    // A selection that is not a path never becomes one
    // -----------------------------------------------------------------------

    @Test
    public void aNameSubstringOverTheCeilingPagesFlatRatherThanInventingGroups() {
        JsonObject action = answer("ballerinax__github", GITHUB + " action");
        Assert.assertFalse(action.has("groups"), "groups spliced from a substring: " + action);
        Assert.assertEquals(action.get("next").getAsString(), GITHUB + " action --page 2");
        for (JsonElement element : action.getAsJsonArray("resources")) {
            Assert.assertFalse(element.getAsJsonObject().get("path").getAsString().startsWith("action/"),
                    element.toString());
        }
    }

    @Test
    public void aSubstringThatLooksLikeAnAccessorIsReTypedAsGivenNotJoinedIntoAPath() {
        JsonObject get = answer("ballerinax__github", GITHUB + " get");
        Assert.assertEquals(get.get("next").getAsString(), GITHUB + " get --page 2");
        JsonObject second = answer("ballerinax__github", get.get("next").getAsString());
        Assert.assertEquals(second.get("total").getAsInt(), get.get("total").getAsInt());
    }

    // -----------------------------------------------------------------------
    // Grouping counts and carries the accessor
    // -----------------------------------------------------------------------

    @Test
    public void groupsUnderAnAccessorCountOnlyThatAccessorAndCarryItIntoTheirCalls() {
        JsonObject groups = answer("ballerinax__github", GITHUB + " repos get");
        JsonObject actions = groupNamed(groups.getAsJsonArray("groups"), "repos/:owner/:repo/actions");
        int count = actions.get("count").getAsInt();
        Assert.assertTrue(count < 72, "repos/:owner/:repo/actions counted every accessor: " + count);
        Assert.assertEquals(actions.get("call").getAsString(), GITHUB + " repos/:owner/:repo/actions get");

        JsonObject opened = answer("ballerinax__github", actions.get("call").getAsString());
        Assert.assertEquals(opened.get("total").getAsInt(), count, opened.toString());
        for (JsonElement element : opened.getAsJsonArray("resources")) {
            Assert.assertEquals(element.getAsJsonObject().getAsJsonArray("accessors").toString(), "[\"get\"]");
        }
    }

    @Test
    public void operationsEndingAtTheGroupedPrefixAreReachableRatherThanAGroupThatLoops() {
        String command = GITHUB + " repos";
        JsonObject groups = answer("ballerinax__github", command);
        for (JsonElement element : groups.getAsJsonArray("groups")) {
            Assert.assertNotEquals(element.getAsJsonObject().get("call").getAsString(), command, element.toString());
        }
        JsonObject here = groups.getAsJsonArray("resources").get(0).getAsJsonObject();
        Assert.assertEquals(here.get("path").getAsString(), "repos/:owner/:repo");
        JsonObject calls = here.getAsJsonObject("calls");
        Assert.assertEquals(calls.size(), here.getAsJsonArray("accessors").size(), here.toString());
        for (JsonElement accessor : here.getAsJsonArray("accessors")) {
            String call = calls.get(accessor.getAsString()).getAsString();
            Assert.assertEquals(call, GITHUB + " repos/:owner/:repo " + accessor.getAsString());
            JsonObject signature = answer("ballerinax__github", call);
            Assert.assertEquals(signature.get("path").getAsString(), "repos/:owner/:repo");
            Assert.assertEquals(signature.get("accessor").getAsString(), accessor.getAsString());
        }
    }

    private static JsonObject groupNamed(JsonArray groups, String name) {
        for (JsonElement element : groups) {
            if (element.getAsJsonObject().get("name").getAsString().equals(name)) {
                return element.getAsJsonObject();
            }
        }
        throw new AssertionError("no group " + name + " in " + groups);
    }

    /**
     * github with every path pushed four literal segments deeper, so a selection five literal segments down still
     * holds hundreds of operations — no recorded connector nests that deep, which is the only reason the depth
     * cap is exercised against an edited payload rather than a recorded one.
     */
    @Test
    public void groupingStopsAtTheDepthCapCountedInLiteralSegments() {
        JsonObject docs = FixtureCorpus.loadRawFixture("ballerinax__github").deepCopy().getAsJsonObject();
        JsonObject client = docs.getAsJsonObject("docsData").getAsJsonArray("modules").get(0).getAsJsonObject()
                .getAsJsonArray("clients").get(0).getAsJsonObject();
        for (String array : List.of("methods", "resourceMethods")) {
            for (JsonElement element : client.getAsJsonArray(array)) {
                JsonObject method = element.getAsJsonObject();
                if (method.get("isResource").getAsBoolean()) {
                    method.addProperty("resourcePath", "a/b/c/d/" + method.get("resourcePath").getAsString());
                }
            }
        }
        HttpOptions http = centralFor(docs.toString());

        Run four = run(http, GITHUB + " a/b/c/d");
        Assert.assertEquals(four.code(), 0, four.err());
        Assert.assertTrue(four.json().has("groups"), "four literal levels still group: " + four.out());

        Run five = run(http, GITHUB + " a/b/c/d/repos");
        Assert.assertEquals(five.code(), 0, five.err());
        Assert.assertFalse(five.json().has("groups"), "a fifth literal level grouped again: " + five.out());
        Assert.assertTrue(five.json().get("total").getAsInt() > 40, "not over the ceiling at all: " + five.out());
        Assert.assertEquals(five.json().get("next").getAsString(), GITHUB + " a/b/c/d/repos --page 2");

        Run parameters = run(http, GITHUB + " a/b/c/d/repos/:owner/:repo");
        Assert.assertEquals(parameters.code(), 0, parameters.err());
        Assert.assertFalse(parameters.json().has("groups"), "parameters counted toward depth: " + parameters.out());
    }
}
