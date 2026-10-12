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
import io.ballerina.tools.discover.cache.DiskCache;
import io.ballerina.tools.discover.cache.DocsCache;
import io.ballerina.tools.discover.central.Bala;
import io.ballerina.tools.discover.central.CentralClient;
import io.ballerina.tools.discover.central.CentralRepository;
import io.ballerina.tools.discover.central.HttpOptions;
import io.ballerina.tools.discover.central.LocalBalas;
import io.ballerina.tools.discover.cli.Cli;
import io.ballerina.tools.discover.model.ServiceHandlers;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.TextRenderer;
import io.ballerina.tools.discover.source.TriggerMetadata;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * What a service type's package says its services define: read from the bala's trigger metadata, shown the same
 * in both renderings, and absent without a trace for a version that publishes none.
 *
 * @since 0.1.0
 */
public class ServiceHandlersTest {

    private static final String KAFKA = "ballerinax__kafka";
    private static final String BALA_URL = "https://files.example/kafka.bala";

    private static Map<String, ServiceHandlers> recorded(String slug, String prefix) {
        return TriggerMetadata.parse(FixtureCorpus.recordedTriggerMetadata(slug).orElseThrow(), prefix);
    }

    private static byte[] bala(boolean withMetadata) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("modules/kafka/caller.bal"));
            zip.write("public type Caller client object {};".getBytes(StandardCharsets.UTF_8));
            if (withMetadata) {
                zip.putNextEntry(new ZipEntry(Bala.TRIGGER_METADATA));
                zip.write(FixtureCorpus.recordedTriggerMetadata(KAFKA).orElseThrow().toString()
                        .getBytes(StandardCharsets.UTF_8));
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
    }

    private static HttpOptions central(boolean withMetadata) {
        String docs = FixtureCorpus.loadRawFixture(KAFKA).toString();
        JsonObject entry = new JsonObject();
        entry.addProperty("balaURL", BALA_URL);
        FakeTransport transport = FakeTransport.routing(url -> {
            if (url.contains("/docs/")) {
                return FakeTransport.ok(docs);
            }
            if (url.contains("/registry/packages/ballerinax/kafka/")) {
                return FakeTransport.ok(entry.toString());
            }
            return url.matches(".*/registry/packages/[^/]+/[^/]+") ? FakeTransport.ok("[\"4.6.6\"]")
                    : FakeTransport.status(404);
        }).downloading(url -> BALA_URL.equals(url) ? Optional.of(bala(withMetadata)) : Optional.empty());
        return HttpOptions.builder().transport(transport).baseDelayMs(1).sleeper(millis -> {
        }).build();
    }

    private static String run(HttpOptions http, String... argv) {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        int code = Cli.run(List.of(argv), new Cli.Streams(out::append, err::append), http);
        Assert.assertEquals(code, 0, String.join(" ", argv) + " failed with " + err);
        return out.toString();
    }

    private static JsonObject json(HttpOptions http, String... argv) {
        List<String> withOutput = new ArrayList<>(List.of(argv));
        withOutput.add("--output");
        withOutput.add("json");
        return JsonParser.parseString(run(http, withOutput.toArray(new String[0]))).getAsJsonObject();
    }

    @Test
    public void kafkaDeclaresARequiredAndAnOptionalHandler() {
        ServiceHandlers service = recorded(KAFKA, "kafka").get("Service");
        Assert.assertEquals(service.handlers().stream().map(ServiceHandlers.Handler::name).toList(),
                List.of("onConsumerRecord", "onError"));
        ServiceHandlers.Handler consumer = service.handlers().get(0);
        Assert.assertTrue(consumer.required());
        Assert.assertFalse(service.handlers().get(1).required());
        Assert.assertEquals(consumer.signature(), "remote function onConsumerRecord(kafka:Caller caller, "
                + "kafka:AnydataConsumerRecord[]|kafka:BytesConsumerRecord[] records) returns error?");
        Assert.assertFalse(consumer.params().get(0).required());
        Assert.assertTrue(consumer.params().get(1).required());
    }

    @Test
    public void graphqlHandlersAreOpenShapesWithAnAtLeastOneRule() {
        ServiceHandlers service = recorded("ballerina__graphql", "graphql").get("Service");
        Assert.assertTrue(service.handlers().stream().allMatch(ServiceHandlers.Handler::repeatable));
        Assert.assertEquals(service.handlers().get(0).signature(),
                "resource function get <path>(anydata <name>) returns anydata|error");
        Assert.assertEquals(service.handlers().get(1).signature(),
                "remote function <name>(anydata <name>) returns anydata|error");
        Assert.assertEquals(service.rules().size(), 1);
        Assert.assertEquals(service.rules().get(0).requirement(), "at least one of");
        Assert.assertEquals(service.rules().get(0).handlers(), List.of("query"));
    }

    @Test
    public void typesAreSpelledAsSourceWithTheModulePrefixOnTheirOwnTypes() {
        String file = """
                {"version":"v1.2","serviceTypes":[{"id":"$s","doc":"d","type":{"name":"S"},"concrete":false,
                "handlers":{"backedByConcreteType":false,"options":[{"id":"$s.on","name":"on","kind":"remote",
                "doc":"d","presence":"required","params":[
                {"id":"$s.on.a","name":"a","doc":"d","presence":"required","type":[{"name":"Msg"},
                  {"shape":"array","elementType":{"name":"byte","builtin":true}},{"name":"()","builtin":true}]},
                {"id":"$s.on.b","name":"b","doc":"d","presence":"optional","type":{"name":"Hdr",
                  "packageInfo":{"org":"ballerina","packageName":"http","moduleName":"http","version":"1"}}},
                {"id":"$s.on.c","name":"c","doc":"d","presence":"optional","type":
                  {"shape":"readonly","elementType":{"shape":"stream","elementType":{"name":"anydata","builtin":true},
                  "completionType":[{"name":"Error"},{"name":"()","builtin":true}]}}}],
                "returns":{"id":"$s.on.returns","type":[{"name":"error","builtin":true},
                  {"name":"()","builtin":true}]}}]}}]}
                """;
        ServiceHandlers.Handler handler = TriggerMetadata.parse(JsonParser.parseString(file), "pkg")
                .get("S").handlers().get(0);
        Assert.assertEquals(handler.signature(), "remote function on((pkg:Msg|byte[])? a, http:Hdr b, "
                + "readonly & stream<anydata, pkg:Error?> c) returns error?");
    }

    @Test
    public void aFileThisReaderCannotTrustYieldsNoHandlers() {
        Assert.assertTrue(TriggerMetadata.parse(JsonParser.parseString("{\"version\":\"v2.0\",\"serviceTypes\":[]}"),
                "p").isEmpty());
        Assert.assertTrue(TriggerMetadata.parse(JsonParser.parseString("[]"), "p").isEmpty());
        Assert.assertTrue(TriggerMetadata.parse(JsonParser.parseString("{}"), "p").isEmpty());
        String unwritable = """
                {"version":"v1.0","serviceTypes":[{"id":"$s","doc":"d","type":{"name":"S"},
                "handlers":{"backedByConcreteType":false,"options":[{"id":"$s.on","name":"on","kind":"remote",
                "doc":"d","params":[{"id":"$s.on.a","name":"a","doc":"d","type":{"shape":"matrix",
                "elementType":{"name":"int","builtin":true}}}]}]}}]}
                """;
        Assert.assertTrue(TriggerMetadata.parse(JsonParser.parseString(unwritable), "p").isEmpty());
    }

    @Test
    public void aConcreteServiceTypeKeepsItsOwnMethods() {
        String concrete = """
                {"version":"v1.0","serviceTypes":[{"id":"$s","doc":"d","type":{"name":"S"},"concrete":true,
                "handlers":{"backedByConcreteType":true}}]}
                """;
        Assert.assertTrue(TriggerMetadata.parse(JsonParser.parseString(concrete), "p").isEmpty());
    }

    @Test
    public void bothRenderingsListTheHandlersFromThePublishedBala() {
        HttpOptions http = central(true);
        String text = run(http, "ballerinax/kafka", "service", "--output", "text");
        Assert.assertTrue(text.contains("Methods to implement (2)\n\nremote function onConsumerRecord("), text);
        Assert.assertTrue(text.contains("  required\n"), text);
        Assert.assertTrue(text.contains("remote function onError(kafka:Error kafkaError) returns error?\n  optional"),
                text);
        Assert.assertTrue(text.contains("Note: attach it to kafka:Listener"), text);
        Assert.assertFalse(text.contains("No methods"), text);

        JsonObject answer = json(http, "ballerinax/kafka", "service", "Service");
        JsonArray handlers = answer.getAsJsonArray("handlers");
        Assert.assertEquals(handlers.size(), 2);
        JsonObject consumer = handlers.get(0).getAsJsonObject();
        Assert.assertEquals(consumer.get("name").getAsString(), "onConsumerRecord");
        Assert.assertEquals(consumer.get("kind").getAsString(), "remote");
        Assert.assertTrue(consumer.get("required").getAsBoolean());
        Assert.assertFalse(handlers.get(1).getAsJsonObject().get("required").getAsBoolean());
        Assert.assertEquals(consumer.getAsJsonArray("params").size(), 2);
        Assert.assertEquals(consumer.get("returns").getAsString(), "error?");
        Assert.assertEquals(answer.get("note").getAsString(), "attach it to kafka:Listener");
    }

    @Test
    public void aHandlerWithoutADocRendersInText() {
        ServiceHandlers.Handler handler = new ServiceHandlers.Handler("onEvent", ServiceHandlers.Kind.REMOTE, true,
                false, List.of(), null, null, List.of(), null);
        String text = TextRenderer.render(new DiscoverResult.ServiceHandlerList(
                "Service", List.of(handler), List.of(new ServiceHandlers.Rule(null, List.of(), null)), null, null));
        Assert.assertEquals(text, "Service\nMethods to implement (1)\n\nremote function onEvent()\n  required");
    }

    @Test
    public void aHandlerNameThatMatchesNothingListsTheMethodsToImplement() {
        HttpOptions http = central(true);
        StringBuilder err = new StringBuilder();
        int code = Cli.run(List.of("ballerinax/kafka", "service", "Service", "nosuch", "--output", "json"),
                new Cli.Streams(out -> { }, err::append), http);
        Assert.assertEquals(code, 1, err.toString());
        JsonObject failure = JsonParser.parseString(err.toString()).getAsJsonObject();
        Assert.assertEquals(failure.get("kind").getAsString(), "symbol-not-found");
        Assert.assertEquals(failure.get("command").getAsString(), "bal discover ballerinax/kafka service Service");
    }

    @Test
    public void aHandlerNamedOnTheCommandLineIsShownAlone() {
        HttpOptions http = central(true);
        JsonObject answer = json(http, "ballerinax/kafka", "service", "Service", "onError");
        Assert.assertEquals(answer.getAsJsonArray("handlers").size(), 1);
        String text = run(http, "ballerinax/kafka", "service", "Service", "onError", "--output", "text");
        Assert.assertTrue(text.contains("Methods to implement (1)"), text);
    }

    @Test
    public void aPackageWithoutTheFileAnswersAsBefore() {
        HttpOptions http = central(false);
        String text = run(http, "ballerinax/kafka", "service", "--output", "text");
        Assert.assertTrue(text.contains("No methods."), text);
        Assert.assertFalse(text.contains("Methods to implement"), text);
        Assert.assertFalse(json(http, "ballerinax/kafka", "service", "Service").has("handlers"));
    }

    @Test
    public void anUnreachableBalaAnswersAsBefore() {
        HttpOptions http = HttpOptions.builder().transport(FakeTransport.routing(url ->
                url.contains("/docs/") ? FakeTransport.ok(FixtureCorpus.loadRawFixture(KAFKA).toString())
                        : url.matches(".*/registry/packages/[^/]+/[^/]+") ? FakeTransport.ok("[\"4.6.6\"]")
                        : FakeTransport.status(404))).baseDelayMs(1).sleeper(millis -> {
        }).build();
        Assert.assertTrue(run(http, "ballerinax/kafka", "service", "--output", "text").contains("No methods."));
    }

    @Test
    public void aLocalBalaSuppliesItsMetadataWithoutTheNetwork() throws IOException {
        Path root = Files.createTempDirectory("discover-handlers");
        try {
            Path platform = root.resolve("ballerinax/kafka/4.6.6/java21");
            Files.createDirectories(platform.resolve("metadata"));
            Files.writeString(platform.resolve(Bala.TRIGGER_METADATA),
                    FixtureCorpus.recordedTriggerMetadata(KAFKA).orElseThrow().toString());
            Optional<Map<String, String>> files = LocalBalas.homeRepository(root.resolve("unused"))
                    .moduleSources(QualifiedName.parse("ballerinax/kafka").value(),
                            Version.parse("4.6.6").value(), "kafka", HttpOptions.builder().build());
            Assert.assertTrue(files.isEmpty(), "a home repository reads its own layout only");

            Path home = Files.createDirectories(root.resolve("repositories/central.ballerina.io/bala"));
            Files.move(root.resolve("ballerinax"), home.resolve("ballerinax"));
            Map<String, String> found = LocalBalas.homeRepository(root)
                    .moduleSources(QualifiedName.parse("ballerinax/kafka").value(),
                            Version.parse("4.6.6").value(), "kafka", HttpOptions.builder().build()).orElseThrow();
            Assert.assertEquals(found.keySet(), java.util.Set.of(Bala.TRIGGER_METADATA));
        } finally {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    public void theDescriptionAndItsAbsenceAreCachedSoTheBalaIsFetchedOnce() throws IOException {
        Path root = Files.createTempDirectory("discover-handlers-cache");
        try {
            DocsCache cache = DiskCache.at(root, 0700);
            for (boolean withMetadata : new boolean[] {true, false}) {
                String name = withMetadata ? "kafka" : "other";
                QualifiedName qualified = QualifiedName.parse("ballerinax/" + name).value();
                FakeTransport transport = FakeTransport.routing(url -> FakeTransport.ok(
                        "{\"balaURL\":\"" + BALA_URL + "\"}")).downloading(url -> Optional.of(bala(withMetadata)));
                HttpOptions http = HttpOptions.builder().transport(transport).cache(cache).build();
                CentralClient.ResolvedVersion resolved =
                        CentralClient.ResolvedVersion.latest(Version.parse("4.6.6").value(), false);
                Map<String, ServiceHandlers> first = TriggerMetadata.load(
                        CentralRepository.INSTANCE, qualified, resolved, "kafka", "kafka", http);
                int calls = transport.calls();
                Map<String, ServiceHandlers> second = TriggerMetadata.load(
                        CentralRepository.INSTANCE, qualified, resolved, "kafka", "kafka", http);
                Assert.assertEquals(second, first);
                Assert.assertEquals(first.containsKey("Service"), withMetadata);
                Assert.assertEquals(transport.calls(), calls, "the second load reached the network");
            }
        } finally {
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
