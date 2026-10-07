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
import io.ballerina.tools.discover.central.HttpTransport;
import io.ballerina.tools.discover.cli.Cli;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.symbols.Surface;
import io.ballerina.tools.discover.views.Containers;
import io.ballerina.tools.discover.views.Readme;
import io.ballerina.tools.discover.views.Types;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every command a document prints is RUN, and has to answer.
 *
 * <p>A pointer that cannot answer is worse than no pointer: following it loops or dead-ends. So this extracts every
 * {@code bal discover} command a bucket can print — read off every answer's own
 * {@code command}/{@code commands}/{@code next} fields — runs it through the real CLI against the recorded payload,
 * and requires exit 0 with something other than "nothing matched". A new pointer cannot be added wrong.
 *
 * <p>Two exclusions, both principled. A command containing an angle-bracket slot is a TEMPLATE — {@code <Name>} or
 * {@code <keyword>} is the grammar, not an argument — and a command naming a DIFFERENT package is a cross-package
 * edge, which by design is not followed and whose payload this fixture cannot serve. Both are asserted as shapes
 * rather than silently skipped, so an exclusion cannot become a hiding place.
 *
 * @since 0.1.0
 */
public class PointersTest {

    @DataProvider(name = "fixtures")
    public Object[][] fixtures() {
        return FixtureCorpus.fixtureRows();
    }

    private static HttpOptions centralFor(String slug) {
        String docs = FixtureCorpus.loadRawFixture(slug).toString();
        HttpTransport transport = FakeTransport.routing(url -> url.contains("/docs/")
                ? FakeTransport.ok(docs)
                : FakeTransport.ok("[\"" + FixtureCorpus.FIXTURE_VERSION.text() + "\"]"));
        return HttpOptions.builder()
                .transport(transport)
                .baseDelayMs(1)
                .sleeper(millis -> {
                    // No test here asserts wall-clock behaviour.
                })
                .build();
    }

    private static List<DiscoverResult> answersOf(LoadedPackage context) {
        List<DiscoverResult> answers = new ArrayList<>();
        for (Surface.Scope scope : Surface.Scope.values()) {
            answers.add(expect(Containers.render(context, scope, Containers.Options.bare())));
            answers.add(expect(Containers.render(context, scope, new Containers.Options(List.of(), "config", 1))));
            for (Surface.Container container : Surface.of(context.library(), scope)) {
                if (container.isModule()) {
                    continue;
                }
                answers.add(expect(Containers.render(context, scope,
                        new Containers.Options(List.of(container.name())))));
                answers.add(expect(Containers.render(context, scope,
                        new Containers.Options(List.of(container.name(), "zzznosuchmember")))));
                for (List<String> selector : narrowingSelectors(container)) {
                    answers.add(expect(Containers.render(context, scope, new Containers.Options(selector))));
                }
            }
        }

        answers.addAll(typeAnswers(context));
        answers.add(expect(Readme.render(context, Readme.Options.BARE)));
        answers.add(expect(Readme.render(context, new Readme.Options(null, "config", 1))));
        for (Readme.Chunk chunk : Readme.chunksOf(context)) {
            answers.add(expect(Readme.render(context, new Readme.Options(String.valueOf(chunk.number()), null, 1))));
        }
        return answers;
    }

    @Test
    public void theCommandsPastAClosureCeilingRunAndAnswer() {
        String slug = "ballerina__http";
        LoadedPackage context = FixtureCorpus.loadedFixture(slug);
        DiscoverResult.TypeDeclaration status = (DiscoverResult.TypeDeclaration) expect(
                Types.render(context, new Types.Options(List.of("StatusCodeResponse"), null, 1)));
        Assert.assertEquals(status.omittedNext(), "bal discover ballerina/http type");
        JsonObject roster = run(slug, centralFor(slug), status.omittedNext());
        Assert.assertEquals(roster.get("total").getAsInt(), Types.count(context));
        Assert.assertTrue(roster.has("next"), roster.toString());
        status.omitted().forEach(each -> Assert.assertEquals(
                run(slug, centralFor(slug), each.command()).get("name").getAsString(), each.name()));
    }

    private static List<DiscoverResult> typeAnswers(LoadedPackage context) {
        List<DiscoverResult> answers = new ArrayList<>();
        DiscoverResult roster = expect(Types.render(context, new Types.Options(List.of(), null, 1)));
        answers.add(roster);
        answers.add(expect(Types.render(context, new Types.Options(List.of(), "config", 1))));
        if (roster instanceof DiscoverResult.TypeRoster typed) {
            for (DiscoverResult.TypeRoster.Section section : typed.sections()) {
                section.entries().stream().limit(1).forEach(entry -> answers.add(
                        expect(Types.render(context, new Types.Options(List.of(entry.name()), null, 1)))));
            }
        }
        return answers;
    }

