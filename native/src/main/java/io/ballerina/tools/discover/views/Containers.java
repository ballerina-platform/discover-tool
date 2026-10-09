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
import io.ballerina.tools.discover.symbols.PathTree;
import io.ballerina.tools.discover.symbols.Surface;

import java.util.ArrayList;
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
 * <p><b>THE SELECTOR GRAMMAR IS READ OFF THE CONTAINER.</b> The container is resolved first, and only one that
 * declares resource functions reads {@code get}/{@code post}/{@code delete} as an accessor; on any other the same
 * token is a member name.
 *
 * <p><b>A MISS FAILS.</b> A selector is matched exactly as this tool prints it, and one that matches nothing — or
 * names something another bucket holds — is a {@code symbol-not-found} failure whose suggestion is the one command
 * to run next, never a list of guesses: an agent that reads a near miss as an answer reasons from the wrong thing.
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

    private static final String NEW = "new";

    private static final String PATH_SEPARATOR = "/";

    private static final String COMMAND = "bal discover ";

    private static final String VOWELS = "aeiou";

    private Containers() {
    }

    /**
     * @param selectors everything after the package: a container name, a member, an accessor and a path
     * @param filter the {@code --filter} keyword, or {@code null}
     * @param page {@code --page}, 1-indexed, for any listing over the ceiling
     */
    public record Options(List<String> selectors, String filter, int page) {

        public Options(List<String> selectors) {
            this(selectors, null, 1);
        }

        public boolean filtered() {
            return filter != null && !filter.isBlank();
        }
    }

    public static Result<DiscoverResult> render(LoadedPackage loaded, Surface.Scope scope, Options options) {
        if (scope == Surface.Scope.SERVICE) {
            loaded = loaded.withBindings();
        }
        List<Surface.Container> containers = Surface.of(loaded.library(), scope);
        List<String> selectors = options.selectors();
        if (containers.isEmpty()) {
            Optional<Failure> misplaced = selectors.isEmpty()
                    ? Optional.empty()
                    : heldElsewhere(loaded, scope, options);
            return misplaced.isPresent() ? Result.err(misplaced.get()) : Result.ok(emptyBucket(loaded, scope));
        }
        if (selectors.isEmpty()) {
            // Another module's service type has nothing here to open; the roster is what carries its command.
            if (containers.size() == 1 && foreignPairing(containers.get(0)).isEmpty()) {
                return answer(loaded, scope, containers.get(0), List.of(), options);
            }
            return roster(loaded, scope, containers, options);
        }
        if (scope == Surface.Scope.MODULE) {
            return answer(loaded, scope, containers.get(0), selectors, options);
        }
        Optional<Surface.Container> named = Surface.byName(containers, selectors.get(0));
        if (named.isPresent()) {
            return answer(loaded, scope, named.get(), selectors.subList(1, selectors.size()), options);
        }
        return Result.err(notInBucket(loaded, scope, options));
    }

    private static Failure notInBucket(LoadedPackage loaded, Surface.Scope scope, Options options) {
        Optional<Failure> misplaced = heldElsewhere(loaded, scope, options);
        if (misplaced.isPresent()) {
            return misplaced.get();
        }
        String token = options.selectors().get(0);
        String listing = bucketCommand(loaded, scope.verb());
        return loaded.symbolNotFound(List.of(token), notA(loaded, token, scope.noun()),
                "List the " + scope.plural() + ": " + Failure.quoted(listing) + ".", listing);
    }

    /**
     * The failure for a first selector this bucket does not hold but something else in the package does: a container
     * or function of another bucket, a type, an enum member, or a member of one or several containers.
     */
    private static Optional<Failure> heldElsewhere(LoadedPackage loaded, Surface.Scope scope, Options options) {
        List<String> selectors = options.selectors();
        String token = selectors.get(0);
        String message = notA(loaded, token, scope.noun());
        for (Surface.Scope other : Surface.Scope.values()) {
            List<Surface.Container> containers = Surface.of(loaded.library(), other);
            boolean holds = other == Surface.Scope.MODULE
                    ? containers.stream().anyMatch(module -> module.member(token).isPresent())
                    : Surface.byName(containers, token).isPresent();
            if (other != scope && holds) {
                return Optional.of(misplaced(loaded, token, message, "It is " + article(other.noun()),
                        bucketCommand(loaded, other.verb()) + shellWords(selectors) + filterArgument(options)));
            }
        }
        Optional<String> kind = Types.kindOf(loaded, token);
        if (kind.isPresent()) {
            return Optional.of(misplaced(loaded, token, message, "It is " + article(kind.get()),
                    bucketCommand(loaded, Types.BUCKET) + " " + shellWord(token)));
        }
        Optional<String> enumeration = Types.enumOf(loaded, token);
        if (enumeration.isPresent()) {
            return Optional.of(misplaced(loaded, token, message, "It is a member of the enum " + enumeration.get(),
                    bucketCommand(loaded, Types.BUCKET) + " " + shellWord(enumeration.get())));
        }
        return ownedElsewhere(loaded, scope, options, message);
    }

    private static Optional<Failure> ownedElsewhere(
            LoadedPackage loaded, Surface.Scope scope, Options options, String message) {
        List<String> selectors = options.selectors();
        String token = selectors.get(0);
        Map<Surface.Container, Fn> owners = new LinkedHashMap<>();
        for (Surface.Scope each : Surface.Scope.values()) {
            if (each == Surface.Scope.MODULE) {
                continue;
            }
            for (Surface.Container container : Surface.of(loaded.library(), each)) {
                container.member(token).ifPresent(fn -> owners.put(container, fn));
            }
        }
        if (owners.size() == 1) {
            Map.Entry<Surface.Container, Fn> owner = owners.entrySet().iterator().next();
            Surface.Container container = owner.getKey();
            return Optional.of(misplaced(loaded, token, message,
                    "It is " + article(memberNoun(owner.getValue())) + " of " + container.name(),
                    baseCommand(loaded, container.scope(), container) + shellWords(selectors)
                            + filterArgument(options)));
        }
        if (owners.isEmpty()) {
            return Optional.empty();
        }
        Set<Surface.Scope> scopes = owners.keySet().stream().map(Surface.Container::scope).collect(Collectors.toSet());
        Surface.Scope listed = scopes.size() == 1 ? scopes.iterator().next() : scope;
        List<String> names = owners.keySet().stream().map(Surface.Container::name).toList();
        return Optional.of(misplaced(loaded, token, message,
                "It is a method of " + Texts.and(names) + ". List the " + listed.plural(),
                bucketCommand(loaded, listed.verb())));
    }

    private static Failure misplaced(LoadedPackage loaded, String token, String message, String lead, String command) {
        return loaded.symbolNotFound(List.of(token), message, lead + ": " + Failure.quoted(command) + ".", command);
    }

    private static String memberNoun(Fn fn) {
        return switch (fn) {
            case Fn.Remote ignored -> "remote method";
            case Fn.Normal ignored -> "method";
            case Fn.Resource ignored -> "resource method";
            case Fn.Constructor ignored -> "constructor";
        };
    }

    private static String notA(LoadedPackage loaded, String token, String noun) {
        return named(token) + " is not " + article(noun) + " in " + loaded.place() + ".";
    }

    private static String named(String token) {
        return "'" + token + "'";
    }

    private static String article(String noun) {
        return (VOWELS.indexOf(noun.charAt(0)) >= 0 ? "an " : "a ") + noun;
    }

    private static String bucketCommand(LoadedPackage loaded, String bucket) {
        return loaded.command() + " " + bucket;
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

    private static DiscoverResult emptyBucket(LoadedPackage loaded, Surface.Scope scope) {
        return emptyBucket(loaded, scope.verb());
    }

    /** A bucket with nothing in it — {@code type} included — and every other bucket that holds something. */
    static DiscoverResult emptyBucket(LoadedPackage loaded, String bucket) {
        List<DiscoverResult.EmptyBucket.Elsewhere> elsewhere = new ArrayList<>();
        for (Surface.Scope other : Surface.Scope.values()) {
            List<Surface.Container> containers = Surface.of(loaded.library(), other);
            if (other.verb().equals(bucket) || containers.isEmpty()) {
                continue;
            }
            int count = other == Surface.Scope.MODULE ? containers.get(0).functions().size() : containers.size();
            elsewhere.add(new DiscoverResult.EmptyBucket.Elsewhere(other.verb(), count,
                    bucketCommand(loaded, other.verb())));
        }
        int types = Types.count(loaded);
        if (types > 0 && !Types.BUCKET.equals(bucket)) {
            elsewhere.add(new DiscoverResult.EmptyBucket.Elsewhere(Types.BUCKET, types,
                    bucketCommand(loaded, Types.BUCKET)));
        }
        return new DiscoverResult.EmptyBucket(bucket, List.copyOf(elsewhere), loaded.warning());
    }

    private static Result<DiscoverResult> roster(
            LoadedPackage loaded, Surface.Scope scope, List<Surface.Container> containers, Options options) {
        String bucket = bucketCommand(loaded, scope.verb());
        List<Surface.Container> selected = options.filtered()
                ? containers.stream()
                        .filter(container -> namedByFilter(container, options)
                                || !filterEntries(container, options).surface().isEmpty())
                        .toList()
                : containers;

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
        return baseCommand(loaded, scope, container)
                + (namedByFilter(container, options) ? "" : filterArgument(options));
    }

    private static Optional<Service> foreignPairing(Surface.Container container) {
        return container.pairings().stream().filter(service -> service.declaredIn().isPresent()).findFirst();
    }

    private static Optional<String> foreignCommand(LoadedPackage loaded, Service service) {
        return loaded.argumentFor(service.declaredIn().orElseThrow())
                .map(target -> COMMAND + target + " " + Surface.Scope.SERVICE.verb() + " " + shellWord(service.name()));
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
        if (selectors.isEmpty()) {
            return entriesOf(container);
        }
        if (container.hasPaths()) {
            List<Entry> byPath = selectByPath(container, selectors);
            if (!byPath.isEmpty()) {
                return byPath;
            }
        }
        if (selectors.size() > 1) {
            return List.of();
        }
        String token = selectors.get(0);
        // `new` is the constructor, which Ballerina spells `init`. An INPUT alias only — the document still prints
        // `init`, because it prints what the package declares.
        if (NEW.equals(token) && container.constructor().isPresent()) {
            return List.of(new Entry(container.constructor().get(), List.of()));
        }
        return entriesOf(container).stream()
                .filter(entry -> !(entry.fn() instanceof Fn.Resource) && entry.label().equals(token))
                .toList();
    }

    /**
     * A path request, split into the accessor that filters it and the path that anchors it.
     *
     * @param accessor the accessor that filters it, {@code null} when none was given
     * @param tokens the path tokens that anchor it
     */
    private record PathRequest(String accessor, List<String> tokens) { }

    // A path and its accessor are read in either order; the order every `command` prints is the path first.
    private static Optional<PathRequest> pathRequest(Surface.Container container, List<String> selectors) {
        if (!container.hasPaths()) {
            return Optional.empty();
        }
        if (selectors.size() == 1) {
            return Optional.of(new PathRequest(null, PathTree.splitPath(selectors.get(0))));
        }
        if (selectors.size() != 2) {
            return Optional.empty();
        }
        if (isAccessor(container, selectors.get(0))) {
            return Optional.of(new PathRequest(selectors.get(0), PathTree.splitPath(selectors.get(1))));
        }
        if (isAccessor(container, selectors.get(1))) {
            return Optional.of(new PathRequest(selectors.get(1), PathTree.splitPath(selectors.get(0))));
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

    private static boolean namesMember(Surface.Container container, String token) {
        return select(container, List.of(token)).stream().anyMatch(entry -> !(entry.fn() instanceof Fn.Resource));
    }

    private static Failure unread(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            int consumed, Options options) {
        String takes = container.hasPaths()
                ? "a member name, or a resource path and its accessor"
                : "one " + (container.isModule() ? "function" : "member") + " name";
        String where = container.isModule() ? scope.verb() : container.name();
        return unread(selectors, consumed, where + " takes " + takes,
                drop(selectors.subList(0, consumed), selectors.size() - consumed,
                        baseCommand(loaded, scope, container), options));
    }

    /**
     * The failure for {@code funcs} given more than one name, or {@code null}; checked before the package is fetched.
     *
     * @param command the command the bucket is listed with, e.g. {@code bal discover ballerina/io funcs}
     */
    public static Failure extraFunctionNames(String command, Options options) {
        List<String> selectors = options.selectors();
        if (selectors.size() < 2) {
            return null;
        }
        return unread(selectors, 1, Surface.Scope.MODULE.verb() + " takes one function name",
                drop(selectors.subList(0, 1), selectors.size() - 1, command, options));
    }

    private record Fix(String change, String command) { }

    private static Failure unread(List<String> selectors, int consumed, String reason, Fix fix) {
        List<String> unread = selectors.subList(consumed, selectors.size());
        return new Failure.Validation(
                "Unexpected " + (unread.size() == 1 ? "argument " : "arguments ")
                        + unread.stream().map(Containers::named).collect(Collectors.joining(" "))
                        + " after '" + String.join(" ", selectors.subList(0, consumed)) + "': " + reason + ".",
                fix.change() + ": " + Failure.quoted(fix.command()) + ".", fix.command());
    }

    private static Fix drop(List<String> kept, int dropped, String base, Options options) {
        return new Fix("Drop " + (dropped == 1 ? "it" : "them"), base + shellWords(kept) + filterArgument(options));
    }

    /**
     * The failure for selectors that name nothing on {@code container}: its suggestion lists the deepest level the
     * lookup reached — the path prefix that resolved, or else the container.
     */
    private static Failure miss(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            Options options) {
        if (container.isModule()) {
            return notInBucket(loaded, scope, options);
        }
        String base = baseCommand(loaded, scope, container);
        String typed = String.join(" ", selectors);
        String what = selectors.size() > 1 ? "a resource method"
                : selectors.get(0).contains(PATH_SEPARATOR) ? "a resource path" : "a member";
        String message = named(typed) + " is not " + what + " of " + container.name() + " in " + loaded.place()
                + ".";
        List<String> reached = reached(container, selectors);
        if (reached.isEmpty()) {
            return loaded.symbolNotFound(selectors, message, "List its members: " + Failure.quoted(base) + ".", base);
        }
        String path = PathTree.join(reached);
        String listing = base + " " + shellWord(path);
        return loaded.symbolNotFound(selectors, message,
                "List what is under " + path + ": " + Failure.quoted(listing) + ".", listing);
    }

    // The deepest path prefix a missed selector resolves before it stops matching: a whole path whose accessor
    // missed, or the segments before the first one that matched nothing. Either selector of a pair may be the path.
    private static List<String> reached(Surface.Container container, List<String> selectors) {
        if (!container.hasPaths()) {
            return List.of();
        }
        PathTree tree = PathTree.build(container.operations());
        return selectors.stream()
                .map(selector -> switch (PathTree.resolve(tree, PathTree.splitPath(selector))) {
                    case PathTree.Resolution.Found found -> found.path();
                    case PathTree.Resolution.Missing missing -> missing.matched();
                })
                .max(Comparator.comparingInt(List::size))
                .orElse(List.of());
    }

    private static List<Entry> selectByPath(Surface.Container container, List<String> selectors) {
        Optional<PathRequest> request = pathRequest(container, selectors);
        if (request.isEmpty() || !(PathTree.resolve(PathTree.build(container.operations()), request.get().tokens())
                instanceof PathTree.Resolution.Found found)) {
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
                .filter(operation -> operation.fn().accessor().equals(accessor))
                .map(operation -> new Entry(operation.fn(), operation.segments()))
                .toList();
    }

    private static boolean isAccessor(Surface.Container container, String token) {
        if (!ACCESSOR.matcher(token).matches()) {
            return false;
        }
        return container.operations().stream().anyMatch(operation -> operation.fn().accessor().equals(token));
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
            Options options) {
        if (container.scope() == Surface.Scope.SERVICE) {
            // However the caller reached it — `type Interceptor` as well as `service Interceptor` — a service
            // type's answer says which listener it binds to, and that is settled from the source.
            loaded = loaded.withBindings();
            container = Surface.byName(Surface.of(loaded.library(), Surface.Scope.SERVICE), container.name())
                    .orElse(container);
        }
        int read = consumed(container, selectors);
        List<Entry> selected = select(container, selectors.subList(0, read));
        if (selected.isEmpty() && read > 0) {
            return Result.err(miss(loaded, scope, container, selectors.subList(0, read), options));
        }
        if (read < selectors.size()) {
            return Result.err(unread(loaded, scope, container, selectors, read, options));
        }
        String note = listenerNote(loaded, container);
        List<DiscoverResult.Documented.Entry> documented = List.of();
        if (options.filtered()) {
            Filter.Split<Entry> split = Filter.apply(options.filter(), selected,
                    entry -> Filter.surfaceOf(entry.fn()), entry -> Filter.docsOf(entry.fn()));
            selected = split.surface();
            documented = documentedRows(split.documented(), baseCommand(loaded, scope, container));
        }
        if (selected.size() == 1) {
            return signature(loaded, scope, container, selectors, selected.get(0), options, documented, note);
        }
        return listing(loaded, scope, container, selectors, selected, options, documented, note);
    }

    /**
     * Documentation-only matches when they are an answer's only list — beside one signature, or as all a
     * {@code --filter} found — paged on their own once over the ceiling, so the command that produced the answer,
     * turned a page, reaches the rest. At or under the ceiling this is page 1 whatever was asked: the answer is
     * not paged, and {@code Cli} rejects any other {@code --page} against it.
     */
    private static Result<Page> documentedPage(
            List<DiscoverResult.Documented.Entry> documented, int page, String command) {
        return Page.of(documented.size() > MAX_ENTRIES ? page : 1, documented.size(), command);
    }

    /** The documentation-only matches on {@code window}, which come after the listing's {@code offset} entries. */
    private static DiscoverResult.Documented documentedOn(
            Page window, List<DiscoverResult.Documented.Entry> documented, int offset) {
        return documented.isEmpty()
                ? DiscoverResult.Documented.NONE
                : new DiscoverResult.Documented(window.slice(documented, offset), documented.size());
    }

    private static Result<DiscoverResult> listing(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            List<Entry> selected, Options options, List<DiscoverResult.Documented.Entry> documented, String note) {
        List<Entry> callable = selected.stream()
                .filter(entry -> !(entry.fn() instanceof Fn.Constructor))
                .toList();
        long forms = callable.stream().map(entry -> entry.fn().getClass()).distinct().count();
        if (forms > 1) {
            return mixedAnswer(loaded, scope, container, selectors, callable, options, documented, note);
        }
        boolean resources = callable.isEmpty()
                ? container.hasPaths() && container.standalone().isEmpty()
                : callable.get(0).fn() instanceof Fn.Resource;
        if (resources) {
            return resourceAnswer(loaded, scope, container, selectors, callable, options, documented, note);
        }
        return methodAnswer(loaded, scope, container, selectors, callable, options, documented, note);
    }

    private static Result<DiscoverResult> signature(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            Entry entry, Options options, List<DiscoverResult.Documented.Entry> documented, String note) {
        String command = baseCommand(loaded, scope, container) + selectorArguments(container, selectors)
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
                fn instanceof Fn.Resource ? PathTree.join(entry.path()) : null,
                entry.callForm(), declaration, params, Signatures.returnType(fn), fn.isDeprecated(),
                types, omitted, closure == null ? 0 : closure.omitted().size(),
                closure == null ? null : Types.omittedNext(loaded, closure), foreign,
                documentedOn(window, documented, 0), window.paging(), window.next(command),
                loaded.warning(), note));
    }

    private static Result<DiscoverResult> mixedAnswer(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            List<Entry> callable, Options options, List<DiscoverResult.Documented.Entry> documented, String note) {
        String warning = loaded.warning();
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
                window.next(command), documentedOn(window, documented, total), warning, note));
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
            List<Entry> callable, Options options, List<DiscoverResult.Documented.Entry> documented, String note) {
        String warning = loaded.warning();
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
            List<Entry> callable, Options options, List<DiscoverResult.Documented.Entry> documented, String note) {
        String warning = loaded.warning();
        String base = baseCommand(loaded, scope, container);
        String command = base + selectorArguments(container, selectors) + filterArgument(options);
        List<DiscoverResult.ResourceList.Resource> merged = mergedResources(callable, base);

        Optional<List<String>> prefix = selectors.isEmpty()
                ? Optional.of(List.of())
                : resolvedPath(container, selectors);
        boolean groupable = !options.filtered() && prefix.isPresent()
                && literalDepth(prefix.get()) <= MAX_GROUP_DEPTH;
        if (groupable && merged.size() > MAX_ENTRIES) {
            Optional<PathTree> node = descend(PathTree.build(operationsOf(callable)), prefix.get());
            if (node.isPresent()) {
                return groupedAnswer(container, selectors, node.get(), prefix.get(), base, command, options.page(),
                        warning, note);
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
                documentedOn(window, documented, merged.size()), warning, note));
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
            String name = PathTree.join(path);
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
            accessorsByPath.computeIfAbsent(PathTree.join(entry.path()), key -> new ArrayList<>())
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

    private static Optional<List<String>> resolvedPath(Surface.Container container, List<String> selectors) {
        if (selectByPath(container, selectors).isEmpty()) {
            return Optional.empty();
        }
        return pathRequest(container, selectors).map(PathRequest::tokens);
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
        return " " + shellWord(PathTree.join(path.get())) + (accessor == null ? "" : " " + accessor);
    }

    private static String baseCommand(LoadedPackage loaded, Surface.Scope scope, Surface.Container container) {
        return bucketCommand(loaded, scope.verb()) + (container.isModule() ? "" : " " + shellWord(container.name()));
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
