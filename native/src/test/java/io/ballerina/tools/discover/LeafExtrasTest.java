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
import io.ballerina.tools.discover.constructs.Decl;
import io.ballerina.tools.discover.constructs.Payload;
import io.ballerina.tools.discover.model.Library;
import io.ballerina.tools.discover.model.Pipeline;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.JsonRenderer;
import io.ballerina.tools.discover.render.TextRenderer;
import io.ballerina.tools.discover.symbols.Surface;
import io.ballerina.tools.discover.views.Containers;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a method's answer carries beyond its signature: how to construct the container it is called on, and the
 * readme sections whose code calls it.
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

    private static final String PAIR = "## Two clients\n\n```ballerina\nacme:Client main = check new ();\n"
            + "acme:Backup spare = check new ();\ncheck spare->send(1);\n```\n\n## Main sends\n\n```ballerina\n"
            + "check main->send(2);\n```\n\n## Unknown receiver\n\n```ballerina\ncheck other->send(3);\n"
            + "check other->ping();\n```\n\n## Prose only\n\nCall main->send(4) like so.\n\n## Paths\n\n"
            + "```ballerina\nacme:Client main = check new ();\nvar a = check main->/items/[id].delete();\n"
            + "var b = check main->/items/latest;\n"
            + "var d = check main->/'import.post(x);\n```\n";

    private static LoadedPackage acme() {
        Payload payload = Payload.pkg();
        payload.with("clients",
                Decl.client("Client", Decl.method("send").on("isRemote"), Decl.method("ping").on("isRemote"),
                        Decl.method("get").on("isResource").with("accessor", "get")
                                .with("resourcePath", "items/latest"),
                        Decl.method("get").on("isResource").with("accessor", "get")
                                .with("resourcePath", "items/[string id]"),
                        Decl.method("delete").on("isResource").with("accessor", "delete")
                                .with("resourcePath", "items/[string id]"),
                        Decl.method("post").on("isResource").with("accessor", "post").with("resourcePath", "'import")),
                Decl.client("Backup", Decl.method("send").on("isRemote")));
        Library library = Pipeline.build(payload.module());
        return new LoadedPackage(QualifiedName.parse("acme/acme").value(), FixtureCorpus.FIXTURE_VERSION, library,
                Optional.of(PAIR), null, List.of(), List.of(), null, null, () -> library, Map::of);
    }

    private static List<Integer> callers(LoadedPackage loaded, String... selectors) {
        Result<DiscoverResult> answer = Containers.render(loaded, Surface.Scope.CLIENT,
                new Containers.Options(List.of(selectors)));
        Assert.assertTrue(answer.isOk(), answer.isOk() ? "" : answer.failure().describe());
        return ((DiscoverResult.Signature) answer.value()).callers().stream()
                .map(DiscoverResult.ReadmeSections.Section::number)
                .toList();
    }

    @Test
    public void aCallIsTheContainersWhenItsReceiverIsDeclaredAsIt() {
        LoadedPackage acme = acme();
        // Section 1 calls Backup's send, section 2 Client's: the receiver's declared type decides, wherever it is
        // declared. Section 3's receiver is declared nowhere, and two containers declare send.
        Assert.assertEquals(callers(acme, "Client", "send"), List.of(2));
        Assert.assertEquals(callers(acme, "Backup", "send"), List.of(1));
        // Only Client declares ping, so an undeclared receiver's call is its; prose is never read.
        Assert.assertEquals(callers(acme, "Client", "ping"), List.of(3));
    }

    @Test
    public void aResourceCallMatchesItsAccessorAndLiteralSegmentsAndTheMostLiteralResourceWins() {
        LoadedPackage acme = acme();
        Assert.assertEquals(callers(acme, "Client", "items/:id", "delete"), List.of(5));
        Assert.assertEquals(callers(acme, "Client", "items/latest", "get"), List.of(5),
                "`->/items/latest` is a get, and the literal resource's");
        Assert.assertEquals(callers(acme, "Client", "items/:id", "get"), List.of());
        Assert.assertEquals(callers(acme, "Client", "import", "post"), List.of(5), "`'import` is `import`");
    }

    @Test
    public void readmeCallersRenderAsSectionRowsWithTheirCommands() {
        Result<DiscoverResult> answer = Containers.render(acme(), Surface.Scope.CLIENT,
                new Containers.Options(List.of("Client", "send")));
        String text = TextRenderer.render(answer.value());
        Assert.assertTrue(text.contains("\nReadme sections that call it\n  2  Main sends  bal discover acme/acme "
                + "readme 2"), text);
        JsonObject row = JsonParser.parseString(JsonRenderer.render(answer.value())).getAsJsonObject()
                .getAsJsonArray("callers").get(0).getAsJsonObject();
        Assert.assertEquals(row.get("command").getAsString(), "bal discover acme/acme readme 2");
    }

    @Test
    public void onTheFixturesAKafkaCallerCommitIsNotAConsumersAndSendIsTheProducers() {
        LoadedPackage kafka = FixtureCorpus.loadedFixture("ballerinax__kafka");
        Assert.assertFalse(callers(kafka, "Producer", "send").isEmpty());
        Assert.assertTrue(callers(kafka, "Caller", "commit").containsAll(callers(kafka, "Consumer", "commit")));
    }

    @Test
    public void aServiceTypeWithNoMethodsAnswersWithItsDocumentationAndTheSectionsThatDeclareOne() {
        Result<DiscoverResult> answer = Containers.render(FixtureCorpus.loadedFixture("ballerina__http"),
                Surface.Scope.SERVICE, new Containers.Options(List.of("Service")));
        DiscoverResult.ServiceExamples service = (DiscoverResult.ServiceExamples) answer.value();
        Assert.assertEquals(service.description().strip(), "The HTTP service type.");
        Assert.assertFalse(service.sections().isEmpty(), "the readme's `service ... on new http:Listener` section");
        Assert.assertNull(service.next());
        String text = TextRenderer.render(service);
        Assert.assertTrue(text.contains("\nService declares no methods: a service of it defines its own.\n"), text);
        Assert.assertTrue(text.contains("\nReadme sections that declare one\n"), text);
    }

    @Test
    public void theOnlyServiceTypeOfAModuleWithNoListenerTakesAnyServiceTheReadmeDeclares() {
        Payload payload = Payload.pkg();
        payload.with("serviceTypes", Decl.serviceType("Service").with("description", "Define onRead."));
        Library library = Pipeline.build(payload.module());
        LoadedPackage cdc = new LoadedPackage(QualifiedName.parse("acme/cdc").value(), FixtureCorpus.FIXTURE_VERSION,
                library, Optional.of("## Intro\n\nText.\n\n## Define it\n\n```ballerina\nlistener mysql:CdcListener l"
                        + " = new ();\nservice on l {\n}\n```\n"), null, List.of(), List.of(), null, null,
                () -> library, Map::of);
        DiscoverResult.ServiceExamples service = (DiscoverResult.ServiceExamples) Containers.render(cdc,
                Surface.Scope.SERVICE, new Containers.Options(List.of())).value();
        Assert.assertEquals(service.sections().stream().map(DiscoverResult.ReadmeSections.Section::title).toList(),
                List.of("Define it"));
        JsonObject json = JsonParser.parseString(JsonRenderer.render(service)).getAsJsonObject();
        Assert.assertEquals(json.getAsJsonArray("methods").size(), 0);
        Assert.assertEquals(json.get("description").getAsString(), "Define onRead.");

        LoadedPackage bare = new LoadedPackage(cdc.qualified(), cdc.version(), library, Optional.of("## Intro\n\nNo "
                + "code.\n"), null, List.of(), List.of(), null, null, () -> library, Map::of);
        DiscoverResult.ServiceExamples none = (DiscoverResult.ServiceExamples) Containers.render(bare,
                Surface.Scope.SERVICE, new Containers.Options(List.of())).value();
        Assert.assertEquals(none.next(), "bal discover acme/cdc readme", "never a dead end");
    }
}
