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

import io.ballerina.tools.discover.Texts;
import io.ballerina.tools.discover.symbols.Names;
import io.ballerina.tools.discover.symbols.Surface;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@link DiscoverResult} → the human-oriented text {@code --output text} prints at an interactive terminal.
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
    private static final String DOCUMENTED = "matched by documentation only";
    private static final Pattern PAGE_ARGUMENT = Pattern.compile(" --page \\d+$");

    private TextRenderer() {
    }

    /**
     * Where an answer was asked from — the header line. None of it is in a {@link DiscoverResult}, because the
     * JSON rendering answers a caller who already holds the command it ran.
     *
     * @param pkg the package, {@code org/name}, or {@code null} when unknown
     * @param module the {@code --module} submodule, or {@code null}
     * @param trail the bucket and selectors, as typed
     * @param filter the {@code --filter} keyword, or {@code null}
     * @param version the {@code --version} the caller pinned, or {@code null}
     */
    public record Context(String pkg, String module, List<String> trail, String filter, String version) {

        public static final Context NONE = new Context(null, null, List.of(), null, null);

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
     * {@code result} under a header naming where it was asked from. A whole readme is printed verbatim, without
     * one.
     */
    public static String render(DiscoverResult result, Context where) {
        if (result instanceof DiscoverResult.Readme readme && readme.chunk() == null) {
            return verbatimReadme(readme);
        }
        Layout layout = new Layout();
        layout.top(header(where, containerOf(result), memberOf(result)));
        fill(layout, result, where);
        if (layout.hasLaterPages && where.filter() == null && where.pkg() != null) {
            layout.next(filterCommand(result));
        }
        return layout.render();
    }

    /**
     * The command that narrows the listing just shown by a keyword, which is what most callers want over a page:
     * its own page command — the canonical one, whatever bucket and spelling were typed — with a filter for the
     * page.
     */
    private static String filterCommand(DiscoverResult result) {
        String next = switch (result) {
            case DiscoverResult.ContainerRoster roster -> roster.next();
            case DiscoverResult.PathGroups groups -> groups.next();
            case DiscoverResult.ResourceList resources -> resources.next();
            case DiscoverResult.MethodList methods -> methods.next();
            case DiscoverResult.MixedListing mixed -> mixed.next();
            case DiscoverResult.TypeRoster roster -> roster.next();
            case DiscoverResult.Owners owners -> owners.next();
            default -> null;
        };
        Matcher page = next == null ? null : PAGE_ARGUMENT.matcher(next);
        return page == null || !page.find() ? null : next.substring(0, page.start()) + " --filter <keyword>";
    }

    private static void fill(Layout layout, DiscoverResult result, Context where) {
        switch (result) {
            case DiscoverResult.BucketList bucketList -> bucketList(layout, bucketList, where);
            case DiscoverResult.ContainerRoster roster -> containerRoster(layout, roster, where);
            case DiscoverResult.PathGroups groups -> pathGroups(layout, groups);
            case DiscoverResult.ResourceList resources -> resourceList(layout, resources);
            case DiscoverResult.MethodList methods -> methodList(layout, methods);
            case DiscoverResult.Readme readme -> readmeChunk(layout, readme);
            case DiscoverResult.ReadmeChunks chunks -> readmeChunks(layout, chunks);
            case DiscoverResult.Signature signature -> signature(layout, signature);
            case DiscoverResult.MixedListing mixed -> mixedListing(layout, mixed);
            case DiscoverResult.NoMatch noMatch -> noMatch(layout, noMatch, where);
            case DiscoverResult.Owners owners -> owners(layout, owners);
            case DiscoverResult.EmptyBucket empty -> emptyBucket(layout, empty);
            case DiscoverResult.TypeRoster roster -> typeRoster(layout, roster);
            case DiscoverResult.TypeDeclaration declaration -> typeDeclaration(layout, declaration);
        }
    }

    // -----------------------------------------------------------------------
    // Shapes
    // -----------------------------------------------------------------------

    private static void bucketList(Layout layout, DiscoverResult.BucketList bucketList, Context where) {
        layout.top(counted(bucketList.buckets().size(), "bucket", "buckets"));
        TextTable buckets = new TextTable(TextTable.Column.LEFT);
        bucketList.buckets().forEach(buckets::row);
        layout.block(buckets.lines(INDENT));
        if (!bucketList.submodules().isEmpty()) {
            TextTable submodules = new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT, TextTable.Column.LEFT);
            bucketList.submodules().forEach(submodule ->
                    submodules.row(submodule.name(), submodule.command(), submodule.summary()));
            layout.section("Submodules", submodules);
        }
        layout.warning(bucketList.warning());
        if (where.pkg() != null && !bucketList.buckets().isEmpty()) {
            layout.next("bal discover " + Texts.shellWord(where.pkg())
                    + (where.module() == null ? "" : " --module " + Texts.shellWord(where.module()))
                    + (where.version() == null ? "" : " --version " + Texts.shellWord(where.version()))
                    + " <bucket>");
        }
    }

    private static void containerRoster(Layout layout, DiscoverResult.ContainerRoster roster, Context where) {
        String[] noun = switch (Surface.Scope.ofVerb(where.bucket()).orElse(Surface.Scope.MODULE)) {
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
        Drill drill = Drill.of(names, commands, "<name>");
        Map<String, List<Integer>> byListener = new LinkedHashMap<>();
        for (int i = 0; i < containers.size(); i++) {
            DiscoverResult.ContainerRoster.Container container = containers.get(i);
            String heading = container.listener() == null || container.unconfirmed() == null
                    ? container.listener()
                    : container.listener() + " — not confirmed (" + container.unconfirmed() + ")";
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
                    TextTable.Column.count("resource", "resources"),
                    TextTable.Column.count("remote", "remote"),
                    TextTable.Column.count("normal", "normal"),
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
            layout.block(List.of("Not attachable to a listener: " + notAttachableTotal + ", on a later page"));
        }
        layout.section("Not attachable to a listener" + (notAttachable.size() < notAttachableTotal
                ? " (" + notAttachable.size() + " of " + notAttachableTotal + ")" : ""), unattachable);
        layout.warning(roster.warning());
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
        layout.section("Here", resourceTable(here, hereDrill));
        Drill groupDrill = Drill.of(groups.groups().stream().map(DiscoverResult.PathGroups.Group::name).toList(),
                groups.groups().stream().map(DiscoverResult.PathGroups.Group::command).toList(), "<group>");
        TextTable table = new TextTable(TextTable.Column.LEFT, TextTable.Column.RIGHT, TextTable.Column.LEFT);
        for (int i = 0; i < groups.groups().size(); i++) {
            DiscoverResult.PathGroups.Group group = groups.groups().get(i);
            table.row(group.name(), String.valueOf(group.count()), groupDrill.explicit(i));
        }
        layout.section("Groups (operations under each)", table);

        layout.notices(groups.note(), groups.warning());
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
        layout.notices(resources.note(), resources.warning());
        layout.more(remaining(resources.shown(), resources.total(), resources.paging()), resources.paging());
        layout.next(drill.pattern());
        documentedDrill.forEach(layout::next);
        layout.next(resources.next());
    }

    private static void methodList(Layout layout, DiscoverResult.MethodList methods) {
        layout.top(methods.container() == null
                ? counted(methods.total(), "function", "functions")
                : counted(methods.total(), "method", "methods"));
        Drill drill = methodDrill(methods.methods());
        layout.block(methodTable(methods.methods(), drill).lines(INDENT));
        List<String> documentedDrill = documented(layout, methods.documented());
        layout.notices(methods.note(), methods.warning());
        layout.more(remaining(methods.shown(), methods.total(), methods.paging()), methods.paging());
        layout.next(drill.pattern());
        documentedDrill.forEach(layout::next);
        layout.next(methods.next());
    }

    /** Verbatim, per the RFC's own words for this bucket — no header, no wrapping, for the whole-readme case. */
    private static String verbatimReadme(DiscoverResult.Readme readme) {
        return readme.warning() == null ? readme.markdown() : readme.markdown() + "\n\nWarning: " + readme.warning();
    }

    private static void readmeChunk(Layout layout, DiscoverResult.Readme readme) {
        layout.top("Chunk " + readme.chunk() + " of " + readme.of() + ": " + readme.title());
        layout.block(List.of(readme.markdown()));
        layout.warning(readme.warning());
    }

    private static void readmeChunks(Layout layout, DiscoverResult.ReadmeChunks chunks) {
        layout.top(counted(chunks.total(), "matching chunk", "matching chunks"));
        List<DiscoverResult.ReadmeChunks.Chunk> rows = chunks.chunks();
        Drill drill = Drill.of(rows.stream().map(chunk -> String.valueOf(chunk.number())).toList(),
                rows.stream().map(DiscoverResult.ReadmeChunks.Chunk::command).toList(), "<n>");
        TextTable table = new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT,
                TextTable.Column.count("line", "lines"), TextTable.Column.LEFT);
        for (int i = 0; i < rows.size(); i++) {
            DiscoverResult.ReadmeChunks.Chunk chunk = rows.get(i);
            table.row(String.valueOf(chunk.number()), chunk.title(), String.valueOf(chunk.lines()),
                    drill.explicit(i));
        }
        layout.block(table.lines(INDENT));
        layout.warning(chunks.warning());
        layout.more(remaining(rows.size(), chunks.total(), chunks.paging()), chunks.paging());
        layout.next(drill.pattern());
        layout.next(chunks.next());
    }

    /** The declaration verbatim — it is Ballerina to copy — then each type it names, indented, one block apiece. */
    private static void signature(Layout layout, DiscoverResult.Signature signature) {
        layout.block(List.of(signature.declaration()));
        List<DiscoverResult.Signature.Type> types = signature.types();
        for (int i = 0; i < types.size(); i++) {
            List<String> block = new ArrayList<>();
            if (i == 0) {
                block.add("Types it names (" + types.size() + ")");
            }
            block.addAll(indented(types.get(i).declaration().lines().toList()));
            layout.block(block);
        }
        closureTail(layout, signature.omitted(), signature.omittedTotal(), signature.omittedNext(),
                signature.foreign());
        List<String> documentedDrill = documented(layout, signature.documented());
        layout.notices(signature.note(), signature.warning());
        layout.more(remaining(signature.documented().names().size(), signature.documented().total(),
                signature.paging()), signature.paging(), DOCUMENTED);
        documentedDrill.forEach(layout::next);
        layout.next(signature.next());
    }

    private static void mixedListing(Layout layout, DiscoverResult.MixedListing mixed) {
        DiscoverResult.MixedListing.Counts counts = mixed.counts();
        List<String> parts = new ArrayList<>();
        if (counts.resources() > 0) {
            parts.add(counted(counts.resources(), "resource path", "resource paths"));
        }
        if (counts.remote() > 0) {
            parts.add(counted(counts.remote(), "remote method", "remote methods"));
        }
        if (counts.normal() > 0) {
            parts.add(counted(counts.normal(), "normal method", "normal methods"));
        }
        layout.top(String.join(", ", parts));
        Drill drill = resourceDrill(mixed.resources());
        layout.section("Resources (->)", resourceTable(mixed.resources(), drill));
        List<DiscoverResult.Method> methods = new ArrayList<>(mixed.remote());
        methods.addAll(mixed.normal());
        Drill methodDrill = methodDrill(methods);
        layout.section("Remote (->)", methodTable(mixed.remote(), methodDrill, 0));
        layout.section("Normal (.)", methodTable(mixed.normal(), methodDrill, mixed.remote().size()));
        List<String> documentedDrill = documented(layout, mixed.documented());
        layout.notices(mixed.note(), mixed.warning());
        layout.more(remaining(mixed.shown(), mixed.total(), mixed.paging()), mixed.paging());
        layout.next(drill.pattern());
        layout.next(methodDrill.pattern());
        documentedDrill.forEach(layout::next);
        layout.next(mixed.next());
    }

    private static void noMatch(Layout layout, DiscoverResult.NoMatch noMatch, Context where) {
        layout.top("Nothing" + (noMatch.container() == null ? "" : " on " + noMatch.container())
                + " matches '" + noMatch.requested() + "'.");
        layout.section("Did you mean", names(noMatch.candidates()));
        TextTable paths = new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT);
        noMatch.paths().forEach(alternative -> paths.row(alternative.path(), alternative.command()));
        layout.section(noMatch.paths().size() + " paths carry that segment; pick one", paths);
        List<String> documentedDrill = documented(layout, noMatch.documented());
        if (noMatch.available() != null) {
            Layout available = new Layout();
            fill(available, noMatch.available(), where);
            List<String> block = new ArrayList<>();
            block.add("Available");
            block.addAll(indented(available.render().lines().toList()));
            layout.block(block);
        }
        layout.notices(noMatch.note(), noMatch.warning());
        documentedDrill.forEach(layout::next);
        if (noMatch.paging() != null) {
            layout.more(noMatch.paging().remaining(), noMatch.paging(), DOCUMENTED);
            layout.next(noMatch.next());
        } else if (noMatch.available() == null) {
            layout.next(noMatch.next());
        }
    }

    private static void owners(Layout layout, DiscoverResult.Owners owners) {
        layout.top("'" + owners.requested() + "' is declared on " + owners.total() + " containers; pick one.");
        TextTable table = new TextTable(TextTable.Column.LEFT, TextTable.Column.count("match", "matches"),
                TextTable.Column.LEFT);
        owners.owners().forEach(owner -> table.row(owner.name(), String.valueOf(owner.matches()), owner.command()));
        layout.block(table.lines(INDENT));
        layout.notices(owners.note(), owners.warning());
        layout.more(remaining(owners.owners().size(), owners.total(), owners.paging()), owners.paging());
        layout.next(owners.next());
    }

    private static void emptyBucket(Layout layout, DiscoverResult.EmptyBucket empty) {
        layout.top("This package declares nothing in " + empty.bucket() + ".");
        TextTable table = new TextTable(TextTable.Column.LEFT, TextTable.Column.RIGHT, TextTable.Column.LEFT);
        empty.elsewhere().forEach(other -> table.row(other.bucket(), String.valueOf(other.count()), other.command()));
        layout.section("Elsewhere", table);
        layout.warning(empty.warning());
    }

    private static void typeRoster(Layout layout, DiscoverResult.TypeRoster roster) {
        List<String> parts = new ArrayList<>();
        roster.counts().forEach((kind, count) -> {
            if (count > 0) {
                parts.add(count + " " + kindNoun(kind, count));
            }
        });
        layout.top(parts.isEmpty() ? "No types." : String.join(", ", parts));
        List<DiscoverResult.Method> all = roster.sections().stream()
                .flatMap(section -> section.entries().stream())
                .toList();
        Drill drill = methodDrill(all);
        int offset = 0;
        for (DiscoverResult.TypeRoster.Section section : roster.sections()) {
            String heading = Character.toUpperCase(section.kind().charAt(0)) + section.kind().substring(1);
            layout.section(heading, methodTable(section.entries(), drill, offset));
            offset += section.entries().size();
        }
        List<String> documentedDrill = documented(layout, roster.documented());
        layout.notices(roster.note(), roster.warning());
        layout.more(remaining(roster.shown(), roster.total(), roster.paging()), roster.paging());
        layout.next(drill.pattern());
        documentedDrill.forEach(layout::next);
        layout.next(roster.next());
    }

    private static String kindNoun(String kind, int count) {
        if (count == 1) {
            return switch (kind) {
                case "aliases" -> "alias";
                default -> kind.substring(0, kind.length() - 1);
            };
        }
        return kind;
    }

    /** The declaration verbatim, then each type it names, indented, one block apiece — as a signature does. */
    private static void typeDeclaration(Layout layout, DiscoverResult.TypeDeclaration declaration) {
        layout.block(List.of(declaration.declaration()));
        List<DiscoverResult.Signature.Type> types = declaration.types();
        for (int i = 0; i < types.size(); i++) {
            List<String> block = new ArrayList<>();
            if (i == 0) {
                block.add("Types it names (" + types.size() + ")");
            }
            block.addAll(indented(types.get(i).declaration().lines().toList()));
            layout.block(block);
        }
        closureTail(layout, declaration.omitted(), declaration.omittedTotal(), declaration.omittedNext(),
                declaration.foreign());
        layout.notices(declaration.note(), declaration.warning());
    }

    /** What a closure left behind: the names its budget dropped, and the declarations another package owns. */
    private static void closureTail(
            Layout layout, List<DiscoverResult.Method> omitted, int omittedTotal, String omittedNext,
            List<DiscoverResult.Foreign> foreign) {
        int unlisted = Math.max(0, omittedTotal - omitted.size());
        layout.section("Past the closure budget (" + omitted.size() + (unlisted > 0 ? " of " + omittedTotal : "") + ")",
                commandTable(omitted));
        layout.more(unlisted, null, "past the closure budget, listed by the type roster");
        if (unlisted > 0) {
            layout.next(omittedNext);
        }
        TextTable table = new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT, TextTable.Column.LEFT);
        foreign.forEach(type -> table.row(type.name(),
                type.module() + (type.version() == null ? "" : " " + type.version()),
                type.command() == null ? "(no command: package not known)" : type.command()));
        layout.section("From other packages (" + foreign.size() + ")", table);
    }

    // -----------------------------------------------------------------------
    // Shared pieces
    // -----------------------------------------------------------------------

    /** {@code org/name · module m · bucket · Container · selectors · --filter k}, from what was asked. */
    private static String header(Context where, String container, String member) {
        List<String> parts = new ArrayList<>();
        if (where.pkg() != null) {
            parts.add(where.pkg());
        }
        if (where.module() != null) {
            parts.add("module " + where.module());
        }
        List<String> trail = resolvedTrail(where, container, member);
        if (container != null && !trail.contains(container)) {
            trail.add(Math.min(1, trail.size()), container);
        }
        parts.addAll(trail);
        if (where.filter() != null) {
            parts.add("--filter " + where.filter());
        }
        return parts.isEmpty() ? null : String.join(SEPARATOR, parts);
    }

    /**
     * The trail as typed, with the first selector spelled as the container it resolved to and the last as the
     * method or function it resolved to — no other word, which may be a path segment that only looks like either.
     */
    private static List<String> resolvedTrail(Context where, String container, String member) {
        List<String> trail = new ArrayList<>(where.trail());
        int containerAt = -1;
        if (container != null && trail.size() > 1 && sameName(trail.get(1), container)) {
            trail.set(1, container);
            containerAt = 1;
        }
        int last = trail.size() - 1;
        if (member != null && last >= 1 && last != containerAt && sameName(trail.get(last), member)) {
            trail.set(last, member);
        }
        return trail;
    }

    /** Whether the caller typed the declared name, in any spelling {@link Names#normalise} accepts. */
    private static boolean sameName(String typed, String declared) {
        return Names.normalise(typed).equals(Names.normalise(declared));
    }

    /** The member the header names after its container: only a signature answers one. */
    private static String memberOf(DiscoverResult result) {
        return result instanceof DiscoverResult.Signature signature ? signature.name() : null;
    }

    private static String containerOf(DiscoverResult result) {
        return switch (result) {
            case DiscoverResult.PathGroups groups -> groups.container();
            case DiscoverResult.ResourceList resources -> resources.container();
            case DiscoverResult.MethodList methods -> methods.container();
            case DiscoverResult.MixedListing mixed -> mixed.container();
            case DiscoverResult.Signature signature -> signature.container();
            case DiscoverResult.NoMatch noMatch -> noMatch.container();
            default -> null;
        };
    }

    private static TextTable resourceTable(List<DiscoverResult.ResourceList.Resource> resources, Drill drill) {
        TextTable table = new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT, TextTable.Column.LEFT);
        for (int i = 0; i < resources.size(); i++) {
            DiscoverResult.ResourceList.Resource resource = resources.get(i);
            table.row(resource.path(), String.join(", ", resource.accessors()), ownCommand(drill.unfit(i)));
        }
        return table;
    }

    /**
     * Every accessor's command teaches the shape, a multi-accessor row's as much as a single one's — the
     * {@code <path> <accessor>} pair is what a row's own name fills.
     */
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

    /**
     * A resource row's commands the shared shape cannot spell (its path needs quoting): one is printed whole;
     * several differ only in their accessor, which the row already lists, so they print once with that slot open.
     */
    private static String ownCommand(List<String> unfit) {
        if (unfit.size() < 2) {
            return unfit.isEmpty() ? null : unfit.get(0);
        }
        String first = unfit.get(0);
        return first.substring(0, first.lastIndexOf(' ')) + " <accessor>";
    }

    private static Drill methodDrill(List<DiscoverResult.Method> methods) {
        return Drill.of(methods.stream().map(DiscoverResult.Method::name).toList(),
                methods.stream().map(DiscoverResult.Method::command).toList(), "<name>");
    }

    private static TextTable methodTable(List<DiscoverResult.Method> methods, Drill drill) {
        return methodTable(methods, drill, 0);
    }

    /** {@code methods}, whose rows sit at {@code offset} in the list {@code drill} was derived from. */
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

    private static TextTable names(List<String> names) {
        TextTable table = new TextTable(TextTable.Column.LEFT);
        names.forEach(table::row);
        return table;
    }

    /**
     * Documentation-only matches on this page — or, on a page that holds none of them while a later one does,
     * one line saying how many are coming, so they are never silently absent.
     *
     * @return the command shapes that open them, for the footer
     */
    private static List<String> documented(Layout layout, DiscoverResult.Documented documented) {
        List<DiscoverResult.Documented.Entry> entries = documented.entries();
        if (entries.isEmpty() && documented.total() > 0) {
            layout.block(List.of("Matched by documentation only: " + documented.total() + ", on a later page"));
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
                table.row(row.path(), String.join(", ", row.accessors()), ownCommand(resourceDrill.unfit(resource++)));
            } else {
                table.row(entry.key(), null, methodDrill.explicit(method++));
            }
        }
        int shown = entries.size();
        layout.section("Matched by documentation only"
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

    /** How many entries a listing left out: everything after this page, or everything past what was shown. */
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
            Map<String, Integer> shapes = new LinkedHashMap<>();
            for (int row = 0; row < commands.size(); row++) {
                for (int i = 0; i < commands.get(row).size(); i++) {
                    String shape = shapeOf(commands.get(row).get(i), literals.get(row).get(i), placeholder);
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
                    if (pattern == null || !command.equals(pattern.replace(placeholder, literals.get(row).get(i)))) {
                        own.add(command);
                    }
                }
                unfit.add(List.copyOf(own));
            }
            return new Drill(pattern, List.copyOf(unfit));
        }

        /** {@code command} with its last whole-word occurrence of {@code literal} replaced, or {@code null}. */
        private static String shapeOf(String command, String literal, String placeholder) {
            String needle = " " + literal;
            for (int at = command.lastIndexOf(needle); at >= 0; at = command.lastIndexOf(needle, at - 1)) {
                int end = at + needle.length();
                if (end == command.length() || command.charAt(end) == ' ') {
                    return command.substring(0, at + 1) + placeholder + command.substring(end);
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

        /** {@code note} first — it explains what bucket this answer actually came from — then {@code warning}. */
        void notices(String note, String warning) {
            if (note != null) {
                notices.add("Note: " + note);
            }
            warning(warning);
        }

        void warning(String warning) {
            if (warning != null) {
                notices.add("Warning: " + warning);
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
                    more.add(what == null ? counted + ", narrow further" : counted);
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
            if (command != null && !next.contains("Next: " + command)) {
                next.add("Next: " + command);
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
