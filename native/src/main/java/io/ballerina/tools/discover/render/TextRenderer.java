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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link DiscoverResult} → the human-oriented text the RFC shows at an interactive terminal.
 *
 * <p>Deliberately terse — the RFC's own worked examples are one or two lines for every shape here. A shape with
 * Ballerina to show prints it bare, as the RFC's own single-signature example does; every command a caller can
 * follow is printed, one per row, as the JSON rendering's {@code call} fields carry it.
 *
 * @since 0.1.0
 */
public final class TextRenderer {

    private TextRenderer() {
    }

    public static String render(DiscoverResult result) {
        return switch (result) {
            case DiscoverResult.BucketList bucketList -> renderBucketList(bucketList);
            case DiscoverResult.ContainerRoster roster -> renderContainerRoster(roster);
            case DiscoverResult.PathGroups groups -> renderPathGroups(groups);
            case DiscoverResult.ResourceList resources -> renderResourceList(resources);
            case DiscoverResult.MethodList methods -> renderMethodList(methods);
            case DiscoverResult.Readme readme -> renderReadme(readme);
            case DiscoverResult.ReadmeChunks chunks -> renderReadmeChunks(chunks);
            case DiscoverResult.Signature signature -> renderSignature(signature);
            case DiscoverResult.MixedListing mixed -> renderMixedListing(mixed);
            case DiscoverResult.NoMatch noMatch -> renderNoMatch(noMatch);
            case DiscoverResult.Owners owners -> renderOwners(owners);
            case DiscoverResult.EmptyBucket empty -> renderEmptyBucket(empty);
        };
    }

    private static String renderBucketList(DiscoverResult.BucketList bucketList) {
        String list = bucketList.buckets().isEmpty() ? "none" : String.join(", ", bucketList.buckets());
        if (!bucketList.submodules().isEmpty()) {
            list += "\n\nSubmodules:\n" + bucketList.submodules().stream()
                    .map(submodule -> "  " + submodule.name()
                            + (submodule.summary().isEmpty() ? "" : " — " + submodule.summary())
                            + ": " + submodule.call())
                    .collect(Collectors.joining("\n"));
        }
        return withWarning(list, bucketList.warning());
    }

    private static String renderContainerRoster(DiscoverResult.ContainerRoster roster) {
        String list = roster.containers().stream()
                .map(container -> container.listener() == null
                        ? container.name()
                        : container.name() + " (binds to " + container.listener() + ")")
                .collect(Collectors.joining(", "));
        return withWarning(
                withNext(list, roster.total() - roster.containers().size(), null, roster.next()), roster.warning());
    }

    private static String renderPathGroups(DiscoverResult.PathGroups groups) {
        List<String> sections = new ArrayList<>();
        if (!groups.resources().isEmpty()) {
            sections.add("Here:\n" + resourceLines(groups.resources()));
        }
        if (!groups.groups().isEmpty()) {
            sections.add("Groups: " + groups.groups().stream()
                    .map(group -> group.name() + " (" + group.count() + ")")
                    .collect(Collectors.joining(", ")));
        }
        int shown = groups.resources().size() + groups.groups().size();
        String body = withNext(String.join("\n", sections), groups.total() - shown, null, groups.next());
        return withNotices(withContainer(body, groups.container()), groups.warning(), groups.note());
    }

    private static String renderResourceList(DiscoverResult.ResourceList resources) {
        String list = resourceLines(resources.resources());
        String body = withNext(list, remaining(resources.shown(), resources.total(), resources.paging()),
                resources.paging(), resources.next());
        return withNotices(withContainer(body, resources.container()), resources.warning(), resources.note());
    }

    private static String renderMethodList(DiscoverResult.MethodList methods) {
        String list = "Methods: " + (methods.methods().isEmpty() ? "none" : String.join(", ", methods.methods()));
        String body = withNext(list, remaining(methods.shown(), methods.total(), methods.paging()),
                methods.paging(), methods.next());
        return withNotices(withContainer(body, methods.container()), methods.warning(), methods.note());
    }

    /** Verbatim, per the RFC's own words for this bucket — no heading, no wrapping, for the whole-readme case. */
    private static String renderReadme(DiscoverResult.Readme readme) {
        if (readme.markdown().isEmpty()) {
            return withWarning("none", readme.warning());
        }
        String body = readme.chunk() == null
                ? readme.markdown()
                : "Chunk " + readme.chunk() + " of " + readme.of() + " — " + readme.title()
                        + "\n\n" + readme.markdown();
        return withWarning(body, readme.warning());
    }

    private static String renderReadmeChunks(DiscoverResult.ReadmeChunks chunks) {
        if (chunks.chunks().isEmpty()) {
            return withWarning("none", chunks.warning());
        }
        String list = chunks.chunks().stream()
                .map(chunk -> chunk.number() + ". " + chunk.title() + " (" + chunk.lines() + " lines)")
                .collect(Collectors.joining("\n"));
        return withWarning(
                withNext(list, remaining(chunks.chunks().size(), chunks.total(), chunks.paging()), chunks.paging(),
                        chunks.next()),
                chunks.warning());
    }

