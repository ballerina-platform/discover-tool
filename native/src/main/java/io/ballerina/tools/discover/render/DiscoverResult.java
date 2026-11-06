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
import io.ballerina.tools.discover.model.ServiceHandlers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One structured shape {@code bal discover} can answer with — the "one source, multiple renderers" the RFC asks
 * for. A view builds one of these; {@link TextRenderer} and {@link JsonRenderer} are the only two things that turn
 * it into bytes, so the two renderers can never drift into describing different data.
 *
 * <p>Field names follow the RFC's own worked examples wherever one exists ({@code buckets}, {@code groups}/
 * {@code name}/{@code count}, {@code resources}/{@code path}/{@code accessors}/{@code commands}, {@code methods},
 * {@code shown}/{@code total}/{@code next}/{@code command}); shapes the RFC shows no example for name their own.
 *
 * @since 0.1.0
 */
public sealed interface DiscoverResult {

    /**
     * Which page of a paginated answer this is, or {@code null} for an answer that is not paged — one that fits on
     * one page, or a shape that never pages at all. A {@code --page} other than 1 is only valid against an answer
     * that reports one.
     */
    default Paging paging() {
        return null;
    }

    /**
     * Which page of a paginated listing this response is.
     *
     * @param page the page served, 1-indexed
     * @param pages how many pages the whole listing spans
     * @param remaining how many entries come after this page
     */
    record Paging(int page, int pages, int remaining) { }

    /**
     * One method or module-level function in a listing.
     *
     * @param name its name, as declared
     * @param command the command that opens its signature
     */
    record Method(String name, String command) implements Documented.Entry {

        @Override
        public String key() {
            return name;
        }
    }

    /**
     * Entries a {@code --filter} matched only in their documentation, not in any name, path, parameter or type —
     * under the same entry ceiling as every listing, since a common word can match most of a package. Paged with
     * {@code --page} like any listing: after the listing's own entries when the answer is a listing, on their own
     * otherwise.
     *
     * @param entries the entries on this page, in the listings' order, each shaped as its listing row is
     * @param total how many matched that way, every page included
     */
    record Documented(List<Entry> entries, int total) {

        public static final Documented NONE = new Documented(List.of(), 0);

        /** A method, function or type by name, or one resource path with every accessor that matched. */
        public sealed interface Entry permits Method, ResourceList.Resource {

            /** What a listing sorts it by: its name, or its path. */
            String key();
        }

        /** Each entry's {@link Entry#key}: a name, or a resource path. */
        public List<String> names() {
            return entries.stream().map(Entry::key).toList();
        }
    }

    /**
     * The first answer about a package (or a targeted module): its clients, service types, classes and functions
     * with their member counts, one line for its types and one for its readme, and its submodules, each with the
     * command that opens it — {@code bal discover package <org>/<name>}.
     *
     * @param sections the callable buckets that hold something, in bucket order
     * @param types how many declarations of each kind the {@code type} bucket holds beyond the containers the
     *     sections list, or {@code null} when it holds none
     * @param readme the readme's section count, or {@code null} when the module publishes no readme
     * @param submodules every OTHER module this package publishes, regardless of which one the sections
     *     describe — empty for a package that publishes only its default module; never cut
     * @param members the package's one client's own listing, page 1 as naming it under {@code client} answers it,
     *     when the package has exactly one client; else {@code null}
     */
    record Overview(List<Section> sections, TypeCounts types, ReadmeSummary readme, List<Submodule> submodules,
            DiscoverResult members) implements DiscoverResult {

        /**
         * One callable bucket's first rows.
         *
         * @param bucket the bucket's name
         * @param rows the containers (or functions) shown, alphabetical, up to the section's share of the ceiling
         * @param total how many the bucket holds
         * @param command the command that lists them all
         */
        public record Section(String bucket, List<Row> rows, int total, String command) { }

        /**
         * @param name the container's or function's name
         * @param resources how many resource methods a container declares
         * @param remote how many remote methods it declares
         * @param normal how many plain methods it declares
         * @param command the command that opens it
         */
        public record Row(String name, int resources, int remote, int normal, String command) { }

        /**
         * @param counts how many declarations of each kind, every kind with at least one
         * @param command the command that lists them
         */
        public record TypeCounts(Map<TypeRoster.Kind, Integer> counts, String command) { }

        /**
         * @param sections how many sections the readme has
         * @param command the command that reads it
         */
        public record ReadmeSummary(int sections, String command) { }

        /**
         * @param name the bare name {@code --module} itself takes
         * @param summary the submodule's own one-line summary, or empty when it publishes none
         * @param command the command that opens it
         */
        public record Submodule(String name, String summary, String command) { }
    }

