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

package io.ballerina.tools.discover.constructs;

import java.util.ArrayList;
import java.util.List;

/**
 * The construct matrix: one case per Ballerina syntax dimension.
 *
 * <p>Read this as a table of claims about the language, not about any package. Each case is the smallest
 * Central payload that describes one construct, and its two expectations are what we print and what the
 * construct's own declaration is. The payload shapes are copied from real Central payloads — the flag on the
 * node Central really sets, at the depth Central really sets it — so a case cannot pass by testing a shape
 * Central never sends.
 *
 * <p>Kept as one file on purpose. It is a coverage table, and a reader checking whether closed records,
 * rest fields and multi-dimensional arrays are all accounted for should see them in one place.
 *
 * @since 0.1.0
 */
public final class Constructs {

    /** A same-module record reference, the category Central uses for one. */
    private static final String RECORDS = "records";

    private Constructs() {
    }

    public static List<Construct> all() {
        List<Construct> cases = new ArrayList<>();
        cases.addAll(records());
        cases.addAll(fields());
        cases.addAll(foreignNames());
        cases.addAll(types());
        cases.addAll(errors());
        cases.addAll(enumsAndConstants());
        cases.addAll(objects());
        cases.addAll(callables());
        cases.addAll(services());
        cases.addAll(annotations());
        return List.copyOf(cases);
    }

    private static List<Construct> records() {
        return List.of(
                Construct.faithful(
                        "records/open",
                        "an open record with one required field",
                        Payload.pkg().with(RECORDS,
                                Decl.record("Config", Decl.field("name", Node.builtin("string")))),
                        """
                        // --- Types ---

                        public type Config record {
                            string name;
                        };"""),

                Construct.faithful(
                        "records/closed",
                        "a closed record is `record {| |}`, and only a closed record rejects extra fields",
                        Payload.pkg().with(RECORDS,
                                Decl.record("Config", Decl.field("name", Node.builtin("string")))
                                        .on("isClosed")),
                        """
                        // --- Types ---

                        public type Config record {|
                            string name;
                        |};"""),

                Construct.faithful(
                        "records/doc-comment",
                        "a doc comment binds to the declaration below it, with no blank line between",
                        Payload.pkg().with(RECORDS,
                                Decl.record("Config", Decl.field("name", Node.builtin("string")))
                                        .with("description", "A configuration.")),
                        """
                        // --- Types ---

                        # A configuration.
                        public type Config record {
                            string name;
                        };"""),

                // Pinned beside the field-level doc path so a shared helper cannot fix one and break the other.
                Construct.faithful(
                        "records/multi-line-doc-comment",
                        "a description that spans lines commits every line to a `#`, at declaration level too",
                        Payload.pkg().with(RECORDS,
                                Decl.record("Config", Decl.field("name", Node.builtin("string")))
                                        .with("description", "What this holds.\nAnd why.")),
                        """
                        // --- Types ---

                        # What this holds.
                        # And why.
                        public type Config record {
                            string name;
                        };"""),

                Construct.faithful(
                        "records/deprecated",
                        "a deprecated record carries `@deprecated`, which is what warns a caller off it",
                        Payload.pkg().with(RECORDS,
                                Decl.record("Legacy", Decl.field("name", Node.builtin("string")))
                                        .on("isDeprecated")),
                        """
                        // --- Types ---

                        @deprecated
                        public type Legacy record {
                            string name;
                        };"""),

                Construct.faithful(
                        "records/no-fields",
                        "a record Central describes no fields for still declares the name",
                        Payload.pkg().with(RECORDS, Decl.record("Empty")),
                        """
                        // --- Types ---

                        public type Empty record {
                        };"""));
    }

