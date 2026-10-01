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
import io.ballerina.tools.discover.model.Fn;
import io.ballerina.tools.discover.model.ModuleRef;
import io.ballerina.tools.discover.model.Param;
import io.ballerina.tools.discover.model.Service;
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
 * <p><b>OUTPUT SIZE IS AN ENTRY/LINE CEILING NOW, NOT A BYTE BUDGET.</b> The RFC replaces this tool's earlier
 * byte-budget tier ladder (bytes of quoted Ballerina, degrading through four tiers) with a hard ceiling of
 * {@value #MAX_ENTRIES} entries per listing: under it, everything is shown; over it, resource paths GROUP by
 * segment and remote/normal methods PAGE with {@code --page}, both honestly disclosing {@code shown}/{@code total}
 * and the exact next command rather than silently degrading. Every answer is a {@link DiscoverResult}, so
 * {@code --output} renders all of them the same way.
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
     * The {@code number}-th {@value #MAX_ENTRIES}-wide window into a {@code total}-sized list — the page-clamping
     * arithmetic every paginated listing in this tool shares (a flat method listing here, a filtered readme-chunk
     * listing in {@link Readme}), so a future fix to the page-boundary rule only has to be made once.
     *
     * @param number the page actually served (1-indexed, clamped up from whatever was requested)
     * @param from the inclusive start index into the full list
     * @param to the exclusive end index into the full list
     */
    public record Page(int number, int from, int to) {
        public static Page of(int requested, int total) {
            int number = Math.max(1, requested);
            int from = Math.min((number - 1) * MAX_ENTRIES, total);
            int to = Math.min(from + MAX_ENTRIES, total);
            return new Page(number, from, to);
        }
    }

    /**
     * How many bytes a type closure may take when inlined under a single fully-resolved result — {@link
     * #signature}'s own budget, unrelated to the entry ceiling above: this bounds a CLOSURE (how many types a
     * signature transitively names), not a LISTING (how many results a query matched).
     */
    public static final int MAX_CLOSURE_BYTES = 6_000;

    /**
     * How many additional path-grouping levels are allowed beyond the top-level segment, surveyed against real
     * connectors (the RFC's own check: seven connectors, three levels needed at most, four is one level of
     * margin). Past this depth, a still-oversized group is shown flat and truncated rather than subdivided
     * again, so a group can never fragment into an unnavigable tree of one-entry leaves.
     */
    private static final int MAX_GROUP_DEPTH = 4;

    /** Accessors a resource function may declare. Not a closed set — see {@link #isAccessor}. */
    private static final Pattern ACCESSOR = Pattern.compile("[a-z][a-zA-Z0-9]*");

    private Containers() {
    }

    /**
     * @param selectors everything after the package: a container name, a member, an accessor and a path
     * @param filter the {@code --filter} keyword, or {@code null}
     * @param page {@code --page}, 1-indexed, for a method listing over the ceiling
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

    private static Result<DiscoverResult> render(
            LoadedPackage loaded, Surface.Scope scope, Options options, String note) {
        List<Surface.Container> containers = Surface.of(loaded.library(), scope);
        if (containers.isEmpty()) {
            return elsewhere(loaded, scope, options);
        }

        List<String> selectors = options.selectors();
        if (selectors.isEmpty()) {
            if (containers.size() == 1) {
                return answer(loaded, scope, containers.get(0), List.of(), options, note);
            }
            return Result.ok(roster(loaded, scope, containers, options, loaded.warning()));
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

        // 3. An EXACT member name or a resolving path, across every container in scope. A bare validation
        //    failure here used to rebuild its own suggestion WITHOUT the name that failed, so following it
        //    looped.
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

    /**
     * Whichever containers in this scope hold the selector: one is answered, several are a roster.
     *
     * <p>{@code exactly} is two passes rather than two code paths — the resolution ORDER is the whole subtlety
     * here, and expressing it as one function called twice is what keeps the two passes from drifting into
     * different notions of a match.
     */
    private static Result<DiscoverResult> byOwner(
            LoadedPackage loaded, Surface.Scope scope, List<Surface.Container> containers,
            List<String> selectors, Options options, String note, boolean exactly) {
        Map<Surface.Container, List<Entry>> owners = new LinkedHashMap<>();
        for (Surface.Container container : containers) {
            List<Entry> hits = exactly
                    ? selectExactly(container, selectors)
                    : select(container, selectors);
            if (!hits.isEmpty()) {
                owners.put(container, hits);
            }
        }
        if (owners.isEmpty()) {
            return null;
        }
        if (owners.size() == 1) {
            Surface.Container owner = owners.keySet().iterator().next();
            return answer(loaded, scope, owner, selectors, options,
                    note != null ? note : ownerNote(loaded, scope, owner, selectors.get(0)));
        }
        return Result.ok(owners(loaded, scope, owners, selectors));
    }

    /**
     * Is this selector known to ANOTHER scope, or to the declaration roster?
     *
     * <p>Split out of {@link #elsewhere} so the resolution order can consult it without committing to a failure:
     * {@code null} means "not there either", which is what lets the substring pass run afterwards.
     */
    private static Result<DiscoverResult> elsewhereIfKnown(
            LoadedPackage loaded, Surface.Scope scope, Options options) {
        String token = options.selectors().get(0);
        for (Surface.Scope other : Surface.Scope.values()) {
            if (other == scope) {
                continue;
            }
            List<Surface.Container> containers = Surface.of(loaded.library(), other);
            if (Surface.byName(containers, token).isPresent()
                    || containers.stream().anyMatch(container ->
                            !selectExactly(container, options.selectors()).isEmpty())) {
                return render(loaded, other, options, kindNote(loaded, other, token));
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Kind tolerance
    // -----------------------------------------------------------------------

    /**
     * A symbol this bucket does not hold, answered anyway.
     *
     * <p>This is what makes bucket-specific addressing safe for an agent at all. Without it every kind guess
     * risks a wasted round trip; with it the split costs ONE PRINTED LINE.
     */
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
        String token = options.selectors().get(0);
        for (Surface.Scope other : Surface.Scope.values()) {
            if (other == scope) {
                continue;
            }
            if (Surface.of(loaded.library(), other).stream()
                    .anyMatch(container -> !select(container, options.selectors()).isEmpty())) {
                return render(loaded, other, options, kindNote(loaded, other, token));
            }
        }
        return Result.err(notFound(loaded, scope, token,
                Declarations.index(loaded.library().addressable())));
    }

    private static String kindNote(LoadedPackage loaded, Surface.Scope actual, String token) {
        return Texts.code(token) + " is addressed by " + Texts.code(actual.verb()) + " — showing it. "
                + "Canonical: " + Texts.code("bal discover " + loaded.pkgArgument()
                + " " + actual.verb() + " " + token);
    }

    private static String ownerNote(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container owner, String token) {
        return Texts.code(token) + " is declared on " + Texts.code(owner.label()) + " — showing it. "
                + "Canonical: " + Texts.code("bal discover " + loaded.pkgArgument()
                + " " + scope.verb() + " " + owner.name() + " " + token);
    }

    /** Two notes, concatenated when both are present — the same join every other combined note in this class uses. */
    private static String mergeNotes(String first, String second) {
        if (first == null) {
            return second;
        }
        return second == null ? first : first + "; " + second;
    }

    /**
     * The listener(s) a service container's own pairings name, joined — every pairing, confirmed or not, since
     * dropping an unconfirmed sibling would lose it for a caller who only ever sees this joined form (the roster's
     * {@code listener} field never carries a per-name confirmation hedge the way {@link #listenerNote} does).
     */
    private static String listenerNames(Surface.Container container) {
        List<Service> pairings = container.pairings();
        if (pairings.isEmpty()) {
            return null;
        }
        return pairings.stream()
                .map(service -> service.listener().name())
                .distinct()
                .collect(Collectors.joining(", "));
    }

    /** {@link #listenerNames} as a one-line fact, for the containers the ceiling has no room to list a fact row for. */
    private static String listenerNote(Surface.Container container) {
        String names = listenerNames(container);
        if (names == null) {
            return null;
        }
        boolean allConfirmed = container.pairings().stream().allMatch(Service::isAttachable);
        return allConfirmed
                ? "binds to " + names
                : "binds to " + names + " — not confirmed by the package's own attach() signature";
    }

    /**
     * Nothing matched anywhere, with the names that came closest.
     *
     * <p>The suggestion must never rebuild the command WITHOUT the argument that failed.
     */
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
        return new Failure.SymbolNotFound(loaded.label(), List.of(token), near, suggestion);
    }

    /** A scope with nothing in it, saying where the callable surface actually is. */
    private static DiscoverResult emptyBucket(LoadedPackage loaded, Surface.Scope scope) {
        String pkg = loaded.pkgArgument();
        List<DiscoverResult.EmptyBucket.Elsewhere> elsewhere = new ArrayList<>();
        for (Surface.Scope other : Surface.Scope.values()) {
            List<Surface.Container> containers = Surface.of(loaded.library(), other);
            if (other == scope || containers.isEmpty()) {
                continue;
            }
            int count = other == Surface.Scope.MODULE ? containers.get(0).functions().size() : containers.size();
            elsewhere.add(new DiscoverResult.EmptyBucket.Elsewhere(
                    other.verb(), count, "bal discover " + pkg + " " + other.verb()));
        }
        return new DiscoverResult.EmptyBucket(scope.verb(), List.copyOf(elsewhere), loaded.warning());
    }

    // -----------------------------------------------------------------------
    // Rosters
    // -----------------------------------------------------------------------

    /**
     * Several containers and nothing to choose between them yet — the RFC's entry ceiling, applied to a roster
     * of containers rather than to one container's own members.
     */
    private static DiscoverResult roster(
            LoadedPackage loaded, Surface.Scope scope, List<Surface.Container> containers, Options options,
            String warning) {
        String pkg = loaded.pkgArgument();
        List<Surface.Container> selected = options.filtered()
                ? containers.stream()
                        .filter(container -> !filterEntries(container, options).surface().isEmpty())
                        .toList()
                : containers;

        if (selected.isEmpty()) {
            List<String> names = containers.stream().map(Surface.Container::name).toList();
            return new DiscoverResult.NoMatch(options.filter(), null, Names.nearMisses(options.filter(), names),
                    List.of(), roster(loaded, scope, containers, Options.bare(), null),
                    "bal discover " + pkg + " " + scope.verb(), List.of(), warning, null);
        }

        List<Surface.Container> shown = selected.subList(0, Math.min(MAX_ENTRIES, selected.size()));
        List<DiscoverResult.ContainerRoster.Container> items = shown.stream()
                .map(container -> new DiscoverResult.ContainerRoster.Container(
                        container.name(),
                        container.operations().size(),
                        (int) container.standalone().stream().filter(Fn.Remote.class::isInstance).count(),
                        (int) container.standalone().stream().filter(Fn.Normal.class::isInstance).count(),
                        listenerNames(container),
                        "bal discover " + pkg + " " + scope.verb() + " " + container.name()))
                .toList();
        String next = shown.size() < selected.size()
                ? "bal discover " + pkg + " " + scope.verb() + " --filter <keyword>"
                : null;
        return new DiscoverResult.ContainerRoster(items, selected.size(), next, warning);
    }

    /**
     * One member name, declared on several containers.
     *
     * <p>The answer is the OWNERS with counts, each ending in the command that opens it — never a bare
     * validation failure, and never a silent pick of one.
     */
    private static DiscoverResult owners(
            LoadedPackage loaded, Surface.Scope scope, Map<Surface.Container, List<Entry>> owners,
            List<String> selectors) {
        String pkg = loaded.pkgArgument();
        String member = selectors.stream().map(Containers::shellWord).collect(Collectors.joining(" "));
        List<DiscoverResult.Owners.Owner> listed = owners.entrySet().stream()
                .limit(MAX_ENTRIES)
                .map(entry -> new DiscoverResult.Owners.Owner(entry.getKey().name(), entry.getValue().size(),
                        "bal discover " + pkg + " " + scope.verb() + " " + entry.getKey().name() + " " + member))
                .toList();
        String next = listed.size() < owners.size()
                ? "bal discover " + pkg + " " + scope.verb() + " <container> " + member
                : null;
        return new DiscoverResult.Owners(String.join(" ", selectors), listed, owners.size(), next, loaded.warning());
    }

    // -----------------------------------------------------------------------
    // Selection inside one container
    // -----------------------------------------------------------------------

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
                case Fn.Constructor ignored -> "init";
            };
        }

        /**
         * {@code ->} or {@code .} — DERIVED and always printed.
         */
        public String callForm() {
            return fn instanceof Fn.Remote || fn instanceof Fn.Resource ? "->" : ".";
        }
    }

    /**
     * What a selector selects inside one container.
     *
     * <p>THE GRAMMAR FOLLOWS THE CONTAINER. A container that declares resource functions reads
     * {@code get}/{@code post}/{@code delete} as an accessor and the token after it as a path; one that does not
     * reads the same token as a member name.
     */
    private static List<Entry> select(Surface.Container container, List<String> selectors) {
        return select(container, selectors, false);
    }

    /**
     * The same selection, restricted to matches a caller could not have meant by accident.
     *
     * <p>An exact member name or a path that resolves. The substring widening — which is what makes a
     * half-remembered name work — is deliberately excluded here, because it is what lets a container name in
     * another scope lose to letters inside an unrelated member (see the resolution order in {@link #render}).
     */
    private static List<Entry> selectExactly(Surface.Container container, List<String> selectors) {
        return select(container, selectors, true);
    }

    private static List<Entry> select(
            Surface.Container container, List<String> selectors, boolean exactly) {
        List<Entry> all = entriesOf(container);
        if (selectors.isEmpty()) {
            return all;
        }
        if (container.hasPaths()) {
            List<Entry> byPath = selectByPath(container, selectors);
            if (!byPath.isEmpty()) {
                return byPath;
            }
        }
        // A single token is a name filter; more than one on a name-shaped container is a caller who typed a path
        // at something that has none, and the empty result routes them to the recovery that says so.
        String token = selectors.get(0);
        if (selectors.size() > 1 && container.hasPaths()) {
            return List.of();
        }
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
            // AN EXACT NAME NEVER LOSES TO A SUBSTRING.
            return exact;
        }
        return all.stream().filter(entry -> matchesName(entry, token)).toList();
    }

    /**
     * A path request, split into the accessor that filters it and the path that anchors it.
     *
     * @param accessor the accessor that filters it, empty when none was given
     * @param tokens the path tokens that anchor it
     */
    private record PathRequest(String accessor, List<String> tokens) { }

    /** How a path selector reads against this container, or nothing when it is not one. */
    private static Optional<PathRequest> pathRequest(Surface.Container container, List<String> selectors) {
        if (!container.hasPaths()) {
            return Optional.empty();
        }
        if (selectors.size() >= 2 && isAccessor(container, selectors.get(0))) {
            return Optional.of(new PathRequest(selectors.get(0), PathTree.splitPath(selectors.get(1))));
        }
        // The RFC's own order, and the one every `call` field prints: the path, then the accessor.
        if (selectors.size() == 2 && isAccessor(container, selectors.get(1))) {
            return Optional.of(new PathRequest(selectors.get(1), PathTree.splitPath(selectors.get(0))));
        }
        if (selectors.size() == 1) {
            // An accessor and a path in ONE argument, which is what copying a whole line out of a fenced
            // signature produces: `get [PathParamType ...path]`. Split across two arguments every path spelling
            // already resolved; as one token only the display spelling did, because a one-token selector was
            // compared against an entry's LABEL and never reached the walk that understands the rest.
            //
            // Safe because a member name cannot contain whitespace, and gated on the container actually
            // declaring the accessor — otherwise `Producer send` would parse `Producer` as one.
            String[] words = selectors.get(0).trim().split("\\s+", 2);
            if (words.length == 2 && isAccessor(container, words[0])) {
                return Optional.of(new PathRequest(words[0], PathTree.splitPath(words[1])));
            }
            return Optional.of(new PathRequest(null, PathTree.splitPath(selectors.get(0))));
        }
        return Optional.empty();
    }

    /** Where a path selector landed, relocation and alternatives included. */
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
        // signature drill-down, and what every resource `call` field prints. Only when it does not is the accessor
        // a filter over everything beneath the path.
        List<Entry> exact = accessorsOf(found.node().operations(), accessor);
        return exact.isEmpty() ? accessorsOf(PathTree.operationsUnder(found.node()), accessor) : exact;
    }

    private static List<Entry> accessorsOf(List<PathTree.Operation> operations, String accessor) {
        return operations.stream()
                .filter(operation -> operation.fn().accessor().equalsIgnoreCase(accessor))
                .map(operation -> new Entry(operation.fn(), operation.segments()))
                .toList();
    }

    /**
     * Where a path selector landed when that is not simply where it pointed, as one line for the result's own
     * {@code note} field: silently dropping which sibling branch a wildcard or an auto-relocation did NOT take is
     * exactly the bug this mechanism exists to prevent (GITHUB-02: {@code repos/*}/{@code *} answered with 420 of
     * 421 operations under exit 0 and nothing said so).
     */
    private static String pathNote(Surface.Container container, List<String> selectors) {
        Optional<PathTree.Located> found = located(container, selectors);
        if (found.isEmpty() || !(found.get().resolution() instanceof PathTree.Resolution.Found node)) {
            return null;
        }
        PathTree.Located located = found.get();
        List<String> parts = new ArrayList<>();
        if (located.relocated() && !located.alternatives().isEmpty()) {
            parts.add("relocated to " + Texts.code(String.join("/", node.path()))
                    + " — the only match for that segment under the requested prefix");
        }
        for (PathTree.Descent.Sibling other : node.alsoMatched()) {
            parts.add("also matched " + Texts.code(String.join("/", other.path())) + " ("
                    + Texts.count(other.total()) + "), not included here");
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    /**
     * Is this token an accessor rather than a member name?
     */
    private static boolean isAccessor(Surface.Container container, String token) {
        if (!ACCESSOR.matcher(token).matches()) {
            return false;
        }
        return container.operations().stream()
                .anyMatch(operation -> operation.fn().accessor().equalsIgnoreCase(token));
    }

    /** A bare token matches anywhere in a name; {@code *} is a wildcard, as it is in a path. */
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

    // -----------------------------------------------------------------------
    // The answer
    // -----------------------------------------------------------------------

    private static Result<DiscoverResult> answer(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            Options options, String note) {
        note = mergeNotes(note, listenerNote(container));
        List<Entry> selected = select(container, selectors);
        List<String> documented = List.of();

        if (options.filtered()) {
            Filter.Split<Entry> split = Filter.apply(options.filter(), selected,
                    entry -> Filter.surfaceOf(entry.fn()), entry -> Filter.docsOf(entry.fn()));
            selected = split.surface();
            documented = split.documented().stream().map(Entry::label).toList();
        }

        if (selected.isEmpty() && selectors.isEmpty() && !options.filtered()) {
            return Result.ok(new DiscoverResult.MethodList(List.of(), 0, 0, null, loaded.warning(), note));
        }
        if (selected.isEmpty()) {
            return Result.ok(nothingMatched(loaded, scope, container, selectors, options, documented, note));
        }
        if (selected.size() == 1) {
            return Result.ok(signature(loaded, container, selectors, selected.get(0), documented, note));
        }
        return Result.ok(listing(loaded, scope, container, selectors, selected, options, documented,
                loaded.warning(), note));
    }

    /**
     * Several entries: resource paths, named methods, or both side by side.
     *
     * <p>The constructor is never part of the ceiling problem — it is one signature, always shown once, on request
     * ({@code init}/{@code new}) rather than folded into a "many entries" listing.
     */
    private static DiscoverResult listing(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            List<Entry> selected, Options options, List<String> documented, String warning, String note) {
        List<Entry> callable = selected.stream()
                .filter(entry -> !(entry.fn() instanceof Fn.Constructor))
                .toList();
        boolean hasResources = callable.stream().anyMatch(entry -> entry.fn() instanceof Fn.Resource);
        boolean hasNamed = callable.stream().anyMatch(entry -> !(entry.fn() instanceof Fn.Resource));
        if (hasResources && hasNamed) {
            return mixedAnswer(loaded, scope, container, selectors, callable, options, documented, warning, note);
        }
        if (hasResources) {
            return resourceAnswer(loaded, scope, container, selectors, callable, options, warning, note);
        }
        return methodAnswer(loaded, scope, container, callable, options, warning, note);
    }

    /**
     * A selector that matched nothing, answered with what IS there.
     *
     * <p>Exit 0 with the alternatives rather than a failure: an empty selection is a fact about the container,
     * and the caller's next move is in the answer.
     */
    private static DiscoverResult nothingMatched(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            Options options, List<String> documented, String note) {
        String asked = options.filtered() ? options.filter() : String.join(" ", selectors);
        String command = "bal discover " + loaded.pkgArgument() + " " + scope.verb() + containerArgument(container);

        // Rule 2 of segment location: more than one occurrence is LISTED and the request stops there. Picking
        // one is exactly the failure anchoring exists to prevent.
        List<List<String>> located = located(container, selectors)
                .map(PathTree.Located::alternatives)
                .orElse(List.of());
        List<DiscoverResult.NoMatch.Alternative> alternatives = located.size() > 1
                ? located.stream()
                        .map(path -> escapeKeywordSegments(String.join("/", path)))
                        .map(path -> new DiscoverResult.NoMatch.Alternative(path, command + " " + quotedPath(path)))
                        .toList()
                : List.of();

        List<Entry> all = entriesOf(container);
        List<String> names = new ArrayList<>();
        for (Entry entry : all) {
            String name = !(entry.fn() instanceof Fn.Resource) ? entry.label()
                    : entry.path().isEmpty() ? "." : entry.path().get(0);
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        DiscoverResult available = alternatives.isEmpty() && !all.isEmpty()
                ? listing(loaded, scope, container, List.of(), all, Options.bare(), List.of(), null, null)
                : null;
        return new DiscoverResult.NoMatch(asked, container.isModule() ? null : container.name(),
                Names.nearMisses(asked, names), alternatives, available, command, documented,
                loaded.warning(), note);
    }

    // -----------------------------------------------------------------------
    // Exactly one result, and the mixed case
    // -----------------------------------------------------------------------

    /**
     * One result, in full, with the declarations its signature names.
     *
     * <p>The inlining is a DEPTH-1 closure and it is what makes the common flow one call rather than two: the
     * caches DELETE on github names {@code ActionsDeleteActionsCacheByKeyQueries}, the included-record parameter
     * whose fields are the call's named arguments, and no signature line spells those out.
     */
    private static DiscoverResult signature(
            LoadedPackage loaded, Surface.Container container, List<String> selectors, Entry entry,
            List<String> documented, String note) {
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
                : Closure.of(roots, index, MAX_CLOSURE_BYTES, 1);
        List<DiscoverResult.Signature.Type> types = closure == null
                ? List.of()
                : closure.names().stream()
                        .map(name -> new DiscoverResult.Signature.Type(name, TypeDefs.renderTypeDef(index.get(name))))
                        .toList();
        List<String> omitted = closure == null || !closure.truncated() ? List.of() : closure.omitted();

        String kind = switch (fn) {
            case Fn.Resource ignored -> "resource";
            case Fn.Remote ignored -> "remote";
            case Fn.Normal ignored -> container.isModule() ? "function" : "normal";
            case Fn.Constructor ignored -> "constructor";
        };
        String name = switch (fn) {
            case Fn.Resource ignored -> null;
            case Fn.Standalone standalone -> standalone.name();
            case Fn.Constructor ignored -> "init";
        };
        return new DiscoverResult.Signature(
                container.isModule() ? null : container.name(), kind, name,
                fn instanceof Fn.Resource resource ? resource.accessor() : null,
                fn instanceof Fn.Resource ? escapeKeywordSegments(String.join("/", entry.path())) : null,
                entry.callForm(), declaration, params, Signatures.returnType(fn), fn.isDeprecated(),
                types, omitted, documented, loaded.warning(), mergeNotes(note, pathNote(container, selectors)));
    }

    /**
     * A container answering to both {@code ->path.accessor()} and a named method — measured, only
     * {@code ballerina/http}'s {@code Client} and {@code ballerinax/sap}'s do this among the connectors surveyed.
     * Under the ceiling every entry is shown; over it the listing is cut at {@value #MAX_ENTRIES}, resources
     * first, and says so.
     */
    private static DiscoverResult mixedAnswer(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            List<Entry> callable, Options options, List<String> documented, String warning, String note) {
        String baseCommand = "bal discover " + loaded.pkgArgument() + " " + scope.verb()
                + containerArgument(container);
        List<DiscoverResult.ResourceList.Resource> resources = mergedResources(
                callable.stream().filter(entry -> entry.fn() instanceof Fn.Resource).toList(), baseCommand);
        List<String> remote = namesOf(callable, Fn.Remote.class);
        List<String> normal = namesOf(callable, Fn.Normal.class);
        int total = resources.size() + remote.size() + normal.size();

        int room = MAX_ENTRIES;
        List<DiscoverResult.ResourceList.Resource> shownResources =
                resources.subList(0, Math.min(room, resources.size()));
        room -= shownResources.size();
        List<String> shownRemote = remote.subList(0, Math.min(room, remote.size()));
        room -= shownRemote.size();
        List<String> shownNormal = normal.subList(0, Math.min(room, normal.size()));
        int shown = shownResources.size() + shownRemote.size() + shownNormal.size();

        String next = shown < total
                ? baseCommand + pathArgument(canonical(container, selectors)) + " --filter <keyword>"
                : null;
        return new DiscoverResult.MixedListing(shownResources, shownRemote, shownNormal, shown, total, next,
                documented, warning, mergeNotes(note, pathNote(container, selectors)));
    }

    private static List<String> namesOf(List<Entry> entries, Class<? extends Fn> form) {
        return entries.stream()
                .filter(entry -> form.isInstance(entry.fn()))
                .map(Entry::label)
                .sorted(Texts.LOCALE_ORDER)
                .toList();
    }

    // -----------------------------------------------------------------------
    // Remote / normal methods — flat under the ceiling, paginated over it
    // -----------------------------------------------------------------------

    private static DiscoverResult methodAnswer(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<Entry> callable,
            Options options, String warning, String note) {
        String pkg = loaded.pkgArgument();
        // A filtered listing pages against the SAME filter — never dropped from `next`, or paging would
        // silently widen back out to the container's full, unfiltered roster.
        String command = "bal discover " + pkg + " " + scope.verb() + containerArgument(container)
                + (options.filtered() ? " --filter " + shellWord(options.filter()) : "");
        List<String> names = callable.stream().map(Entry::label).sorted(Texts.LOCALE_ORDER).toList();
        int total = names.size();

        if (total <= MAX_ENTRIES) {
            return new DiscoverResult.MethodList(names, total, total, null, warning, note);
        }

        Page page = Page.of(options.page(), total);
        List<String> shown = names.subList(page.from(), page.to());
        String next = page.to() < total ? command + " --page " + (page.number() + 1) : null;
        return new DiscoverResult.MethodList(shown, shown.size(), total, next, warning, note);
    }

    // -----------------------------------------------------------------------
    // Resource paths — flat under the ceiling, grouped by segment over it
    // -----------------------------------------------------------------------

    private static DiscoverResult resourceAnswer(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            List<Entry> callable, Options options, String warning, String note) {
        String pkg = loaded.pkgArgument();
        String baseCommand = "bal discover " + pkg + " " + scope.verb() + containerArgument(container);
        List<String> prefix = canonical(container, selectors);
        List<DiscoverResult.ResourceList.Resource> merged = mergedResources(callable, baseCommand);
        // A kind-tolerance note and a path advisory DO co-occur: `elsewhereIfKnown` re-invokes `render` with the
        // whole original selector list still attached, so a container reached across buckets can go on to
        // resolve a wildcard or a relocation in that same call. Concatenated rather than one outranking the
        // other, for the same reason `pathNote` itself joins a relocation and a skipped sibling instead of
        // picking one — losing either fact silently is what this mechanism exists to prevent.
        String combinedNote = mergeNotes(note, pathNote(container, selectors));

        boolean mustStayFlat = options.filtered() || prefix.size() > MAX_GROUP_DEPTH;
        if (mustStayFlat || merged.size() <= MAX_ENTRIES) {
            List<DiscoverResult.ResourceList.Resource> shown =
                    merged.subList(0, Math.min(MAX_ENTRIES, merged.size()));
            String next = shown.size() < merged.size()
                    ? baseCommand + pathArgument(prefix) + " --filter <keyword>"
                    : null;
            return new DiscoverResult.ResourceList(shown, shown.size(), merged.size(), next, warning, combinedNote);
        }

        PathTree node = nodeFor(container, selectors);
        List<PathGroup> groups = groupsUnder(node, prefix);
        List<PathGroup> shownGroups = groups.subList(0, Math.min(MAX_ENTRIES, groups.size()));
        List<DiscoverResult.PathGroups.Group> jsonGroups = shownGroups.stream()
                .map(group -> new DiscoverResult.PathGroups.Group(
                        group.name(), group.count(), baseCommand + " " + quotedPath(group.name())))
                .toList();
        String next = shownGroups.size() < groups.size()
                ? baseCommand + pathArgument(prefix) + " --filter <keyword>"
                : null;
        return new DiscoverResult.PathGroups(jsonGroups, groups.size(), next, warning, combinedNote);
    }

    /** One entry per resource PATH, not one per accessor — several accessors on one path share one row. */
    private static List<DiscoverResult.ResourceList.Resource> mergedResources(
            List<Entry> entries, String baseCommand) {
        Map<String, List<String>> accessorsByPath = new LinkedHashMap<>();
        for (Entry entry : entries) {
            accessorsByPath.computeIfAbsent(String.join("/", entry.path()), key -> new ArrayList<>())
                    .add(((Fn.Resource) entry.fn()).accessor());
        }
        List<DiscoverResult.ResourceList.Resource> resources = new ArrayList<>();
        accessorsByPath.forEach((path, accessors) -> {
            String escaped = escapeKeywordSegments(path);
            // A `call` field only where it is unambiguous — exactly one accessor. A flat field on a
            // multi-accessor path would have to guess which one, which this design refuses to do.
            String call = accessors.size() == 1
                    ? baseCommand + " " + quotedPath(escaped) + " " + accessors.get(0)
                    : null;
            resources.add(new DiscoverResult.ResourceList.Resource(escaped, List.copyOf(accessors), call));
        });
        return List.copyOf(resources);
    }

    /** The tree node the current selector already resolved to — the root, for a bare container. */
    private static PathTree nodeFor(Surface.Container container, List<String> selectors) {
        if (selectors.isEmpty()) {
            return PathTree.build(container.operations());
        }
        return located(container, selectors)
                .map(PathTree.Located::resolution)
                .filter(PathTree.Resolution.Found.class::isInstance)
                .map(resolution -> ((PathTree.Resolution.Found) resolution).node())
                .orElseGet(() -> PathTree.build(container.operations()));
    }

    /**
     * One node's operations, grouped by their next LITERAL segment — transparent through any purely-parameter
     * level in between, since a parameter carries no naming choice and is not a grouping boundary. Busiest
     * first, matching the tree's own ordering.
     *
     * @param name the group's path, {@code /}-joined from the top of the bucket — {@code "."} for operations
     *     that terminate exactly at this prefix, with no further segment (github's own top level has one)
     * @param count operations at or under this group
     */
    private record PathGroup(String name, int count) { }

    private static List<PathGroup> groupsUnder(PathTree node, List<String> prefix) {
        Map<String, List<PathTree>> byLiteralChild = new LinkedHashMap<>();
        int terminalHere = collectNextLiteralChildren(node, byLiteralChild);

        List<PathGroup> groups = new ArrayList<>();
        if (terminalHere > 0) {
            String name = prefix.isEmpty() ? "." : escapeKeywordSegments(String.join("/", prefix));
            groups.add(new PathGroup(name, terminalHere));
        }
        byLiteralChild.forEach((literal, children) -> {
            List<String> path = new ArrayList<>(prefix);
            path.add(literal);
            int count = children.stream().mapToInt(PathTree::total).sum();
            groups.add(new PathGroup(escapeKeywordSegments(String.join("/", path)), count));
        });
        groups.sort(Comparator.comparingInt(PathGroup::count).reversed()
                .thenComparing(PathGroup::name, Texts.LOCALE_ORDER));
        return groups;
    }

    /**
     * Literal children into {@code into}, keyed by their own segment; a running count of operations that
     * terminate transparently through {@code node} and every purely-parameter level walked through to reach
     * them, returned rather than collected — a parameter node can carry its own terminal operations AND further
     * literal children at once (github's {@code repos/:owner/:repo} declares get/update/delete of the repo
     * itself alongside 63 literal children like {@code issues}), and both have to survive the same walk.
     */
    private static int collectNextLiteralChildren(PathTree node, Map<String, List<PathTree>> into) {
        int terminal = node.operations().size();
        for (PathTree child : node.children()) {
            if (child.isParam()) {
                terminal += collectNextLiteralChildren(child, into);
            } else {
                into.computeIfAbsent(child.segment(), key -> new ArrayList<>()).add(child);
            }
        }
        return terminal;
    }

    private static String pathArgument(List<String> prefix) {
        return prefix.isEmpty() ? "" : " " + quotedPath(escapeKeywordSegments(String.join("/", prefix)));
    }

    /**
     * A path, with every segment that collides with a Ballerina keyword quoted — the same keyword set and the
     * same per-segment rule {@link ModuleRef#importPath()} applies to a module path, here applied to a resource
     * path instead.
     *
     * <p>{@link PathTree#readableSegment} already strips this apostrophe on the way IN, for the tree's own
     * prose/matching register — so by the time a path reaches this class, {@code gists/'public} has already
     * become {@code gists/public} and the collision has to be re-derived from the keyword set rather than
     * recovered from what Central published.
     */
    private static String escapeKeywordSegments(String path) {
        return Arrays.stream(path.split("/", -1))
                .map(segment -> ModuleRef.isKeyword(segment) ? "'" + segment : segment)
                .collect(Collectors.joining("/"));
    }

    /**
     * A resource path, pre-quoted when it needs it — never left for the caller to notice.
     *
     * <p>The one character that ever forces this is the keyword-escaping apostrophe ({@code gists/'public}):
     * unsafe unquoted in every shell tested. Wrapped in double quotes, the RFC's own verified-safe form.
     */
    private static String quotedPath(String path) {
        return path.contains("'") ? "\"" + path + "\"" : path;
    }

    // -----------------------------------------------------------------------
    // Shared
    // -----------------------------------------------------------------------

    /**
     * The selector as the tree spells it, where a path resolved.
     *
     * <p>A pointer offers the CANONICAL form, not the one the caller happened to type: {@code repos/owner/repo},
     * {@code repos/:owner/:repo} and {@code repos/[string owner]/[string repo]} all address one path, and a
     * command echoing the typed spelling teaches the reader whichever variant they arrived with.
     */
    private static List<String> canonical(Surface.Container container, List<String> selectors) {
        Optional<PathTree.Located> found = located(container, selectors);
        if (found.isEmpty()
                || !(found.get().resolution() instanceof PathTree.Resolution.Found node)
                || node.path().isEmpty()) {
            return selectors;
        }
        return List.of(String.join("/", node.path()));
    }

    private static String containerArgument(Surface.Container container) {
        return container.isModule() ? "" : " " + container.name();
    }

    /** One argument as a shell reads it back: quoted when it holds whitespace or a quote character. */
    private static String shellWord(String token) {
        return token.matches(".*[\\s'\"].*") ? "\"" + token.replace("\"", "\\\"") + "\"" : token;
    }
}
