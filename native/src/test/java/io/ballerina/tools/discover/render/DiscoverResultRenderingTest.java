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
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

/**
 * The two renderers driven directly against {@link DiscoverResult}, independent of the CLI layer — one source,
 * and nothing about the data itself changes between the two.
 *
 * @since 0.1.0
 */
public class DiscoverResultRenderingTest {

    @Test
    public void aBucketListRendersAsAPlainCommaListInText() {
        DiscoverResult result = new DiscoverResult.BucketList(List.of("client", "service", "funcs", "readme"));
        Assert.assertEquals(TextRenderer.render(result), "client, service, funcs, readme");
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
        Assert.assertEquals(TextRenderer.render(result), "none");
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
        Assert.assertEquals(TextRenderer.render(result),
                "client, service, funcs, readme\n\nSubmodules:\n"
                        + "  dataloader — load data from a source with batching and caching\n"
                        + "  subgraph — create subgraphs for a federated GraphQL service");

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
        Assert.assertTrue(TextRenderer.render(result).startsWith("Warning: the registry was unreachable"));
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
        Assert.assertEquals(TextRenderer.render(result), "Caller, Consumer");

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
        Assert.assertEquals(TextRenderer.render(result), "Service (binds to kafka:Listener), Plain");

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
        Assert.assertEquals(TextRenderer.render(result), "A\n... 90 more, narrow further: "
                + "bal discover pkg class --page 2");
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
        Assert.assertEquals(TextRenderer.render(result), "Groups: repos (421), orgs (124)\n"
                + "... 34 more, narrow further: bal discover ballerinax/github client --filter <keyword>");

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        JsonArray groups = json.getAsJsonArray("groups");
        Assert.assertEquals(groups.get(0).getAsJsonObject().get("name").getAsString(), "repos");
        Assert.assertEquals(groups.get(0).getAsJsonObject().get("count").getAsInt(), 421);
        Assert.assertEquals(json.get("shown").getAsInt(), 2);
        Assert.assertEquals(json.get("total").getAsInt(), 36);
    }

    // -----------------------------------------------------------------------
    // ResourceList
    // -----------------------------------------------------------------------

    @Test
    public void resourceListOmitsCallOnlyOnMultiAccessorEntries() {
        DiscoverResult result = new DiscoverResult.ResourceList(
                List.of(
                        new DiscoverResult.ResourceList.Resource("gists", List.of("get", "post"), null),
                        new DiscoverResult.ResourceList.Resource("gists/:gistId", List.of("get", "delete"), null),
                        new DiscoverResult.ResourceList.Resource("gists/starred", List.of("get"),
                                "bal discover ballerinax/github client gists/starred get")),
                3, 3, null);
        Assert.assertEquals(TextRenderer.render(result),
                "gists — get, post\ngists/:gistId — get, delete\ngists/starred — get");

        JsonArray resources = JsonParser.parseString(JsonRenderer.render(result))
                .getAsJsonObject().getAsJsonArray("resources");
        Assert.assertFalse(resources.get(0).getAsJsonObject().has("call"), "gists has two accessors");
        Assert.assertFalse(resources.get(1).getAsJsonObject().has("call"), "gists/:gistId has two accessors");
        Assert.assertTrue(resources.get(2).getAsJsonObject().has("call"), "gists/starred has exactly one");
    }

    // -----------------------------------------------------------------------
    // MethodList — pinned against ballerinax/twilio's real fixture number (199)
    // -----------------------------------------------------------------------

    @Test
    public void aPaginatedMethodListNamesTheNextPageInBothRenderings() {
        DiscoverResult result = new DiscoverResult.MethodList(
                List.of("createAccount", "createAddress"), 40, 199,
                "bal discover ballerinax/twilio client --page 2");
        Assert.assertEquals(TextRenderer.render(result),
                "Methods: createAccount, createAddress\n"
                        + "... 159 more, narrow further: bal discover ballerinax/twilio client --page 2");
        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.get("shown").getAsInt(), 40);
        Assert.assertEquals(json.get("total").getAsInt(), 199);
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
        Assert.assertEquals(TextRenderer.render(result), "none");

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.get("readme").getAsString(), "");
        Assert.assertEquals(json.get("lines").getAsInt(), 0);
    }

    @Test
    public void oneChunkCarriesItsPositionInJsonAndAHeaderLineInText() {
        DiscoverResult result = new DiscoverResult.Readme("## Quickstart\n\ncode", 3, 2, 5, "Quickstart", null);
        Assert.assertEquals(TextRenderer.render(result),
                "Chunk 2 of 5 — Quickstart\n\n## Quickstart\n\ncode");

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
        Assert.assertEquals(TextRenderer.render(result),
                "1. Overview (10 lines)\n3. Quickstart (25 lines)");

        JsonArray chunks = JsonParser.parseString(JsonRenderer.render(result))
                .getAsJsonObject().getAsJsonArray("chunks");
        Assert.assertEquals(chunks.get(1).getAsJsonObject().get("number").getAsInt(), 3);
        Assert.assertEquals(chunks.get(1).getAsJsonObject().get("call").getAsString(), "bal discover pkg readme 3");
    }

    @Test
    public void noChunkMatchingTheFilterIsNoneInTextAndAnEmptyArrayInJson() {
        DiscoverResult result = new DiscoverResult.ReadmeChunks(List.of(), 0, null);
        Assert.assertEquals(TextRenderer.render(result), "none");

        JsonObject json = JsonParser.parseString(JsonRenderer.render(result)).getAsJsonObject();
        Assert.assertEquals(json.getAsJsonArray("chunks").size(), 0);
        Assert.assertEquals(json.get("total").getAsInt(), 0);
    }
}