    private static List<String> commandsOf(LoadedPackage context) {
        List<String> commands = new ArrayList<>();
        answersOf(context).forEach(answer -> commands.addAll(commandsOf(answer)));
        return commands;
    }

    private static List<List<String>> narrowingSelectors(Surface.Container container) {
        List<List<String>> selectors = new ArrayList<>();
        container.memberNames().stream().findFirst().filter(name -> name.length() > 3)
                .ifPresent(name -> selectors.add(List.of(container.name(), name.substring(1, name.length() - 1))));
        container.operations().stream().findFirst().ifPresent(operation -> selectors.add(
                List.of(container.name(), operation.segments().get(0), operation.fn().accessor())));
        return selectors;
    }

    private static List<String> commandsOf(DiscoverResult result) {
        List<String> commands = new ArrayList<>();
        switch (result) {
            case DiscoverResult.BucketList bucketList ->
                    bucketList.submodules().forEach(submodule -> commands.add(submodule.command()));
            case DiscoverResult.ContainerRoster roster -> {
                roster.containers().forEach(entry -> commands.add(entry.command()));
                addIfPresent(commands, roster.next());
            }
            case DiscoverResult.PathGroups groups -> {
                groups.resources().forEach(resource -> commands.addAll(resource.commands().values()));
                groups.groups().forEach(group -> commands.add(group.command()));
                addIfPresent(commands, groups.next());
            }
            case DiscoverResult.ResourceList resources -> {
                resources.resources().forEach(resource -> commands.addAll(resource.commands().values()));
                addIfPresent(commands, resources.next());
            }
            case DiscoverResult.MethodList methods -> {
                methods.methods().forEach(method -> commands.add(method.command()));
                addIfPresent(commands, methods.next());
            }
            case DiscoverResult.Readme ignored -> {
                // No embedded command fields on the whole-readme/single-chunk answer.
            }
            case DiscoverResult.ReadmeChunks chunks -> {
                chunks.chunks().forEach(chunk -> commands.add(chunk.command()));
                addIfPresent(commands, chunks.next());
            }
            case DiscoverResult.Signature signature -> {
                signature.omitted().forEach(omitted -> commands.add(omitted.command()));
                addIfPresent(commands, signature.omittedNext());
                addForeign(commands, signature.foreign());
            }
            case DiscoverResult.MixedListing mixed -> {
                mixed.resources().forEach(resource -> commands.addAll(resource.commands().values()));
                mixed.remote().forEach(method -> commands.add(method.command()));
                mixed.normal().forEach(method -> commands.add(method.command()));
                addIfPresent(commands, mixed.next());
            }
            case DiscoverResult.NoMatch noMatch -> {
                noMatch.paths().forEach(alternative -> commands.add(alternative.command()));
                if (noMatch.available() != null) {
                    commands.addAll(commandsOf(noMatch.available()));
                }
                commands.add(noMatch.next());
            }
            case DiscoverResult.Owners owners -> {
                owners.owners().forEach(owner -> commands.add(owner.command()));
                addIfPresent(commands, owners.next());
            }
            case DiscoverResult.EmptyBucket empty ->
                    empty.elsewhere().forEach(other -> commands.add(other.command()));
            case DiscoverResult.TypeRoster roster -> {
                roster.sections().forEach(section ->
                        section.entries().stream().limit(2).forEach(entry -> commands.add(entry.command())));
                addIfPresent(commands, roster.next());
            }
            case DiscoverResult.TypeDeclaration declaration -> {
                declaration.omitted().forEach(omitted -> commands.add(omitted.command()));
                addIfPresent(commands, declaration.omittedNext());
                addForeign(commands, declaration.foreign());
            }
        }
        return commands;
    }

    private static void addForeign(List<String> commands, List<DiscoverResult.Foreign> foreign) {
        foreign.stream().map(DiscoverResult.Foreign::command)
                .filter(command -> command != null && !command.contains(" --module "))
                .forEach(commands::add);
    }

    private static void addIfPresent(List<String> commands, String command) {
        if (command != null) {
            commands.add(command);
        }
    }

    private static DiscoverResult expect(Result<DiscoverResult> view) {
        Assert.assertTrue(view.isOk(), view.isOk() ? "" : view.failure().describe());
        return view.value();
    }

