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

import io.ballerina.tools.discover.Command;
import io.ballerina.tools.discover.Failure;
import io.ballerina.tools.discover.LoadedPackage;
import io.ballerina.tools.discover.Result;
import io.ballerina.tools.discover.Texts;
import io.ballerina.tools.discover.model.Bindings;
import io.ballerina.tools.discover.model.Fn;
import io.ballerina.tools.discover.model.ModuleRef;
import io.ballerina.tools.discover.model.Param;
import io.ballerina.tools.discover.model.Service;
import io.ballerina.tools.discover.model.ServiceHandlers;
import io.ballerina.tools.discover.model.TypeRef;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.Identifiers;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code client} / {@code service} / {@code class} / {@code function}, and the {@code method} and {@code resource}
 * of any container — one implementation, four scopes.
 *
 * <p>The four scopes differ ONLY in which slice of {@link Surface} they address. Everything below — how a name
 * resolves, how much is printed and which next command is offered — is one code path, because four copies of it
 * would be four places for the same rule to rot separately. {@code service} adds exactly one thing the other three
 * do not carry — the listener a container's pairings name — folded into the shared {@code note}/roster machinery
 * ({@link #listenerNote}, {@link #listenerNames}) rather than a parallel path.
 *
 * <p><b>THE NOUN SAYS WHAT COMES BACK.</b> A container noun answers with the container whole; {@code method} reads
 * only its methods and constructor, {@code resource} only its resource methods, by path and accessor.
 *
 * <p><b>A MEMBER'S CONTAINER MAY BE LEFT OUT WHEN TYPED.</b> Without a container flag, every container is searched;
 * when exactly one declares what the selectors name it answers as if it had been named, and with two or more the
 * failure lists each owner's command. When none declares all of it but exactly one declares a path typed whole, that
 * one is settled and the failure is its miss, as if it had been named. Every printed command names the container,
 * so a command printed today still runs once a later version adds a second owner.
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
        public static Result<Page> of(int requested, int total, Command command) {
            int pages = Math.max(1, (total + MAX_ENTRIES - 1) / MAX_ENTRIES);
            if (requested < 1 || requested > pages) {
                String fixed = command.paged(pages).text();
                String message = "page " + requested + " is out of range: this listing has " + total
                        + " entries on " + pages + (pages == 1 ? " page" : " pages");
                String suggestion = pages == 1
                        ? "This listing has only one page; drop --page."
                        : "Pass a page from 1 to " + pages + ".";
                return Result.err(requested < 1
                        ? new Failure.Validation(message, suggestion, fixed)
                        : new Failure.PageNotFound(message, suggestion, fixed, requested, pages));
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
        public String next(Command command) {
            return number < pages ? command.paged(number + 1).text() : null;
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

    /** How many names a resource method is asked by: its path and its accessor. */
    private static final int PATH_AND_ACCESSOR = 2;

    private static final char MEMBER_ACCESS = '.';

    /** Accessors a resource function may declare. Not a closed set — see {@link #isAccessor}. */
    private static final Pattern ACCESSOR = Pattern.compile("[a-z][a-zA-Z0-9]*");

    private static final String VOWELS = "aeiou";

    private Containers() {
    }

    /** What a command asks a container for: the container itself, one of its methods, or one of its resources. */
    public enum Ask {

        /** {@code client}, {@code service}, {@code class} or {@code function}: a roster, or one container whole. */
        CONTAINER,

        /** {@code method}: a remote or plain method, or the constructor, {@code init}. */
        METHOD,

        /** {@code resource}: a resource path, or a path and its accessor. */
        RESOURCE
    }

    /**
     * @param ask what is asked for
     * @param container the container named — after a container noun, or a member's container flag — or
     *     {@code null} when none was
     * @param selectors what follows the noun below the container: a function's or method's name, or a path and its
     *     accessor
     * @param filter the {@code --filter} keyword, or {@code null}
     * @param page {@code --page}, 1-indexed, for any listing over the ceiling
     */
    public record Options(Ask ask, String container, List<String> selectors, String filter, int page) {

        public Options {
            selectors = List.copyOf(selectors);
        }

        /** {@code client [Name]}, {@code service [Name]}, {@code class [Name]}, or {@code function [name]}. */
        public static Options container(String name, String filter, int page) {
            return new Options(Ask.CONTAINER, name, List.of(), filter, page);
        }

        public static Options function(String name, String filter, int page) {
            return new Options(Ask.CONTAINER, null, name == null ? List.of() : List.of(name), filter, page);
        }

        /** {@code method [name] [--client <C>]}. */
        public static Options method(String container, String name, String filter, int page) {
            return new Options(Ask.METHOD, container, name == null ? List.of() : List.of(name), filter, page);
        }

        /** {@code resource [path [accessor]] [--client <C>]}. */
        public static Options resource(String container, List<String> path, String filter, int page) {
            return new Options(Ask.RESOURCE, container, path, filter, page);
        }

        public boolean filtered() {
            return filter != null && !filter.isBlank();
        }
    }

    /**
     * @param scope the bucket a container noun lists, or a member's container flag; {@code null} for a member asked
     *     without one, which every container in the package is searched for
     */
    public static Result<DiscoverResult> render(LoadedPackage asked, Surface.Scope scope, Options options) {
        if (options.ask() != Ask.CONTAINER) {
            return member(asked, scope, options);
        }
        LoadedPackage loaded = scope == Surface.Scope.SERVICE ? asked.withSource() : asked;
        List<Surface.Container> containers = Surface.of(loaded.library(), scope);
        String name = options.container();
        if (containers.isEmpty()) {
            String token = name != null ? name : options.selectors().isEmpty() ? null : options.selectors().get(0);
            Optional<Failure> misplaced = token == null
                    ? Optional.empty()
                    : heldElsewhere(loaded, scope, notA(loaded, token, scope.noun()), token, options);
            return misplaced.isPresent() ? Result.err(misplaced.get()) : Result.ok(emptyBucket(loaded, scope));
        }
        if (scope == Surface.Scope.MODULE) {
            return answer(loaded, scope, containers.get(0), options.selectors(), options);
        }
        if (name == null) {
            return containers.size() == 1
                    ? answer(loaded, scope, containers.get(0), List.of(), options)
                    : roster(loaded, scope, containers, options);
        }
        Optional<Surface.Container> named = Surface.byName(containers, name);
        if (named.isPresent()) {
            return answer(loaded, scope, named.get(), List.of(), options);
        }
        return Result.err(notInBucket(loaded, scope, name, options));
    }

    // A method or a resource: of the container its flag names, else of the one container that declares it.
    private static Result<DiscoverResult> member(LoadedPackage loaded, Surface.Scope scope, Options options) {
        List<Surface.Container> candidates = scope == null ? containersOf(loaded) : Surface.of(loaded.library(), scope);
        String name = options.container();
        if (name != null) {
            Optional<Surface.Container> named = Surface.byName(candidates, name);
            return named.isPresent()
                    ? answer(loaded, named.get().scope(), named.get(), options.selectors(), options)
                    : Result.err(noSuchOwner(loaded, scope, name, options));
        }
        List<Surface.Container> owners = candidates.stream()
                .filter(container -> selects(container, options))
                .toList();
        if (owners.isEmpty() || options.selectors().isEmpty()) {
            owners = candidates.stream()
                    .filter(container -> selects(container, options) || describes(loaded, container, options))
                    .toList();
        }
        if (owners.size() == 1) {
            return answer(loaded, owners.get(0).scope(), owners.get(0), options.selectors(), options);
        }
        if (owners.size() > 1) {
            return Result.err(severalOwners(loaded, owners, options));
        }
        List<Surface.Container> pathOwners = options.ask() == Ask.RESOURCE
                ? candidates.stream().filter(container -> holdsPath(container, options.selectors())).toList()
                : List.of();
        if (pathOwners.size() == 1) {
            return answer(loaded, pathOwners.get(0).scope(), pathOwners.get(0), options.selectors(), options);
        }
        List<Surface.Container> withPaths = options.ask() == Ask.RESOURCE
                ? candidates.stream().filter(Surface.Container::hasPaths).toList()
                : List.of();
        if (withPaths.size() == 1) {
            return answer(loaded, withPaths.get(0).scope(), withPaths.get(0), options.selectors(), options);
        }
        return Result.err(noOwner(loaded, options));
    }

    /**
     * The member command {@code names} meant, when there are more of them than a method or a resource takes: the
     * container they start with as its flag; else for a resource, its first path and accessor, or the names before
     * the accessor as one path, or else the listing; else for a method, its first name.
     */
    public static Command memberMeant(LoadedPackage loaded, Ask ask, Command.Owner owner, List<String> names) {
        String noun = ask == Ask.RESOURCE ? Command.RESOURCE : Command.METHOD;
        int takes = ask == Ask.RESOURCE ? PATH_AND_ACCESSOR : 1;
        Optional<Surface.Container> first = Surface.byName(containersOf(loaded), names.get(0));
        if (first.isPresent()) {
            List<String> rest = names.subList(1, Math.min(names.size(), takes + 1));
            return owned(loaded.command(noun).with(plainWords(rest)), first.get());
        }
        Command meant = ask == Ask.METHOD
                ? loaded.command(noun, PathTree.plainSelector(names.get(0)))
                : declaredPath(loaded, owner, names);
        return owner == null ? meant : meant.ownedBy(owner);
    }

    private static Command declaredPath(LoadedPackage loaded, Command.Owner owner, List<String> names) {
        List<String> words = plainWords(names);
        List<String> joined = List.of(String.join(PathTree.SEPARATOR, words.subList(0, words.size() - 1)),
                words.get(words.size() - 1));
        return Stream.of(words.subList(0, PATH_AND_ACCESSOR), joined)
                .filter(asked -> containersOf(loaded).stream()
                        .filter(container -> owner == null || Identifiers.plain(container.name()).equals(owner.name()))
                        .anyMatch(container -> !select(container, Ask.RESOURCE, asked).isEmpty()))
                .findFirst()
                .map(asked -> loaded.command(Command.RESOURCE).with(asked))
                .orElse(loaded.command(Command.RESOURCE));
    }

    private static List<Surface.Container> containersOf(LoadedPackage loaded) {
        List<Surface.Container> all = new ArrayList<>();
        for (Surface.Scope each : Surface.Scope.values()) {
            if (each != Surface.Scope.MODULE) {
                all.addAll(Surface.of(loaded.library(), each));
            }
        }
        return all;
    }

    // Whether one of the selectors is a whole path `container` declares, whatever the others name: the container
    // is then settled, and what it does not hold under that path is its miss to report.
    private static boolean holdsPath(Surface.Container container, List<String> selectors) {
        if (!container.hasPaths()) {
            return false;
        }
        PathTree tree = PathTree.build(container.operations());
        return selectors.stream().anyMatch(selector ->
                PathTree.resolve(tree, PathTree.splitPath(selector)) instanceof PathTree.Resolution.Found);
    }

    // Whether `container` itself declares what a member command asks for: the method or the resource it names, or
    // with no name, any method or resource at all.
    private static boolean selects(Surface.Container container, Options options) {
        return options.selectors().isEmpty()
                ? !members(container, options.ask()).isEmpty()
                : !select(container, options.ask(), options.selectors()).isEmpty();
    }

    // Whether a marker service type's package describes a method the command asks for. Only read once no container
    // declares the name itself, so a lookup that no service is part of never fetches the package's handler file.
    private static boolean describes(LoadedPackage loaded, Surface.Container container, Options options) {
        List<String> selectors = options.selectors();
        return options.ask() == Ask.METHOD && container.scope() == Surface.Scope.SERVICE
                && loaded.handlersOf(container.name())
                        .map(described -> described.handlers().stream()
                                .anyMatch(handler -> selectors.isEmpty() || names(handler, selectors.get(0))))
                        .orElse(false);
    }

    private static boolean names(ServiceHandlers.Handler handler, String typed) {
        return handler.name() != null && Identifiers.plain(typed).equals(Identifiers.plain(handler.name()));
    }

    private static Failure severalOwners(LoadedPackage loaded, List<Surface.Container> owners, Options options) {
        List<String> selectors = options.selectors();
        List<String> names = owners.stream().map(Surface.Container::plainName).toList();
        String what = selectors.isEmpty()
                ? (options.ask() == Ask.RESOURCE ? "resource methods are" : "methods are")
                : named(String.join(" ", plainWords(selectors))) + " is";
        List<Failure.Match> rows = owners.stream().map(owner -> new Failure.Match(owner.plainName(),
                        askCommand(loaded, owner, options.ask(), selectors).filtered(filterOf(options)).text()))
                .toList();
        String message = what + " declared by " + Texts.and(names) + " in " + loaded.place();
        String search = searchFor(loaded, selectors);
        String suggestion = rows.size() > MAX_ENTRIES
                ? "Name the one you mean with " + Texts.or(Command.OWNER_FLAGS) + "; the first "
                        + MAX_ENTRIES + " of " + rows.size() + " are below, and " + Failure.quoted(search)
                        + " lists them all."
                : "Name the one you mean with " + Texts.or(Command.OWNER_FLAGS) + ".";
        return loaded.ambiguous(selectors, message, suggestion, Failure.OWNERS,
                rows.subList(0, Math.min(rows.size(), MAX_ENTRIES)));
    }

    private static String searchFor(LoadedPackage loaded, List<String> requested) {
        return requested.isEmpty()
                ? loaded.overview().text()
                : loaded.command(Command.SEARCH, PathTree.plainSelector(requested.get(0))).text();
    }

    // Each owner's command is the answer, so the failure lists them and quotes none; past the entry ceiling, the
    // package's search for the name lists the rest.
    private static Failure declaredBySeveral(LoadedPackage loaded, List<String> requested, String message,
            List<Failure.Match> owners) {
        String search = searchFor(loaded, requested);
        String suggestion = owners.size() > MAX_ENTRIES
                ? "Name the one you mean; the first " + MAX_ENTRIES + " of " + owners.size() + " are below, and "
                        + Failure.quoted(search) + " lists them all."
                : "Name the one you mean.";
        return loaded.ambiguous(requested, message, suggestion, Failure.OWNERS,
                owners.subList(0, Math.min(owners.size(), MAX_ENTRIES)));
    }

    // A container flag naming no container of its kind: the right flag when it names one of another kind, else the
    // listing of that kind.
    private static Failure noSuchOwner(LoadedPackage loaded, Surface.Scope scope, String name, Options options) {
        for (Surface.Container other : containersOf(loaded)) {
            if (other.scope() != scope && Identifiers.plain(other.name()).equals(Identifiers.plain(name))) {
                String command = askCommand(loaded, other, options.ask(), options.selectors())
                        .filtered(filterOf(options)).text();
                return loaded.symbolNotFound(List.of(name),
                        named(name) + " is " + article(other.scope().noun()) + ", not " + article(scope.noun())
                                + ", in " + loaded.place(),
                        "Name it with " + other.scope().commandFlag() + ".", command);
            }
        }
        return loaded.missAmong(List.of(name), notA(loaded, name, scope.noun()), scope.plural(),
                bucketCommand(loaded, scope.commandNoun()), namesIn(loaded, scope), null);
    }

    // A member no container declares, asked without a container flag: where the name is held instead, else the
    // package's search for it.
    private static Failure noOwner(LoadedPackage loaded, Options options) {
        boolean resource = options.ask() == Ask.RESOURCE;
        String what = resource ? "resource method" : "method";
        if (options.selectors().isEmpty()) {
            return noneOfItsKind(loaded, resource, what);
        }
        String token = options.selectors().get(0);
        String typed = String.join(" ", plainWords(options.selectors()));
        String message = named(typed) + " is not a " + what + " in " + loaded.place();
        if (options.ask() == Ask.METHOD) {
            Optional<Failure> misplaced = heldElsewhere(loaded, null, message, token, options)
                    .or(() -> dotted(loaded, message, token, options));
            if (misplaced.isPresent()) {
                return misplaced.get();
            }
        }
        Optional<String> keyword = searchKeyword(token);
        String plural = what + "s";
        List<String> candidates = membersOfAll(loaded, options.ask());
        if (keyword.isEmpty()) {
            String listing = loaded.command(Command.RESOURCE).text();
            return loaded.missAmong(options.selectors(), message, plural, listing, candidates, null);
        }
        String search = loaded.command(Command.SEARCH, keyword.get()).text();
        return candidates.isEmpty() || candidates.size() > MAX_ENTRIES
                ? loaded.symbolNotFound(options.selectors(), message, Search.SEARCH_FOR_IT, search)
                : loaded.missAmong(options.selectors(), message, plural, search, candidates, search);
    }

    /**
     * The word a search finds a typed name by: a path's first literal segment, since a search matches one segment,
     * and a dotted {@code Container.member}'s member. Empty for a path with no literal segment.
     */
    public static Optional<String> searchKeyword(String token) {
        String plain = PathTree.plainSelector(token);
        if (plain.contains(PathTree.SEPARATOR)) {
            return PathTree.splitPath(plain).stream()
                    .filter(segment -> !segment.isEmpty() && !segment.startsWith(PathTree.PARAMETER))
                    .findFirst();
        }
        int dot = plain.lastIndexOf(MEMBER_ACCESS);
        return Optional.of(dot >= 0 && dot < plain.length() - 1 ? plain.substring(dot + 1) : plain);
    }

    // Nothing to list of the kind asked: the containers that declare the other kind, else the package's overview.
    private static Failure noneOfItsKind(LoadedPackage loaded, boolean resource, String what) {
        Ask other = resource ? Ask.METHOD : Ask.RESOURCE;
        String message = "no container in " + loaded.place() + " declares a " + what;
        Optional<Surface.Container> owner = containersOf(loaded).stream()
                .filter(container -> !members(container, other).isEmpty())
                .findFirst();
        if (owner.isEmpty()) {
            String overview = loaded.overview().text();
            return new Failure.Validation(message, "See what it declares.", overview);
        }
        String noun = owner.get().scope().commandNoun();
        String listing = bucketCommand(loaded, noun);
        return new Failure.Validation(message + "; its " + (resource ? "methods" : "resource methods")
                + " are read under " + noun, "List the " + owner.get().scope().plural() + ".", listing);
    }

    // `Producer.send`, the way code calls it: the method on the container its flag names.
    private static Optional<Failure> dotted(LoadedPackage loaded, String message, String token, Options options) {
        int dot = token.indexOf(MEMBER_ACCESS);
        if (dot <= 0) {
            return Optional.empty();
        }
        String method = token.substring(dot + 1);
        return Surface.byName(containersOf(loaded), token.substring(0, dot))
                .filter(container -> !select(container, Ask.METHOD, List.of(method)).isEmpty())
                .map(container -> misplaced(loaded, token, message, "Name its container with "
                        + container.scope().commandFlag(), askCommand(loaded, container, Ask.METHOD,
                        List.of(method)).filtered(filterOf(options)).text()));
    }

    // A name a container noun holds nowhere may have been a container or a member of one: the package's search
    // for it, beside the noun's listing.
    private static Failure notInBucket(LoadedPackage loaded, Surface.Scope scope, String token, Options options) {
        String message = scope == Surface.Scope.MODULE
                ? notA(loaded, token, scope.noun())
                : named(token) + " is not " + article(scope.noun()) + ", nor a member of any " + scope.noun()
                        + ", in " + loaded.place();
        Optional<Failure> misplaced = heldElsewhere(loaded, scope, notA(loaded, token, scope.noun()), token, options)
                .or(() -> dotted(loaded, message, token, options));
        if (misplaced.isPresent()) {
            return misplaced.get();
        }
        String listing = bucketCommand(loaded, scope.commandNoun());
        Optional<String> keyword = searchKeyword(token);
        String search = scope == Surface.Scope.MODULE || keyword.isEmpty()
                ? null
                : loaded.command(Command.SEARCH, keyword.get()).text();
        return loaded.missAmong(List.of(token), message, scope.plural(), listing, namesIn(loaded, scope), search);
    }

    /**
     * The failure for a name {@code scope} does not hold but something else in the package does: a container or
     * function of another bucket, a type, an enum member, or — asked as a container or function — a method of one
     * or several containers.
     */
    private static Optional<Failure> heldElsewhere(
            LoadedPackage loaded, Surface.Scope scope, String message, String token, Options options) {
        String plain = Identifiers.plain(token);
        for (Surface.Scope other : Surface.Scope.values()) {
            List<Surface.Container> containers = Surface.of(loaded.library(), other);
            boolean holds = other == Surface.Scope.MODULE
                    ? containers.stream().anyMatch(module -> module.member(token).isPresent())
                    : Surface.byName(containers, token).isPresent();
            if (other != scope && holds) {
                return Optional.of(misplaced(loaded, token, message, "It is " + article(other.noun()),
                        loaded.command(other.commandNoun(), plain).filtered(filterOf(options)).text()));
            }
        }
        Optional<String> kind = Types.kindOf(loaded, token);
        if (kind.isPresent()) {
            return Optional.of(misplaced(loaded, token, message, "It is " + article(kind.get()),
                    loaded.command(Command.TYPE, plain).text()));
        }
        Optional<String> enumeration = Types.enumOf(loaded, token);
        if (enumeration.isPresent()) {
            return Optional.of(misplaced(loaded, token, message, "It is a member of the enum " + enumeration.get(),
                    loaded.command(Command.TYPE, enumeration.get()).text()));
        }
        return options.ask() == Ask.CONTAINER ? ownedElsewhere(loaded, token, options, message) : Optional.empty();
    }

    private static Optional<Failure> ownedElsewhere(LoadedPackage loaded, String token, Options options,
            String message) {
        Map<Surface.Container, Fn> owners = new LinkedHashMap<>();
        for (Surface.Container container : containersOf(loaded)) {
            container.member(token).ifPresent(fn -> owners.put(container, fn));
        }
        List<String> asked = List.of(token);
        if (owners.size() == 1) {
            Map.Entry<Surface.Container, Fn> owner = owners.entrySet().iterator().next();
            Surface.Container container = owner.getKey();
            return Optional.of(misplaced(loaded, token, message,
                    "It is " + article(memberNoun(owner.getValue())) + " of " + container.plainName(),
                    askCommand(loaded, container, Ask.METHOD, asked).filtered(filterOf(options)).text()));
        }
        if (owners.isEmpty()) {
            return Optional.empty();
        }
        List<String> names = owners.keySet().stream().map(Surface.Container::plainName).toList();
        return Optional.of(declaredBySeveral(loaded, asked,
                message + "; it is a method of " + Texts.and(names),
                owners.keySet().stream().map(owner -> new Failure.Match(owner.plainName(),
                        askCommand(loaded, owner, Ask.METHOD, asked).filtered(filterOf(options)).text()))
                        .toList()));
    }

    private static Failure misplaced(LoadedPackage loaded, String token, String message, String lead, String command) {
        return loaded.symbolNotFound(List.of(token), message, lead + ".", command);
    }

    private static String memberNoun(Fn fn) {
        return switch (fn) {
            case Fn.Remote ignored -> "remote method";
            case Fn.Normal ignored -> "method";
            case Fn.Resource ignored -> "resource method";
            case Fn.Constructor ignored -> "constructor";
        };
    }

    // Every name `scope` lists, in the listing's order: its containers, or the module's functions.
    private static List<String> namesIn(LoadedPackage loaded, Surface.Scope scope) {
        List<Surface.Container> containers = Surface.of(loaded.library(), scope);
        if (scope == Surface.Scope.MODULE) {
            return containers.stream().flatMap(module -> labels(members(module, Ask.METHOD)).stream())
                    .sorted(Texts.LOCALE_ORDER).toList();
        }
        return containers.stream().map(Surface.Container::plainName).sorted(Texts.LOCALE_ORDER).toList();
    }

    private static List<String> labels(List<Entry> entries) {
        return entries.stream().map(Entry::label).sorted(Texts.LOCALE_ORDER).toList();
    }

    // Each resource method under `prefix`, as its path then its accessor, the way a command names it.
    private static List<String> resourcesUnder(Surface.Container container, List<String> prefix) {
        return descend(PathTree.build(container.operations()), prefix)
                .map(node -> resourceRows(PathTree.operationsUnder(node)))
                .orElse(List.of());
    }

    // Resource methods as a command names them, in the listing's order: by path, each path's accessors as declared.
    private static List<String> resourceRows(List<PathTree.Operation> operations) {
        Map<String, Set<String>> accessorsByPath = new LinkedHashMap<>();
        for (PathTree.Operation operation : operations) {
            accessorsByPath.computeIfAbsent(PathTree.join(operation.segments()), path -> new LinkedHashSet<>())
                    .add(operation.fn().accessor());
        }
        return accessorsByPath.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Texts.LOCALE_ORDER))
                .flatMap(path -> path.getValue().stream().map(accessor -> path.getKey() + " " + accessor))
                .toList();
    }

    // Every member of `ask`'s kind any container declares, each once, in the listing's order.
    private static List<String> membersOfAll(LoadedPackage loaded, Ask ask) {
        List<Surface.Container> containers = containersOf(loaded);
        if (ask == Ask.RESOURCE) {
            return resourceRows(containers.stream().flatMap(container -> container.operations().stream()).toList());
        }
        return containers.stream()
                .flatMap(container -> labels(members(container, Ask.METHOD)).stream())
                .distinct()
                .sorted(Texts.LOCALE_ORDER)
                .toList();
    }

    private static String notA(LoadedPackage loaded, String token, String noun) {
        return named(token) + " is not " + article(noun) + " in " + loaded.place();
    }

    private static String named(String token) {
        return "'" + token + "'";
    }

    private static String article(String noun) {
        return (VOWELS.indexOf(noun.charAt(0)) >= 0 ? "an " : "a ") + noun;
    }

    private static String bucketCommand(LoadedPackage loaded, String bucket) {
        return loaded.command(bucket).text();
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
            return "no listener in this package accepts it directly";
        }
        String names = listenerNames(container);
        if (names == null) {
            return container.scope() == Surface.Scope.SERVICE && loaded.library().listeners().isEmpty()
                    ? "no listener in this package accepts it; it attaches to a listener another package declares"
                    : null;
        }
        Optional<Service> foreign = foreignPairing(container);
        if (foreign.isPresent()) {
            return "attach it to " + names + "; declared in " + foreign.get().declaredIn().map(ModuleRef::coordinate)
                    .orElse("") + foreignCommand(loaded, foreign.get()).map(command -> " — " + command).orElse("");
        }
        return unsettled(container)
                .map(binding -> "probably attaches to " + names + " (unverified: " + binding.reason() + ")")
                .orElse("attach it to " + names);
    }

    private static Optional<Bindings.Binding> unsettled(Surface.Container container) {
        return container.pairings().stream().map(Service::binding).filter(Bindings.Binding::isUnsettled).findFirst();
    }

    private static DiscoverResult emptyBucket(LoadedPackage loaded, Surface.Scope scope) {
        return emptyBucket(loaded, scope.commandNoun());
    }

    /** A bucket with nothing in it — {@code type} included — and every other bucket that holds something. */
    static DiscoverResult emptyBucket(LoadedPackage loaded, String bucket) {
        List<DiscoverResult.EmptyBucket.Elsewhere> elsewhere = new ArrayList<>();
        for (Surface.Scope other : Surface.Scope.values()) {
            List<Surface.Container> containers = Surface.of(loaded.library(), other);
            if (other.commandNoun().equals(bucket) || containers.isEmpty()) {
                continue;
            }
            int count = other == Surface.Scope.MODULE ? containers.get(0).functions().size() : containers.size();
            elsewhere.add(new DiscoverResult.EmptyBucket.Elsewhere(other.commandNoun(), count,
                    bucketCommand(loaded, other.commandNoun())));
        }
        int types = Types.count(loaded);
        if (types > 0 && !Command.TYPE.equals(bucket)) {
            elsewhere.add(new DiscoverResult.EmptyBucket.Elsewhere(Command.TYPE, types,
                    bucketCommand(loaded, Command.TYPE)));
        }
        return new DiscoverResult.EmptyBucket(bucket, List.copyOf(elsewhere));
    }

    private static Result<DiscoverResult> roster(
            LoadedPackage loaded, Surface.Scope scope, List<Surface.Container> containers, Options options) {
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
        Command command = loaded.command(scope.commandNoun()).filtered(filterOf(options));
        Result<Page> page = Page.of(options.page(), bindable.size() + unattachable.size(), command);
        if (!page.isOk()) {
            return page.cast();
        }
        Page window = page.value();
        List<DiscoverResult.ContainerRoster.Container> items = window.slice(bindable, 0).stream()
                .map(container -> new DiscoverResult.ContainerRoster.Container(
                        container.plainName(),
                        container.operations().size(),
                        (int) container.standalone().stream().filter(Fn.Remote.class::isInstance).count(),
                        (int) container.standalone().stream().filter(Fn.Normal.class::isInstance).count(),
                        listenerNames(container),
                        unsettled(container).map(Bindings.Binding::label).orElse(null),
                        openCommand(loaded, container, options)))
                .toList();
        List<DiscoverResult.ContainerRoster.NotAttachable> notAttachable =
                window.slice(unattachable, bindable.size()).stream()
                        .map(container -> new DiscoverResult.ContainerRoster.NotAttachable(
                                container.plainName(), openCommand(loaded, container, options)))
                        .toList();
        return Result.ok(new DiscoverResult.ContainerRoster(items, bindable.size() + unattachable.size(),
                window.paging(), window.next(command), notAttachable, unattachable.size()));
    }

    private static boolean namedByFilter(Surface.Container container, Options options) {
        return options.filtered() && Filter.matches(options.filter(), container.name());
    }

    /** Everything callable on {@code container} but its constructor, in the package's own order. */
    static List<Entry> members(Surface.Container container) {
        return entriesOf(container).stream().filter(entry -> !(entry.fn() instanceof Fn.Constructor)).toList();
    }

    // What `ask` lists of `container`: every member, its methods, or its resource methods; never the constructor.
    private static List<Entry> members(Surface.Container container, Ask ask) {
        return members(container).stream().filter(entry -> isAsked(entry, ask)).toList();
    }

    private static boolean isAsked(Entry entry, Ask ask) {
        return switch (ask) {
            case CONTAINER -> true;
            case METHOD -> !(entry.fn() instanceof Fn.Resource);
            case RESOURCE -> entry.fn() instanceof Fn.Resource;
        };
    }

    /** The command that opens one member of {@code container}: a function, a method, or a resource method. */
    static String memberCommand(LoadedPackage loaded, Surface.Container container, Entry entry) {
        return entry.fn() instanceof Fn.Resource resource
                ? resourceCommand(loaded, container, List.of(PathTree.join(entry.path()), resource.accessor()))
                        .text()
                : nameCommand(loaded, container, entry.label()).text();
    }

    /** The command that opens {@code container} from a listing of its bucket. */
    static String openCommand(LoadedPackage loaded, Surface.Container container) {
        return containerCommand(loaded, container).text();
    }

    /** The module's functions as overview rows, alphabetical, each with the command that opens it. */
    static List<DiscoverResult.Overview.Row> functionRows(LoadedPackage loaded, Surface.Container module) {
        return methodsOf(loaded, module, entriesOf(module), Fn.class).stream()
                .map(function -> new DiscoverResult.Overview.Row(function.name(), 0, 0, 0, function.command()))
                .toList();
    }

    // Another module's service type opens here too: the listener's package may describe the methods a service of it
    // defines, and its answer otherwise names the module that declares it.
    private static String openCommand(LoadedPackage loaded, Surface.Container container, Options options) {
        return containerCommand(loaded, container)
                .filtered(namedByFilter(container, options) ? null : filterOf(options)).text();
    }

    private static Command containerCommand(LoadedPackage loaded, Surface.Container container) {
        return container.isModule()
                ? loaded.command(Surface.Scope.MODULE.commandNoun())
                : loaded.command(container.scope().commandNoun(), container.plainName());
    }

    private static Command nameCommand(LoadedPackage loaded, Surface.Container container, String name) {
        return container.isModule()
                ? loaded.command(Surface.Scope.MODULE.commandNoun(), name)
                : owned(loaded.command(Command.METHOD, name), container);
    }

    private static Command resourceCommand(LoadedPackage loaded, Surface.Container container, List<String> words) {
        return owned(loaded.command(Command.RESOURCE).with(words), container);
    }

    private static Command owned(Command command, Surface.Container container) {
        return command.ownedBy(container.scope().commandNoun(), container.plainName());
    }

    /** The command that asks {@code container} for what {@code ask} names with {@code selectors}, as printed. */
    private static Command askCommand(LoadedPackage loaded, Surface.Container container, Ask ask,
            List<String> selectors) {
        List<String> words = plainWords(selectors);
        return switch (ask) {
            case CONTAINER -> container.isModule()
                    ? loaded.command(Surface.Scope.MODULE.commandNoun()).with(words)
                    : containerCommand(loaded, container);
            case METHOD -> owned(loaded.command(Command.METHOD).with(words), container);
            case RESOURCE -> resourceCommand(loaded, container, words);
        };
    }

    private static Optional<Service> foreignPairing(Surface.Container container) {
        return container.pairings().stream().filter(service -> service.declaredIn().isPresent()).findFirst();
    }

    private static Optional<String> foreignCommand(LoadedPackage loaded, Service service) {
        return loaded.targetFor(service.declaredIn().orElseThrow())
                .map(target -> Command.of(Surface.Scope.SERVICE.commandNoun(), service.name()).in(target).text());
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
                case Fn.Standalone named -> Identifiers.plain(named.name());
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

    // What `selectors` name on `container`: a resource path and its accessor, or a method or function by name.
    private static List<Entry> select(Surface.Container container, Ask ask, List<String> selectors) {
        if (selectors.isEmpty()) {
            return ask == Ask.CONTAINER ? entriesOf(container) : members(container, ask);
        }
        if (ask == Ask.RESOURCE) {
            return container.hasPaths() ? selectByPath(container, selectors) : List.of();
        }
        if (selectors.size() > 1) {
            return List.of();
        }
        String token = Identifiers.plain(selectors.get(0));
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

    /**
     * The failure for selectors that name nothing on {@code container}: its suggestion lists the deepest level the
     * lookup reached — the path prefix that resolved, or else the container.
     */
    private static Failure miss(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            Options options) {
        if (container.isModule()) {
            return notInBucket(loaded, scope, selectors.get(0), options);
        }
        boolean none = members(container, options.ask()).isEmpty();
        String base = none
                ? containerCommand(loaded, container).text()
                : askCommand(loaded, container, options.ask(), List.of()).text();
        String typed = String.join(" ", selectors);
        String what = options.ask() == Ask.METHOD ? "a method"
                : selectors.size() > 1 ? "a resource method" : "a resource path";
        String message = named(typed) + " is not " + what + " of " + container.plainName() + " in " + loaded.place();
        if (none) {
            return loaded.symbolNotFound(selectors, message, "List its members.", base);
        }
        List<String> reached = options.ask() == Ask.RESOURCE ? reached(container, selectors) : List.of();
        if (options.ask() == Ask.METHOD) {
            return loaded.missAmong(selectors, message, "methods of " + container.plainName(), base,
                    labels(members(container, Ask.METHOD)), null);
        }
        String listing = reached.isEmpty()
                ? base
                : resourceCommand(loaded, container, List.of(PathTree.join(reached))).text();
        String level = reached.isEmpty() ? "resource methods of " + container.plainName()
                : "resource methods under " + PathTree.join(reached);
        return loaded.missAmong(selectors, message, level, listing, resourcesUnder(container, reached), null);
    }

    // The deepest path prefix a missed selector resolves before it stops matching: a whole path whose accessor
    // missed, or the segments before the first one that matched nothing. Either selector of a pair may be the path.
    private static List<String> reached(Surface.Container container, List<String> selectors) {
        if (!container.hasPaths() || selectors.isEmpty()) {
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
            loaded = loaded.withSource();
            container = Surface.byName(Surface.of(loaded.library(), Surface.Scope.SERVICE), container.name())
                    .orElse(container);
        }
        if (options.ask() != Ask.RESOURCE) {
            Optional<Result<DiscoverResult>> handlers = handlerAnswer(loaded, container, selectors, options);
            if (handlers.isPresent()) {
                return handlers.get();
            }
            if (container.scope() == Surface.Scope.SERVICE && selectors.isEmpty() && !options.filtered()
                    && members(container).isEmpty()) {
                return Result.ok(serviceExamples(loaded, container));
            }
        }
        List<Entry> selected = select(container, options.ask(), selectors);
        if (selected.isEmpty() && !selectors.isEmpty()) {
            return Result.err(miss(loaded, scope, container, selectors, options));
        }
        if (selected.isEmpty() && options.ask() != Ask.CONTAINER) {
            return Result.err(nothingToList(loaded, container, options.ask()));
        }
        String note = listenerNote(loaded, container);
        List<DiscoverResult.Documented.Entry> documented = List.of();
        if (options.filtered()) {
            Filter.Split<Entry> split = Filter.apply(options.filter(), selected,
                    entry -> Filter.surfaceOf(entry.fn()), entry -> Filter.docsOf(entry.fn()));
            selected = split.surface();
            documented = documentedRows(loaded, container, split.documented());
        }
        if (selected.size() == 1) {
            return signature(loaded, scope, container, selectors, selected.get(0), options, documented, note);
        }
        return listing(loaded, scope, container, selectors, selected, options, documented, note);
    }

    // A member listing asked of a container that declares none of that kind: the other kind, else the container.
    private static Failure nothingToList(LoadedPackage loaded, Surface.Container container, Ask ask) {
        Ask other = ask == Ask.METHOD ? Ask.RESOURCE : Ask.METHOD;
        String what = ask == Ask.METHOD ? "methods" : "resource methods";
        List<Entry> others = members(container, other);
        String command = others.isEmpty()
                ? containerCommand(loaded, container).text()
                : askCommand(loaded, container, other, List.of()).text();
        String lead = others.isEmpty()
                ? "Open it"
                : "It declares " + others.size() + " " + (other == Ask.METHOD ? "methods" : "resource methods")
                        + "; list them";
        return loaded.symbolNotFound(List.of(), container.plainName() + " declares no " + what + " in "
                + loaded.place(), lead + ".", command);
    }

    private static DiscoverResult serviceExamples(LoadedPackage loaded, Surface.Container container) {
        List<String> listeners = container.pairings().stream().map(Service::listener).distinct().toList();
        List<DiscoverResult.ReadmeSections.Section> sections =
                ReadmeCalls.declaringService(loaded, container, listeners).stream()
                        .map(section -> new DiscoverResult.ReadmeSections.Section(section.number(), section.title(),
                                section.lines(), readmeCommand(loaded, section.number())))
                        .toList();
        String next = sections.isEmpty() && loaded.readme().isPresent()
                ? loaded.command(Command.README).text()
                : null;
        return new DiscoverResult.ServiceExamples(container.plainName(), container.description(), sections, next,
                listenerNote(loaded, container));
    }

    // A service type that only marks what a listener accepts has no methods of its own; its package may describe the
    // ones a service defines, and naming one of them narrows the answer to it.
    private static Optional<Result<DiscoverResult>> handlerAnswer(LoadedPackage loaded, Surface.Container container,
            List<String> selectors, Options options) {
        boolean marker = container.scope() == Surface.Scope.SERVICE && !options.filtered() && selectors.size() <= 1
                && entriesOf(container).stream().noneMatch(entry -> !(entry.fn() instanceof Fn.Constructor));
        Optional<ServiceHandlers> described = marker ? loaded.handlersOf(container.name()) : Optional.empty();
        if (described.isEmpty()) {
            return Optional.empty();
        }
        List<ServiceHandlers.Handler> handlers = described.get().handlers();
        if (!selectors.isEmpty()) {
            handlers = handlers.stream().filter(handler -> names(handler, selectors.get(0))).toList();
            if (handlers.isEmpty()) {
                String base = askCommand(loaded, container, options.ask(), List.of()).text();
                return Optional.of(Result.err(loaded.missAmong(selectors,
                        named(selectors.get(0)) + " is not a method to implement for " + container.plainName() + " in "
                                + loaded.place(),
                        "methods to implement for " + container.plainName(), base,
                        described.get().handlers().stream().map(ServiceHandlers.Handler::name)
                                .filter(Objects::nonNull).sorted(Texts.LOCALE_ORDER).toList(), null)));
            }
        }
        List<ServiceHandlers.Rule> rules = selectors.isEmpty() ? described.get().rules() : List.of();
        return Optional.of(Result.ok(new DiscoverResult.ServiceHandlerList(
                containerName(container), handlers, rules, listenerNote(loaded, container))));
    }

    /**
     * Documentation-only matches when they are an answer's only list — beside one signature, or as all a
     * {@code --filter} found — paged on their own once over the ceiling, so the command that produced the answer,
     * turned a page, reaches the rest. At or under the ceiling this is page 1 whatever was asked: the answer is
     * not paged, and {@code Cli} rejects any other {@code --page} against it.
     */
    private static Result<Page> documentedPage(
            List<DiscoverResult.Documented.Entry> documented, int page, Command command) {
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
        Command command = askCommand(loaded, container, options.ask(), selectorWords(container, selectors))
                .filtered(filterOf(options));
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
        Closure.Roots roots = Closure.rootsOf(fn, index);
        Closure.Result closure = roots.parameters().isEmpty() && roots.others().isEmpty()
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
            case Fn.Standalone standalone -> Identifiers.plain(standalone.name());
            case Fn.Constructor ignored -> Fn.Constructor.NAME;
        };
        DiscoverResult.Signature.Create create = fn instanceof Fn.Constructor
                ? null
                : Construction.of(loaded, container, nameCommand(loaded, container, Fn.Constructor.NAME).text())
                        .orElse(null);
        return Result.ok(new DiscoverResult.Signature(
                container.isModule() ? null : container.plainName(), kind, name,
                fn instanceof Fn.Resource resource ? resource.accessor() : null,
                fn instanceof Fn.Resource ? PathTree.join(entry.path()) : null,
                entry.callForm(), declaration, params, Signatures.returnType(fn), fn.isDeprecated(),
                types, omitted, foreign, create, callers(loaded, container, entry),
                documentedOn(window, documented, 0), window.paging(), window.next(command), note));
    }

    private static List<DiscoverResult.ReadmeSections.Section> callers(LoadedPackage loaded,
            Surface.Container container, Entry entry) {
        return ReadmeCalls.calling(loaded, container, entry).stream()
                .map(section -> new DiscoverResult.ReadmeSections.Section(section.number(), section.title(),
                        section.lines(), readmeCommand(loaded, section.number())))
                .toList();
    }

    private static String readmeCommand(LoadedPackage loaded, int section) {
        return loaded.command(Command.README, String.valueOf(section)).text();
    }

    private static Result<DiscoverResult> mixedAnswer(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            List<Entry> callable, Options options, List<DiscoverResult.Documented.Entry> documented, String note) {
        Command command = listingCommand(loaded, container, selectors, options);
        List<DiscoverResult.ResourceList.Resource> resources = mergedResources(loaded, container,
                callable.stream().filter(entry -> entry.fn() instanceof Fn.Resource).toList());
        List<DiscoverResult.Method> remote = methodsOf(loaded, container, callable, Fn.Remote.class);
        List<DiscoverResult.Method> normal = methodsOf(loaded, container, callable, Fn.Normal.class);
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
                new DiscoverResult.MixedListing.Counts(
                        resources.stream().mapToInt(resource -> resource.accessors().size()).sum(),
                        remote.size(), normal.size()),
                shownResources.size() + shownRemote.size() + shownNormal.size(), total, window.paging(),
                window.next(command), documentedOn(window, documented, total), note));
    }

    private static List<DiscoverResult.Method> methodsOf(LoadedPackage loaded, Surface.Container container,
            List<Entry> entries, Class<? extends Fn> form) {
        return entries.stream()
                .filter(entry -> form.isInstance(entry.fn()))
                .map(Entry::label)
                .sorted(Texts.LOCALE_ORDER)
                .map(name -> new DiscoverResult.Method(name, nameCommand(loaded, container, name).text()))
                .toList();
    }

    private static Command listingCommand(LoadedPackage loaded, Surface.Container container, List<String> selectors,
            Options options) {
        return askCommand(loaded, container, options.ask(), selectorWords(container, selectors))
                .filtered(filterOf(options));
    }

    private static Result<DiscoverResult> methodAnswer(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            List<Entry> callable, Options options, List<DiscoverResult.Documented.Entry> documented, String note) {
        Command command = listingCommand(loaded, container, selectors, options);
        List<DiscoverResult.Method> methods = methodsOf(loaded, container, callable, Fn.class);
        Result<Page> page = Page.of(options.page(), methods.size() + documented.size(), command);
        if (!page.isOk()) {
            return page.cast();
        }
        Page window = page.value();
        List<DiscoverResult.Method> shown = window.slice(methods, 0);
        boolean remote = !methods.isEmpty() && callable.stream().allMatch(entry -> entry.fn() instanceof Fn.Remote);
        return Result.ok(new DiscoverResult.MethodList(containerName(container), shown, remote, shown.size(),
                methods.size(), window.paging(), window.next(command),
                documentedOn(window, documented, methods.size()), note));
    }

    private static Result<DiscoverResult> resourceAnswer(
            LoadedPackage loaded, Surface.Scope scope, Surface.Container container, List<String> selectors,
            List<Entry> callable, Options options, List<DiscoverResult.Documented.Entry> documented, String note) {
        Command command = listingCommand(loaded, container, selectors, options);
        List<DiscoverResult.ResourceList.Resource> merged = mergedResources(loaded, container, callable);

        Optional<List<String>> prefix = selectors.isEmpty()
                ? Optional.of(List.of())
                : resolvedPath(container, selectors);
        boolean groupable = !options.filtered() && prefix.isPresent()
                && literalDepth(prefix.get()) <= MAX_GROUP_DEPTH;
        if (groupable && merged.size() > MAX_ENTRIES) {
            Optional<PathTree> node = descend(PathTree.build(operationsOf(callable)), prefix.get());
            if (node.isPresent()) {
                return groupedAnswer(loaded, container, selectors, node.get(), prefix.get(), command,
                        options.page(), note);
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
                documentedOn(window, documented, merged.size()), note));
    }

    private static Result<DiscoverResult> groupedAnswer(LoadedPackage loaded,
            Surface.Container container, List<String> selectors, PathTree node, List<String> prefix,
            Command command, int requested, String note) {
        String accessor = pathRequest(container, selectors).map(PathRequest::accessor).orElse(null);
        List<PathTree.Operation> terminal = new ArrayList<>();
        Map<List<String>, PathTree> literalChildren = new LinkedHashMap<>();
        collectNextLiteralChildren(node, prefix, terminal, literalChildren);

        List<DiscoverResult.ResourceList.Resource> here = mergedResources(loaded, container,
                terminal.stream().map(operation -> new Entry(operation.fn(), operation.segments())).toList());
        List<DiscoverResult.PathGroups.Group> groups = new ArrayList<>();
        literalChildren.forEach((path, child) -> {
            String name = PathTree.join(path);
            groups.add(new DiscoverResult.PathGroups.Group(
                    name, child.total(),
                    resourceCommand(loaded, container, groupArguments(container, name, accessor, child.total()))
                            .text()));
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
                total, window.paging(), window.next(command), note));
    }

    // A path that itself declares the accessor would answer with one signature and silently drop the rest of
    // the group, so such a group is opened unnarrowed.
    private static List<String> groupArguments(Surface.Container container, String name, String accessor,
            int count) {
        if (accessor != null && select(container, Ask.RESOURCE, List.of(name, accessor)).size() == count) {
            return List.of(name, accessor);
        }
        return List.of(name);
    }

    private static List<DiscoverResult.ResourceList.Resource> mergedResources(LoadedPackage loaded,
            Surface.Container container, List<Entry> entries) {
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
            accessors.forEach(accessor -> commands.put(accessor,
                    resourceCommand(loaded, container, List.of(path, accessor)).text()));
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
        return (int) path.stream().filter(segment -> !segment.startsWith(PathTree.PARAMETER)).count();
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

    // The selectors as a printed command spells them: a path resolved to its own spelling, the accessor after it.
    private static List<String> selectorWords(Surface.Container container, List<String> selectors) {
        Optional<List<String>> path = selectors.isEmpty() ? Optional.empty() : resolvedPath(container, selectors);
        if (path.isEmpty()) {
            return selectors;
        }
        String accessor = pathRequest(container, selectors).map(PathRequest::accessor).orElse(null);
        String joined = PathTree.join(path.get());
        return accessor == null ? List.of(joined) : List.of(joined, accessor);
    }

    private static List<DiscoverResult.Documented.Entry> documentedRows(LoadedPackage loaded,
            Surface.Container container, List<Entry> entries) {
        List<DiscoverResult.Documented.Entry> rows = new ArrayList<>(mergedResources(loaded, container,
                entries.stream().filter(entry -> entry.fn() instanceof Fn.Resource).toList()));
        entries.stream()
                .filter(entry -> !(entry.fn() instanceof Fn.Resource))
                .map(entry -> new DiscoverResult.Method(entry.label(),
                        nameCommand(loaded, container, entry.label()).text()))
                .forEach(rows::add);
        rows.sort(Comparator.comparing(DiscoverResult.Documented.Entry::key, Texts.LOCALE_ORDER));
        return List.copyOf(rows);
    }

    private static String containerName(Surface.Container container) {
        return container.isModule() ? null : container.plainName();
    }

    private static String filterOf(Options options) {
        return options.filtered() ? options.filter() : null;
    }

    // Typed words echoed into a printed command, spelled as this tool prints them: `'public` as `public`.
    private static List<String> plainWords(List<String> words) {
        return words.stream().map(PathTree::plainSelector).toList();
    }
}