    private static String resourceLines(List<DiscoverResult.ResourceList.Resource> resources) {
        return resources.stream()
                .map(resource -> resource.path() + " — " + String.join(", ", resource.accessors()))
                .collect(Collectors.joining("\n"));
    }

    /** The declaration first, bare — the RFC's own example is that line and nothing else — then what it names. */
    private static String renderSignature(DiscoverResult.Signature signature) {
        List<String> blocks = new ArrayList<>();
        blocks.add(signature.declaration());
        if (!signature.types().isEmpty()) {
            blocks.add("Types it names (" + signature.types().size() + "):");
            signature.types().forEach(type -> blocks.add(type.declaration()));
        }
        if (!signature.omitted().isEmpty()) {
            blocks.add(signature.omitted().size() + " more past the closure budget, not shown: "
                    + String.join(", ", signature.omitted()));
        }
        addDocumented(blocks, signature.documented());
        return withNotices(String.join("\n\n", blocks), signature.warning(), signature.note());
    }

    private static String renderMixedListing(DiscoverResult.MixedListing mixed) {
        List<String> sections = new ArrayList<>();
        if (!mixed.resources().isEmpty()) {
            sections.add("Resources (->):\n" + resourceLines(mixed.resources()));
        }
        if (!mixed.remote().isEmpty()) {
            sections.add("Remote (->): " + String.join(", ", mixed.remote()));
        }
        if (!mixed.normal().isEmpty()) {
            sections.add("Normal (.): " + String.join(", ", mixed.normal()));
        }
        addDocumented(sections, mixed.documented());
        String body = withNext(String.join("\n", sections), mixed.total() - mixed.shown(), null, mixed.next());
        return withNotices(withContainer(body, mixed.container()), mixed.warning(), mixed.note());
    }

    private static String renderNoMatch(DiscoverResult.NoMatch noMatch) {
        List<String> lines = new ArrayList<>();
        lines.add("Nothing" + (noMatch.container() == null ? "" : " on " + noMatch.container())
                + " matches '" + noMatch.requested() + "'.");
        if (!noMatch.candidates().isEmpty()) {
            lines.add("Did you mean: " + String.join(", ", noMatch.candidates()));
        }
        if (!noMatch.paths().isEmpty()) {
            lines.add(noMatch.paths().size() + " paths carry that segment — pick one:");
            noMatch.paths().forEach(alternative -> lines.add("  " + alternative.path() + ": " + alternative.call()));
        }
        addDocumented(lines, noMatch.documented());
        if (noMatch.available() != null) {
            lines.add("Available:");
            lines.add(render(noMatch.available()));
        } else {
            lines.add("List everything: " + noMatch.next());
        }
        return withNotices(String.join("\n", lines), noMatch.warning(), noMatch.note());
    }

    private static String renderOwners(DiscoverResult.Owners owners) {
        String list = "'" + owners.requested() + "' is declared on " + owners.total() + " containers — pick one:\n"
                + owners.owners().stream()
                        .map(owner -> "  " + owner.name() + " (" + owner.matches()
                                + (owner.matches() == 1 ? " match): " : " matches): ") + owner.call())
                        .collect(Collectors.joining("\n"));
        return withWarning(withNext(list, owners.total() - owners.owners().size(), null, owners.next()),
                owners.warning());
    }

    private static String renderEmptyBucket(DiscoverResult.EmptyBucket empty) {
        String body = empty.bucket() + ": none in this package";
        if (!empty.elsewhere().isEmpty()) {
            body += "\nElsewhere:\n" + empty.elsewhere().stream()
                    .map(other -> "  " + other.bucket() + " (" + other.count() + "): " + other.call())
                    .collect(Collectors.joining("\n"));
        }
        return withWarning(body, empty.warning());
    }

    private static void addDocumented(List<String> blocks, List<String> documented) {
        if (!documented.isEmpty()) {
            blocks.add("Matched by documentation only: " + String.join(", ", documented));
        }
    }

    private static String withWarning(String body, String warning) {
        return warning == null ? body : "Warning: " + warning + "\n" + body;
    }

    /** {@code note} first — it explains what bucket this answer actually came from — then {@code warning}. */
    private static String withNotices(String body, String warning, String note) {
        String withNote = note == null ? body : "Note: " + note + "\n" + body;
        return withWarning(withNote, warning);
    }

    /** The single container a listing belongs to, as its first line — none for module-level functions. */
    private static String withContainer(String body, String container) {
        return container == null ? body : "Container: " + container + "\n" + body;
    }

    /** How many entries a listing left out: everything after this page, or everything past what was shown. */
    private static int remaining(int shown, int total, DiscoverResult.Paging paging) {
        return paging == null ? total - shown : paging.remaining();
    }

    /**
     * The RFC's truncation line: {@code ... N more, narrow further: <command>} — only when something was cut. A
     * paged listing names its page instead, including the last one, which has nothing more to point at.
     */
    private static String withNext(String body, int remaining, DiscoverResult.Paging paging, String next) {
        String position = paging == null ? "" : " (page " + paging.page() + " of " + paging.pages() + ")";
        if (next == null || remaining <= 0) {
            return position.isEmpty() ? body : body + "\nLast page" + position;
        }
        return body + "\n... " + remaining + " more" + position
                + (paging == null ? ", narrow further: " : ", next page: ") + next;
    }
}
