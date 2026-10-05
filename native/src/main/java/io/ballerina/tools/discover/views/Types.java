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
import io.ballerina.tools.discover.model.Library;
import io.ballerina.tools.discover.model.ModuleRef;
import io.ballerina.tools.discover.model.TypeDef;
import io.ballerina.tools.discover.model.TypeRef;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.Documents;
import io.ballerina.tools.discover.render.TypeDefs;
import io.ballerina.tools.discover.symbols.Declarations;
import io.ballerina.tools.discover.symbols.Filter;
import io.ballerina.tools.discover.symbols.Names;
import io.ballerina.tools.discover.symbols.Surface;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code type} — the declarations that are not callable: records, enums, errors, type aliases, constants, module
 * variables and annotations.
 *
 * <p>Not derived from call-site grammar, so it shares no {@link Surface.Scope} with the four container buckets:
 * every loop over {@code Scope.values()} assumes a container with methods. Bare, it is a roster grouped by kind
 * and paged as one sequence; one name is a leaf, the declaration whole with the types it names. A name that is
 * really a class, client or service type is answered by the bucket that holds it, and says so.
 *
 * @since 0.1.0
 */
public final class Types {

    private static final List<String> KINDS =
            List.of("records", "enums", "errors", "aliases", "constants", "variables", "annotations");

    private Types() {
    }

    /**
     * @param selectors everything after {@code type}: at most one declaration name
     * @param filter the {@code --filter} keyword, or {@code null}
     * @param page {@code --page}, 1-indexed, for a roster over the ceiling
     * @param note the routing advisory when another bucket sent the caller here, or {@code null}
     */
    public record Options(List<String> selectors, String filter, int page, String note) {

        public Options(List<String> selectors, String filter, int page) {
            this(selectors, filter, page, null);
        }

        public boolean filtered() {
            return filter != null && !filter.isBlank();
        }
    }

    /**
     * One roster row: a declaration or an annotation, with the text a {@code --filter} reads.
     *
     * @param kind the section it lists under
     * @param name the declared name
     * @param surface what its name and shape say
     * @param docs what its documentation says
     */
    private record Entry(String kind, String name, String surface, String docs) { }

    /** How many declarations the bucket holds — what the bare package listing and an empty bucket report. */
    public static int count(LoadedPackage loaded) {
        return entries(loaded.library()).size();
    }

    /** Whether {@code name} is a declaration this bucket holds, for another bucket deciding to route here. */
    public static boolean declares(LoadedPackage loaded, String name) {
        Library library = loaded.library();
        return entries(library).stream().anyMatch(entry -> entry.name().equals(name));
    }

    public static Result<DiscoverResult> render(LoadedPackage loaded, Options options) {
        if (options.selectors().size() > 1) {
            return Result.err(new Failure.Validation(
                    "type takes one declaration name; got " + options.selectors().size() + ".",
                    "Name one declaration, or list them all: `bal discover " + loaded.pkgArgument() + " type`."));
        }
        if (options.selectors().isEmpty()) {
            return roster(loaded, options);
        }
        if (options.filtered()) {
            return Result.err(new Failure.Validation(
                    "--filter narrows a listing, and '" + options.selectors().get(0) + "' names one declaration.",
                    "Drop --filter, or drop the name: `bal discover " + loaded.pkgArgument() + " type "
                            + "--filter " + Texts.shellWord(options.filter()) + "`."));
        }
        return leaf(loaded, options.selectors().get(0), options.note());
    }

    // -----------------------------------------------------------------------
    // The roster
    // -----------------------------------------------------------------------

