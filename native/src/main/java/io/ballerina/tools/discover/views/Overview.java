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

import io.ballerina.tools.discover.Coordinate;
import io.ballerina.tools.discover.LoadedPackage;
import io.ballerina.tools.discover.Result;
import io.ballerina.tools.discover.Texts;
import io.ballerina.tools.discover.model.Fn;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.symbols.Surface;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The first answer about a package: every callable bucket's first rows, one line for its types, one for its readme,
 * and its submodules, so the first command already names what the next one opens.
 *
 * <p><b>IT STAYS INSIDE THE ENTRY CEILING.</b> A bucket shows at most {@value #SECTION_ROWS} rows; the type and
 * readme lines are a row each; submodules are never cut, since no other answer lists them. If that still comes to
 * more than {@value Containers#MAX_ENTRIES} rows, the section showing the most gives up one row at a time (the later
 * bucket on a tie) until it fits. A section showing fewer rows than its bucket holds says so and carries the command
 * that lists the bucket whole.
 *
 * <p><b>ONE CLIENT IS OPENED IN PLACE.</b> A package with exactly one client is most often used through it, so its
 * listing is inlined under its row: page 1 of {@code client <name>}, grouped and paged by that answer's own rules,
 * so it never holds more than {@value Containers#MAX_ENTRIES} entries and, when the client has more, carries that
 * answer's next-page command ({@code ballerinax/twilio}'s 199 methods show their first 40). The other sections then
 * share what the inlined listing leaves of the ceiling, down to their heading and the command that lists them.
 *
 * <p>It reads only the docs payload every answer reads: which listener a service type binds to can need the package
 * source, so that stays in the {@code service} bucket's own answer.
 *
 * @since 0.1.0
 */
public final class Overview {

    /** The most rows one bucket shows here. */
    static final int SECTION_ROWS = 10;

    /** Kinds the sections already list, so the type line leaves them out. */
    private static final Set<DiscoverResult.TypeRoster.Kind> LISTED_AS_SECTIONS = EnumSet.of(
            DiscoverResult.TypeRoster.Kind.CLIENTS, DiscoverResult.TypeRoster.Kind.CLASSES,
            DiscoverResult.TypeRoster.Kind.SERVICE_TYPES);

    private Overview() {
    }

    public static DiscoverResult.Overview of(LoadedPackage loaded) {
        Map<Surface.Scope, List<DiscoverResult.Overview.Row>> rows = new LinkedHashMap<>();
        for (Surface.Scope scope : Surface.Scope.values()) {
            List<DiscoverResult.Overview.Row> all = rowsOf(loaded, scope);
            if (!all.isEmpty()) {
                rows.put(scope, all);
            }
        }
        Map<DiscoverResult.TypeRoster.Kind, Integer> counts = new LinkedHashMap<>(Types.countsByKind(loaded));
        counts.keySet().removeAll(LISTED_AS_SECTIONS);
        DiscoverResult.Overview.TypeCounts types = counts.isEmpty()
                ? null
                : new DiscoverResult.Overview.TypeCounts(counts, loaded.command() + " " + Types.BUCKET);
        DiscoverResult.Overview.ReadmeSummary readme = loaded.readme().isEmpty()
                ? null
                : new DiscoverResult.Overview.ReadmeSummary(Readme.sectionsOf(loaded).size(),
                        loaded.command() + " " + Readme.BUCKET);
        List<DiscoverResult.Overview.Submodule> submodules = loaded.submodules().stream()
                .map(submodule -> new DiscoverResult.Overview.Submodule(submodule.name(),
                        Texts.firstSentence(submodule.summary()),
                        Coordinate.COMMAND + loaded.pkgArgument(submodule.name())))
                .toList();
        DiscoverResult members = onlyClientsListing(loaded).orElse(null);

        int fixed = (types == null ? 0 : 1) + (readme == null ? 0 : 1) + submodules.size();
        Map<Surface.Scope, List<DiscoverResult.Overview.Row>> shared = new LinkedHashMap<>(rows);
        if (members != null) {
            shared.remove(Surface.Scope.CLIENT);
            fixed += 1 + shownBy(members);
        }
        Map<Surface.Scope, Integer> shown = shares(shared, Containers.MAX_ENTRIES - fixed);
        List<DiscoverResult.Overview.Section> sections = new ArrayList<>();
        rows.forEach((scope, all) -> sections.add(new DiscoverResult.Overview.Section(scope.verb(),
                all.subList(0, shown.getOrDefault(scope, all.size())), all.size(),
                loaded.command() + " " + scope.verb())));
        return new DiscoverResult.Overview(List.copyOf(sections), types, readme, submodules, loaded.warning(),
                members);
    }

    /** How many entries a listing the overview can open in place shows; 0 for any other answer. */
    public static int shownBy(DiscoverResult listing) {
        return switch (listing) {
            case DiscoverResult.MethodList methods -> methods.shown();
            case DiscoverResult.ResourceList resources -> resources.shown();
            case DiscoverResult.MixedListing mixed -> mixed.shown();
            case DiscoverResult.PathGroups groups -> groups.resources().size() + groups.groups().size();
            default -> 0;
        };
    }

    // The one client's own listing, when the package has exactly one and its answer is a listing.
    private static Optional<DiscoverResult> onlyClientsListing(LoadedPackage loaded) {
        List<Surface.Container> clients = Surface.of(loaded.library(), Surface.Scope.CLIENT);
        if (clients.size() != 1) {
            return Optional.empty();
        }
        Result<DiscoverResult> listing = Containers.render(loaded, Surface.Scope.CLIENT,
                new Containers.Options(List.of(clients.get(0).name())));
        return listing.isOk() && shownBy(listing.value()) > 0
                ? Optional.of(listing.value())
                : Optional.empty();
    }

    // Each section's row count: its size up to SECTION_ROWS, then the largest gives one up until all fit `room`.
    private static Map<Surface.Scope, Integer> shares(Map<Surface.Scope, List<DiscoverResult.Overview.Row>> rows,
            int room) {
        Map<Surface.Scope, Integer> shown = new LinkedHashMap<>();
        rows.forEach((scope, all) -> shown.put(scope, Math.min(all.size(), SECTION_ROWS)));
        while (shown.values().stream().mapToInt(Integer::intValue).sum() > Math.max(room, 0)) {
            Surface.Scope largest = null;
            for (Map.Entry<Surface.Scope, Integer> entry : shown.entrySet()) {
                if (largest == null || entry.getValue() >= shown.get(largest)) {
                    largest = entry.getKey();
                }
            }
            if (shown.get(largest) == 0) {
                break;
            }
            shown.merge(largest, -1, Integer::sum);
        }
        return shown;
    }

    private static List<DiscoverResult.Overview.Row> rowsOf(LoadedPackage loaded, Surface.Scope scope) {
        List<Surface.Container> containers = Surface.of(loaded.library(), scope);
        if (scope == Surface.Scope.MODULE) {
            return containers.isEmpty() ? List.of() : Containers.functionRows(loaded, containers.get(0));
        }
        return containers.stream()
                .map(container -> new DiscoverResult.Overview.Row(container.plainName(),
                        container.operations().size(),
                        (int) container.standalone().stream().filter(Fn.Remote.class::isInstance).count(),
                        (int) container.standalone().stream().filter(Fn.Normal.class::isInstance).count(),
                        Containers.openCommand(loaded, scope, container)))
                .toList();
    }
}