    /**
     * Several containers in one bucket (several clients, several classes) — the roster a caller chooses from
     * before naming one.
     *
     * @param containers the containers on this page, up to the ceiling
     * @param total how many the bucket (or the {@code --filter}) selects — in {@code service}, the
     *     not-attachable types included
     * @param paging which page this is, or {@code null} when the whole roster fit on one
     * @param next the ready-to-run command that turns the page, or {@code null} when nothing was cut off
     * @param notAttachable the {@code service} bucket's service types no listener of the package accepts — still
     *     addressable by name, listed apart so they are never read as bindable — on this page: they follow the
     *     bindable containers in one paged listing, so the two together stay under the ceiling
     * @param notAttachableTotal how many such types the bucket (or the {@code --filter}) selects
     */
    record ContainerRoster(List<Container> containers, int total, Paging paging, String next,
            List<NotAttachable> notAttachable, int notAttachableTotal) implements DiscoverResult {

        /**
         * A service type no listener accepts — an interceptor, or a type a service returns rather than attaches.
         *
         * @param name the type's own name
         * @param command the command that opens it
         */
        public record NotAttachable(String name, String command) { }

        /**
         * @param name the container's own name
         * @param resources how many distinct resource paths it declares — several accessors on one path count once
         * @param remote how many remote methods it declares
         * @param normal how many plain (non-remote) methods it declares
         * @param listener the listener(s) this service type binds to, joined, or {@code null} outside the
         *     {@code service} bucket
         * @param unconfirmed why that pairing is not confirmed, or {@code null} when it is
         * @param command the command that opens it
         */
        public record Container(String name, int resources, int remote, int normal, String listener,
                String unconfirmed, String command) { }
    }

    /**
     * Resource paths grouped by their next segment, because there are too many to list flat — the RFC's
     * {@code repos (275), orgs (124), ...} shape.
     *
     * @param container the container these paths belong to
     * @param resources the operations on this page that end at this level rather than under a further group, one
     *     entry per path — listed beside the groups because no group name could reach them without looping back
     * @param groups the groups on this page, busiest first; {@code resources} then {@code groups} are one paged
     *     sequence sharing the ceiling
     * @param counts how many resource paths and groups the whole level holds, every page included
     * @param total how many entries (resources plus groups) exist at this level
     * @param paging which page this is, or {@code null} when the whole level fit on one
     * @param next the ready-to-run command that turns the page, or {@code null} when nothing was cut off
     * @param note the listener a service type binds to, or that it is attachable to none — {@code null} outside the
     *     {@code service} bucket
     */
    record PathGroups(
            String container, List<ResourceList.Resource> resources, List<Group> groups, Counts counts, int total,
            Paging paging, String next, String note) implements DiscoverResult {

        /**
         * @param resources resource paths ending at this level
         * @param groups groups at this level
         */
        public record Counts(int resources, int groups) { }

        /**
         * @param name the group's path prefix, {@code /}-joined from the top of the bucket
         * @param count selected operations under this group
         * @param command the command that opens it, carrying the accessor the listing was narrowed by — a group is
         *     never ambiguous, so this is never {@code null}
         */
        public record Group(String name, int count, String command) { }
    }

