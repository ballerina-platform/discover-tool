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
import com.google.gson.JsonParser;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.Documents;
import io.ballerina.tools.discover.render.JsonRenderer;
import io.ballerina.tools.discover.render.TextRenderer;
import io.ballerina.tools.discover.symbols.Surface;
import io.ballerina.tools.discover.views.Containers;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Every answer a bucket can give, in both renderings, and the Ballerina it quotes — enforced mechanically.
 *
 * <p>Breadth is the point: the rules have to hold for answers nobody has written yet, so this walks every bucket
 * over every container of every fixture rather than sampling one shape per bucket. {@code readme} is absent on
 * purpose — its content is the package author's own bytes rather than this tool's.
 *
 * @since 0.1.0
 */
public class RegisterTest {

    @DataProvider(name = "fixtures")
    public Object[][] fixtures() {
        return FixtureCorpus.fixtureRows();
    }

    /**
     * An answer plus what produced it, for a failure message that names the query.
     *
     * @param label what produced the answer
     * @param result the answer itself
     */
    private record Answer(String label, DiscoverResult result) { }

    /** Every answer one fixture's buckets give: bare, filtered, per container, and a selector that misses. */
    private static List<Answer> answers(String slug) {
        LoadedPackage context = FixtureCorpus.loadedFixture(slug);
        List<Answer> answers = new ArrayList<>();
        for (Surface.Scope scope : Surface.Scope.values()) {
            String verb = scope.verb();
            add(answers, verb, Containers.render(context, scope, Containers.Options.bare()));
            add(answers, verb + " --filter config",
                    Containers.render(context, scope, new Containers.Options(List.of(), "config", 1)));
            for (Surface.Container container : Surface.of(context.library(), scope)) {
                List<String> selector = container.isModule() ? List.of() : List.of(container.name());
                if (!selector.isEmpty()) {
                    add(answers, verb + " " + container.name(),
                            Containers.render(context, scope, new Containers.Options(selector)));
                }
                List<String> missing = new ArrayList<>(selector);
                missing.add("zzzznosuchmember");
                add(answers, verb + " " + String.join(" ", missing),
                        Containers.render(context, scope, new Containers.Options(missing)));
                for (String member : container.memberNames().stream().limit(3).toList()) {
                    List<String> one = new ArrayList<>(selector);
                    one.add(member);
                    add(answers, verb + " " + String.join(" ", one),
                            Containers.render(context, scope, new Containers.Options(one)));
                }
            }
        }
        return answers;
    }

    private static void add(List<Answer> answers, String label, Result<DiscoverResult> view) {
        Assert.assertTrue(view.isOk(), label + ": " + (view.isOk() ? "" : view.failure().describe()));
        answers.add(new Answer(label, view.value()));
    }

    // -----------------------------------------------------------------------
    // Both renderings
    // -----------------------------------------------------------------------

    @Test(dataProvider = "fixtures")
    public void everyAnswerRendersAsOneJsonObject(String slug) {
        for (Answer answer : answers(slug)) {
            JsonElement parsed = JsonParser.parseString(JsonRenderer.render(answer.result()));
            Assert.assertTrue(parsed.isJsonObject(), slug + " " + answer.label());
        }
    }

    /**
     * No text rendering carries the furniture of the Markdown report this tool used to print: a format marker,
     * a facts table, a heading. Those were answers of their own register, which {@code --output text} replaced.
     */
    @Test(dataProvider = "fixtures")
    public void noTextRenderingCarriesReportFurniture(String slug) {
        for (Answer answer : answers(slug)) {
            String text = TextRenderer.render(answer.result());
            String label = slug + " " + answer.label();
            Assert.assertFalse(text.isBlank(), label + ": an empty answer");
            Assert.assertFalse(text.contains("<!-- bal discover"), label + ": a report marker");
            Assert.assertFalse(Pattern.compile("^\\|.*\\|$", Pattern.MULTILINE).matcher(text).find(),
                    label + ": a Markdown table");
            Assert.assertFalse(Pattern.compile("^#{1,6} ", Pattern.MULTILINE).matcher(text).find()
                            && !(answer.result() instanceof DiscoverResult.Signature),
                    label + ": a Markdown heading");
            Assert.assertFalse(text.contains("\n\n\n"), label + ": a block was emitted empty");
            Assert.assertFalse(text.endsWith("\n"), label + ": the CLI adds the one trailing newline");
        }
    }

    // -----------------------------------------------------------------------
    // The Ballerina an answer quotes
    // -----------------------------------------------------------------------

    /**
     * Quoted Ballerina is nothing but declarations: no fence, no report marker, no table. A fence inside a
     * {@code #} doc comment is the package author's sample, not this tool's structure, so only a fence at the
     * start of a line counts.
     */
    @Test(dataProvider = "fixtures")
    public void quotedBallerinaCarriesNoReportFurniture(String slug) {
        LoadedPackage context = FixtureCorpus.loadedFixture(slug);
        List<String> documents = new ArrayList<>(List.of(Documents.toSyntaxString(context.library())));
        for (Answer answer : answers(slug)) {
            if (answer.result() instanceof DiscoverResult.Signature signature) {
                documents.add(signature.declaration());
                signature.types().forEach(type -> documents.add(type.declaration()));
            }
        }
        Assert.assertTrue(documents.size() > 1, slug + ": no answer quoted a declaration");
        for (String document : documents) {
            Assert.assertFalse(Pattern.compile("^\\s*```", Pattern.MULTILINE).matcher(document).find(),
                    slug + ": a fence in quoted Ballerina:\n" + document);
            Assert.assertFalse(document.contains("<!-- bal discover"), slug + ": a report marker");
            Assert.assertFalse(Pattern.compile("^\\|.*\\|$", Pattern.MULTILINE).matcher(document).find(),
                    slug + ": a Markdown table");
        }
    }
}
