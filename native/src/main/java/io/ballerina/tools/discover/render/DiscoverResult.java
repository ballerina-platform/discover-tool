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
     * @param warning why the loaded version cannot be trusted, or {@code null} when it was confirmed against the
     *     registry — see {@code Loader.unverifiedWarning}
     */
    record BucketList(List<String> buckets, String warning) implements DiscoverResult {

        public BucketList(List<String> buckets) {
            this(buckets, null);
        }
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
         * @param call the command that opens it
         */
        public record Container(String name, int resources, int remote, int normal, String call) { }
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
     * @param note this answer was reached by kind tolerance — the selector named something in a different
     *     bucket, and this is that bucket's own answer instead — or {@code null} when the request already named
     *     the bucket that holds it
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
     * @param note this answer was reached by kind tolerance — the selector named something in a different
     *     bucket, and this is that bucket's own answer instead — or {@code null} when the request already named
     *     the bucket that holds it
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
     * @param note this answer was reached by kind tolerance — the selector named something in a different
     *     bucket, and this is that bucket's own answer instead — or {@code null} when the request already named
     *     the bucket that holds it
     */
    record MethodList(List<String> methods, int shown, int total, String next, String warning, String note)
            implements DiscoverResult {

        public MethodList(List<String> methods, int shown, int total, String next) {
            this(methods, shown, total, next, null, null);
        }
    }
}
