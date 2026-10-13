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
import io.ballerina.tools.discover.render.Identifiers;
import io.ballerina.tools.discover.render.TypeDefs;
import io.ballerina.tools.discover.symbols.Declarations;
import io.ballerina.tools.discover.symbols.Filter;
import io.ballerina.tools.discover.symbols.Surface;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code type} — every type the package declares: records, enums, errors, type aliases, constants, module variables,
 * annotations, listeners, and the classes, clients and service types the callable buckets also list.
 *
 * <p>Not derived from call-site grammar, so it shares no {@link Surface.Scope} with the four container buckets:
 * every loop over {@code Scope.values()} assumes a container with methods. Bare, it is a roster grouped by kind
 * and paged as one sequence; one name is a leaf, the declaration whole with the types it names. A class, client or
 * service type gets the answer its own bucket gives.
 *
 * @since 0.1.0
 */
public final class Types {

    public static final String BUCKET = "type";

    private Types() {
    }

    /**
     * @param selectors everything after {@code type}: at most one declaration name
     * @param filter the {@code --filter} keyword, or {@code null}
     * @param page {@code --page}, 1-indexed, for a roster over the ceiling, or the answer a class gets
     */
    public record Options(List<String> selectors, String filter, int page) {

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
    private record Entry(DiscoverResult.TypeRoster.Kind kind, String name, String surface, String docs) { }

    /** How many declarations the bucket holds — what the bare package listing and an empty bucket report. */
    public static int count(LoadedPackage loaded) {
        return entries(loaded.library()).size();
    }

    /** How many declarations of each kind the bucket holds, in roster order, every kind with at least one. */
    public static Map<DiscoverResult.TypeRoster.Kind, Integer> countsByKind(LoadedPackage loaded) {
        Map<DiscoverResult.TypeRoster.Kind, Integer> counts = new LinkedHashMap<>();
        for (DiscoverResult.TypeRoster.Kind kind : DiscoverResult.TypeRoster.Kind.values()) {
            long count = entries(loaded.library()).stream().filter(entry -> entry.kind() == kind).count();
            if (count > 0) {
                counts.put(kind, (int) count);
            }
        }
        return counts;
    }

    /** What {@code name} is, when this bucket holds a type by that name: {@code record}, {@code listener}. */
    public static Optional<String> kindOf(LoadedPackage loaded, String name) {
        String plain = Identifiers.plain(name);
        return entries(loaded.library()).stream()
                .filter(entry -> entry.name().equals(plain))
                .map(entry -> entry.kind().noun())
                .findFirst();
    }

    /** The one enum declaring a member named {@code member}, when exactly one does. */
    public static Optional<String> enumOf(LoadedPackage loaded, String member) {
        List<TypeDef.Enumeration> owners = enumsWithMember(loaded, member);
        return owners.size() == 1 ? Optional.of(owners.get(0).name()) : Optional.empty();
    }

    public static Result<DiscoverResult> render(LoadedPackage loaded, Options options) {
        Failure misuse = misuse(baseCommand(loaded), options);
        if (misuse != null) {
            return Result.err(misuse);
        }
        if (options.selectors().isEmpty()) {
            return roster(loaded, options);
        }
        return leaf(loaded, options.selectors().get(0), options.page());
    }

    /**
     * The failure for more than one name, or a name with {@code --filter}, or {@code null}; checked before the
     * package is fetched.
     *
     * @param command the command the bucket is listed with, e.g. {@code bal discover ballerina/io type}
     */
    public static Failure misuse(String command, Options options) {
        if (options.selectors().size() > 1) {
            return new Failure.Validation(
                    "The type bucket takes one declaration name; got " + options.selectors().size() + ".",
                    "Name one declaration, or list them all: `" + command + "`.");
        }
        if (options.selectors().size() == 1 && options.filtered()) {
            return new Failure.Validation(
                    "The --filter option narrows a listing, and '" + options.selectors().get(0)
                            + "' names one declaration.",
                    "Drop --filter, or drop the name: `" + command + " --filter "
                            + Texts.shellWord(options.filter()) + "`.");
        }
        return null;
    }

    private static List<Entry> entries(Library library) {
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<String> seenAnnotations = new LinkedHashSet<>();
        for (TypeDef typeDef : library.addressable()) {
            if (seen.add(typeDef.name())) {
                entries.add(new Entry(kindOf(library, typeDef), Identifiers.plain(typeDef.name()),
                        Filter.surfaceOf(typeDef),
                        Filter.docsOf(typeDef)));
            }
        }
        for (Library.AnnotationDef annotation : library.annotations()) {
            if (seenAnnotations.add(annotation.name())) {
                entries.add(new Entry(DiscoverResult.TypeRoster.Kind.ANNOTATIONS, Identifiers.plain(annotation.name()),
                        annotation.name() + " " + annotation.type().map(TypeRef::name).orElse("") + " "
                                + annotation.attachmentPoints(),
                        annotation.description()));
            }
        }
        return List.copyOf(entries);
    }