    private static List<Entry> entries(Library library) {
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TypeDef typeDef : library.addressable()) {
            String kind = kindOf(typeDef);
            if (kind != null && seen.add(typeDef.name())) {
                entries.add(new Entry(kind, typeDef.name(), Filter.surfaceOf(typeDef), Filter.docsOf(typeDef)));
            }
        }
        for (Library.AnnotationDef annotation : library.annotations()) {
            if (seen.add(annotation.name())) {
                entries.add(new Entry("annotations", annotation.name(),
                        annotation.name() + " " + annotation.type().map(TypeRef::name).orElse("") + " "
                                + annotation.attachmentPoints(),
                        annotation.description()));
            }
        }
        return List.copyOf(entries);
    }

    /** The plural section a declaration lists under, or {@code null} for a class, client or service type. */
    private static String kindOf(TypeDef typeDef) {
        return switch (typeDef) {
            case TypeDef.Rec ignored -> "records";
            case TypeDef.Enumeration ignored -> "enums";
            case TypeDef.ErrorDef ignored -> "errors";
            case TypeDef.Alias alias -> alias.type().name().startsWith("distinct ") ? "errors" : "aliases";
            case TypeDef.Constant ignored -> "constants";
            case TypeDef.Variable ignored -> "variables";
            case TypeDef.ObjectDef ignored -> null;
        };
    }

    private static String baseCommand(LoadedPackage loaded) {
        return "bal discover " + loaded.pkgArgument() + " type";
    }

    private static Result<DiscoverResult> roster(LoadedPackage loaded, Options options) {
        List<Entry> all = entries(loaded.library());
        if (all.isEmpty()) {
            return Result.ok(Containers.emptyBucket(loaded, "type"));
        }
        String base = baseCommand(loaded);
        String command = base + (options.filtered() ? " --filter " + Texts.shellWord(options.filter()) : "");

        List<Entry> surface = all;
        List<String> documented = List.of();
        if (options.filtered()) {
            Filter.Split<Entry> split = Filter.apply(options.filter(), all, Entry::surface, Entry::docs);
            surface = split.surface();
            documented = split.documented().stream().map(Entry::name).sorted(Texts.LOCALE_ORDER).toList();
            if (surface.isEmpty() && documented.isEmpty()) {
                return Result.ok(new DiscoverResult.NoMatch(options.filter(), null,
                        Names.nearMisses(options.filter(), all.stream().map(Entry::name).toList()), List.of(), null,
                        base, DiscoverResult.Documented.NONE, null, loaded.warning(), options.note()));
            }
        }

        Map<String, List<DiscoverResult.Method>> byKind = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String kind : KINDS) {
            List<DiscoverResult.Method> methods = surface.stream()
                    .filter(entry -> entry.kind().equals(kind))
                    .map(Entry::name)
                    .sorted(Texts.LOCALE_ORDER)
                    .map(name -> new DiscoverResult.Method(name, base + " " + Texts.shellWord(name)))
                    .toList();
            byKind.put(kind, methods);
            counts.put(kind, methods.size());
        }
        int total = surface.size();

        Result<Containers.Page> page = Containers.Page.of(options.page(), total + documented.size(), command);
        if (!page.isOk()) {
            return page.cast();
        }
        Containers.Page window = page.value();
        List<DiscoverResult.TypeRoster.Section> sections = new ArrayList<>();
        int offset = 0;
        for (String kind : KINDS) {
            List<DiscoverResult.Method> shown = window.slice(byKind.get(kind), offset);
            offset += byKind.get(kind).size();
            if (!shown.isEmpty()) {
                sections.add(new DiscoverResult.TypeRoster.Section(kind, shown));
            }
        }
        int shown = sections.stream().mapToInt(section -> section.entries().size()).sum();
        DiscoverResult.Documented documentedHere = documented.isEmpty()
                ? DiscoverResult.Documented.NONE
                : new DiscoverResult.Documented(window.slice(documented, total), documented.size());
        return Result.ok(new DiscoverResult.TypeRoster(List.copyOf(sections), counts, shown, total,
                window.paging(), window.next(command), documentedHere, loaded.warning(), options.note()));
    }

    // -----------------------------------------------------------------------
    // One declaration
    // -----------------------------------------------------------------------

    private static Result<DiscoverResult> leaf(LoadedPackage loaded, String requested, String note) {
        Library library = loaded.library();
        Declarations index = Declarations.index(library.addressable());
        List<String> names = new ArrayList<>(index.names());
        library.annotations().stream().map(Library.AnnotationDef::name).filter(name -> !names.contains(name))
                .forEach(names::add);

        Names.Match match = Names.match(requested, names);
        if (!(match instanceof Names.Match.Found found)) {
            return Result.err(new Failure.SymbolNotFound(
                    loaded.label(), List.of(requested), Names.candidatesOf(match),
                    missSuggestion(loaded, match instanceof Names.Match.Ambiguous)));
        }
        String name = found.name();
        TypeDef typeDef = index.get(name);
        if (typeDef instanceof TypeDef.ObjectDef object) {
            return routed(loaded, object);
        }
        if (typeDef == null) {
            Library.AnnotationDef annotation = library.annotations().stream()
                    .filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
            return Result.ok(new DiscoverResult.TypeDeclaration(name, "annotation",
                    Documents.renderAnnotation(annotation), List.of(), List.of(), List.of(), loaded.warning(),
                    note));
        }

        Closure.Result closure = Closure.leaf(List.of(name), index);
        List<DiscoverResult.Signature.Type> types = closure.types(index).stream()
                .filter(type -> !type.name().equals(name))
                .toList();
        List<DiscoverResult.Method> omitted = closure.omitted().stream()
                .limit(Containers.MAX_ENTRIES)
                .map(missing -> new DiscoverResult.Method(missing, commandFor(loaded, missing)))
                .toList();
        List<TypeDef> printed = closure.names().stream().map(index::get).toList();
        return Result.ok(new DiscoverResult.TypeDeclaration(name, singular(typeDef),
                TypeDefs.renderTypeDef(typeDef), types, omitted, foreignOf(loaded, printed), loaded.warning(),
                note));
    }

    private static String singular(TypeDef typeDef) {
        return switch (kindOf(typeDef)) {
            case "records" -> "record";
            case "enums" -> "enum";
            case "errors" -> "error";
            case "aliases" -> "alias";
            case "constants" -> "constant";
            case "variables" -> "variable";
            default -> throw new IllegalStateException("not a type-bucket declaration: " + typeDef.name());
        };
    }

    /**
     * A class, client, service type or listener the caller addressed by {@code type}: answered by the bucket that
     * holds it, with the same one-line routing note every other kind guess gets.
     */
    private static Result<DiscoverResult> routed(LoadedPackage loaded, TypeDef.ObjectDef object) {
        boolean listener = loaded.library().listeners().stream().anyMatch(each -> each.name().equals(object.name()));
        Surface.Scope scope = listener ? Surface.Scope.SERVICE : Surface.scopeOf(object);
        String routing = "'" + object.name() + "' is "
                + (listener ? "a listener, shown with the service types it serves" : "addressed by " + scope.verb())
                + " — showing it. Canonical: bal discover " + loaded.pkgArgument() + " " + scope.verb()
                + (listener ? "" : " " + Texts.shellWord(object.name()));
        return Containers.render(loaded, scope,
                new Containers.Options(listener ? List.of() : List.of(object.name())), routing);
    }

    private static String missSuggestion(LoadedPackage loaded, boolean ambiguous) {
        String search = "`bal discover " + loaded.pkgArgument() + " type --filter <keyword>`";
        return (ambiguous
                ? "Several declarations normalise to the same name, so this reader will not choose between them. "
                        + "Re-run with one of the candidates exactly as spelled"
                : "No declaration matched. Re-run with one of the candidates if it is what you meant, or search "
                        + "by keyword with " + search)
                + ". Add --refresh if you believe the name exists and is newer than the cached copy.";
    }

    /** The command that opens a name this package declares: its own bucket when it is a container. */
    private static String commandFor(LoadedPackage loaded, String name) {
        for (Surface.Scope scope : Surface.Scope.values()) {
            if (Surface.of(loaded.library(), scope).stream().anyMatch(container -> container.name().equals(name))) {
                return "bal discover " + loaded.pkgArgument() + " " + scope.verb() + " " + Texts.shellWord(name);
            }
        }
        return baseCommand(loaded) + " " + Texts.shellWord(name);
    }

    /**
     * Every declaration another package owns that the printed declarations name, once each. A pre-declared module
     * is not an edge to cross: nothing to import, and its follow-up command is a measured dead end.
     */
    static List<DiscoverResult.Foreign> foreignOf(LoadedPackage loaded, List<TypeDef> printed) {
        Map<String, DiscoverResult.Foreign> foreign = new LinkedHashMap<>();
        for (TypeDef typeDef : printed) {
            for (TypeRef expression : Closure.expressionsOf(typeDef)) {
                for (TypeRef.Link link : expression.links()) {
                    if (link instanceof TypeRef.Link.External external && !external.module().isPredeclared()) {
                        ModuleRef module = external.module();
                        foreign.computeIfAbsent(module.coordinate() + ":" + external.recordName(), key ->
                                new DiscoverResult.Foreign(external.recordName(), module.coordinate(),
                                        module.pinnedVersion().orElse(null),
                                        loaded.argumentFor(module)
                                                .map(target -> "bal discover " + target + " type "
                                                        + Texts.shellWord(external.recordName()))
                                                .orElse(null)));
                    }
                }
            }
        }
        return List.copyOf(foreign.values());
    }
}