    private static List<Construct> fields() {
        return List.of(
                Construct.faithful(
                        "fields/optional",
                        "`isOptional` is `field?` — the field may be absent",
                        record(Decl.field("name", Node.builtin("string").on("isOptional"))),
                        body("    string name?;")),

                Construct.faithful(
                        "fields/nullable",
                        "`isNullable` is `T?` — the field is present and may hold ()",
                        record(Decl.field("name", Node.builtin("string").on("isNullable"))),
                        body("    string? name;")),

                Construct.faithful(
                        "fields/optional-and-nullable",
                        "the two are independent and both render",
                        record(Decl.field(
                                "name", Node.builtin("string").on("isNullable").on("isOptional"))),
                        body("    string? name?;")),

                Construct.faithful(
                        "fields/default-value",
                        "a defaulted field is `T name = value`",
                        record(Decl.field("count", Node.builtin("int")).with("defaultValue", "10")),
                        body("    int count = 10;")),

                Construct.faithful(
                        "fields/readonly",
                        "`readonly T f` is part of the field's type; without it the document says the field "
                                + "is assignable when it is not",
                        record(Decl.field("status", Node.builtin("int")).on("isReadOnly")),
                        body("    readonly int status;")),

                // Central publishes `isClosed: false` for rest-field records whose sources are `record {| … |}`,
                // so this case asserts BOTH halves: the member form and the closed braces the grammar requires.
                Construct.faithful(
                        "fields/rest",
                        "a rest field is `T...;`, and a record that has one is closed by the grammar",
                        record(Decl.field("", Node.structural()
                                        .on("isRestParam")
                                        .elementType(Node.builtin("anydata")))
                                .with("description", "Rest field")),
                        "// --- Types ---\n\npublic type Config record {|\n    anydata...;\n|};"),

                // `T...;` is legal only as the LAST member, so a rest field with declarations after it is
                // something no source wrote — it is Central copying an included record's implicit
                // `anydata...` in along with its members (ballerina/time:Civil arrives with two). Rendered, the
                // compiler rejects it with `more record fields after rest field`.
                Construct.faithful(
                        "fields/rest-stranded",
                        "a rest field that is not last was spliced in by a flattened inclusion, not declared",
                        record(
                                Decl.field("id", Node.builtin("int")),
                                Decl.field("", Node.structural()
                                                .on("isRestParam")
                                                .elementType(Node.builtin("anydata")))
                                        .with("description", "Rest field"),
                                Decl.field("name", Node.builtin("string"))),
                        body("    int id;", "    string name;")),

                Construct.faithful(
                        "fields/array",
                        "a one-dimensional array is `T[]`",
                        record(Decl.field("tags", Node.structural()
                                .on("isArrayType")
                                .with("arrayDimensions", 1)
                                .elementType(Node.builtin("string")))),
                        body("    string[] tags;")),

                Construct.faithful(
                        "fields/array-two-dimensional",
                        "`arrayDimensions` is the number of `[]` pairs; a 2-D array is not a 1-D array",
                        record(Decl.field("grid", Node.structural()
                                .on("isArrayType")
                                .with("arrayDimensions", 2)
                                .elementType(Node.builtin("string")))),
                        body("    string[][] grid;")),

                Construct.faithful(
                        "fields/deprecated",
                        "a deprecated field carries `@deprecated`",
                        record(Decl.field("path", Node.builtin("string")).on("isDeprecated")),
                        body("    @deprecated\n    string path;")),

                Construct.faithful(
                        "fields/multi-line-description",
                        "every line of a doc comment needs its own `#`; a bare continuation line is source",
                        record(Decl.field("name", Node.builtin("string"))
                                .with("description", "The name.\nSecond line.")),
                        body("    # The name.\n    # Second line.\n    string name;")),

                Construct.faithful(
                        "fields/padded-type-name",
                        "a singleton type name arrives with trailing whitespace and must be trimmed",
                        record(Decl.field("'type", Node.named("\"message\" ", "other"))),
                        body("    \"message\" 'type;")),

                // Most of slack's padded names sit inside a union or an inline record, where the padding lands
                // mid-expression; trimming only at the field's own type would leave them.
                Construct.faithful(
                        "fields/padded-union-member",
                        "padding inside a union member is trimmed too, or the type expression carries it "
                                + "mid-line",
                        record(Decl.field("plan", Node.structural()
                                .on("isAnonymousUnionType")
                                .members(Node.named("    \"\"", "other"), Node.named("\"plus\" ", "other")))),
                        body("    \"\"|\"plus\" plan;")),

                Construct.faithful(
                        "fields/inclusion",
                        "an included record is `*Other;` — one line that keeps the two declarations linked",
                        Payload.pkg().with(RECORDS,
                                Decl.record("Accepted", Decl.inclusion(
                                        Node.named("CommonResponse", RECORDS).members(
                                                Decl.member("mediaType", Node.builtin("string")),
                                                Decl.member("headers", Node.builtin("string")))))),
                        """
                        // --- Types ---

                        public type Accepted record {
                            *CommonResponse;
                        };"""),

                // Order is Central's, which is the SOURCE's: psql writes `database` and then
                // `*cdc:ListenerConfiguration`, and Central lists them that way.
                Construct.faithful(
                        "fields/inclusion-overwrites-declaration",
                        "a record's own field survives an inclusion, and both keep the position Central "
                                + "lists them in",
                        Payload.pkg().with(RECORDS,
                                Decl.record("Row",
                                        Decl.field("id", Node.builtin("int")),
                                        Decl.inclusion(Node.named("Base", RECORDS).members(
                                                Decl.member("id", Node.builtin("string")))))),
                        """
                        // --- Types ---

                        public type Row record {
                            int id;
                            *Base;
                        };"""),

                Construct.faithful(
                        "fields/cross-package-type",
                        "a name from another package is qualified with that package's alias",
                        record(Decl.field("message", Node.external("ballerinax", "googleapis.gmail", "Message"))),
                        body("    gmail:Message message;")));
    }