    private static DiscoverResult.TypeRoster.Kind kindOf(Library library, TypeDef typeDef) {
        return switch (typeDef) {
            case TypeDef.Rec ignored -> DiscoverResult.TypeRoster.Kind.RECORDS;
            case TypeDef.Enumeration ignored -> DiscoverResult.TypeRoster.Kind.ENUMS;
            case TypeDef.ErrorDef ignored -> DiscoverResult.TypeRoster.Kind.ERRORS;
            case TypeDef.Alias alias -> alias.type().name().startsWith("distinct ")
                    ? DiscoverResult.TypeRoster.Kind.ERRORS
                    : DiscoverResult.TypeRoster.Kind.ALIASES;
            case TypeDef.Constant ignored -> DiscoverResult.TypeRoster.Kind.CONSTANTS;
            case TypeDef.Variable ignored -> DiscoverResult.TypeRoster.Kind.VARIABLES;
            case TypeDef.ObjectDef object -> isListener(library, object.name())
                    ? DiscoverResult.TypeRoster.Kind.LISTENERS
                    : switch (Surface.scopeOf(object)) {
                        case CLIENT -> DiscoverResult.TypeRoster.Kind.CLIENTS;
                        case SERVICE -> DiscoverResult.TypeRoster.Kind.SERVICE_TYPES;
                        case CLASS, MODULE -> DiscoverResult.TypeRoster.Kind.CLASSES;
                    };
        };
    }

    private static boolean isListener(Library library, String name) {
        return library.listeners().stream().anyMatch(listener -> listener.name().equals(name));
    }

    private static String baseCommand(LoadedPackage loaded) {
        return "bal discover " + loaded.pkgArgument() + " " + BUCKET;
    }

    private static Result<DiscoverResult> roster(LoadedPackage loaded, Options options) {
        List<Entry> all = entries(loaded.library());
        if (all.isEmpty()) {
            return Result.ok(Containers.emptyBucket(loaded, BUCKET));
        }
        String base = baseCommand(loaded);
        String command = base + (options.filtered() ? " --filter " + Texts.shellWord(options.filter()) : "");

        List<Entry> surface = all;
        List<DiscoverResult.Documented.Entry> documented = List.of();
        if (options.filtered()) {
            Filter.Split<Entry> split = Filter.apply(options.filter(), all, Entry::surface, Entry::docs);
            surface = split.surface();
            documented = split.documented().stream().map(Entry::name).sorted(Texts.LOCALE_ORDER)
                    .<DiscoverResult.Documented.Entry>map(name ->
                            new DiscoverResult.Method(name, base + " " + Texts.shellWord(name)))
                    .toList();
        }

        Map<DiscoverResult.TypeRoster.Kind, List<DiscoverResult.Method>> byKind = new LinkedHashMap<>();
        Map<DiscoverResult.TypeRoster.Kind, Integer> counts = new LinkedHashMap<>();
        for (DiscoverResult.TypeRoster.Kind kind : DiscoverResult.TypeRoster.Kind.values()) {
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
        for (DiscoverResult.TypeRoster.Kind kind : DiscoverResult.TypeRoster.Kind.values()) {
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
                window.paging(), window.next(command), documentedHere, loaded.warning()));
    }

    private static Result<DiscoverResult> leaf(LoadedPackage loaded, String requested, int page) {
        Library library = loaded.library();
        Declarations index = Declarations.index(library.addressable());
        List<String> names = new ArrayList<>(index.names());
        library.annotations().stream().map(Library.AnnotationDef::name).filter(name -> !names.contains(name))
                .forEach(names::add);

        String plain = Identifiers.plain(requested);
        Optional<String> declared = names.stream().filter(name -> Identifiers.plain(name).equals(plain)).findFirst();
        if (declared.isEmpty()) {
            return member(loaded, index, requested);
        }
        String name = declared.get();
        TypeDef typeDef = index.get(name);
        if (typeDef == null) {
            return Result.ok(annotationLeaf(loaded, index, name));
        }
        if (typeDef instanceof TypeDef.ObjectDef object && !isListener(library, name)) {
            return Containers.render(loaded, Surface.scopeOf(object),
                    new Containers.Options(List.of(name), null, page));
        }
        return declaration(loaded, index, name, null);
    }

    private static Result<DiscoverResult> member(LoadedPackage loaded, Declarations index, String requested) {
        List<TypeDef.Enumeration> owners = enumsWithMember(loaded, requested);
        if (owners.size() == 1) {
            String enumName = owners.get(0).name();
            return declaration(loaded, index, enumName,
                    "'" + requested + "' is a member of the enum " + enumName + ".");
        }
        String listing = baseCommand(loaded);
        if (owners.isEmpty()) {
            return Result.err(loaded.symbolNotFound(List.of(requested),
                    "'" + requested + "' is not a type in " + loaded.place() + ".",
                    "List the types: " + Failure.quoted(listing) + ".", listing));
        }
        List<String> enums = owners.stream().map(TypeDef.Enumeration::name).toList();
        return Result.err(loaded.symbolNotFound(List.of(requested),
                "'" + requested + "' is a member of the enums " + Texts.and(enums) + " in " + loaded.place() + ".",
                "Open the enum you mean, e.g. " + Failure.quoted(listing + " " + Texts.shellWord(enums.get(0))) + ".",
                null));
    }