    /**
     * Resource paths, flat: one entry per path with the accessors it answers to — the terminal answer once a
     * group (or the whole bucket) fits under the ceiling, or once a selection cannot be grouped and pages instead.
     *
     * @param container the container these paths belong to
     * @param resources the paths shown, up to the ceiling
     * @param shown how many are in this response
     * @param total how many exist at this level
     * @param paging which page this is, or {@code null} when the whole listing fit on one
     * @param next the ready-to-run command that turns the page, or {@code null} when nothing was cut off
     * @param documented entries a {@code --filter} matched only in their documentation, paged after the paths
     * @param note the listener a service type binds to, or that it is attachable to none — {@code null} outside the
     *     {@code service} bucket
     */
    record ResourceList(
            String container, List<Resource> resources, int shown, int total, Paging paging, String next,
            Documented documented, String note) implements DiscoverResult {

        /**
         * @param path the resource's path, {@code :name}-spelled for parameters, {@code .} for the root
         * @param accessors every accessor this path answers to
         * @param commands the exact next-step command per accessor, in {@code accessors} order — one shape whether a
         *     path answers to one accessor or several, and every accessor gets its own, so none is preferred
         */
        public record Resource(String path, List<String> accessors, Map<String, String> commands)
                implements Documented.Entry {

            public Resource {
                accessors = List.copyOf(accessors);
                commands = Collections.unmodifiableMap(new LinkedHashMap<>(commands));
                if (accessors.isEmpty() || !List.copyOf(commands.keySet()).equals(accessors)) {
                    throw new IllegalArgumentException("one command per accessor, in order: " + path);
                }
            }

            @Override
            public String key() {
                return path;
            }
        }
    }

    /**
     * Methods of one call form, flat — the RFC's {@code Methods: get, post, ...} shape, paginated with
     * {@code --page} once a container has too many to show at once. A container declaring both remote and normal
     * methods is a {@link MixedListing} instead, so the call form is never lost.
     *
     * @param container the container that declares them, or {@code null} for module-level functions
     * @param methods the methods shown, alphabetical, up to the ceiling
     * @param remote whether they are all remote methods, which the count then names
     * @param shown how many are in this response
     * @param total how many the container declares
     * @param paging which page this is, or {@code null} when the whole listing fit on one
     * @param next the ready-to-run command that turns the page, or {@code null} when nothing was cut off
     * @param documented entries a {@code --filter} matched only in their documentation, paged after the methods
     * @param note the listener a service type binds to, or that it is attachable to none — {@code null} outside the
     *     {@code service} bucket
     */
    record MethodList(
            String container, List<Method> methods, boolean remote, int shown, int total, Paging paging,
            String next, Documented documented, String note) implements DiscoverResult { }

    /**
     * What a service type's package says a service of it must or may define: the methods to implement, and the
     * constraints across them. Shown instead of "no methods" for a service type that is only a marker, so the caller
     * learns {@code onConsumerRecord} from the tool rather than from a search.
     *
     * @param container the service type
     * @param handlers the methods, in the package's own order
     * @param rules the constraints that range over more than one of them
     * @param note the listener the service type binds to, or that it is attachable to none
     */
    record ServiceHandlerList(
            String container, List<ServiceHandlers.Handler> handlers, List<ServiceHandlers.Rule> rules, String note)
            implements DiscoverResult {

        public ServiceHandlerList {
            handlers = List.copyOf(handlers);
            rules = List.copyOf(rules);
        }
    }

    /**
     * A service type that declares no methods and whose package describes none: a service of it declares its own, so
     * the answer is the type's own documentation and the readme sections whose code declares such a service — never
     * a bare "no methods".
     *
     * @param container the service type
     * @param description its documentation, verbatim, or empty
     * @param sections the readme sections whose code declares a service of it
     * @param next where to look when no section does: the readme, or {@code null} when the module publishes none
     * @param note the listener the service type binds to, or that it is attachable to none, or {@code null}
     */
    record ServiceExamples(String container, String description, List<ReadmeSections.Section> sections, String next,
            String note) implements DiscoverResult { }

    /**
     * The {@code readme} bucket's own answer: the whole readme verbatim, or — when a section selector resolved to
     * exactly one section — that section alone. No entry ceiling applies here; unlike every other listing this
     * bucket answers, the RFC's own text for it is "returns that module's README, verbatim", with no size limit
     * of any kind.
     *
     * @param markdown the readme, or the one resolved section, verbatim
     * @param lines how many lines {@code markdown} holds
     * @param section the section's 1-based number, or {@code null} for the whole readme
     * @param of how many sections the readme carries, or {@code null} for the whole readme
     * @param title the section's own heading, or {@code null} for the whole readme
     */
    record Readme(String markdown, int lines, Integer section, Integer of, String title)
            implements DiscoverResult {

        public Readme(String markdown, int lines) {
            this(markdown, lines, null, null, null);
        }
    }

