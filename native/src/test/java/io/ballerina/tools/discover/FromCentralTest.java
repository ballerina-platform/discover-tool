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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ballerina.tools.discover.central.schema.CentralDocs;
import io.ballerina.tools.discover.central.schema.Schema;
import io.ballerina.tools.discover.model.Fn;
import io.ballerina.tools.discover.model.FromCentral;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Which module of a payload gets rendered.
 *
 * <p>Every fixture in the corpus is single-module, so this is the one behaviour the corpus cannot test by
 * construction — and the one the cache's coordinate check depends on, since verifying one module and rendering
 * another verifies nothing. The payloads here are therefore assembled rather than recorded.
 *
 * @since 0.1.0
 */
public class FromCentralTest {

    private static QualifiedName qualified(String name) {
        Result<QualifiedName> parsed = QualifiedName.parse(name);
        Assert.assertTrue(parsed.isOk(), name);
        return parsed.value();
    }

    private static CentralDocs multiModule(List<String> ids, String org) {
        JsonObject raw = FixtureCorpus.loadRawFixture("ballerinax__kafka").getAsJsonObject();
        JsonElement template = raw.getAsJsonObject("docsData").getAsJsonArray("modules").get(0);

        JsonArray modules = new JsonArray();
        for (String id : ids) {
            JsonObject module = template.deepCopy().getAsJsonObject();
            module.addProperty("id", id);
            module.addProperty("orgName", org);
            module.addProperty("summary", "I am " + id);
            modules.add(module);
        }
        JsonObject docsData = new JsonObject();
        docsData.add("modules", modules);
        JsonObject wrapper = new JsonObject();
        wrapper.add("docsData", docsData);

        Result<CentralDocs> parsed = Schema.parse(wrapper, "assembled");
        Assert.assertTrue(parsed.isOk(), parsed.isOk() ? "" : parsed.failure().describe());
        return parsed.value();
    }

    @Test
    public void theRequestedModuleIsRendered() {
        // Not whichever one Central listed first.
        CentralDocs docs = multiModule(List.of("other", "kafka", "another"), "ballerinax");
        Result<CentralDocs.Module> selected = FromCentral.selectModule(docs, qualified("ballerinax/kafka"));
        Assert.assertTrue(selected.isOk());
        Assert.assertEquals(FromCentral.fromCentral(selected.value()).name(), "ballerinax/kafka");
        Assert.assertEquals(FromCentral.fromCentral(selected.value()).description(), "I am kafka");
    }

    @Test
    public void aDottedPackageNameSelectsItsModuleByExactId() {
        // `googleapis.gmail` is one literal package name, not a `gmail` module of some `googleapis` package.
        CentralDocs docs = multiModule(List.of("googleapis", "googleapis.gmail"), "ballerinax");
        Result<CentralDocs.Module> selected =
                FromCentral.selectModule(docs, qualified("ballerinax/googleapis.gmail"));
        Assert.assertTrue(selected.isOk());
        Assert.assertEquals(
                FromCentral.fromCentral(selected.value()).name(), "ballerinax/googleapis.gmail");
    }

    @Test
    public void theOrgHasToMatchToo() {
        // So a same-named module from another org is not substituted.
        CentralDocs docs = multiModule(List.of("kafka"), "someoneelse");
        Assert.assertFalse(FromCentral.selectModule(docs, qualified("ballerinax/kafka")).isOk());
    }

    @Test
    public void noMatchingModuleFailsLoudlyAndNamesWhatCentralReturned() {
        CentralDocs docs = multiModule(List.of("notkafka"), "ballerinax");
        Result<CentralDocs.Module> selected = FromCentral.selectModule(docs, qualified("ballerinax/kafka"));
        Assert.assertFalse(selected.isOk());
        Failure.SchemaDrift failure = (Failure.SchemaDrift) selected.failure();
        Assert.assertTrue(failure.describe().contains("ballerinax/notkafka"));
        Assert.assertFalse(failure.suggestion().isEmpty());
    }