    private static List<Construct> foreignNames() {
        return List.of(
                Construct.faithful(
                        "fields/predeclared-langlib",
                        "a langlib type whose prefix is a basic-type keyword needs no import",
                        record(Decl.field("size", Node.external("ballerina", "lang.int", "Signed32"))),
                        body("    int:Signed32 size;")),
                Construct.faithful(
                        "fields/langlib-needing-an-import",
                        "a langlib type whose prefix is NOT a basic-type keyword is still qualified: `value:` is "
                                + "an undefined module without the import",
                        record(Decl.field("held", Node.external("ballerina", "lang.value", "Cloneable"))),
                        body("    value:Cloneable held;")),
                Construct.faithful(
                        "fields/reserved-word-module-path",
                        "a module path segment that is a Ballerina keyword is quoted in the import advice",
                        record(Decl.field("config",
                                Node.external("ballerinax", "client.config", "ConnectionConfig"))),
                        body("    config:ConnectionConfig config;")),
                Construct.faithful(
                        "fields/non-default-module",
                        "a name from a module that is not its package's default is named as a module, because "
                                + "that is what an import takes",
                        record(Decl.field("auth", Node.external("ballerinax", "aws.auth", "AuthConfig"))),
                        body("    auth:AuthConfig auth;")));
    }

