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
import com.google.gson.JsonParser;
import io.ballerina.tools.discover.constructs.Decl;
import io.ballerina.tools.discover.constructs.Payload;
import io.ballerina.tools.discover.model.Library;
import io.ballerina.tools.discover.model.Pipeline;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.JsonRenderer;
import io.ballerina.tools.discover.render.TextRenderer;
import io.ballerina.tools.discover.symbols.Surface;
import io.ballerina.tools.discover.views.Overview;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * An overview section's rows go by how often the module's readme uses each name, alphabetical otherwise.
 *
 * @since 0.1.0
 */
public class OverviewOrderTest {

    private static final List<String> CLASSES = List.of("Alpha", "Beta", "Gamma", "Delta");

    @Test
    public void theMostUsedNameComesFirst() {
        String readme = "Use `Gamma`, then `Gamma` again, and `Delta` once.\n```ballerina\nGamma g = new;\n```";
        Assert.assertEquals(classRows(readme), List.of("Gamma", "Delta", "Alpha", "Beta"));
    }

    @Test
    public void tiesAndUnusedNamesStayAlphabetical() {
        Assert.assertEquals(classRows("`Delta` and `Beta`, once each."), List.of("Beta", "Delta", "Alpha", "Gamma"));
        Assert.assertEquals(classRows("Nothing here names a class."), List.of("Alpha", "Beta", "Delta", "Gamma"));
    }

    @Test
    public void onlyAWholeIdentifierIsAUse() {
        Assert.assertEquals(classRows("`AlphaConfig, BetaClient, _Gamma, Gamma2 and alpha. Delta.`"),
                List.of("Delta", "Alpha", "Beta", "Gamma"));
    }

    @Test
    public void aQualifiedOrEscapedNameIsAUse() {
        Assert.assertEquals(classRows("```ballerina\npkg:Delta d = check new;\n'Beta b = new;\n```"),
                List.of("Beta", "Delta", "Alpha", "Gamma"));
    }

    @Test
    public void aProseWordIsNotAUse() {
        Assert.assertEquals(rows(List.of("Alpha", "Status"),
                "`Alpha`. Online Certificate Status Protocol checks Status, Status and Status."),
                List.of("Alpha", "Status"));
    }

    @Test
    public void anInlineCodeSpanIsAUse() {
        Assert.assertEquals(rows(List.of("Alpha", "Beta", "Status"), "`Alpha` and `Alpha`, then `pkg:Status`."),
                List.of("Alpha", "Status", "Beta"));
    }

    @Test
    public void aTildeFenceIsCode() {
        Assert.assertEquals(rows(List.of("Alpha", "Beta"), "Prose.\n~~~ballerina\nBeta b = new;\n~~~\nAlpha."),
                List.of("Beta", "Alpha"));
    }

    @Test
    public void anotherModulesQualifiedNameIsNotAUse() {
        Assert.assertEquals(classRows("`jdbc:Gamma` and `jdbc:Gamma`, then `pkg:Delta`."),
                List.of("Delta", "Alpha", "Beta", "Gamma"));
    }

    @Test
    public void aSubmoduleCountsItsOwnPrefix() {
        Payload payload = Payload.pkg();
        payload.with("classes", CLASSES.stream().map(name -> Decl.client(name, Decl.method("run")))
                .toArray(Decl[]::new));
        Library library = Pipeline.build(payload.module());
        LoadedPackage sub = new LoadedPackage(QualifiedName.parse("test/pkg").value(), FixtureCorpus.FIXTURE_VERSION,
                library, Optional.of("`sub:Gamma`, `pkg:Beta` and `pkg:Beta`."), "sub", List.of(), List.of("sub"),
                null, null, () -> library, Map::of);
        Assert.assertEquals(names(Overview.of(sub)), List.of("Gamma", "Alpha", "Beta", "Delta"));
    }

    @Test
    public void withoutAReadmeTheOrderIsAlphabetical() {
        Assert.assertEquals(names(Overview.of(withClasses(Optional.empty()))),
                List.of("Alpha", "Beta", "Delta", "Gamma"));
    }

    @Test
    public void jsonAndTextShowTheSameOrder() {
        DiscoverResult.Overview overview = Overview.of(withClasses(Optional.of("`Gamma Gamma Beta`")));
        JsonArray entries = JsonParser.parseString(JsonRenderer.render(overview)).getAsJsonObject()
                .getAsJsonObject("classes").getAsJsonArray("entries");
        Assert.assertEquals(StreamSupport.stream(entries.spliterator(), false)
                        .map(JsonElement::getAsJsonObject).map(entry -> entry.get("name").getAsString()).toList(),
                List.of("Gamma", "Beta", "Alpha", "Delta"));
        String text = TextRenderer.render(overview, new TextRenderer.Context("test/pkg", null, List.of(), null, null,
                null));
        List<Integer> places = List.of("Gamma", "Beta", "Alpha", "Delta").stream()
                .map(name -> text.indexOf("  " + name + " ")).toList();
        Assert.assertTrue(places.get(0) >= 0, text);
        Assert.assertEquals(places, places.stream().sorted().toList(), text);
    }

    @Test
    public void theRowsACutKeepsAreTheMostUsed() {
        Payload payload = Payload.pkg();
        List<Decl> classes = new ArrayList<>();
        for (char letter = 'A'; letter <= 'L'; letter++) {
            classes.add(Decl.client("Class" + letter, Decl.method("run")));
        }
        payload.with("classes", classes.toArray(Decl[]::new));
        List<String> shown = names(Overview.of(loaded(payload, Optional.of("`ClassL` and `ClassK`; `ClassL`."))));
        Assert.assertEquals(shown.size(), 10);
        Assert.assertEquals(shown.subList(0, 3), List.of("ClassL", "ClassK", "ClassA"));
        Assert.assertFalse(shown.contains("ClassI"), shown.toString());
    }

    private static List<String> classRows(String readme) {
        return rows(CLASSES, readme);
    }

    private static List<String> rows(List<String> classes, String readme) {
        return names(Overview.of(withClasses(classes, Optional.of(readme))));
    }

    private static List<String> names(DiscoverResult.Overview overview) {
        return overview.sections().stream()
                .filter(section -> section.bucket().equals(Surface.Scope.CLASS.verb()))
                .findFirst().orElseThrow().rows().stream()
                .map(DiscoverResult.Overview.Row::name)
                .toList();
    }

    private static LoadedPackage withClasses(Optional<String> readme) {
        return withClasses(CLASSES, readme);
    }

    private static LoadedPackage withClasses(List<String> classes, Optional<String> readme) {
        Payload payload = Payload.pkg();
        payload.with("classes", classes.stream().map(name -> Decl.client(name, Decl.method("run")))
                .toArray(Decl[]::new));
        return loaded(payload, readme);
    }

    private static LoadedPackage loaded(Payload payload, Optional<String> readme) {
        Library library = Pipeline.build(payload.module());
        return new LoadedPackage(QualifiedName.parse("test/pkg").value(), FixtureCorpus.FIXTURE_VERSION, library,
                readme, null, List.of(), List.of(), null, null, () -> library, Map::of);
    }
}
