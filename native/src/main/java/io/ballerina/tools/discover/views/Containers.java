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
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.Documents;
import io.ballerina.tools.discover.render.Report;
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
 * {@code client} / {@code class} / {@code funcs} — one implementation, three scopes.
 *
 * <p>The three verbs differ ONLY in which slice of {@link Surface} they address. Everything below — how a
 * positional resolves, how a selector is parsed, how much is printed and what {@code Next} offers — is one code
 * path, because three copies of it would be three places for the same rule to rot separately.
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
 * and the exact next command rather than silently degrading. Exactly one result, and a container mixing resource
 * paths with named methods under the ceiling, are the two cases this still answers as Markdown — see
 * {@link #fullAnswer} and {@link #mixedAnswer} — because neither has a ceiling problem to solve.
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
     * How many bytes a whole container's signatures may take — used only by the still-Markdown, still-dead
     * {@code -r} code register path ({@link #codeAnswer}) and its miss case, which this phase does not reach
     * from the CLI (there is no {@code -r} flag left to set {@code Options.resolve()} true) and does not delete
     * either — that is item 8's job, alongside the rest of the code register.
     */
    public static final int MAX_LISTING_BYTES = 20_000;

    /**
     * How many bytes a type closure may take when inlined under a single fully-resolved result — {@link
     * #renderFull}'s own budget, unrelated to the entry ceiling above: this bounds a CLOSURE (how many types a
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

    /**
     * How many bytes of member names a MISS may offer before it prints a count instead.
     *
     * <p>Far below either listing budget, because a miss is not an answer: its job is to say what was asked for,
     * roughly what is there, and the command that lists it properly. See {@link #missComment} for the 42KB reply
     * to a github typo that set this.
     */
    private static final int MAX_MISS_NAME_BYTES = 1_500;

    /** Accessors a resource function may declare. Not a closed set — see {@link #isAccessor}. */
    private static final Pattern ACCESSOR = Pattern.compile("[a-z][a-zA-Z0-9]*");

    private Containers() {
    }

    /**
     * @param selectors everything after the package: a container name, a member, an accessor and a path
     * @param filter the {@code --filter} keyword, or {@code null}
     * @param resolve {@code -r} from the tool's earlier grammar — dead from the CLI now (see
     *     {@link #MAX_LISTING_BYTES})
     * @param all {@code --all} from the tool's earlier grammar — dead from the CLI now, same reason
     * @param page {@code --page}, 1-indexed, for a method listing over the ceiling
     */
    public record Options(List<String> selectors, String filter, boolean resolve, boolean all, int page) {

        public static Options bare() {
            return new Options(List.of(), null, false, false, 1);
        }

        public Options(List<String> selectors) {
            this(selectors, null, false, false, 1);
        }

        public boolean filtered() {
            return filter != null && !filter.isBlank();
        }
    }

    /**
     * What a bucket query answers with: still-Markdown prose for the cases with no ceiling problem (exactly one
     * result, a mixed resource-and-method container under the ceiling, kind tolerance, a miss), or a value on
     * the RFC's shared result IR for the cases the ceiling actually governs.
     */
    public sealed interface Answer {

        record Markdown(String text) implements Answer { }

        record Structured(DiscoverResult result) implements Answer { }

        static Answer markdown(String text) {
            return new Markdown(text);
        }

        static Answer structured(DiscoverResult result) {
            return new Structured(result);
        }
    }

    public static Result<Answer> render(LoadedPackage loaded, Surface.Scope scope, Options options) {
        return render(loaded, scope, options, null);
    }

    private static Result<Answer> render(
            LoadedPackage loaded, Surface.Scope scope, Options options, String note) {
        List<Surface.Container> containers = Surface.of(loaded.library(), scope);
        if (containers.isEmpty()) {
            return elsewhere(loaded, scope, options, "this package declares none");
        }

        List<String> selectors = options.selectors();
        if (selectors.isEmpty()) {
            if (containers.size() == 1) {
                return answer(loaded, scope, containers.get(0), List.of(), options, note);
            }
            return Result.ok(roster(loaded, scope, containers, options));
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
        Result<Answer> exact = byOwner(loaded, scope, containers, selectors, options, note, true);
        if (exact != null) {
            return exact;
        }

        // 4. Another verb's scope. BEFORE the substring pass, and that ordering is load-bearing: `Cookie` is a
        //    CLASS, and it is also a substring of `getCookieStore`, which two of http's ten clients declare. Run
        //    the fuzzy pass first and `client ballerina/http Cookie` answers with a roster of two clients that
        //    happen to contain those letters instead of routing to the class the caller plainly named.
        Result<Answer> other = elsewhereIfKnown(loaded, scope, options);
        if (other != null) {
            return other;
        }

        // 5. A substring of a member name, which is the widening a caller relies on when they half-remember one.
        Result<Answer> fuzzy = byOwner(loaded, scope, containers, selectors, options, note, false);
        if (fuzzy != null) {
            return fuzzy;
        }

        return elsewhere(loaded, scope, options, "no " + scope.verb() + " declares it");
    }

    /**
     * Whichever containers in this scope hold the selector: one is answered, several are a roster.
     *
     * <p>{@code exactly} is two passes rather than two code paths — the resolution ORDER is the whole subtlety
     * here, and expressing it as one function called twice is what keeps the two passes from drifting into
     * different notions of a match.
     */
    private static Result<Answer> byOwner(
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
        return Result.ok(Answer.markdown(ownerRoster(loaded, scope, owners, selectors)));
    }

    /**
     * Is this selector known to ANOTHER scope, or to the declaration roster?
     *
     * <p>Split out of {@link #elsewhere} so the resolution order can consult it without committing to a failure:
     * {@code null} means "not there either", which is what lets the substring pass run afterwards.
     */
    private static Result<Answer> elsewhereIfKnown(
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
    private static Result<Answer> elsewhere(
            LoadedPackage loaded, Surface.Scope scope, Options options, String why) {
        if (options.selectors().isEmpty()) {
            return Result.ok(Answer.markdown(emptyScope(loaded, scope, why)));
        }
        // The exact pass first, then a substring of a member in another scope — the same two-pass order the
        // in-scope resolution uses, for the same reason.
        Result<Answer> known = elsewhereIfKnown(loaded, scope, options);
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
                + "Canonical: " + Texts.code("bal discover " + loaded.qualified().qualified()
                + " " + actual.verb() + " " + token);
    }

    private static String ownerNote(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container owner, String token) {
        return Texts.code(token) + " is declared on " + Texts.code(owner.label()) + " — showing it. "
                + "Canonical: " + Texts.code("bal discover " + loaded.qualified().qualified()
                + " " + scope.verb() + " " + owner.name() + " " + token);
    }

    /**
     * Nothing matched anywhere, with the names that came closest.
     *
     * <p>The suggestion must never rebuild the command WITHOUT the argument that failed.
     */
    private static Failure notFound(
            LoadedPackage loaded, Surface.Scope scope, String token, Declarations index) {
        String pkg = loaded.qualified().qualified();
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
    private static String emptyScope(LoadedPackage loaded, Surface.Scope scope, String why) {
        String pkg = loaded.qualified().qualified();
        Report report = new Report(scope.verb());
        report.heading(1, title(scope) + " — " + pkg);
        List<Report.Fact> facts = new ArrayList<>(Report.warning(loaded.warning()));
        facts.add(new Report.Fact(title(scope), why));
        report.facts(facts);

        report.heading(2, "Next");
        List<String> next = new ArrayList<>();
        for (Surface.Scope other : Surface.Scope.values()) {
            if (other == scope) {
                continue;
            }
            List<Surface.Container> containers = Surface.of(loaded.library(), other);
            if (!containers.isEmpty()) {
                next.add(Texts.code("bal discover " + pkg + " " + other.verb()) + " — "
                        + Texts.count(containers.size()) + " " + title(other).toLowerCase(java.util.Locale.ROOT)
                        + (other == Surface.Scope.MODULE
                                ? " (" + Texts.count(containers.get(0).functions().size()) + " functions)"
                                : ""));
            }
        }
        next.add(Texts.code("bal discover " + pkg) + " — the buckets this package has");
        report.bullets(next);
        return report.toString();
    }

    // -----------------------------------------------------------------------
    // Rosters
    // -----------------------------------------------------------------------

    /**
     * Several containers and nothing to choose between them yet — the RFC's entry ceiling, applied to a roster
     * of containers rather than to one container's own members.
     */
    private static Answer roster(
            LoadedPackage loaded, Surface.Scope scope, List<Surface.Container> containers, Options options) {
        String pkg = loaded.qualified().qualified();
        List<Surface.Container> selected = options.filtered()
                ? containers.stream()
                        .filter(container -> !filterEntries(container, options).surface().isEmpty())
                        .toList()
                : containers;

        if (selected.isEmpty()) {
            return Answer.markdown(noRosterMatch(loaded, scope, containers.size(), options));
        }

        List<Surface.Container> shown = selected.subList(0, Math.min(MAX_ENTRIES, selected.size()));
        List<DiscoverResult.ContainerRoster.Container> items = shown.stream()
                .map(container -> new DiscoverResult.ContainerRoster.Container(
                        container.name(),
                        container.operations().size(),
                        (int) container.standalone().stream().filter(Fn.Remote.class::isInstance).count(),
                        (int) container.standalone().stream().filter(Fn.Normal.class::isInstance).count(),
                        "bal discover " + pkg + " " + scope.verb() + " " + container.name()))
                .toList();
        String next = shown.size() < selected.size()
                ? "bal discover " + pkg + " " + scope.verb() + " --filter <keyword>"
                : null;
        return Answer.structured(new DiscoverResult.ContainerRoster(items, selected.size(), next, loaded.warning()));
    }

    private static String noRosterMatch(
            LoadedPackage loaded, Surface.Scope scope, int total, Options options) {
        String pkg = loaded.qualified().qualified();
        Report report = new Report(scope.verb());
        report.heading(1, title(scope) + " — " + pkg);
        List<Report.Fact> facts = new ArrayList<>(Report.warning(loaded.warning()));
        facts.add(new Report.Fact(title(scope), Texts.count(total) + " declared"));
        facts.add(new Report.Fact("Filter", Texts.code(options.filter()) + " — none declare a match"));
        report.facts(facts);
        report.heading(2, "Next");
        report.bullets(List.of("list them all: " + Texts.code("bal discover " + pkg + " " + scope.verb())));
        return report.toString();
    }

    /** What a container holds, split by call form because {@code ->} versus {@code .} is the fact a caller wants. */
    private static String counts(Surface.Container container) {
        List<String> parts = new ArrayList<>();
        int resources = container.operations().size();
        long remote = container.standalone().stream().filter(Fn.Remote.class::isInstance).count();
        long normal = container.standalone().stream().filter(Fn.Normal.class::isInstance).count();
        if (resources > 0) {
            parts.add(Texts.count(resources) + " resource");
        }
        if (remote > 0) {
            parts.add(Texts.count(remote) + " remote");
        }
        if (normal > 0) {
            parts.add(Texts.count(normal) + " normal");
        }
        return parts.isEmpty() ? "nothing callable" : String.join(", ", parts);
    }

    /**
     * One member name, declared on several containers.
     *
     * <p>The answer is the OWNERS with counts, each row ending in the command that opens it — never a bare
     * validation failure. Kept as Markdown: the owner count is bounded by how many containers a bucket has, and
     * no fixture comes close to the entry ceiling here.
     */
    private static String ownerRoster(
            LoadedPackage loaded, Surface.Scope scope, Map<Surface.Container, List<Entry>> owners,
            List<String> selectors) {
        String pkg = loaded.qualified().qualified();
        String token = selectors.get(0);
        Report report = new Report(scope.verb());
        report.heading(1, title(scope) + " — " + pkg + " " + Texts.code(token));

        List<Report.Fact> facts = new ArrayList<>(Report.warning(loaded.warning()));
        facts.add(new Report.Fact("Requested", Texts.code(token)));
        facts.add(new Report.Fact("Declared on", Texts.count(owners.size()) + " of "
                + Texts.count(Surface.of(loaded.library(), scope).size()) + ", so this verb will not choose"));
        report.facts(facts);

        report.heading(2, "Next");
        Surface.Container first = owners.keySet().iterator().next();
        report.bullets(List.of("pick one: " + Texts.code("bal discover " + pkg + " " + scope.verb() + " "
                + first.name() + " " + String.join(" ", selectors))));

        report.heading(2, Texts.count(owners.size()) + " owners");
        report.bullets(owners.entrySet().stream()
                .map(entry -> Texts.code(entry.getKey().name()) + " — " + Texts.count(entry.getValue().size())
                        + " match" + (entry.getValue().size() == 1 ? "" : "es") + " · "
                        + Texts.code("bal discover " + pkg + " " + scope.verb() + " " + entry.getKey().name()
                                + " " + String.join(" ", selectors)))
                .toList());
        return report.toString();
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
        List<Entry> entries = PathTree.operationsUnder(found.node()).stream()
                .map(operation -> new Entry(operation.fn(), operation.segments()))
                .toList();
        String accessor = request.get().accessor();
        return accessor == null
                ? entries
                : entries.stream()
                        .filter(entry -> entry.fn() instanceof Fn.Resource resource
                                && resource.accessor().equalsIgnoreCase(accessor))
                        .toList();
    }

    /**
     * The facts rows a path selector earns: where it landed, and what a wildcard did not take.
     */
    private static List<Report.Fact> pathFacts(Surface.Container container, List<String> selectors) {
        Optional<PathTree.Located> found = located(container, selectors);
        if (found.isEmpty()) {
            return List.of();
        }
        PathTree.Located located = found.get();
        List<Report.Fact> facts = new ArrayList<>();
        if (located.resolution() instanceof PathTree.Resolution.Found node
                && !node.path().isEmpty()) {
            facts.add(new Report.Fact("Path", Texts.code(String.join("/", node.path()))));
        }
        if (located.relocated() && !located.alternatives().isEmpty()) {
            facts.add(new Report.Fact("Located",
                    Texts.code(String.join("/", located.alternatives().get(0)))
                            + " — the only match for that segment under the requested prefix"));
        }
        if (located.resolution() instanceof PathTree.Resolution.Found node) {
            for (PathTree.Descent.Sibling other : node.alsoMatched()) {
                facts.add(new Report.Fact("Also matched",
                        Texts.code(String.join("/", other.path())) + " (" + Texts.count(other.total())
                                + "), not included here"));
            }
        }
        return List.copyOf(facts);
    }

    /**
     * The same relocation/wildcard advisory {@link #pathFacts} renders as Markdown facts rows, as one line for
     * the structured IR's own {@code note} field — the RFC's shapes have no room for several distinct facts rows,
     * but silently dropping which sibling branch a wildcard or an auto-relocation did NOT take is exactly the bug
     * this mechanism exists to prevent (GITHUB-02: {@code repos/*}/{@code *} answered with 420 of 421 operations
     * under exit 0 and nothing said so), so it travels here instead of being lost when the answer is a resource
     * listing rather than one full signature.
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

    private static Result<Answer> answer(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            Options options, String note) {
        List<Entry> selected = select(container, selectors);
        List<Entry> documented = List.of();

        if (options.filtered()) {
            Filter.Split<Entry> split = Filter.apply(options.filter(), selected,
                    entry -> Filter.surfaceOf(entry.fn()), entry -> Filter.docsOf(entry.fn()));
            selected = split.surface();
            documented = split.documented();
        }

        // The register is a property of the DOCUMENT, so it is decided BEFORE the answer's shape: a `-r`
        // response is nothing but declarations even when the selection came back empty.
        if (options.resolve()) {
            return Result.ok(Answer.markdown(
                    codeAnswer(loaded, scope, container, selected, selectors, options, note)));
        }
        if (selected.isEmpty() && documented.isEmpty()) {
            return Result.ok(Answer.markdown(nothingMatched(loaded, scope, container, selectors, options)));
        }
        if (selected.size() == 1) {
            return Result.ok(Answer.markdown(
                    fullAnswer(loaded, scope, container, selectors, selected.get(0), documented, options, note)));
        }

        // The constructor is never part of the ceiling problem — it is one signature, always shown once, on
        // request (`init`/`new`) rather than folded into a "many entries" listing.
        List<Entry> callable = selected.stream()
                .filter(entry -> !(entry.fn() instanceof Fn.Constructor))
                .toList();
        boolean hasResources = callable.stream().anyMatch(entry -> entry.fn() instanceof Fn.Resource);
        boolean hasNamed = callable.stream().anyMatch(entry -> !(entry.fn() instanceof Fn.Resource));
        if (hasResources && hasNamed) {
            // A container answering to both `->path.accessor()` and `->name()` under the ceiling — measured,
            // only ballerina/http's Client does this among real connectors surveyed, at 22 total entries. Kept
            // as Markdown, sectioned by call form: the RFC's separate resource/method JSON shapes have no
            // combined form, and this is the one case item 4 leaves for a later pass rather than inventing one
            // under this item's own time pressure.
            return Result.ok(Answer.markdown(
                    mixedAnswer(loaded, scope, container, selectors, selected, documented, options, note)));
        }
        if (hasResources) {
            return Result.ok(resourceAnswer(loaded, scope, container, selectors, callable, options, note));
        }
        return Result.ok(methodAnswer(loaded, scope, container, callable, options, note));
    }

    /**
     * A selector that matched nothing, answered with what IS there.
     *
     * <p>Exit 0 with the alternatives rather than a failure: an empty selection is a fact about the container,
     * and the caller's next move is in the document.
     */
    private static String nothingMatched(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            Options options) {
        String pkg = loaded.qualified().qualified();
        String asked = options.filtered() ? options.filter() : String.join(" ", selectors);
        List<Entry> all = entriesOf(container);

        Report report = new Report(scope.verb());
        report.heading(1, title(scope) + " — " + pkg + containerSuffix(container));
        List<Report.Fact> facts = new ArrayList<>(Report.warning(loaded.warning()));
        facts.add(new Report.Fact("Requested", Texts.code(asked)));
        facts.add(new Report.Fact("Matched", "nothing on " + Texts.code(container.label()) + ", which declares "
                + counts(container)));
        report.facts(facts);

        // Rule 2 of segment location: more than one occurrence is LISTED and the request stops there. Picking
        // one is exactly the failure anchoring exists to prevent.
        List<List<String>> alternatives = located(container, selectors)
                .map(PathTree.Located::alternatives)
                .orElse(List.of());

        report.heading(2, "Next");
        List<String> next = new ArrayList<>();
        if (alternatives.size() > 1) {
            next.add("pick one of the paths below: " + Texts.code("bal discover " + pkg + " " + scope.verb()
                    + containerArgument(container) + " '" + String.join("/", alternatives.get(0)) + "'"));
        }
        next.add("list what is there: " + Texts.code("bal discover " + pkg + " " + scope.verb()
                + containerArgument(container)));
        report.bullets(next);

        if (alternatives.size() > 1) {
            report.heading(2, Texts.count(alternatives.size()) + " paths carry that segment");
            report.literal(alternatives.stream().map(path -> String.join("/", path)).toList());
            return report.toString();
        }

        if (container.hasPaths()) {
            PathTree tree = PathTree.build(container.operations());
            report.heading(2, "Top-level path segments");
            report.literal(Report.columns(tree.children().stream()
                    .map(child -> child.segment() + " " + Texts.count(child.total()))
                    .toList()));
        } else if (!all.isEmpty()) {
            report.heading(2, Texts.count(all.size()) + " declared here");
            report.literal(Report.columns(all.stream().map(Entry::label).toList()));
        }
        return report.toString();
    }

    // -----------------------------------------------------------------------
    // Exactly one result, and the mixed case — still Markdown, no ceiling problem
    // -----------------------------------------------------------------------

    private static String fullAnswer(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            Entry entry, List<Entry> documented, Options options, String note) {
        String pkg = loaded.qualified().qualified();
        Report report = new Report(scope.verb());
        report.heading(1, title(scope) + " — " + pkg + containerSuffix(container));

        List<Report.Fact> facts = new ArrayList<>(Report.warning(loaded.warning()));
        if (note != null) {
            facts.add(new Report.Fact("Note", note));
        }
        if (!container.isModule()) {
            facts.add(new Report.Fact("Container", Texts.code(container.name()) + " — " + counts(container)));
        }
        if (!selectors.isEmpty()) {
            facts.add(new Report.Fact("Selector", Texts.code(String.join(" ", selectors)) + " — 1 of "
                    + Texts.count(entriesOf(container).size())));
            facts.addAll(pathFacts(container, selectors));
        }
        if (options.filtered()) {
            facts.add(new Report.Fact("Filter", Texts.code(options.filter()) + " — 1 by name or type, "
                    + Texts.count(documented.size()) + " more by documentation only"));
        }
        facts.add(new Report.Fact("Showing", "the declaration in full, with the types it names"));
        report.facts(facts);
        if (facts.stream().anyMatch(fact -> "Also matched".equals(fact.label()))) {
            report.paragraph("A wildcard takes one branch. What follows is short by exactly the paths in the "
                    + "'Also matched' rows above — ask for those by name before concluding an operation does "
                    + "not exist.");
        }

        report.heading(2, "Next");
        report.bullets(nextBullets(loaded, scope, container, canonical(container, selectors)));

        renderFull(report, container, entry, loaded, options);

        if (!documented.isEmpty()) {
            report.heading(2, Texts.count(documented.size()) + " matched documentation only");
            report.literal(Report.columns(documented.stream().map(Entry::label).toList()));
        }
        return report.toString();
    }

    /**
     * One result, in full, with the declarations its signature names.
     *
     * <p>The inlining is a DEPTH-1 closure and it is what makes the common flow one call rather than two: the
     * caches DELETE on github names {@code ActionsDeleteActionsCacheByKeyQueries}, the included-record parameter
     * whose fields are the call's named arguments, and no signature line spells those out.
     */
    private static void renderFull(
            Report report, Surface.Container container, Entry entry, LoadedPackage loaded, Options options) {
        report.heading(2, "The call — " + Texts.code(entry.callForm()));
        report.ballerina(List.of(container.isModule() && entry.fn() instanceof Fn.Standalone standalone
                ? Signatures.renderStandaloneFunction(standalone)
                : Signatures.renderMemberFunction(entry.fn(), "", Signatures.Detail.FULL)));

        Declarations index = Declarations.index(loaded.library().addressable());
        List<String> roots = Closure.rootsOf(entry.fn(), index);
        if (roots.isEmpty()) {
            return;
        }
        Closure.Result closure = options.resolve()
                ? Closure.of(roots, index, Closure.MAX_BYTES, Closure.UNBOUNDED)
                : Closure.of(roots, index, MAX_CLOSURE_BYTES, 1);
        List<String> rendered = closure.names().stream()
                .map(name -> TypeDefs.renderTypeDef(index.get(name)))
                .toList();
        if (rendered.isEmpty()) {
            return;
        }
        report.heading(2, "The types it names — " + Texts.count(rendered.size()));
        report.ballerina(rendered);
        if (closure.truncated()) {
            report.paragraph(Texts.count(closure.omitted().size()) + " more reached the budget and are not "
                    + "printed: " + closure.omitted().stream().map(Texts::code)
                            .collect(Collectors.joining(", ")) + ".");
        }
    }

    /**
     * A container answering to both {@code ->path.accessor()} and {@code ->name()}, under the ceiling — see the
     * note at its one call site in {@link #answer}.
     */
    private static String mixedAnswer(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            List<Entry> selected, List<Entry> documented, Options options, String note) {
        String pkg = loaded.qualified().qualified();
        Report report = new Report(scope.verb());
        report.heading(1, title(scope) + " — " + pkg + containerSuffix(container));

        List<Report.Fact> facts = new ArrayList<>(Report.warning(loaded.warning()));
        if (note != null) {
            facts.add(new Report.Fact("Note", note));
        }
        facts.add(new Report.Fact("Container", Texts.code(container.name()) + " — " + counts(container)));
        if (!selectors.isEmpty()) {
            facts.add(new Report.Fact("Selector", Texts.code(String.join(" ", selectors)) + " — "
                    + Texts.count(selected.size()) + " of " + Texts.count(entriesOf(container).size())));
            facts.addAll(pathFacts(container, selectors));
        }
        if (options.filtered()) {
            facts.add(new Report.Fact("Filter", Texts.code(options.filter()) + " — "
                    + Texts.count(selected.size()) + " by name or type, " + Texts.count(documented.size())
                    + " more by documentation only"));
        }
        report.facts(facts);

        report.heading(2, "Next");
        report.bullets(nextBullets(loaded, scope, container, canonical(container, selectors)));

        section(report, container, "Constructor", null,
                selected.stream().filter(entry -> entry.fn() instanceof Fn.Constructor).toList());
        section(report, container, "Resource functions", "-> and a path",
                selected.stream().filter(entry -> entry.fn() instanceof Fn.Resource).toList());
        section(report, container, "Remote functions", "->",
                selected.stream().filter(entry -> entry.fn() instanceof Fn.Remote).toList());
        section(report, container, container.isModule() ? "Module-level functions" : "Normal functions", ".",
                selected.stream().filter(entry -> entry.fn() instanceof Fn.Normal).toList());

        if (!documented.isEmpty()) {
            report.heading(2, Texts.count(documented.size()) + " matched documentation only");
            report.literal(Report.columns(documented.stream().map(Entry::label).toList()));
        }
        return report.toString();
    }

    private static void section(
            Report report, Surface.Container container, String label, String callForm, List<Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        report.heading(2, label + " — " + Texts.count(entries.size())
                + (callForm == null ? "" : ", call with " + Texts.code(callForm)));
        report.ballerina(signatures(entries, container));
    }

    /**
     * The signatures, from the one shared renderer.
     *
     * <p>A module function carries {@code public} and a member does not, so the renderer is chosen by scope
     * rather than by shape.
     */
    private static List<String> signatures(List<Entry> entries, Surface.Container container) {
        return entries.stream()
                .map(entry -> container.isModule() && entry.fn() instanceof Fn.Standalone standalone
                        ? Signatures.renderStandaloneFunction(standalone)
                        : Signatures.renderSignature(entry.fn()))
                .toList();
    }

    // -----------------------------------------------------------------------
    // Remote / normal methods — flat under the ceiling, paginated over it
    // -----------------------------------------------------------------------

    private static Answer methodAnswer(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<Entry> callable,
            Options options, String note) {
        String pkg = loaded.qualified().qualified();
        String command = "bal discover " + pkg + " " + scope.verb() + containerArgument(container);
        List<String> names = callable.stream().map(Entry::label).sorted(Texts.LOCALE_ORDER).toList();
        int total = names.size();

        if (options.filtered() || total <= MAX_ENTRIES) {
            return Answer.structured(
                    new DiscoverResult.MethodList(names, total, total, null, loaded.warning(), note));
        }

        int page = Math.max(1, options.page());
        int from = Math.min((page - 1) * MAX_ENTRIES, total);
        int to = Math.min(from + MAX_ENTRIES, total);
        List<String> shown = names.subList(from, to);
        String next = to < total ? command + " --page " + (page + 1) : null;
        return Answer.structured(
                new DiscoverResult.MethodList(shown, shown.size(), total, next, loaded.warning(), note));
    }

    // -----------------------------------------------------------------------
    // Resource paths — flat under the ceiling, grouped by segment over it
    // -----------------------------------------------------------------------

    private static Answer resourceAnswer(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            List<Entry> callable, Options options, String note) {
        String pkg = loaded.qualified().qualified();
        String baseCommand = "bal discover " + pkg + " " + scope.verb() + containerArgument(container);
        List<String> prefix = canonical(container, selectors);
        List<DiscoverResult.ResourceList.Resource> merged = mergedResources(callable, baseCommand);
        // A relocation or a wildcard's skipped sibling outranks a kind-tolerance note: the two never co-occur in
        // one request (the first is within-bucket, the second crosses buckets before a container is even named),
        // and losing which branch a wildcard did NOT take is the one this mechanism exists to prevent.
        String pathAdvisory = pathNote(container, selectors);
        String combinedNote = pathAdvisory != null ? pathAdvisory : note;

        boolean mustStayFlat = options.filtered() || prefix.size() > MAX_GROUP_DEPTH;
        if (mustStayFlat || merged.size() <= MAX_ENTRIES) {
            List<DiscoverResult.ResourceList.Resource> shown =
                    merged.subList(0, Math.min(MAX_ENTRIES, merged.size()));
            String next = shown.size() < merged.size()
                    ? baseCommand + pathArgument(prefix) + " --filter <keyword>"
                    : null;
            return Answer.structured(new DiscoverResult.ResourceList(
                    shown, shown.size(), merged.size(), next, loaded.warning(), combinedNote));
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
        return Answer.structured(
                new DiscoverResult.PathGroups(jsonGroups, groups.size(), next, loaded.warning(), combinedNote));
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
    // The code register — dead from the CLI (no `-r` flag sets Options.resolve() true), left for item 8
    // -----------------------------------------------------------------------

    /**
     * A miss in the code register: what was asked, what the container holds, and the way out — bounded.
     */
    private static String missComment(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            Options options) {
        List<Entry> all = entriesOf(container);
        String asked = options.filtered() ? "\"" + options.filter() + "\"" : String.join(" ", selectors);
        String opening = "// Nothing on " + container.label() + " matches " + asked;
        if (all.isEmpty()) {
            return opening + ". It declares nothing callable.";
        }
        String command = "bal discover " + loaded.qualified().qualified() + " " + scope.verb()
                + " " + container.name();
        List<String> names = new ArrayList<>();
        int spent = 0;
        for (Entry entry : all) {
            spent += Texts.byteLength(entry.label()) + 2;
            if (spent > MAX_MISS_NAME_BYTES) {
                break;
            }
            names.add(entry.label());
        }
        String held = names.size() == all.size()
                ? ". It declares: " + String.join(", ", names)
                : ". It declares " + all.size() + ", of which: " + String.join(", ", names) + ", …";
        return opening + held + "\n// Read them: " + command
                + "\n// Or narrow it: " + command + " --filter \"" + Texts.plain(asked) + "\"";
    }

    private static String codeAnswer(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<Entry> selected,
            List<String> selectors, Options options, String note) {
        Declarations index = Declarations.index(loaded.library().addressable());
        List<String> blocks = new ArrayList<>();
        blocks.add(Documents.headerComment(loaded.label(), loaded.warning()));
        if (note != null) {
            blocks.add("// Note: " + Texts.plain(note));
        }
        if (selected.isEmpty()) {
            blocks.add(missComment(loaded, scope, container, selectors, options));
            return String.join("\n\n", blocks) + "\n";
        }

        List<Entry> shown = selected;
        int budget = options.all() ? Integer.MAX_VALUE : MAX_LISTING_BYTES;
        List<String> rendered = signatures(shown, container);
        int spent = rendered.stream().mapToInt(Texts::byteLength).sum();
        if (spent > budget) {
            int kept = 0;
            int running = 0;
            for (String signature : rendered) {
                running += Texts.byteLength(signature);
                if (running > budget) {
                    break;
                }
                kept++;
            }
            blocks.add("// " + Texts.count(selected.size() - kept) + " of " + Texts.count(selected.size())
                    + " signatures omitted at the " + Texts.count(budget) + "-byte budget. Narrow with a "
                    + "selector or --filter, or ask for one by name.");
            shown = selected.subList(0, Math.max(1, kept));
            rendered = signatures(shown, container);
        }
        blocks.addAll(rendered);

        List<String> roots = new ArrayList<>();
        shown.forEach(entry -> Closure.rootsOf(entry.fn(), index).forEach(root -> {
            if (!roots.contains(root)) {
                roots.add(root);
            }
        }));
        Closure.Result closure = Closure.of(roots, index, Closure.MAX_BYTES, Closure.UNBOUNDED);
        closure.names().forEach(name -> blocks.add(TypeDefs.renderTypeDef(index.get(name))));

        String omission = Closure.omissionComment(closure.omitted());
        if (omission != null) {
            blocks.add(omission);
        }
        Set<String> printed = new LinkedHashSet<>(closure.names());
        List<Closure.ExternalRef> external = new ArrayList<>(Closure.externalRefs(closure.names(), index));
        String footer = Closure.externalFooter(external, loaded, printed);
        if (footer != null) {
            blocks.add(footer);
        }
        return String.join("\n\n", blocks) + "\n";
    }

    // -----------------------------------------------------------------------
    // Shared prose
    // -----------------------------------------------------------------------

    // TODO(follow-up): a richer "Next" for the still-Markdown answers (fullAnswer, mixedAnswer) — pointing at
    // --filter and, for a resource-bearing mixed container, the resource listing directly — once real usage
    // shows which suggestions are worth the line. Deliberately minimal for now.
    private static List<String> nextBullets(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors) {
        String pkg = loaded.qualified().qualified();
        List<String> next = new ArrayList<>();
        if (!selectors.isEmpty() && !container.isModule()) {
            next.add("everything on this container: " + Texts.code("bal discover " + pkg + " " + scope.verb()
                    + " " + container.name()));
        }
        next.add("the buckets this package has: " + Texts.code("bal discover " + pkg));
        return next;
    }

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

    private static String containerSuffix(Surface.Container container) {
        return container.isModule() ? "" : " " + Texts.code(container.name());
    }

    private static String title(Surface.Scope scope) {
        return switch (scope) {
            case CLIENT -> "Clients";
            case CLASS -> "Classes";
            case MODULE -> "Module functions";
        };
    }
}
