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

import java.util.List;
import java.util.Optional;

/**
 * A module-level type declaration.
 *
 * <p>The renderer switches over these cases with no {@code default}, so a Ballerina shape nobody
 * renders is a compile error rather than a silently dropped declaration.
 *
 * <p>{@link Alias} carries every one of Central's seventeen alias categories, because a Ballerina type
 * alias is one shape however Central files it, and {@link ObjectDef} carries its four object categories for
 * the same reason. A name whose descriptor the reader could not encode is an {@code Alias} with an empty
 * type — it renders as a comment naming itself.
 *
 * @since 0.1.0
 */
public sealed interface TypeDef {

    String name();

    String description();

    /**
     * A record. {@code isClosed} is {@code record {| |}} — the form that REJECTS extra fields, and the one
     * fact a caller building a value needs, since an open record accepts anything and a closed one does not.
     *
     * @param name the record type's name
     * @param description the type's own documentation, verbatim
     * @param isClosed whether the record rejects fields beyond the ones declared
     * @param isDeprecated whether Central flagged the type deprecated
     * @param fields the record's own fields
     */
    record Rec(
            String name,
            String description,
            boolean isClosed,
            boolean isDeprecated,
            List<RecordField> fields) implements TypeDef {

        /**
         * The same record with different members.
         *
         * <p>Exists so a patch cannot lose {@code isClosed} by rebuilding: an inclusive record descriptor
         * holding a rest field is a syntax error.
         */
        public Rec withFields(List<RecordField> replacement) {
            return new Rec(name, description, isClosed, isDeprecated, replacement);
        }
    }

    /**
     * An enum, whose members carry a description each.
     *
     * <p>What Central publishes for NO member is its VALUE: most of postgresql's members have an explicit one
     * that differs from the member name, so {@code enum SSLMode { … VERIFY_CA }} is a legal declaration that is
     * not quite the real one.
     *
     * @param name the enum type's name
     * @param description the type's own documentation, verbatim
     * @param members the enum's members, each with its own description
     */
    record Enumeration(String name, String description, List<Member> members) implements TypeDef {

        public record Member(String name, String description) { }

        public List<String> memberNames() {
            return members.stream().map(Member::name).toList();
        }
    }

    /**
     * A type alias: a name bound to a type descriptor.
     *
     * <p>ONE case for all seventeen categories Central models separately — string, integer, decimal, boolean,
     * simple-name-reference, array, union, intersection, anydata, any, tuple, function, typedesc, map,
     * stream, table and xml. They differ in nothing a declaration shows: {@code type Id int|string;} and
     * {@code type TsDef string;} are the same shape with different right-hand sides, and Central already
     * publishes each one's descriptor in full.
     *
     * <p>{@code type.name()} empty means the reader could not encode the descriptor. That still renders the
     * name, as a comment, because the declaration exists and a caller may need to know it does.
     *
     * @param name the alias's own name
     * @param description the type's own documentation, verbatim
     * @param type the descriptor it is bound to, or empty when the reader could not encode it
     */
    record Alias(String name, String description, TypeRef type) implements TypeDef { }

    record Constant(String name, String description, String value, TypeRef varType) implements TypeDef { }

    /**
     * A module-level {@code public final} variable: a value the package computes once and exports.
     *
     * <p>Distinct from {@link Constant}, which is {@code const} — a compile-time constant whose value is part
     * of its type. These are not: {@code public final readonly & Continue CONTINUE = {};} has a value the
     * package builds, so its initialiser is not something a caller can be given. Central publishes them under
     * {@code variables}; {@code http:CONTINUE} compiles from another module.
     *
     * @param name the variable's name
     * @param description the variable's own documentation, verbatim
     * @param varType the variable's declared type
     * @param initialiser the default Central publishes, which for most of these is {@code {}} and is therefore
     *     not the value; {@link Defaults} decides whether it is writable
     * @param isReadOnly whether the variable's type is {@code readonly}
     */
    record Variable(String name, String description, TypeRef varType, String initialiser, boolean isReadOnly)
            implements TypeDef { }

