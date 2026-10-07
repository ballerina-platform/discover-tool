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
 * <p><b>OUTPUT SIZE IS AN ENTRY/LINE CEILING NOW, NOT A BYTE BUDGET.</b> The RFC replaces this tool's earlier
 * byte-budget tier ladder (bytes of quoted Ballerina, degrading through four tiers) with a hard ceiling of
 * {@value #MAX_ENTRIES} entries per listing: under it, everything is shown; over it, resource paths selected by a
 * path GROUP by segment, and every listing — a roster, a level of groups, methods, a mixed listing, a resource
 * selection that cannot group, and documentation-only matches — PAGES with {@code --page}, all honestly
 * disclosing {@code shown}/{@code total} and the exact next command rather than silently degrading. Every answer
 * is a {@link DiscoverResult}, so {@code --output} renders all of them the same way.
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
     * arithmetic every paginated listing in this tool shares (every listing here, a filtered readme-chunk listing
     * in {@link Readme}), so a future fix to the page-boundary rule only has to be made once.
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
                        "--page " + requested + " is out of range: this listing has " + total + " entries on "
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

    /** Whether {@code container} holds what the selectors start with, read in full or by a leading part alone. */
    private static boolean knows(Surface.Container container, List<String> selectors, boolean exactly) {
        return !select(container, selectors, exactly).isEmpty();
    }

    /** Does {@code token} alone select a member that is not a resource, which is what it names when more follows? */
    private static boolean namesMember(Surface.Container container, String token) {
        return select(container, List.of(token)).stream()
                .anyMatch(entry -> !(entry.fn() instanceof Fn.Resource));
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
                    || containers.stream().anyMatch(container -> knows(container, options.selectors(), true))) {
                return render(loaded, other, options, kindNote(loaded, other, options.selectors()));
            }
        }
        if (options.selectors().size() == 1 && Types.declares(loaded, token)) {
            return Types.render(loaded, new Types.Options(options.selectors(), options.filter(), options.page(),
                    kindNote(loaded, "type", options.selectors())));
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

    /** {@link #kindNote}, its first selector spelled as the container of {@code scope} it names, if it names one. */
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
                + "bal discover " + loaded.pkgArgument() + " " + scope.verb() + " " + owner.name()
                + shellWords(selectors);
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
                .map(Service::listener)
                .distinct()
                .collect(Collectors.joining(", "));
    }

    /** {@link #listenerNames} as a one-line fact, for the containers the ceiling has no room to list a fact row for. */
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

    /** The first of a container's pairings whose binding is unsettled, if any is. */
    private static Optional<Bindings.Binding> unsettled(Surface.Container container) {
        return container.pairings().stream().map(Service::binding).filter(Bindings.Binding::isUnsettled).findFirst();
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
        if (types > 0 && !"type".equals(bucket)) {
            elsewhere.add(new DiscoverResult.EmptyBucket.Elsewhere("type", types, "bal discover " + pkg + " type"));
        }
        return new DiscoverResult.EmptyBucket(bucket, List.copyOf(elsewhere), loaded.warning());
    }

    // -----------------------------------------------------------------------
    // Rosters
    // -----------------------------------------------------------------------

    /**
     * Several containers and nothing to choose between them yet — the RFC's entry ceiling, applied to a roster
     * of containers rather than to one container's own members, and paged like every other listing.
     *
     * <p>{@code --filter} keeps a container whose own NAME matches as well as one with a matching member, and a
     * name match opens the whole container rather than its members narrowed by the keyword: a container that
     * declares no methods at all ({@code ballerinax/postgresql}'s {@code *Value} classes) has nothing else for
     * the keyword to match.
     */
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

    /**
     * The command that opens one container — in ANOTHER package for a service type a listener here accepts but
     * another module declares ({@code postgresql:CdcListener}'s {@code cdc:Service}), since that is where its
     * contract is, when {@link LoadedPackage#argumentFor} can name that package; otherwise the local stub that
     * says where the type is declared.
     */
    private static String openCommand(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, Options options) {
        Optional<String> foreign = foreignPairing(container).flatMap(service -> foreignCommand(loaded, service));
        if (foreign.isPresent()) {
            return foreign.get();
        }
        return "bal discover " + loaded.pkgArgument() + " " + scope.verb() + " " + Texts.shellWord(container.name())
                + (namedByFilter(container, options) ? "" : filterArgument(options));
    }

    private static Optional<Service> foreignPairing(Surface.Container container) {
        return container.pairings().stream().filter(service -> service.declaredIn().isPresent()).findFirst();
    }

    private static Optional<String> foreignCommand(LoadedPackage loaded, Service service) {
        return loaded.argumentFor(service.declaredIn().orElseThrow())
                .map(target -> "bal discover " + target + " service " + shellWord(service.name()));
    }

    /**
     * One member name, declared on several containers.
     *
     * <p>The answer is the OWNERS with counts, each ending in the command that opens it — never a bare
     * validation failure, and never a silent pick of one.
     */
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
                        bucket + " " + entry.getKey().name() + member))
                .toList();
        return Result.ok(new DiscoverResult.Owners(String.join(" ", selectors), listed, owners.size(),
                window.paging(), window.next(command), loaded.warning(), note));
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
         * {@code ->}, {@code .} or {@code new} — DERIVED and always printed.
         */
        public String callForm() {
            return switch (fn) {
                case Fn.Remote ignored -> "->";
                case Fn.Resource ignored -> "->";
                case Fn.Normal ignored -> ".";
                case Fn.Constructor ignored -> "new";
            };
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
        // The RFC's own order, and the one every `command` field prints: the path, then the accessor.
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

    /**
     * How many leading selectors this container reads, derived from what they name: two that resolve as a path and
     * its accessor are read as one, a first that names a member that is not a resource is read alone, and on a
     * container with resource paths any other two are a pair that resolves nothing (a no-match). The rest are
     * unread, and rejected rather than answered as if they were never typed.
     */
    private static int consumed(Surface.Container container, List<String> selectors) {
        if (selectors.size() < 2 || !container.hasPaths()) {
            return Math.min(selectors.size(), 1);
        }
        if (!selectByPath(container, selectors.subList(0, 2)).isEmpty()) {
            return 2;
        }
        return namesMember(container, selectors.get(0)) ? 1 : 2;
    }

    /** Selectors after the ones {@code container} reads, as a usage failure naming the command without them. */
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
                .map(path -> "Join the path's segments with `/` in one argument: `" + base + path
                        + filterArgument(options) + "`.");
        List<String> read = selectors.subList(0, consumed);
        List<Entry> selected = select(container, read);
        List<String> kept = selected.size() == 1 ? spelled(selected.get(0), read) : read;
        return unread(selectors, consumed, where + " takes " + takes,
                joined.orElse(drop(kept, selectors.size() - consumed, base, options)));
    }

    /**
     * The same failure for what several containers declare, none of which reads what follows it: {@code owners}
     * maps each to how many selectors it read.
     */
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
     */
    private static Optional<String> joinedPath(Surface.Container container, List<String> selectors) {
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
        String path = String.join("/", segments);
        if (!(PathTree.locate(PathTree.build(container.operations()), PathTree.splitPath(path)).resolution()
                instanceof PathTree.Resolution.Found)) {
            return Optional.empty();
        }
        return Optional.of(" " + shellWord(path) + (accessor == null ? "" : " " + accessor));
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
            parts.add("relocated to " + pathName(node.path())
                    + " — the only match for that path under the requested prefix");
        }
        for (PathTree.Descent.Sibling other : node.alsoMatched()) {
            parts.add("also matched " + pathName(other.path()) + " ("
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

    static DiscoverResult.Documented documentedOn(
            Page window, List<DiscoverResult.Documented.Entry> documented, int offset) {
        return documented.isEmpty()
                ? DiscoverResult.Documented.NONE
                : new DiscoverResult.Documented(window.slice(documented, offset), documented.size());
    }

    /**
     * Several entries: resource paths, methods of one call form, or several forms side by side.
     *
     * <p>The constructor is never part of the ceiling problem — it is one signature, always shown once, on request
     * ({@code init}/{@code new}) rather than folded into a "many entries" listing.
     */
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

    /**
     * A selector that matched nothing, answered with what IS there.
     *
     * <p>Exit 0 with the alternatives rather than a failure: an empty selection is a fact about the container,
     * and the caller's next move is in the answer.
     */
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

        Optional<String> joined = selectors.size() == 2 ? joinedPath(container, selectors) : Optional.empty();
        if (joined.isPresent()) {
            return Result.ok(new DiscoverResult.NoMatch(asked, containerName(container),
                    List.of(String.join("/", selectors)), List.of(), null,
                    command + joined.get() + filterArgument(options), DiscoverResult.Documented.NONE, null,
                    loaded.warning(), note));
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
            case Fn.Constructor ignored -> "init";
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

    /**
     * A selection in more than one call form — resources beside named methods ({@code ballerina/http}'s and
     * {@code ballerinax/sap}'s {@code Client}), or remote methods beside normal ones — split by form, because a
     * flat list of names loses whether each is called with {@code ->} or {@code .}. The three sections are one
     * sequence — resources, then remote, then normal — paged {@value #MAX_ENTRIES} at a time like every other flat
     * listing, so a page can end partway through one section and start the next.
     */
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

    /** Each method of one call form, alphabetical, with the command that opens its signature. */
    private static List<DiscoverResult.Method> methodsOf(List<Entry> entries, Class<? extends Fn> form, String base) {
        return entries.stream()
                .filter(entry -> form.isInstance(entry.fn()))
                .map(Entry::label)
                .sorted(Texts.LOCALE_ORDER)
                .map(name -> new DiscoverResult.Method(name, base + " " + shellWord(name)))
                .toList();
    }

    // -----------------------------------------------------------------------
    // Remote / normal methods — flat under the ceiling, paginated over it
    // -----------------------------------------------------------------------

    private static Result<DiscoverResult> methodAnswer(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            List<Entry> callable, Options options, List<DiscoverResult.Documented.Entry> documented, String warning,
            String note) {
        // A page is turned on the SAME selection — selector and filter both — or paging would silently widen
        // back out to the container's full roster.
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

    // -----------------------------------------------------------------------
    // Resource paths — flat under the ceiling, grouped by segment over it
    // -----------------------------------------------------------------------

    /**
     * Resource paths: flat under the ceiling, grouped by their next literal segment over it.
     *
     * <p>Only a selection anchored at a path GROUPS, and it groups the SELECTED operations, never the whole tree
     * under the node — an accessor narrows the counts and rides along into every group's {@code command}. A
     * selection that is not a path (a name substring such as {@code action}, or a {@code --filter}) has no prefix a
     * group name could extend without inventing one, so it pages flat instead, every entry carrying its own command.
     */
    private static Result<DiscoverResult> resourceAnswer(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            List<Entry> callable, Options options, List<DiscoverResult.Documented.Entry> documented, String warning,
            String note) {
        String base = baseCommand(loaded, scope, container);
        String command = base + selectorArguments(container, selectors) + filterArgument(options);
        List<DiscoverResult.ResourceList.Resource> merged = mergedResources(callable, base);
        // A kind-tolerance note and a path advisory DO co-occur: `elsewhereIfKnown` re-invokes `render` with the
        // whole original selector list still attached, so a container reached across buckets can go on to
        // resolve a wildcard or a relocation in that same call. Concatenated rather than one outranking the
        // other, for the same reason `pathNote` itself joins a relocation and a skipped sibling instead of
        // picking one — losing either fact silently is what this mechanism exists to prevent.
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

    /**
     * The groups at one level, and the paths that end there, as one paged sequence: the paths first, since no
     * group name reaches them, then the groups, busiest first.
     */
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

    /**
     * A group's own selector: its path, plus the accessor the listing was narrowed by. Checked against the
     * selection it would actually make, because a path that itself declares that accessor reads as one
     * operation's signature rather than as a filter over the group — and a call that answers with one of the
     * group's {@code count} operations is a call that silently loses the rest. Such a group is opened unnarrowed.
     */
    private static String groupArguments(Surface.Container container, String name, String accessor, int count) {
        if (accessor != null && select(container, List.of(name, accessor)).size() == count) {
            return " " + shellWord(name) + " " + accessor;
        }
        return " " + shellWord(name);
    }

    /** One entry per resource PATH, not one per accessor — several accessors on one path share one row. */
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

    /** The node at exactly {@code path}, walked segment by segment through a tree of the selected operations. */
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

    /**
     * The next literal children into {@code into}, keyed by their FULL path — through any purely-parameter level
     * in between, since a parameter carries no naming choice and is not a grouping boundary, but spelled out in
     * the key: {@code repos/branches} alone also names {@code repos/:owner/:repo/rules/branches}, so a group
     * named by its literal segments only would be an ambiguous address. The operations that terminate
     * transparently through {@code node} and every parameter level walked go into {@code terminal} — a parameter
     * node can carry its own terminal operations AND further literal children at once (github's
     * {@code repos/:owner/:repo} declares get/update/delete of the repo itself alongside 63 literal children like
     * {@code issues}), and both have to survive the same walk.
     */
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

    /** A path as it is printed and typed back: keyword segments escaped, the root spelled {@code .}. */
    private static String pathName(List<String> segments) {
        return segments.isEmpty() ? "." : escapeKeywordSegments(String.join("/", segments));
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

    // -----------------------------------------------------------------------
    // Shared
    // -----------------------------------------------------------------------

    /**
     * The path a selection was anchored at, when the selection came from a path at all — in the tree's own
     * spelling.
     *
     * <p>A pointer offers the CANONICAL form, not the one the caller happened to type: {@code repos/owner/repo},
     * {@code repos/:owner/:repo} and {@code repos/[string owner]/[string repo]} all address one path, and a
     * command echoing the typed spelling teaches the reader whichever variant they arrived with.
     */
    private static Optional<List<String>> resolvedPath(Surface.Container container, List<String> selectors) {
        if (selectByPath(container, selectors).isEmpty()) {
            return Optional.empty();
        }
        return located(container, selectors)
                .map(PathTree.Located::resolution)
                .filter(PathTree.Resolution.Found.class::isInstance)
                .map(resolution -> ((PathTree.Resolution.Found) resolution).path());
    }

    /**
     * The selector, as every follow-up command re-types it: a path selection canonically (path, then accessor), and
     * anything else exactly as given, one shell word per argument — never joined into a path it was not.
     */
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
                + (container.isModule() ? "" : " " + container.name());
    }

    /** {@code selectors} with the one that names {@code entry}'s method or function spelled as it is declared. */
    private static List<String> spelled(Entry entry, List<String> selectors) {
        if (!(entry.fn() instanceof Fn.Standalone named)) {
            return selectors;
        }
        String wanted = Names.normalise(named.name());
        return selectors.stream().map(word -> Names.normalise(word).equals(wanted) ? named.name() : word).toList();
    }

    /**
     * Documentation-only matches as the listings show the same entries — one row per resource path with its
     * accessors, one per method — and in the listings' alphabetical order.
     */
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
