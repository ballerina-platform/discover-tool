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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ballerina.tools.discover.central.HttpOptions;
import io.ballerina.tools.discover.cli.Cli;
import io.ballerina.tools.discover.constructs.Decl;
import io.ballerina.tools.discover.constructs.Node;
import io.ballerina.tools.discover.constructs.Payload;
import io.ballerina.tools.discover.model.Pipeline;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.symbols.Surface;
import io.ballerina.tools.discover.views.Containers;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * What a service answer says about the listener a type binds to: confirmed or plainly marked otherwise, the same
 * whichever bucket the caller typed, and with commands that resolve for another module's service type.
 *
 * @since 0.1.0
 */
public class ServiceAnswersTest {

    private static final String BALA_URL = "https://files.example/http.bala";

    private static byte[] httpBala() {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> source
                    : FixtureCorpus.recordedSources("ballerina__http").orElseThrow().entrySet()) {
                zip.putNextEntry(new ZipEntry("modules/http/" + source.getKey()));
                zip.write(source.getValue().getBytes(StandardCharsets.UTF_8));
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException cause) {
            throw new UncheckedIOException(cause);
        }
    }

    /** Central replayed for {@code slug}, serving http's archive only when {@code withSource}. */
    private static HttpOptions central(String slug, boolean withSource) {
        String docs = FixtureCorpus.loadRawFixture(slug).toString();
        JsonObject entry = new JsonObject();
        entry.addProperty("balaURL", BALA_URL);
        FakeTransport transport = FakeTransport.routing(url -> {
            if (url.contains("/docs/")) {
                return FakeTransport.ok(docs);
            }
            if (url.contains("/registry/packages/ballerina/http/")) {
                return withSource ? FakeTransport.ok(entry.toString()) : FakeTransport.status(404);
            }
            return url.matches(".*/registry/packages/[^/]+/[^/]+") ? FakeTransport.ok("[\"2.16.6\"]")
                    : FakeTransport.status(404);
        }).downloading(url -> withSource && BALA_URL.equals(url) ? Optional.of(httpBala()) : Optional.empty());
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
    public void withoutTheSourceAnUnsettledPairingIsMarkedInBothRenderings() {
        JsonObject roster = json(central("ballerina__http", false), "ballerina/http", "service");
        for (JsonElement element : roster.getAsJsonArray("containers")) {
            JsonObject container = element.getAsJsonObject();
            boolean target = "Service".equals(container.get("name").getAsString());
            Assert.assertEquals(container.has("confirmed"), !target, container.toString());
            if (!target) {
                Assert.assertFalse(container.get("confirmed").getAsBoolean());
            }
        }
        String text = run(central("ballerina__http", false), "ballerina/http", "service", "--output", "text");
        Assert.assertTrue(text.contains("http:Listener\n  Service"), text);
        Assert.assertTrue(text.contains("http:Listener — not confirmed (package source unavailable)\n"
                + "  ServiceContract"), text);
    }

    @Test
    public void withTheSourceEveryPairingIsConfirmed() {
        JsonObject roster = json(central("ballerina__http", true), "ballerina/http", "service");
        for (JsonElement element : roster.getAsJsonArray("containers")) {
            Assert.assertFalse(element.getAsJsonObject().has("confirmed"), element.toString());
        }
        Assert.assertEquals(roster.getAsJsonArray("notAttachable").size(), 4);
    }

    @Test
    public void aServiceTypeReachedThroughAnotherBucketSaysWhatTheServiceBucketSays() {
        HttpOptions http = central("ballerina__http", true);
        String through = json(http, "ballerina/http", "class", "RequestInterceptor").get("note").getAsString();
        Assert.assertTrue(through.contains("not attachable to any listener this package declares"), through);
        String bound = json(http, "ballerina/http", "class", "InterceptableService").get("note").getAsString();
        Assert.assertTrue(bound.contains("binds to http:Listener"), bound);
        Assert.assertFalse(bound.contains("not confirmed"), bound);
    }

    @Test
    public void withoutTheSourceTheNoteSaysTheSourceWasUnavailable() {
        String note = json(central("ballerina__http", false), "ballerina/http", "service", "ServiceContract")
                .get("note").getAsString();
        Assert.assertTrue(note.contains("may bind to http:Listener — not confirmed: the package source"), note);
    }

    @Test
    public void anotherModulesServiceTypeAnswersToItsBareName() {
        JsonObject answer = json(central("ballerinax__postgresql", false), "ballerinax/postgresql", "service",
                "Service");
        Assert.assertEquals(answer.get("container").getAsString(), "cdc:Service");
        Assert.assertTrue(answer.get("note").getAsString().contains("bal discover ballerinax/cdc service Service"));
    }

    @Test
    public void aSiblingModulesServiceTypeIsReachedThroughModule() {
        Payload payload = Payload.pkg().with("listeners", Decl.listenerAttaching(
                Node.external("test", "pkg.events", "Service"), "Listener"));
        LoadedPackage loaded = new LoadedPackage(QualifiedName.parse("test/pkg").value(),
                FixtureCorpus.FIXTURE_VERSION, Pipeline.build(payload.module()), Optional.empty(), null, List.of(),
                null);
        Result<DiscoverResult> answer =
                Containers.render(loaded, Surface.Scope.SERVICE, new Containers.Options(List.of(), null, 1));
        DiscoverResult.ContainerRoster roster = (DiscoverResult.ContainerRoster) answer.value();
        Assert.assertEquals(roster.containers().get(0).command(),
                "bal discover test/pkg --module events service Service");
    }
}
