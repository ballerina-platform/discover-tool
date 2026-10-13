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
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.JsonRenderer;
import io.ballerina.tools.discover.render.TextRenderer;
import io.ballerina.tools.discover.symbols.Surface;
import io.ballerina.tools.discover.views.Containers;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

/**
 * What a method's answer carries beyond its signature: how to construct the container it is called on.
 *
 * @since 0.1.0
 */
public class LeafExtrasTest {

    private static DiscoverResult.Signature leaf(String slug, Surface.Scope scope, String... selectors) {
        Result<DiscoverResult> answer = Containers.render(FixtureCorpus.loadedFixture(slug), scope,
                new Containers.Options(List.of(selectors)));
        Assert.assertTrue(answer.isOk(), answer.isOk() ? "" : answer.failure().describe());
        return (DiscoverResult.Signature) answer.value();
    }

    @Test
    public void aMethodSaysHowToConstructItsContainerFromItsInit() {
        DiscoverResult.Signature send = leaf("ballerinax__kafka", Surface.Scope.CLIENT, "Producer", "send");
        Assert.assertEquals(send.create().code(), "kafka:Producer producer = check new (string|string[] "
                + "bootstrapServers, *kafka:ProducerConfiguration config);");
        Assert.assertEquals(send.create().command(), "bal discover ballerinax/kafka client Producer init");
    }

    @Test
    public void aVariableThatWouldBeAKeywordTakesThePrefixAndDefaultsAreKept() {
        DiscoverResult.Signature get = leaf("ballerinax__github", Surface.Scope.CLIENT, "Client", "gists", "get");
        Assert.assertEquals(get.create().code(), "github:Client githubClient = check new (github:ConnectionConfig "
                + "config, string serviceUrl = \"https://api.github.com\");");
    }

    @Test
    public void noLineIsInventedForWhatHasNoConstructorOrIsTheConstructor() {
        Assert.assertNull(leaf("ballerinax__kafka", Surface.Scope.CLIENT, "Producer", "init").create());
        Assert.assertNull(leaf("ballerina__log", Surface.Scope.MODULE, "printInfo").create());
    }

    @Test
    public void theLineRendersAfterTheDeclarationAndAsItsOwnJsonObject() {
        DiscoverResult.Signature send = leaf("ballerinax__kafka", Surface.Scope.CLIENT, "Producer", "send");
        String text = TextRenderer.render(send);
        Assert.assertTrue(text.contains(";\n\nCreate it\n  kafka:Producer producer = check new ("), text);
        JsonObject create = JsonParser.parseString(JsonRenderer.render(send)).getAsJsonObject()
                .getAsJsonObject("create");
        Assert.assertEquals(create.get("command").getAsString(), "bal discover ballerinax/kafka client Producer init");
    }
}