    /**
     * A class, an object type, a service type or a listener — the four categories Central publishes with one
     * shape, and one IR case, because a Ballerina object declaration is one shape too.
     *
     * <p>{@link Form} and {@link Role} are orthogonal and both load-bearing. {@code Form} is whether the
     * declaration is instantiable: a {@code class} is, an {@code object} type is a contract that is not, and
     * printing the one as the other told a caller to {@code new} an abstract type. {@code Role} is whether its
     * methods are reached with {@code ->} or with {@code .}, which is the difference between
     * {@code db->query(q)} compiling and not.
     *
     * <p>Neither is a flag Central sets. {@code isClient} does not exist in the payload and {@code isService}
     * exists but is false on all 230 objects in the corpus, so both are DERIVED, and derived from the grammar
     * rather than guessed: a {@code remote} method is only legal in a client or service object, and a service
     * type is a service object by definition — which is what {@code http:Service} and {@code kafka:Service}
     * are declared as in their own sources.
     *
     * <p>{@code methods} is Central's {@code methods} array and nothing else. Its {@code otherMethods},
     * {@code lifeCycleMethods} and {@code initMethod} are all SUBSETS of it — measured across every object in
     * the corpus, with no exception — so they are groupings of one list rather than four lists, and reading
     * them all would print {@code init} twice.
     *
     * @param name the object's name
     * @param description the type's own documentation, verbatim
     * @param form whether the declaration is instantiable
     * @param role how the object's methods are called
     * @param isDistinct whether Central declared the type {@code distinct}
     * @param isReadOnly whether Central declared the type {@code readonly}
     * @param isIsolated whether Central declared the type {@code isolated}
     * @param isDeprecated whether Central flagged the type deprecated
     * @param fields the object's own fields
     * @param methods every method Central published for it, including its lifecycle and init methods
     */
    record ObjectDef(
            String name,
            String description,
            Form form,
            Role role,
            boolean isDistinct,
            boolean isReadOnly,
            boolean isIsolated,
            boolean isDeprecated,
            List<RecordField> fields,
            List<Fn> methods) implements TypeDef {

        /** {@code class X { }} versus {@code type X object { };} — instantiable versus a contract. */
        public enum Form { CLASS, OBJECT_TYPE }

        /** How the object's methods are called: plain, {@code ->} on a client, or attached as a service. */
        public enum Role { PLAIN, CLIENT, SERVICE }
    }

    /**
     * An error declaration, with the facts that make error handling learnable from the document instead
     * of guessable.
     *
     * <p>Central publishes one key, {@code detailType}, for two different things, and which one it is
     * decides the syntax:
     *
     * <pre>
     *   category "errors"   supertype       type SslError distinct ClientError;
     *   category "records"  detail record   type FHIRServerError distinct error&lt;FHIRServerErrorDetails&gt;;
     * </pre>
     *
     * <p>{@code base} therefore cannot be named for the wire, and {@code detailRecord} says which reading
     * applies. Assuming a supertype is not safe: {@code ballerinax/health.clients.fhir} publishes records, and
     * {@code distinct FHIRServerErrorDetails} does not compile.
     *
     * @param name the error type's name
     * @param description the type's own documentation, verbatim
     * @param isDistinct whether Central declared the type {@code distinct}
     * @param base the error's supertype or detail record, or empty when Central published none
     * @param detailRecord whether {@code base} is a detail record rather than a supertype
     */
    record ErrorDef(
            String name, String description, boolean isDistinct, Optional<TypeRef> base, boolean detailRecord)
            implements TypeDef {

        /** An error whose {@code base}, if any, is a supertype rather than a detail record. */
        public ErrorDef(String name, String description, boolean isDistinct, Optional<TypeRef> base) {
            this(name, description, isDistinct, base, false);
        }
    }
}