    private static Result<DiscoverResult> declaration(
            LoadedPackage loaded, Declarations index, String name, String note) {
        TypeDef typeDef = index.get(name);
        Closure.Result closure = Closure.leaf(Closure.Roots.askedFor(List.of(name)), index);
        List<DiscoverResult.Signature.Type> types = closure.types(index).stream()
                .filter(type -> !type.name().equals(name))
                .toList();
        return Result.ok(new DiscoverResult.TypeDeclaration(Identifiers.plain(name),
                kindOf(loaded.library(), typeDef).noun(),
                TypeDefs.renderTypeDef(typeDef), types, omittedOf(loaded, closure),
                foreignOf(loaded, closure, index, List.of()), loaded.warning(), note));
    }

    private static List<TypeDef.Enumeration> enumsWithMember(LoadedPackage loaded, String member) {
        return loaded.library().addressable().stream()
                .filter(TypeDef.Enumeration.class::isInstance)
                .map(TypeDef.Enumeration.class::cast)
                .filter(each -> each.memberNames().stream()
                        .anyMatch(name -> Identifiers.plain(name).equals(Identifiers.plain(member))))
                .toList();
    }

    private static DiscoverResult annotationLeaf(LoadedPackage loaded, Declarations index, String name) {
        Library.AnnotationDef annotation = annotationNamed(loaded.library(), name).orElseThrow();
        List<String> roots = annotation.type().map(type -> Closure.rootsOf(type, index)).orElse(List.of());
        Closure.Result closure = Closure.leaf(Closure.Roots.askedFor(roots), index);
        return new DiscoverResult.TypeDeclaration(Identifiers.plain(name),
                DiscoverResult.TypeRoster.Kind.ANNOTATIONS.noun(),
                Documents.renderAnnotation(annotation), closure.types(index), omittedOf(loaded, closure),
                foreignOf(loaded, closure, index, annotation.type().map(List::of).orElse(List.of())),
                loaded.warning(), null);
    }

    private static Optional<Library.AnnotationDef> annotationNamed(Library library, String name) {
        return library.annotations().stream().filter(candidate -> candidate.name().equals(name)).findFirst();
    }

    /** Every name a closure left out, each with the command that opens it. */
    static List<DiscoverResult.Method> omittedOf(LoadedPackage loaded, Closure.Result closure) {
        return closure.omitted().stream()
                .map(Identifiers::plain)
                .map(missing -> new DiscoverResult.Method(missing, commandFor(loaded, missing)))
                .toList();
    }

    private static String commandFor(LoadedPackage loaded, String name) {
        for (Surface.Scope scope : Surface.Scope.values()) {
            if (Surface.byName(Surface.of(loaded.library(), scope), name).isPresent()) {
                return "bal discover " + loaded.pkgArgument() + " " + scope.verb() + " " + Texts.shellWord(name);
            }
        }
        return baseCommand(loaded) + " " + Texts.shellWord(name);
    }

    /**
     * Every declaration another package owns that the printed declarations, and any {@code extra} expressions the
     * caller itself names, mention, once each. A pre-declared module is not an edge to cross: nothing to import,
     * and its follow-up command is a measured dead end.
     */
    static List<DiscoverResult.Foreign> foreignOf(
            LoadedPackage loaded, Closure.Result closure, Declarations index, List<TypeRef> extra) {
        List<TypeRef> expressions = new ArrayList<>(extra);
        for (String printed : closure.names()) {
            expressions.addAll(Closure.expressionsOf(index.get(printed)));
        }
        Map<String, DiscoverResult.Foreign> foreign = new LinkedHashMap<>();
        for (TypeRef expression : expressions) {
            for (TypeRef.Link link : expression.links()) {
                if (link instanceof TypeRef.Link.External external && !external.module().isPredeclared()) {
                    ModuleRef module = external.module();
                    foreign.computeIfAbsent(module.coordinate() + ":" + external.recordName(), key ->
                            new DiscoverResult.Foreign(external.recordName(), module.coordinate(),
                                    module.pinnedVersion().orElse(null),
                                    loaded.argumentFor(module)
                                            .map(target -> pinnedTo(loaded, module, target))
                                            .map(target -> "bal discover " + target + " " + BUCKET + " "
                                                    + Texts.shellWord(Identifiers.plain(external.recordName())))
                                            .orElse(null)));
                }
            }
        }
        return List.copyOf(foreign.values());
    }

    private static String pinnedTo(LoadedPackage loaded, ModuleRef module, String target) {
        return target.equals(module.coordinate()) && !module.coordinate().equals(loaded.qualified().qualified())
                ? Coordinate.argument(target, module.pinnedVersion().orElse(null), null)
                : target;
    }
}
