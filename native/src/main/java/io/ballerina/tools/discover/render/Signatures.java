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

import io.ballerina.tools.discover.model.Fn;
import io.ballerina.tools.discover.model.ModuleRef;
import io.ballerina.tools.discover.model.Param;
import io.ballerina.tools.discover.model.ReturnDef;
import io.ballerina.tools.discover.model.TypeRef;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * How one callable is written, and how a foreign name inside it is qualified.
 *
 * <p>This is the module the views share with the API document, and sharing it is what makes their
 * agreement structural rather than tested: the container buckets quote {@link #renderMemberFunction}, so a
 * signature they show cannot differ from the one the API document shows.
 * {@code ViewsAgreeTest} still asserts it, because the cheap way to break the guarantee is for a view to
 * hand-roll a line rather than call this.
 *
 * <p>A name owned by another package is rendered with that package's module alias ({@code gmail:Message}), which
 * is the import the caller adds.
 *
 * @since 0.1.0
 */
public final class Signatures {

    private Signatures() {
    }

    /**
     * One external name and the module it came from.
     *
     * <p>The alias and the import path are derived from the module rather than passed alongside it, so the
     * prefix a signature is printed with and the coordinate a footer offers cannot disagree.
     *
     * @param recordName the name as the owning module declares it
     * @param module the module it came from
     */
    public record ExternalLink(String recordName, ModuleRef module) {

        public String modulePrefix() {
            return module.prefix();
        }
    }

    /**
     * How much of what a callable documents to print.
     *
     * <p>The declaration itself is identical either way — the same bytes, from the same code path — and that is
     * what {@code ViewsAgreeTest} pins. What differs is the documentation around it, and the split follows what
     * each caller is for: the API document and a single-callable answer are declaration registers, where a
     * parameter's description is the point; a compact view quotes the description and the declaration only.
     */
    public enum Detail {

        /** Everything the declaration documents, including a {@code # + name - description} row per parameter. */
        FULL,

        /** The description and the declaration. What a compact view quotes. */
        SIGNATURE
    }

    /**
     * The foreign names inside a type expression that a caller has to import to use it.
     *
     * <p>A pre-declared module is skipped: {@code int:Signed32} is a foreign name by Central's encoding but needs
     * no import by the language's, and {@code import ballerina/lang.int;} does not even compile.
     */
    public static List<ExternalLink> collectExternalLinks(TypeRef type) {
        if (type == null) {
            return List.of();
        }
        List<ExternalLink> links = new ArrayList<>();
        for (TypeRef.Link link : type.links()) {
            if (link instanceof TypeRef.Link.External external
                    && !external.module().coordinate().isEmpty()
                    && !external.module().isPredeclared()) {
                links.add(new ExternalLink(external.recordName(), external.module()));
            }
        }
        return List.copyOf(links);
    }

    /**
     * Qualify every foreign name inside a type expression.
     *
     * <p>Done textually because the expression is already a string by this point — a union or an array of
     * a foreign record has no structure left to walk. The "preceded by a colon" guard keeps an
     * already-qualified name from gaining a second prefix when two links resolve to the same word.
     */
    public static String applyPrefixToTypeName(String typeName, List<ExternalLink> links) {
        String result = typeName;
        for (ExternalLink link : links) {
            Pattern pattern = Pattern.compile("\\b" + Pattern.quote(link.recordName()) + "\\b");
            Matcher matcher = pattern.matcher(result);
            StringBuilder rewritten = new StringBuilder();
            while (matcher.find()) {
                boolean alreadyQualified = matcher.start() > 0 && result.charAt(matcher.start() - 1) == ':';
                String replacement = alreadyQualified
                        ? matcher.group()
                        : link.modulePrefix() + ":" + matcher.group();
                matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(rewritten);
            result = rewritten.toString();
        }
        return result;
    }

    /**
     * The trailing comment a line carries when a default it shows cannot be written by the caller, or {@code ""}.
     *
     * <p>Which package a foreign name comes from is not said here: the name is rendered with its module alias
     * ({@code gmail:Message}), and an answer's {@code foreign} list names the module and the command that opens it.
     */
    public static String trailingNote(List<String> unwritableDefaults) {
        return unwritableDefaults.isEmpty() ? "" : " // " + unwritableClause(unwritableDefaults);
    }

    /** Ballerina doc comments, one {@code #} per line, with the trailing newline callers splice in. */
    public static String renderDescription(String description) {
        if (description.trim().isEmpty()) {
            return "";
        }
        // `split("\n", -1)` rather than `lines()`: a trailing newline or a stray carriage return has to
        // survive verbatim, because these bytes are compared against a committed snapshot.
        return Arrays.stream(description.split("\n", -1))
                .map(line -> "# " + line)
                .collect(Collectors.joining("\n")) + "\n";
    }

    private static String renderParam(Param param) {
        String type = paramType(param);
        String name = Identifiers.write(param.name());
        return switch (param.form()) {
            case INCLUSION -> "*" + type + " " + name;
            case REST -> type + "... " + name;
            case NORMAL -> type + " " + name
                    + (param.hasDefault() ? " = " + param.defaultValue() : "");
        };
    }

    /** A parameter's type as its declaration spells it, foreign names qualified. */
    public static String paramType(Param param) {
        return applyPrefixToTypeName(param.type().name(), collectExternalLinks(param.type()));
    }

    /** A callable's return type as its declaration spells it, or {@code null} when it returns nothing. */
    public static String returnType(Fn fn) {
        return fn.returns().hasType()
                ? applyPrefixToTypeName(fn.returns().type().name(), collectExternalLinks(fn.returns().type()))
                : null;
    }

    private static String renderReturns(ReturnDef returns) {
        return returns.hasType()
                ? " returns " + applyPrefixToTypeName(returns.type().name(), collectExternalLinks(returns.type()))
                : "";
    }

    private static String unwritableClause(List<String> expressions) {
        String names = String.join(", ", expressions);
        return expressions.size() == 1
                ? "the default " + names + " is not exported by this package; omit the argument rather than "
                        + "repeating it"
                : "the defaults " + names + " are not exported by this package; omit the arguments rather "
                        + "than repeating them";
    }

    private static List<String> unwritableDefaults(List<Param> params) {
        return params.stream()
                .filter(Param::unwritableDefault)
                .map(Param::defaultValue)
                .toList();
    }

    /**
     * The indented sibling of {@link #renderDescription}, for a doc comment inside a block.
     *
     * <p>A continuation line without its own {@code #} is not a comment but source the compiler reads as a
     * declaration, so every block renderer splits descriptions through this one place.
     */
    public static String renderDocComment(String description, String indent) {
        if (description.isEmpty()) {
            return "";
        }
        return indent + "# " + String.join("\n" + indent + "# ", description.split("\n", -1)) + "\n";
    }

    private static void addDocRow(List<String> lines, String indent, String label, String description) {
        String[] parts = description.split("\n", -1);
        lines.add(indent + "# + " + label + " - " + parts[0]);
        for (int index = 1; index < parts.length; index++) {
            lines.add(indent + "# " + parts[index]);
        }
    }

    private static String renderCallableDocs(Fn fn, String indent, Detail detail) {
        String description = renderDocComment(fn.description(), indent);
        if (detail == Detail.SIGNATURE) {
            return description;
        }
        List<String> rows = new ArrayList<>();
        for (Param param : fn.params()) {
            if (!param.description().isEmpty()) {
                addDocRow(rows, indent, param.name(), param.description());
            }
        }
        if (fn.returns().hasDescription()) {
            addDocRow(rows, indent, "return", fn.returns().description());
        }
        return rows.isEmpty() ? description : description + String.join("\n", rows) + "\n";
    }

    private static String isolatedQualifier(Fn fn) {
        return fn.isIsolated() ? "isolated " : "";
    }

    private static String renderResourcePath(Fn.Resource fn) {
        return fn.paths().stream()
                .map(segment -> switch (segment) {
                    case Fn.PathSegment.Literal literal -> literal.text();
                    case Fn.PathSegment.Parameter parameter ->
                            "[" + parameter.type() + " " + Identifiers.write(parameter.name()) + "]";
                })
                .collect(Collectors.joining("/"));
    }

    /**
     * A signature at column zero — a report quotes declarations, it does not indent them. Every view calls this
     * rather than its own copy, so they all print the same bytes.
     */
    public static String renderSignature(Fn fn) {
        return renderMemberFunction(fn, "", Detail.SIGNATURE);
    }

    public static String renderMemberFunction(Fn fn, String indent, Detail detail) {
        String params = fn.params().stream().map(Signatures::renderParam).collect(Collectors.joining(", "));
        String note = trailingNote(unwritableDefaults(fn.params()));
        String docs = renderCallableDocs(fn, indent, detail);
        // Between the doc comment and the signature, which is where the language puts it.
        String deprecated = fn.isDeprecated() ? indent + "@deprecated\n" : "";
        String isolated = isolatedQualifier(fn);

        return switch (fn) {
            case Fn.Constructor constructor -> docs + deprecated
                    + indent + isolated + "function init(" + params + ")"
                    + renderReturns(constructor.returns()) + ";" + note;
            case Fn.Remote remote -> docs + deprecated
                    + indent + isolated + "remote function " + Identifiers.write(remote.name())
                    + "(" + params + ")" + renderReturns(remote.returns()) + ";" + note;
            case Fn.Resource resource -> {
                // Path parameters are declared in the path, so repeating them in the parameter list would
                // be a signature no caller can write.
                Set<String> inPath = resource.paths().stream()
                        .filter(Fn.PathSegment.Parameter.class::isInstance)
                        .map(segment -> ((Fn.PathSegment.Parameter) segment).name())
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                String rest = resource.params().stream()
                        .filter(param -> !inPath.contains(param.name()))
                        .map(Signatures::renderParam)
                        .collect(Collectors.joining(", "));
                yield docs + deprecated
                        + indent + isolated + "resource function " + resource.accessor() + " "
                        + renderResourcePath(resource) + "(" + rest + ")"
                        + renderReturns(resource.returns()) + ";" + note;
            }
            case Fn.Normal normal -> docs + deprecated
                    + indent + isolated + "function " + Identifiers.write(normal.name()) + "(" + params + ")"
                    + renderReturns(normal.returns()) + ";" + note;
        };
    }

    /**
     * A module-level function, documented rather than merely declared: standalone functions are usually
     * utilities whose parameters are not self-describing.
     *
     * <p>Separate from {@link #renderMemberFunction} because a module function carries {@code public}, which no
     * member does.
     */
    public static String renderStandaloneFunction(Fn.Standalone fn) {
        List<String> lines = new ArrayList<>();
        if (!fn.description().isEmpty()) {
            for (String line : fn.description().split("\n", -1)) {
                lines.add("# " + line);
            }
        }
        for (Param param : fn.params()) {
            if (!param.description().isEmpty()) {
                addDocRow(lines, "", param.name(), param.description());
            }
        }
        if (fn.returns().hasDescription()) {
            addDocRow(lines, "", "return", fn.returns().description());
        }
        if (fn.isDeprecated()) {
            lines.add("@deprecated");
        }
        String params = fn.params().stream().map(Signatures::renderParam).collect(Collectors.joining(", "));
        lines.add("public " + isolatedQualifier(fn) + "function " + Identifiers.write(fn.name())
                + "(" + params + ")" + renderReturns(fn.returns()) + ";"
                + trailingNote(unwritableDefaults(fn.params())));
        return String.join("\n", lines);
    }
}
