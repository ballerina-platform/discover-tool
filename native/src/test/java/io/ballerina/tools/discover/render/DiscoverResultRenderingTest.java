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
}