    private static List<Construct> types() {
        return List.of(
                Construct.faithful(
                        "types/union-alias",
                        "a union alias is `type X A|B;`",
                        Payload.pkg().with("unionTypes",
                                Decl.alias("Id", Node.builtin("int"), Node.builtin("string"))),
                        """
                        // --- Types ---

                        public type Id int|string;"""),

                Construct.faithful(
                        "types/intersection-alias",
                        "Ballerina spells an intersection `A & B`; `A|B` is the opposite type",
                        Payload.pkg().with("intersectionTypes",
                                Decl.intersectionAlias("Frozen",
                                        Node.builtin("any"), Node.builtin("readonly"))),
                        """
                        // --- Types ---

                        public type Frozen any & readonly;"""),

                Construct.faithful(
                        "types/anonymous-union",
                        "an inline union in a field position renders as its members",
                        record(Decl.field("id", Node.structural()
                                .on("isAnonymousUnionType")
                                .members(Node.builtin("int"), Node.builtin("string")))),
                        body("    int|string id;")),

                Construct.faithful(
                        "types/anonymous-union-array",
                        "an array of a union needs parentheses: `(string|())[]`, never `string|()[]`",
                        record(Decl.field("values", Node.structural()
                                .on("isArrayType")
                                .with("arrayDimensions", 1)
                                .elementType(Node.structural()
                                        .on("isAnonymousUnionType")
                                        .members(Node.builtin("string"), Node.builtin("()"))))),
                        body("    (string|())[] values;")),

                Construct.faithful(
                        "types/parenthesised-union-array",
                        "when Central does flag the parenthesis, the same shape comes out right — which is "
                                + "what makes the unflagged case a defect rather than a missing feature",
                        record(Decl.field("values", Node.structural()
                                .on("isArrayType")
                                .with("arrayDimensions", 1)
                                .elementType(Node.structural()
                                        .on("isParenthesisedType")
                                        .elementType(Node.structural()
                                                .on("isAnonymousUnionType")
                                                .members(Node.builtin("string"), Node.builtin("()")))))),
                        body("    (string|())[] values;")),

                Construct.faithful(
                        "types/map-constrained",
                        "`map<T>` carries its constraint",
                        record(Decl.field("headers", Node.named("map", "map")
                                .constraint(Node.builtin("string")))),
                        body("    map<string> headers;")),

                Construct.faithful(
                        "types/map-nullable",
                        "a nullable map is `map<T>?`; dropping the `?` makes a default of () not typecheck",
                        record(Decl.field("headers", Node.named("map", "map")
                                .on("isNullable")
                                .constraint(Node.builtin("string")))),
                        body("    map<string>? headers;")),

                Construct.faithful(
                        "types/map-array",
                        "an array of maps is `map<T>[]`",
                        record(Decl.field("headers", Node.named("map", "map")
                                .on("isArrayType")
                                .with("arrayDimensions", 1)
                                .constraint(Node.builtin("string")))),
                        body("    map<string>[] headers;")),

                Construct.faithful(
                        "types/table-constrained",
                        "a bare `table` has no meaning — the row type is part of the type descriptor",
                        record(Decl.field("rows", Node.named("table", "table")
                                .constraint(Node.named("Row", RECORDS)))),
                        body("    table<Row> rows;")),

                Construct.faithful(
                        "types/stream-two-members",
                        "`stream<T, E>` has a space after the comma, as every other type argument list does",
                        record(Decl.field("results", Node.named("stream", "stream")
                                .members(Node.named("Row", RECORDS), Node.builtin("error?")))),
                        body("    stream<Row, error?> results;")),

                Construct.faithful(
                        "types/typedesc",
                        "`typedesc<T>` keeps its argument",
                        record(Decl.field("rowType", Node.structural()
                                .on("isTypeDesc")
                                .elementType(Node.builtin("anydata")))),
                        body("    typedesc<anydata> rowType;")),

                Construct.faithful(
                        "types/tuple-alias",
                        "a tuple is `[A, B]`, and `tupleTypes` is the category Central files it under",
                        Payload.pkg().with("tupleTypes",
                                Decl.tupleAlias("TopicPartitionTimestamp",
                                        Node.named("TopicPartition", RECORDS), Node.builtin("int"))),
                        """
                        // --- Types ---

                        public type TopicPartitionTimestamp [TopicPartition, int];"""),

                Construct.faithful(
                        "types/string-alias",
                        "a named alias of a builtin is `type X string;`, not a comment saying the name is "
                                + "unknown",
                        Payload.pkg().with("stringTypes",
                                Decl.alias("TsDef", Node.builtin("string"))
                                        .with("description", "A Slack timestamp.")),
                        """
                        // --- Types ---

                        # A Slack timestamp.
                        public type TsDef string;"""),

                Construct.faithful(
                        "types/simple-name-reference",
                        "a re-exported name resolves to the type it references, which Central puts in "
                                + "`memberTypes`",
                        Payload.pkg().with("simpleNameReferenceTypes",
                                Decl.alias("ClientError",
                                        Node.external("ballerina", "http", "ClientError"))),
                        """
                        // --- Types ---

                        public type ClientError http:ClientError;"""),

                Construct.faithful(
                        "types/inline-record-named-fields",
                        "an inline record whose members carry names renders its fields",
                        record(Decl.field("meta", Node.named("", "inline_record").members(
                                Decl.member("cursor", Node.builtin("string")),
                                Decl.member("count", Node.builtin("int"))))),
                        body("    record {string cursor; int count; } meta;")),

                // ballerina/http's `ClientSecureSocket.protocol` is `record {| … |}` in source and arrives as
                // `inline_closed_record`; printed open, it claimed the record accepts fields it rejects.
                Construct.faithful(
                        "types/inline-record-closed",
                        "an `inline_closed_record` is an exclusive record, `record {| … |}`",
                        record(Decl.field("meta", Node.named("", "inline_closed_record").members(
                                Decl.member("cursor", Node.builtin("string")),
                                Decl.member("count", Node.builtin("int"))))),
                        body("    record {|string cursor; int count; |} meta;")),

                // No recorded payload sets this flag on an inline member — slack 5.0.1's `last_read?` arrives
                // without it — so this pins only where the `?` goes when it is set: after the name, since
                // `T? f` is a required nilable field and `T f?` an optional one.
                Construct.faithful(
                        "types/inline-record-optional-field",
                        "an optional inline field is `T f?;`, not the nilable `T? f;`",
                        record(Decl.field("meta", Node.named("", "inline_record").members(
                                Decl.member("cursor", Node.builtin("string")).on("isOptional"),
                                Decl.member("count", Node.builtin("int"))))),
                        body("    record {string cursor?; int count; } meta;")),

                // ballerina/log's `Module`: named fields, then a nameless member whose element type is the
                // rest descriptor. `T...;` is legal only in an exclusive record.
                Construct.faithful(
                        "types/inline-record-rest",
                        "an inline record's rest member is `T...;`, inside `record {| … |}`",
                        record(Decl.field("meta", Node.named("", "inline_record").members(
                                Decl.member("name", Node.builtin("string")),
                                Node.structural().elementType(Node.structural()
                                        .on("isRestParam")
                                        .elementType(Node.builtin("anydata")))))),
                        body("    record {|string name; anydata...; |} meta;")),

                // ballerinax/github's rest-only inline records (`record {|string...;|}`): collapsed to
                // `record {}`, it would accept any field of any anydata type.
                Construct.faithful(
                        "types/inline-record-rest-only",
                        "an inline record holding only a rest member is `record {|T...; |}`, not `record {}`",
                        record(Decl.field("meta", Node.named("", "inline_record").members(
                                Node.structural().elementType(Node.structural()
                                        .on("isRestParam")
                                        .elementType(Node.builtin("string")))))),
                        body("    record {|string...; |} meta;")),

                Construct.faithful(
                        "types/unencoded-node",
                        "a type node the reader cannot encode must not be reported as an anonymous record — "
                                + "`record {}` is a claim about the type, not an admission of ignorance",
                        // `const MAX = 100;` and not `const int MAX = 100;`: Central sends a type node with
                        // a category and no name for exactly the constants whose source declares no type
                        // either, because Ballerina infers it. Inventing `int` from the value would be a
                        // guess dressed as a fact.
                        Payload.pkg().with("constants",
                                Decl.constant("MAX", "100", Node.structural())),
                        """
                        // --- Types ---

                        public const MAX = 100;"""));
    }

