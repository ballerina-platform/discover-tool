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

package io.ballerina.tools.discover.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ballerina.tools.discover.Command;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two renderers driven directly against {@link DiscoverResult}, independent of the CLI layer — one source,
 * and nothing about the data itself changes between the two.
 *
 * @since 0.1.0
 */
public class DiscoverResultRenderingTest {

    private static String kafka(String noun, String... names) {
        return Command.of(noun, names).in("ballerinax/kafka", null).text();
    }

    private static DiscoverResult.Overview kafkaOverview(List<DiscoverResult.Overview.Submodule> submodules,
            String warning) {
        return new DiscoverResult.Overview(
                List.of(new DiscoverResult.Overview.Section("client", List.of(
                                new DiscoverResult.Overview.Row("Consumer", 0, 24, 0, kafka("client", "Consumer")),
                                new DiscoverResult.Overview.Row("Producer", 0, 5, 0, kafka("client", "Producer"))),
                                3, kafka("client")),
                        new DiscoverResult.Overview.Section("function", List.of(
                                new DiscoverResult.Overview.Row("parse", 0, 0, 0, kafka("function", "parse"))),
                                1, kafka("function"))),
                new DiscoverResult.Overview.TypeCounts(
                        new LinkedHashMap<>(Map.of(DiscoverResult.TypeRoster.Kind.RECORDS, 14)), kafka("type")),
                new DiscoverResult.Overview.ReadmeSummary(11, kafka("readme")), submodules, warning, null);
    }

    @Test
    public void anOverviewListsEachBucketsFirstRowsThenTheTypeAndReadmeLines() {
        TextRenderer.Context where = new TextRenderer.Context("ballerinax/kafka", null, List.of(), null, null, "4.6.6");
        Assert.assertEquals(TextRenderer.render(kafkaOverview(List.of(), null), where), lines(
                "ballerinax/kafka:4.6.6",
                "",
                "Clients (3)",
                "  Consumer  24 remote methods",
                "  Producer   5 remote methods",
                "  ... 1 more: bal discover client -p ballerinax/kafka",
                "Functions (1)",
                "  parse",
                "Types   bal discover type -p ballerinax/kafka    14 records",
                "Readme  bal discover readme -p ballerinax/kafka  11 sections",
                "",
                "Next: bal discover <noun> <name> -p ballerinax/kafka",
                "Next: bal discover search <keyword> -p ballerinax/kafka"));
    }

    @Test
    public void anOverviewGivesEveryRowItsCommandInJson() {
        JsonObject json = JsonParser.parseString(JsonRenderer.render(kafkaOverview(List.of(), null)))
                .getAsJsonObject();
        Assert.assertEquals(json.getAsJsonObject("clients").toString(),
                "{\"entries\":[{\"name\":\"Consumer\",\"remote\":24,\"command\":\"" + kafka("client",
                        "Consumer") + "\"},"
                        + "{\"name\":\"Producer\",\"remote\":5,\"command\":\"" + kafka("client", "Producer") + "\"}],"
                        + "\"shown\":2,\"total\":3,\"command\":\"" + kafka("client") + "\"}");
        Assert.assertEquals(json.getAsJsonObject("functions").getAsJsonArray("entries").get(0).toString(),
                "{\"name\":\"parse\",\"command\":\"" + kafka("function", "parse") + "\"}");
        Assert.assertEquals(json.getAsJsonObject("types").toString(),
                "{\"counts\":{\"records\":14},\"command\":\"" + kafka("type") + "\"}");
        Assert.assertEquals(json.getAsJsonObject("readme").toString(),
                "{\"sections\":11,\"command\":\"" + kafka("readme") + "\"}");
        Assert.assertFalse(json.has("submodules"), "a package with no submodules carries no submodules key at all");
    }

    @Test
    public void anEmptyOverviewSaysSoInTextAndIsAnEmptyObjectInJson() {
        DiscoverResult result = new DiscoverResult.Overview(List.of(), null, null, List.of(), null, null);
        Assert.assertEquals(TextRenderer.render(result), "Nothing to list.");
        Assert.assertEquals(JsonRenderer.render(result), "{}");
    }

    @Test
    public void submodulesFollowTheOverviewInTextAndAreTheirOwnArrayInJson() {
        DiscoverResult result = new DiscoverResult.Overview(List.of(), null, null, List.of(
                new DiscoverResult.Overview.Submodule("dataloader", "load data from a source with batching and caching",
                        "bal discover package ballerina/graphql --module dataloader"),
                new DiscoverResult.Overview.Submodule("subgraph", "create subgraphs for a federated GraphQL service",
                        "bal discover package ballerina/graphql --module subgraph")), null, null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "Nothing to list.",
                "",
                "Submodules",
                "  dataloader  bal discover package ballerina/graphql --module dataloader  "
                        + "load data from a source with batching and caching",
                "  subgraph    bal discover package ballerina/graphql --module subgraph    "
                        + "create subgraphs for a federated GraphQL service"));

