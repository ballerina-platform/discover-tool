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
 * {@code name}/{@code count}, {@code resources}/{@code path}/{@code accessors}/{@code calls}, {@code methods},
 * {@code shown}/{@code total}/{@code next}/{@code call}). {@link ContainerRoster} has no RFC example to match —
 * several containers in one bucket is existing, pre-RFC behaviour — so its field names are this rewrite's own
 * choice, as are those of every other shape the RFC shows no example for ({@link Signature}, {@link MixedListing},
 * {@link NoMatch}, {@link Owners}, {@link EmptyBucket}).
 *
 * @since 0.1.0
 */
public sealed interface DiscoverResult {

    /**
     * Which page of a paginated listing this response is.
     *
     * @param page the page served, 1-indexed
     * @param pages how many pages the whole listing spans
     * @param remaining how many entries come after this page
     */
    record Paging(int page, int pages, int remaining) { }

    /**
     * A package's (or a targeted module's) own top-level buckets — the answer to a bare
     * {@code bal discover <org/name>} with no bucket and no selector.
     *
     * @param buckets the buckets this package actually declares, in a fixed order
     * @param submodules every OTHER module this package publishes, regardless of which one {@code buckets} itself
     *     describes — empty for a package that publishes only its default module
     * @param warning why the loaded version cannot be trusted, or {@code null} when it was confirmed against the
     *     registry — see {@code Loader.unverifiedWarning}
     */
    record BucketList(List<String> buckets, List<Submodule> submodules, String warning) implements DiscoverResult {

        public BucketList(List<String> buckets) {
            this(buckets, List.of(), null);
        }

        public BucketList(List<String> buckets, String warning) {
            this(buckets, List.of(), warning);
        }

        /**
         * @param name the bare name {@code --module} itself takes
         * @param summary the submodule's own one-line summary, or empty when it publishes none
         * @param call the command that opens it
         */
        public record Submodule(String name, String summary, String call) { }
    }

    /**
     * Several containers in one bucket (several clients, several classes) — the roster a caller chooses from
     * before naming one.
     *
     * @param containers every container in the bucket, up to the ceiling
     * @param total how many the bucket actually declares
     * @param next the ready-to-run command that narrows further, or {@code null} when nothing was cut off
     * @param warning why the loaded version cannot be trusted, or {@code null} when it was confirmed against the
     *     registry — see {@code Loader.unverifiedWarning}
     */
    record ContainerRoster(List<Container> containers, int total, String next, String warning)
            implements DiscoverResult {

        public ContainerRoster(List<Container> containers, int total, String next) {
            this(containers, total, next, null);
        }

        /**
         * @param name the container's own name
         * @param resources how many resource paths it declares
         * @param remote how many remote methods it declares
         * @param normal how many plain (non-remote) methods it declares
         * @param listener the listener(s) this service type binds to, joined, or {@code null} outside the
         *     {@code service} bucket
         * @param call the command that opens it
         */
        public record Container(String name, int resources, int remote, int normal, String listener, String call) {

            public Container(String name, int resources, int remote, int normal, String call) {
                this(name, resources, remote, normal, null, call);
            }
        }
    }