    private static List<Construct> errors() {
        return List.of(
                Construct.faithful(
                        "errors/distinct-with-base",
                        "`distinct` plus a base is the subtype relation `e is X` depends on",
                        Payload.pkg().with("errors",
                                Decl.error("SslError", true).detail(Node.named("ClientError", "errors"))),
                        errorBody("public type SslError distinct ClientError;")),

                Construct.faithful(
                        "errors/distinct-no-base",
                        "an error at the top of its own hierarchy narrows the language's `error`",
                        Payload.pkg().with("errors", Decl.error("Error", true)),
                        errorBody("public type Error distinct error;")),

                Construct.faithful(
                        "errors/plain-no-base",
                        "a non-distinct error with no base is still `error`",
                        Payload.pkg().with("errors", Decl.error("Plain", false)),
                        errorBody("public type Plain error;")),

                Construct.faithful(
                        "errors/intersection-keeps-ampersand",
                        "an error base that is an intersection keeps its `&` — the SAME shape that renders "
                                + "as `|` when it arrives through `intersectionTypes`, which is what places "
                                + "HTTP-01 at one render path rather than in the type model",
                        Payload.pkg().with("errors",
                                Decl.error("Wrapped", true)
                                        .detail(Decl.detailIntersection(Node.named("Error", "errors")))),
                        errorBody("public type Wrapped distinct (Error & error);")),

                // Named after a package rather than a syntax: the argument is not in the payload — an
                // `errors[]` item has no key for it and nothing links an error to its detail record — so only
                // the correction table, which keys on the package, can answer it.
                Construct.faithful(
                        "errors/detail-type-argument",
                        "the detail record is the type argument `error<Detail>`, and it is what makes "
                                + "`e.detail().field` reachable at all",
                        Payload.pkg("ballerinax", "kafka").with("errors",
                                Decl.error("PayloadBindingError", true)
                                        .detail(Decl.detailIntersection(Node.named("Error", "errors")))),
                        errorBody("public type PayloadBindingError distinct (Error & error<PartitionOffset>);")),

                // The one correction that repairs a construct rather than papering over it, pinned here so a
                // rewrite of `Patches.java` cannot drop it silently.
                Construct.faithful(
                        "errors/detail-restored-for-http",
                        "`restoreErrorDetailArguments` puts back the `<Detail>` argument Central stripped, "
                                + "which is what makes `e.detail().statusCode` reachable",
                        Payload.pkg("ballerina", "http").with("errors",
                                Decl.error("ApplicationResponseError", true)
                                        .detail(Decl.detailIntersection(
                                                Node.named("ClientError", "errors")))),
                        errorBody("public type ApplicationResponseError distinct (ClientError & error<Detail>);"))
                        .inSection("Types"));
    }

    private static List<Construct> enumsAndConstants() {
        return List.of(
                Construct.faithful(
                        "enums/members",
                        "an enum lists its members",
                        Payload.pkg().with("enums", Decl.enumeration(
                                "Colour", Decl.named("RED"), Decl.named("GREEN"))),
                        """
                        // --- Types ---

                        public enum Colour {
                            RED,
                            GREEN
                        }"""),

                // The description Central does publish for a member.
                Construct.faithful(
                        "enums/member-description",
                        "a described member carries its doc comment, which is the only thing Central "
                                + "publishes about a member beyond the name",
                        Payload.pkg().with("enums", Decl.enumeration("Plugin",
                                Decl.named("PGOUTPUT")
                                        .with("description", "The standard plug-in in PostgreSQL 10+"),
                                Decl.named("DECODERBUFS"))),
                        """
                        // --- Types ---

                        public enum Plugin {
                            # The standard plug-in in PostgreSQL 10+
                            PGOUTPUT,
                            DECODERBUFS
                        }"""),

                // Stays open because no amount of reading fixes it: the strings `VERIFY-CA`, `pgoutput` and
                // `all_tables` appear nowhere in postgresql's payload or on Central's own rendered page. Read
                // literally, `enum SSLMode { … VERIFY_CA }` says the singleton is "VERIFY_CA", and it is
                // "VERIFY-CA".
                Construct.broken(
                        "enums/member-values",
                        "an enum member's value is not its name, and Central publishes no member's value",
                        "PSQL-04",
                        Payload.pkg().with("enums", Decl.enumeration("Level",
                                Decl.named("DEBUG"), Decl.named("INFO"))),
                        """
                        // --- Types ---

                        public enum Level {
                            DEBUG,
                            INFO
                        }""",
                        """
                        // --- Types ---

                        public enum Level {
                            DEBUG = "debug",
                            INFO = "info"
                        }"""),

                Construct.faithful(
                        "constants/string-value-quoted-once",
                        "Central sends a string constant's value with its quotes, and a second pair is not "
                                + "valid Ballerina",
                        Payload.pkg().with("constants",
                                Decl.constant("AUTH", "\"PLAIN\"", Node.builtin("string"))),
                        """
                        // --- Types ---

                        public const string AUTH = "PLAIN";"""),

                Construct.faithful(
                        "constants/int-value-unquoted",
                        "a non-string constant is never quoted",
                        Payload.pkg().with("constants",
                                Decl.constant("PORT", "9092", Node.builtin("int"))),
                        """
                        // --- Types ---

                        public const int PORT = 9092;"""));
    }

