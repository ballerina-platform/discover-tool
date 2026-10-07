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
import io.ballerina.tools.discover.model.RecordField;
import io.ballerina.tools.discover.model.TypeDef;
import io.ballerina.tools.discover.model.TypeRef;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One module-level declaration, as Ballerina.
 *
 * <p>{@link #renderTypeDef} switches over every case with no {@code default}: adding a Ballerina shape to
 * the IR fails the build here until it has a rendering. The {@code type} bucket prints its declarations through
 * it too, so a declaration read by name is byte-identical to the same declaration in the whole-package document.
 *
 * <p>A doc comment is adjacent to the declaration it documents, and a declaration without one starts at its first
 * line. Every renderer therefore concatenates {@link Signatures#renderDescription}'s output instead of joining it,
 * because that output already ends in a newline.
 *
 * @since 0.1.0
 */
public final class TypeDefs {

    /**
     * A string constant's value as Central sends it: WITH its quotes.
     *
     * <p>Quotes are still added when absent: "already quoted" is an observation about today's payload, and an
     * unquoted value would otherwise render as a bare identifier that reads like a reference.
     */
    private static final Pattern QUOTED = Pattern.compile("^\".*\"$", Pattern.DOTALL);

    /**
     * Every declaration in this document is {@code public}, and that is measured rather than assumed.
     *
     * <p>Central's docs payload carries no declaration-level visibility — its {@code isPublic} key lives on type
     * reference nodes, describing the referent — but it publishes only public declarations: checked against the
     * fixtures' sources, every module-private declaration is withheld. The one published exception,
     * {@code configurable}, is not rendered.
     */
    private static final String PUBLIC = "public ";

    /**
     * Which body a field is being written into, because the language spells the two differently.
     *
     * <p>A class or object-type field takes {@code public}; a record field cannot ({@code invalid token 'public'}).
     * Central publishes only an object's public fields, and the qualifier is not cosmetic: an including class must
     * repeat the visibility of the field it overrides ({@code mismatched visibility qualifiers}, e.g. postgresql's
     * value classes including {@code *sql:TypedValue}).
     */
    private enum Owner { RECORD, OBJECT }

    private TypeDefs() {
    }

    public static String renderTypeDef(TypeDef typeDef) {
        return switch (typeDef) {
            case TypeDef.Rec record -> renderRecord(record);
            case TypeDef.Enumeration enumeration -> renderEnum(enumeration);
            case TypeDef.Alias alias -> renderAlias(alias);
            case TypeDef.Constant constant -> renderConstant(constant);
            case TypeDef.Variable variable -> renderVariable(variable);
            case TypeDef.ObjectDef object -> renderObject(object);
            case TypeDef.ErrorDef error -> renderError(error);
        };
    }

    private static String renderRecord(TypeDef.Rec typeDef) {
        String open = typeDef.isClosed() ? " record {|" : " record {";
        String close = typeDef.isClosed() ? "|};" : "};";
        List<String> lines = new ArrayList<>();
        if (typeDef.isDeprecated()) {
            lines.add("@deprecated");
        }
        lines.add(PUBLIC + "type " + typeDef.name() + open);
        for (RecordField field : typeDef.fields()) {
            lines.add(renderRecordField(field, Owner.RECORD));
        }
        lines.add(close);
        return Signatures.renderDescription(typeDef.description()) + String.join("\n", lines);
    }

    private static String renderRecordField(RecordField field, Owner owner) {
        List<Signatures.ExternalLink> links = Signatures.collectExternalLinks(field.type());
        String typeName = Signatures.applyPrefixToTypeName(field.type().name(), links);
        String description = Signatures.renderDocComment(field.description(), "    ");
        return switch (field.form()) {
            // An inclusion takes no visibility qualifier in either body: `*sql:TypedValue;` is a statement about
            // where the members come from, not a member of its own.
            case INCLUSION -> description + "    *" + typeName + ";";
            // No doc comment: Central's description for a rest field is the literal string "Rest field",
            // which is its label for the FORM and not documentation. The source writes none either.
            case REST -> "    " + typeName + "...;";
            case DECLARED -> {
                String deprecated = field.deprecated() ? "    @deprecated\n" : "";
                String visibility = owner == Owner.OBJECT ? PUBLIC : "";
                String readonly = field.readonly() ? "readonly " : "";
                String optional = field.optional() ? "?" : "";
                String defaultValue = field.hasDefault() ? " = " + field.defaultValue() : "";
                String caveat = Signatures.trailingNote(
                        field.unwritableDefault() ? List.of(field.defaultValue()) : List.of());
                yield description + deprecated + "    " + visibility + readonly + typeName + " "
                        + Identifiers.write(field.name())
                        + optional + defaultValue + ";" + caveat;
            }
        };
    }

    private static String renderObject(TypeDef.ObjectDef typeDef) {
        boolean isClass = typeDef.form() == TypeDef.ObjectDef.Form.CLASS;
        StringBuilder quals = new StringBuilder();
        if (typeDef.isDistinct()) {
            quals.append("distinct ");
        }
        if (typeDef.isReadOnly()) {
            quals.append("readonly ");
        }
        if (typeDef.isIsolated()) {
            quals.append("isolated ");
        }
        quals.append(switch (typeDef.role()) {
            case CLIENT -> "client ";
            case SERVICE -> "service ";
            case PLAIN -> "";
        });

        List<String> lines = new ArrayList<>();
        if (typeDef.isDeprecated()) {
            lines.add("@deprecated");
        }
        lines.add(isClass
                ? PUBLIC + quals + "class " + typeDef.name() + " {"
                : PUBLIC + "type " + typeDef.name() + " " + quals + "object {");
        lines.addAll(renderMembers(typeDef.fields(), typeDef.methods()));
        lines.add(isClass ? "}" : "};");
        return Signatures.renderDescription(typeDef.description()) + String.join("\n", lines);
    }

    /**
     * The members of an object body: its fields, then its methods.
     *
     * <p>Fields are contiguous, as a record's are; a blank line separates each method from what precedes it —
     * between members, never under the header.
     */
    public static List<String> renderMembers(List<RecordField> fields, List<Fn> methods) {
        List<String> lines = new ArrayList<>();
        for (RecordField field : fields) {
            lines.add(renderRecordField(field, Owner.OBJECT));
        }
        for (Fn fn : methods) {
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add(Signatures.renderMemberFunction(fn, "    ", Signatures.Detail.FULL));
        }
        return lines;
    }

    private static String renderEnum(TypeDef.Enumeration typeDef) {
        List<String> members = new ArrayList<>();
        for (TypeDef.Enumeration.Member member : typeDef.members()) {
            // The comma belongs to the member's own line, so a described member's doc comment stays above the
            // name it documents rather than after the previous member's separator.
            members.add(Signatures.renderDocComment(member.description(), "    ") + "    " + member.name());
        }
        return Signatures.renderDescription(typeDef.description())
                + PUBLIC + "enum " + typeDef.name() + " {\n" + String.join(",\n", members) + "\n}";
    }

    // `// Unknown type:` when the descriptor could not be encoded: dropping hides the name, `type X ;` does not
    // parse.
    private static String renderAlias(TypeDef.Alias typeDef) {
        String description = Signatures.renderDescription(typeDef.description());
        if (typeDef.type().name().isEmpty()) {
            return description + "// Unknown type: " + typeDef.name();
        }
        List<Signatures.ExternalLink> links = Signatures.collectExternalLinks(typeDef.type());
        String type = Signatures.applyPrefixToTypeName(typeDef.type().name(), links);
        return description + PUBLIC + "type " + typeDef.name() + " " + type + ";";
    }

    // The initialiser is required (`public final T X;` does not compile); Central's value is the source's own.
    private static String renderVariable(TypeDef.Variable typeDef) {
        List<Signatures.ExternalLink> links = Signatures.collectExternalLinks(typeDef.varType());
        String type = Signatures.applyPrefixToTypeName(typeDef.varType().name(), links);
        String initialiser = typeDef.initialiser().isEmpty() ? "" : " = " + typeDef.initialiser();
        return Signatures.renderDescription(typeDef.description())
                + PUBLIC + "final " + type + " " + typeDef.name() + initialiser + ";";
    }

    // Central sends a nameless type node for a constant the source declares untyped
    // (`public const EXECUTION_FAILED = -3;`); printing a type there would be a guess.
    private static String renderConstant(TypeDef.Constant typeDef) {
        String type = typeDef.varType().name();
        boolean needsQuotes = "string".equals(type) && !QUOTED.matcher(typeDef.value()).matches();
        String value = needsQuotes ? "\"" + typeDef.value() + "\"" : typeDef.value();
        String declared = type.isEmpty() ? "" : type + " ";
        return Signatures.renderDescription(typeDef.description())
                + PUBLIC + "const " + declared + typeDef.name() + " = " + value + ";";
    }

    private static String renderError(TypeDef.ErrorDef typeDef) {
        TypeRef baseRef = typeDef.base().orElse(null);
        List<Signatures.ExternalLink> links = Signatures.collectExternalLinks(baseRef);
        String base = baseRef == null ? "error" : Signatures.applyPrefixToTypeName(baseRef.name(), links);
        String narrowed = baseRef != null && typeDef.detailRecord() ? "error<" + base + ">" : base;
        String distinct = typeDef.isDistinct() ? "distinct " : "";
        return Signatures.renderDescription(typeDef.description())
                + PUBLIC + "type " + typeDef.name() + " " + distinct + narrowed + ";";
    }
}
