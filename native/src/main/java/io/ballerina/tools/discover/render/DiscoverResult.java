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

import java.util.List;

/**
 * One structured shape {@code bal discover} can answer with — the "one source, multiple renderers" the RFC asks
 * for. A view builds one of these; {@link TextRenderer} and {@link JsonRenderer} are the only two things that turn
 * it into bytes, so the two renderers can never drift into describing different data.
 *
 * <p>Field names follow the RFC's own worked examples wherever one exists ({@code buckets}, {@code groups}/
 * {@code name}/{@code count}, {@code resources}/{@code path}/{@code accessors}, {@code methods}, {@code shown}/
 * {@code total}/{@code next}/{@code call}). {@link ContainerRoster} has no RFC example to match — several
 * containers in one bucket is existing, pre-RFC behaviour — so its field names are this rewrite's own choice.
 *
 * <p>Still not defined here: a single fully-resolved signature. {@code Containers} keeps rendering that case
 * (an exact one-of-one match) through its existing rich Markdown — declaration syntax, doc comments, and the
 * type closure the signature names — rather than folding it into this IR now. That is a real, tested capability
 * with no ceiling problem to solve (it is already exactly one result), so the RFC-alignment plan's item 4 scope
 * (the output-size ceiling, resource grouping, pagination and {@code --filter}) does not require touching it;
 * giving it a proper structured JSON shape of its own is left as a follow-up rather than decided under this
 * item's time pressure.
 *
 * @since 0.1.0
 */
public sealed interface DiscoverResult {

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
     * @param groups the groups shown, up to the ceiling, busiest first
     * @param total how many groups exist at this level
     * @param next the ready-to-run command that narrows further, or {@code null} when nothing was cut off
     * @param warning why the loaded version cannot be trusted, or {@code null} when it was confirmed against the
     *     registry — see {@code Loader.unverifiedWarning}
     * @param note one or two facts the ceiling has no room to give its own field: this answer was reached by kind
     *     tolerance (the selector named something in a different bucket, and this is that bucket's own answer
     *     instead), the {@code service} bucket's own container names the listener it binds to, or both, joined —
     *     {@code null} when neither applies
     */
    record PathGroups(List<Group> groups, int total, String next, String warning, String note)
            implements DiscoverResult {

        public PathGroups(List<Group> groups, int total, String next) {
            this(groups, total, next, null, null);
        }

        /**
         * @param name the group's path prefix, {@code /}-joined from the top of the bucket
         * @param count operations at or under this group
         * @param call the command that opens it — a group is never ambiguous, so this is never {@code null}
         */
        public record Group(String name, int count, String call) { }
    }

    /**
     * Resource paths, flat: one entry per path with the accessors it answers to — the terminal answer once a
     * group (or the whole bucket) fits under the ceiling.
     *
     * @param resources the paths shown, up to the ceiling
     * @param shown how many are in this response
     * @param total how many exist at this level
     * @param next the ready-to-run command that narrows further, or {@code null} when nothing was cut off
     * @param warning why the loaded version cannot be trusted, or {@code null} when it was confirmed against the
     *     registry — see {@code Loader.unverifiedWarning}
     * @param note one or two facts the ceiling has no room to give its own field: this answer was reached by kind
     *     tolerance (the selector named something in a different bucket, and this is that bucket's own answer
     *     instead), the {@code service} bucket's own container names the listener it binds to, or both, joined —
     *     {@code null} when neither applies
     */
    record ResourceList(List<Resource> resources, int shown, int total, String next, String warning, String note)
            implements DiscoverResult {

        public ResourceList(List<Resource> resources, int shown, int total, String next) {
            this(resources, shown, total, next, null, null);
        }

        /**
         * @param path the resource's path, {@code :name}-spelled for parameters
         * @param accessors every accessor this path answers to
         * @param call the exact next-step command, or {@code null} on a multi-accessor entry — a flat field
         *     would have to guess which accessor
         */
        public record Resource(String path, List<String> accessors, String call) { }
    }

    /**
     * Remote or normal method names, flat — the RFC's {@code Methods: get, post, ...} shape, paginated with
     * {@code --page} once a container has too many to show at once.
     *
     * @param methods the names shown, alphabetical, up to the ceiling
     * @param shown how many are in this response
     * @param total how many the container declares
     * @param next the ready-to-run command that narrows further or turns the page, or {@code null} when nothing
     *     was cut off
     * @param warning why the loaded version cannot be trusted, or {@code null} when it was confirmed against the
     *     registry — see {@code Loader.unverifiedWarning}
     * @param note one or two facts the ceiling has no room to give its own field: this answer was reached by kind
     *     tolerance (the selector named something in a different bucket, and this is that bucket's own answer
     *     instead), the {@code service} bucket's own container names the listener it binds to, or both, joined —
     *     {@code null} when neither applies
     */
    record MethodList(List<String> methods, int shown, int total, String next, String warning, String note)
            implements DiscoverResult {

        public MethodList(List<String> methods, int shown, int total, String next) {
            this(methods, shown, total, next, null, null);
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
     * @param next the ready-to-run command that narrows further, or {@code null} when nothing was cut off
     * @param warning why the loaded version cannot be trusted, or {@code null} when it was confirmed against the
     *     registry — see {@code Loader.unverifiedWarning}
     */
    record ReadmeChunks(List<Chunk> chunks, int total, String next, String warning) implements DiscoverResult {

        public ReadmeChunks(List<Chunk> chunks, int total, String next) {
            this(chunks, total, next, null);
        }

        /**
         * @param number the 1-based address a caller types
         * @param title the heading it sits under
         * @param lines how many lines the chunk holds
         * @param call the command that opens it
         */
        public record Chunk(int number, String title, int lines, String call) { }
    }
}