    /**
     * A roster of readme sections to choose one from: those matching a selector or {@code --filter}, up to the
     * ceiling every other listing obeys, or — for a readme too large to print whole — every section it has.
     *
     * @param sections the sections shown, up to the ceiling
     * @param total how many sections actually match
     * @param paging which page this is, or {@code null} when every match fit on one
     * @param next the ready-to-run command that narrows further, or {@code null} when nothing was cut off
     * @param matching whether the sections are the ones that matched something, rather than all the readme has
     */
    record ReadmeSections(
            List<Section> sections, int total, Paging paging, String next, boolean matching)
            implements DiscoverResult {

        /**
         * @param number the 1-based address a caller types
         * @param title the heading it sits under
         * @param lines how many lines the section holds
         * @param command the command that opens it
         */
        public record Section(int number, String title, int lines, String command) { }
    }

    /**
     * Exactly one callable — the terminal answer of a drill-down, e.g. {@code client "gists/'public" get}.
     *
     * @param container the container that declares it, or {@code null} for a module-level function
     * @param kind {@code resource}, {@code remote}, {@code normal}, {@code constructor} or {@code function} (a
     *     module-level one)
     * @param name the method's name ({@code init} for a constructor), or {@code null} for a resource
     * @param accessor a resource's accessor, or {@code null} otherwise
     * @param path a resource's path, {@code :name}-spelled for parameters, or {@code null} otherwise
     * @param form how it is called: {@code ->}, {@code .}, or {@code new} for a constructor
     * @param declaration the Ballerina declaration with its doc comment, quoted verbatim from the shared renderer
     * @param params its parameters, in declaration order — a resource's path parameters live in {@code path}
     * @param returns its return type, or {@code null} when it returns nothing
     * @param deprecated whether calling it is discouraged
     * @param types the declarations its signature names, one level deep
     * @param omitted every declaration it names that {@code types} does not print, each with its command
     * @param foreign the declarations from other packages that {@code types} name
     * @param create how to construct the container a method is called on, or {@code null} for a function, a
     *     constructor, a service type, or a container with no constructor
     * @param callers the readme sections whose code calls it, or empty
     * @param documented other entries a {@code --filter} matched only in their documentation
     * @param paging which page of {@code documented} this is, or {@code null} when it fit on one
     * @param next the ready-to-run command that turns {@code documented}'s page, or {@code null} on the last
     * @param note the listener a service type's method binds through, or {@code null}
     */
    record Signature(
            String container, String kind, String name, String accessor, String path, String form,
            String declaration, List<Parameter> params, String returns, boolean deprecated,
            List<Type> types, List<Method> omitted, List<Foreign> foreign, Create create,
            List<ReadmeSections.Section> callers, Documented documented, Paging paging,
            String next, String note) implements DiscoverResult {

        /**
         * One line of Ballerina that constructs the container, and its constructor's parameters.
         *
         * @param code the line, e.g. {@code kafka:Producer producer = check new (bootstrapServers, config);}
         * @param init the constructor's parameters as declared, e.g.
         *     {@code init(string|string[] bootstrapServers, *kafka:ProducerConfiguration config)}, or {@code null} for
         *     a class that declares no constructor
         * @param command the command that opens the constructor's own signature, or {@code null} when it has none
         */
        public record Create(String code, String init, String command) { }

        /**
         * @param name the parameter's name
         * @param type its type, qualified with the module prefix it needs
         * @param defaultValue its default expression, or {@code null} when it has none
         * @param kind {@code inclusion} or {@code rest} for those forms, {@code null} for a plain parameter
         * @param description its own documentation, or empty
         */
        public record Parameter(String name, String type, String defaultValue, String kind, String description) { }

        /**
         * @param name the declared name
         * @param declaration the declaration, verbatim
         */
        public record Type(String name, String declaration) { }
    }

