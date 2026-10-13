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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ballerina.tools.discover.cli.Cli;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

/**
 * {@code bal discover search <keywords...>}: Central's package and symbol searches, every row followable.
 *
 * @since 0.1.0
 */
public class CentralSearchTest {

    private static final String PACKAGES = "{\"packages\":[{\"organization\":\"ballerinax\",\"name\":\"kafka\","
            + "\"version\":\"4.6.6\",\"summary\":\"[Apache Kafka](https://kafka.apache.org) is a distributed event "
            + "streaming platform. It is used widely.\"},{\"organization\":\"acme\",\"name\":\"tool\","
            + "\"version\":\"0.1.0\",\"summary\":\"![Build](https://x/y.svg) > **Acme** tool\"}],\"count\":12,"
            + "\"offset\":0,\"limit\":10}";

    private static final String SYMBOLS = "{\"symbols\":["
            + symbol("ballerinax", "kafka", "kafka", "client-method", "Producer", "send", false, true) + ","
            + symbol("ballerinax", "github", "github", "client-method", "Client",
                    "get repos/[string owner]/[string repo]/'import", true, false) + ","
            + symbol("ballerinax", "kafka", "kafka", "client-method", "Producer", "init", false, false) + ","
            + symbol("ballerina", "graphql", "graphql.subgraph", "record", "", "FederatedEntity", false, false) + ","
            + symbol("ballerina", "io", "io", "function", "", "println", false, false) + ","
            + symbol("ballerina", "http", "http", "service-type-method", "InterceptableService",
                    "createInterceptors", false, false) + ","
            + symbol("ballerina", "websub", "websub", "listener-method", "Listener", "attachWithConfig", false,
                    false)
            + "],\"count\":60,\"offset\":0,\"limit\":25}";

    private static String symbol(String org, String name, String module, String type, String parent, String symbol,
            boolean resource, boolean remote) {
        JsonObject json = new JsonObject();
        json.addProperty("organization", org);
        json.addProperty("name", name);
        json.addProperty("moduleName", module);
        json.addProperty("version", "1.0.0");
        json.addProperty("symbolType", type);
        json.addProperty("symbolParent", parent);
        json.addProperty("symbolName", symbol);
        json.addProperty("isResource", resource);
        json.addProperty("isRemote", remote);
        return json.toString();
    }

    private static FakeTransport central() {
        return FakeTransport.routing(url -> url.contains("search-symbols")
                ? FakeTransport.ok(SYMBOLS)
                : FakeTransport.ok(PACKAGES));
    }

    private static JsonObject search(FakeTransport transport, String... argv) {
        CliTest.Capture capture = new CliTest.Capture();
        Assert.assertEquals(Cli.run(List.of(argv), capture.streams(), CliTest.options(transport)), 0,
                capture.stderr());
        return JsonParser.parseString(capture.stdout()).getAsJsonObject();
    }

    @Test
    public void everyRowCarriesTheCommandThatOpensIt() {
        JsonObject answer = search(central(), "search", "kafka", "send");
        JsonObject packages = answer.getAsJsonObject("packages");
        Assert.assertEquals(packages.get("shown").getAsInt(), 2);
        Assert.assertEquals(packages.get("total").getAsInt(), 12);
        JsonObject kafka = packages.getAsJsonArray("entries").get(0).getAsJsonObject();
        Assert.assertEquals(kafka.get("command").getAsString(), "bal discover ballerinax/kafka:4.6.6");
        Assert.assertEquals(kafka.get("summary").getAsString(), "Apache Kafka is a distributed event streaming "
                + "platform.");
        Assert.assertEquals(packages.getAsJsonArray("entries").get(1).getAsJsonObject().get("summary").getAsString(),
                "Acme tool");

        JsonArray symbols = answer.getAsJsonObject("symbols").getAsJsonArray("entries");
        List<String> commands = symbols.asList().stream()
                .map(row -> row.getAsJsonObject().get("command").getAsString())
                .toList();
        Assert.assertEquals(commands, List.of(
                "bal discover ballerinax/kafka:1.0.0 client Producer send",
                "bal discover ballerinax/github:1.0.0 client Client repos/:owner/:repo/import get",
                "bal discover ballerinax/kafka:1.0.0 client Producer init",
                "bal discover ballerina/graphql:1.0.0 --module subgraph type FederatedEntity",
                "bal discover ballerina/io:1.0.0 funcs println",
                "bal discover ballerina/http:1.0.0 service InterceptableService createInterceptors",
                "bal discover ballerina/websub:1.0.0 type Listener"));
        Assert.assertEquals(symbols.asList().stream()
                        .map(row -> row.getAsJsonObject().get("kind").getAsString()).toList(),
                List.of("remote method", "resource method", "constructor", "record", "function", "method",
                        "listener method"));
        Assert.assertEquals(symbols.get(3).getAsJsonObject().get("module").getAsString(), "subgraph");
        Assert.assertEquals(answer.get("pages").getAsInt(), 3);
        Assert.assertEquals(answer.get("next").getAsString(), "bal discover search kafka send --page 2");
    }

