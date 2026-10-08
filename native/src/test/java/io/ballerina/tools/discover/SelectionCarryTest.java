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
                + "Next: bal discover ballerinax/twilio client Client <name>\n"
                + "Next: bal discover ballerinax/twilio client Client --page 3\n"), text.out());
    }

    @Test
    public void aPageOutsideTheListingIsAUsageFailureNamingTheRange() {
        Run run = run("ballerinax__twilio", "bal discover ballerinax/twilio client Client --page 6");
        Assert.assertEquals(run.code(), 1, run.out());
        Assert.assertEquals(run.out(), "");
        JsonObject failure = JsonParser.parseString(run.err()).getAsJsonObject();
        Assert.assertEquals(failure.get("kind").getAsString(), "validation");
        Assert.assertTrue(failure.get("message").getAsString().contains("5 pages"), run.err());
        Assert.assertTrue(failure.get("suggestion").getAsString()
                .contains("bal discover ballerinax/twilio client Client --page 5"), run.err());
        Run readme = run("ballerinax__kafka", "bal discover ballerinax/kafka readme --filter kafka --page 9");
        Assert.assertEquals(readme.code(), 1, readme.out());
        Assert.assertEquals(JsonParser.parseString(readme.err()).getAsJsonObject().get("kind").getAsString(),
                "validation");
    }

    @Test
    public void anAutoSelectedContainerIsNamedInBothRenderings() {
        JsonObject service = answer("ballerinax__kafka", "bal discover ballerinax/kafka service");
        Assert.assertEquals(service.get("container").getAsString(), "Service", service.toString());
        Run text = run("ballerinax__kafka", "bal discover ballerinax/kafka service --output text");
        Assert.assertTrue(text.out().startsWith("ballerinax/kafka:0.0.0-fixture · service · Service\n"), text.out());

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
            Assert.assertTrue(element.getAsJsonObject().get("command").getAsString().endsWith(" --filter commit"),
                    element.toString());
        }
    }

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

    @Test
    public void groupsUnderAnAccessorCountOnlyThatAccessorAndCarryItIntoTheirCommands() {
        JsonObject groups = answer("ballerinax__github", GITHUB + " repos get");
        JsonObject actions = groupNamed(groups.getAsJsonArray("groups"), "repos/:owner/:repo/actions");
        int count = actions.get("count").getAsInt();
        Assert.assertTrue(count < 72, "repos/:owner/:repo/actions counted every accessor: " + count);
        Assert.assertEquals(actions.get("command").getAsString(), GITHUB + " repos/:owner/:repo/actions get");

        JsonObject opened = answer("ballerinax__github", actions.get("command").getAsString());
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
            Assert.assertNotEquals(element.getAsJsonObject().get("command").getAsString(), command, element.toString());
        }
        JsonObject here = groups.getAsJsonArray("resources").get(0).getAsJsonObject();
        Assert.assertEquals(here.get("path").getAsString(), "repos/:owner/:repo");
        JsonObject commands = here.getAsJsonObject("commands");
        Assert.assertEquals(commands.size(), here.getAsJsonArray("accessors").size(), here.toString());
        for (JsonElement accessor : here.getAsJsonArray("accessors")) {
            String opens = commands.get(accessor.getAsString()).getAsString();
            Assert.assertEquals(opens, GITHUB + " repos/:owner/:repo " + accessor.getAsString());
            JsonObject signature = answer("ballerinax__github", opens);
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

    private static final String HTTP = "bal discover ballerina/http client Client";

    private static HttpOptions bulkyHttp() {
        JsonObject docs = FixtureCorpus.loadRawFixture("ballerina__http").deepCopy().getAsJsonObject();
        JsonObject client = null;
        for (JsonElement element : docs.getAsJsonObject("docsData").getAsJsonArray("modules").get(0)
                .getAsJsonObject().getAsJsonArray("clients")) {
            if (element.getAsJsonObject().get("name").getAsString().equals("Client")) {
                client = element.getAsJsonObject();
            }
        }
        Assert.assertNotNull(client);
        JsonArray methods = client.getAsJsonArray("methods");
        JsonObject remote = null;
        JsonObject normal = null;
        for (JsonElement element : methods) {
            String name = element.getAsJsonObject().get("name").getAsString();
            if (name.equals("execute")) {
                remote = element.getAsJsonObject();
            } else if (name.equals("getCookieStore")) {
                normal = element.getAsJsonObject();
            }
        }
        Assert.assertNotNull(remote);
        Assert.assertNotNull(normal);
        for (int i = 1; i <= 45; i++) {
            JsonObject copy = remote.deepCopy();
            copy.addProperty("name", String.format("bulkRemote%02d", i));
            methods.add(copy);
        }
        for (int i = 1; i <= 26; i++) {
            JsonObject copy = normal.deepCopy();
            copy.addProperty("name", String.format("bulkNormal%02d", i));
            methods.add(copy);
        }
        return centralFor(docs.toString());
    }

    private static List<String> strings(JsonObject json, String field) {
        List<String> values = new ArrayList<>();
        if (json.has(field)) {
            json.getAsJsonArray(field).forEach(value ->
                    values.add(value.getAsJsonObject().get("name").getAsString()));
        }
        return values;
    }

    @Test
    public void aMixedListingOverTheCeilingPagesWithItsSectionsKept() {
        HttpOptions http = bulkyHttp();
        Run run = run(http, HTTP);
        Assert.assertEquals(run.code(), 0, run.err());
        JsonObject first = run.json();
        Assert.assertEquals(first.getAsJsonArray("resources").size(), 1, first.toString());
        Assert.assertEquals(strings(first, "remote").size(), 39, first.toString());
        Assert.assertFalse(first.has("normal"), first.toString());
        Assert.assertEquals(first.get("shown").getAsInt(), 40);
        Assert.assertEquals(first.get("total").getAsInt(), 91);
        Assert.assertEquals(first.get("page").getAsInt(), 1);
        Assert.assertEquals(first.get("pages").getAsInt(), 3);
        Assert.assertEquals(first.get("next").getAsString(), HTTP + " --page 2");

        Run middle = run(http, first.get("next").getAsString());
        Assert.assertEquals(middle.code(), 0, middle.err());
        JsonObject second = middle.json();
        Assert.assertFalse(second.has("resources"), second.toString());
        Assert.assertEquals(second.getAsJsonObject("counts").toString(),
                "{\"resources\":1,\"remote\":60,\"normal\":30}", "page 2 still names every form it does not hold");
        Assert.assertEquals(strings(second, "remote").size(), 21, second.toString());
        Assert.assertEquals(strings(second, "normal").size(), 19, second.toString());
        Assert.assertEquals(second.get("next").getAsString(), HTTP + " --page 3");

        Run last = run(http, second.get("next").getAsString());
        Assert.assertEquals(last.code(), 0, last.err());
        JsonObject third = last.json();
        Assert.assertFalse(third.has("resources") || third.has("remote"), third.toString());
        Assert.assertEquals(strings(third, "normal").size(), 11, third.toString());
        Assert.assertEquals(third.get("page").getAsInt(), 3);
        Assert.assertFalse(third.has("next"), third.toString());

        List<String> remote = new ArrayList<>(strings(first, "remote"));
        remote.addAll(strings(second, "remote"));
        Assert.assertEquals(remote.stream().distinct().count(), 60L, "a page repeated or skipped an entry");
        List<String> normal = new ArrayList<>(strings(second, "normal"));
        normal.addAll(strings(third, "normal"));
        Assert.assertEquals(normal.stream().distinct().count(), 30L, "a page repeated or skipped an entry");

        Run text = run(http, HTTP + " --page 2 --output text");
        Assert.assertTrue(text.out().contains("\n\nRemote (->)\n  "), text.out());
        Assert.assertTrue(text.out().contains("\n\nNormal (.)\n  "), text.out());
        Assert.assertTrue(text.out().contains("\n... 11 more (page 2 of 3)\nNext: " + HTTP + " <name>\nNext: " + HTTP
                + " --page 3"), text.out());
    }

    @Test
    public void aMixedListingsPageKeepsTheFilterAndFailsOutsideItsRange() {
        HttpOptions http = bulkyHttp();
        Run filtered = run(http, HTTP + " --filter e");
        Assert.assertEquals(filtered.code(), 0, filtered.err());
        JsonObject first = filtered.json();
        Assert.assertTrue(first.has("resources") && first.get("total").getAsInt() > 40, first.toString());
        Assert.assertEquals(first.get("next").getAsString(), HTTP + " --filter e --page 2");
        Run second = run(http, first.get("next").getAsString());
        Assert.assertEquals(second.code(), 0, second.err());
        Assert.assertEquals(second.json().get("total").getAsInt(), first.get("total").getAsInt(),
                "page 2 widened back out to the whole client");

        Run outside = run(http, HTTP + " --page 4");
        Assert.assertEquals(outside.code(), 1, outside.out());
        Assert.assertEquals(outside.out(), "");
        JsonObject failure = JsonParser.parseString(outside.err()).getAsJsonObject();
        Assert.assertEquals(failure.get("kind").getAsString(), "validation");
        Assert.assertTrue(failure.get("message").getAsString().contains("3 pages"), outside.err());
        Assert.assertTrue(failure.get("suggestion").getAsString().contains(HTTP + " --page 3"), outside.err());
    }

    /** The recorded redis {@code Client}: 111 remote methods and {@code close}, 112 entries on pages of 40/40/32. */
    @Test
    public void theRecordedRedisClientPagesThroughBothFormsWithoutRepeatingOrSkipping() {
        String command = "bal discover ballerinax/redis client Client";
        List<String> remote = new ArrayList<>();
        List<String> normal = new ArrayList<>();
        JsonObject page = answer("ballerinax__redis", command);
        int[] expected = {40, 40, 31};
        for (int number = 1; number <= 3; number++) {
            Assert.assertEquals(page.get("page").getAsInt(), number, page.toString());
            Assert.assertEquals(page.get("total").getAsInt(), 112);
            Assert.assertEquals(page.getAsJsonObject("counts").toString(), "{\"remote\":111,\"normal\":1}");
            Assert.assertEquals(strings(page, "remote").size(), expected[number - 1], page.toString());
            remote.addAll(strings(page, "remote"));
            normal.addAll(strings(page, "normal"));
            if (number < 3) {
                Assert.assertFalse(page.has("normal"), page.toString());
                Assert.assertEquals(page.get("next").getAsString(), command + " --page " + (number + 1));
                page = answer("ballerinax__redis", page.get("next").getAsString());
            } else {
                Assert.assertFalse(page.has("next"), page.toString());
            }
        }
        Assert.assertEquals(remote.stream().distinct().count(), 111L, "a page repeated or skipped an entry");
        Assert.assertEquals(normal, List.of("close"));

        Run text = run("ballerinax__redis", command + " --output text");
        Assert.assertTrue(text.out().startsWith(
                "ballerinax/redis:0.0.0-fixture · client · Client\n111 remote methods, 1 normal method\n"), text.out());
    }

    /**
     * A common word matches most of a package in its documentation alone: redis's {@code the} matches no name but
     * 102 entries' docs. Those are held to the same ceiling as every listing, with the full count beside them.
     */
    @Test
    public void documentationOnlyMatchesStayUnderTheCeilingAndPageToTheRest() {
        String command = "bal discover ballerinax/redis client Client --filter the";
        JsonObject miss = answer("ballerinax__redis", command);
        Assert.assertEquals(miss.getAsJsonArray("documented").size(), 40, miss.toString());
        Assert.assertEquals(miss.get("documentedTotal").getAsInt(), 102, miss.toString());
        Assert.assertEquals(miss.get("next").getAsString(), command + " --page 2");

        List<String> documented = new ArrayList<>();
        JsonObject page = miss;
        while (true) {
            page.getAsJsonArray("documented")
                    .forEach(entry -> documented.add(entry.getAsJsonObject().get("name").getAsString()));
            if (!page.has("next")) {
                break;
            }
            page = answer("ballerinax__redis", page.get("next").getAsString());
        }
        Assert.assertEquals(page.get("page").getAsInt(), 3, page.toString());
        Assert.assertEquals(page.getAsJsonArray("documented").get(0).getAsJsonObject().get("command").getAsString(),
                "bal discover ballerinax/redis client Client "
                        + page.getAsJsonArray("documented").get(0).getAsJsonObject().get("name").getAsString());
        Assert.assertEquals(documented.size(), 102);
        Assert.assertEquals(documented.stream().distinct().count(), 102L, "a page repeated or skipped a name");

        Run text = run("ballerinax__redis", command + " --output text");
        Assert.assertTrue(text.out().contains("\nMatched by documentation only (40 of 102)\n"), text.out());
        Assert.assertTrue(text.out().endsWith("\n... 62 more matched by documentation only (page 1 of 3)\n"
                + "Next: bal discover ballerinax/redis client Client <name>\nNext: " + command + " --page 2\n"),
                text.out());
    }

    /**
     * Documentation-only matches beside a listing are the listing's last section, under the same {@code --page}:
     * redis's {@code key} matches 103 methods by name and more in their docs alone, and every one of both is
     * reachable by turning pages, none twice.
     */
    @Test
    public void documentationOnlyMatchesBesideAListingPageAfterIt() {
        String command = "bal discover ballerinax/redis client Client --filter key";
        JsonObject page = answer("ballerinax__redis", command);
        int documentedTotal = page.get("documentedTotal").getAsInt();
        Assert.assertTrue(documentedTotal > 0, page.toString());
        Assert.assertEquals(page.getAsJsonArray("documented").size(), 0, "documented before the listing: " + page);
        Assert.assertEquals(page.get("pages").getAsInt(), (103 + documentedTotal + 39) / 40, page.toString());

        List<String> methods = new ArrayList<>();
        List<String> documented = new ArrayList<>();
        while (true) {
            methods.addAll(strings(page, "methods"));
            int names = page.getAsJsonArray("documented").size();
            page.getAsJsonArray("documented")
                    .forEach(entry -> documented.add(entry.getAsJsonObject().get("name").getAsString()));
            Assert.assertTrue(page.get("shown").getAsInt() + names <= 40, "over the ceiling: " + page);
            if (!page.has("next")) {
                break;
            }
            page = answer("ballerinax__redis", page.get("next").getAsString());
        }
        Assert.assertEquals(methods.stream().distinct().count(), 103L);
        Assert.assertEquals(documented.size(), documentedTotal);
        Assert.assertEquals(documented.stream().distinct().count(), (long) documentedTotal);
    }

    /** postgresql's 125 classes, 82 of them declaring no method at all — every one reachable by turning pages. */
    @Test
    public void aRosterOverTheCeilingPagesAndEveryContainerOnItIsReachable() {
        String command = "bal discover ballerinax/postgresql class";
        List<String> names = new ArrayList<>();
        JsonObject page = answer("ballerinax__postgresql", command);
        for (int number = 1; ; number++) {
            Assert.assertEquals(page.get("page").getAsInt(), number, page.toString());
            Assert.assertEquals(page.get("pages").getAsInt(), 4, page.toString());
            for (JsonElement element : page.getAsJsonArray("containers")) {
                JsonObject container = element.getAsJsonObject();
                names.add(container.get("name").getAsString());
                Assert.assertEquals(run("ballerinax__postgresql", container.get("command").getAsString()).code(), 0,
                        container.toString());
            }
            if (!page.has("next")) {
                break;
            }
            Assert.assertEquals(page.get("next").getAsString(), command + " --page " + (number + 1));
            page = answer("ballerinax__postgresql", page.get("next").getAsString());
        }
        Assert.assertEquals(names.size(), 125);
        Assert.assertEquals(names.stream().distinct().count(), 125L, "a page repeated or skipped a container");
        Assert.assertTrue(names.contains("TsQueryValue"), names.toString());
    }

    @Test
    public void aRosterFilterKeepsAContainerByItsOwnNameAndOpensItWhole() {
        JsonObject roster = answer("ballerinax__postgresql",
                "bal discover ballerinax/postgresql class --filter TsQuery");
        Assert.assertFalse(roster.has("candidates"), "a container named by the filter was not kept: " + roster);
        List<String> names = strings(roster, "containers");
        Assert.assertTrue(names.containsAll(List.of("TsQueryValue", "TsQueryArrayValue", "TsQueryOutParameter")),
                names.toString());
        for (JsonElement element : roster.getAsJsonArray("containers")) {
            String opens = element.getAsJsonObject().get("command").getAsString();
            Assert.assertFalse(opens.contains("--filter"), "a name match narrowed its own members: " + opens);
            Assert.assertFalse(answer("ballerinax__postgresql", opens).has("candidates"), opens);
        }
    }

    @Test
    public void aFilteredRostersPageKeepsTheFilter() {
        String command = "bal discover ballerinax/postgresql class --filter Value";
        JsonObject first = answer("ballerinax__postgresql", command);
        Assert.assertTrue(first.get("total").getAsInt() > 40, first.toString());
        Assert.assertEquals(first.get("next").getAsString(), command + " --page 2");
        JsonObject second = answer("ballerinax__postgresql", first.get("next").getAsString());
        Assert.assertEquals(second.get("total").getAsInt(), first.get("total").getAsInt(),
                "page 2 widened back out to the whole bucket");
    }

    /** A path with several accessors is one resource path, not one per accessor, in a roster as in a listing. */
    @Test
    public void aRosterCountsResourcePathsNotOperations() {
        JsonObject roster = answer("ballerina__http", "bal discover ballerina/http client");
        for (JsonElement element : roster.getAsJsonArray("containers")) {
            JsonObject container = element.getAsJsonObject();
            if (!container.has("resources")) {
                continue;
            }
            JsonObject opened = answer("ballerina__http", container.get("command").getAsString());
            int paths = opened.has("counts") ? opened.getAsJsonObject("counts").get("resources").getAsInt()
                    : opened.get("total").getAsInt();
            Assert.assertEquals(container.get("resources").getAsInt(), paths, container.toString());
        }
    }

    @Test
    public void aGroupedLevelOverTheCeilingPagesWithoutRepeatingOrSkipping() {
        String command = GITHUB + " repos";
        JsonObject first = answer("ballerinax__github", command);
        Assert.assertEquals(first.get("page").getAsInt(), 1, first.toString());
        Assert.assertEquals(first.get("next").getAsString(), command + " --page 2");
        JsonObject second = answer("ballerinax__github", first.get("next").getAsString());
        Assert.assertFalse(second.has("next"), second.toString());

        List<String> entries = new ArrayList<>(strings(first, "groups"));
        entries.addAll(strings(second, "groups"));
        int resources = first.getAsJsonObject("counts").get("resources").getAsInt();
        Assert.assertEquals(entries.size() + resources, first.get("total").getAsInt());
        Assert.assertEquals(entries.stream().distinct().count(), (long) entries.size(), "a group repeated");
        Assert.assertEquals(second.getAsJsonObject("counts"), first.getAsJsonObject("counts"));

        Run text = run("ballerinax__github", first.get("next").getAsString() + " --output text");
        Assert.assertTrue(text.out().contains("\n1 resource path here, 64 path groups\n"), text.out());
    }
}
