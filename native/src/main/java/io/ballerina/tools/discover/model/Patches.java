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

package io.ballerina.tools.discover.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Per-package corrections, applied after the IR is built.
 *
 * <p>Every one of these exists because Central OMITS something the package publishes, and an agent that
 * trusts the docs writes code that fails to build. They are deliberately narrow — keyed on the exact library
 * name, no pattern matching — so a package nobody has had trouble with passes through untouched.
 *
 * <p>"Omits" is the whole admission criterion. A correction has to name the fact Central drops, name the
 * oracle that has it, and be pinned in both directions — what it must change AND what it must leave alone. A
 * correction that is merely an improvement on what the package chose to publish does not belong here, and
 * neither does one whose failure mode is silence.
 *
 * @since 0.1.0
 */
public final class Patches {

    /**
     * The intersection member Central publishes in place of {@code error<Something>}: a bare {@code error},
     * last in the intersection, with or without the enclosing parentheses.
     *
     * <p>Both spellings occur and the difference is Central's, not a rendering artefact:
     * {@code isParenthesisedType} is true for the three http declarations filed under {@code errors} and
     * false for the two filed under {@code intersectionTypes}.
     *
     * <p>The spacing around {@code &} is {@link FromCentral}'s — this matches a RENDERED intersection, so
     * the two must move together; a mismatch fails silently, which is why {@code PatchesTest} pins every row.
     */
    private static final Pattern TRAILING_BARE_ERROR = Pattern.compile(" & error(\\))?$");

    /**
     * The type argument Central drops, keyed by the declaration that carries it.
     *
     * <p>Central publishes the intersection and omits the argument, and nothing in the payload can recover
     * it: an {@code errors[]} item has no key for it, and the detail records appear only as declarations of
     * their own with no link back. So this is a hand-maintained table, and its shape is dictated by the
     * facts rather than chosen — keyed on {@code (library, declaration)} because one row per package is
     * wrong: {@code ballerina/http} needs three different arguments, and two of them belong to declarations
     * Central files under {@code intersectionTypes}, which the reader renders as aliases rather than errors.
     *
     * <p>Every row is read off the SAME package version the corresponding fixture records — http 2.16.6,
     * kafka 4.6.5, graphql 1.17.0. The table rots when those packages release; the two-direction pins in
     * {@code PatchesTest} keep that from happening silently.
     *
     * <p>What is deliberately absent matters as much: http's 51 errors whose {@code detailType} is a plain
     * named base carry no detail record at all, and attaching one would make every {@code SslError}
     * advertise an {@code int statusCode} it does not have.
     *
     * @param library the package the pin was read from, as {@code org/name}
     * @param declaration the error type the argument belongs to
     * @param argument the detail record argument, as source text
     */
    private record DetailArgument(String library, String declaration, String argument) { }

    private static final List<DetailArgument> ERROR_DETAIL_ARGUMENTS = List.of(
            new DetailArgument("ballerina/http", "ApplicationResponseError", "Detail"),
            new DetailArgument("ballerina/http", "ClientRequestError", "Detail"),
            new DetailArgument("ballerina/http", "RemoteServerError", "Detail"),
            new DetailArgument("ballerina/http", "LoadBalanceActionError", "LoadBalanceActionErrorData"),
            new DetailArgument(
                    "ballerina/http", "StatusCodeResponseBindingError", "StatusCodeBindingErrorDetail"),
            new DetailArgument("ballerinax/kafka", "PayloadBindingError", "PartitionOffset"),
            new DetailArgument("ballerinax/kafka", "PayloadValidationError", "PartitionOffset"),
            // graphql's four are anonymous record descriptors rather than names, which is why the table
            // holds the argument's SPELLING and not a type name to look up.
            new DetailArgument("ballerina/graphql", "HttpError", "record {| anydata body; |}"),
            new DetailArgument(
                    "ballerina/graphql", "InvalidDocumentError", "record {| ErrorDetail[]? errors; |}"),
            new DetailArgument(
                    "ballerina/graphql", "PayloadBindingError", "record {| ErrorDetail[]? errors; |}"),
            new DetailArgument("ballerina/graphql", "ServerError",
                    "record {| json? data?; ErrorDetail[] errors; map<json>? extensions?; |}"));

    /**
     * Order matters in one place: every other correction keys on the library name, so
     * {@link #changeClientConfigName} — which CHANGES that name — runs last.
     */
    private static final List<UnaryOperator<Library>> PATCHES = List.of(
            Patches::restoreErrorDetailArguments,
            Patches::declareSlackOkTrue,
            Patches::changeClientConfigName);

    private Patches() {
    }

    public static Library applyPatches(Library library) {
        Library current = library;
        for (UnaryOperator<Library> patch : PATCHES) {
            current = patch.apply(current);
        }
        return current;
    }

    // Central publishes `distinct (ClientError & error<Detail>)` without the type argument. Gated on the
    // declaration name: a shape-only rule would also hit http's StatusCodeBindingClientRequestError.
    private static Library restoreErrorDetailArguments(Library library) {
        Map<String, String> arguments = ERROR_DETAIL_ARGUMENTS.stream()
                .filter(row -> row.library().equals(library.name()))
                .collect(Collectors.toMap(DetailArgument::declaration, DetailArgument::argument));
        if (arguments.isEmpty()) {
            return library;
        }
        return library.withTypeDefs(library.typeDefs().stream()
                .map(typeDef -> restoreDetail(typeDef, arguments.get(typeDef.name())))
                .toList());
    }

    private static TypeDef restoreDetail(TypeDef typeDef, String argument) {
        if (argument == null) {
            return typeDef;
        }
        if (typeDef instanceof TypeDef.ErrorDef error && error.base().isPresent()) {
            return withArgument(error.base().get(), argument)
                    .map(base -> (TypeDef) new TypeDef.ErrorDef(
                            error.name(), error.description(), error.isDistinct(), Optional.of(base)))
                    .orElse(typeDef);
        }
        if (typeDef instanceof TypeDef.Alias alias) {
            return withArgument(alias.type(), argument)
                    .map(type -> (TypeDef) new TypeDef.Alias(alias.name(), alias.description(), type))
                    .orElse(typeDef);
        }
        return typeDef;
    }

    private static Optional<TypeRef> withArgument(TypeRef base, String argument) {
        Matcher matcher = TRAILING_BARE_ERROR.matcher(base.name());
        if (!matcher.find()) {
            return Optional.empty();
        }
        String restored = base.name().substring(0, matcher.start()) + " & error<" + argument + ">"
                + (matcher.group(1) == null ? "" : ")");
        return Optional.of(new TypeRef(restored, base.links()));
    }

    // Central publishes no declaration for OkTrueDef (`public type OkTrueDef true;`) yet references it 179 times.
    private static Library declareSlackOkTrue(Library library) {
        if (!"ballerinax/slack".equals(library.name())
                || library.typeDefs().stream().anyMatch(typeDef -> "OkTrueDef".equals(typeDef.name()))) {
            return library;
        }
        List<TypeDef> combined = new ArrayList<>();
        combined.add(new TypeDef.Alias("OkTrueDef", "", new TypeRef("true")));
        combined.addAll(library.typeDefs());
        return library.withTypeDefs(List.copyOf(combined));
    }

    // `client` is a keyword, so the import needs `'client.config`; FromCentral encodes the same fact for links.
    private static Library changeClientConfigName(Library library) {
        return "ballerinax/client.config".equals(library.name())
                ? library.withName("ballerinax/'client.config")
                : library;
    }

}
