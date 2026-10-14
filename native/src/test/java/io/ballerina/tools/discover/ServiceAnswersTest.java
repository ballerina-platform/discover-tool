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
import io.ballerina.tools.discover.model.Library;
import io.ballerina.tools.discover.model.Pipeline;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.JsonRenderer;
import io.ballerina.tools.discover.symbols.Surface;
import io.ballerina.tools.discover.views.Overview;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
        JsonObject roster = json(central("ballerina__http", false), "service", "-p", "ballerina/http");
        for (JsonElement element : roster.getAsJsonArray("containers")) {
            JsonObject container = element.getAsJsonObject();
            boolean target = "Service".equals(container.get("name").getAsString());
            Assert.assertEquals(container.has("confirmed"), !target, container.toString());
            if (!target) {
                Assert.assertFalse(container.get("confirmed").getAsBoolean());
                Assert.assertEquals(container.get("unconfirmedReason").getAsString(), "package source unavailable");
            } else {
                Assert.assertFalse(container.has("unconfirmedReason"), container.toString());
            }
        }
        String text = run(central("ballerina__http", false), "service", "-p", "ballerina/http", "--output", "text");
        Assert.assertTrue(text.contains("http:Listener\n  Service"), text);
        Assert.assertTrue(text.contains("http:Listener — unverified (package source unavailable)\n"
                + "  InterceptableService      1 method\n"
                + "  RequestErrorInterceptor"), text);
    }

    @Test
    public void withTheSourceEveryPairingIsConfirmed() {
        JsonObject roster = json(central("ballerina__http", true), "service", "-p", "ballerina/http");
        for (JsonElement element : roster.getAsJsonArray("containers")) {
            Assert.assertFalse(element.getAsJsonObject().has("confirmed"), element.toString());
        }
        Assert.assertEquals(roster.getAsJsonArray("notAttachable").size(), 4);
    }

    @Test
    public void aServiceTypeReachedThroughTypeSaysWhatTheServiceBucketSays() {
        HttpOptions http = central("ballerina__http", true);
        String through = json(http, "type", "RequestInterceptor", "-p", "ballerina/http").get("note").getAsString();
        Assert.assertTrue(through.contains("no listener in this package accepts it directly"), through);
        String bound = json(http, "type", "InterceptableService", "-p", "ballerina/http").get("note").getAsString();
        Assert.assertTrue(bound.contains("attach it to http:Listener"), bound);
        Assert.assertFalse(bound.contains("unverified"), bound);
    }

    @Test
    public void withoutTheSourceTheNoteSaysTheSourceWasUnavailable() {
        String note = json(central("ballerina__http", false), "service", "ServiceContract", "-p", "ballerina/http")
                .get("note").getAsString();
        Assert.assertTrue(note.contains("probably attaches to http:Listener (unverified: the package source"), note);
    }

    @Test
    public void anotherPackagesServiceTypeOpensInTheListenersPackageAndNamesWhereItIsDeclared() {
        HttpOptions http = central("ballerinax__postgresql", false);
        for (List<String> argv : List.of(List.of("service", "cdc:Service", "-p", "ballerinax/postgresql"),
                List.of("service", "-p", "ballerinax/postgresql"))) {
            JsonObject answer = json(http, argv.toArray(new String[0]));
            Assert.assertEquals(answer.get("container").getAsString(), "cdc:Service", argv.toString());
            Assert.assertTrue(answer.get("note").getAsString()
                    .contains("declared in ballerinax/cdc — bal discover service Service -p ballerinax/cdc"));
        }
        JsonObject overview = json(http, "package", "ballerinax/postgresql");
        Assert.assertEquals(overview.getAsJsonObject("serviceTypes").getAsJsonArray("entries").get(0)
                .getAsJsonObject().get("command").getAsString(), "bal discover service cdc:Service -p "
                + "ballerinax/postgresql", "the listener's package may describe the methods a service of it defines");
    }

    @Test
    public void aSiblingModulesServiceTypeIsReachedThroughModule() {
        LoadedPackage loaded = attaching(Node.external("test", "pkg.events", "Service"), null, "events");
        Assert.assertEquals(rowCommand(loaded), "bal discover service events:Service -p test/pkg");
        Assert.assertTrue(open(loaded, "events:Service").get("note").getAsString()
                .contains("declared in test/pkg.events — bal discover service Service -p test/pkg --module events"));
    }

    @Test
    public void aSiblingModulesServiceTypeIsReachedThroughModuleFromAnotherSubmodule() {
        LoadedPackage loaded = attaching(Node.external("test", "pkg.b", "Service"), "a", "a", "b");
        Assert.assertTrue(bare(loaded).get("note").getAsString()
                .endsWith(" — bal discover service Service -p test/pkg --module b"), bare(loaded).toString());
    }

    @Test
    public void aForeignServiceTypeNeedingQuotesIsQuotedInItsCommand() {
        LoadedPackage loaded = attaching(Node.external("test", "pkg.b", "'Service"), "a", "a", "b");
        Assert.assertTrue(bare(loaded).get("note").getAsString()
                .endsWith(" — bal discover service \"'Service\" -p test/pkg --module b"), bare(loaded).toString());
    }

    @Test
    public void theDefaultModuleIsReachedFromASubmoduleWithoutModule() {
        LoadedPackage loaded = attachingIn("pkg.core", Node.external("test", "pkg.core", "Service"), "events");
        Assert.assertTrue(bare(loaded).get("note").getAsString()
                .endsWith(" — bal discover service Service -p test/pkg.core"), bare(loaded).toString());
    }

    @Test
    public void aPackageWhoseNameMerelyExtendsThisOneIsOpenedThroughItsLocalStub() {
        LoadedPackage loaded = attaching(Node.external("test", "pkg.driver", "Service"), null, "events");
        Assert.assertEquals(rowCommand(loaded), "bal discover service driver:Service -p test/pkg");
        String note = open(loaded, "driver:Service").get("note").getAsString();
        Assert.assertTrue(note.endsWith("declared in test/pkg.driver"), note);
    }

    @Test
    public void anotherPackagesSubmoduleIsOpenedThroughItsLocalStub() {
        LoadedPackage loaded = attaching(Node.external("other", "lib.events", "Service"), null);
        String command = rowCommand(loaded);
        Assert.assertEquals(command, "bal discover service events:Service -p test/pkg");

        JsonObject answer = open(loaded, command.split(" ")[3]);
        Assert.assertEquals(answer.get("container").getAsString(), "events:Service");
        String note = answer.get("note").getAsString();
        Assert.assertTrue(note.endsWith("declared in other/lib.events"), note);
        Assert.assertFalse(note.contains("bal discover"), note);
    }

    private static LoadedPackage attaching(Node serviceType, String module, String... submodules) {
        return attachingIn("pkg", serviceType, module, submodules);
    }

    private static LoadedPackage attachingIn(String name, Node serviceType, String module, String... submodules) {
        Payload payload = Payload.pkg("test", module == null ? name : name + "." + module)
                .with("listeners", Decl.listenerAttaching(serviceType, "Listener"));
        Library library = Pipeline.build(payload.module());
        String childPrefix = module == null ? "" : module + ".";
        List<LoadedPackage.Submodule> children = Arrays.stream(submodules)
                .filter(submodule -> submodule.startsWith(childPrefix))
                .map(submodule -> new LoadedPackage.Submodule(submodule, ""))
                .toList();
        return new LoadedPackage(QualifiedName.parse("test/" + name).value(), FixtureCorpus.FIXTURE_VERSION,
                library, Optional.empty(), module, children, List.of(submodules), null, null, () -> library, Map::of);
    }

    // The command the package's overview opens its one service type with.
    private static String rowCommand(LoadedPackage loaded) {
        return Overview.of(loaded).sections().stream()
                .filter(section -> Surface.Scope.SERVICE.verb().equals(section.bucket()))
                .findFirst().orElseThrow().rows().get(0).command();
    }

    private static JsonObject bare(LoadedPackage loaded) {
        return JsonParser.parseString(JsonRenderer.render(Asked.of(loaded, "service").value())).getAsJsonObject();
    }

    private static JsonObject open(LoadedPackage loaded, String selector) {
        Result<DiscoverResult> answer = Asked.of(loaded, "service", selector);
        Assert.assertTrue(answer.isOk(), selector);
        return JsonParser.parseString(JsonRenderer.render(answer.value())).getAsJsonObject();
    }
}
