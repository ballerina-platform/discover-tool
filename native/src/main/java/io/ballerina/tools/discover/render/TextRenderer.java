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

import java.util.stream.Collectors;

/**
 * {@link DiscoverResult} → the human-oriented text the RFC shows at an interactive terminal.
 *
 * <p>Deliberately terse — the RFC's own worked examples are one or two lines for every shape here, which is a
 * real departure from this tool's earlier Markdown-report convention (headings, a facts table, a
 * {@code ## Next} section). That convention is not carried forward for any shape defined on
 * {@link DiscoverResult}: it belongs only to the single-signature answer {@code views.Containers} still renders
 * directly, which this class never sees.
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
        };
    }

    private static String renderBucketList(DiscoverResult.BucketList bucketList) {
        String list = bucketList.buckets().isEmpty() ? "none" : String.join(", ", bucketList.buckets());
        return withWarning(list, bucketList.warning());
    }

    private static String renderContainerRoster(DiscoverResult.ContainerRoster roster) {
        String list = roster.containers().stream()
                .map(DiscoverResult.ContainerRoster.Container::name)
                .collect(Collectors.joining(", "));
        return withWarning(
                withNext(list, roster.containers().size(), roster.total(), roster.next()), roster.warning());
    }

    private static String renderPathGroups(DiscoverResult.PathGroups groups) {
        String list = "Groups: " + groups.groups().stream()
                .map(group -> group.name() + " (" + group.count() + ")")
                .collect(Collectors.joining(", "));
        return withNotices(withNext(list, groups.groups().size(), groups.total(), groups.next()),
                groups.warning(), groups.note());
    }

    private static String renderResourceList(DiscoverResult.ResourceList resources) {
        String list = resources.resources().stream()
                .map(resource -> resource.path() + " — " + String.join(", ", resource.accessors()))
                .collect(Collectors.joining("\n"));
        return withNotices(withNext(list, resources.shown(), resources.total(), resources.next()),
                resources.warning(), resources.note());
    }

    private static String renderMethodList(DiscoverResult.MethodList methods) {
        String list = "Methods: " + String.join(", ", methods.methods());
        return withNotices(withNext(list, methods.shown(), methods.total(), methods.next()),
                methods.warning(), methods.note());
    }

    private static String withWarning(String body, String warning) {
        return warning == null ? body : "Warning: " + warning + "\n" + body;
    }

    /** {@code note} first — it explains what bucket this answer actually came from — then {@code warning}. */
    private static String withNotices(String body, String warning, String note) {
        String withNote = note == null ? body : "Note: " + note + "\n" + body;
        return withWarning(withNote, warning);
    }

    /** The RFC's truncation line: {@code ... N more, narrow further: <command>} — only when something was cut. */
    private static String withNext(String body, int shown, int total, String next) {
        if (next == null || shown >= total) {
            return body;
        }
        return body + "\n... " + (total - shown) + " more, narrow further: " + next;
    }
}
