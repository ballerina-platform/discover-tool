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
import io.ballerina.tools.discover.Texts;
import org.testng.Assert;
import org.testng.annotations.Test;

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

    @Test
    public void aBucketListRendersOneBucketPerLineInText() {
        DiscoverResult result = new DiscoverResult.BucketList(List.of("client", "service", "funcs", "readme"));
        Assert.assertEquals(TextRenderer.render(result), lines(
                "4 buckets",
                "",
                "  client",
                "  service",
                "  funcs",
                "  readme"));
    }

    @Test
    public void aBucketListAtATerminalNamesThePackageAndTheCommandThatOpensABucket() {
        DiscoverResult result = new DiscoverResult.BucketList(List.of("client", "readme"));
        TextRenderer.Context where = new TextRenderer.Context("ballerina/graphql", "subgraph", List.of(), null);
        Assert.assertEquals(TextRenderer.render(result, where), lines(
                "ballerina/graphql · module subgraph",
                "2 buckets",
                "",
                "  client",
                "  readme",
                "",
                "Next: bal discover ballerina/graphql --module subgraph <bucket>"));
    }

    @Test
    public void aBucketListRendersAsAJsonArrayField() {
        DiscoverResult result = new DiscoverResult.BucketList(List.of("client", "readme"));
        String json = JsonRenderer.render(result);
        Assert.assertEquals(
                JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("buckets").toString(),
                "[\"client\",\"readme\"]");
    }

    @Test
    public void anEmptyBucketListRendersAsNoneInTextAndAnEmptyArrayInJson() {
        DiscoverResult result = new DiscoverResult.BucketList(List.of());
        Assert.assertEquals(TextRenderer.render(result), "No buckets.");
        Assert.assertEquals(JsonRenderer.render(result), "{\"buckets\":[]}");
    }

    @Test
    public void submodulesAppearAfterTheBucketListInTextAndAsTheirOwnArrayInJson() {
        DiscoverResult result = new DiscoverResult.BucketList(
                List.of("client", "service", "funcs", "readme"),
                List.of(
                        new DiscoverResult.BucketList.Submodule("dataloader",
                                "load data from a source with batching and caching",
                                "bal discover ballerina/graphql --module dataloader"),
                        new DiscoverResult.BucketList.Submodule("subgraph",
                                "create subgraphs for a federated GraphQL service",
                                "bal discover ballerina/graphql --module subgraph")),
                null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "4 buckets",
                "",
                "  client",
                "  service",
                "  funcs",
                "  readme",
                "",
                "Submodules",
                "  dataloader  bal discover ballerina/graphql --module dataloader  "
                        + "load data from a source with batching and caching",
                "  subgraph    bal discover ballerina/graphql --module subgraph    "
                        + "create subgraphs for a federated GraphQL service"));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        JsonArray submodules = json.getAsJsonArray("submodules");
        Assert.assertEquals(submodules.size(), 2);
        Assert.assertEquals(submodules.get(0).getAsJsonObject().get("name").getAsString(), "dataloader");
        Assert.assertEquals(submodules.get(0).getAsJsonObject().get("call").getAsString(),
                "bal discover ballerina/graphql --module dataloader");
    }

    @Test
    public void noSubmodulesOmitsTheFieldEntirelyInJson() {
        DiscoverResult result = new DiscoverResult.BucketList(List.of("client", "readme"));
        Assert.assertFalse(JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject().has("submodules"),
                "a package with no submodules carries no submodules key at all");
    }

    @Test
    public void anUnverifiedVersionsWarningAppearsInBothRenderings() {
        DiscoverResult result = new DiscoverResult.BucketList(
                List.of("client"), "the registry was unreachable, so this version came off disk unchecked");
        Assert.assertTrue(TextRenderer.render(result).endsWith(
                "\n\nWarning: the registry was unreachable, so this version came off disk unchecked"));
        String json = JsonRenderer.render(result);
        Assert.assertEquals(
                JsonParser.parseString(json).getAsJsonObject().get("warning").getAsString(),
                "the registry was unreachable, so this version came off disk unchecked");
    }

    // -----------------------------------------------------------------------
    // ContainerRoster
    // -----------------------------------------------------------------------

    @Test
    public void aContainerRosterListsNamesInTextAndCallsInJson() {
        DiscoverResult result = new DiscoverResult.ContainerRoster(
                List.of(
                        new DiscoverResult.ContainerRoster.Container(
                                "Caller", 0, 3, 0, "bal discover ballerinax/kafka client Caller"),
                        new DiscoverResult.ContainerRoster.Container(
                                "Consumer", 0, 24, 0, "bal discover ballerinax/kafka client Consumer")),
                2, null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "2 containers",
                "",
                "  Caller     3 remote",
                "  Consumer  24 remote",
                "",
                "Next: bal discover ballerinax/kafka client <name>"));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        JsonArray containers = json.getAsJsonArray("containers");
        Assert.assertEquals(containers.size(), 2);
        Assert.assertEquals(containers.get(0).getAsJsonObject().get("remote").getAsInt(), 3);
        Assert.assertFalse(containers.get(0).getAsJsonObject().has("resources"),
                "a zero count is omitted rather than printed as 0");
        Assert.assertEquals(containers.get(0).getAsJsonObject().get("call").getAsString(),
                "bal discover ballerinax/kafka client Caller");
        Assert.assertEquals(json.get("shown").getAsInt(), 2);
        Assert.assertEquals(json.get("total").getAsInt(), 2);
        Assert.assertFalse(json.has("next"), "nothing was cut off");
    }

    @Test
    public void aContainerRosterNamesTheBoundListenerInBothRenderingsWhenOneIsCarried() {
        DiscoverResult result = new DiscoverResult.ContainerRoster(
                List.of(
                        new DiscoverResult.ContainerRoster.Container(
                                "Service", 0, 1, 0, "kafka:Listener",
                                "bal discover ballerinax/kafka service Service"),
                        new DiscoverResult.ContainerRoster.Container(
                                "Plain", 0, 1, 0, "bal discover ballerinax/kafka service Plain")),
                2, null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "2 containers",
                "",
                "kafka:Listener",
                "  Service  1 remote",
                "",
                "No listener",
                "  Plain  1 remote",
                "",
                "Next: bal discover ballerinax/kafka service <name>"));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        JsonArray containers = json.getAsJsonArray("containers");
        Assert.assertEquals(containers.get(0).getAsJsonObject().get("listener").getAsString(), "kafka:Listener");
        Assert.assertFalse(containers.get(1).getAsJsonObject().has("listener"),
                "no listener to report is omitted rather than printed null");
    }

    @Test
    public void aTruncatedContainerRosterNamesWhatWasCutInBothRenderings() {
        DiscoverResult result = new DiscoverResult.ContainerRoster(
                List.of(new DiscoverResult.ContainerRoster.Container(
                        "A", 0, 1, 0, "bal discover pkg class A")),
                91, "bal discover pkg class --page 2");
        Assert.assertEquals(TextRenderer.render(result), lines(
                "91 containers",
                "",
                "  A  1 remote",
                "",
                "... 90 more, narrow further",
                "Next: bal discover pkg class <name>",
                "Next: bal discover pkg class --page 2"));
        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.get("next").getAsString(), "bal discover pkg class --page 2");
    }

    // -----------------------------------------------------------------------
    // PathGroups — pinned against ballerinax/github's real fixture numbers
    // -----------------------------------------------------------------------

    @Test
    public void pathGroupsRenderTheRfcsGroupsShape() {
        DiscoverResult result = new DiscoverResult.PathGroups(
                List.of(
                        new DiscoverResult.PathGroups.Group(
                                "repos", 421, "bal discover ballerinax/github client repos"),
                        new DiscoverResult.PathGroups.Group(
                                "orgs", 124, "bal discover ballerinax/github client orgs")),
                36, "bal discover ballerinax/github client --filter <keyword>");
        Assert.assertEquals(TextRenderer.render(result), lines(
                "36 path groups",
                "",
                "Groups (operations under each)",
                "  repos  421",
                "  orgs   124",
                "",
                "... 34 more, narrow further",
                "Next: bal discover ballerinax/github client <group>",
                "Next: bal discover ballerinax/github client --filter <keyword>"));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        JsonArray groups = json.getAsJsonArray("groups");
        Assert.assertEquals(groups.get(0).getAsJsonObject().get("name").getAsString(), "repos");
        Assert.assertEquals(groups.get(0).getAsJsonObject().get("count").getAsInt(), 421);
        Assert.assertEquals(json.get("shown").getAsInt(), 2);
        Assert.assertEquals(json.get("total").getAsInt(), 36);
        Assert.assertFalse(json.has("resources"), "nothing ends at the top level here");
    }

    @Test
    public void operationsEndingAtTheGroupedPrefixAreListedBesideTheGroupsNotAsAGroupOfTheirOwn() {
        String client = "bal discover ballerinax/github client Client";
        DiscoverResult result = new DiscoverResult.PathGroups("Client",
                List.of(resource("repos/:owner/:repo", client + " repos/:owner/:repo", "get", "patch", "delete")),
                List.of(new DiscoverResult.PathGroups.Group("repos/actions", 38, client + " repos/actions get")),
                2, null, null, null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "Client",
                "1 resource path here, 1 path group",
                "",
                "Here",
                "  repos/:owner/:repo  get, patch, delete",
                "",
                "Groups (operations under each)",
                "  repos/actions  38",
                "",
                "Next: bal discover ballerinax/github client Client <path> <accessor>",
                "Next: bal discover ballerinax/github client Client <group> get"));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.get("container").getAsString(), "Client");
        Assert.assertEquals(json.getAsJsonArray("resources").get(0).getAsJsonObject().get("path").getAsString(),
                "repos/:owner/:repo");
        Assert.assertEquals(json.get("shown").getAsInt(), 2);
        Assert.assertEquals(json.get("total").getAsInt(), 2);
    }

    // -----------------------------------------------------------------------
    // ResourceList
    // -----------------------------------------------------------------------

    @Test
    public void everyResourceRowCarriesOneCommandPerAccessorAndNeverAFlatCall() {
        String client = "bal discover ballerinax/github client";
        DiscoverResult result = new DiscoverResult.ResourceList(
                List.of(
                        resource("gists", client + " gists", "get", "post"),
                        resource("gists/:gistId", client + " gists/:gistId", "get", "delete"),
                        resource("gists/starred", client + " gists/starred", "get")),
                3, 3, null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "3 resource paths",
                "",
                "  gists          get, post",
                "  gists/:gistId  get, delete",
                "  gists/starred  get",
                "",
                "Next: bal discover ballerinax/github client <path> <accessor>"));

        JsonArray resources = JsonParser.parseString(JsonRenderer.render(result))
                .getAsJsonObject().getAsJsonArray("resources");
        Assert.assertEquals(resources.get(1).toString(),
                "{\"path\":\"gists/:gistId\",\"accessors\":[\"get\",\"delete\"],\"calls\":{"
                        + "\"get\":\"" + client + " gists/:gistId get\","
                        + "\"delete\":\"" + client + " gists/:gistId delete\"}}");
        Assert.assertEquals(resources.get(2).getAsJsonObject().getAsJsonObject("calls").toString(),
                "{\"get\":\"" + client + " gists/starred get\"}");
        for (int i = 0; i < resources.size(); i++) {
            Assert.assertFalse(resources.get(i).getAsJsonObject().has("call"), resources.get(i).toString());
        }
    }

    @Test
    public void aRowWhoseCommandTheSharedShapeCannotSpellCarriesItsOwn() {
        String client = "bal discover ballerinax/github client Client";
        DiscoverResult result = new DiscoverResult.ResourceList(
                List.of(resource("gists/starred", client + " gists/starred", "get"),
                        resource("gists/'public", client + " \"gists/'public\"", "get"),
                        resource("gists/'private", client + " \"gists/'private\"", "get", "post")),
                3, 3, null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "3 resource paths",
                "",
                "  gists/starred   get",
                "  gists/'public   get        " + client + " \"gists/'public\" get",
                "  gists/'private  get, post  " + client + " \"gists/'private\" <accessor>",
                "",
                "Next: " + client + " <path> <accessor>"));
    }

    // -----------------------------------------------------------------------
    // MethodList — pinned against ballerinax/twilio's real fixture number (199)
    // -----------------------------------------------------------------------

    @Test
    public void aPaginatedMethodListNamesTheNextPageInBothRenderings() {
        DiscoverResult result = new DiscoverResult.MethodList("Client",
                methods(TWILIO, "createAccount", "createAddress"), 40, 199, new DiscoverResult.Paging(1, 5, 159),
                TWILIO + " --page 2", null, null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "Client",
                "199 methods",
                "",
                "  createAccount",
                "  createAddress",
                "",
                "... 159 more (page 1 of 5)",
                "Next: " + TWILIO + " <name>",
                "Next: " + TWILIO + " --page 2"));
        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.getAsJsonArray("methods").get(0).toString(),
                "{\"name\":\"createAccount\",\"call\":\"" + TWILIO + " createAccount\"}");
        Assert.assertEquals(json.get("container").getAsString(), "Client");
        Assert.assertEquals(json.get("shown").getAsInt(), 40);
        Assert.assertEquals(json.get("total").getAsInt(), 199);
        Assert.assertEquals(json.get("page").getAsInt(), 1);
        Assert.assertEquals(json.get("pages").getAsInt(), 5);
    }

    @Test
    public void aLaterPageCountsOnlyWhatComesAfterIt() {
        DiscoverResult result = new DiscoverResult.MethodList("Client",
                methods(TWILIO, "deleteConferenceRecording"), 40, 199, new DiscoverResult.Paging(2, 5, 119),
                "bal discover ballerinax/twilio client Client --page 3", null, null);
        Assert.assertTrue(TextRenderer.render(result).endsWith(lines(
                "... 119 more (page 2 of 5)",
                "Next: " + TWILIO + " <name>",
                "Next: " + TWILIO + " --page 3")));

        DiscoverResult last = new DiscoverResult.MethodList("Client",
                methods(TWILIO, "updateUsageTrigger"), 39, 199, new DiscoverResult.Paging(5, 5, 0), null, null, null);
        Assert.assertTrue(TextRenderer.render(last).endsWith("Last page (page 5 of 5)\nNext: " + TWILIO + " <name>"));
        Assert.assertFalse(JsonParser.parseString(JsonRenderer.render(last)).getAsJsonObject().has("next"));
    }

    // -----------------------------------------------------------------------
    // Readme — no entry ceiling, unlike every listing above
    // -----------------------------------------------------------------------

    @Test
    public void theWholeReadmeIsPrintedVerbatimWithNoWrappingInText() {
        DiscoverResult result = new DiscoverResult.Readme("## Overview\n\nbody", 3, null);
        Assert.assertEquals(TextRenderer.render(result), "## Overview\n\nbody");

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.get("readme").getAsString(), "## Overview\n\nbody");
        Assert.assertEquals(json.get("lines").getAsInt(), 3);
        Assert.assertFalse(json.has("chunk"), "the whole readme names no chunk");
    }

    @Test
    public void aPackageWithNoReadmeIsNoneInTextAndAnEmptyStringInJson() {
        DiscoverResult result = new DiscoverResult.Readme("", 0, null);
        Assert.assertEquals(TextRenderer.render(result), "No readme in this module.");

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.get("readme").getAsString(), "");
        Assert.assertEquals(json.get("lines").getAsInt(), 0);
    }

    @Test
    public void oneChunkCarriesItsPositionInJsonAndAHeaderLineInText() {
        DiscoverResult result = new DiscoverResult.Readme("## Quickstart\n\ncode", 3, 2, 5, "Quickstart", null);
        Assert.assertEquals(TextRenderer.render(result), "Chunk 2 of 5: Quickstart\n\n## Quickstart\n\ncode");

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.get("chunk").getAsInt(), 2);
        Assert.assertEquals(json.get("of").getAsInt(), 5);
        Assert.assertEquals(json.get("title").getAsString(), "Quickstart");
    }

    @Test
    public void severalMatchingChunksAreARosterInBothRenderings() {
        DiscoverResult result = new DiscoverResult.ReadmeChunks(
                List.of(
                        new DiscoverResult.ReadmeChunks.Chunk(1, "Overview", 10, "bal discover pkg readme 1"),
                        new DiscoverResult.ReadmeChunks.Chunk(3, "Quickstart", 25, "bal discover pkg readme 3")),
                2, null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "2 matching chunks",
                "",
                "  1  Overview    10 lines",
                "  3  Quickstart  25 lines",
                "",
                "Next: bal discover pkg readme <n>"));

        JsonArray chunks = JsonParser.parseString(JsonRenderer.render(result))
                .getAsJsonObject().getAsJsonArray("chunks");
        Assert.assertEquals(chunks.get(1).getAsJsonObject().get("number").getAsInt(), 3);
        Assert.assertEquals(chunks.get(1).getAsJsonObject().get("call").getAsString(), "bal discover pkg readme 3");
    }

    @Test
    public void noChunkMatchingTheFilterIsNoneInTextAndAnEmptyArrayInJson() {
        DiscoverResult result = new DiscoverResult.ReadmeChunks(List.of(), 0, null);
        Assert.assertEquals(TextRenderer.render(result), "No matching chunks.");

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.getAsJsonArray("chunks").size(), 0);
        Assert.assertEquals(json.get("total").getAsInt(), 0);
    }

    // -----------------------------------------------------------------------
    // Signature
    // -----------------------------------------------------------------------

    private static DiscoverResult.Signature getPublicGists(List<DiscoverResult.Signature.Type> types,
            List<String> omitted, String note) {
        return new DiscoverResult.Signature("Client", "resource", null, "get", "gists/'public", "->",
                "resource function get gists/'public(map<string|string[]> headers = {}, *GistsListPublicQueries "
                        + "queries) returns BaseGist[]|error;",
                List.of(new DiscoverResult.Signature.Parameter("headers", "map<string|string[]>", "{}", null, ""),
                        new DiscoverResult.Signature.Parameter(
                                "queries", "GistsListPublicQueries", null, "inclusion", "Queries to send")),
                "BaseGist[]|error", false, types, omitted, List.of(), null, note);
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
                List.of("GistFile"), "relocated to gists/'public");
        Assert.assertEquals(TextRenderer.render(result), lines(
                "Client",
                "",
                "resource function get gists/'public(map<string|string[]> headers = {}, *GistsListPublicQueries "
                        + "queries) returns BaseGist[]|error;",
                "",
                "Types it names (1)",
                "  public type BaseGist record {|",
                "  |};",
                "",
                "1 more past the closure budget, not shown",
                "  GistFile",
                "",
                "Note: relocated to gists/'public"));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        JsonObject type = json.getAsJsonArray("types").get(0).getAsJsonObject();
        Assert.assertEquals(type.get("name").getAsString(), "BaseGist");
        Assert.assertEquals(type.get("declaration").getAsString(), "public type BaseGist record {|\n|};");
        Assert.assertEquals(json.getAsJsonArray("omitted").get(0).getAsString(), "GistFile");
        Assert.assertEquals(json.get("note").getAsString(), "relocated to gists/'public");
    }

    // -----------------------------------------------------------------------
    // MixedListing
    // -----------------------------------------------------------------------

    @Test
    public void aMixedListingIsSectionedByCallFormInTextAndThreeArraysInJson() {
        DiscoverResult result = new DiscoverResult.MixedListing("Client",
                List.of(resource(":...path", HTTP + " :...path", "get", "post")),
                methods(HTTP, "execute", "get"), methods(HTTP, "getCookieStore"), 4, 4, null, null, List.of(), null,
                null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "Client",
                "1 resource path, 2 remote methods, 1 normal method",
                "",
                "Resources (->)",
                "  :...path  get, post",
                "",
                "Remote (->)",
                "  execute",
                "  get",
                "",
                "Normal (.)",
                "  getCookieStore",
                "",
                "Next: " + HTTP + " <path> <accessor>",
                "Next: " + HTTP + " <name>"));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.get("container").getAsString(), "Client");
        Assert.assertEquals(json.getAsJsonArray("resources").get(0).getAsJsonObject().get("path").getAsString(),
                ":...path");
        Assert.assertEquals(json.getAsJsonArray("remote").get(1).toString(),
                "{\"name\":\"get\",\"call\":\"" + HTTP + " get\"}");
        Assert.assertEquals(json.getAsJsonArray("normal").get(0).getAsJsonObject().get("name").getAsString(),
                "getCookieStore");
        Assert.assertEquals(json.get("shown").getAsInt(), 4);
        Assert.assertEquals(json.get("total").getAsInt(), 4);
        Assert.assertFalse(json.has("documented"));
    }

    @Test
    public void aPagedMixedListingNamesItsPageAndDocumentationOnlyMatches() {
        String next = "bal discover pkg client Client --page 2";
        DiscoverResult result = new DiscoverResult.MixedListing(null,
                List.of(), methods("bal discover pkg client Client", "execute"),
                methods("bal discover pkg client Client", "'close"), 2, 4, new DiscoverResult.Paging(1, 2, 2), next,
                List.of("forward"), null, null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "4 entries",
                "",
                "Remote (->)",
                "  execute",
                "",
                "Normal (.)",
                "  'close  bal discover pkg client Client \"'close\"",
                "",
                "Matched by documentation only",
                "  forward",
                "",
                "... 2 more (page 1 of 2)",
                "Next: bal discover pkg client Client <name>",
                "Next: " + next));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.get("next").getAsString(), next);
        Assert.assertEquals(json.get("page").getAsInt(), 1);
        Assert.assertEquals(json.get("pages").getAsInt(), 2);
        Assert.assertEquals(json.getAsJsonArray("documented").get(0).getAsString(), "forward");
        Assert.assertFalse(json.has("resources"), "a section with nothing on this page is omitted");
        Assert.assertFalse(json.has("container"));
    }

    // -----------------------------------------------------------------------
    // NoMatch
    // -----------------------------------------------------------------------

    @Test
    public void aMissNamesTheClosestNamesAndWhatIsThereInBothRenderings() {
        DiscoverResult result = new DiscoverResult.NoMatch("sendd", "Producer", List.of("send"), List.of(),
                new DiscoverResult.MethodList(methods("bal discover ballerinax/kafka client Producer", "close", "send"),
                        2, 2, null),
                "bal discover ballerinax/kafka client Producer", List.of(), null, null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "Producer",
                "Nothing on Producer matches 'sendd'.",
                "",
                "Did you mean",
                "  send",
                "",
                "Available",
                "  2 functions",
                "",
                "    close",
                "    send",
                "",
                "  Next: bal discover ballerinax/kafka client Producer <name>"));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.get("requested").getAsString(), "sendd");
        Assert.assertEquals(json.get("container").getAsString(), "Producer");
        Assert.assertEquals(json.getAsJsonArray("candidates").toString(), "[\"send\"]");
        Assert.assertEquals(json.getAsJsonObject("available").getAsJsonArray("methods").size(), 2);
        Assert.assertEquals(json.get("next").getAsString(), "bal discover ballerinax/kafka client Producer");
        Assert.assertFalse(json.has("paths"), "no ambiguous segment, so no paths key");
    }

    @Test
    public void anAmbiguousSegmentListsEveryPathWithTheCommandThatOpensIt() {
        DiscoverResult result = new DiscoverResult.NoMatch("repos/owner/repo/secrets", "Client", List.of(),
                List.of(new DiscoverResult.NoMatch.Alternative("repos/:owner/:repo/actions/secrets",
                                "bal discover ballerinax/github client Client repos/:owner/:repo/actions/secrets"),
                        new DiscoverResult.NoMatch.Alternative("repos/:owner/:repo/dependabot/secrets",
                                "bal discover ballerinax/github client Client repos/:owner/:repo/dependabot/secrets")),
                null, "bal discover ballerinax/github client Client", List.of(), null, null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "Client",
                "Nothing on Client matches 'repos/owner/repo/secrets'.",
                "",
                "2 paths carry that segment; pick one",
                "  repos/:owner/:repo/actions/secrets     "
                        + "bal discover ballerinax/github client Client repos/:owner/:repo/actions/secrets",
                "  repos/:owner/:repo/dependabot/secrets  "
                        + "bal discover ballerinax/github client Client repos/:owner/:repo/dependabot/secrets",
                "",
                "Next: bal discover ballerinax/github client Client"));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        JsonArray paths = json.getAsJsonArray("paths");
        Assert.assertEquals(paths.get(1).getAsJsonObject().get("call").getAsString(),
                "bal discover ballerinax/github client Client repos/:owner/:repo/dependabot/secrets");
        Assert.assertFalse(json.has("available"));
    }

    // -----------------------------------------------------------------------
    // Owners and EmptyBucket
    // -----------------------------------------------------------------------

    @Test
    public void aMemberOnSeveralContainersListsTheOwnersInBothRenderings() {
        DiscoverResult result = new DiscoverResult.Owners("commit",
                List.of(new DiscoverResult.Owners.Owner(
                                "Caller", 1, "bal discover ballerinax/kafka client Caller commit"),
                        new DiscoverResult.Owners.Owner(
                                "Consumer", 3, "bal discover ballerinax/kafka client Consumer commit")),
                2, null, null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "'commit' is declared on 2 containers; pick one.",
                "",
                "  Caller    1 match    bal discover ballerinax/kafka client Caller commit",
                "  Consumer  3 matches  bal discover ballerinax/kafka client Consumer commit"));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.get("requested").getAsString(), "commit");
        JsonObject consumer = json.getAsJsonArray("owners").get(1).getAsJsonObject();
        Assert.assertEquals(consumer.get("matches").getAsInt(), 3);
        Assert.assertEquals(consumer.get("call").getAsString(), "bal discover ballerinax/kafka client Consumer commit");
        Assert.assertEquals(json.get("shown").getAsInt(), 2);
        Assert.assertEquals(json.get("total").getAsInt(), 2);
    }

    @Test
    public void anEmptyBucketNamesTheBucketsThatAreNot() {
        DiscoverResult result = new DiscoverResult.EmptyBucket("funcs",
                List.of(new DiscoverResult.EmptyBucket.Elsewhere("client", 3, "bal discover ballerinax/kafka client"),
                        new DiscoverResult.EmptyBucket.Elsewhere("class", 4, "bal discover ballerinax/kafka class")),
                null);
        Assert.assertEquals(TextRenderer.render(result), lines(
                "This package declares nothing in funcs.",
                "",
                "Elsewhere",
                "  client  3  bal discover ballerinax/kafka client",
                "  class   4  bal discover ballerinax/kafka class"));

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.get("bucket").getAsString(), "funcs");
        Assert.assertEquals(json.get("total").getAsInt(), 0);
        JsonObject client = json.getAsJsonArray("elsewhere").get(0).getAsJsonObject();
        Assert.assertEquals(client.get("bucket").getAsString(), "client");
        Assert.assertEquals(client.get("count").getAsInt(), 3);
        Assert.assertEquals(client.get("call").getAsString(), "bal discover ballerinax/kafka client");
    }

    private static final String TWILIO = "bal discover ballerinax/twilio client Client";

    private static final String HTTP = "bal discover ballerina/http client Client";

    /** Each name with its command, {@code prefix} and the name as one shell word. */
    private static List<DiscoverResult.Method> methods(String prefix, String... names) {
        return Arrays.stream(names)
                .map(name -> new DiscoverResult.Method(name, prefix + " " + Texts.shellWord(name)))
                .toList();
    }

    /** A resource row whose command for each accessor is {@code prefix} and the accessor. */
    private static DiscoverResult.ResourceList.Resource resource(String path, String prefix, String... accessors) {
        Map<String, String> calls = new LinkedHashMap<>();
        for (String accessor : accessors) {
            calls.put(accessor, prefix + " " + accessor);
        }
        return new DiscoverResult.ResourceList.Resource(path, List.of(accessors), calls);
    }

    private static String lines(String... lines) {
        return String.join("\n", lines);
    }
}