        JsonArray submodules = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject()
                .getAsJsonArray("submodules");
        Assert.assertEquals(submodules.size(), 2);
        Assert.assertEquals(submodules.get(0).getAsJsonObject().get("command").getAsString(),
                "bal discover package ballerina/graphql --module dataloader");
    }

    @Test
    public void anUnverifiedVersionsWarningAppearsInBothRenderings() {
        DiscoverResult result = kafkaOverview(List.of(),
                "the registry was unreachable, so this version came off disk unchecked");
        Assert.assertTrue(TextRenderer.render(result).contains(
                "\n\nWarning: the registry was unreachable, so this version came off disk unchecked\n"));
        String json = JsonRenderer.render(result);
        Assert.assertEquals(
                JsonParser.parseString(json).getAsJsonObject().get("warning").getAsString(),
                "the registry was unreachable, so this version came off disk unchecked");
    }

    @Test
    public void aContainerRosterListsNamesInTextAndCommandsInJson() {
        DiscoverResult result = new DiscoverResult.ContainerRoster(
                List.of(
                        new DiscoverResult.ContainerRoster.Container(
                                "Caller", 0, 3, 0, null, null, "bal discover client Caller -p ballerinax/kafka"),
                        new DiscoverResult.ContainerRoster.Container(
                                "Consumer", 0, 24, 0, null, null, "bal discover client Consumer -p ballerinax/kafka")),
                2, null, null, null, List.of(), 0);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "2 containers",
                "",
                "  Caller     3 remote methods",
                "  Consumer  24 remote methods",
                "",
                "Next: bal discover client <name> -p ballerinax/kafka"));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        JsonArray containers = json.getAsJsonArray("containers");
        Assert.assertEquals(containers.size(), 2);
        Assert.assertEquals(containers.get(0).getAsJsonObject().get("remote").getAsInt(), 3);
        Assert.assertFalse(containers.get(0).getAsJsonObject().has("resources"),
                "a zero count is omitted rather than printed as 0");
        Assert.assertEquals(containers.get(0).getAsJsonObject().get("command").getAsString(),
                "bal discover client Caller -p ballerinax/kafka");
        Assert.assertEquals(json.get("shown").getAsInt(), 2);
        Assert.assertEquals(json.get("total").getAsInt(), 2);
        Assert.assertFalse(json.has("next"), "nothing was cut off");
    }

    @Test
    public void aContainerRosterNamesTheBoundListenerInBothRenderingsWhenOneIsCarried() {
        DiscoverResult result = new DiscoverResult.ContainerRoster(
                List.of(
                        new DiscoverResult.ContainerRoster.Container(
                                "Service", 0, 1, 0, "kafka:Listener", null,
                                "bal discover service Service -p ballerinax/kafka"),
                        new DiscoverResult.ContainerRoster.Container(
                                "Plain", 0, 1, 0, null, null, "bal discover service Plain -p ballerinax/kafka")),
                2, null, null, null, List.of(), 0);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "2 containers",
                "",
                "kafka:Listener",
                "  Service  1 remote method",
                "",
                "No listener",
                "  Plain  1 remote method",
                "",
                "Next: bal discover service <name> -p ballerinax/kafka"));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        JsonArray containers = json.getAsJsonArray("containers");
        Assert.assertEquals(containers.get(0).getAsJsonObject().get("listener").getAsString(), "kafka:Listener");
        Assert.assertFalse(containers.get(1).getAsJsonObject().has("listener"),
                "no listener to report is omitted rather than printed null");
    }

    @Test
    public void aServiceTypeNoListenerAcceptsIsListedApartAndPagedWithTheRest() {
        DiscoverResult result = new DiscoverResult.ContainerRoster(
                List.of(new DiscoverResult.ContainerRoster.Container(
                        "Service", 0, 0, 0, "http:Listener", null, "bal discover service Service -p ballerina/http")),
                1, new DiscoverResult.Paging(1, 2, 2), "bal discover service -p ballerina/http --page 2", null,
                List.of(new DiscoverResult.ContainerRoster.NotAttachable(
                        "RequestInterceptor", "bal discover service RequestInterceptor -p ballerina/http")),
                3);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "1 container",
                "",
                "http:Listener",
                "  Service",
                "",
                "Not attachable to a listener (1 of 3)",
                "  RequestInterceptor",
                "",
                "... 2 more (page 1 of 2)",
                "Next: bal discover service <name> -p ballerina/http",
                "Next: bal discover service -p ballerina/http --page 2"));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        JsonObject first = json.getAsJsonArray("notAttachable").get(0).getAsJsonObject();
        Assert.assertEquals(first.get("name").getAsString(), "RequestInterceptor");
        Assert.assertEquals(first.get("command").getAsString(),
                "bal discover service RequestInterceptor -p ballerina/http");
        Assert.assertEquals(json.get("notAttachableTotal").getAsInt(), 3);
        Assert.assertEquals(json.get("next").getAsString(), "bal discover service -p ballerina/http --page 2");
    }

    @Test
    public void anUnconfirmedPairingCarriesItsReasonInBothRenderings() {
        DiscoverResult result = new DiscoverResult.ContainerRoster(
                List.of(
                        new DiscoverResult.ContainerRoster.Container(
                                "Service", 0, 0, 1, "http:Listener", null,
                                "bal discover service Service -p ballerina/http"),
                        new DiscoverResult.ContainerRoster.Container(
                                "InterceptableService", 0, 0, 1, "http:Listener", "package source unavailable",
                                "bal discover service InterceptableService -p ballerina/http")),
                2, null, null, null, List.of(), 0);
        Assert.assertTrue(TextRenderer.render(result).contains(
                "http:Listener — unverified (package source unavailable)\n  InterceptableService"));

        JsonArray containers = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject()
                .getAsJsonArray("containers");
        JsonObject confirmed = containers.get(0).getAsJsonObject();
        Assert.assertFalse(confirmed.has("confirmed"), confirmed.toString());
        Assert.assertFalse(confirmed.has("unconfirmedReason"), confirmed.toString());
        JsonObject unconfirmed = containers.get(1).getAsJsonObject();
        Assert.assertFalse(unconfirmed.get("confirmed").getAsBoolean());
        Assert.assertEquals(unconfirmed.get("unconfirmedReason").getAsString(), "package source unavailable");
    }

    @Test
    public void aServiceRosterPageBeforeTheNotAttachableTypesSaysTheyAreComing() {
        DiscoverResult result = new DiscoverResult.ContainerRoster(
                List.of(new DiscoverResult.ContainerRoster.Container(
                        "Service", 0, 0, 0, "http:Listener", null, "bal discover service Service -p ballerina/http")),
                6, new DiscoverResult.Paging(1, 2, 5), "bal discover service -p ballerina/http --page 2", null,
                List.of(), 5);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "6 containers",
                "",
                "http:Listener",
                "  Service",
                "",
                "Not attachable to a listener: 5 more, on later pages",
                "",
                "... 5 more (page 1 of 2)",
                "Next: bal discover service <name> -p ballerina/http",
                "Next: bal discover service -p ballerina/http --page 2"));
    }

    @Test
    public void aPagedContainerRosterNamesWhatWasCutInBothRenderings() {
        DiscoverResult result = new DiscoverResult.ContainerRoster(
                List.of(new DiscoverResult.ContainerRoster.Container(
                        "A", 0, 1, 0, null, null, "bal discover class A -p pkg")),
                91, new DiscoverResult.Paging(1, 3, 51), "bal discover class -p pkg --page 2", null, List.of(), 0);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "91 containers",
                "",
                "  A  1 remote method",
                "",
                "... 51 more (page 1 of 3)",
                "Next: bal discover class <name> -p pkg",
                "Next: bal discover class -p pkg --page 2"));
        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.get("next").getAsString(), "bal discover class -p pkg --page 2");
        Assert.assertEquals(json.get("page").getAsInt(), 1);
        Assert.assertEquals(json.get("pages").getAsInt(), 3);
    }

    @Test
    public void pathGroupsRenderTheRfcsGroupsShape() {
        DiscoverResult result = new DiscoverResult.PathGroups(null, List.of(),
                List.of(
                        new DiscoverResult.PathGroups.Group(
                                "repos", 421, "bal discover client repos -p ballerinax/github"),
                        new DiscoverResult.PathGroups.Group(
                                "orgs", 124, "bal discover client orgs -p ballerinax/github")),
                new DiscoverResult.PathGroups.Counts(0, 42), 42, new DiscoverResult.Paging(1, 2, 40),
                "bal discover client -p ballerinax/github --page 2", null, null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "42 path groups",
                "",
                "Path groups (resource methods in each)",
                "  repos  421",
                "  orgs   124",
                "",
                "... 40 more (page 1 of 2)",
                "Next: bal discover client <group> -p ballerinax/github",
                "Next: bal discover client -p ballerinax/github --page 2"));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        JsonArray groups = json.getAsJsonArray("groups");
        Assert.assertEquals(groups.get(0).getAsJsonObject().get("name").getAsString(), "repos");
        Assert.assertEquals(groups.get(0).getAsJsonObject().get("count").getAsInt(), 421);
        Assert.assertEquals(json.get("shown").getAsInt(), 2);
        Assert.assertEquals(json.get("total").getAsInt(), 42);
        Assert.assertEquals(json.get("page").getAsInt(), 1);
        Assert.assertEquals(json.get("pages").getAsInt(), 2);
        Assert.assertEquals(json.getAsJsonObject("counts").toString(), "{\"groups\":42}");
        Assert.assertFalse(json.has("resources"), "nothing ends at the top level here");
    }

    @Test
    public void operationsEndingAtTheGroupedPrefixAreListedBesideTheGroupsNotAsAGroupOfTheirOwn() {
        DiscoverResult result = new DiscoverResult.PathGroups("Client",
                List.of(resource("ballerinax/github", "repos/:owner/:repo", "get", "patch", "delete")),
                List.of(new DiscoverResult.PathGroups.Group("repos/actions", 38,
                        "bal discover resource repos/actions get -p ballerinax/github --client Client")),
                new DiscoverResult.PathGroups.Counts(1, 1), 2, null, null, null, null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "Client",
                "1 resource path here, 1 path group",
                "",
                "Paths at this level",
                "  repos/:owner/:repo  get, patch, delete",
                "",
                "Path groups (resource methods in each)",
                "  repos/actions  38",
                "",
                "Next: bal discover resource <path> <accessor> -p ballerinax/github --client Client",
                "Next: bal discover resource <group> get -p ballerinax/github --client Client"));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.get("container").getAsString(), "Client");
        Assert.assertEquals(json.getAsJsonArray("resources").get(0).getAsJsonObject().get("path").getAsString(),
                "repos/:owner/:repo");
        Assert.assertEquals(json.get("shown").getAsInt(), 2);
        Assert.assertEquals(json.get("total").getAsInt(), 2);
    }

    /** A page the paths ending here fill on their own still counts the groups that come after it. */
    @Test
    public void aPageHoldingOnlyThePathsEndingHereStillCountsTheGroups() {
        List<DiscoverResult.ResourceList.Resource> here = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            here.add(resource("pkg", "p" + i, "get"));
        }
        String client = "bal discover resource -p pkg --client Client";
        DiscoverResult result = new DiscoverResult.PathGroups("Client", here, List.of(),
                new DiscoverResult.PathGroups.Counts(45, 3), 48, new DiscoverResult.Paging(1, 2, 8),
                client + " --page 2", null, null);
        String text = TextRenderer.render(result);
        Assert.assertTrue(text.startsWith("Client\n45 resource paths here, 3 path groups\n"), text);
        Assert.assertTrue(text.endsWith("\n... 8 more (page 1 of 2)\nNext: bal discover resource <path> <accessor> "
                + "-p pkg --client Client\nNext: " + client + " --page 2"), text);
        Assert.assertEquals(JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject()
                .getAsJsonObject("counts").toString(), "{\"resources\":45,\"groups\":3}");
    }

    @Test
    public void everyResourceRowCarriesOneCommandPerAccessorAndNeverAFlatCall() {
        String client = "bal discover resource %s -p ballerinax/github --client Client";
        DiscoverResult result = new DiscoverResult.ResourceList(null,
                List.of(
                        resource("ballerinax/github", "gists", "get", "post"),
                        resource("ballerinax/github", "gists/:gistId", "get", "delete"),
                        resource("ballerinax/github", "gists/starred", "get")),
                3, 3, null, null, DiscoverResult.Documented.NONE, null, null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "3 resource paths",
                "",
                "  gists          get, post",
                "  gists/:gistId  get, delete",
                "  gists/starred  get",
                "",
                "Next: " + client.formatted("<path> <accessor>")));

        JsonArray resources = JsonParser.parseString(JsonRenderer.render(result))
                .getAsJsonObject().getAsJsonArray("resources");
        Assert.assertEquals(resources.get(1).toString(),
                "{\"path\":\"gists/:gistId\",\"accessors\":[\"get\",\"delete\"],\"commands\":{"
                        + "\"get\":\"" + client.formatted("gists/:gistId get") + "\","
                        + "\"delete\":\"" + client.formatted("gists/:gistId delete") + "\"}}");
        Assert.assertEquals(resources.get(2).getAsJsonObject().getAsJsonObject("commands").toString(),
                "{\"get\":\"" + client.formatted("gists/starred get") + "\"}");
        for (int i = 0; i < resources.size(); i++) {
            Assert.assertFalse(resources.get(i).getAsJsonObject().has("command"), resources.get(i).toString());
        }
    }

    @Test
    public void aRowWhoseCommandTheSharedShapeCannotSpellCarriesItsOwn() {
        String client = "bal discover resource %s -p ballerinax/github --client Client";
        DiscoverResult result = new DiscoverResult.ResourceList(null,
                List.of(resource("ballerinax/github", "gists/starred", "get"),
                        resource("ballerinax/github", "gists/'public", "get"),
                        resource("ballerinax/github", "gists/'private", "get", "post")),
                3, 3, null, null, DiscoverResult.Documented.NONE, null, null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "3 resource paths",
                "",
                "  gists/starred   get",
                "  gists/'public   get        " + client.formatted("\"gists/'public\" get"),
                "  gists/'private  get, post  " + client.formatted("\"gists/'private\" <accessor>"),
                "",
                "Next: " + client.formatted("<path> <accessor>")));
    }

    @Test
    public void aPaginatedMethodListNamesTheNextPageInBothRenderings() {
        DiscoverResult result = new DiscoverResult.MethodList("Client",
                methods(TWILIO, "createAccount", "createAddress"), true, 40, 199, new DiscoverResult.Paging(1, 5, 159),
                TWILIO_CLIENT + " --page 2", DiscoverResult.Documented.NONE, null, null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "Client",
                "199 remote methods",
                "",
                "  createAccount",
                "  createAddress",
                "",
                "... 159 more (page 1 of 5)",
                "Next: bal discover method <name> -p " + TWILIO + " --client Client",
                "Next: " + TWILIO_CLIENT + " --page 2"));
        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.getAsJsonArray("methods").get(0).toString(),
                "{\"name\":\"createAccount\",\"command\":\"bal discover method createAccount -p " + TWILIO
                        + " --client Client\"}");
        Assert.assertEquals(json.get("container").getAsString(), "Client");
        Assert.assertEquals(json.get("shown").getAsInt(), 40);
        Assert.assertEquals(json.get("total").getAsInt(), 199);
        Assert.assertEquals(json.get("page").getAsInt(), 1);
        Assert.assertEquals(json.get("pages").getAsInt(), 5);
    }

    @Test
    public void aLaterPageCountsOnlyWhatComesAfterIt() {
        DiscoverResult result = new DiscoverResult.MethodList("Client",
                methods(TWILIO, "deleteConferenceRecording"), true, 40, 199, new DiscoverResult.Paging(2, 5, 119),
                "bal discover client Client -p ballerinax/twilio --page 3", DiscoverResult.Documented.NONE, null, null);
        Assert.assertTrue(TextRenderer.render(result).endsWith(lines(
                "... 119 more (page 2 of 5)",
                "Next: bal discover method <name> -p " + TWILIO + " --client Client",
                "Next: " + TWILIO_CLIENT + " --page 3")));

        DiscoverResult last = new DiscoverResult.MethodList("Client",
                methods(TWILIO, "updateUsageTrigger"), true, 39, 199, new DiscoverResult.Paging(5, 5, 0), null,
                DiscoverResult.Documented.NONE, null, null);
        Assert.assertTrue(TextRenderer.render(last).endsWith("Last page (page 5 of 5)\nNext: bal discover method "
                + "<name> -p " + TWILIO
                + " --client Client"));
        Assert.assertFalse(JsonParser.parseString(JsonRenderer.render(last)).getAsJsonObject().has("next"));
    }

    @Test
    public void theWholeReadmeIsPrintedVerbatimWithNoWrappingInText() {
        DiscoverResult result = new DiscoverResult.Readme("## Overview\n\nbody", 3, null);
        Assert.assertEquals(TextRenderer.render(result), "## Overview\n\nbody");

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.get("readme").getAsString(), "## Overview\n\nbody");
        Assert.assertEquals(json.get("lines").getAsInt(), 3);
        Assert.assertFalse(json.has("section"), "the whole readme names no section");
    }

    @Test
    public void oneSectionCarriesItsPositionInJsonAndAHeaderLineInText() {
        DiscoverResult result = new DiscoverResult.Readme("## Quickstart\n\ncode", 3, 2, 5, "Quickstart", null);
        Assert.assertEquals(TextRenderer.render(result), "Section 2 of 5: Quickstart\n\n## Quickstart\n\ncode");

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.get("section").getAsInt(), 2);
        Assert.assertEquals(json.get("of").getAsInt(), 5);
        Assert.assertEquals(json.get("title").getAsString(), "Quickstart");
    }

    @Test
    public void severalMatchingSectionsAreARosterInBothRenderings() {
        DiscoverResult result = new DiscoverResult.ReadmeSections(
                List.of(
                        new DiscoverResult.ReadmeSections.Section(1, "Overview", 10, "bal discover readme 1 -p pkg"),
                        new DiscoverResult.ReadmeSections.Section(3, "Quickstart", 25, "bal discover readme 3 -p pkg")),
                2, null, null, null, true);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "2 matching sections",
                "",
                "  1  Overview    10 lines",
                "  3  Quickstart  25 lines",
                "",
                "Next: bal discover readme <n> -p pkg"));

        JsonArray sections = JsonParser.parseString(JsonRenderer.render(result))
                .getAsJsonObject().getAsJsonArray("sections");
        Assert.assertEquals(sections.get(1).getAsJsonObject().get("number").getAsInt(), 3);
        Assert.assertEquals(sections.get(1).getAsJsonObject().get("command").getAsString(),
                "bal discover readme 3 -p pkg");
    }

    @Test
    public void noSectionMatchingTheFilterIsNoneInTextAndAnEmptyArrayInJson() {
        DiscoverResult result = new DiscoverResult.ReadmeSections(List.of(), 0, null, null, null, true);
        Assert.assertEquals(TextRenderer.render(result), "No matching sections.");

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.getAsJsonArray("sections").size(), 0);
        Assert.assertEquals(json.get("total").getAsInt(), 0);
    }

    private static DiscoverResult.Signature getPublicGists(List<DiscoverResult.Signature.Type> types,
            List<DiscoverResult.Method> omitted, String note) {
        return new DiscoverResult.Signature("Client", "resource", null, "get", "gists/'public", "->",
                "resource function get gists/'public(map<string|string[]> headers = {}, *GistsListPublicQueries "
                        + "queries) returns BaseGist[]|error;",
                List.of(new DiscoverResult.Signature.Parameter("headers", "map<string|string[]>", "{}", null, ""),
                        new DiscoverResult.Signature.Parameter(
                                "queries", "GistsListPublicQueries", null, "inclusion", "Queries to send")),
                "BaseGist[]|error", false, types, omitted, List.of(), null, List.of(),
                DiscoverResult.Documented.NONE, null, null, null, note);
    }

    @Test
    public void aSignaturesDocumentationOnlyMatchesPageWithTheCommandThatReachesTheRest() {
        String command = "bal discover method echo -p pkg --client Client --filter the";
        DiscoverResult result = new DiscoverResult.Signature("Client", "remote", "echo", null, null, "->",
                "remote isolated function echo(string echoStr) returns string|error;", List.of(), "string|error",
                false, List.of(), List.of(), List.of(), null, List.of(),
                new DiscoverResult.Documented(
                        List.copyOf(methods("pkg", "ping", "auth")), 82),
                new DiscoverResult.Paging(2, 3, 2),
                command + " --page 3", null, null);
        String text = TextRenderer.render(result);
        Assert.assertTrue(text.contains("\nMentioned only in their documentation (2 of 82)\n  ping\n  auth\n"), text);
        Assert.assertTrue(text.endsWith("\n... 2 more that mention it only in their documentation (page 2 of 3)\n"
                + "Next: bal discover method <name> -p pkg --client Client\nNext: " + command + " --page 3"), text);

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.get("documentedTotal").getAsInt(), 82);
        Assert.assertEquals(json.get("page").getAsInt(), 2);
        Assert.assertEquals(json.get("pages").getAsInt(), 3);
        Assert.assertEquals(json.get("next").getAsString(), command + " --page 3");
    }

    @Test
    public void aLeafListsEveryTypeItLeavesOutBeforeTheDocumentationOnlyPage() {
        String command = "bal discover method echo -p pkg --client Client --filter the";
        DiscoverResult result = new DiscoverResult.Signature("Client", "remote", "echo", null, null, "->",
                "remote isolated function echo(string echoStr) returns string|error;", List.of(), "string|error",
                false, List.of(), List.of(new DiscoverResult.Method("Gist", "bal discover type Gist -p pkg")),
                List.of(), null, List.of(),
                new DiscoverResult.Documented(List.copyOf(methods("pkg", "ping")), 41),
                new DiscoverResult.Paging(1, 2, 1),
                command + " --page 2", null, null);
        String text = TextRenderer.render(result);
        Assert.assertTrue(text.endsWith(lines(
                "... 1 more that mention it only in their documentation (page 1 of 2)",
                "Next: bal discover method <name> -p pkg --client Client",
                "Next: " + command + " --page 2")), text);
        Assert.assertTrue(text.contains("Other types it uses, not printed (1)\n"
                + "  Gist  bal discover type Gist -p pkg\n"), text);
        Assert.assertFalse(text.contains("narrow with"), text);
    }

    @Test
    public void theHeaderEchoesTheTrailAndNamesTheContainerABareBucketOpened() {
        DiscoverResult result = new DiscoverResult.MethodList("Client",
                List.of(new DiscoverResult.Method("createCall",
                        "bal discover method createCall -p ballerinax/twilio --client Client")),
                false, 1, 199, new DiscoverResult.Paging(1, 5, 159),
                "bal discover client Client -p ballerinax/twilio --page 2",
                DiscoverResult.Documented.NONE, null, null);
        String text = TextRenderer.render(result, new TextRenderer.Context("ballerinax/twilio", null,
                List.of("client"), null, null, null));
        Assert.assertTrue(text.startsWith("ballerinax/twilio · client · Client\n"), text);
        Assert.assertTrue(text.contains("\nNext: bal discover client Client -p ballerinax/twilio --filter <keyword>"),
                text);
        Assert.assertTrue(TextRenderer.render(result, new TextRenderer.Context("ballerinax/twilio", null,
                List.of("client", "Client"), null, null, null)).startsWith("ballerinax/twilio · client · Client\n"));
    }

    @Test
    public void aSignatureIsTheBareDeclarationInTextAndStructuredInJson() {
        DiscoverResult result = getPublicGists(List.of(), List.of(), null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "Client",
                "",
                "resource function get gists/'public(map<string|string[]> headers = {}, *GistsListPublicQueries "
                        + "queries) returns BaseGist[]|error;"));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.get("container").getAsString(), "Client");
        Assert.assertEquals(json.get("kind").getAsString(), "resource");
        Assert.assertFalse(json.has("name"), "a resource is addressed by path and accessor, not a name");
        Assert.assertEquals(json.get("accessor").getAsString(), "get");
        Assert.assertEquals(json.get("path").getAsString(), "gists/'public");
        Assert.assertEquals(json.get("form").getAsString(), "->");
        Assert.assertEquals(json.get("returns").getAsString(), "BaseGist[]|error");
        JsonArray params = json.getAsJsonArray("params");
        Assert.assertEquals(params.get(0).getAsJsonObject().get("default").getAsString(), "{}");
        Assert.assertFalse(params.get(0).getAsJsonObject().has("kind"), "a plain parameter carries no kind");
        Assert.assertFalse(params.get(0).getAsJsonObject().has("description"), "an empty description is omitted");
        Assert.assertEquals(params.get(1).getAsJsonObject().get("kind").getAsString(), "inclusion");
        Assert.assertEquals(json.getAsJsonArray("types").size(), 0);
        Assert.assertFalse(json.has("omitted"));
        Assert.assertFalse(json.has("deprecated"), "only a deprecated callable says so");
    }

    @Test
    public void aSignaturesTypesFollowItAndWhatTheBudgetLeftOutIsNamed() {
        DiscoverResult result = getPublicGists(
                List.of(new DiscoverResult.Signature.Type("BaseGist", "public type BaseGist record {|\n|};")),
                List.of(new DiscoverResult.Method("GistFile", "bal discover type GistFile -p pkg")),
                "relocated to gists/'public");
        Assert.assertEquals(TextRenderer.render(result), lines(
                "Client",
                "",
                "resource function get gists/'public(map<string|string[]> headers = {}, *GistsListPublicQueries "
                        + "queries) returns BaseGist[]|error;",
                "",
                "Types it uses (1)",
                "  public type BaseGist record {|",
                "  |};",
                "",
                "Other types it uses, not printed (1)",
                "  GistFile  bal discover type GistFile -p pkg",
                "",
                "Note: relocated to gists/'public"));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        JsonObject type = json.getAsJsonArray("types").get(0).getAsJsonObject();
        Assert.assertEquals(type.get("name").getAsString(), "BaseGist");
        Assert.assertEquals(type.get("declaration").getAsString(), "public type BaseGist record {|\n|};");
        JsonObject omitted = json.getAsJsonArray("omitted").get(0).getAsJsonObject();
        Assert.assertEquals(omitted.get("name").getAsString(), "GistFile");
        Assert.assertEquals(omitted.get("command").getAsString(), "bal discover type GistFile -p pkg");
        Assert.assertEquals(json.get("note").getAsString(), "relocated to gists/'public");
    }

    @Test
    public void aMixedListingIsSectionedByCallFormInTextAndThreeArraysInJson() {
        DiscoverResult result = new DiscoverResult.MixedListing("Client",
                List.of(resource(HTTP, ":...path", "get", "post")),
                methods(HTTP, "execute", "get"), methods(HTTP, "getCookieStore"),
                new DiscoverResult.MixedListing.Counts(1, 2, 1), 4, 4, null, null, DiscoverResult.Documented.NONE, null,
                null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "Client",
                "1 resource method, 2 remote methods, 1 method",
                "",
                "Resource methods (->)",
                "  :...path  get, post",
                "",
                "Remote methods (->)",
                "  execute",
                "  get",
                "",
                "Methods (.)",
                "  getCookieStore",
                "",
                "Next: bal discover resource <path> <accessor> -p " + HTTP + " --client Client",
                "Next: bal discover method <name> -p " + HTTP + " --client Client"));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.get("container").getAsString(), "Client");
        Assert.assertEquals(json.getAsJsonArray("resources").get(0).getAsJsonObject().get("path").getAsString(),
                ":...path");
        Assert.assertEquals(json.getAsJsonArray("remote").get(1).toString(),
                "{\"name\":\"get\",\"command\":\"bal discover method get -p " + HTTP + " --client Client\"}");
        Assert.assertEquals(json.getAsJsonArray("normal").get(0).getAsJsonObject().get("name").getAsString(),
                "getCookieStore");
        Assert.assertEquals(json.get("shown").getAsInt(), 4);
        Assert.assertEquals(json.get("total").getAsInt(), 4);
        Assert.assertFalse(json.has("documented"));
    }

    @Test
    public void aPagedMixedListingNamesItsPageAndDocumentationOnlyMatches() {
        String next = "bal discover client Client -p pkg --page 2";
        DiscoverResult result = new DiscoverResult.MixedListing(null,
                List.of(), methods("pkg", "execute"),
                methods("pkg", "'close"), new DiscoverResult.MixedListing.Counts(0, 2, 2),
                2, 4, new DiscoverResult.Paging(1, 2, 2), next,
                new DiscoverResult.Documented(List.copyOf(methods("pkg", "forward")), 3),
                null, null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "2 remote methods, 2 methods",
                "",
                "Remote methods (->)",
                "  execute",
                "",
                "Methods (.)",
                "  'close  bal discover method \"'close\" -p pkg --client Client",
                "",
                "Mentioned only in their documentation (1 of 3)",
                "  forward",
                "",
                "... 2 more (page 1 of 2)",
                "Next: bal discover method <name> -p pkg --client Client",
                "Next: " + next));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.get("next").getAsString(), next);
        Assert.assertEquals(json.get("page").getAsInt(), 1);
        Assert.assertEquals(json.get("pages").getAsInt(), 2);
        Assert.assertEquals(json.get("remaining").getAsInt(), 2);
        Assert.assertEquals(json.getAsJsonArray("documented").get(0).toString(),
                "{\"name\":\"forward\",\"command\":\"bal discover method forward -p pkg --client Client\"}");
        Assert.assertEquals(json.get("documentedTotal").getAsInt(), 3);
        Assert.assertFalse(json.has("resources"), "a section with nothing on this page is omitted");
        Assert.assertEquals(json.getAsJsonObject("counts").toString(), "{\"remote\":2,\"normal\":2}",
                "the whole listing's split, a form with none omitted");
        Assert.assertFalse(json.has("container"));
    }

    @Test
    public void anEmptyBucketNamesTheBucketsThatAreNot() {
        DiscoverResult result = new DiscoverResult.EmptyBucket("function",
                List.of(new DiscoverResult.EmptyBucket.Elsewhere("client", 3,
                        "bal discover client -p ballerinax/kafka"),
                        new DiscoverResult.EmptyBucket.Elsewhere("class", 4, "bal discover class -p ballerinax/kafka")),
                null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "This package has no functions.",
                "",
                "Other buckets",
                "  client  3  bal discover client -p ballerinax/kafka",
                "  class   4  bal discover class -p ballerinax/kafka"));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.get("bucket").getAsString(), "function");
        Assert.assertEquals(json.get("total").getAsInt(), 0);
        JsonObject client = json.getAsJsonArray("elsewhere").get(0).getAsJsonObject();
        Assert.assertEquals(client.get("bucket").getAsString(), "client");
        Assert.assertEquals(client.get("count").getAsInt(), 3);
        Assert.assertEquals(client.get("command").getAsString(), "bal discover client -p ballerinax/kafka");
    }

    private static final String TWILIO = "ballerinax/twilio";

    private static final String TWILIO_CLIENT = "bal discover client Client -p ballerinax/twilio";

    private static final String HTTP = "ballerina/http";

    // Methods of `pkg`'s Client, each with the command that opens it.
    private static List<DiscoverResult.Method> methods(String pkg, String... names) {
        return Arrays.stream(names)
                .map(name -> new DiscoverResult.Method(name, Command.of("method", name).in(pkg, null)
                        .ownedBy("client", "Client").text()))
                .toList();
    }

    // A path of `pkg`'s Client, with the command that opens each accessor.
    private static DiscoverResult.ResourceList.Resource resource(String pkg, String path, String... accessors) {
        Map<String, String> commands = new LinkedHashMap<>();
        for (String accessor : accessors) {
            commands.put(accessor, Command.of("resource", path, accessor).in(pkg, null).ownedBy("client", "Client")
                    .text());
        }
        return new DiscoverResult.ResourceList.Resource(path, List.of(accessors), commands);
    }

    private static String lines(String... lines) {
        return String.join("\n", lines);
    }
}
