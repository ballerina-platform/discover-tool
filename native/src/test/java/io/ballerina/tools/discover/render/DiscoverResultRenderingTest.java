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
}