    private static List<Construct> objects() {
        return List.of(
                Construct.faithful(
                        "objects/class-methods",
                        "a class's methods are its contract; an empty body says the type has none",
                        Payload.pkg().with("classes", Decl.client("Engine",
                                Decl.method("run").returns(Node.builtin("error?")))),
                        """
                        // --- Types ---

                        public class Engine {
                            function run() returns error?;
                        }"""),

                Construct.faithful(
                        "objects/class-description",
                        "the description is passed as a literal empty string, so a class loses its doc "
                                + "comment even where nothing else about it is read",
                        Payload.pkg().with("classes",
                                Decl.named("Engine").with("description", "The engine.")),
                        """
                        // --- Types ---

                        # The engine.
                        public class Engine {
                        }"""),

                Construct.faithful(
                        "objects/object-type",
                        "an object type is `type X object { … }`, not a class",
                        Payload.pkg().with("objectTypes", Decl.client("Queryable",
                                Decl.method("query").returns(Node.builtin("error?")))),
                        """
                        // --- Types ---

                        public type Queryable object {
                            function query() returns error?;
                        };"""),

                // Central publishes no `isClient` key at all, so the fact comes from the grammar: a
                // `remote` method is legal only in a client or service object. Without it the document says
                // `db.query()` where the language requires `db->query()`.
                Construct.faithful(
                        "objects/client-object",
                        "an object type with a remote method is a `client object`, which is why its methods "
                                + "are called with `->`",
                        Payload.pkg().with("objectTypes", Decl.client("Client",
                                Decl.method("query", Decl.param("q", Node.named("Query", RECORDS)))
                                        .on("isRemote")
                                        .returns(Node.builtin("error?")))),
                        """
                        // --- Types ---

                        public type Client client object {
                            remote function query(Query q) returns error?;
                        };"""),

                // Central's `isService` is present and FALSE even on http service types whose own source says
                // `distinct service object`. The category is the fact.
                Construct.faithful(
                        "objects/service-type",
                        "a service type is a `service object`, and `distinct` is why two service types with "
                                + "the same members are not interchangeable",
                        Payload.pkg().with("serviceTypes",
                                Decl.serviceType("Service").on("isDistinct")
                                        .with("description", "The service type.")),
                        """
                        // --- Types ---

                        # The service type.
                        public type Service distinct service object {
                        };"""),

                Construct.faithful(
                        "objects/qualified-class",
                        "`readonly` and `isolated` are part of what a class IS — a readonly class's fields "
                                + "cannot be assigned after construction",
                        Payload.pkg()
                                .with("classes", Decl.named("Frozen").on("isReadOnly").on("isIsolated")),
                        """
                        // --- Types ---

                        public readonly isolated class Frozen {
                        }"""),

                Construct.faithful(
                        "objects/class-fields",
                        "a class field carries `public` where a record field cannot, and an inclusion carries "
                                + "neither",
                        Payload.pkg().with("classes", Decl.record("StatusAccepted",
                                        Decl.inclusion(Node.named("Status", "objectTypes")),
                                        Decl.field("code", Node.builtin("int"))
                                                .with("description", "The response status code"))
                                .on("isReadOnly")),
                        """
                        // --- Types ---

                        public readonly class StatusAccepted {
                            *Status;
                            # The response status code
                            public int code;
                        }"""),

                // A class constructor is the only statement anywhere of how a value class is built.
                Construct.faithful(
                        "objects/constructor",
                        "a class's `init` is its constructor and belongs in its body; Central publishes it "
                                + "both under `initMethod` and in `methods`, and it must print once",
                        Payload.pkg().with("classes", Decl.client("IntegerValue",
                                        Decl.method("init", Decl.param("value", Node.builtin("int?"))
                                                        .with("defaultValue", "()"))
                                                .returns(Node.builtin("error?")))
                                .with("description", "An integer parameter value.")),
                        """
                        // --- Types ---

                        # An integer parameter value.
                        public class IntegerValue {
                            function init(int? value = ()) returns error?;
                        }"""));
    }