    /**
     * A container answering in more than one call form — {@code ->path.accessor()}, {@code ->name()},
     * {@code .name()} — the {@link ResourceList} and {@link MethodList} shapes side by side, split by call form,
     * paginated with {@code --page} as one sequence (resources, then remote, then normal) once there are too many
     * to show at once.
     *
     * @param container the container that declares them
     * @param resources the resource paths on this page
     * @param remote the remote methods on this page, alphabetical
     * @param normal the plain methods on this page, alphabetical
     * @param counts how many of each form the whole listing holds, every page included — a page can hold
     *     nothing of a form the container still declares
     * @param shown how many entries across all three are in this response
     * @param total how many exist across all three
     * @param paging which page this is, or {@code null} when the whole listing fit on one
     * @param next the ready-to-run command that turns the page, or {@code null} when nothing was cut off
     * @param documented entries a {@code --filter} matched only in their documentation, paged as the sequence's
     *     last section, after the normal methods
     * @param note the same joined advisory {@link ResourceList#note} carries, or {@code null}
     */
    record MixedListing(
            String container, List<ResourceList.Resource> resources, List<Method> remote, List<Method> normal,
            Counts counts, int shown, int total, Paging paging, String next, Documented documented, String note)
            implements DiscoverResult {

        /**
         * @param resources resource methods in the whole listing
         * @param remote remote methods in the whole listing
         * @param normal methods (called with {@code .}) in the whole listing
         */
        public record Counts(int resources, int remote, int normal) { }
    }

    /**
     * One declaration from another package that a declaration names.
     *
     * @param name the declaration's name
     * @param module the module that declares it, {@code org/module}
     * @param version the version the answer was generated against, or {@code null} when Central published none
     * @param command the command that opens it in its own package, or {@code null} when that package is not known
     */
    record Foreign(String name, String module, String version, String command) { }

    /**
     * The {@code type} bucket's roster: every type the package declares, grouped by kind, paged as one sequence in
     * section order.
     *
     * @param sections the kinds with an entry on this page, in {@link Kind} order
     * @param counts how many of each kind the whole listing holds, every page included
     * @param shown how many entries across all sections are in this response
     * @param total how many exist across all sections
     * @param paging which page this is, or {@code null} when the whole listing fit on one
     * @param next the ready-to-run command that turns the page, or {@code null} when nothing was cut off
     * @param documented entries a {@code --filter} matched only in their documentation, paged as the last section
     */
    record TypeRoster(
            List<Section> sections, Map<Kind, Integer> counts, int shown, int total, Paging paging, String next,
            Documented documented) implements DiscoverResult {

        /**
         * A section of the roster, in the order the roster lists them: its JSON key, then what one of its entries
         * is called and what several are.
         */
        public enum Kind {
            RECORDS("records", "record", "records"),
            ENUMS("enums", "enum", "enums"),
            ERRORS("errors", "error", "errors"),
            ALIASES("aliases", "alias", "aliases"),
            CONSTANTS("constants", "constant", "constants"),
            VARIABLES("variables", "variable", "variables"),
            ANNOTATIONS("annotations", "annotation", "annotations"),
            LISTENERS("listeners", "listener", "listeners"),
            CLASSES("classes", "class", "classes"),
            CLIENTS("clients", "client", "clients"),
            SERVICE_TYPES("serviceTypes", "service type", "service types");

            private final String key;
            private final String noun;
            private final String plural;

            Kind(String key, String noun, String plural) {
                this.key = key;
                this.noun = noun;
                this.plural = plural;
            }

            public String key() {
                return key;
            }

            public String noun() {
                return noun;
            }

            public String plural() {
                return plural;
            }
        }

        /**
         * @param kind the kind of every entry in it
         * @param entries the declarations of that kind on this page, alphabetical
         */
        public record Section(Kind kind, List<Method> entries) { }
    }

    /**
     * One declaration of the {@code type} bucket, whole, with the declarations it names.
     *
     * @param name the declared name
     * @param kind {@code record}, {@code enum}, {@code error}, {@code alias}, {@code constant}, {@code variable},
     *     {@code annotation} or {@code listener}
     * @param declaration the declaration, verbatim
     * @param types the declarations it names, one level deep
     * @param omitted every declaration it names that {@code types} does not print, each with the command that
     *     opens it
     * @param foreign the declarations it names that another package declares
     * @param note the enum an enum member's answer shows, or {@code null}
     */
    record TypeDeclaration(
            String name, String kind, String declaration, List<Signature.Type> types, List<Method> omitted,
            List<Foreign> foreign, String note)
            implements DiscoverResult { }

