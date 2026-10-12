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
     * A package's (or a targeted module's) own top-level buckets — the answer to a bare
     * {@code bal discover <org/name>} with no bucket and no selector.
     *
     * @param buckets the buckets this package actually declares, in a fixed order, each with the command that opens it
     * @param submodules every OTHER module this package publishes, regardless of which one {@code buckets} itself
     *     describes — empty for a package that publishes only its default module
     * @param warning why the loaded version cannot be trusted, or {@code null} when it was confirmed against the
     *     registry — see {@code Loader.unverifiedWarning}
     */
    record BucketList(List<Bucket> buckets, List<Submodule> submodules, String warning) implements DiscoverResult {

        /**
         * @param name the bucket's name
         * @param command the command that opens it
         */
        public record Bucket(String name, String command) { }

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
     * @param warning why the loaded version cannot be trusted, or {@code null} when it was confirmed against the
     *     registry — see {@code Loader.unverifiedWarning}
     * @param notAttachable the {@code service} bucket's service types no listener of the package accepts — still
     *     addressable by name, listed apart so they are never read as bindable — on this page: they follow the
     *     bindable containers in one paged listing, so the two together stay under the ceiling
     * @param notAttachableTotal how many such types the bucket (or the {@code --filter}) selects
     */
    record ContainerRoster(List<Container> containers, int total, Paging paging, String next, String warning,
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
     * @param warning why the loaded version cannot be trusted, or {@code null} when it was confirmed against the
     *     registry — see {@code Loader.unverifiedWarning}
     * @param note the listener a service type binds to, or that it is attachable to none — {@code null} outside the
     *     {@code service} bucket
     */
    record PathGroups(
            String container, List<ResourceList.Resource> resources, List<Group> groups, Counts counts, int total,
            Paging paging, String next, String warning, String note) implements DiscoverResult {

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
     * @param warning why the loaded version cannot be trusted, or {@code null} when it was confirmed against the
     *     registry — see {@code Loader.unverifiedWarning}
     * @param note the listener a service type binds to, or that it is attachable to none — {@code null} outside the
     *     {@code service} bucket
     */
    record ResourceList(
            String container, List<Resource> resources, int shown, int total, Paging paging, String next,
            Documented documented, String warning, String note) implements DiscoverResult {

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
     * @param warning why the loaded version cannot be trusted, or {@code null} when it was confirmed against the
     *     registry — see {@code Loader.unverifiedWarning}
     * @param note the listener a service type binds to, or that it is attachable to none — {@code null} outside the
     *     {@code service} bucket
     */
    record MethodList(
            String container, List<Method> methods, boolean remote, int shown, int total, Paging paging,
            String next, Documented documented, String warning, String note) implements DiscoverResult { }

    /**
     * What a service type's package says a service of it must or may define: the methods to implement, and the
     * constraints across them. Shown instead of "no methods" for a service type that is only a marker, so the caller
     * learns {@code onConsumerRecord} from the tool rather than from a search.
     *
     * @param container the service type
     * @param handlers the methods, in the package's own order
     * @param rules the constraints that range over more than one of them
     * @param warning why the loaded version cannot be trusted, or {@code null} when it was confirmed against the
     *     registry — see {@code Loader.unverifiedWarning}
     * @param note the listener the service type binds to, or that it is attachable to none
     */
    record ServiceHandlerList(
            String container, List<ServiceHandlers.Handler> handlers, List<ServiceHandlers.Rule> rules,
            String warning, String note) implements DiscoverResult {

        public ServiceHandlerList {
            handlers = List.copyOf(handlers);
            rules = List.copyOf(rules);
        }
    }

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
     * @param warning why the loaded version cannot be trusted, or {@code null} when it was confirmed against the
     *     registry — see {@code Loader.unverifiedWarning}
     */
    record Readme(String markdown, int lines, Integer section, Integer of, String title, String warning)
            implements DiscoverResult {

        public Readme(String markdown, int lines, String warning) {
            this(markdown, lines, null, null, null, warning);
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
     * @param warning why the loaded version cannot be trusted, or {@code null} when it was confirmed against the
     *     registry — see {@code Loader.unverifiedWarning}
     * @param matching whether the sections are the ones that matched something, rather than all the readme has
     */
    record ReadmeSections(
            List<Section> sections, int total, Paging paging, String next, String warning, boolean matching)
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
     * @param documented other entries a {@code --filter} matched only in their documentation
     * @param paging which page of {@code documented} this is, or {@code null} when it fit on one
     * @param next the ready-to-run command that turns {@code documented}'s page, or {@code null} on the last
     * @param warning why the loaded version cannot be trusted, or {@code null}
     * @param note the listener a service type's method binds through, or {@code null}
     */
    record Signature(
            String container, String kind, String name, String accessor, String path, String form,
            String declaration, List<Parameter> params, String returns, boolean deprecated,
            List<Type> types, List<Method> omitted, List<Foreign> foreign,
            Documented documented, Paging paging,
            String next, String warning, String note) implements DiscoverResult {

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
     * @param warning why the loaded version cannot be trusted, or {@code null}
     * @param note the same joined advisory {@link ResourceList#note} carries, or {@code null}
     */
    record MixedListing(
            String container, List<ResourceList.Resource> resources, List<Method> remote, List<Method> normal,
            Counts counts, int shown, int total, Paging paging, String next, Documented documented,
            String warning, String note) implements DiscoverResult {

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
     * @param warning why the loaded version cannot be trusted, or {@code null}
     */
    record TypeRoster(
            List<Section> sections, Map<Kind, Integer> counts, int shown, int total, Paging paging, String next,
            Documented documented, String warning) implements DiscoverResult {

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
     * @param warning why the loaded version cannot be trusted, or {@code null}
     * @param note the enum an enum member's answer shows, or {@code null}
     */
    record TypeDeclaration(
            String name, String kind, String declaration, List<Signature.Type> types, List<Method> omitted,
            List<Foreign> foreign, String warning, String note)
            implements DiscoverResult { }

    /**
     * A bucket this package declares nothing in, and the buckets that do hold its callable surface.
     *
     * @param bucket the bucket asked for
     * @param elsewhere every other callable bucket with something in it
     * @param warning why the loaded version cannot be trusted, or {@code null}
     */
    record EmptyBucket(String bucket, List<Elsewhere> elsewhere, String warning) implements DiscoverResult {

        /**
         * @param bucket the bucket's name
         * @param count containers in it — functions, for {@code funcs}
         * @param command the command that opens it
         */
        public record Elsewhere(String bucket, int count, String command) { }
    }
}