    /**
     * Resource paths grouped by their next segment, because there are too many to list flat — the RFC's
     * {@code repos (275), orgs (124), ...} shape.
     *
     * @param container the container these paths belong to
     * @param resources the operations that end at this level rather than under a further group, one entry per
     *     path — listed beside the groups because no group name could reach them without looping back here
     * @param groups the groups shown, busiest first; {@code resources} and {@code groups} share the ceiling
     * @param total how many entries (resources plus groups) exist at this level
     * @param next the ready-to-run command that narrows further, or {@code null} when nothing was cut off
     * @param warning why the loaded version cannot be trusted, or {@code null} when it was confirmed against the
     *     registry — see {@code Loader.unverifiedWarning}
     * @param note one or two facts the ceiling has no room to give its own field: this answer was reached by kind
     *     tolerance (the selector named something in a different bucket, and this is that bucket's own answer
     *     instead), the {@code service} bucket's own container names the listener it binds to, or both, joined —
     *     {@code null} when neither applies
     */
    record PathGroups(
            String container, List<ResourceList.Resource> resources, List<Group> groups, int total, String next,
            String warning, String note) implements DiscoverResult {

        public PathGroups(List<Group> groups, int total, String next) {
            this(null, List.of(), groups, total, next, null, null);
        }

        /**
         * @param name the group's path prefix, {@code /}-joined from the top of the bucket
         * @param count selected operations under this group
         * @param call the command that opens it, carrying the accessor the listing was narrowed by — a group is
         *     never ambiguous, so this is never {@code null}
         */
        public record Group(String name, int count, String call) { }
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
     * @param warning why the loaded version cannot be trusted, or {@code null} when it was confirmed against the
     *     registry — see {@code Loader.unverifiedWarning}
     * @param note one or two facts the ceiling has no room to give its own field: this answer was reached by kind
     *     tolerance (the selector named something in a different bucket, and this is that bucket's own answer
     *     instead), the {@code service} bucket's own container names the listener it binds to, or both, joined —
     *     {@code null} when neither applies
     */
    record ResourceList(
            String container, List<Resource> resources, int shown, int total, Paging paging, String next,
            String warning, String note) implements DiscoverResult {

        public ResourceList(List<Resource> resources, int shown, int total, String next) {
            this(null, resources, shown, total, null, next, null, null);
        }

        /**
         * @param path the resource's path, {@code :name}-spelled for parameters, {@code .} for the root
         * @param accessors every accessor this path answers to
         * @param calls the exact next-step command per accessor, in {@code accessors} order — one shape whether a
         *     path answers to one accessor or several, and every accessor gets its own, so none is preferred
         */
        public record Resource(String path, List<String> accessors, Map<String, String> calls) {

            public Resource {
                accessors = List.copyOf(accessors);
                calls = Collections.unmodifiableMap(new LinkedHashMap<>(calls));
            }
        }
    }

    /**
     * Remote or normal method names, flat — the RFC's {@code Methods: get, post, ...} shape, paginated with
     * {@code --page} once a container has too many to show at once.
     *
     * @param container the container that declares them, or {@code null} for module-level functions
     * @param methods the names shown, alphabetical, up to the ceiling
     * @param shown how many are in this response
     * @param total how many the container declares
     * @param paging which page this is, or {@code null} when the whole listing fit on one
     * @param next the ready-to-run command that narrows further or turns the page, or {@code null} when nothing
     *     was cut off
     * @param warning why the loaded version cannot be trusted, or {@code null} when it was confirmed against the
     *     registry — see {@code Loader.unverifiedWarning}
     * @param note one or two facts the ceiling has no room to give its own field: this answer was reached by kind
     *     tolerance (the selector named something in a different bucket, and this is that bucket's own answer
     *     instead), the {@code service} bucket's own container names the listener it binds to, or both, joined —
     *     {@code null} when neither applies
     */
    record MethodList(
            String container, List<String> methods, int shown, int total, Paging paging, String next,
            String warning, String note) implements DiscoverResult {

        public MethodList(List<String> methods, int shown, int total, String next) {
            this(null, methods, shown, total, null, next, null, null);
        }
    }

    /**
     * The {@code readme} bucket's own answer: the whole readme verbatim, or — when a chunk selector resolved to
     * exactly one section — that section alone. No entry ceiling applies here; unlike every other listing this
     * bucket answers, the RFC's own text for it is "returns that module's README, verbatim", with no size limit
     * of any kind.
     *
     * @param markdown the readme, or the one resolved chunk, verbatim
     * @param lines how many lines {@code markdown} holds
     * @param chunk the chunk's 1-based number, or {@code null} for the whole readme
     * @param of how many chunks the readme carries, or {@code null} for the whole readme
     * @param title the chunk's own heading, or {@code null} for the whole readme
     * @param warning why the loaded version cannot be trusted, or {@code null} when it was confirmed against the
     *     registry — see {@code Loader.unverifiedWarning}
     */
    record Readme(String markdown, int lines, Integer chunk, Integer of, String title, String warning)
            implements DiscoverResult {

        public Readme(String markdown, int lines, String warning) {
            this(markdown, lines, null, null, null, warning);
        }
    }

    /**
     * More than one readme chunk matches a selector or {@code --filter} — the roster to choose one from, the same
     * ceiling every other listing in this tool obeys.
     *
     * @param chunks the chunks shown, up to the ceiling
     * @param total how many chunks actually match
     * @param paging which page this is, or {@code null} when every match fit on one
     * @param next the ready-to-run command that narrows further, or {@code null} when nothing was cut off
     * @param warning why the loaded version cannot be trusted, or {@code null} when it was confirmed against the
     *     registry — see {@code Loader.unverifiedWarning}
     */
    record ReadmeChunks(List<Chunk> chunks, int total, Paging paging, String next, String warning)
            implements DiscoverResult {

        public ReadmeChunks(List<Chunk> chunks, int total, String next) {
            this(chunks, total, null, next, null);
        }

        /**
         * @param number the 1-based address a caller types
         * @param title the heading it sits under
         * @param lines how many lines the chunk holds
         * @param call the command that opens it
         */
        public record Chunk(int number, String title, int lines, String call) { }
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
     * @param types the declarations its signature names, one level deep, within the closure budget
     * @param omitted the names the closure budget left out of {@code types}
     * @param documented other entries a {@code --filter} matched only in their documentation
     * @param warning why the loaded version cannot be trusted, or {@code null}
     * @param note kind tolerance, a path advisory or a service's listener, joined — or {@code null}
     */
    record Signature(
            String container, String kind, String name, String accessor, String path, String form,
            String declaration, List<Parameter> params, String returns, boolean deprecated,
            List<Type> types, List<String> omitted, List<String> documented, String warning, String note)
            implements DiscoverResult {

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
     * A container answering to both {@code ->path.accessor()} and {@code ->name()}/{@code .name()} — the
     * {@link ResourceList} and {@link MethodList} shapes side by side, split by call form, paginated with
     * {@code --page} as one sequence (resources, then remote, then normal) once there are too many to show at once.
     *
     * @param container the container that declares them
     * @param resources the resource paths on this page
     * @param remote the remote method names on this page, alphabetical
     * @param normal the plain method names on this page, alphabetical
     * @param shown how many entries across all three are in this response
     * @param total how many exist across all three
     * @param paging which page this is, or {@code null} when the whole listing fit on one
     * @param next the ready-to-run command that turns the page, or {@code null} when nothing was cut off
     * @param documented entries a {@code --filter} matched only in their documentation
     * @param warning why the loaded version cannot be trusted, or {@code null}
     * @param note the same joined advisory {@link ResourceList#note} carries, or {@code null}
     */
    record MixedListing(
            String container, List<ResourceList.Resource> resources, List<String> remote, List<String> normal,
            int shown, int total, Paging paging, String next, List<String> documented, String warning, String note)
            implements DiscoverResult { }

    /**
     * A selector or {@code --filter} that matched nothing — exit 0, with what IS there.
     *
     * @param requested the selector or filter keyword, as typed
     * @param container the container it was looked for in, or {@code null} for a whole bucket
     * @param candidates the closest names that do exist
     * @param paths where a trailing path segment occurs, when it occurs in several places and none was picked
     * @param available the listing the same command gives without the selector, or {@code null} when
     *     {@code paths} already names the way forward
     * @param next the command that lists everything there
     * @param documented entries a {@code --filter} matched only in their documentation
     * @param warning why the loaded version cannot be trusted, or {@code null}
     * @param note the same joined advisory every other container answer carries, or {@code null}
     */
    record NoMatch(
            String requested, String container, List<String> candidates, List<Alternative> paths,
            DiscoverResult available, String next, List<String> documented, String warning, String note)
            implements DiscoverResult {

        /**
         * @param path one full path carrying the requested segment
         * @param call the command that opens it
         */
        public record Alternative(String path, String call) { }
    }

    /**
     * One member name declared on several containers in a bucket — which one is the caller's choice, not a guess.
     *
     * @param requested the member name, as typed
     * @param owners the containers declaring it, up to the ceiling
     * @param total how many containers declare it
     * @param next a command template naming the slot to fill, or {@code null} when nothing was cut off
     * @param warning why the loaded version cannot be trusted, or {@code null}
     */
    record Owners(String requested, List<Owner> owners, int total, String next, String warning)
            implements DiscoverResult {

        /**
         * @param name the container's name
         * @param matches how many of its entries the selector matched
         * @param call the command that opens the member on that container
         */
        public record Owner(String name, int matches, String call) { }
    }

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
         * @param call the command that opens it
         */
        public record Elsewhere(String bucket, int count, String call) { }
    }
}
