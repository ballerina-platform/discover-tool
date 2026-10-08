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

package io.ballerina.tools.discover.views;

import io.ballerina.tools.discover.Failure;
import io.ballerina.tools.discover.LoadedPackage;
import io.ballerina.tools.discover.Result;
import io.ballerina.tools.discover.Texts;
import io.ballerina.tools.discover.model.Bindings;
import io.ballerina.tools.discover.model.Fn;
import io.ballerina.tools.discover.model.ModuleRef;
import io.ballerina.tools.discover.model.Param;
import io.ballerina.tools.discover.model.Service;
import io.ballerina.tools.discover.model.TypeRef;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.Signatures;
import io.ballerina.tools.discover.render.TypeDefs;
import io.ballerina.tools.discover.symbols.Declarations;
import io.ballerina.tools.discover.symbols.Filter;
import io.ballerina.tools.discover.symbols.Names;
import io.ballerina.tools.discover.symbols.PathTree;
import io.ballerina.tools.discover.symbols.Surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@code client} / {@code service} / {@code class} / {@code funcs} — one implementation, four scopes.
 *
 * <p>The four verbs differ ONLY in which slice of {@link Surface} they address. Everything below — how a
 * positional resolves, how a selector is parsed, how much is printed and which next command is offered — is one
 * code path, because four copies of it would be four places for the same rule to rot separately. {@code service} adds
 * exactly one thing the other three do not carry — the listener a container's pairings name — folded into the
 * shared {@code note}/roster machinery ({@link #listenerNote}, {@link #listenerNames}) rather than a parallel path.
 *
 * <p><b>THE PARSER CANNOT BE DECIDED PER VERB.</b> In Ballerina a client IS a class, so {@code ballerina/http:Client}
 * is a legal argument to both {@code client} and {@code class} and declares seven resource functions either way.
 * Confining HTTP-verb parsing to one verb would make {@code class ballerina/http Client delete repos/…} fail on a
 * container that has exactly that operation. So the CONTAINER is resolved first and the selector grammar is read
 * off what that container declares.
 *
 * <p><b>A WRONG GUESS COSTS A LINE, NOT A ROUND TRIP.</b> Three rules do that work: a name that is a MEMBER rather
 * than a container still resolves and names its owner; a symbol of another kind still renders, with one line
 * saying which bucket is canonical; and an exact one-of-many name match prints the SIGNATURE rather than the name
 * back.
 *
 * <p><b>OUTPUT SIZE IS AN ENTRY CEILING, NOT A BYTE BUDGET.</b> The RFC caps a listing at {@value #MAX_ENTRIES}
 * entries: over it, resource paths selected by a path GROUP by segment, and every listing PAGES with
 * {@code --page}, disclosing {@code shown}/{@code total} and the exact next command rather than silently degrading.
 *
 * <p><b>THE VIEWS QUOTE, THEY NEVER RE-SPELL.</b> Every declaration printed here comes from
 * {@link Signatures} or {@link TypeDefs} byte-for-byte. That is what {@code ViewsAgreeTest} pins, and the reason
 * it is a gate rather than a suite: a summariser permitted to invent a shorter form must pick one, and no test
 * can catch a spelling nothing else in the tool produces.
 *
 * @since 0.1.0
 */
public final class Containers {

    /**
     * The RFC's output-size ceiling: at most this many entries in one listing. Applies to a roster of
     * containers, a grouped path listing, a flat resource listing and a method listing alike — the one number
     * every "too many to show" decision in this class is made against.
     */
    public static final int MAX_ENTRIES = 40;

    /**
     * The {@code number}-th {@value #MAX_ENTRIES}-wide window into a {@code total}-sized list, shared by every
     * paginated listing, {@link Readme}'s included.
     *
     * <p>A page outside the listing is a usage failure naming the valid range, never an empty answer: an empty
     * page with no {@code next} reads exactly like a listing that has nothing in it.
     *
     * @param number the page served, 1-indexed
     * @param pages how many pages the whole listing spans
     * @param from the inclusive start index into the full list
     * @param to the exclusive end index into the full list
     * @param total the size of the full list
     */
    public record Page(int number, int pages, int from, int to, int total) {

        /**
         * @param command the command that produced the listing, without {@code --page}, for the failure's
         *     suggestion
         */
        public static Result<Page> of(int requested, int total, String command) {
            int pages = Math.max(1, (total + MAX_ENTRIES - 1) / MAX_ENTRIES);
            if (requested < 1 || requested > pages) {
                return Result.err(new Failure.Validation(
                        "Page " + requested + " is out of range: this listing has " + total + " entries on "
                                + pages + (pages == 1 ? " page." : " pages."),
                        "Pass a page from 1 to " + pages + ", e.g. `" + command + " --page " + pages + "`."));
            }
            int from = (requested - 1) * MAX_ENTRIES;
            return Result.ok(new Page(requested, pages, from, Math.min(from + MAX_ENTRIES, total), total));
        }

        /** What a response reports about this page — {@code null} when the whole listing fit on one. */
        public DiscoverResult.Paging paging() {
            return pages == 1 ? null : new DiscoverResult.Paging(number, pages, total - to);
        }

        /**
         * The part of one section this page covers, when the listing is several sections laid end to end.
         *
         * @param offset where {@code section} starts in the whole listing
         */
        public <T> List<T> slice(List<T> section, int offset) {
            int start = Math.clamp(from - offset, 0, section.size());
            int end = Math.clamp(to - offset, 0, section.size());
            return section.subList(start, end);
        }

        /** The command for the page after this one, or {@code null} on the last. */
        public String next(String command) {
            return number < pages ? command + " --page " + (number + 1) : null;
        }
    }

    /**
     * How many additional path-grouping levels are allowed beyond the top-level segment, surveyed against real
     * connectors (the RFC's own check: seven connectors, three levels needed at most, four is one level of
     * margin). Counted in LITERAL segments, since grouping is transparent through path parameters. Past this
     * depth, a still-oversized group is listed flat and paged rather than subdivided again, so a group can never
     * fragment into an unnavigable tree of one-entry leaves.
     */
    private static final int MAX_GROUP_DEPTH = 4;

    /** Accessors a resource function may declare. Not a closed set — see {@link #isAccessor}. */
    private static final Pattern ACCESSOR = Pattern.compile("[a-z][a-zA-Z0-9]*");

    private Containers() {
    }

    /**
     * @param selectors everything after the package: a container name, a member, an accessor and a path
     * @param filter the {@code --filter} keyword, or {@code null}
     * @param page {@code --page}, 1-indexed, for any listing over the ceiling
     */
    public record Options(List<String> selectors, String filter, int page) {

        public static Options bare() {
            return new Options(List.of(), null, 1);
        }

        public Options(List<String> selectors) {
            this(selectors, null, 1);
        }

        public boolean filtered() {
            return filter != null && !filter.isBlank();
        }
    }

    public static Result<DiscoverResult> render(LoadedPackage loaded, Surface.Scope scope, Options options) {
        return render(loaded, scope, options, null);
    }

    /** {@code note}: the advisory a caller routed here by another bucket gets, shown as the answer's own note. */
    public static Result<DiscoverResult> render(
            LoadedPackage loaded, Surface.Scope scope, Options options, String note) {
        if (scope == Surface.Scope.SERVICE) {
            loaded = loaded.withBindings();
        }
        List<Surface.Container> containers = Surface.of(loaded.library(), scope);
        if (containers.isEmpty()) {
            return elsewhere(loaded, scope, options);
        }

        List<String> selectors = options.selectors();
        if (selectors.isEmpty()) {
            // Another module's service type has nothing here to open; the roster is what carries its command.
            if (containers.size() == 1 && foreignPairing(containers.get(0)).isEmpty()) {
                return answer(loaded, scope, containers.get(0), List.of(), options, note);
            }
            return roster(loaded, scope, containers, options);
        }

        // 1. An exact container name always wins, so no casing heuristic is needed anywhere. `client sheets
        //    Client` is the container `Client` even in a package that also declares a member spelled that way.
        Optional<Surface.Container> named = Surface.byName(containers, selectors.get(0));
        if (named.isPresent()) {
            return answer(loaded, scope, named.get(), selectors.subList(1, selectors.size()), options, note);
        }

        // 2. One container in scope: whatever was typed is a selector for it.
        if (containers.size() == 1) {
            return answer(loaded, scope, containers.get(0), selectors, options, note);
        }

        // 3. An EXACT member name or a resolving path, across every container in scope.
        Result<DiscoverResult> exact = byOwner(loaded, scope, containers, selectors, options, note, true);
        if (exact != null) {
            return exact;
        }

        // 4. Another verb's scope. BEFORE the substring pass, and that ordering is load-bearing: `Cookie` is a
        //    CLASS, and it is also a substring of `getCookieStore`, which two of http's ten clients declare. Run
        //    the fuzzy pass first and `client ballerina/http Cookie` answers with a roster of two clients that
        //    happen to contain those letters instead of routing to the class the caller plainly named.
        Result<DiscoverResult> other = elsewhereIfKnown(loaded, scope, options);
        if (other != null) {
            return other;
        }

        // 5. A substring of a member name, which is the widening a caller relies on when they half-remember one.
        Result<DiscoverResult> fuzzy = byOwner(loaded, scope, containers, selectors, options, note, false);
        if (fuzzy != null) {
            return fuzzy;
        }

        return elsewhere(loaded, scope, options);
    }

    private static Result<DiscoverResult> byOwner(
            LoadedPackage loaded, Surface.Scope scope, List<Surface.Container> containers,
            List<String> selectors, Options options, String note, boolean exactly) {
        Map<Surface.Container, List<Entry>> owners = new LinkedHashMap<>();
        Map<Surface.Container, Integer> readingFewer = new LinkedHashMap<>();
        for (Surface.Container container : containers) {
            List<Entry> hits = exactly
                    ? selectExactly(container, selectors)
                    : select(container, selectors);
            if (hits.isEmpty()) {
                continue;
            }
            int read = consumed(container, selectors);
            if (read == selectors.size()) {
                owners.put(container, hits);
            } else {
                readingFewer.put(container, read);
            }
        }
        if (owners.isEmpty() && readingFewer.size() == 1) {
            return answer(loaded, scope, readingFewer.keySet().iterator().next(), selectors, options, note);
        }
        if (owners.isEmpty() && !readingFewer.isEmpty()) {
            return Result.err(unread(loaded, scope, readingFewer, selectors, options));
        }
        if (owners.isEmpty()) {
            return null;
        }
        if (owners.size() == 1) {
            Surface.Container owner = owners.keySet().iterator().next();
            return answer(loaded, scope, owner, selectors, options,
                    note != null ? note : ownerNote(loaded, scope, owner, selectors));
        }
        return owners(loaded, scope, owners, selectors, options, note);
    }

    private static boolean knows(Surface.Container container, List<String> selectors, boolean exactly) {
        return !select(container, selectors, exactly).isEmpty();
    }

    private static boolean namesMember(Surface.Container container, String token) {
        return select(container, List.of(token)).stream()
                .anyMatch(entry -> !(entry.fn() instanceof Fn.Resource));
    }

    private static Result<DiscoverResult> elsewhereIfKnown(
            LoadedPackage loaded, Surface.Scope scope, Options options) {
        String token = options.selectors().get(0);
        for (Surface.Scope other : Surface.Scope.values()) {
            if (other == scope) {
                continue;
            }
            List<Surface.Container> containers = Surface.of(loaded.library(), other);
            if (Surface.byName(containers, token).isPresent()
                    || containers.stream().anyMatch(container -> knows(container, options.selectors(), true))) {
                return render(loaded, other, options, kindNote(loaded, other, options.selectors()));
            }
        }
        if (options.selectors().size() == 1 && Types.declares(loaded, token)) {
            return Types.render(loaded, new Types.Options(options.selectors(), options.filter(), options.page(),
                    kindNote(loaded, Types.BUCKET, options.selectors())));
        }
        return null;
    }

    private static Result<DiscoverResult> elsewhere(
            LoadedPackage loaded, Surface.Scope scope, Options options) {
        if (options.selectors().isEmpty()) {
            return Result.ok(emptyBucket(loaded, scope));
        }
        // The exact pass first, then a substring of a member in another scope — the same two-pass order the
        // in-scope resolution uses, for the same reason.
        Result<DiscoverResult> known = elsewhereIfKnown(loaded, scope, options);
        if (known != null) {
            return known;
        }
        for (Surface.Scope other : Surface.Scope.values()) {
            if (other == scope) {
                continue;
            }
            if (Surface.of(loaded.library(), other).stream()
                    .anyMatch(container -> knows(container, options.selectors(), false))) {
                return render(loaded, other, options, kindNote(loaded, other, options.selectors()));
            }
        }
        return Result.err(notFound(loaded, scope, options.selectors().get(0),
                Declarations.index(loaded.library().addressable())));
    }

    private static String kindNote(LoadedPackage loaded, String verb, List<String> selectors) {
        return "'" + selectors.get(0) + "' is addressed by " + verb + " — showing it. Canonical: "
                + "bal discover " + loaded.pkgArgument() + " " + verb + shellWords(selectors);
    }

    private static String kindNote(LoadedPackage loaded, Surface.Scope scope, List<String> selectors) {
        List<String> spelled = new ArrayList<>(selectors);
        Surface.byName(Surface.of(loaded.library(), scope), selectors.get(0))
                .ifPresent(container -> spelled.set(0, container.name()));
        return "'" + selectors.get(0) + "' is addressed by " + scope.verb() + " — showing it. Canonical: "
                + "bal discover " + loaded.pkgArgument() + " " + scope.verb() + shellWords(spelled);
    }

    private static String ownerNote(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container owner, List<String> selectors) {
        return "'" + selectors.get(0) + "' is declared on " + owner.label() + " — showing it. Canonical: "
                + "bal discover " + loaded.pkgArgument() + " " + scope.verb() + " " + shellWord(owner.name())
                + shellWords(selectors);
    }

    private static String mergeNotes(String first, String second) {
        if (first == null) {
            return second;
        }
        return second == null ? first : first + "; " + second;
    }

    private static String listenerNames(Surface.Container container) {
        List<Service> pairings = container.pairings();
        if (pairings.isEmpty()) {
            return null;
        }
        return pairings.stream()
                .map(Service::listener)
                .distinct()
                .collect(Collectors.joining(", "));
    }

    private static String listenerNote(LoadedPackage loaded, Surface.Container container) {
        if (Surface.isUnattachable(loaded.library(), container)) {
            return "not attachable to any listener this package declares";
        }
        String names = listenerNames(container);
        if (names == null) {
            return null;
        }
        Optional<Service> foreign = foreignPairing(container);
        if (foreign.isPresent()) {
            return "binds to " + names + "; declared in " + foreign.get().declaredIn().map(ModuleRef::coordinate)
                    .orElse("") + foreignCommand(loaded, foreign.get()).map(command -> " — " + command).orElse("");
        }
        return unsettled(container)
                .map(binding -> "may bind to " + names + " — not confirmed: " + binding.reason())
                .orElse("binds to " + names);
    }

    private static Optional<Bindings.Binding> unsettled(Surface.Container container) {
        return container.pairings().stream().map(Service::binding).filter(Bindings.Binding::isUnsettled).findFirst();
    }

    // The suggestion must never rebuild the command without the argument that failed.
    private static Failure notFound(
            LoadedPackage loaded, Surface.Scope scope, String token, Declarations index) {
        String pkg = loaded.pkgArgument();
        List<String> candidates = new ArrayList<>();
        for (Surface.Scope other : Surface.Scope.values()) {
            Surface.of(loaded.library(), other).forEach(container -> {
                if (!container.isModule()) {
                    candidates.add(container.name());
                }
                candidates.addAll(container.memberNames());
            });
        }
        List<String> near = Names.nearMisses(token, List.copyOf(new LinkedHashSet<>(candidates)));
        if (near.isEmpty()) {
            near = Names.nearMisses(token, index.names());
        }
        String suggestion = near.isEmpty()
                ? "Nothing in " + loaded.label() + " is named anything like that. List what is there: "
                        + "`bal discover " + pkg + " " + scope.verb() + "`."
                : "The candidates are the closest names in the package. Re-run with one of them, or list what "
                        + "is there: `bal discover " + pkg + " " + scope.verb() + "`.";
        return new Failure.SymbolNotFound(loaded.qualified().qualified(), loaded.version().text(), loaded.module(),
                List.of(token), near, suggestion);
    }

    private static DiscoverResult emptyBucket(LoadedPackage loaded, Surface.Scope scope) {
        return emptyBucket(loaded, scope.verb());
    }

    /** A bucket with nothing in it — {@code type} included — and every other bucket that holds something. */
    static DiscoverResult emptyBucket(LoadedPackage loaded, String bucket) {
        String pkg = loaded.pkgArgument();
        List<DiscoverResult.EmptyBucket.Elsewhere> elsewhere = new ArrayList<>();
        for (Surface.Scope other : Surface.Scope.values()) {
            List<Surface.Container> containers = Surface.of(loaded.library(), other);
            if (other.verb().equals(bucket) || containers.isEmpty()) {
                continue;
            }
            int count = other == Surface.Scope.MODULE ? containers.get(0).functions().size() : containers.size();
            elsewhere.add(new DiscoverResult.EmptyBucket.Elsewhere(
                    other.verb(), count, "bal discover " + pkg + " " + other.verb()));
        }
        int types = Types.count(loaded);
        if (types > 0 && !Types.BUCKET.equals(bucket)) {
            elsewhere.add(new DiscoverResult.EmptyBucket.Elsewhere(
                    Types.BUCKET, types, "bal discover " + pkg + " " + Types.BUCKET));
        }
        return new DiscoverResult.EmptyBucket(bucket, List.copyOf(elsewhere), loaded.warning());
    }

    private static Result<DiscoverResult> roster(
            LoadedPackage loaded, Surface.Scope scope, List<Surface.Container> containers, Options options) {
        String pkg = loaded.pkgArgument();
        String bucket = "bal discover " + pkg + " " + scope.verb();
        List<Surface.Container> selected = options.filtered()
                ? containers.stream()
                        .filter(container -> namedByFilter(container, options)
                                || !filterEntries(container, options).surface().isEmpty())
                        .toList()
                : containers;

        if (selected.isEmpty()) {
            List<String> names = containers.stream().map(Surface.Container::name).toList();
            return Result.ok(new DiscoverResult.NoMatch(options.filter(), null,
                    Names.nearMisses(options.filter(), names), List.of(),
                    roster(loaded, scope, containers, Options.bare()).value(), bucket,
                    DiscoverResult.Documented.NONE, null, loaded.warning(), null));
        }

        List<Surface.Container> bindable = selected.stream()
                .filter(container -> !Surface.isUnattachable(loaded.library(), container))
                .toList();
        List<Surface.Container> unattachable = selected.stream()
                .filter(container -> Surface.isUnattachable(loaded.library(), container))
                .toList();
        String command = bucket + filterArgument(options);
        Result<Page> page = Page.of(options.page(), bindable.size() + unattachable.size(), command);
        if (!page.isOk()) {
            return page.cast();
        }
        Page window = page.value();
        List<DiscoverResult.ContainerRoster.Container> items = window.slice(bindable, 0).stream()
                .map(container -> new DiscoverResult.ContainerRoster.Container(
                        container.name(),
                        (int) container.operations().stream().map(PathTree.Operation::segments).distinct().count(),
                        (int) container.standalone().stream().filter(Fn.Remote.class::isInstance).count(),
                        (int) container.standalone().stream().filter(Fn.Normal.class::isInstance).count(),
                        listenerNames(container),
                        unsettled(container).map(Bindings.Binding::label).orElse(null),
                        openCommand(loaded, scope, container, options)))
                .toList();
        List<DiscoverResult.ContainerRoster.NotAttachable> notAttachable =
                window.slice(unattachable, bindable.size()).stream()
                        .map(container -> new DiscoverResult.ContainerRoster.NotAttachable(
                                container.name(), openCommand(loaded, scope, container, options)))
                        .toList();
        return Result.ok(new DiscoverResult.ContainerRoster(items, bindable.size() + unattachable.size(),
                window.paging(), window.next(command), loaded.warning(), notAttachable, unattachable.size()));
    }

    private static boolean namedByFilter(Surface.Container container, Options options) {
        return options.filtered() && Filter.matches(options.filter(), container.name());
    }

    private static String openCommand(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, Options options) {
        Optional<String> foreign = foreignPairing(container).flatMap(service -> foreignCommand(loaded, service));
        if (foreign.isPresent()) {
            return foreign.get();
        }
        return "bal discover " + loaded.pkgArgument() + " " + scope.verb() + " " + shellWord(container.name())
                + (namedByFilter(container, options) ? "" : filterArgument(options));
    }

    private static Optional<Service> foreignPairing(Surface.Container container) {
        return container.pairings().stream().filter(service -> service.declaredIn().isPresent()).findFirst();
    }

    private static Optional<String> foreignCommand(LoadedPackage loaded, Service service) {
        return loaded.argumentFor(service.declaredIn().orElseThrow())
                .map(target -> "bal discover " + target + " service " + shellWord(service.name()));
    }

    private static Result<DiscoverResult> owners(
            LoadedPackage loaded, Surface.Scope scope, Map<Surface.Container, List<Entry>> owners,
            List<String> selectors, Options options, String note) {
        String bucket = "bal discover " + loaded.pkgArgument() + " " + scope.verb();
        String member = shellWords(selectors);
        String command = bucket + member + filterArgument(options);
        Result<Page> page = Page.of(options.page(), owners.size(), command);
        if (!page.isOk()) {
            return page.cast();
        }
        Page window = page.value();
        List<DiscoverResult.Owners.Owner> listed = owners.entrySet().stream()
                .skip(window.from())
                .limit(window.to() - window.from())
                .map(entry -> new DiscoverResult.Owners.Owner(entry.getKey().name(), entry.getValue().size(),
                        bucket + " " + shellWord(entry.getKey().name()) + member))
                .toList();
        return Result.ok(new DiscoverResult.Owners(String.join(" ", selectors), listed, owners.size(),
                window.paging(), window.next(command), loaded.warning(), note));
    }

    /**
     * One callable, with the path it is reached by when it has one.
     *
     * @param fn the callable
     * @param path the path it is reached by, empty when it has none
     */
    public record Entry(Fn fn, List<String> path) {

        /** How it is addressed: {@code get repos/:owner} for a resource, the bare name otherwise. */
        public String label() {
            return switch (fn) {
                case Fn.Resource resource -> resource.accessor() + " " + String.join("/", path);
                case Fn.Standalone named -> named.name();
                case Fn.Constructor ignored -> Fn.Constructor.NAME;
            };
        }

        public String callForm() {
            return switch (fn) {
                case Fn.Remote ignored -> "->";
                case Fn.Resource ignored -> "->";
                case Fn.Normal ignored -> ".";
                case Fn.Constructor ignored -> "new";
            };
        }
    }

    private static List<Entry> select(Surface.Container container, List<String> selectors) {
        return select(container, selectors, false);
    }

    // No substring widening here: it would let letters inside an unrelated member beat a container name in
    // another scope.
    private static List<Entry> selectExactly(Surface.Container container, List<String> selectors) {
        return select(container, selectors, true);
    }

    private static List<Entry> select(
            Surface.Container container, List<String> selectors, boolean exactly) {
        List<Entry> all = entriesOf(container);
        if (selectors.isEmpty()) {
            return all;
        }
        List<String> read = selectors.subList(0, consumed(container, selectors));
        if (container.hasPaths()) {
            List<Entry> byPath = selectByPath(container, read);
            if (!byPath.isEmpty()) {
                return byPath;
            }
        }
        // Two tokens are a path and its accessor. One that resolved nothing is a no-match, never a name filter on
        // the accessor.
        if (read.size() > 1) {
            return List.of();
        }
        String token = read.get(0);
        // `new` is the constructor, which Ballerina spells `init`. An INPUT alias only — the document still prints
        // `init`, because it prints what the package declares.
        if (token.equalsIgnoreCase("new") && container.constructor().isPresent()) {
            List<Entry> constructor = all.stream().filter(entry -> entry.fn() instanceof Fn.Constructor).toList();
            if (!constructor.isEmpty()) {
                return constructor;
            }
        }
        // Against BOTH spellings of the token, because a label is built for prose while the token may have been
        // copied out of a fence. `post chat\.postMessage` and `post chat.postMessage` are the same operation.
        String readable = PathTree.readableSelector(token);
        List<Entry> exact = all.stream()
                .filter(entry -> entry.label().equals(token) || entry.label().equals(readable))
                .toList();
        if (exactly || !exact.isEmpty()) {
            return exact;
        }
        return all.stream().filter(entry -> matchesName(entry, token)).toList();
    }

    /**
     * A path request, split into the accessor that filters it and the path that anchors it.
     *
     * @param accessor the accessor that filters it, {@code null} when none was given
     * @param tokens the path tokens that anchor it
     */
    private record PathRequest(String accessor, List<String> tokens) { }

    private static Optional<PathRequest> pathRequest(Surface.Container container, List<String> selectors) {
        if (!container.hasPaths()) {
            return Optional.empty();
        }
        if (selectors.size() >= 2 && isAccessor(container, selectors.get(0))) {
            return Optional.of(new PathRequest(selectors.get(0), PathTree.splitPath(selectors.get(1))));
        }
        // The RFC's own order, and the one every `command` field prints: the path, then the accessor.
        if (selectors.size() == 2 && isAccessor(container, selectors.get(1))) {
            return Optional.of(new PathRequest(selectors.get(1), PathTree.splitPath(selectors.get(0))));
        }
        if (selectors.size() == 1) {
            // An accessor and a path in ONE argument, as copying a line out of a fenced signature produces:
            // `get [PathParamType ...path]`. Safe because a member name cannot contain whitespace, and gated on the
            // container declaring the accessor — otherwise `Producer send` would parse `Producer` as one.
            String[] words = selectors.get(0).trim().split("\\s+", 2);
            if (words.length == 2 && isAccessor(container, words[0])) {
                return Optional.of(new PathRequest(words[0], PathTree.splitPath(words[1])));
            }
            return Optional.of(new PathRequest(null, PathTree.splitPath(selectors.get(0))));
        }
        return Optional.empty();
    }

    private static int consumed(Surface.Container container, List<String> selectors) {
        if (selectors.size() < 2 || !container.hasPaths()) {
            return Math.min(selectors.size(), 1);
        }
        if (!selectByPath(container, selectors.subList(0, 2)).isEmpty()) {
            return 2;
        }
        return namesMember(container, selectors.get(0)) ? 1 : 2;
    }

    private static Failure unread(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            Options options) {
        int consumed = consumed(container, selectors);
        String takes = container.hasPaths()
                ? "a member name, or a resource path and its accessor"
                : "one " + (container.isModule() ? "function" : "member") + " name";
        String where = container.isModule() ? scope.verb() : container.name();
        String base = baseCommand(loaded, scope, container);
        Optional<String> joined = joinedPath(container, selectors)
                .map(path -> "Join the path's segments with `/` in one argument: `" + base + path.arguments()
                        + filterArgument(options) + "`.");
        List<String> read = selectors.subList(0, consumed);
        List<Entry> selected = select(container, read);
        List<String> kept = selected.size() == 1 ? spelled(selected.get(0), read) : read;
        return unread(selectors, consumed, where + " takes " + takes,
                joined.orElse(drop(kept, selectors.size() - consumed, base, options)));
    }

    private static Failure unread(
            LoadedPackage loaded, Surface.Scope scope, Map<Surface.Container, Integer> owners,
            List<String> selectors, Options options) {
        String names = owners.keySet().stream().map(Surface.Container::name).collect(Collectors.joining(", "));
        int consumed = Collections.min(owners.values());
        return unread(selectors, consumed, "'" + String.join(" ", selectors.subList(0, consumed))
                        + "' is declared on " + names + ", and none of them reads all of '"
                        + String.join(" ", selectors) + "'",
                drop(selectors.subList(0, consumed), selectors.size() - consumed,
                        "bal discover " + loaded.pkgArgument() + " " + scope.verb(), options));
    }

    private static Failure unread(List<String> selectors, int consumed, String reason, String suggestion) {
        List<String> unread = selectors.subList(consumed, selectors.size());
        return new Failure.Validation(
                "Unexpected " + (unread.size() == 1 ? "argument " : "arguments ")
                        + unread.stream().map(word -> "'" + word + "'").collect(Collectors.joining(" "))
                        + " after '" + String.join(" ", selectors.subList(0, consumed)) + "': " + reason + ".",
                suggestion);
    }

    private static String drop(List<String> kept, int dropped, String base, Options options) {
        return "Drop " + (dropped == 1 ? "it" : "them") + ": `" + base + shellWords(kept) + filterArgument(options)
                + "`.";
    }

    /**
     * A path typed as separate words, joined into the one argument that resolves, with its accessor when one was
     * typed first or last: {@code repos :owner :repo} is {@code "repos/:owner/:repo"}.
     *
     * @param path the path it resolved to, spelled as listings print it ({@code gists/'public} for
     *     {@code gists public})
     * @param accessor the accessor typed with it, or {@code null}
     */
    private record JoinedPath(String path, String accessor) {

        /** The arguments that reach it, each with its leading space. */
        String arguments() {
            return " " + shellWord(path) + (accessor == null ? "" : " " + accessor);
        }
    }

    private static Optional<JoinedPath> joinedPath(Surface.Container container, List<String> selectors) {
        if (!container.hasPaths()) {
            return Optional.empty();
        }
        String accessor = null;
        List<String> segments = selectors;
        if (isAccessor(container, selectors.get(0))) {
            accessor = selectors.get(0);
            segments = selectors.subList(1, selectors.size());
        } else if (isAccessor(container, selectors.get(selectors.size() - 1))) {
            accessor = selectors.get(selectors.size() - 1);
            segments = selectors.subList(0, selectors.size() - 1);
        }
        if (segments.size() < 2) {
            return Optional.empty();
        }
        if (!(PathTree.locate(PathTree.build(container.operations()), PathTree.splitPath(String.join("/", segments)))
                .resolution() instanceof PathTree.Resolution.Found found)) {
            return Optional.empty();
        }
        return Optional.of(new JoinedPath(pathName(found.path()), accessor));
    }

    /**
     * Two selectors that are not a path and a declared accessor, split into the one that resolves as a path and
     * the other: {@code "gists/'public" head}, {@code gists zzz}.
     *
     * @param path the selector that resolves as a path
     * @param entries the operations at or beneath it
     */
    private record UnresolvedPair(String path, List<Entry> entries) {

        List<String> accessors() {
            return entries.stream().map(Entry::fn).filter(Fn.Resource.class::isInstance)
                    .map(fn -> ((Fn.Resource) fn).accessor()).distinct().toList();
        }
    }

    private static Optional<UnresolvedPair> unresolvedPair(Surface.Container container, List<String> selectors) {
        if (!container.hasPaths() || selectors.size() != 2) {
            return Optional.empty();
        }
        Optional<PathRequest> request = pathRequest(container, selectors);
        if (request.isPresent()) {
            if (!selectByPath(container, selectors).isEmpty()) {
                return Optional.empty();
            }
            // A path that resolves, with an accessor only another path declares.
            String path = selectors.get(selectors.get(0).equals(request.get().accessor()) ? 1 : 0);
            List<Entry> entries = selectByPath(container, List.of(path));
            return entries.isEmpty() ? Optional.empty() : Optional.of(new UnresolvedPair(path, entries));
        }
        for (String token : selectors) {
            List<Entry> entries = selectByPath(container, List.of(token));
            if (!entries.isEmpty()) {
                return Optional.of(new UnresolvedPair(token, entries));
            }
        }
        return Optional.empty();
    }

    private static Optional<PathTree.Located> located(
            Surface.Container container, List<String> selectors) {
        return pathRequest(container, selectors)
                .map(request -> PathTree.locate(PathTree.build(container.operations()), request.tokens()));
    }

    private static List<Entry> selectByPath(Surface.Container container, List<String> selectors) {
        Optional<PathRequest> request = pathRequest(container, selectors);
        Optional<PathTree.Located> located = located(container, selectors);
        if (request.isEmpty() || located.isEmpty()
                || !(located.get().resolution() instanceof PathTree.Resolution.Found found)) {
            return List.of();
        }
        String accessor = request.get().accessor();
        if (accessor == null) {
            return PathTree.operationsUnder(found.node()).stream()
                    .map(operation -> new Entry(operation.fn(), operation.segments()))
                    .toList();
        }
        // A path AND an accessor name one operation when the path declares that accessor itself — the RFC's
        // signature drill-down, and what every resource `commands` value prints. Only when it does not is the
        // accessor a filter over everything beneath the path.
        List<Entry> exact = accessorsOf(found.node().operations(), accessor);
        return exact.isEmpty() ? accessorsOf(PathTree.operationsUnder(found.node()), accessor) : exact;
    }

    private static List<Entry> accessorsOf(List<PathTree.Operation> operations, String accessor) {
        return operations.stream()
                .filter(operation -> operation.fn().accessor().equalsIgnoreCase(accessor))
                .map(operation -> new Entry(operation.fn(), operation.segments()))
                .toList();
    }

    private static String pathNote(Surface.Container container, List<String> selectors) {
        Optional<PathTree.Located> found = located(container, selectors);
        if (found.isEmpty() || !(found.get().resolution() instanceof PathTree.Resolution.Found node)) {
            return null;
        }
        PathTree.Located located = found.get();
        List<String> parts = new ArrayList<>();
        if (located.relocated() && !located.alternatives().isEmpty()) {
            parts.add("relocated to " + pathName(node.path())
                    + " — the only match for that path under the requested prefix");
        }
        for (PathTree.Descent.Sibling other : node.alsoMatched()) {
            parts.add("also matched " + pathName(other.path()) + " ("
                    + Texts.count(other.total()) + "), not included here");
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    private static boolean isAccessor(Surface.Container container, String token) {
        if (!ACCESSOR.matcher(token).matches()) {
            return false;
        }
        return container.operations().stream()
                .anyMatch(operation -> operation.fn().accessor().equalsIgnoreCase(token));
    }

    private static boolean matchesName(Entry entry, String token) {
        return glob(token).matcher(entry.label()).matches()
                || glob(PathTree.readableSelector(token)).matcher(entry.label()).matches();
    }

    private static Pattern glob(String token) {
        String expanded = token.contains("*") ? token : "*" + token + "*";
        StringBuilder regex = new StringBuilder();
        String[] literals = expanded.split("\\*", -1);
        for (int index = 0; index < literals.length; index++) {
            if (index > 0) {
                regex.append(".*");
            }
            if (!literals[index].isEmpty()) {
                regex.append(Pattern.quote(literals[index]));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE);
    }

    private static List<Entry> entriesOf(Surface.Container container) {
        List<Entry> entries = new ArrayList<>();
        container.constructor().ifPresent(constructor -> entries.add(new Entry(constructor, List.of())));
        for (PathTree.Operation operation : container.operations()) {
            entries.add(new Entry(operation.fn(), operation.segments()));
        }
        container.standalone().forEach(fn -> entries.add(new Entry(fn, List.of())));
        return List.copyOf(entries);
    }

    private static Filter.Split<Entry> filterEntries(Surface.Container container, Options options) {
        return Filter.apply(options.filter(), entriesOf(container),
                entry -> Filter.surfaceOf(entry.fn()), entry -> Filter.docsOf(entry.fn()));
    }

    private static Result<DiscoverResult> answer(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            Options options, String note) {
        if (container.scope() == Surface.Scope.SERVICE) {
            // However the caller reached it — `class Interceptor` as well as `service Interceptor` — a service
            // type's answer says which listener it binds to, and that is settled from the source.
            loaded = loaded.withBindings();
            container = Surface.byName(Surface.of(loaded.library(), Surface.Scope.SERVICE), container.name())
                    .orElse(container);
        }
        if (consumed(container, selectors) < selectors.size()) {
            return Result.err(unread(loaded, scope, container, selectors, options));
        }
        note = mergeNotes(note, listenerNote(loaded, container));
        List<Entry> selected = select(container, selectors);
        List<DiscoverResult.Documented.Entry> documented = List.of();

        if (options.filtered()) {
            Filter.Split<Entry> split = Filter.apply(options.filter(), selected,
                    entry -> Filter.surfaceOf(entry.fn()), entry -> Filter.docsOf(entry.fn()));
            selected = split.surface();
            documented = documentedRows(split.documented(), baseCommand(loaded, scope, container));
        }

        if (selected.isEmpty() && selectors.isEmpty() && !options.filtered()) {
            return Result.ok(new DiscoverResult.MethodList(containerName(container), List.of(), 0, 0, null, null,
                    DiscoverResult.Documented.NONE, loaded.warning(), note));
        }
        if (selected.isEmpty()) {
            return nothingMatched(loaded, scope, container, selectors, options, documented, note);
        }
        if (selected.size() == 1) {
            return signature(loaded, scope, container, selectors, selected.get(0), options, documented, note);
        }
        return listing(loaded, scope, container, selectors, selected, options, documented, loaded.warning(), note);
    }

    /**
     * Documentation-only matches when they are an answer's only list — beside one signature, or as all a
     * {@code --filter} found — paged on their own once over the ceiling, so the command that produced the answer,
     * turned a page, reaches the rest. At or under the ceiling this is page 1 whatever was asked: the answer is
     * not paged, and {@code Cli} rejects any other {@code --page} against it.
     */
    static Result<Page> documentedPage(List<DiscoverResult.Documented.Entry> documented, int page, String command) {
        return Page.of(documented.size() > MAX_ENTRIES ? page : 1, documented.size(), command);
    }

    /** The documentation-only matches on {@code window}, which come after the listing's {@code offset} entries. */
    static DiscoverResult.Documented documentedOn(
            Page window, List<DiscoverResult.Documented.Entry> documented, int offset) {
        return documented.isEmpty()
                ? DiscoverResult.Documented.NONE
                : new DiscoverResult.Documented(window.slice(documented, offset), documented.size());
    }

    private static Result<DiscoverResult> listing(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            List<Entry> selected, Options options, List<DiscoverResult.Documented.Entry> documented, String warning,
            String note) {
        List<Entry> callable = selected.stream()
                .filter(entry -> !(entry.fn() instanceof Fn.Constructor))
                .toList();
        long forms = callable.stream().map(entry -> entry.fn().getClass()).distinct().count();
        boolean hasResources = callable.stream().anyMatch(entry -> entry.fn() instanceof Fn.Resource);
        if (forms > 1) {
            return mixedAnswer(loaded, scope, container, selectors, callable, options, documented, warning, note);
        }
        if (hasResources) {
            return resourceAnswer(loaded, scope, container, selectors, callable, options, documented, warning, note);
        }
        return methodAnswer(loaded, scope, container, selectors, callable, options, documented, warning, note);
    }

    private static Result<DiscoverResult> nothingMatched(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            Options options, List<DiscoverResult.Documented.Entry> documented, String note) {
        String asked = options.filtered() ? options.filter() : String.join(" ", selectors);
        String command = baseCommand(loaded, scope, container);
        String repeated = command + selectorArguments(container, selectors) + filterArgument(options);
        Result<Page> page = documentedPage(documented, options.page(), repeated);
        if (!page.isOk()) {
            return page.cast();
        }
        Page window = page.value();

        // Rule 2 of segment location: more than one occurrence is LISTED and the request stops there. Picking
        // one is exactly the failure anchoring exists to prevent.
        List<List<String>> located = located(container, selectors)
                .map(PathTree.Located::alternatives)
                .orElse(List.of());
        List<DiscoverResult.NoMatch.Alternative> alternatives = located.size() > 1
                ? located.stream()
                        .map(path -> escapeKeywordSegments(String.join("/", path)))
                        .map(path -> new DiscoverResult.NoMatch.Alternative(path, command + " " + shellWord(path)))
                        .toList()
                : List.of();

        Optional<JoinedPath> joined = selectors.size() == 2 ? joinedPath(container, selectors) : Optional.empty();
        if (joined.isPresent()) {
            return Result.ok(new DiscoverResult.NoMatch(asked, containerName(container),
                    List.of(joined.get().path()), List.of(), null,
                    command + joined.get().arguments() + filterArgument(options), DiscoverResult.Documented.NONE,
                    null, loaded.warning(), note));
        }
        Optional<UnresolvedPair> pair = unresolvedPair(container, selectors);
        if (pair.isPresent()) {
            DiscoverResult forPath = listing(loaded, scope, container, List.of(pair.get().path()),
                    pair.get().entries(), Options.bare(), List.of(), null, null).value();
            return Result.ok(new DiscoverResult.NoMatch(asked, containerName(container), pair.get().accessors(),
                    List.of(), forPath, window.paging() == null ? command : window.next(repeated),
                    documentedOn(window, documented, 0), window.paging(), loaded.warning(), note));
        }
        List<Entry> all = entriesOf(container);
        List<String> names = new ArrayList<>();
        for (Entry entry : all) {
            String name = !(entry.fn() instanceof Fn.Resource) ? entry.label()
                    : entry.path().isEmpty() ? "." : entry.path().get(0);
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        DiscoverResult available = alternatives.isEmpty() && documented.isEmpty() && !all.isEmpty()
                ? listing(loaded, scope, container, List.of(), all, Options.bare(), List.of(), null, null).value()
                : null;
        return Result.ok(new DiscoverResult.NoMatch(asked, containerName(container),
                Names.nearMisses(asked, names), alternatives, available,
                window.paging() == null ? command : window.next(repeated),
                documentedOn(window, documented, 0), window.paging(), loaded.warning(), note));
    }

    private static Result<DiscoverResult> signature(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            Entry entry, Options options, List<DiscoverResult.Documented.Entry> documented, String note) {
        String command = baseCommand(loaded, scope, container) + selectorArguments(container, spelled(entry, selectors))
                + filterArgument(options);
        Result<Page> page = documentedPage(documented, options.page(), command);
        if (!page.isOk()) {
            return page.cast();
        }
        Page window = page.value();
        Fn fn = entry.fn();
        String declaration = container.isModule() && fn instanceof Fn.Standalone standalone
                ? Signatures.renderStandaloneFunction(standalone)
                : Signatures.renderMemberFunction(fn, "", Signatures.Detail.FULL);

        Set<String> inPath = new LinkedHashSet<>();
        if (fn instanceof Fn.Resource resource) {
            resource.paths().stream()
                    .filter(Fn.PathSegment.Parameter.class::isInstance)
                    .forEach(segment -> inPath.add(((Fn.PathSegment.Parameter) segment).name()));
        }
        List<DiscoverResult.Signature.Parameter> params = fn.params().stream()
                .filter(param -> !inPath.contains(param.name()))
                .map(param -> new DiscoverResult.Signature.Parameter(param.name(), Signatures.paramType(param),
                        param.form() == Param.Form.NORMAL ? param.defaultValue() : null,
                        switch (param.form()) {
                            case NORMAL -> null;
                            case INCLUSION -> "inclusion";
                            case REST -> "rest";
                        },
                        param.description()))
                .toList();

        Declarations index = Declarations.index(loaded.library().addressable());
        List<String> roots = Closure.rootsOf(fn, index);
        Closure.Result closure = roots.isEmpty()
                ? null
                : Closure.leaf(roots, index);
        List<DiscoverResult.Signature.Type> types = closure == null ? List.of() : closure.types(index);
        List<DiscoverResult.Method> omitted = closure == null
                ? List.of()
                : Types.omittedOf(loaded, closure);
        List<TypeRef> named = new ArrayList<>(fn.params().stream().map(Param::type).toList());
        if (fn.returns().hasType()) {
            named.add(fn.returns().type());
        }
        List<DiscoverResult.Foreign> foreign = Types.foreignOf(
                loaded, closure == null ? new Closure.Result(List.of(), List.of()) : closure, index, named);

        String kind = switch (fn) {
            case Fn.Resource ignored -> "resource";
            case Fn.Remote ignored -> "remote";
            case Fn.Normal ignored -> container.isModule() ? "function" : "normal";
            case Fn.Constructor ignored -> "constructor";
        };
        String name = switch (fn) {
            case Fn.Resource ignored -> null;
            case Fn.Standalone standalone -> standalone.name();
            case Fn.Constructor ignored -> Fn.Constructor.NAME;
        };
        return Result.ok(new DiscoverResult.Signature(
                container.isModule() ? null : container.name(), kind, name,
                fn instanceof Fn.Resource resource ? resource.accessor() : null,
                fn instanceof Fn.Resource ? pathName(entry.path()) : null,
                entry.callForm(), declaration, params, Signatures.returnType(fn), fn.isDeprecated(),
                types, omitted, closure == null ? 0 : closure.omitted().size(),
                closure == null ? null : Types.omittedNext(loaded, closure), foreign,
                documentedOn(window, documented, 0), window.paging(), window.next(command),
                loaded.warning(), mergeNotes(note, pathNote(container, selectors))));
    }

    private static Result<DiscoverResult> mixedAnswer(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            List<Entry> callable, Options options, List<DiscoverResult.Documented.Entry> documented, String warning,
            String note) {
        String base = baseCommand(loaded, scope, container);
        String command = base + selectorArguments(container, selectors) + filterArgument(options);
        List<DiscoverResult.ResourceList.Resource> resources = mergedResources(
                callable.stream().filter(entry -> entry.fn() instanceof Fn.Resource).toList(), base);
        List<DiscoverResult.Method> remote = methodsOf(callable, Fn.Remote.class, base);
        List<DiscoverResult.Method> normal = methodsOf(callable, Fn.Normal.class, base);
        int total = resources.size() + remote.size() + normal.size();

        Result<Page> page = Page.of(options.page(), total + documented.size(), command);
        if (!page.isOk()) {
            return page.cast();
        }
        Page window = page.value();
        List<DiscoverResult.ResourceList.Resource> shownResources = window.slice(resources, 0);
        List<DiscoverResult.Method> shownRemote = window.slice(remote, resources.size());
        List<DiscoverResult.Method> shownNormal = window.slice(normal, resources.size() + remote.size());
        return Result.ok(new DiscoverResult.MixedListing(containerName(container),
                shownResources, shownRemote, shownNormal,
                new DiscoverResult.MixedListing.Counts(resources.size(), remote.size(), normal.size()),
                shownResources.size() + shownRemote.size() + shownNormal.size(), total, window.paging(),
                window.next(command), documentedOn(window, documented, total), warning,
                mergeNotes(note, pathNote(container, selectors))));
    }

    private static List<DiscoverResult.Method> methodsOf(List<Entry> entries, Class<? extends Fn> form, String base) {
        return entries.stream()
                .filter(entry -> form.isInstance(entry.fn()))
                .map(Entry::label)
                .sorted(Texts.LOCALE_ORDER)
                .map(name -> new DiscoverResult.Method(name, base + " " + shellWord(name)))
                .toList();
    }

    private static Result<DiscoverResult> methodAnswer(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            List<Entry> callable, Options options, List<DiscoverResult.Documented.Entry> documented, String warning,
            String note) {
        String base = baseCommand(loaded, scope, container);
        String command = base + selectorArguments(container, selectors) + filterArgument(options);
        List<DiscoverResult.Method> methods = methodsOf(callable, Fn.class, base);
        Result<Page> page = Page.of(options.page(), methods.size() + documented.size(), command);
        if (!page.isOk()) {
            return page.cast();
        }
        Page window = page.value();
        List<DiscoverResult.Method> shown = window.slice(methods, 0);
        return Result.ok(new DiscoverResult.MethodList(containerName(container), shown, shown.size(),
                methods.size(), window.paging(), window.next(command),
                documentedOn(window, documented, methods.size()), warning, note));
    }

    private static Result<DiscoverResult> resourceAnswer(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            List<Entry> callable, Options options, List<DiscoverResult.Documented.Entry> documented, String warning,
            String note) {
        String base = baseCommand(loaded, scope, container);
        String command = base + selectorArguments(container, selectors) + filterArgument(options);
        List<DiscoverResult.ResourceList.Resource> merged = mergedResources(callable, base);
        // A kind-tolerance note and a path advisory DO co-occur (`elsewhereIfKnown` re-renders with the original
        // selectors), so both are kept rather than one outranking the other.
        String combinedNote = mergeNotes(note, pathNote(container, selectors));

        Optional<List<String>> prefix = selectors.isEmpty()
                ? Optional.of(List.of())
                : resolvedPath(container, selectors);
        boolean groupable = !options.filtered() && prefix.isPresent()
                && literalDepth(prefix.get()) <= MAX_GROUP_DEPTH;
        if (groupable && merged.size() > MAX_ENTRIES) {
            Optional<PathTree> node = descend(PathTree.build(operationsOf(callable)), prefix.get());
            if (node.isPresent()) {
                return groupedAnswer(container, selectors, node.get(), prefix.get(), base, command, options.page(),
                        warning, combinedNote);
            }
        }

        Result<Page> page = Page.of(options.page(), merged.size() + documented.size(), command);
        if (!page.isOk()) {
            return page.cast();
        }
        Page window = page.value();
        List<DiscoverResult.ResourceList.Resource> shown = window.slice(merged, 0);
        return Result.ok(new DiscoverResult.ResourceList(containerName(container), shown, shown.size(),
                merged.size(), window.paging(), window.next(command),
                documentedOn(window, documented, merged.size()), warning, combinedNote));
    }

    private static Result<DiscoverResult> groupedAnswer(
            Surface.Container container, List<String> selectors, PathTree node, List<String> prefix, String base,
            String command, int requested, String warning, String note) {
        String accessor = pathRequest(container, selectors).map(PathRequest::accessor).orElse(null);
        List<PathTree.Operation> terminal = new ArrayList<>();
        Map<List<String>, PathTree> literalChildren = new LinkedHashMap<>();
        collectNextLiteralChildren(node, prefix, terminal, literalChildren);

        List<DiscoverResult.ResourceList.Resource> here = mergedResources(
                terminal.stream().map(operation -> new Entry(operation.fn(), operation.segments())).toList(), base);
        List<DiscoverResult.PathGroups.Group> groups = new ArrayList<>();
        literalChildren.forEach((path, child) -> {
            String name = pathName(path);
            groups.add(new DiscoverResult.PathGroups.Group(
                    name, child.total(), base + groupArguments(container, name, accessor, child.total())));
        });
        groups.sort(Comparator.comparingInt(DiscoverResult.PathGroups.Group::count).reversed()
                .thenComparing(DiscoverResult.PathGroups.Group::name, Texts.LOCALE_ORDER));

        int total = here.size() + groups.size();
        Result<Page> page = Page.of(requested, total, command);
        if (!page.isOk()) {
            return page.cast();
        }
        Page window = page.value();
        return Result.ok(new DiscoverResult.PathGroups(containerName(container), window.slice(here, 0),
                window.slice(groups, here.size()), new DiscoverResult.PathGroups.Counts(here.size(), groups.size()),
                total, window.paging(), window.next(command), warning, note));
    }

    // A path that itself declares the accessor would answer with one signature and silently drop the rest of
    // the group, so such a group is opened unnarrowed.
    private static String groupArguments(Surface.Container container, String name, String accessor, int count) {
        if (accessor != null && select(container, List.of(name, accessor)).size() == count) {
            return " " + shellWord(name) + " " + accessor;
        }
        return " " + shellWord(name);
    }

    private static List<DiscoverResult.ResourceList.Resource> mergedResources(List<Entry> entries, String base) {
        Map<String, List<String>> accessorsByPath = new LinkedHashMap<>();
        for (Entry entry : entries) {
            accessorsByPath.computeIfAbsent(pathName(entry.path()), key -> new ArrayList<>())
                    .add(((Fn.Resource) entry.fn()).accessor());
        }
        List<DiscoverResult.ResourceList.Resource> resources = new ArrayList<>();
        accessorsByPath.entrySet().stream().sorted(Map.Entry.comparingByKey(Texts.LOCALE_ORDER)).forEach(entry -> {
            String path = entry.getKey();
            List<String> accessors = entry.getValue();
            Map<String, String> commands = new LinkedHashMap<>();
            accessors.forEach(accessor -> commands.put(accessor, base + " " + shellWord(path) + " " + accessor));
            resources.add(new DiscoverResult.ResourceList.Resource(path, accessors, commands));
        });
        return List.copyOf(resources);
    }

    private static List<PathTree.Operation> operationsOf(List<Entry> entries) {
        return entries.stream()
                .map(entry -> new PathTree.Operation((Fn.Resource) entry.fn(), entry.path()))
                .toList();
    }

    private static Optional<PathTree> descend(PathTree root, List<String> path) {
        PathTree node = root;
        for (String segment : path) {
            Optional<PathTree> child = node.children().stream()
                    .filter(candidate -> candidate.segment().equals(segment))
                    .findFirst();
            if (child.isEmpty()) {
                return Optional.empty();
            }
            node = child.get();
        }
        return Optional.of(node);
    }

    private static int literalDepth(List<String> path) {
        return (int) path.stream().filter(segment -> !segment.startsWith(":")).count();
    }

    // Keyed by FULL path: literal segments alone are ambiguous (repos/branches), and a parameter node can carry
    // terminal operations and literal children at once.
    private static void collectNextLiteralChildren(
            PathTree node, List<String> path, List<PathTree.Operation> terminal, Map<List<String>, PathTree> into) {
        terminal.addAll(node.operations());
        for (PathTree child : node.children()) {
            List<String> childPath = new ArrayList<>(path);
            childPath.add(child.segment());
            if (child.isParam()) {
                collectNextLiteralChildren(child, childPath, terminal, into);
            } else {
                into.put(List.copyOf(childPath), child);
            }
        }
    }

    private static String pathName(List<String> segments) {
        return segments.isEmpty() ? "." : escapeKeywordSegments(String.join("/", segments));
    }

    // PathTree strips the apostrophe on the way in, so the collision is re-derived from the keyword set.
    private static String escapeKeywordSegments(String path) {
        return Arrays.stream(path.split("/", -1))
                .map(segment -> ModuleRef.isKeyword(segment) ? "'" + segment : segment)
                .collect(Collectors.joining("/"));
    }

    private static Optional<List<String>> resolvedPath(Surface.Container container, List<String> selectors) {
        if (selectByPath(container, selectors).isEmpty()) {
            return Optional.empty();
        }
        return located(container, selectors)
                .map(PathTree.Located::resolution)
                .filter(PathTree.Resolution.Found.class::isInstance)
                .map(resolution -> ((PathTree.Resolution.Found) resolution).path());
    }

    private static String selectorArguments(Surface.Container container, List<String> selectors) {
        if (selectors.isEmpty()) {
            return "";
        }
        Optional<List<String>> path = resolvedPath(container, selectors);
        if (path.isEmpty()) {
            return shellWords(selectors);
        }
        String accessor = pathRequest(container, selectors).map(PathRequest::accessor).orElse(null);
        return " " + shellWord(pathName(path.get())) + (accessor == null ? "" : " " + accessor);
    }

    private static String baseCommand(LoadedPackage loaded, Surface.Scope scope, Surface.Container container) {
        return "bal discover " + loaded.pkgArgument() + " " + scope.verb()
                + (container.isModule() ? "" : " " + shellWord(container.name()));
    }

    private static List<String> spelled(Entry entry, List<String> selectors) {
        if (!(entry.fn() instanceof Fn.Standalone named)) {
            return selectors;
        }
        String wanted = Names.normalise(named.name());
        return selectors.stream().map(word -> Names.normalise(word).equals(wanted) ? named.name() : word).toList();
    }

    private static List<DiscoverResult.Documented.Entry> documentedRows(List<Entry> entries, String base) {
        List<DiscoverResult.Documented.Entry> rows = new ArrayList<>(mergedResources(
                entries.stream().filter(entry -> entry.fn() instanceof Fn.Resource).toList(), base));
        entries.stream()
                .filter(entry -> !(entry.fn() instanceof Fn.Resource))
                .map(entry -> new DiscoverResult.Method(entry.label(), base + " " + shellWord(entry.label())))
                .forEach(rows::add);
        rows.sort(Comparator.comparing(DiscoverResult.Documented.Entry::key, Texts.LOCALE_ORDER));
        return List.copyOf(rows);
    }

    private static String containerName(Surface.Container container) {
        return container.isModule() ? null : container.name();
    }

    private static String filterArgument(Options options) {
        return options.filtered() ? " --filter " + shellWord(options.filter()) : "";
    }

    private static String shellWords(List<String> words) {
        return words.stream().map(word -> " " + shellWord(word)).collect(Collectors.joining());
    }

    private static String shellWord(String word) {
        return Texts.shellWord(word);
    }
}
