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

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        Assert.assertEquals(kafka.get("command").getAsString(), "bal discover package ballerinax/kafka:4.6.6");
        Assert.assertEquals(kafka.get("summary").getAsString(), "Apache Kafka is a distributed event streaming "
                + "platform.");
        Assert.assertEquals(packages.getAsJsonArray("entries").get(1).getAsJsonObject().get("summary").getAsString(),
                "Acme tool");

        JsonArray symbols = answer.getAsJsonObject("symbols").getAsJsonArray("entries");
        List<String> commands = symbols.asList().stream()
                .map(row -> row.getAsJsonObject().get("command").getAsString())
                .toList();
        Assert.assertEquals(commands, List.of(
                "bal discover method send -p ballerinax/kafka:1.0.0 --client Producer",
                "bal discover resource repos/:owner/:repo/import get -p ballerinax/github:1.0.0 --client Client",
                "bal discover method init -p ballerinax/kafka:1.0.0 --client Producer",
                "bal discover type FederatedEntity -p ballerina/graphql:1.0.0 --module subgraph",
                "bal discover function println -p ballerina/io:1.0.0",
                "bal discover method createInterceptors -p ballerina/http:1.0.0 --service InterceptableService",
                "bal discover type Listener -p ballerina/websub:1.0.0"));
        Assert.assertEquals(symbols.asList().stream()
                        .map(row -> row.getAsJsonObject().get("kind").getAsString()).toList(),
                List.of("remote method", "resource method", "constructor", "record", "function", "method",
                        "listener method"));
        Assert.assertEquals(symbols.get(3).getAsJsonObject().get("module").getAsString(), "subgraph");
        Assert.assertEquals(answer.get("pages").getAsInt(), 3);
        Assert.assertEquals(answer.get("next").getAsString(), "bal discover search kafka send --page 2");
    }

    @Test
    public void aResourceRowCarriesItsPathAndAccessorAsTheInPackageSearchDoes() {
        JsonObject row = search(central(), "search", "kafka").getAsJsonObject("symbols").getAsJsonArray("entries")
                .get(1).getAsJsonObject();
        Assert.assertEquals(row.get("container").getAsString(), "Client");
        Assert.assertEquals(row.get("path").getAsString(), "repos/:owner/:repo/import");
        Assert.assertEquals(row.get("accessor").getAsString(), "get");
        Assert.assertFalse(row.has("name"), row.toString());
    }

    // One row of every symbolType Central's search answered for a spread of queries (2026-10-13), as recorded.
    private static final Map<String, String> COMMANDS_BY_KIND = commandsByKind();

    private static Map<String, String> commandsByKind() {
        Map<String, String> commands = new LinkedHashMap<>();
        commands.put("annotation", "type CallerInfo -p sabtharm/http:2.12.3");
        commands.put("any data type", "type Status -p ballerinax/squareup:1.3.2");
        commands.put("array type", "type PromptItemsArray -p ballerinax/openai.text:1.0.6");
        commands.put("class", "class Caller -p ballerinax/trigger.asb:1.2.0");
        commands.put("method", "method getStatus -p ballerina/workflow:1.0.0 --class DurableAgent");
        commands.put("client", "client Caller -p ballerina/smpp:1.0.0");
        commands.put("remote method", "method fetchOutgoingCallerId -p ballerinax/twilio:5.0.2 --client Client");
        commands.put("resource method", "resource status get -p luheerathantheva/newcon:0.1.0 --client Client");
        commands.put("configurable", "package ballerina/etl:0.8.1");
        commands.put("constant", "type TAG_ANCHOR_POINT_ANNOTATION_UNITS -p ballerinax/health.dicom:0.1.0");
        commands.put("decimal type", "type Id -p ballerinax/smartsheet:1.0.2");
        commands.put("enum", "type Status -p ballerina/ldap:1.4.2");
        commands.put("error", "type FunctionCallError -p ballerina/test:2201.10.4");
        commands.put("function", "function callerOf -p commons/service_commons:0.1.0 --module auth");
        commands.put("function type", "type Transformer -p tharmigan/reliable.messaging:0.4.0");
        commands.put("intersection type", "type ExecutionError -p tharmigan/reliable.messaging:0.4.0");
        commands.put("listener", "type Listener -p ballerina/mcp:1.3.0");
        commands.put("listener method", "type Listener -p ballerina/websub:2.15.2");
        commands.put("map type", "type JsonObject -p ballerina/workflow:1.0.0");
        commands.put("object type", "type Status -p sabtharm/http:2.12.3");
        commands.put("object type method", "type SchemaClient -p ballerina/sql:1.19.0");
        commands.put("remote method/object", "method query -p ballerina/sql:1.19.0 --client Client");
        commands.put("record", "type ListOutgoingCallerIdResponse -p ballerinax/twilio:5.0.2");
        commands.put("service type", "service StoreService -p ballerina/messaging:1.0.1");
        commands.put("method/service", "method onGroupClose -p ballerinax/trigger.slack:0.9.0 --service GroupService");
        commands.put("simple name reference type",
                "type InlineArrayItemsSkuIdsSkuIdsOneOf12 -p ballerinax/discord:2.0.1");
        commands.put("string type", "type Status -p ballerinax/gotomeeting:1.5.2");
        commands.put("tuple type", "type DestinationWithProcessors -p tharmigan/reliable.messaging:0.4.0");
        commands.put("type", "type AuthConfig -p ballerinax/client.config:1.0.1");
        commands.put("type descriptor type", "type ValueTypeDesc -p ballerina/protobuf:1.8.0 --module types.any");
        commands.put("union type", "type Status -p ballerinax/microsoft.sharepoint.sites:1.0.2");
        commands.put("variable", "type VARIABLE_LENGTH_VALUE_BYTES -p ballerinax/health.dicom:0.1.0");
        commands.put("xml type", "type XmlType -p ballerina/lang.xml:2201.13.2");
        return commands;
    }

    @Test
    public void everySymbolKindCentralAnswersOpensTheBucketThisToolListsItUnder() throws IOException {
        String recorded = Files.readString(FixtureCorpus.FIXTURES_DIR.resolve("search").resolve(
                "symbols-by-kind.json"));
        JsonObject answer = search(FakeTransport.routing(url -> url.contains("search-symbols")
                ? FakeTransport.ok(recorded)
                : FakeTransport.ok(PACKAGES)), "search", "any");
        List<String> commands = answer.getAsJsonObject("symbols").getAsJsonArray("entries").asList().stream()
                .map(row -> row.getAsJsonObject().get("command").getAsString())
                .sorted()
                .toList();
        Assert.assertEquals(commands, COMMANDS_BY_KIND.values().stream().map(args -> "bal discover " + args)
                .sorted().toList());
    }

    @Test
    public void aRowMissingWhatItsCommandNeedsOrOfAnUnknownKindOpensThePackage() {
        String rows = "{\"symbols\":["
                + symbol("acme", "pkg", "pkg", "client-method", "", "query", false, true) + ","
                + symbol("acme", "pkg", "pkg", "listener-method", "", "attach", false, false) + ","
                + symbol("acme", "pkg", "pkg", "client-method", "Client", "get", true, false) + ","
                + symbol("acme", "pkg", "pkg", "something-new", "", "Thing", false, false) + ","
                + symbol("acme", "pkg", "pkg", "client-method", "Client", "get [string ...path]", true, false)
                + "],\"count\":5}";
        JsonArray entries = search(FakeTransport.routing(url -> url.contains("search-symbols")
                ? FakeTransport.ok(rows)
                : FakeTransport.ok(PACKAGES)), "search", "any").getAsJsonObject("symbols").getAsJsonArray("entries");
        List<String> commands = entries.asList().stream()
                .map(row -> row.getAsJsonObject().get("command").getAsString())
                .toList();
        Assert.assertEquals(commands, List.of("bal discover package acme/pkg:1.0.0",
                "bal discover package acme/pkg:1.0.0",
                "bal discover package acme/pkg:1.0.0", "bal discover package acme/pkg:1.0.0",
                "bal discover resource :...path get -p acme/pkg:1.0.0 --client Client"));
    }

    @Test
    public void aSummaryIsCutAtASentencesEndNotAtAnAbbreviationAndKeepsItsUnderscores() {
        String packages = "{\"packages\":[{\"organization\":\"a\",\"name\":\"b\",\"version\":\"1.0.0\","
                + "\"summary\":\"Calls v2. the API, e.g. the __init hook and **bold** text. Then more.\"}],"
                + "\"count\":1}";
        JsonObject answer = search(FakeTransport.routing(url -> url.contains("search-symbols")
                ? FakeTransport.ok("{\"symbols\":[],\"count\":0}")
                : FakeTransport.ok(packages)), "search", "b");
        Assert.assertEquals(answer.getAsJsonObject("packages").getAsJsonArray("entries").get(0).getAsJsonObject()
                .get("summary").getAsString(), "Calls v2. the API, e.g. the __init hook and bold text.");
    }

    @Test
    public void aPageTurnsBothRankingsAtOnce() {
        FakeTransport transport = central();
        JsonObject answer = search(transport, "search", "kafka send", "--page", "2");
        Assert.assertTrue(transport.urls().stream().anyMatch(url -> url.contains("registry/packages/?q=kafka+send"
                + "&limit=10&offset=10")), transport.urls().toString());
        Assert.assertTrue(transport.urls().stream().anyMatch(url -> url.contains("registry/search-symbols?q=kafka+AND"
                + "+send&limit=25&offset=25")), transport.urls().toString());
        Assert.assertEquals(answer.get("page").getAsInt(), 2);
        Assert.assertEquals(answer.get("next").getAsString(), "bal discover search \"kafka send\" --page 3");

        CliTest.Capture beyond = new CliTest.Capture();
        Assert.assertEquals(Cli.run(List.of("search", "kafka", "--page", "9"), beyond.streams(),
                CliTest.options(central())), Failure.USAGE);
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
                CliTest.options(FakeTransport.always(FakeTransport.status(400)))), Failure.BAD_ANSWER);
        Assert.assertEquals(capture.field("kind"), "upstream", capture.stderr());
        CliTest.Capture shapeless = new CliTest.Capture();
        Assert.assertEquals(Cli.run(List.of("search", "kafka"), shapeless.streams(),
                CliTest.options(FakeTransport.always(FakeTransport.ok("{\"items\":[]}")))), Failure.BAD_ANSWER);
        Assert.assertEquals(shapeless.field("kind"), "upstream", shapeless.stderr());
    }

    @Test
    public void aSearchAtATerminalPrintsEachRowAsTheArgumentsToFollowIt() {
        CliTest.Capture capture = new CliTest.Capture();
        Assert.assertEquals(Cli.run(List.of("search", "kafka"), capture.streams(), CliTest.options(central()),
                null, true), 0, capture.stderr());
        String text = capture.stdout();
        Assert.assertTrue(text.startsWith("search · kafka\n12 packages, 60 symbols\n"), text);
        Assert.assertTrue(text.contains("\n  remote method    method send -p ballerinax/kafka:1.0.0 --client "
                + "Producer\n"), text);
        Assert.assertTrue(text.contains("\nNext: bal discover <row>\nNext: bal discover search kafka --page 2\n"),
                text);
    }
}
