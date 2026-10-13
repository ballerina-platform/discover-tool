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
import io.ballerina.tools.discover.model.Fn;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.symbols.Filter;
import io.ballerina.tools.discover.symbols.PathTree;
import io.ballerina.tools.discover.symbols.Surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code search} inside one package — every name it declares and every readme section title, matched locally
 * against what is already loaded; nothing is sent to Central.
 *
 * <p><b>THE MATCHING RULE.</b> Each keyword is matched as a case-insensitive substring of a symbol's name, and a
 * symbol matches when every keyword does. The name is a container's, a function's or a type's own name, a method's
 * own name (not its container's), a resource method's path and accessor ({@code repos/:owner/:repo/issues post}),
 * and a readme section's title. Names are read without Ballerina's escapes, as every answer prints them. Unlike a
 * selector this is a keyword search, so it is lenient by design; unlike {@code --filter}, it reads names only, never
 * parameters, types or documentation.
 *
 * <p>Matches are grouped by kind in {@link DiscoverResult.Search.Kind} order and paged as one sequence under the
 * entry ceiling. No match is an answer, not a failure: it points back at the package's overview.
 *
 * @since 0.1.0
 */
public final class Search {

    public static final String BUCKET = "search";

    private static final String KEYWORD_SEPARATOR = "\\s+";

    private static final Map<Surface.Scope, DiscoverResult.Search.Kind> CONTAINER_KINDS = Map.of(
            Surface.Scope.CLIENT, DiscoverResult.Search.Kind.CLIENTS,
            Surface.Scope.SERVICE, DiscoverResult.Search.Kind.SERVICE_TYPES,
            Surface.Scope.CLASS, DiscoverResult.Search.Kind.CLASSES);

    private Search() {
    }

    /**
     * @param keywords the keywords as typed, one or more
     * @param page {@code --page}, 1-indexed
     */
    public record Options(List<String> keywords, int page) { }

    /**
     * The failure for a search with no keyword, or with {@code --filter}, or {@code null}; checked before anything is
     * fetched. Shared by this search and Central's.
     *
     * @param search the command that searches, without keywords: {@code bal discover ballerinax/kafka search}
     */
    public static Failure misuse(String search, List<String> keywords, String filter) {
        if (keywords.isEmpty()) {
            return new Failure.Validation("The search needs at least one keyword.",
                    "Write " + Failure.quoted(search + " <keyword>") + ".");
        }
        if (filter != null) {
            String fixed = search + words(keywords) + " " + Texts.shellWord(filter);
            return new Failure.Validation("The --filter option narrows a listing, and a search is not one.",
                    "Add its keyword to the search instead: " + Failure.quoted(fixed) + ".", fixed);
        }
        return null;
    }

    public static Result<DiscoverResult> render(LoadedPackage loaded, Options options) {
        List<String> keywords = options.keywords().stream()
                .flatMap(typed -> Arrays.stream(typed.trim().split(KEYWORD_SEPARATOR)))
                .filter(keyword -> !keyword.isEmpty())
                .toList();
        Map<DiscoverResult.Search.Kind, List<DiscoverResult.Search.Hit>> found = new EnumMap<>(
                DiscoverResult.Search.Kind.class);
        for (DiscoverResult.Search.Kind kind : DiscoverResult.Search.Kind.values()) {
            found.put(kind, new ArrayList<>());
        }
        collectCallables(loaded, keywords, found);
        collectTypes(loaded, keywords, found);
        collectReadme(loaded, keywords, found);

        List<DiscoverResult.Search.Hit> all = found.values().stream().flatMap(List::stream).toList();
        String command = loaded.command() + " " + BUCKET + words(options.keywords());
        Result<Containers.Page> page = Containers.Page.of(options.page(), all.size(), command);
        if (!page.isOk()) {
            return page.cast();
        }
        Containers.Page window = page.value();
        List<DiscoverResult.Search.Group> groups = new ArrayList<>();
        Map<DiscoverResult.Search.Kind, Integer> counts = new LinkedHashMap<>();
        int offset = 0;
        for (Map.Entry<DiscoverResult.Search.Kind, List<DiscoverResult.Search.Hit>> kind : found.entrySet()) {
            List<DiscoverResult.Search.Hit> shown = window.slice(kind.getValue(), offset);
            offset += kind.getValue().size();
            if (!kind.getValue().isEmpty()) {
                counts.put(kind.getKey(), kind.getValue().size());
            }
            if (!shown.isEmpty()) {
                groups.add(new DiscoverResult.Search.Group(kind.getKey(), List.copyOf(shown)));
            }
        }
        int shown = groups.stream().mapToInt(group -> group.hits().size()).sum();
        return Result.ok(new DiscoverResult.Search(options.keywords(), List.copyOf(groups), counts, shown,
                all.size(), window.paging(), window.next(command), loaded.command(), loaded.warning()));
    }

    // A hit and the group it lists under.
    private record Found(DiscoverResult.Search.Kind kind, DiscoverResult.Search.Hit hit) { }

    private static void collectCallables(LoadedPackage loaded, List<String> keywords,
            Map<DiscoverResult.Search.Kind, List<DiscoverResult.Search.Hit>> found) {
        List<Found> members = new ArrayList<>();
        for (Surface.Scope scope : Surface.Scope.values()) {
            for (Surface.Container container : Surface.of(loaded.library(), scope)) {
                if (!container.isModule() && matches(container.plainName(), keywords)) {
                    found.get(CONTAINER_KINDS.get(scope)).add(new DiscoverResult.Search.Hit(container.plainName(),
                            null, null, Containers.openCommand(loaded, scope, container)));
                }
                for (Containers.Entry entry : Containers.members(container)) {
                    if (matches(nameOf(entry), keywords)) {
                        members.add(memberHit(loaded, container, entry));
                    }
                }
            }
        }
        members.sort(Comparator.comparing((Found member) -> String.valueOf(member.hit().container()),
                Texts.LOCALE_ORDER).thenComparing(member -> member.hit().name(), Texts.LOCALE_ORDER));
        members.forEach(member -> found.get(member.kind()).add(member.hit()));
    }

    // What a member is searched by: its own name, or a resource method's path and accessor.
    private static String nameOf(Containers.Entry entry) {
        return entry.fn() instanceof Fn.Resource resource
                ? PathTree.join(entry.path()) + " " + resource.accessor()
                : entry.label();
    }

    private static Found memberHit(LoadedPackage loaded, Surface.Container container, Containers.Entry entry) {
        String command = Containers.memberCommand(loaded, container, entry);
        if (container.isModule()) {
            return new Found(DiscoverResult.Search.Kind.FUNCTIONS,
                    new DiscoverResult.Search.Hit(entry.label(), null, null, command));
        }
        return switch (entry.fn()) {
            case Fn.Resource resource -> new Found(DiscoverResult.Search.Kind.RESOURCES, new DiscoverResult.Search.Hit(
                    PathTree.join(entry.path()), container.plainName(), resource.accessor(), command));
            case Fn.Remote ignored -> new Found(DiscoverResult.Search.Kind.REMOTE,
                    new DiscoverResult.Search.Hit(entry.label(), container.plainName(), null, command));
            case Fn.Normal ignored -> new Found(DiscoverResult.Search.Kind.METHODS,
                    new DiscoverResult.Search.Hit(entry.label(), container.plainName(), null, command));
            case Fn.Constructor ignored -> throw new IllegalStateException("constructors are not searched");
        };
    }

    private static void collectTypes(LoadedPackage loaded, List<String> keywords,
            Map<DiscoverResult.Search.Kind, List<DiscoverResult.Search.Hit>> found) {
        for (Types.Declared declared : Types.declared(loaded)) {
            if (!matches(declared.name(), keywords)) {
                continue;
            }
            switch (declared.kind()) {
                case CLIENTS, CLASSES, SERVICE_TYPES -> {
                    // Listed with their own bucket's command, as containers.
                }
                case LISTENERS -> found.get(DiscoverResult.Search.Kind.LISTENERS).add(
                        new DiscoverResult.Search.Hit(declared.name(), null, null, declared.command()));
                default -> found.get(DiscoverResult.Search.Kind.TYPES).add(new DiscoverResult.Search.Hit(
                        declared.name(), null, declared.kind().noun(), declared.command()));
            }
        }
        for (DiscoverResult.Search.Kind sorted : List.of(DiscoverResult.Search.Kind.TYPES,
                DiscoverResult.Search.Kind.LISTENERS)) {
            found.get(sorted).sort(Comparator.comparing(DiscoverResult.Search.Hit::name, Texts.LOCALE_ORDER));
        }
    }

    private static void collectReadme(LoadedPackage loaded, List<String> keywords,
            Map<DiscoverResult.Search.Kind, List<DiscoverResult.Search.Hit>> found) {
        for (Readme.Section section : Readme.sectionsOf(loaded)) {
            if (matches(section.title(), keywords)) {
                found.get(DiscoverResult.Search.Kind.README).add(new DiscoverResult.Search.Hit(section.title(), null,
                        null, section.number(), loaded.command() + " " + Readme.BUCKET + " " + section.number()));
            }
        }
    }

    private static boolean matches(String name, List<String> keywords) {
        return keywords.stream().allMatch(keyword -> Filter.matches(keyword, name));
    }

    private static String words(List<String> words) {
        return words.stream().map(word -> " " + Texts.shellWord(word)).collect(Collectors.joining());
    }
}