    private static List<Construct> callables() {
        return List.of(
                Construct.faithful(
                        "callables/remote-method",
                        "a remote method is `remote function name(params) returns T;`",
                        client(Decl.method("send", Decl.param("text", Node.builtin("string")))
                                .on("isRemote")
                                .returns(Node.builtin("error?"))),
                        clientBody("    remote function send(string text) returns error?;")),

                Construct.faithful(
                        "callables/resource-path-parameters",
                        "a path parameter is declared in the path and not repeated in the parameter list",
                        client(Decl.method("get")
                                .on("isResource")
                                .with("accessor", "get")
                                .with("resourcePath", "repos/[string owner]")
                                .returns(Node.builtin("json"))),
                        clientBody("    resource function get repos/[string owner]() returns json;")),

                Construct.faithful(
                        "callables/defaultable-parameter",
                        "a defaulted parameter is `T name = value`",
                        client(Decl.method("list", Decl.param("page", Node.builtin("int"))
                                        .with("defaultValue", "1"))
                                .on("isRemote")
                                .returns(Node.builtin("json"))),
                        clientBody("    remote function list(int page = 1) returns json;")),

                Construct.faithful(
                        "callables/no-return",
                        "`nil` is not a type name — a function returning nothing has no `returns` clause, "
                                + "or returns `()`",
                        client(Decl.method("close").on("isRemote")),
                        clientBody("    remote function close();")),

                Construct.faithful(
                        "callables/parameter-description",
                        "a parameter's description is the only place its units, format or allowed values "
                                + "are stated",
                        client(Decl.method("send", Decl.param("channel", Node.builtin("string"))
                                        .with("description", "The channel ID, not its name."))
                                .on("isRemote")
                                .returns(Node.builtin("error?"), "nil on success")),
                        clientBody("    # + channel - The channel ID, not its name.\n"
                                + "    # + return - nil on success\n"
                                + "    remote function send(string channel) returns error?;")),

                // The constructor takes the qualifier too, and it is the one a caller cannot work around: an
                // isolated function may only construct an isolated thing, so a client whose `init` is not marked
                // reads as unusable from isolated code.
                Construct.faithful(
                        "callables/isolated-constructor",
                        "`isolated` on `init` is what lets an isolated caller construct the client at all",
                        client(Decl.method("init", Decl.param("config", Node.named("Config", RECORDS)))
                                .on("isIsolated")
                                .returns(Node.builtin("error?"))),
                        clientBody("    isolated function init(Config config) returns error?;")),

                Construct.faithful(
                        "callables/isolated",
                        "`isolated` is part of the signature a caller must match to use the method "
                                + "concurrently",
                        client(Decl.method("send").on("isRemote").on("isIsolated")
                                .returns(Node.builtin("error?"))),
                        clientBody("    isolated remote function send() returns error?;")),

                // Central marks the parameter's own type node `isInclusion`, which is the one place the fact
                // appears.
                Construct.faithful(
                        "callables/inclusion-parameter",
                        "an included-record parameter is `*T name` — the caller passes the record's fields "
                                + "as named arguments, so the `*` is part of how the call is written",
                        client(Decl.method("list",
                                        Decl.param("queries", Node.named("ListQueries", RECORDS)
                                                .on("isInclusion")))
                                .on("isRemote")
                                .returns(Node.builtin("error?"))),
                        clientBody("    remote function list(*ListQueries queries) returns error?;")),

                Construct.faithful(
                        "callables/deprecated",
                        "a deprecated operation carries `@deprecated`; the renderer exists but is wired "
                                + "only to service methods",
                        client(Decl.method("legacy").on("isRemote").on("isDeprecated")
                                .returns(Node.builtin("error?"))),
                        clientBody("    @deprecated\n    remote function legacy() returns error?;")),

                Construct.faithful(
                        "callables/constructor-doc",
                        "`init`'s doc comment is where a connector states what its constructor needs",
                        client(Decl.method("init", Decl.param("config", Node.named("Config", RECORDS)))
                                .with("description", "Initialise the client with a config.")
                                .returns(Node.builtin("error?"))),
                        clientBody("    # Initialise the client with a config.\n"
                                + "    function init(Config config) returns error?;")),

                Construct.faithful(
                        "callables/module-function-doc-rows",
                        "a module-level function documents its parameters and return with `# +` rows",
                        Payload.pkg().with("functions",
                                Decl.method("parse", Decl.param("text", Node.builtin("string"))
                                                .with("description", "The text to parse."))
                                        .with("description", "Parse a string.")
                                        .returns(Node.builtin("int"), "the parsed value")),
                        """
                        // --- Functions ---

                        # Parse a string.
                        # + text - The text to parse.
                        # + return - the parsed value
                        public function parse(string text) returns int;"""),

                // Central quotes most of these itself — kafka's listener publishes a parameter named
                // `'service` — but not all: `postgresql:CdcListener` publishes the method it includes from
                // `cdc:Listener` as a bare `start`, and `function start()` does not parse. The keyword list
                // this relies on was derived by compiling each candidate, not recalled.
                Construct.faithful(
                        "callables/keyword-name",
                        "a declared name that collides with a keyword is written `'name`",
                        client(Decl.method("start", Decl.param("limit", Node.builtin("int")))
                                .returns(Node.builtin("error?"))),
                        clientBody("    function 'start(int 'limit) returns error?;")),

                // Central publishes a rest PARAMETER the way it publishes a rest field: `isRestParam` on the
                // type node, the parameter's own name repeated as that node's name, and the real type under
                // `elementType`. Reading the node's name gives
                // `removeCookiesFromRemoteStore(cookiesToRemove cookiesToRemove)`.
                Construct.faithful(
                        "callables/rest-parameter",
                        "a rest parameter is `T... name` — the caller passes any number of them",
                        client(Decl.method("removeAll",
                                        Decl.param("cookies", Node.structural()
                                                .on("isRestParam")
                                                .with("name", "cookies")
                                                .elementType(Node.named("Cookie", "classes"))))
                                .returns(Node.builtin("error?"))),
                        clientBody("    function removeAll(Cookie... cookies) returns error?;")),

                // The expression stays on the line because a defaultable parameter printed without its default
                // reads as required — a worse claim than an unwritable default — and the note is what keeps it
                // from reading as copyable syntax.
                Construct.faithful(
                        "callables/unwritable-default",
                        "a printed default that names something the package does not export is flagged, not "
                                + "presented as syntax the caller can write",
                        client(Decl.method("init",
                                        Decl.param("serviceUrl", Node.builtin("string"))
                                                .with("defaultValue", "BASE_URL"))
                                .returns(Node.builtin("error?"))),
                        clientBody("    function init(string serviceUrl = BASE_URL) returns error?;"
                                + " // the default BASE_URL is not exported by this "
                                + "package; omit the argument rather than repeating it")));
    }

