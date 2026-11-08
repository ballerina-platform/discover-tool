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

package io.ballerina.tools.discover.render;

import io.ballerina.tools.discover.Command;
import io.ballerina.tools.discover.Coordinate;
import io.ballerina.tools.discover.Texts;
import io.ballerina.tools.discover.model.ServiceHandlers;
import io.ballerina.tools.discover.symbols.Surface;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * {@link DiscoverResult} → the human-oriented text {@code --output-format text} prints at an interactive terminal.
 *
 * <p>Every answer has the same frame: a header naming where the caller is ({@code ballerinax/github · client ·
 * Client}) and a count line, then the entries one per line in aligned columns, grouped under plain headings, then
 * a footer holding the notes, how much was left out and the {@code Next:} commands. Where the JSON rendering
 * gives every row its own {@code command} (a resource row its {@code commands}, one per accessor), a large
 * listing prints the shared shape of those commands once in the footer instead; a row whose command does not fit
 * that shape (a name that needs shell quoting, a group opened without the listing's accessor) carries its own.
 * No colour and no wrapping, so the output stays as greppable as the JSON.
 *
 * @since 0.1.0
 */
public final class TextRenderer {

    private static final String INDENT = "  ";
    private static final String SEPARATOR = " · ";
    private static final String HANDLERS_HEADING = "Methods to implement";
    private static final String RULES_HEADING = "Rules";
    private static final String REPEATABLE = "any number";
    private static final String REQUIRED = "required";
    private static final String OPTIONAL = "optional";
    private static final String DOCUMENTED = "that mention it only in their documentation";

    private static final String NAME_PLACEHOLDER = "<name>";

    private static final String CONTAINER_PLACEHOLDER = "<container>";

    /** How a search group's rows read in the shared command shape its {@code Next:} line prints. */
    private static final Map<DiscoverResult.Search.Kind, List<String>> SEARCH_PLACEHOLDERS = Map.of(
            DiscoverResult.Search.Kind.RESOURCES, List.of(CONTAINER_PLACEHOLDER, "<accessor>", "<path>"),
            DiscoverResult.Search.Kind.REMOTE, List.of(CONTAINER_PLACEHOLDER, NAME_PLACEHOLDER),
            DiscoverResult.Search.Kind.METHODS, List.of(CONTAINER_PLACEHOLDER, NAME_PLACEHOLDER),
            DiscoverResult.Search.Kind.README, List.of("<n>"));

    private static final List<String> NAME_PLACEHOLDERS = List.of(NAME_PLACEHOLDER);

    private static final String FILTER_PLACEHOLDER = "<keyword>";

    private static final String QUERY_PLACEHOLDER = "<query>";

    private static final String ROW_PLACEHOLDER = "<row>";

    private static final String ACCESSOR_PLACEHOLDER = "<accessor>";

    private static final String BUCKET_PLACEHOLDER = "<noun>";

    private static final String NEXT = "Next: ";

    private TextRenderer() {
    }

    /**
     * Where an answer was asked from — the header line. None of it is in a {@link DiscoverResult}, because the
     * JSON rendering answers a caller who already holds the command it ran.
     *
     * @param pkg the package, {@code org/name}, or {@code null} when unknown
     * @param module the {@code --module} submodule, or {@code null}
     * @param trail the noun and its names, as typed
     * @param filter the {@code --filter} keyword, or {@code null}
     * @param pin the version the caller wrote in the coordinate, or {@code null}
     * @param version the version that was read, shown after the package, or {@code null} when unknown
     */
    public record Context(String pkg, String module, List<String> trail, String filter, String pin,
            String version) {

        public static final Context NONE = new Context(null, null, List.of(), null, null, null);

        public Context {
            trail = trail == null ? List.of() : List.copyOf(trail);
        }

        private String bucket() {
            return trail.isEmpty() ? null : trail.get(0);
        }
    }

    /** {@code result} with no header line, for a caller that has no command to echo. */
    public static String render(DiscoverResult result) {
        return render(result, Context.NONE);
    }

    /**
     * {@code result} under a header naming where it was asked from. A whole readme follows the header verbatim.
     */
    public static String render(DiscoverResult result, Context where) {
        if (result instanceof DiscoverResult.Readme readme && readme.section() == null) {
            String header = header(where, null);
            return header == null ? readme.markdown() : header + "\n\n" + readme.markdown();
        }
        Layout layout = new Layout();
        layout.top(header(where, containerOf(result)));
        fill(layout, result, where);
        if (layout.hasLaterPages && where.filter() == null && where.pkg() != null) {
            layout.next(filterCommand(result));
        }
        return layout.render();
    }

    private static String filterCommand(DiscoverResult result) {
        String next = switch (result) {
            case DiscoverResult.ContainerRoster roster -> roster.next();
            case DiscoverResult.PathGroups groups -> groups.next();
            case DiscoverResult.ResourceList resources -> resources.next();
            case DiscoverResult.MethodList methods -> methods.next();
            case DiscoverResult.MixedListing mixed -> mixed.next();
            case DiscoverResult.TypeRoster roster -> roster.next();
            default -> null;
        };
        Command turned = next == null ? null : Command.parse(next).orElse(null);
        return turned == null || turned.page() == Command.FIRST_PAGE
                ? null
                : turned.paged(Command.FIRST_PAGE).filtered(Command.placeholder(FILTER_PLACEHOLDER)).text();
    }

    private static void fill(Layout layout, DiscoverResult result, Context where) {
        switch (result) {
            case DiscoverResult.Overview overview -> overview(layout, overview, where);
            case DiscoverResult.ContainerRoster roster -> containerRoster(layout, roster, where);
            case DiscoverResult.PathGroups groups -> pathGroups(layout, groups);
            case DiscoverResult.ResourceList resources -> resourceList(layout, resources);
            case DiscoverResult.MethodList methods -> methodList(layout, methods);
            case DiscoverResult.ServiceHandlerList handlers -> serviceHandlers(layout, handlers);
            case DiscoverResult.ServiceExamples examples -> serviceExamples(layout, examples);
            case DiscoverResult.Readme readme -> readmeSection(layout, readme);
            case DiscoverResult.ReadmeSections sections -> readmeSections(layout, sections);
            case DiscoverResult.Signature signature -> signature(layout, signature);
            case DiscoverResult.MixedListing mixed -> mixedListing(layout, mixed);
            case DiscoverResult.EmptyBucket empty -> emptyBucket(layout, empty, where);
            case DiscoverResult.TypeRoster roster -> typeRoster(layout, roster);
            case DiscoverResult.TypeDeclaration declaration -> typeDeclaration(layout, declaration);
            case DiscoverResult.Search search -> search(layout, search);
            case DiscoverResult.CentralSearch search -> centralSearch(layout, search);
        }
    }

    private static void overview(Layout layout, DiscoverResult.Overview overview, Context where) {
        List<String> lines = new ArrayList<>();
        List<String> patterns = new ArrayList<>();
        for (DiscoverResult.Overview.Section section : overview.sections()) {
            Surface.Scope scope = Surface.Scope.ofCommandNoun(section.bucket()).orElse(Surface.Scope.MODULE);
            lines.add(capitalised(scope.plural()) + " (" + section.total() + ")");
            List<DiscoverResult.Overview.Row> rows = section.rows();
            Drill drill = Drill.of(rows.stream().map(DiscoverResult.Overview.Row::name).toList(),
                    rows.stream().map(DiscoverResult.Overview.Row::command).toList(), NAME_PLACEHOLDER);
            TextTable table = scope == Surface.Scope.MODULE
                    ? new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT)
                    : new TextTable(TextTable.Column.LEFT,
                            TextTable.Column.count("resource method", "resource methods"),
                            TextTable.Column.count("remote method", "remote methods"),
                            TextTable.Column.count("method", "methods"),
                            TextTable.Column.LEFT);
            for (int i = 0; i < rows.size(); i++) {
                DiscoverResult.Overview.Row row = rows.get(i);
                if (scope == Surface.Scope.MODULE) {
                    table.row(row.name(), drill.explicit(i));
                } else {
                    table.row(row.name(), String.valueOf(row.resources()), String.valueOf(row.remote()),
                            String.valueOf(row.normal()), drill.explicit(i));
                }
            }
            lines.addAll(table.lines(INDENT));
            if (rows.size() < section.total()) {
                lines.add(INDENT + "... " + (section.total() - rows.size()) + " more: " + section.command());
            }
            if (overview.members() != null && scope == Surface.Scope.CLIENT) {
                lines.addAll(inlined(layout, overview.members()));
            } else if (drill.pattern() != null) {
                patterns.add(drill.pattern());
            }
        }
        TextTable summary = new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT, TextTable.Column.LEFT);
        if (overview.types() != null) {
            List<String> parts = new ArrayList<>();
            overview.types().counts().forEach((kind, count) ->
                    parts.add(count + " " + (count == 1 ? kind.noun() : kind.plural())));
            summary.row("Types", overview.types().command(), String.join(", ", parts));
        }
        if (overview.readme() != null) {
            summary.row("Readme", overview.readme().command(),
                    counted(overview.readme().sections(), "section", "sections"));
        }
        lines.addAll(summary.lines(""));
        layout.block(lines.isEmpty() ? List.of("Nothing to list.") : lines);
        if (!overview.submodules().isEmpty()) {
            TextTable submodules = new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT, TextTable.Column.LEFT);
            overview.submodules().forEach(submodule ->
                    submodules.row(submodule.name(), submodule.command(), submodule.summary()));
            layout.section("Submodules", submodules);
        }
        Command.Target pkg = where.pkg() == null
                ? null
                : new Command.Target(Coordinate.text(where.pkg(), where.pin()), where.module());
        boolean foldable = pkg != null && patterns.size() > 1 && patterns.stream().allMatch(pattern ->
                overview.sections().stream().anyMatch(section ->
                        pattern.equals(namePattern(section.bucket(), pkg))));
        if (foldable) {
            layout.next(namePattern(BUCKET_PLACEHOLDER, pkg));
        } else {
            patterns.forEach(layout::next);
        }
        layout.inlinedNext.forEach(layout::next);
        if (pkg != null && !overview.sections().isEmpty()) {
            layout.next(Command.of(Command.SEARCH, Command.placeholder(QUERY_PLACEHOLDER)).in(pkg).text());
        }
    }

    private static String namePattern(String noun, Command.Target pkg) {
        return Command.of(noun, Command.placeholder(NAME_PLACEHOLDER)).in(pkg).text();
    }

    // A listing opened in place under its row: its headings one level below the row, their entries below them, its
    // count of what is left after them, and its Next: commands among the overview's own.
    private static List<String> inlined(Layout layout, DiscoverResult listing) {
        Layout inner = new Layout();
        fill(inner, listing, Context.NONE);
        List<String> lines = new ArrayList<>();
        inner.blocks.forEach(block -> lines.addAll(block.get(0).startsWith(INDENT)
                ? indented(block)
                : indented(indented(block))));
        inner.more.forEach(line -> lines.add(INDENT + INDENT + line));
        inner.next.forEach(line -> layout.inlinedNext.add(line.substring(NEXT.length())));
        return lines;
    }

    private static void containerRoster(Layout layout, DiscoverResult.ContainerRoster roster, Context where) {
        String[] noun = switch (Surface.Scope.ofCommandNoun(where.bucket()).orElse(Surface.Scope.MODULE)) {
            case CLIENT -> new String[] {"client", "clients"};
            case CLASS -> new String[] {"class", "classes"};
            case SERVICE -> new String[] {"service type", "service types"};
            case MODULE -> new String[] {"container", "containers"};
        };
        layout.top(counted(roster.total(), noun[0], noun[1]));

        List<DiscoverResult.ContainerRoster.Container> containers = roster.containers();
        List<DiscoverResult.ContainerRoster.NotAttachable> notAttachable = roster.notAttachable();
        List<String> names = new ArrayList<>();
        List<String> commands = new ArrayList<>();
        containers.forEach(container -> {
            names.add(container.name());
            commands.add(container.command());
        });
        notAttachable.forEach(type -> {
            names.add(type.name());
            commands.add(type.command());
        });
        Drill drill = Drill.of(names, commands, NAME_PLACEHOLDER);
        Map<String, List<Integer>> byListener = new LinkedHashMap<>();
        for (int i = 0; i < containers.size(); i++) {
            DiscoverResult.ContainerRoster.Container container = containers.get(i);
            String heading = container.listener() == null || container.unconfirmed() == null
                    ? container.listener()
                    : container.listener() + " — unverified (" + container.unconfirmed() + ")";
            byListener.computeIfAbsent(heading, key -> new ArrayList<>()).add(i);
        }
        boolean paired = !byListener.containsKey(null) || byListener.size() > 1;
        Comparator<Map.Entry<String, List<Integer>>> byListenerThenConfirmed = Comparator
                .comparing((Map.Entry<String, List<Integer>> group) ->
                        containers.get(group.getValue().get(0)).listener(),
                        Comparator.nullsLast(Texts.LOCALE_ORDER))
                .thenComparing(group -> containers.get(group.getValue().get(0)).unconfirmed() != null);
        byListener.entrySet().stream().sorted(byListenerThenConfirmed).forEach(group -> {
            String listener = group.getKey();
            List<Integer> rows = group.getValue();
            TextTable table = new TextTable(TextTable.Column.LEFT,
                    TextTable.Column.count("resource method", "resource methods"),
                    TextTable.Column.count("remote method", "remote methods"),
                    TextTable.Column.count("method", "methods"),
                    TextTable.Column.LEFT);
            for (int row : rows) {
                DiscoverResult.ContainerRoster.Container container = containers.get(row);
                table.row(container.name(), String.valueOf(container.resources()),
                        String.valueOf(container.remote()), String.valueOf(container.normal()),
                        drill.explicit(row));
            }
            if (paired) {
                layout.section(listener == null ? "No listener" : listener, table);
            } else {
                layout.block(table.lines(INDENT));
            }
        });
        TextTable unattachable = new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT);
        for (int i = 0; i < notAttachable.size(); i++) {
            unattachable.row(notAttachable.get(i).name(), drill.explicit(containers.size() + i));
        }
        int notAttachableTotal = roster.notAttachableTotal();
        if (notAttachable.isEmpty() && notAttachableTotal > 0) {
            layout.block(List.of("Not attachable to a listener: " + notAttachableTotal + " more, on later pages"));
        }
        layout.section("Not attachable to a listener" + (notAttachable.size() < notAttachableTotal
                ? " (" + notAttachable.size() + " of " + notAttachableTotal + ")" : ""), unattachable);
        layout.more(remaining(containers.size() + notAttachable.size(), roster.total(), roster.paging()),
                roster.paging());
        layout.next(drill.pattern());
        layout.next(roster.next());
    }

    private static void pathGroups(Layout layout, DiscoverResult.PathGroups groups) {
        List<DiscoverResult.ResourceList.Resource> here = groups.resources();
        DiscoverResult.PathGroups.Counts counts = groups.counts();
        if (counts.groups() == 0) {
            layout.top(counted(counts.resources(), "resource path", "resource paths"));
        } else if (counts.resources() == 0) {
            layout.top(counted(counts.groups(), "path group", "path groups"));
        } else {
            layout.top(counted(counts.resources(), "resource path", "resource paths") + " here, "
                    + counted(counts.groups(), "path group", "path groups"));
        }

        Drill hereDrill = resourceDrill(here);
        layout.section("Paths at this level", resourceTable(here, hereDrill));
        Drill groupDrill = Drill.of(groups.groups().stream().map(DiscoverResult.PathGroups.Group::name).toList(),
                groups.groups().stream().map(DiscoverResult.PathGroups.Group::command).toList(), "<group>");
        TextTable table = new TextTable(TextTable.Column.LEFT, TextTable.Column.RIGHT, TextTable.Column.LEFT);
        for (int i = 0; i < groups.groups().size(); i++) {
            DiscoverResult.PathGroups.Group group = groups.groups().get(i);
            table.row(group.name(), String.valueOf(group.count()), groupDrill.explicit(i));
        }
        layout.section("Path groups (resource methods in each)", table);

        layout.note(groups.note());
        layout.more(remaining(here.size() + groups.groups().size(), groups.total(), groups.paging()),
                groups.paging());
        layout.next(hereDrill.pattern());
        layout.next(groupDrill.pattern());
        layout.next(groups.next());
    }

    private static void resourceList(Layout layout, DiscoverResult.ResourceList resources) {
        layout.top(counted(resources.total(), "resource path", "resource paths"));
        Drill drill = resourceDrill(resources.resources());
        layout.block(resourceTable(resources.resources(), drill).lines(INDENT));
        List<String> documentedDrill = documented(layout, resources.documented());
        layout.note(resources.note());
        layout.more(remaining(resources.shown(), resources.total(), resources.paging()), resources.paging(),
                onlyDocumented(resources.total()));
        layout.next(drill.pattern());
        documentedDrill.forEach(layout::next);
        layout.next(resources.next());
    }

    private static void methodList(Layout layout, DiscoverResult.MethodList methods) {
        layout.top(methods.container() == null
                ? counted(methods.total(), "function", "functions")
                : methods.remote() ? counted(methods.total(), "remote method", "remote methods")
                        : counted(methods.total(), "method", "methods"));
        Drill drill = methodDrill(methods.methods());
        layout.block(methodTable(methods.methods(), drill).lines(INDENT));
        List<String> documentedDrill = documented(layout, methods.documented());
        layout.note(methods.note());
        layout.more(remaining(methods.shown(), methods.total(), methods.paging()), methods.paging(),
                onlyDocumented(methods.total()));
        layout.next(drill.pattern());
        documentedDrill.forEach(layout::next);
        layout.next(methods.next());
    }

    private static void serviceHandlers(Layout layout, DiscoverResult.ServiceHandlerList list) {
        layout.top(HANDLERS_HEADING + " (" + list.handlers().size() + ")");
        for (ServiceHandlers.Handler handler : list.handlers()) {
            List<String> lines = new ArrayList<>();
            lines.add(handler.signature());
            String presence = presence(handler.repeatable(), handler.required());
            lines.add(INDENT + (handler.name() == null ? handler.id() + ", " + presence : presence));
            if (handler.deprecated() != null) {
                lines.add(INDENT + "Deprecated: " + handler.deprecated());
            }
            if (handler.doc() != null && !handler.doc().isBlank()) {
                lines.add(INDENT + handler.doc().strip());
            }
            for (ServiceHandlers.Param param : handler.params()) {
                lines.add(INDENT + (param.name() == null ? "<name>" : param.name())
                        + " (" + presence(param.repeatable(), param.required()) + ")"
                        + (param.doc() == null || param.doc().isBlank() ? "" : ": " + param.doc().strip()));
            }
            layout.block(lines);
        }
        if (!list.rules().isEmpty()) {
            List<String> lines = new ArrayList<>();
            lines.add(RULES_HEADING);
            list.rules().stream().map(TextRenderer::ruleText).filter(text -> !text.isEmpty())
                    .forEach(text -> lines.add(INDENT + text));
            if (lines.size() > 1) {
                layout.block(lines);
            }
        }
        layout.note(list.note());
    }

    private static void serviceExamples(Layout layout, DiscoverResult.ServiceExamples examples) {
        layout.top(examples.container() + " declares no methods; a service of this type declares its own resource "
                + "or remote methods.");
        if (!examples.description().isBlank()) {
            layout.block(List.of(examples.description().strip()));
        }
        TextTable sections = new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT, TextTable.Column.LEFT);
        examples.sections().forEach(section ->
                sections.row(String.valueOf(section.number()), section.title(), section.command()));
        layout.section("Readme sections that declare one", sections);
        layout.note(examples.note());
        layout.next(examples.next());
    }

    private static String presence(boolean repeatable, boolean required) {
        return repeatable ? REPEATABLE : required ? REQUIRED : OPTIONAL;
    }

    private static String ruleText(ServiceHandlers.Rule rule) {
        String requirement = rule.requirement() == null
                ? null
                : capitalised(rule.requirement()) + ": " + String.join(", ", rule.handlers());
        if (requirement == null) {
            return rule.message() == null ? "" : rule.message();
        }
        return rule.message() == null ? requirement : requirement + " — " + rule.message();
    }

    private static String capitalised(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }


    private static void readmeSection(Layout layout, DiscoverResult.Readme readme) {
        layout.top("Section " + readme.section() + " of " + readme.of() + ": " + readme.title());
        layout.block(List.of(readme.markdown()));
    }

    private static void readmeSections(Layout layout, DiscoverResult.ReadmeSections sections) {
        layout.top(sections.matching() ? counted(sections.total(), "matching section", "matching sections")
                : counted(sections.total(), "section", "sections"));
        List<DiscoverResult.ReadmeSections.Section> rows = sections.sections();
        Drill drill = Drill.of(rows.stream().map(section -> String.valueOf(section.number())).toList(),
                rows.stream().map(DiscoverResult.ReadmeSections.Section::command).toList(), "<n>");
        TextTable table = new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT,
                TextTable.Column.count("line", "lines"), TextTable.Column.LEFT);
        for (int i = 0; i < rows.size(); i++) {
            DiscoverResult.ReadmeSections.Section section = rows.get(i);
            table.row(String.valueOf(section.number()), section.title(), String.valueOf(section.lines()),
                    drill.explicit(i));
        }
        layout.block(table.lines(INDENT));
        layout.more(remaining(rows.size(), sections.total(), sections.paging()), sections.paging());
        layout.next(drill.pattern());
        layout.next(sections.next());
    }

    private static void signature(Layout layout, DiscoverResult.Signature signature) {
        layout.block(List.of(signature.declaration()));
        if (signature.create() != null) {
            List<String> create = new ArrayList<>(List.of("Create it", INDENT + signature.create().code()));
            Stream.of(signature.create().init(), signature.create().command()).filter(Objects::nonNull)
                    .forEach(line -> create.add(INDENT + line));
            layout.block(create);
        }
        List<DiscoverResult.Signature.Type> types = signature.types();
        for (int i = 0; i < types.size(); i++) {
            List<String> block = new ArrayList<>();
            if (i == 0) {
                block.add("Types it uses (" + types.size() + ")");
            }
            block.addAll(indented(types.get(i).declaration().lines().toList()));
            layout.block(block);
        }
        closureTail(layout, signature.omitted(), signature.foreign());
        TextTable callers = new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT, TextTable.Column.LEFT);
        signature.callers().forEach(section ->
                callers.row(String.valueOf(section.number()), section.title(), section.command()));
        layout.section("Readme sections that call it", callers);
        List<String> documentedDrill = documented(layout, signature.documented());
        layout.note(signature.note());
        layout.more(remaining(signature.documented().names().size(), signature.documented().total(),
                signature.paging()), signature.paging(), DOCUMENTED);
        documentedDrill.forEach(layout::next);
        layout.next(signature.next());
    }

    private static void mixedListing(Layout layout, DiscoverResult.MixedListing mixed) {
        DiscoverResult.MixedListing.Counts counts = mixed.counts();
        List<String> parts = new ArrayList<>();
        if (counts.resources() > 0) {
            parts.add(counted(counts.resources(), "resource method", "resource methods"));
        }
        if (counts.remote() > 0) {
            parts.add(counted(counts.remote(), "remote method", "remote methods"));
        }
        if (counts.normal() > 0) {
            parts.add(counted(counts.normal(), "method", "methods"));
        }
        layout.top(String.join(", ", parts));
        Drill drill = resourceDrill(mixed.resources());
        layout.section("Resource methods (->)", resourceTable(mixed.resources(), drill));
        List<DiscoverResult.Method> methods = new ArrayList<>(mixed.remote());
        methods.addAll(mixed.normal());
        Drill methodDrill = methodDrill(methods);
        layout.section("Remote methods (->)", methodTable(mixed.remote(), methodDrill, 0));
        layout.section("Methods (.)", methodTable(mixed.normal(), methodDrill, mixed.remote().size()));
        List<String> documentedDrill = documented(layout, mixed.documented());
        layout.note(mixed.note());
        layout.more(remaining(mixed.shown(), mixed.total(), mixed.paging()), mixed.paging());
        layout.next(drill.pattern());
        layout.next(methodDrill.pattern());
        documentedDrill.forEach(layout::next);
        layout.next(mixed.next());
    }

    private static void emptyBucket(Layout layout, DiscoverResult.EmptyBucket empty, Context where) {
        String plural = Surface.Scope.ofCommandNoun(empty.bucket()).map(Surface.Scope::plural)
                .orElse(empty.bucket() + "s");
        layout.top((where.pkg() == null ? "This package" : where.pkg()) + " has no " + plural + ".");
        TextTable table = new TextTable(TextTable.Column.LEFT, TextTable.Column.RIGHT, TextTable.Column.LEFT);
        empty.elsewhere().forEach(other -> table.row(other.bucket(), String.valueOf(other.count()), other.command()));
        layout.section("Elsewhere in the package", table);
    }

    private static void typeRoster(Layout layout, DiscoverResult.TypeRoster roster) {
        List<String> parts = new ArrayList<>();
        roster.counts().forEach((kind, count) -> {
            if (count > 0) {
                parts.add(count + " " + (count == 1 ? kind.noun() : kind.plural()));
            }
        });
        layout.top(parts.isEmpty() ? "No types." : String.join(", ", parts));
        List<DiscoverResult.Method> all = roster.sections().stream()
                .flatMap(section -> section.entries().stream())
                .toList();
        Drill drill = methodDrill(all);
        int offset = 0;
        for (DiscoverResult.TypeRoster.Section section : roster.sections()) {
            String plural = section.kind().plural();
            layout.section(Character.toUpperCase(plural.charAt(0)) + plural.substring(1),
                    methodTable(section.entries(), drill, offset));
            offset += section.entries().size();
        }
        List<String> documentedDrill = documented(layout, roster.documented());
        layout.more(remaining(roster.shown(), roster.total(), roster.paging()), roster.paging(),
                onlyDocumented(roster.total()));
        layout.next(drill.pattern());
        documentedDrill.forEach(layout::next);
        layout.next(roster.next());
    }

    private static void search(Layout layout, DiscoverResult.Search search) {
        if (search.total() == 0) {
            layout.top("No name in this package holds " + String.join(" and ", search.keywords().stream()
                    .map(keyword -> "'" + keyword + "'").toList()) + ".");
            layout.next(search.overview());
            return;
        }
        List<String> parts = new ArrayList<>();
        search.counts().forEach((kind, count) -> parts.add(counted(count, kind.noun(), kind.plural())));
        layout.top(String.join(", ", parts));
        for (DiscoverResult.Search.Group group : search.groups()) {
            List<DiscoverResult.Search.Hit> hits = group.hits();
            List<List<String>> literals = new ArrayList<>();
            TextTable table = switch (group.kind()) {
                case RESOURCES -> new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT, TextTable.Column.LEFT,
                        TextTable.Column.LEFT);
                case REMOTE, METHODS, README, TYPES -> new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT,
                        TextTable.Column.LEFT);
                default -> new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT);
            };
            for (DiscoverResult.Search.Hit hit : hits) {
                literals.add(switch (group.kind()) {
                    case RESOURCES -> List.of(hit.container(), hit.detail(), hit.name());
                    case REMOTE, METHODS -> List.of(hit.container(), hit.name());
                    case README -> List.of(String.valueOf(hit.number()));
                    default -> List.of(hit.name());
                });
            }
            Drill drill = Drill.ofParts(literals, hits.stream().map(DiscoverResult.Search.Hit::command).toList(),
                    SEARCH_PLACEHOLDERS.getOrDefault(group.kind(), NAME_PLACEHOLDERS));
            for (int i = 0; i < hits.size(); i++) {
                DiscoverResult.Search.Hit hit = hits.get(i);
                switch (group.kind()) {
                    case RESOURCES -> table.row(hit.container(), hit.name(), hit.detail(), drill.explicit(i));
                    case REMOTE, METHODS -> table.row(hit.container(), hit.name(), drill.explicit(i));
                    case README -> table.row(String.valueOf(hit.number()), hit.name(), drill.explicit(i));
                    case TYPES -> table.row(hit.name(), hit.detail(), drill.explicit(i));
                    default -> table.row(hit.name(), drill.explicit(i));
                }
            }
            layout.section(capitalised(group.kind().plural()), table);
            layout.next(drill.pattern());
        }
        layout.more(remaining(search.shown(), search.total(), search.paging()), search.paging());
        layout.next(search.next());
    }

    private static void centralSearch(Layout layout, DiscoverResult.CentralSearch search) {
        if (search.packagesTotal() == 0 && search.symbolsTotal() == 0) {
            layout.top("Central matches no package or symbol.");
            return;
        }
        layout.top(counted(search.packagesTotal(), "package", "packages") + ", "
                + counted(search.symbolsTotal(), "symbol", "symbols"));
        List<String> packageRows = search.packages().stream()
                .map(DiscoverResult.CentralSearch.PackageHit::arguments)
                .toList();
        TextTable packageTable = new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT);
        for (int i = 0; i < packageRows.size(); i++) {
            packageTable.row(packageRows.get(i), search.packages().get(i).summary());
        }
        layout.section(shownOf("Packages", search.packages().size(), search.packagesTotal()), packageTable);
        List<String> symbolRows = search.symbols().stream()
                .map(DiscoverResult.CentralSearch.SymbolHit::arguments)
                .toList();
        TextTable symbolTable = new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT);
        for (int i = 0; i < symbolRows.size(); i++) {
            symbolTable.row(search.symbols().get(i).kind(), symbolRows.get(i));
        }
        layout.section(shownOf("Symbols", search.symbols().size(), search.symbolsTotal()), symbolTable);
        layout.more(remaining(search.packages().size() + search.symbols().size(),
                search.packagesTotal() + search.symbolsTotal(), search.paging()), search.paging());
        if (!packageRows.isEmpty() || !symbolRows.isEmpty()) {
            layout.next(Command.PREFIX + ROW_PLACEHOLDER);
        }
        layout.next(search.next());
    }

    private static String shownOf(String heading, int shown, int total) {
        return heading + (shown < total ? " (" + shown + " of " + total + ")" : " (" + total + ")");
    }

    private static void typeDeclaration(Layout layout, DiscoverResult.TypeDeclaration declaration) {
        layout.block(List.of(declaration.declaration()));
        List<DiscoverResult.Signature.Type> types = declaration.types();
        for (int i = 0; i < types.size(); i++) {
            List<String> block = new ArrayList<>();
            if (i == 0) {
                block.add("Types it uses (" + types.size() + ")");
            }
            block.addAll(indented(types.get(i).declaration().lines().toList()));
            layout.block(block);
        }
        closureTail(layout, declaration.omitted(), declaration.foreign());
        layout.note(declaration.note());
    }

    private static void closureTail(
            Layout layout, List<DiscoverResult.Method> omitted, List<DiscoverResult.Foreign> foreign) {
        layout.section("Other types it uses, not printed (" + omitted.size() + ")",
                commandTable(omitted));
        TextTable table = new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT, TextTable.Column.LEFT);
        foreign.forEach(type -> table.row(type.name(),
                type.module() + (type.version() == null ? "" : " " + type.version()),
                type.command() == null ? "(package unknown: cannot be looked up)" : type.command()));
        layout.section("From other packages (" + foreign.size() + ")", table);
    }

    private static String header(Context where, String container) {
        List<String> parts = new ArrayList<>();
        if (where.pkg() != null) {
            parts.add(where.version() == null ? where.pkg() : where.pkg() + ":" + where.version());
        }
        if (where.module() != null) {
            parts.add("module " + where.module());
        }
        List<String> trail = new ArrayList<>(where.trail());
        if (container != null && !trail.contains(container)) {
            trail.add(Math.min(1, trail.size()), container);
        }
        parts.addAll(trail);
        if (where.filter() != null) {
            parts.add("--filter " + where.filter());
        }
        return parts.isEmpty() ? null : String.join(SEPARATOR, parts);
    }


    private static String containerOf(DiscoverResult result) {
        return switch (result) {
            case DiscoverResult.PathGroups groups -> groups.container();
            case DiscoverResult.ResourceList resources -> resources.container();
            case DiscoverResult.MethodList methods -> methods.container();
            case DiscoverResult.MixedListing mixed -> mixed.container();
            case DiscoverResult.Signature signature -> signature.container();
            case DiscoverResult.ServiceHandlerList handlers -> handlers.container();
            case DiscoverResult.ServiceExamples examples -> examples.container();
            default -> null;
        };
    }

    private static TextTable resourceTable(List<DiscoverResult.ResourceList.Resource> resources, Drill drill) {
        TextTable table = new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT, TextTable.Column.LEFT);
        for (int i = 0; i < resources.size(); i++) {
            DiscoverResult.ResourceList.Resource resource = resources.get(i);
            table.row(resource.path(), String.join(", ", resource.accessors()), ownCommand(resource, drill.unfit(i)));
        }
        return table;
    }

    private static Drill resourceDrill(List<DiscoverResult.ResourceList.Resource> resources) {
        List<List<String>> literals = new ArrayList<>();
        List<List<String>> commands = new ArrayList<>();
        for (DiscoverResult.ResourceList.Resource resource : resources) {
            literals.add(resource.commands().keySet().stream().map(accessor -> resource.path() + " " + accessor)
                    .toList());
            commands.add(List.copyOf(resource.commands().values()));
        }
        return Drill.ofRows(literals, commands, "<path> <accessor>");
    }

    // A row's own command: its one unfit command, or with several, the first with its accessor as a placeholder.
    private static String ownCommand(DiscoverResult.ResourceList.Resource resource, List<String> unfit) {
        if (unfit.size() < 2) {
            return unfit.isEmpty() ? null : unfit.get(0);
        }
        String first = unfit.get(0);
        return resource.commands().entrySet().stream()
                .filter(command -> command.getValue().equals(first))
                .map(command -> Drill.shapeOf(first, command.getKey(), ACCESSOR_PLACEHOLDER))
                .findFirst()
                .orElse(first);
    }

    private static Drill methodDrill(List<DiscoverResult.Method> methods) {
        return Drill.of(methods.stream().map(DiscoverResult.Method::name).toList(),
                methods.stream().map(DiscoverResult.Method::command).toList(), NAME_PLACEHOLDER);
    }

    private static TextTable methodTable(List<DiscoverResult.Method> methods, Drill drill) {
        return methodTable(methods, drill, 0);
    }

    private static TextTable methodTable(List<DiscoverResult.Method> methods, Drill drill, int offset) {
        TextTable table = new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT);
        for (int i = 0; i < methods.size(); i++) {
            table.row(methods.get(i).name(), drill.explicit(offset + i));
        }
        return table;
    }

    private static TextTable commandTable(List<DiscoverResult.Method> methods) {
        TextTable table = new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT);
        methods.forEach(method -> table.row(method.name(), method.command()));
        return table;
    }

    private static List<String> documented(Layout layout, DiscoverResult.Documented documented) {
        List<DiscoverResult.Documented.Entry> entries = documented.entries();
        if (entries.isEmpty() && documented.total() > 0) {
            layout.block(List.of(documented.total() + " more mention it only in their documentation, on a later page"));
            return List.of();
        }
        List<DiscoverResult.Method> methods = new ArrayList<>();
        List<DiscoverResult.ResourceList.Resource> resources = new ArrayList<>();
        entries.forEach(entry -> {
            switch (entry) {
                case DiscoverResult.Method method -> methods.add(method);
                case DiscoverResult.ResourceList.Resource resource -> resources.add(resource);
            }
        });
        Drill methodDrill = methodDrill(methods);
        Drill resourceDrill = resourceDrill(resources);
        TextTable table = new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT, TextTable.Column.LEFT);
        int method = 0;
        int resource = 0;
        for (DiscoverResult.Documented.Entry entry : entries) {
            if (entry instanceof DiscoverResult.ResourceList.Resource row) {
                table.row(row.path(), String.join(", ", row.accessors()),
                        ownCommand(row, resourceDrill.unfit(resource++)));
            } else {
                table.row(entry.key(), null, methodDrill.explicit(method++));
            }
        }
        int shown = entries.size();
        layout.section("Mentioned only in their documentation"
                + (shown < documented.total() ? " (" + shown + " of " + documented.total() + ")" : ""), table);
        return Stream.of(resourceDrill.pattern(), methodDrill.pattern()).filter(Objects::nonNull).toList();
    }

    private static List<String> indented(List<String> lines) {
        return lines.stream().map(line -> line.isEmpty() ? line : INDENT + line).toList();
    }

    private static String counted(int count, String singular, String plural) {
        if (count == 0) {
            return "No " + plural + ".";
        }
        return count + " " + (count == 1 ? singular : plural);
    }

    private static String onlyDocumented(int total) {
        return total == 0 ? DOCUMENTED : null;
    }

    private static int remaining(int shown, int total, DiscoverResult.Paging paging) {
        return paging == null ? total - shown : paging.remaining();
    }

    /**
     * The one command shape a listing's per-row {@code command}s share — the row's own name swapped for a
     * placeholder — and, for each row, its commands only where substituting its name into that shape would not
     * reproduce them exactly.
     *
     * @param pattern the shared command shape, or {@code null} when no row carries a command
     * @param unfit per row, the commands the shape cannot spell, in order
     */
    private record Drill(String pattern, List<List<String>> unfit) {

        static Drill of(List<String> literals, List<String> commands, String placeholder) {
            return ofRows(literals.stream().map(List::of).toList(),
                    commands.stream()
                            .map(command -> command == null ? List.<String>of() : List.of(command))
                            .toList(),
                    placeholder);
        }

        /** Each row with any number of commands, {@code literals} naming each one in the same order. */
        static Drill ofRows(List<List<String>> literals, List<List<String>> commands, String placeholder) {
            return shaped(literals.stream().map(row -> row.stream().map(List::of).toList()).toList(), commands,
                    List.of(placeholder));
        }

        /** Each row with one command naming several literals, each swapped for its own placeholder, in order. */
        static Drill ofParts(List<List<String>> parts, List<String> commands, List<String> placeholders) {
            return shaped(parts.stream().map(List::of).toList(),
                    commands.stream()
                            .map(command -> command == null ? List.<String>of() : List.of(command))
                            .toList(),
                    placeholders);
        }

        private static Drill shaped(List<List<List<String>>> literals, List<List<String>> commands,
                List<String> placeholders) {
            Map<String, Integer> shapes = new LinkedHashMap<>();
            for (int row = 0; row < commands.size(); row++) {
                for (int i = 0; i < commands.get(row).size(); i++) {
                    String shape = shapeOf(commands.get(row).get(i), literals.get(row).get(i), placeholders);
                    if (shape != null) {
                        shapes.merge(shape, 1, Integer::sum);
                    }
                }
            }
            String pattern = shapes.entrySet().stream()
                    .reduce((best, candidate) -> candidate.getValue() > best.getValue() ? candidate : best)
                    .map(Map.Entry::getKey)
                    .orElse(null);
            List<List<String>> unfit = new ArrayList<>();
            for (int row = 0; row < commands.size(); row++) {
                List<String> own = new ArrayList<>();
                for (int i = 0; i < commands.get(row).size(); i++) {
                    String command = commands.get(row).get(i);
                    if (pattern == null || !pattern.equals(shapeOf(command, literals.get(row).get(i), placeholders))) {
                        own.add(command);
                    }
                }
                unfit.add(List.copyOf(own));
            }
            return new Drill(pattern, List.copyOf(unfit));
        }

        private static String shapeOf(String command, List<String> literals, List<String> placeholders) {
            Command shape = Command.parse(command).orElse(null);
            for (int part = 0; part < literals.size() && shape != null; part++) {
                shape = reshaped(shape, literals.get(part), placeholders.get(part));
            }
            return shape == null ? null : shape.text();
        }

        /** {@code command} with {@code literal} swapped for {@code placeholder}, or {@code null} if it cannot be. */
        static String shapeOf(String command, String literal, String placeholder) {
            return shapeOf(command, List.of(literal), List.of(placeholder));
        }

        // The literal is the container a member command names with its flag, or a run of the command's names; a
        // literal the shell would see quoted is never shaped, since a reader fills the placeholder in as shown.
        private static Command reshaped(Command command, String literal, String placeholder) {
            List<String> words = List.of(literal.split(" "));
            if (words.stream().anyMatch(word -> !Texts.shellWord(word).equals(word))) {
                return null;
            }
            if (CONTAINER_PLACEHOLDER.equals(placeholder)) {
                Command.Owner owner = command.owner();
                return owner != null && owner.name().equals(literal)
                        ? command.ownedBy(owner.kind(), Command.placeholder(placeholder))
                        : null;
            }
            List<String> names = command.names();
            for (int at = names.size() - words.size(); at >= 0; at--) {
                if (names.subList(at, at + words.size()).equals(words)) {
                    List<String> labels = List.of(placeholder.split(" "));
                    List<String> shaped = new ArrayList<>(names.subList(0, at));
                    (labels.size() == words.size() ? labels : List.of(placeholder))
                            .forEach(label -> shaped.add(Command.placeholder(label)));
                    shaped.addAll(names.subList(at + words.size(), names.size()));
                    return command.withNames(shaped);
                }
            }
            return null;
        }

        List<String> unfit(int row) {
            return unfit.get(row);
        }

        /** A single-command row's own command where the shape cannot spell it, else {@code null}. */
        String explicit(int row) {
            return unfit.get(row).isEmpty() ? null : unfit.get(row).get(0);
        }
    }

    /**
     * The frame every answer shares: the header and count lines, the blocks, then the footer — notices, then
     * what was left out, then the {@code Next:} commands. One blank line between parts, none inside one.
     */
    private static final class Layout {

        private final List<String> top = new ArrayList<>();
        private final List<List<String>> blocks = new ArrayList<>();
        private final List<String> notices = new ArrayList<>();
        private final List<String> more = new ArrayList<>();
        private final List<String> next = new ArrayList<>();
        private boolean hasLaterPages;
        private final List<String> inlinedNext = new ArrayList<>();

        void top(String line) {
            if (line != null) {
                top.add(line);
            }
        }

        void block(List<String> lines) {
            if (!lines.isEmpty()) {
                blocks.add(lines);
            }
        }

        void section(String heading, TextTable table) {
            if (!table.isEmpty()) {
                List<String> lines = new ArrayList<>();
                lines.add(heading);
                lines.addAll(table.lines(INDENT));
                blocks.add(lines);
            }
        }

        /** What bucket this answer actually came from, or {@code null}. */
        void note(String note) {
            if (note != null) {
                notices.add("Note: " + note);
            }
        }

        /**
         * The RFC's truncation line, {@code ... N more, narrow further} — only when something was cut. A paged
         * listing names its page instead, including the last one, which has nothing more to point at.
         */
        void more(int remaining, DiscoverResult.Paging paging) {
            more(remaining, paging, null);
        }

        /** {@code what} names the entries counted, where the footer can hold another count of other entries. */
        void more(int remaining, DiscoverResult.Paging paging, String what) {
            String counted = "... " + remaining + " more" + (what == null ? "" : " " + what);
            if (paging == null) {
                if (remaining > 0) {
                    more.add(what == null ? counted + ", narrow with --filter <keyword>" : counted);
                }
            } else if (remaining > 0) {
                hasLaterPages = true;
                more.add(counted + " (page " + paging.page() + " of " + paging.pages() + ")");
            } else if (paging.pages() > 1) {
                more.add("Last page (page " + paging.page() + " of " + paging.pages() + ")");
            }
        }

        /** A {@code Next:} line for {@code command}, once however many times it is offered. */
        void next(String command) {
            if (command != null && !next.contains(NEXT + command)) {
                next.add(NEXT + command);
            }
        }

        String render() {
            List<String> parts = new ArrayList<>();
            addPart(parts, top);
            blocks.forEach(block -> addPart(parts, block));
            List<String> footer = new ArrayList<>(notices);
            footer.addAll(more);
            footer.addAll(next);
            addPart(parts, footer);
            return String.join("\n\n", parts);
        }

        private static void addPart(List<String> parts, List<String> lines) {
            if (!lines.isEmpty()) {
                parts.add(String.join("\n", lines));
            }
        }
    }
}
