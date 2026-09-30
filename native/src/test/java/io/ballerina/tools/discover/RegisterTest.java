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

import io.ballerina.tools.discover.render.Documents;
import io.ballerina.tools.discover.symbols.Surface;
import io.ballerina.tools.discover.views.Containers;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The two registers, enforced mechanically.
 *
 * <p>A document either IS Ballerina or it DESCRIBES a package, and without a test the two drift back together —
 * an earlier design produced a {@code client class Client {} shell whose body was
 * {@code // WARNING: 903 resource functions}, which looks like a declaration, is not one, and invites an agent to
 * transcribe from it.
 *
 * <p>This is the mechanical form of that rule, run over every fixture and every verb, so it also covers documents
 * nobody has written yet.
 *
 * @since 0.1.0
 */
public class RegisterTest {

    /**
     * Things that would read as a declaration. {@code type} is deliberately absent: it appears in prose all the
     * time ("read one with {@code type}") and inside {@code | Types |}, and a keyword test on it would ban the
     * English word.
     */
    private static final Pattern LOOKS_LIKE_A_DECLARATION = Pattern.compile(
            "^\\s*(client class|class|service|public annotation|enum|remote function|resource function"
                    + "|function)\\b");

    private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,}|~{3,})");

    @DataProvider(name = "fixtures")
    public Object[][] fixtures() {
        return FixtureCorpus.fixtureRows();
    }

    /**
     * A document plus what produced it, for a failure message that names the verb.
     *
     * @param label what produced the document
     * @param text the document itself
     */
    private record Document(String label, String text) { }

    /**
     * Lines outside every fenced block.
     *
     * <p>Fence tracking rather than a regex, because the guide is embedded verbatim and carries its own fences —
     * including tilde ones — and a line inside somebody else's sample is a quotation, not this document's claim.
     */
    private static List<String> unfencedLines(String document) {
        List<String> lines = new ArrayList<>();
        String fence = null;
        for (String line : document.split("\n", -1)) {
            java.util.regex.Matcher opener = FENCE.matcher(line);
            boolean opens = opener.find();
            if (fence == null && opens) {
                fence = opener.group(1);
                continue;
            }
            if (fence != null && opens && opener.group(1).startsWith(fence.substring(0, 3))) {
                fence = null;
                continue;
            }
            if (fence == null) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * Every report document a fixture can produce, keyed by what produced it.
     *
     * <p>Breadth is the point. The register rules have to hold for documents nobody has written yet, so this walks
     * every verb over every container of every fixture rather than sampling one shape per verb — which is how the
     * one document that reads as source stays out of the corpus. {@code readme} is absent here on purpose: it
     * answers on the result IR from the start (see {@code DiscoverResultRenderingTest}) rather than through this
     * still-Markdown register, and its content is the package author's own bytes rather than this tool's prose —
     * neither register's rules were ever about a quotation.
     */
    private static List<Document> reportDocuments(String slug) {
        LoadedPackage context = FixtureCorpus.loadedFixture(slug);
        List<Document> documents = new ArrayList<>();

        for (Surface.Scope scope : Surface.Scope.values()) {
            documents.addAll(containerDocuments(context, scope));
        }
        return documents;
    }

    /**
     * One scope's documents: the bare listing, one per container, a filter, and a selector that misses.
     *
     * <p>Structured answers (a roster, a grouped or paginated listing over the RFC's entry ceiling) are skipped
     * here — the report-register rules this class enforces (headings, fences, the {@code <!-- bal discover -->}
     * marker) are specifically about the Markdown shape {@code Containers} still produces for the cases with no
     * ceiling problem; a structured answer is neither register, by design, and the {@code render} package's own
     * tests pin its shape instead.
     */
    private static List<Document> containerDocuments(LoadedPackage context, Surface.Scope scope) {
        List<Document> documents = new ArrayList<>();
        String verb = scope.verb();
        addIfMarkdown(documents, verb, Containers.render(context, scope, Containers.Options.bare()));
        addIfMarkdown(documents, verb + " -s",
                Containers.render(context, scope, new Containers.Options(List.of(), "config", false, false, 1)));

        List<Surface.Container> containers = Surface.of(context.library(), scope);
        for (Surface.Container container : containers) {
            List<String> selector = container.isModule() ? List.of() : List.of(container.name());
            if (!selector.isEmpty()) {
                addIfMarkdown(documents, verb + " " + container.name(),
                        Containers.render(context, scope, new Containers.Options(selector)));
            }
            // A selector that matches nothing is answered at exit 0 with what IS there, so it is a report like any
            // other and has to obey the same rules.
            List<String> missing = new ArrayList<>(selector);
            missing.add("zzzznosuchmember");
            addIfMarkdown(documents, verb + " (missing selector)",
                    Containers.render(context, scope, new Containers.Options(missing)));
        }
        return documents;
    }

    private static void addIfMarkdown(List<Document> documents, String label, Result<Containers.Answer> view) {
        Assert.assertTrue(view.isOk(), view.isOk() ? "" : view.failure().describe());
        if (view.value() instanceof Containers.Answer.Markdown markdown) {
            documents.add(new Document(label, markdown.text()));
        }
    }

    // -----------------------------------------------------------------------
    // The report register
    // -----------------------------------------------------------------------

    @Test(dataProvider = "fixtures")
    public void noReportDocumentCarriesADeclarationOutsideAFence(String slug) {
        for (Document document : reportDocuments(slug)) {
            for (String line : unfencedLines(document.text())) {
                Assert.assertFalse(LOOKS_LIKE_A_DECLARATION.matcher(line).find(),
                        slug + " " + document.label() + ": this reads as source:\n  " + line);
            }
        }
    }

    @Test(dataProvider = "fixtures")
    public void noReportDocumentAnnotatesItselfWithASlashSlashComment(String slug) {
        for (Document document : reportDocuments(slug)) {
            for (String line : unfencedLines(document.text())) {
                // In the code register a `//` comment annotates a real declaration, which is what
                // `// Special Agent Note:` does. Here it was the thing doing the impersonating, and Markdown
                // prose replaces it.
                Assert.assertFalse(line.startsWith("//"),
                        slug + " " + document.label() + ": a bare // comment outside a fence:\n  " + line);
            }
        }
    }

    @Test(dataProvider = "fixtures")
    public void everyReportDocumentIsNavigableByHeading(String slug) {
        for (Document document : reportDocuments(slug)) {
            // Structure is headings, so `grep '^## '` returns the document's sections.
            long sections = unfencedLines(document.text()).stream()
                    .filter(line -> line.startsWith("## "))
                    .count();
            Assert.assertTrue(sections > 0, slug + " " + document.label() + ": no sections");
            Assert.assertTrue(document.text().matches("(?s)^<!-- bal discover \\w+ v1 -->\n# .*"),
                    slug + " " + document.label() + ": no marker and title");
            Assert.assertTrue(document.text().endsWith("\n"),
                    slug + " " + document.label() + ": no trailing newline");
            Assert.assertFalse(document.text().contains("\n\n\n"),
                    slug + " " + document.label() + ": blank-line runs mean a block was emitted empty");
        }
    }

    // -----------------------------------------------------------------------
    // The code register
    // -----------------------------------------------------------------------

    @Test(dataProvider = "fixtures")
    public void noCodeDocumentCarriesReportFurniture(String slug) {
        LoadedPackage context = FixtureCorpus.loadedFixture(slug);

        List<Document> documents = new ArrayList<>(List.of(
                new Document("api", Documents.toSyntaxString(context.library()))));

        // The register is a property of the DOCUMENT, not of the verb. A `-r` response is
        // nothing but declarations, so it is code however it was reached — which means the container verbs produce
        // documents in BOTH registers and each has to obey the rules of the one it is in.
        for (Surface.Scope scope : Surface.Scope.values()) {
            for (Surface.Container container : Surface.of(context.library(), scope)) {
                List<String> selector = container.isModule() ? List.of() : List.of(container.name());
                Result<Containers.Answer> resolved = Containers.render(context, scope,
                        new Containers.Options(selector, null, true, false, 1));
                Assert.assertTrue(resolved.isOk(), scope + " " + container.name());
                Assert.assertTrue(resolved.value() instanceof Containers.Answer.Markdown,
                        scope + " " + container.name() + ": the code register is always Markdown");
                documents.add(new Document(scope.verb() + " " + container.name() + " -r",
                        ((Containers.Answer.Markdown) resolved.value()).text()));
            }
        }

        for (Document document : documents) {
            // A fence at the START of a line would be this document's own structure. One inside a `#` doc comment
            // is the package author's sample, and every fence in the corpus's api snapshots is of that second
            // kind — verified zero at line start across all nine.
            Assert.assertFalse(Pattern.compile("^\\s*```", Pattern.MULTILINE)
                            .matcher(document.text()).find(),
                    slug + " " + document.label() + ": a fence in the code register");
            Assert.assertFalse(document.text().contains("<!-- bal discover"),
                    slug + " " + document.label() + ": a report marker");
            Assert.assertFalse(Pattern.compile("^\\|.*\\|$", Pattern.MULTILINE)
                            .matcher(document.text()).find(),
                    slug + " " + document.label() + ": a Markdown table");
            // NOTE: `#` is not tested. A leading `# ` here is a Ballerina doc comment — the language's own syntax
            // — and banning it would ban the documentation.
        }
    }
}