    @Test
    public void aPageTurnsBothRankingsAtOnce() {
        FakeTransport transport = central();
        JsonObject answer = search(transport, "search", "kafka send", "--page", "2");
        Assert.assertTrue(transport.urls().stream().anyMatch(url -> url.contains("registry/packages/?q=kafka+send"
                + "&limit=10&offset=10")), transport.urls().toString());
        Assert.assertTrue(transport.urls().stream().anyMatch(url -> url.contains("registry/search-symbols?q=kafka+send"
                + "&limit=25&offset=25")), transport.urls().toString());
        Assert.assertEquals(answer.get("page").getAsInt(), 2);
        Assert.assertEquals(answer.get("next").getAsString(), "bal discover search \"kafka send\" --page 3");

        CliTest.Capture beyond = new CliTest.Capture();
        Assert.assertEquals(Cli.run(List.of("search", "kafka", "--page", "9"), beyond.streams(),
                CliTest.options(central())), 1);
        Assert.assertEquals(beyond.field("command"), "bal discover search kafka --page 3");
    }

    @Test
    public void aSearchTakesKeywordsAndNothingThatNamesOnePackage() {
        for (List<String> argv : List.of(List.of("search"), List.of("search", "kafka", "--module", "x"),
                List.of("search", "kafka", "--filter", "send"))) {
            CliTest.Capture capture = new CliTest.Capture();
            Assert.assertEquals(Cli.run(argv, capture.streams(), CliTest.never()), 2, argv.toString());
            Assert.assertEquals(capture.field("kind"), "validation", capture.stderr());
        }
    }

    @Test
    public void centralFailingIsAnUpstreamFailure() {
        CliTest.Capture capture = new CliTest.Capture();
        Assert.assertEquals(Cli.run(List.of("search", "kafka"), capture.streams(),
                CliTest.options(FakeTransport.always(FakeTransport.status(400)))), 1);
        Assert.assertEquals(capture.field("kind"), "upstream", capture.stderr());
        CliTest.Capture shapeless = new CliTest.Capture();
        Assert.assertEquals(Cli.run(List.of("search", "kafka"), shapeless.streams(),
                CliTest.options(FakeTransport.always(FakeTransport.ok("{\"items\":[]}")))), 1);
        Assert.assertEquals(shapeless.field("kind"), "upstream", shapeless.stderr());
    }

    @Test
    public void aSearchAtATerminalPrintsEachRowAsTheArgumentsToFollowIt() {
        CliTest.Capture capture = new CliTest.Capture();
        Assert.assertEquals(Cli.run(List.of("search", "kafka"), capture.streams(), CliTest.options(central()),
                null, true), 0, capture.stderr());
        String text = capture.stdout();
        Assert.assertTrue(text.startsWith("search · kafka\n12 packages, 60 symbols\n"), text);
        Assert.assertTrue(text.contains("\n  remote method    ballerinax/kafka:1.0.0 client Producer send\n"), text);
        Assert.assertTrue(text.contains("\nNext: bal discover <row>\nNext: bal discover search kafka --page 2\n"),
                text);
    }
}
