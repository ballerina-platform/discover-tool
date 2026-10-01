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
import io.ballerina.tools.discover.views.Readme;
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
                // The three answers that carry a note: a member named without its owner, a wildcard path that
                // skips sibling branches, and a container asked of a bucket that does not hold it.
                container.memberNames().stream().findFirst().ifPresent(member -> addIfOk(answers, verb + " " + member,
                        Containers.render(context, scope, new Containers.Options(List.of(member)))));
                if (container.hasPaths()) {
                    List<String> wildcard = new ArrayList<>(selector);
                    wildcard.add("*/*");
                    add(answers, verb + " " + String.join(" ", wildcard),
                            Containers.render(context, scope, new Containers.Options(wildcard)));
                }
                for (Surface.Scope other : Surface.Scope.values()) {
                    if (other != scope && !container.isModule()) {
                        add(answers, other.verb() + " " + container.name(),
                                Containers.render(context, other, new Containers.Options(selector)));
                    }
                }
            }
        }
        return answers;
    }

    /** A member named bare can be ambiguous across buckets in ways this walk does not control. */
    private static void addIfOk(List<Answer> answers, String label, Result<DiscoverResult> view) {
        if (view.isOk()) {
            answers.add(new Answer(label, view.value()));
        }
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
     * Every JSON answer is exactly one line: an agent's tooling cuts output by line, and a cut anywhere inside a
     * multi-line answer leaves JSON that does not parse. The readme is included here, because its markdown is the
     * one value certain to carry newlines of its own.
     */
    @Test(dataProvider = "fixtures")
    public void everyJsonAnswerIsOneLine(String slug) {
        List<Answer> all = new ArrayList<>(answers(slug));
        LoadedPackage context = FixtureCorpus.loadedFixture(slug);
        add(all, "readme", Readme.render(context, Readme.Options.BARE));
        Readme.chunksOf(context).forEach(chunk -> add(all, "readme " + chunk.number(),
                Readme.render(context, new Readme.Options(String.valueOf(chunk.number()), null, 1))));
        for (Answer answer : all) {
            String json = JsonRenderer.render(answer.result());
            Assert.assertFalse(json.contains("\n") || json.contains("\r"), slug + " " + answer.label() + ":\n" + json);
            Assert.assertTrue(JsonParser.parseString(json).isJsonObject(), slug + " " + answer.label());
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

    /**
     * A note is plain prose in both renderings: a Markdown backtick in it is a leftover of the report register,
     * and in JSON it is two characters a caller has to strip before the command inside can run.
     */
    @Test(dataProvider = "fixtures")
    public void noNoteCarriesMarkdown(String slug) {
        int notes = 0;
        for (Answer answer : answers(slug)) {
            String note = switch (answer.result()) {
                case DiscoverResult.PathGroups groups -> groups.note();
                case DiscoverResult.ResourceList resources -> resources.note();
                case DiscoverResult.MethodList methods -> methods.note();
                case DiscoverResult.MixedListing mixed -> mixed.note();
                case DiscoverResult.Signature signature -> signature.note();
                case DiscoverResult.NoMatch noMatch -> noMatch.note();
                default -> null;
            };
            if (note != null) {
                Assert.assertFalse(note.contains("`"), slug + " " + answer.label() + ": " + note);
                notes++;
            }
        }
        if (slug.equals("ballerina__http") || slug.equals("ballerinax__github")) {
            Assert.assertTrue(notes > 0, slug + ": no answer carried a note, so this checked nothing");
        }
    }

    // -----------------------------------------------------------------------
    // Text layout
    // -----------------------------------------------------------------------

    /** Each entry on a line of its own, in order — never a comma-joined run that wraps mid-word in a terminal. */
    @Test(dataProvider = "fixtures")
    public void everyTextListingPrintsOneEntryPerLine(String slug) {
        int checked = 0;
        for (Answer answer : answers(slug)) {
            List<String> entries = entriesOf(answer.result());
            List<String> lines = TextRenderer.render(answer.result()).lines().toList();
            int previous = -1;
            for (String entry : entries) {
                int line = previous + 1;
                while (line < lines.size() && !isRowFor(lines.get(line), entry)) {
                    line++;
                }
                Assert.assertTrue(line < lines.size(), slug + " " + answer.label() + ": no line of its own for '"
                        + entry + "'\n" + String.join("\n", lines));
                previous = line;
                checked++;
            }
        }
        Assert.assertTrue(checked > 0, slug + ": no listing entry was checked");
    }

    /** Within a block of rows, every name is padded to the longest, so whatever follows it starts in one column. */
    @Test(dataProvider = "fixtures")
    public void everyTextBlockLinesUpItsColumns(String slug) {
        for (Answer answer : answers(slug)) {
            if (answer.result() instanceof DiscoverResult.Signature) {
                continue;
            }
            List<String> lines = TextRenderer.render(answer.result()).lines().toList();
            int start = 0;
            while (start < lines.size()) {
                int indent = indentOf(lines.get(start));
                int end = start + 1;
                while (end < lines.size() && indent >= 2 && indentOf(lines.get(end)) == indent) {
                    end++;
                }
                if (indent >= 2) {
                    assertAligned(lines.subList(start, end), indent, slug + " " + answer.label());
                }
                start = end;
            }
        }
    }

    /**
     * Every command the JSON rendering gives a row is printable from the text: verbatim, or as a {@code Next:}
     * shape whose placeholder that row's own name fills — and a listing that left entries out ends on the command
     * that reaches them.
     */
    @Test(dataProvider = "fixtures")
    public void everyTextAnswerCarriesEveryCommandTheJsonDoes(String slug) {
        for (Answer answer : answers(slug)) {
            String text = TextRenderer.render(answer.result());
            String label = slug + " " + answer.label() + "\n" + text;
            List<String> shapes = text.lines().filter(line -> line.startsWith("Next: "))
                    .map(line -> line.substring("Next: ".length()))
                    .toList();
            for (String[] row : callsOf(answer.result())) {
                String literal = row[0];
                String call = row[1];
                boolean reachable = text.contains(call) || shapes.stream().anyMatch(shape -> shape.contains("<")
                        && call.equals(shape.substring(0, shape.indexOf('<')) + literal
                                + shape.substring(shape.lastIndexOf('>') + 1)))
                        || row.length > 2 && accessorSlotOnItsRow(text, row[2], call);
                Assert.assertTrue(reachable, label + "\nmissing: " + call);
            }
            String next = nextOf(answer.result());
            if (next != null) {
                Assert.assertTrue(shapes.contains(next), label + "\nmissing Next: " + next);
            }
        }
    }

    /**
     * A resource row whose quoted path the footer's shape cannot spell prints its command once with the accessor
     * left as a slot — lossless, because the same row lists every accessor that fills it.
     */
    private static boolean accessorSlotOnItsRow(String text, String path, String call) {
        String slotted = call.substring(0, call.lastIndexOf(' ')) + " <accessor>";
        return text.lines().anyMatch(line -> isRowFor(line, path) && line.endsWith(slotted));
    }

    private static boolean isRowFor(String line, String entry) {
        String trimmed = line.strip();
        return line.startsWith("  ") && trimmed.startsWith(entry)
                && (trimmed.length() == entry.length() || trimmed.startsWith(entry + "  "));
    }

    private static int indentOf(String line) {
        return line.isEmpty() ? -1 : line.length() - line.stripLeading().length();
    }

    private static void assertAligned(List<String> block, int indent, String label) {
        int widest = block.stream().mapToInt(line -> nameOf(line, indent).length()).max().orElse(0);
        for (String line : block) {
            String name = nameOf(line, indent);
            if (line.length() > indent + name.length()) {
                Assert.assertTrue(line.length() > indent + widest + 2
                                && line.substring(indent + name.length(), indent + widest + 2).isBlank(),
                        label + ": a column out of line\n" + String.join("\n", block));
            }
        }
    }

    private static String nameOf(String line, int indent) {
        String rest = line.substring(indent);
        int gap = rest.indexOf("  ");
        return gap < 0 ? rest : rest.substring(0, gap);
    }

    private static List<String> entriesOf(DiscoverResult result) {
        List<String> entries = new ArrayList<>();
        switch (result) {
            case DiscoverResult.ContainerRoster roster ->
                    roster.containers().forEach(container -> entries.add(container.name()));
            case DiscoverResult.PathGroups groups -> {
                groups.resources().forEach(resource -> entries.add(resource.path()));
                groups.groups().forEach(group -> entries.add(group.name()));
            }
            case DiscoverResult.ResourceList resources ->
                    resources.resources().forEach(resource -> entries.add(resource.path()));
            case DiscoverResult.MethodList methods ->
                    methods.methods().forEach(method -> entries.add(method.name()));
            case DiscoverResult.MixedListing mixed -> {
                mixed.resources().forEach(resource -> entries.add(resource.path()));
                mixed.remote().forEach(method -> entries.add(method.name()));
                mixed.normal().forEach(method -> entries.add(method.name()));
                entries.addAll(mixed.documented().names());
            }
            case DiscoverResult.NoMatch noMatch -> {
                entries.addAll(noMatch.candidates());
                noMatch.paths().forEach(alternative -> entries.add(alternative.path()));
            }
            case DiscoverResult.Owners owners -> owners.owners().forEach(owner -> entries.add(owner.name()));
            case DiscoverResult.EmptyBucket empty -> empty.elsewhere().forEach(other -> entries.add(other.bucket()));
            default -> { }
        }
        return entries;
    }

    /** Each row's own name and the command the JSON rendering gives it. */
    private static List<String[]> callsOf(DiscoverResult result) {
        List<String[]> calls = new ArrayList<>();
        switch (result) {
            case DiscoverResult.ContainerRoster roster -> roster.containers()
                    .forEach(container -> calls.add(new String[] {container.name(), container.call()}));
            case DiscoverResult.PathGroups groups -> {
                groups.resources().forEach(resource -> addResourceCalls(calls, resource));
                groups.groups().forEach(group -> calls.add(new String[] {group.name(), group.call()}));
            }
            case DiscoverResult.ResourceList resources ->
                    resources.resources().forEach(resource -> addResourceCalls(calls, resource));
            case DiscoverResult.MethodList methods ->
                    methods.methods().forEach(method -> calls.add(new String[] {method.name(), method.call()}));
            case DiscoverResult.MixedListing mixed -> {
                mixed.resources().forEach(resource -> addResourceCalls(calls, resource));
                mixed.remote().forEach(method -> calls.add(new String[] {method.name(), method.call()}));
                mixed.normal().forEach(method -> calls.add(new String[] {method.name(), method.call()}));
            }
            case DiscoverResult.NoMatch noMatch -> noMatch.paths()
                    .forEach(alternative -> calls.add(new String[] {alternative.path(), alternative.call()}));
            case DiscoverResult.Owners owners -> owners.owners()
                    .forEach(owner -> calls.add(new String[] {owner.name(), owner.call()}));
            case DiscoverResult.EmptyBucket empty -> empty.elsewhere()
                    .forEach(other -> calls.add(new String[] {other.bucket(), other.call()}));
            default -> { }
        }
        return calls;
    }

    /** One row per accessor, named by its path and that accessor, with the row's path as the line it sits on. */
    private static void addResourceCalls(List<String[]> calls, DiscoverResult.ResourceList.Resource resource) {
        resource.calls().forEach((accessor, call) ->
                calls.add(new String[] {resource.path() + " " + accessor, call, resource.path()}));
    }

    private static String nextOf(DiscoverResult result) {
        return switch (result) {
            case DiscoverResult.ContainerRoster roster -> roster.next();
            case DiscoverResult.PathGroups groups -> groups.next();
            case DiscoverResult.ResourceList resources -> resources.next();
            case DiscoverResult.MethodList methods -> methods.next();
            case DiscoverResult.MixedListing mixed -> mixed.next();
            case DiscoverResult.NoMatch noMatch -> noMatch.available() == null ? noMatch.next() : null;
            case DiscoverResult.Owners owners -> owners.next();
            default -> null;
        };
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
