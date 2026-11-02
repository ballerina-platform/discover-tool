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
import io.ballerina.tools.discover.constructs.Node;
import io.ballerina.tools.discover.constructs.Payload;
import io.ballerina.tools.discover.model.Library;
import io.ballerina.tools.discover.model.Pipeline;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.JsonRenderer;
import io.ballerina.tools.discover.render.TextRenderer;
import io.ballerina.tools.discover.symbols.Surface;
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

    private static DiscoverResult.Signature leaf(String slug, Surface.Scope scope, String container,
            String... member) {
        Result<DiscoverResult> answer = Asked.member(FixtureCorpus.loadedFixture(slug), scope, container, member);
        Assert.assertTrue(answer.isOk(), answer.isOk() ? "" : answer.failure().describe());
        return (DiscoverResult.Signature) answer.value();
    }

    @Test
    public void aMethodSaysHowToConstructItsContainerFromItsInit() {
        DiscoverResult.Signature send = leaf("ballerinax__kafka", Surface.Scope.CLIENT, "Producer", "send");
        Assert.assertEquals(send.create().code(), "kafka:Producer producer = check new (bootstrapServers, config);");
        Assert.assertEquals(send.create().init(), "init(string|string[] bootstrapServers, "
                + "*kafka:ProducerConfiguration config)");
        Assert.assertEquals(send.create().command(), "bal discover method init -p ballerinax/kafka --client Producer");
    }

    @Test
    public void aVariableThatWouldBeAKeywordTakesThePrefixAndADefaultedParameterIsLeftOut() {
        DiscoverResult.Signature get = leaf("ballerinax__github", Surface.Scope.CLIENT, "Client", "gists", "get");
        Assert.assertEquals(get.create().code(), "github:Client githubClient = check new (config);");
        Assert.assertEquals(get.create().init(), "init(github:ConnectionConfig config, string serviceUrl = "
                + "\"https://api.github.com\")");
    }

    @Test
    public void aRunOfCapitalsIsLowerCasedUpToTheNextWordAndAClassWithNoInitTakesNoArguments() {
        Payload payload = Payload.pkg();
        payload.with("clients", Decl.client("HTTPClient", Decl.method("send").on("isRemote")));
        payload.with("classes", Decl.client("Plain", Decl.method("go")));
        Library library = Pipeline.build(payload.module());
        LoadedPackage acme = new LoadedPackage(QualifiedName.parse("acme/acme").value(), FixtureCorpus.FIXTURE_VERSION,
                library, Optional.empty(), null, List.of(), List.of(), null, null, () -> library, Map::of);
        DiscoverResult.Signature send = (DiscoverResult.Signature) Asked.of(acme, "method", "send", "--client",
                "HTTPClient").value();
        Assert.assertEquals(send.create().code(), "acme:HTTPClient httpClient = new ();");
        DiscoverResult.Signature go = (DiscoverResult.Signature) Asked.of(acme, "method", "go", "--class", "Plain")
                .value();
        Assert.assertEquals(go.create().code(), "acme:Plain plain = new ();");
        Assert.assertNull(go.create().init());
        Assert.assertNull(go.create().command());
    }

    private static final String DEFAULT_VALUE = "defaultValue";

    private static String createdWith(Decl... initParams) {
        Payload payload = Payload.pkg();
        payload.with("clients", Decl.client("Client", Decl.method("init", initParams),
                Decl.method("send").on("isRemote")));
        payload.with("records", Decl.record("Config", Decl.field("port", Node.builtin("int"))),
                Decl.record("Params", Decl.field("seed", Node.builtin("int"))));
        Library library = Pipeline.build(payload.module());
        LoadedPackage acme = new LoadedPackage(QualifiedName.parse("acme/acme").value(), FixtureCorpus.FIXTURE_VERSION,
                library, Optional.empty(), null, List.of(), List.of(), null, null, () -> library, Map::of);
        Result<DiscoverResult> send = Asked.of(acme, "method", "send", "--client", "Client");
        Assert.assertTrue(send.isOk(), send.isOk() ? "" : send.failure().describe());
        return ((DiscoverResult.Signature) send.value()).create().code();
    }

    private static Decl included(String name, String record) {
        return Decl.param(name, Node.named(record, "records").on("isInclusion"));
    }

    @Test
    public void anIncludedRecordAfterASkippedDefaultIsPassedByName() {
        Assert.assertEquals(createdWith(Decl.param("host", Node.builtin("string")),
                        Decl.param("username", Node.builtin("string")).with(DEFAULT_VALUE, "()"),
                        Decl.param("password", Node.builtin("string")).with(DEFAULT_VALUE, "()"),
                        included("clientConfig", "Config")),
                "acme:Client acmeClient = new (host, clientConfig = clientConfig);");
    }

    @Test
    public void everyIncludedRecordAfterASkippedDefaultIsPassedByName() {
        Assert.assertEquals(createdWith(Decl.param("modelType", Node.builtin("string")),
                        Decl.param("serviceUrl", Node.builtin("string")).with(DEFAULT_VALUE, "\"http://x\""),
                        included("modelParameters", "Params"), included("connectionConfig", "Config")),
                "acme:Client acmeClient = new (modelType, modelParameters = modelParameters, "
                        + "connectionConfig = connectionConfig);");
    }

    @Test
    public void aRestParameterIsLeftOut() {
        Assert.assertEquals(createdWith(Decl.param("a", Node.builtin("string")),
                        Decl.param("r", Node.structural().on("isRestParam").elementType(Node.builtin("string")))),
                "acme:Client acmeClient = new (a);");
    }

    @Test
    public void aKeywordNamedArgumentIsQuotedOnBothSides() {
        Assert.assertEquals(createdWith(Decl.param("url", Node.builtin("string")),
                        Decl.param("timeout", Node.builtin("int")).with(DEFAULT_VALUE, "10"),
                        included("type", "Config")),
                "acme:Client acmeClient = new (url, 'type = 'type);");
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
        Assert.assertTrue(text.contains(";\n\nCreate it\n  kafka:Producer producer = check new (bootstrapServers, "
                + "config);\n  init(string|string[] bootstrapServers, *kafka:ProducerConfiguration config)\n  bal "
                + "discover method init -p ballerinax/kafka --client Producer\n"), text);
        JsonObject create = JsonParser.parseString(JsonRenderer.render(send)).getAsJsonObject()
                .getAsJsonObject("create");
        Assert.assertEquals(create.get("command").getAsString(),
                "bal discover method init -p ballerinax/kafka --client Producer");
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

    private static List<Integer> callers(LoadedPackage loaded, String container, String... member) {
        Result<DiscoverResult> answer = Asked.member(loaded, Surface.Scope.CLIENT, container, member);
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
        Result<DiscoverResult> answer = Asked.of(acme(), "method", "send", "--client", "Client");
        String text = TextRenderer.render(answer.value());
        Assert.assertTrue(text.contains("\nReadme sections that call it\n  2  Main sends  bal discover readme 2 -p "
                + "acme/acme"), text);
        JsonObject row = JsonParser.parseString(JsonRenderer.render(answer.value())).getAsJsonObject()
                .getAsJsonArray("callers").get(0).getAsJsonObject();
        Assert.assertEquals(row.get("command").getAsString(), "bal discover readme 2 -p acme/acme");
    }

    @Test
    public void onTheFixturesAKafkaCallerCommitIsNotAConsumersAndSendIsTheProducers() {
        LoadedPackage kafka = FixtureCorpus.loadedFixture("ballerinax__kafka");
        Assert.assertEquals(callers(kafka, "Producer", "send"), List.of(7));
        Assert.assertEquals(callers(kafka, "Caller", "commit"), List.of(6));
        Assert.assertEquals(callers(kafka, "Consumer", "commit"), List.of(),
                "section 6 calls caller->commit() on a kafka:Caller");
    }

    private static final String RECEIVERS = "## Field\n\n```ballerina\nclass Repo {\n    acme:Store store;\n"
            + "    function go() {\n        var r = check self.store->/files/[\"a\"]/[\"b\"].get();\n    }\n}\n```\n\n"
            + "## Union\n\n```ballerina\nacme:Store|error s = new ();\nvar x = check s->/items/[id].delete();\n```"
            + "\n\n## Undeclared\n\n```ballerina\nvar y = check other->/items/[id].delete();\n```\n";

    private static LoadedPackage stores() {
        Payload payload = Payload.pkg();
        payload.with("clients",
                Decl.client("Store",
                        Decl.method("get").on("isResource").with("accessor", "get")
                                .with("resourcePath", "files/[string... path]"),
                        Decl.method("delete").on("isResource").with("accessor", "delete")
                                .with("resourcePath", "items/[string id]")),
                Decl.client("Archive", Decl.method("delete").on("isResource").with("accessor", "delete")
                        .with("resourcePath", "items/[string itemId]")));
        Library library = Pipeline.build(payload.module());
        return new LoadedPackage(QualifiedName.parse("acme/acme").value(), FixtureCorpus.FIXTURE_VERSION, library,
                Optional.of(RECEIVERS), null, List.of(), List.of(), null, null, () -> library, Map::of);
    }

    @Test
    public void aRestPathAFieldReceiverAndAUnionDeclaredReceiverAreRead() {
        LoadedPackage stores = stores();
        Assert.assertEquals(callers(stores, "Store", "files/:...path", "get"), List.of(1),
                "`self.store` is the field declared acme:Store, and a rest parameter takes every remaining segment");
        Assert.assertEquals(callers(stores, "Store", "items/:id", "delete"), List.of(2),
                "`acme:Store|error s` is a Store; an undeclared receiver's call Archive's items/:itemId also answers");
    }



    @Test
    public void aServiceTypeWithNoMethodsAnswersWithItsDocumentationAndTheSectionsThatDeclareOne() {
        Result<DiscoverResult> answer = Asked.of(FixtureCorpus.loadedFixture("ballerina__http"), "service",
                "Service");
        DiscoverResult.ServiceExamples service = (DiscoverResult.ServiceExamples) answer.value();
        Assert.assertEquals(service.description().strip(), "The HTTP service type.");
        Assert.assertFalse(service.sections().isEmpty(), "the readme's `service ... on new http:Listener` section");
        Assert.assertNull(service.next());
        String text = TextRenderer.render(service);
        Assert.assertTrue(text.contains("\nService declares no methods; a service of this type declares its own "
                + "resource or remote methods.\n"), text);
        Assert.assertTrue(text.contains("\nReadme sections that declare one\n"), text);
    }

    @Test
    public void anUntypedServiceIsTheListenersAttachTypesNotEveryServiceTypeTheListenerAccepts() {
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        DiscoverResult.ServiceExamples service = (DiscoverResult.ServiceExamples) Asked.of(http, "service", "Service")
                .value();
        Assert.assertFalse(service.sections().isEmpty());
        DiscoverResult.ServiceExamples contract = (DiscoverResult.ServiceExamples) Asked.of(http, "service",
                "ServiceContract").value();
        Assert.assertEquals(contract.sections(), List.of(),
                "`service /x on new http:Listener(...)` is an http:Service, though the listener accepts both");
    }

    @Test
    public void twoServiceTypesOnOneListenerWhoseAttachTakesEitherClaimOnlyTheServicesTypedAsThem() {
        Payload payload = Payload.pkg();
        payload.with("listeners", Decl.listenerAttaching(Node.structural().on("isAnonymousUnionType").members(
                Node.named("Orders", "objectTypes").with("orgName", "test").with("moduleName", "pkg"),
                Node.named("Events", "objectTypes").with("orgName", "test").with("moduleName", "pkg")), "Listener"));
        payload.with("serviceTypes", Decl.serviceType("Orders"), Decl.serviceType("Events"));
        Library library = Pipeline.build(payload.module());
        String readme = "## Untyped\n\n```ballerina\nservice on new pkg:Listener() {\n}\n```\n\n## Typed\n\n"
                + "```ballerina\nservice pkg:Events on new pkg:Listener() {\n}\n```\n";
        LoadedPackage acme = new LoadedPackage(QualifiedName.parse("test/pkg").value(), FixtureCorpus.FIXTURE_VERSION,
                library, Optional.of(readme), null, List.of(), List.of(), null, null, () -> library, Map::of);
        for (String type : List.of("Orders", "Events")) {
            DiscoverResult.ServiceExamples answer = (DiscoverResult.ServiceExamples) Asked.of(acme, "service", type)
                    .value();
            Assert.assertEquals(answer.sections().stream().map(DiscoverResult.ReadmeSections.Section::title).toList(),
                    type.equals("Events") ? List.of("Typed") : List.of(), type);
        }
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
        DiscoverResult.ServiceExamples service = (DiscoverResult.ServiceExamples) Asked.of(cdc, "service").value();
        Assert.assertEquals(service.sections().stream().map(DiscoverResult.ReadmeSections.Section::title).toList(),
                List.of("Define it"));
        JsonObject json = JsonParser.parseString(JsonRenderer.render(service)).getAsJsonObject();
        Assert.assertEquals(json.getAsJsonArray("methods").size(), 0);
        Assert.assertEquals(json.get("description").getAsString(), "Define onRead.");
        Assert.assertEquals(service.note(), "no listener in this package accepts it; it attaches to a listener "
                + "another package declares");

        LoadedPackage bare = new LoadedPackage(cdc.qualified(), cdc.version(), library, Optional.of("## Intro\n\nNo "
                + "code.\n"), null, List.of(), List.of(), null, null, () -> library, Map::of);
        DiscoverResult.ServiceExamples none = (DiscoverResult.ServiceExamples) Asked.of(bare, "service").value();
        Assert.assertEquals(none.next(), "bal discover readme -p acme/cdc", "never a dead end");
    }
}