    /**
     * A submodule's id starts with {@code qualified.name() + "."}, so a prefix match would render whichever module
     * the payload listed first; only the exact default module answers a caller who gave no {@code --module}.
     */
    @Test
    public void anExactDefaultModuleWinsOverAPrefixedSubmoduleWhicheverComesFirst() {
        CentralDocs docs = multiModule(List.of("kafka.other", "kafka"), "ballerinax");
        Result<CentralDocs.Module> selected = FromCentral.selectModule(docs, qualified("ballerinax/kafka"));
        Assert.assertTrue(selected.isOk());
        Assert.assertEquals(selected.value().id(), "kafka");
        Assert.assertEquals(FromCentral.fromCentral(selected.value()).description(), "I am kafka");
    }

    @Test
    public void aModuleFlagIsReachedByComposingItOntoThePackageName() {
        CentralDocs page = Schema.parse(FixtureCorpus.loadRawModulePage("ballerina__graphql.dataloader"), "page")
                .value();
        Result<CentralDocs.Module> selected =
                FromCentral.selectModule(page, qualified("ballerina/graphql"), "dataloader", null);
        Assert.assertTrue(selected.isOk());
        Assert.assertEquals(selected.value().id(), "graphql.dataloader");
    }

    @Test
    public void aPackagesSubmodulesAreTheOnesItsPageNamesNotTheOnesItCarries() {
        // Central's page for a package carries its default module alone; the rest are named in relatedModules.
        CentralDocs docs = FixtureCorpus.loadFixture("ballerina__graphql");
        Assert.assertEquals(docs.modules().size(), 1);
        Assert.assertEquals(FromCentral.submodulesOf(docs, qualified("ballerina/graphql")).stream()
                .map(CentralDocs.RelatedModule::id).toList(), List.of("graphql.dataloader", "graphql.subgraph"));
    }

    @Test
    public void aModuleFlagThatNamesNoSubmoduleFailsWithEveryBareSubmoduleName() {
        CentralDocs docs = FixtureCorpus.loadFixture("ballerina__graphql");
        Result<CentralDocs.Module> selected =
                FromCentral.selectModule(docs, qualified("ballerina/graphql"), "nosuch", null);
        Assert.assertFalse(selected.isOk());
        Failure.SymbolNotFound failure = (Failure.SymbolNotFound) selected.failure();
        Assert.assertEquals(failure.requested(), List.of("nosuch"));
        Assert.assertEquals(Set.copyOf(failure.candidates()), Set.of("dataloader", "subgraph"));
    }

    @Test
    public void aResourcePathKeepsAParametersTypeAndNameApart() {
        List<Fn.PathSegment> paths = FromCentral.createPaths(
                Optional.of("repos/[string owner]/[string repo]/code\\-scanning"));
        Assert.assertEquals(paths, List.of(
                new Fn.PathSegment.Literal("repos"),
                new Fn.PathSegment.Parameter("string", "owner"),
                new Fn.PathSegment.Parameter("string", "repo"),
                new Fn.PathSegment.Literal("code\\-scanning")));
    }

    @Test
    public void aRestPathParameterIsOneShapeWhicheverWayCentralSpacesIt() {
        Fn.PathSegment rest = new Fn.PathSegment.Parameter("PathParamType...", "path");
        Assert.assertEquals(FromCentral.createPaths(Optional.of("[PathParamType ...path]")), List.of(rest));
        Assert.assertEquals(FromCentral.createPaths(Optional.of("[PathParamType... path]")), List.of(rest));
        Assert.assertEquals(FromCentral.createPaths(Optional.of("[http:PathParamType... path]")),
                List.of(new Fn.PathSegment.Parameter("http:PathParamType...", "path")));
        Assert.assertEquals(FromCentral.createPaths(Optional.of("[string ... path]")),
                List.of(new Fn.PathSegment.Parameter("string...", "path")));
    }

    @Test
    public void aPathParameterOfAnIntersectionTypeIsNamedByItsLastWord() {
        Assert.assertEquals(FromCentral.createPaths(Optional.of("items/[readonly & string id]")), List.of(
                new Fn.PathSegment.Literal("items"), new Fn.PathSegment.Parameter("readonly & string", "id")));
    }

    @Test
    public void aBracketedSegmentWithNoSpaceInsideIsNotAParameter() {
        // Central emits an odd bracketed form without a type, and it stays a literal.
        Assert.assertEquals(FromCentral.createPaths(Optional.of("[\"quoted\"]")),
                List.of(new Fn.PathSegment.Literal("[\"quoted\"]")));
        Assert.assertEquals(FromCentral.createPaths(Optional.empty()), List.of());
    }
}