    private static List<Construct> services() {
        return List.of(
                // The payload publishes no return parameter, so a `returns` clause on `init` would be invented.
                Construct.faithful(
                        "services/listener-only",
                        "a listener is a declaration in its own right — `new Listener(config)` is how a "
                                + "service half of a package is reached at all",
                        Payload.pkg().with("listeners", Decl.listener("Listener",
                                Decl.param("config", Node.named("ListenerConfig", RECORDS)))),
                        """
                        // --- Listeners ---

                        public class Listener {
                            function init(ListenerConfig config);
                        }""")
                        .inSection("Listeners"));
    }

    private static List<Construct> annotations() {
        return List.of(
                Construct.faithful(
                        "annotations/service-attachment",
                        "an annotation a service author writes is listed with its attachment point",
                        Payload.pkg().with("annotations",
                                Decl.annotation("ServiceConfig", "service")
                                        .with("description", "Service configuration.")),
                        """
                        // --- Annotations ---

                        # Service configuration.
                        public annotation ServiceConfig on service;"""),

                Construct.faithful(
                        "annotations/parameter-attachment",
                        "a parameter annotation like `@kafka:Payload` is written at a call site and cannot "
                                + "be written if the document never names it",
                        Payload.pkg().with("annotations",
                                Decl.annotation("Payload", "parameter")
                                        .with("description", "Marks the payload parameter.")),
                        """
                        // --- Annotations ---

                        # Marks the payload parameter.
                        public annotation Payload on parameter;"""),

                // Printed as `service_function`, the attach point fails to parse. Central's own spelling is the
                // language's.
                Construct.faithful(
                        "annotations/object-function-attachment",
                        "`object function` is the language's attach point for a method annotation; "
                                + "`service_function` is not a token the compiler has",
                        Payload.pkg().with("annotations",
                                Decl.annotation("ResourceConfig", "object function")
                                        .typed(Node.named("HttpResourceConfig", RECORDS))),
                        """
                        // --- Annotations ---

                        public annotation HttpResourceConfig ResourceConfig on object function;"""),

                Construct.faithful(
                        "annotations/config-record",
                        "an annotation's config record is what makes `@Ann { … }` writable, and it precedes "
                                + "the annotation's own name",
                        Payload.pkg().with("annotations",
                                Decl.annotation("Payload", "parameter, return")
                                        .typed(Node.named("HttpPayload", RECORDS))
                                        .with("description", "Defines the payload parameter.")),
                        """
                        // --- Annotations ---

                        # Defines the payload parameter.
                        public annotation HttpPayload Payload on parameter, return;"""),

                // `graphql:ID`'s shape: three attach points and no config record, because a marker annotation
                // takes no argument. Both halves matter — a type printed here would be a guess, and a clause
                // truncated to one point would reject two of the three places the annotation is legal.
                Construct.faithful(
                        "annotations/marker-with-several-points",
                        "a marker annotation has no config record, and every attach point Central lists is "
                                + "part of the declaration",
                        Payload.pkg().with("annotations",
                                Decl.annotation("ID", "record field, parameter, return")),
                        """
                        // --- Annotations ---

                        public annotation ID on record field, parameter, return;"""));
    }

    private static Payload record(Decl... fields) {
        return Payload.pkg().with(RECORDS, Decl.record("Config", fields));
    }

    private static String body(String... fieldLines) {
        return "// --- Types ---\n\npublic type Config record {\n"
                + String.join("\n", fieldLines) + "\n};";
    }

    private static Payload client(Decl... methods) {
        return Payload.pkg().with("clients", Decl.client("Client", methods));
    }

    private static String clientBody(String... memberLines) {
        return "// --- Client ---\n\npublic client class Client {\n"
                + String.join("\n\n", memberLines) + "\n}";
    }

    private static String errorBody(String declaration) {
        return "// --- Types ---\n\n" + declaration;
    }
}