    /** How many levels past the first answers a printed command is followed. */
    private static final int DEPTH = 2;

    /**
     * Bounds on the walk past the first answers, so a deep walk over github's 903 operations stays a unit test:
     * how many of one answer's own commands are followed, and how many commands one fixture runs past the first
     * level. Every command the first answers print is always run.
     */
    private static final int PER_ANSWER = 6;

    private static final int BUDGET = 80;

    @Test(dataProvider = "fixtures")
    public void everyCommandADocumentPrintsRunsAndAnswers(String slug) {
        LoadedPackage context = FixtureCorpus.loadedFixture(slug);
        String pkg = context.qualified().qualified();
        HttpOptions http = centralFor(slug);

        List<String> level = new ArrayList<>(new LinkedHashSet<>(commandsOf(context)));
        Set<String> seen = new LinkedHashSet<>();
        int ran = 0;
        int deep = 0;
        for (int depth = 0; depth <= DEPTH && !level.isEmpty(); depth++) {
            List<String> deeper = new ArrayList<>();
            for (String text : level) {
                if (!seen.add(text) || !runnable(slug, pkg, text)) {
                    continue;
                }
                if (depth > 0 && deep++ >= BUDGET) {
                    break;
                }
                ran++;
                List<String> printed = followed(slug, http, text);
                for (String command : printed) {
                    Assert.assertNotEquals(command, text, slug + ": `" + text + "` points back at itself");
                }
                deeper.addAll(printed.subList(0, Math.min(PER_ANSWER, printed.size())));
            }
            level = deeper;
        }
        Assert.assertTrue(ran > 0, slug + ": no document printed a runnable command");
    }

    /**
     * Every resource row's {@code commands}, one per accessor, opens exactly that path and accessor's signature — the
     * end of the drill-down, never another listing. A multi-accessor row is followed accessor by accessor, the
     * same as a single-accessor one.
     */
    @Test(dataProvider = "fixtures")
    public void everyResourceRowsCommandsOpenTheirOwnSignatures(String slug) {
        LoadedPackage context = FixtureCorpus.loadedFixture(slug);
        HttpOptions http = centralFor(slug);
        Set<String> seen = new LinkedHashSet<>();
        for (DiscoverResult answer : answersOf(context)) {
            for (DiscoverResult.ResourceList.Resource resource : resourcesOf(answer)) {
                Assert.assertEquals(List.copyOf(resource.commands().keySet()), resource.accessors(),
                        slug + ": " + resource);
                resource.commands().forEach((accessor, command) -> {
                    if (!seen.add(command)) {
                        return;
                    }
                    JsonObject signature = run(slug, http, command);
                    Assert.assertEquals(signature.has("declaration") ? signature.get("kind").getAsString() : null,
                            "resource", slug + ": `" + command + "` is not a signature:\n" + signature);
                    Assert.assertEquals(signature.get("path").getAsString(), resource.path(), command);
                    Assert.assertEquals(signature.get("accessor").getAsString(), accessor, command);
                });
            }
        }
    }

    /**
     * Every method row's {@code command} opens exactly that method's signature, never another listing: the same name,
     * on the same container, and — under a mixed listing's section — the call form that section names.
     */
    @Test(dataProvider = "fixtures")
    public void everyMethodRowsCommandOpensItsOwnSignature(String slug) {
        LoadedPackage context = FixtureCorpus.loadedFixture(slug);
        HttpOptions http = centralFor(slug);
        Set<String> seen = new LinkedHashSet<>();
        for (DiscoverResult answer : answersOf(context)) {
            for (Row row : methodRowsOf(answer)) {
                String command = row.method().command();
                if (!seen.add(command)) {
                    continue;
                }
                JsonObject signature = run(slug, http, command);
                Assert.assertTrue(signature.has("declaration"), slug + ": `" + command + "` is not a signature:\n"
                        + signature);
                Assert.assertEquals(signature.get("name").getAsString(), row.method().name(), command);
                Assert.assertEquals(signature.has("container") ? signature.get("container").getAsString() : null,
                        row.container(), command);
                if (row.form() != null) {
                    Assert.assertEquals(signature.get("form").getAsString(), row.form(), command);
                }
            }
        }
    }

    /**
     * One method row and where it was listed.
     *
     * @param method the method
     * @param container the listing's container, {@code null} for module-level functions
     * @param form the call form its section names, or {@code null} for a single-form listing
     */
    private record Row(DiscoverResult.Method method, String container, String form) { }

