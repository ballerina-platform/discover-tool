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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link DiscoverResult} → the human-oriented text {@code --output text} prints at an interactive terminal.
 *
 * <p>Every answer has the same frame: a header naming where the caller is ({@code ballerinax/github · client ·
 * Client}) and a count line, then the entries one per line in aligned columns, grouped under plain headings, then
 * a footer holding the notes, how much was left out and the {@code Next:} commands. Where the JSON rendering gives
 * every row its own {@code call} (a resource row its {@code calls}, one per accessor), a large listing prints the
 * shared shape of those commands once in the footer instead; a row whose command does not fit that shape (a name
 * that needs shell quoting, a group opened without the listing's accessor) carries its own. No colour and no
 * wrapping, so the output stays as greppable as the JSON.
 *
 * @since 0.1.0
 */
public final class TextRenderer {

    private static final String INDENT = "  ";
    private static final String SEPARATOR = " · ";

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
     */
    public record Context(String pkg, String module, List<String> trail, String filter) {

        public static final Context NONE = new Context(null, null, List.of(), null);

        public Context {
            trail = trail == null ? List.of() : List.copyOf(trail);
        }

        private String bucket() {
            return trail.isEmpty() ? null : trail.get(0);
        }
    }

    public static String render(DiscoverResult result) {
        return render(result, Context.NONE);
    }

    public static String render(DiscoverResult result, Context where) {
        if (result instanceof DiscoverResult.Readme readme && readme.chunk() == null && !readme.markdown().isEmpty()) {
            return verbatimReadme(readme);
        }
        Layout layout = new Layout();
        layout.top(header(where, containerOf(result)));
        fill(layout, result, where);
        return layout.render();
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
                    submodules.row(submodule.name(), submodule.call(), submodule.summary()));
            layout.section("Submodules", submodules);
        }
        layout.warning(bucketList.warning());
        if (where.pkg() != null && !bucketList.buckets().isEmpty()) {
            layout.next("bal discover " + where.pkg()
                    + (where.module() == null ? "" : " --module " + where.module()) + " <bucket>");
        }
    }

    private static void containerRoster(Layout layout, DiscoverResult.ContainerRoster roster, Context where) {
        String[] noun = switch (where.bucket() == null ? "" : where.bucket()) {
            case "client" -> new String[] {"client", "clients"};
            case "class" -> new String[] {"class", "classes"};
            case "service" -> new String[] {"service type", "service types"};
            default -> new String[] {"container", "containers"};
        };
        layout.top(counted(roster.total(), noun[0], noun[1]));

        List<DiscoverResult.ContainerRoster.Container> containers = roster.containers();
        Drill drill = Drill.of(containers.stream().map(DiscoverResult.ContainerRoster.Container::name).toList(),
                containers.stream().map(DiscoverResult.ContainerRoster.Container::call).toList(), "<name>");
        Map<String, List<Integer>> byListener = new LinkedHashMap<>();
        for (int i = 0; i < containers.size(); i++) {
            String listener = containers.get(i).listener();
            byListener.computeIfAbsent(listener, key -> new ArrayList<>()).add(i);
        }
        boolean paired = !byListener.containsKey(null) || byListener.size() > 1;
        byListener.forEach((listener, rows) -> {
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
        layout.warning(roster.warning());
        layout.more(roster.total() - containers.size(), null);
        layout.next(drill.pattern());
        layout.next(roster.next());
    }

    private static void pathGroups(Layout layout, DiscoverResult.PathGroups groups) {
        List<DiscoverResult.ResourceList.Resource> here = groups.resources();
        if (groups.groups().isEmpty()) {
            layout.top(counted(groups.total(), "resource path", "resource paths"));
        } else if (here.isEmpty()) {
            layout.top(counted(groups.total(), "path group", "path groups"));
        } else {
            layout.top(counted(here.size(), "resource path", "resource paths") + " here, "
                    + counted(groups.total() - here.size(), "path group", "path groups"));
        }

        Drill hereDrill = resourceDrill(here);
        layout.section("Here", resourceTable(here, hereDrill));
        Drill groupDrill = Drill.of(groups.groups().stream().map(DiscoverResult.PathGroups.Group::name).toList(),
                groups.groups().stream().map(DiscoverResult.PathGroups.Group::call).toList(), "<group>");
        TextTable table = new TextTable(TextTable.Column.LEFT, TextTable.Column.RIGHT, TextTable.Column.LEFT);
        for (int i = 0; i < groups.groups().size(); i++) {
            DiscoverResult.PathGroups.Group group = groups.groups().get(i);
            table.row(group.name(), String.valueOf(group.count()), groupDrill.explicit(i));
        }
        layout.section("Groups (operations under each)", table);

        layout.notices(groups.note(), groups.warning());
        layout.more(groups.total() - here.size() - groups.groups().size(), null);
        layout.next(hereDrill.pattern());
        layout.next(groupDrill.pattern());
        layout.next(groups.next());
    }

    private static void resourceList(Layout layout, DiscoverResult.ResourceList resources) {
        layout.top(counted(resources.total(), "resource path", "resource paths"));
        Drill drill = resourceDrill(resources.resources());
        layout.block(resourceTable(resources.resources(), drill).lines(INDENT));
        layout.notices(resources.note(), resources.warning());
        layout.more(remaining(resources.shown(), resources.total(), resources.paging()), resources.paging());
        layout.next(drill.pattern());
        layout.next(resources.next());
    }

    private static void methodList(Layout layout, DiscoverResult.MethodList methods) {
        layout.top(methods.container() == null
                ? counted(methods.total(), "function", "functions")
                : counted(methods.total(), "method", "methods"));
        layout.block(names(methods.methods()).lines(INDENT));
        layout.notices(methods.note(), methods.warning());
        layout.more(remaining(methods.shown(), methods.total(), methods.paging()), methods.paging());
        layout.next(methods.next());
    }

    /** Verbatim, per the RFC's own words for this bucket — no header, no wrapping, for the whole-readme case. */
    private static String verbatimReadme(DiscoverResult.Readme readme) {
        return readme.warning() == null ? readme.markdown() : readme.markdown() + "\n\nWarning: " + readme.warning();
    }

    private static void readmeChunk(Layout layout, DiscoverResult.Readme readme) {
        if (readme.markdown().isEmpty()) {
            layout.top("No readme in this module.");
        } else {
            layout.top("Chunk " + readme.chunk() + " of " + readme.of() + ": " + readme.title());
            layout.block(List.of(readme.markdown()));
        }
        layout.warning(readme.warning());
    }

    private static void readmeChunks(Layout layout, DiscoverResult.ReadmeChunks chunks) {
        layout.top(counted(chunks.total(), "matching chunk", "matching chunks"));
        List<DiscoverResult.ReadmeChunks.Chunk> rows = chunks.chunks();
        Drill drill = Drill.of(rows.stream().map(chunk -> String.valueOf(chunk.number())).toList(),
                rows.stream().map(DiscoverResult.ReadmeChunks.Chunk::call).toList(), "<n>");
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
        layout.section(signature.omitted().size() + " more past the closure budget, not shown",
                names(signature.omitted()));
        documented(layout, signature.documented());
        layout.notices(signature.note(), signature.warning());
    }

    private static void mixedListing(Layout layout, DiscoverResult.MixedListing mixed) {
        if (mixed.shown() < mixed.total()) {
            layout.top(counted(mixed.total(), "entry", "entries"));
        } else {
            List<String> parts = new ArrayList<>();
            if (!mixed.resources().isEmpty()) {
                parts.add(counted(mixed.resources().size(), "resource path", "resource paths"));
            }
            if (!mixed.remote().isEmpty()) {
                parts.add(counted(mixed.remote().size(), "remote method", "remote methods"));
            }
            if (!mixed.normal().isEmpty()) {
                parts.add(counted(mixed.normal().size(), "normal method", "normal methods"));
            }
            layout.top(String.join(", ", parts));
        }
        Drill drill = resourceDrill(mixed.resources());
        layout.section("Resources (->)", resourceTable(mixed.resources(), drill));
        layout.section("Remote (->)", names(mixed.remote()));
        layout.section("Normal (.)", names(mixed.normal()));
        documented(layout, mixed.documented());
        layout.notices(mixed.note(), mixed.warning());
        layout.more(mixed.total() - mixed.shown(), null);
        layout.next(drill.pattern());
        layout.next(mixed.next());
    }

    private static void noMatch(Layout layout, DiscoverResult.NoMatch noMatch, Context where) {
        layout.top("Nothing" + (noMatch.container() == null ? "" : " on " + noMatch.container())
                + " matches '" + noMatch.requested() + "'.");
        layout.section("Did you mean", names(noMatch.candidates()));
        TextTable paths = new TextTable(TextTable.Column.LEFT, TextTable.Column.LEFT);
        noMatch.paths().forEach(alternative -> paths.row(alternative.path(), alternative.call()));
        layout.section(noMatch.paths().size() + " paths carry that segment; pick one", paths);
        documented(layout, noMatch.documented());
        if (noMatch.available() != null) {
            Layout available = new Layout();
            fill(available, noMatch.available(), where);
            List<String> block = new ArrayList<>();
            block.add("Available");
            block.addAll(indented(available.render().lines().toList()));
            layout.block(block);
        } else {
            layout.next(noMatch.next());
        }
        layout.notices(noMatch.note(), noMatch.warning());
    }

    private static void owners(Layout layout, DiscoverResult.Owners owners) {
        layout.top("'" + owners.requested() + "' is declared on " + owners.total() + " containers; pick one.");
        TextTable table = new TextTable(TextTable.Column.LEFT, TextTable.Column.count("match", "matches"),
                TextTable.Column.LEFT);
        owners.owners().forEach(owner -> table.row(owner.name(), String.valueOf(owner.matches()), owner.call()));
        layout.block(table.lines(INDENT));
        layout.warning(owners.warning());
        layout.more(owners.total() - owners.owners().size(), null);
        layout.next(owners.next());
    }

    private static void emptyBucket(Layout layout, DiscoverResult.EmptyBucket empty) {
        layout.top("This package declares nothing in " + empty.bucket() + ".");
        TextTable table = new TextTable(TextTable.Column.LEFT, TextTable.Column.RIGHT, TextTable.Column.LEFT);
        empty.elsewhere().forEach(other -> table.row(other.bucket(), String.valueOf(other.count()), other.call()));
        layout.section("Elsewhere", table);
        layout.warning(empty.warning());
    }

    // -----------------------------------------------------------------------
    // Shared pieces
    // -----------------------------------------------------------------------

    /** {@code org/name · module m · bucket · Container · selectors · --filter k}, from what was asked. */
    private static String header(Context where, String container) {
        List<String> parts = new ArrayList<>();
        if (where.pkg() != null) {
            parts.add(where.pkg());
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
        List<List<String>> calls = new ArrayList<>();
        for (DiscoverResult.ResourceList.Resource resource : resources) {
            literals.add(resource.calls().keySet().stream().map(accessor -> resource.path() + " " + accessor)
                    .toList());
            calls.add(List.copyOf(resource.calls().values()));
        }
        return Drill.ofRows(literals, calls, "<path> <accessor>");
    }

    /**
     * A resource row's commands the shared shape cannot spell (its path needs quoting): one is printed whole;
     * several differ only in their accessor, which the row already lists, so they print once with that slot open.
     */
    private static String ownCommand(List<String> unfit) {
        if (unfit.size() < 2) {
            return unfit.isEmpty() ? null : unfit.get(0);
        }
        String prefix = unfit.get(0).substring(0, unfit.get(0).lastIndexOf(' '));
        boolean shared = unfit.stream().allMatch(call -> call.lastIndexOf(' ') == prefix.length()
                && call.startsWith(prefix));
        return shared ? prefix + " <accessor>" : String.join("  ", unfit);
    }

    private static TextTable names(List<String> names) {
        TextTable table = new TextTable(TextTable.Column.LEFT);
        names.forEach(table::row);
        return table;
    }

    private static void documented(Layout layout, List<String> documented) {
        layout.section("Matched by documentation only", names(documented));
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
     * The one command shape a listing's per-row {@code call}s share — the row's own name swapped for a
     * placeholder — and, for each row, its commands only where substituting its name into that shape would not
     * reproduce them exactly.
     *
     * @param pattern the shared command shape, or {@code null} when no row carries a command
     * @param unfit per row, the commands the shape cannot spell, in order
     */
    private record Drill(String pattern, List<List<String>> unfit) {

        static Drill of(List<String> literals, List<String> calls, String placeholder) {
            return ofRows(literals.stream().map(List::of).toList(),
                    calls.stream().map(call -> call == null ? List.<String>of() : List.of(call)).toList(),
                    placeholder);
        }

        /** Each row with any number of commands, {@code literals} naming each one in the same order. */
        static Drill ofRows(List<List<String>> literals, List<List<String>> calls, String placeholder) {
            Map<String, Integer> shapes = new LinkedHashMap<>();
            for (int row = 0; row < calls.size(); row++) {
                for (int i = 0; i < calls.get(row).size(); i++) {
                    String shape = shapeOf(calls.get(row).get(i), literals.get(row).get(i), placeholder);
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
            for (int row = 0; row < calls.size(); row++) {
                List<String> own = new ArrayList<>();
                for (int i = 0; i < calls.get(row).size(); i++) {
                    String call = calls.get(row).get(i);
                    if (pattern == null || !call.equals(pattern.replace(placeholder, literals.get(row).get(i)))) {
                        own.add(call);
                    }
                }
                unfit.add(List.copyOf(own));
            }
            return new Drill(pattern, List.copyOf(unfit));
        }

        /** {@code call} with its last whole-word occurrence of {@code literal} replaced, or {@code null}. */
        private static String shapeOf(String call, String literal, String placeholder) {
            String needle = " " + literal;
            for (int at = call.lastIndexOf(needle); at >= 0; at = call.lastIndexOf(needle, at - 1)) {
                int end = at + needle.length();
                if (end == call.length() || call.charAt(end) == ' ') {
                    return call.substring(0, at + 1) + placeholder + call.substring(end);
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
            if (paging == null) {
                if (remaining > 0) {
                    more.add("... " + remaining + " more, narrow further");
                }
            } else if (remaining > 0) {
                more.add("... " + remaining + " more (page " + paging.page() + " of " + paging.pages() + ")");
            } else if (paging.pages() > 1) {
                more.add("Last page (page " + paging.page() + " of " + paging.pages() + ")");
            }
        }

        void next(String command) {
            if (command != null) {
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