    /**
     * Every symbol and readme section of one package whose name holds every keyword, grouped by kind and paged as
     * one sequence in kind order.
     *
     * @param keywords the keywords, as typed
     * @param groups the kinds with a match on this page, in {@link Kind} order
     * @param counts how many matched of each kind, every page included
     * @param shown how many matches are in this response
     * @param total how many matched across all kinds
     * @param paging which page this is, or {@code null} when every match fit on one
     * @param next the ready-to-run command that turns the page, or {@code null} when nothing was cut off
     * @param overview the command that opens the package's overview, for an answer with no match
     */
    record Search(List<String> keywords, List<Group> groups, Map<Kind, Integer> counts, int shown, int total,
            Paging paging, String next, String overview) implements DiscoverResult {

        /** What a match is, in the order an answer lists them: its JSON key, then its noun, one and several. */
        public enum Kind {
            CLIENTS("clients", "client", "clients"),
            SERVICE_TYPES("serviceTypes", "service type", "service types"),
            CLASSES("classes", "class", "classes"),
            LISTENERS("listeners", "listener", "listeners"),
            FUNCTIONS("functions", "function", "functions"),
            REMOTE("remote", "remote method", "remote methods"),
            RESOURCES("resources", "resource method", "resource methods"),
            METHODS("methods", "method", "methods"),
            TYPES("types", "type", "types"),
            README("readme", "readme section", "readme sections"),
            SUBMODULES("submodules", "submodule", "submodules");

            private final String key;
            private final String noun;
            private final String plural;

            Kind(String key, String noun, String plural) {
                this.key = key;
                this.noun = noun;
                this.plural = plural;
            }

            public String key() {
                return key;
            }

            public String noun() {
                return noun;
            }

            public String plural() {
                return plural;
            }
        }

        /**
         * @param kind what every hit in it is
         * @param hits the matches of that kind on this page
         */
        public record Group(Kind kind, List<Hit> hits) { }

        /**
         * One match.
         *
         * @param name its name, a resource's path, or a readme section's title
         * @param container the container that declares a method, or {@code null}
         * @param detail a resource's accessor or a type's kind, or {@code null}
         * @param number a readme section's number, or {@code null}
         * @param command the command that opens it
         */
        public record Hit(String name, String container, String detail, Integer number, String command) {

            public Hit(String name, String container, String detail, String command) {
                this(name, container, detail, null, command);
            }
        }
    }

    /**
     * What Ballerina Central's own search ranks first for some keywords: packages, and symbols across every package,
     * each with the command that opens it. The ranking is Central's; this tool only pages it.
     *
     * @param keywords the keywords, as typed
     * @param packages the packages on this page, in Central's order
     * @param packagesTotal how many packages Central matched
     * @param symbols the symbols on this page, in Central's order
     * @param symbolsTotal how many symbols Central matched
     * @param paging which page this is, or {@code null} when everything fit on one
     * @param next the ready-to-run command that turns the page, or {@code null} on the last
     */
    record CentralSearch(List<String> keywords, List<PackageHit> packages, int packagesTotal, List<SymbolHit> symbols,
            int symbolsTotal, Paging paging, String next) implements DiscoverResult {

        /**
         * @param qualified {@code org/name}
         * @param version the version Central matched
         * @param summary the package's own summary, its first sentence, or empty
         * @param command the command that opens its overview
         */
        public record PackageHit(String qualified, String version, String summary, Command command) {

            /** What follows {@code bal discover} in {@link #command()}. */
            public String arguments() {
                return command.arguments();
            }
        }

        /**
         * @param qualified {@code org/name} of the package that declares it
         * @param version the version Central matched
         * @param module the submodule that declares it, or {@code null} for the package's default module
         * @param kind what it is, in words: {@code remote method}, {@code record}
         * @param container the client, class or service type that declares a method, or {@code null}
         * @param name its name, or {@code null} for a resource method
         * @param path a resource method's path, as this tool prints it, or {@code null}
         * @param accessor a resource method's accessor, or {@code null}
         * @param command the command that opens it
         */
        public record SymbolHit(String qualified, String version, String module, String kind, String container,
                String name, String path, String accessor, Command command) {

            /** What follows {@code bal discover} in {@link #command()}. */
            public String arguments() {
                return command.arguments();
            }
        }
    }

    /**
     * A bucket this package declares nothing in, and the buckets that do hold its callable surface.
     *
     * @param bucket the bucket asked for
     * @param elsewhere every other callable bucket with something in it
     */
    record EmptyBucket(String bucket, List<Elsewhere> elsewhere) implements DiscoverResult {

        /**
         * @param bucket the bucket's name
         * @param count containers in it — functions, for {@code function}
         * @param command the command that opens it
         */
        public record Elsewhere(String bucket, int count, String command) { }
    }
}