    private static List<Row> methodRowsOf(DiscoverResult answer) {
        List<Row> rows = new ArrayList<>();
        switch (answer) {
            case DiscoverResult.MethodList methods ->
                    methods.methods().forEach(method -> rows.add(new Row(method, methods.container(), null)));
            case DiscoverResult.MixedListing mixed -> {
                mixed.remote().forEach(method -> rows.add(new Row(method, mixed.container(), "->")));
                mixed.normal().forEach(method -> rows.add(new Row(method, mixed.container(), ".")));
            }
            case DiscoverResult.NoMatch noMatch -> {
                if (noMatch.available() != null) {
                    rows.addAll(methodRowsOf(noMatch.available()));
                }
            }
            default -> { }
        }
        return rows;
    }

    private static List<DiscoverResult.ResourceList.Resource> resourcesOf(DiscoverResult answer) {
        return switch (answer) {
            case DiscoverResult.PathGroups groups -> groups.resources();
            case DiscoverResult.ResourceList resources -> resources.resources();
            case DiscoverResult.MixedListing mixed -> mixed.resources();
            case DiscoverResult.NoMatch noMatch -> noMatch.available() == null ? List.of()
                    : resourcesOf(noMatch.available());
            default -> List.of();
        };
    }

    private static boolean runnable(String slug, String pkg, String text) {
        if (text.contains("<") || text.contains(">")) {
            // A template is the grammar rather than an argument, so it has to LOOK like one: every angle-bracket
            // slot is a placeholder name, never a value that leaked out of a rendering.
            Assert.assertTrue(Pattern.compile("<[A-Za-z][^>]*>").matcher(text).find(),
                    slug + ": `" + text + "` has an angle bracket that is not a slot");
            return false;
        }
        if (!text.contains(" " + pkg)) {
            // A cross-package edge, so it has to name a real coordinate and this package must not be it.
            Assert.assertTrue(Pattern.compile("bal discover [\\w.]+/[\\w.]+ ").matcher(text).find(),
                    slug + ": `" + text + "` names no package coordinate");
            return false;
        }
        return true;
    }

    private static JsonObject run(String slug, HttpOptions http, String text) {
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        int code = Cli.run(argv(text), new Cli.Streams(out::append, err::append), http);
        Assert.assertEquals(code, 0, slug + ": `" + text + "` failed with " + err);
        Assert.assertFalse(out.toString().isBlank(), slug + ": `" + text + "` answered with nothing");
        Assert.assertEquals(out.toString().strip().lines().count(), 1L, slug + ": `" + text + "` spans lines");
        JsonObject answer = JsonParser.parseString(out.toString()).getAsJsonObject();
        // Exit 0 is not enough on its own: "nothing matched" is an exit-0 answer too, and a pointer that lands
        // on one is exactly the loop this test exists to catch.
        Assert.assertFalse(answer.has("candidates"), slug + ": `" + text + "` matched nothing:\n" + out);
        return answer;
    }

    private static List<String> followed(String slug, HttpOptions http, String text) {
        JsonObject answer = run(slug, http, text);
        List<String> printed = new ArrayList<>();
        collectCommands(answer, printed);
        return printed;
    }

    private static void collectCommands(JsonElement element, List<String> into) {
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(child -> collectCommands(child, into));
        } else if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> field : element.getAsJsonObject().entrySet()) {
                if (field.getKey().equals("foreign")) {
                    continue;
                }
                if ((field.getKey().equals("command") || field.getKey().equals("next")
                        || field.getKey().equals("omittedNext"))
                        && field.getValue().isJsonPrimitive()) {
                    into.add(field.getValue().getAsString());
                } else if (field.getKey().equals("commands") && field.getValue().isJsonObject()) {
                    field.getValue().getAsJsonObject().entrySet()
                            .forEach(command -> into.add(command.getValue().getAsString()));
                } else {
                    collectCommands(field.getValue(), into);
                }
            }
        }
    }

    private static List<String> argv(String command) {
        List<String> tokens = new ArrayList<>();
        Matcher token = Pattern.compile("'([^']*)'|\"([^\"]*)\"|(\\S+)").matcher(command);
        while (token.find()) {
            tokens.add(token.group(1) != null ? token.group(1)
                    : token.group(2) != null ? token.group(2) : token.group(3));
        }
        // `bal discover` is the launcher, not an argument.
        Assert.assertEquals(tokens.subList(0, 2), List.of("bal", "discover"), command);
        return tokens.subList(2, tokens.size());
    }
}
