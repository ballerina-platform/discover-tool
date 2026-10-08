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

package io.ballerina.tools.discover.central.schema;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.ballerina.tools.discover.Failure;
import io.ballerina.tools.discover.Result;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Untyped JSON in, {@link CentralDocs} or a located {@code schema-drift} failure out.
 *
 * <p>Hand-written rather than declarative, and that is the point: the tool jar carries no third-party
 * classes of its own, so a validator is 200 lines here against a bundled dependency plus a second
 * schema artifact to keep in sync.
 *
 * <p>It collects EVERY mismatch before failing. A payload that drifted in four places should report
 * four paths, because the person reading the failure is about to extend {@link CentralDocs} and needs
 * the whole list — a fail-fast validator turns one review into four round trips. So a bad value yields
 * a placeholder and the walk continues, rather than throwing.
 *
 * @since 0.1.0
 */
public final class Schema {

    /** Central's keys for a page's module list and each listed module's identity. */
    public static final String DOCS_DATA = "docsData";
    public static final String MODULES = "modules";
    public static final String RELATED_MODULES = "relatedModules";
    public static final String ID = "id";
    public static final String ORG_NAME = "orgName";
    public static final String VERSION = "version";
    public static final String SUMMARY = "summary";
    public static final String IS_DEFAULT_MODULE = "isDefaultModule";

    private static final String INCLUSION_TYPE = "inclusionType";

    private Schema() {
    }

    /** Validate a raw payload, reporting every mismatch at once. */
    public static Result<CentralDocs> parse(JsonElement raw, String qualified) {
        return parse(raw, qualified, null);
    }

    /** {@link #parse(JsonElement, String)} for a payload read at a known version. */
    public static Result<CentralDocs> parse(JsonElement raw, String qualified, String version) {
        Cursor cursor = new Cursor();
        JsonObject root = cursor.object(raw, "");
        JsonObject docsData = cursor.object(root.get(DOCS_DATA), DOCS_DATA);
        List<CentralDocs.Module> modules =
                cursor.array(docsData, DOCS_DATA, MODULES, Schema::module);
        if (modules.isEmpty() && cursor.issues.isEmpty()) {
            cursor.issue(DOCS_DATA + "." + MODULES, "expected at least one module, received none");
        }
        if (!cursor.issues.isEmpty()) {
            return Result.err(new Failure.SchemaDrift(
                    qualified, version, null, List.copyOf(cursor.issues), Failure.SCHEMA_DRIFT_SUGGESTION));
        }
        return Result.ok(new CentralDocs(modules));
    }

    // `types` and `resources` are deliberately not read: empty in every fixture, so their shape is unknown;
    // KeySpaceTest flags the first package that populates one.
    private static CentralDocs.Module module(Cursor cursor, JsonObject json, String path) {
        return new CentralDocs.Module(
                cursor.requiredString(json, path, ID),
                cursor.requiredString(json, path, ORG_NAME),
                cursor.optionalString(json, path, SUMMARY),
                cursor.optionalString(json, path, "description"),
                cursor.bucket(json, path, "records", Schema::recordDecl),
                cursor.bucket(json, path, "stringTypes", Schema::aliasDecl),
                cursor.bucket(json, path, "integerTypes", Schema::aliasDecl),
                cursor.bucket(json, path, "decimalTypes", Schema::aliasDecl),
                cursor.bucket(json, path, "booleanTypes", Schema::aliasDecl),
                cursor.bucket(json, path, "simpleNameReferenceTypes", Schema::aliasDecl),
                cursor.bucket(json, path, "arrayTypes", Schema::aliasDecl),
                cursor.bucket(json, path, "unionTypes", Schema::aliasDecl),
                cursor.bucket(json, path, "intersectionTypes", Schema::aliasDecl),
                cursor.bucket(json, path, "anyDataTypes", Schema::aliasDecl),
                cursor.bucket(json, path, "anyTypes", Schema::aliasDecl),
                cursor.bucket(json, path, "tupleTypes", Schema::aliasDecl),
                cursor.bucket(json, path, "functionTypes", Schema::aliasDecl),
                cursor.bucket(json, path, "typeDescriptorTypes", Schema::aliasDecl),
                cursor.bucket(json, path, "mapTypes", Schema::aliasDecl),
                cursor.bucket(json, path, "streamTypes", Schema::aliasDecl),
                cursor.bucket(json, path, "tableTypes", Schema::aliasDecl),
                cursor.bucket(json, path, "xmlTypes", Schema::aliasDecl),
                cursor.bucket(json, path, "errors", Schema::errorDecl),
                cursor.bucket(json, path, "constants", Schema::constant),
                cursor.bucket(json, path, "enums", Schema::enumDecl),
                cursor.bucket(json, path, "classes", Schema::objectDecl),
                cursor.bucket(json, path, "objectTypes", Schema::objectDecl),
                cursor.bucket(json, path, "clients", Schema::client),
                cursor.bucket(json, path, "functions", Schema::method),
                cursor.bucket(json, path, "listeners", Schema::listener),
                cursor.bucket(json, path, "serviceTypes", Schema::objectDecl),
                cursor.bucket(json, path, "annotations", Schema::annotation),
                cursor.bucket(json, path, "variables", Schema::variableDecl),
                cursor.bucket(json, path, "configurables", Schema::variableDecl),
                relatedModules(json));
    }

    // Lenient and never drift: every page carries this list, so a strict read would fail every lookup.
    private static List<CentralDocs.RelatedModule> relatedModules(JsonObject json) {
        JsonElement related = json.get(RELATED_MODULES);
        if (related == null || !related.isJsonArray()) {
            return List.of();
        }
        List<CentralDocs.RelatedModule> modules = new ArrayList<>();
        for (JsonElement entry : related.getAsJsonArray()) {
            if (!entry.isJsonObject()) {
                continue;
            }
            JsonObject module = entry.getAsJsonObject();
            String id = string(module, ID);
            String orgName = string(module, ORG_NAME);
            if (id != null && orgName != null) {
                JsonElement isDefault = module.get(IS_DEFAULT_MODULE);
                modules.add(new CentralDocs.RelatedModule(id, orgName, Optional.ofNullable(string(module, SUMMARY)),
                        isDefault != null && isDefault.isJsonPrimitive()
                                && isDefault.getAsJsonPrimitive().isBoolean() && isDefault.getAsBoolean()));
            }
        }
        return List.copyOf(modules);
    }

    private static String string(JsonObject owner, String key) {
        JsonElement value = owner.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : null;
    }

    private static CentralDocs.TypeNode typeNode(Cursor cursor, JsonObject json, String path) {
        return new CentralDocs.TypeNode(
                cursor.optionalString(json, path, "name"),
                cursor.optionalString(json, path, "category"),
                cursor.optionalString(json, path, ORG_NAME),
                cursor.optionalString(json, path, "moduleName"),
                cursor.optionalString(json, path, VERSION),
                cursor.optionalString(json, path, "description"),
                cursor.flag(json, path, "isArrayType"),
                cursor.flag(json, path, "isNullable"),
                cursor.flag(json, path, "isOptional"),
                cursor.flag(json, path, "isAnonymousUnionType"),
                cursor.flag(json, path, "isIntersectionType"),
                cursor.flag(json, path, "isParenthesisedType"),
                cursor.flag(json, path, "isTypeDesc"),
                cursor.flag(json, path, "isTuple"),
                cursor.flag(json, path, "isRestParam"),
                cursor.flag(json, path, "isInclusion"),
                cursor.flag(json, path, "isReadOnly"),
                cursor.flag(json, path, "isDeprecated"),
                cursor.flag(json, path, "isIsolated"),
                cursor.flag(json, path, "isDistinct"),
                cursor.flag(json, path, "isLambda"),
                cursor.count(json, path, "arrayDimensions"),
                cursor.optionalObject(json, path, "constraint", Schema::typeNode),
                cursor.optionalObject(json, path, "elementType", Schema::typeNode),
                cursor.optionalObject(json, path, "returnType", Schema::typeNode),
                cursor.optionalArray(json, path, "memberTypes", Schema::typeNode),
                cursor.optionalArray(json, path, "paramTypes", Schema::typeNode),
                cursor.optionalArray(json, path, "functionTypes", Schema::typeNode));
    }

    private static CentralDocs.AnnotationRef annotationRef(Cursor cursor, JsonObject json, String path) {
        return new CentralDocs.AnnotationRef(
                cursor.requiredString(json, path, "name"),
                cursor.optionalString(json, path, ORG_NAME),
                cursor.optionalString(json, path, "moduleName"),
                cursor.optionalString(json, path, VERSION),
                cursor.optionalString(json, path, "description"));
    }

    private static CentralDocs.Parameter parameter(Cursor cursor, JsonObject json, String path) {
        return new CentralDocs.Parameter(
                cursor.requiredString(json, path, "name"),
                cursor.optionalString(json, path, "description"),
                cursor.optionalString(json, path, "defaultValue"),
                cursor.flag(json, path, "isDeprecated"),
                cursor.requiredObject(json, path, "type", Schema::typeNode));
    }

    private static CentralDocs.ReturnParameter returnParameter(Cursor cursor, JsonObject json, String path) {
        return new CentralDocs.ReturnParameter(
                cursor.optionalString(json, path, "description"),
                cursor.requiredObject(json, path, "type", Schema::typeNode));
    }

    private static CentralDocs.Method method(Cursor cursor, JsonObject json, String path) {
        return new CentralDocs.Method(
                cursor.requiredString(json, path, "name"),
                cursor.optionalString(json, path, "description"),
                cursor.flag(json, path, "isRemote"),
                cursor.flag(json, path, "isResource"),
                cursor.flag(json, path, "isDeprecated"),
                cursor.flag(json, path, "isIsolated"),
                cursor.flag(json, path, "isExtern"),
                cursor.optionalString(json, path, "accessor"),
                cursor.optionalString(json, path, "resourcePath"),
                cursor.optionalArray(json, path, "parameters", Schema::parameter),
                cursor.optionalArray(json, path, "returnParameters", Schema::returnParameter),
                cursor.optionalArray(json, path, "annotationAttachments", Schema::annotationRef));
    }

    private static CentralDocs.Field field(Cursor cursor, JsonObject json, String path) {
        if (json.has(INCLUSION_TYPE)) {
            return new CentralDocs.Field.Inclusion(
                    cursor.requiredObject(json, path, INCLUSION_TYPE, Schema::typeNode),
                    cursor.flag(json, path, "isReadOnly"),
                    cursor.flag(json, path, "isDeprecated"));
        }
        // The type is read FIRST because it decides whether the name is required. A rest field
        // (`anydata...;`) has no name to publish, and Central spells that absence two ways: most
        // packages send `"name": ""`, while ballerina/time omits the key entirely. Requiring a
        // string rejected the second, which failed the WHOLE document over a field that is not
        // supposed to have a name.
        CentralDocs.TypeNode type = cursor.requiredObject(json, path, "type", Schema::typeNode);
        String name = type.isRestParam()
                ? cursor.optionalString(json, path, "name").orElse("")
                : cursor.requiredString(json, path, "name");
        return new CentralDocs.Field.Declared(
                name,
                cursor.optionalString(json, path, "description"),
                cursor.optionalString(json, path, "defaultValue"),
                cursor.flag(json, path, "isReadOnly"),
                cursor.flag(json, path, "isDeprecated"),
                cursor.optionalArray(json, path, "annotationAttachments", Schema::annotationRef),
                type);
    }

    private static CentralDocs.Named named(Cursor cursor, JsonObject json, String path) {
        return new CentralDocs.Named(
                cursor.requiredString(json, path, "name"),
                cursor.optionalString(json, path, "description"),
                cursor.flag(json, path, "isDeprecated"));
    }

    private static CentralDocs.AliasDecl aliasDecl(Cursor cursor, JsonObject json, String path) {
        return new CentralDocs.AliasDecl(
                cursor.requiredString(json, path, "name"),
                cursor.optionalString(json, path, "description"),
                typeNode(cursor, json, path));
    }

    private static CentralDocs.ObjectDecl objectDecl(Cursor cursor, JsonObject json, String path) {
        return new CentralDocs.ObjectDecl(
                cursor.requiredString(json, path, "name"),
                cursor.optionalString(json, path, "description"),
                cursor.flag(json, path, "isDeprecated"),
                cursor.flag(json, path, "isIsolated"),
                cursor.flag(json, path, "isReadOnly"),
                cursor.flag(json, path, "isService"),
                cursor.flag(json, path, "isDistinct"),
                cursor.optionalObject(json, path, "initMethod", Schema::method),
                cursor.optionalArray(json, path, "fields", Schema::field),
                cursor.optionalArray(json, path, "methods", Schema::method),
                cursor.optionalArray(json, path, "otherMethods", Schema::method));
    }

    private static CentralDocs.VariableDecl variableDecl(Cursor cursor, JsonObject json, String path) {
        return new CentralDocs.VariableDecl(
                cursor.requiredString(json, path, "name"),
                cursor.optionalString(json, path, "description"),
                cursor.optionalString(json, path, "defaultValue"),
                cursor.flag(json, path, "isReadOnly"),
                cursor.flag(json, path, "isDeprecated"),
                cursor.requiredObject(json, path, "type", Schema::typeNode));
    }

    private static CentralDocs.ErrorDecl errorDecl(Cursor cursor, JsonObject json, String path) {
        return new CentralDocs.ErrorDecl(
                cursor.requiredString(json, path, "name"),
                cursor.optionalString(json, path, "description"),
                cursor.requiredBoolean(json, path, "isDistinct"),
                cursor.optionalObject(json, path, "detailType", Schema::typeNode));
    }

    private static CentralDocs.RecordDecl recordDecl(Cursor cursor, JsonObject json, String path) {
        return new CentralDocs.RecordDecl(
                cursor.requiredString(json, path, "name"),
                cursor.optionalString(json, path, "description"),
                cursor.flag(json, path, "isClosed"),
                cursor.flag(json, path, "isReadOnly"),
                cursor.flag(json, path, "isDeprecated"),
                cursor.optionalArray(json, path, "fields", Schema::field));
    }

    private static CentralDocs.Constant constant(Cursor cursor, JsonObject json, String path) {
        return new CentralDocs.Constant(
                cursor.requiredString(json, path, "name"),
                cursor.optionalString(json, path, "description"),
                cursor.requiredString(json, path, "value"),
                cursor.requiredObject(json, path, "type", Schema::typeNode));
    }

    private static CentralDocs.EnumDecl enumDecl(Cursor cursor, JsonObject json, String path) {
        return new CentralDocs.EnumDecl(
                cursor.requiredString(json, path, "name"),
                cursor.optionalString(json, path, "description"),
                cursor.flag(json, path, "isDeprecated"),
                cursor.optionalArray(json, path, "members", Schema::named));
    }

    private static CentralDocs.Client client(Cursor cursor, JsonObject json, String path) {
        return new CentralDocs.Client(
                cursor.requiredString(json, path, "name"),
                cursor.optionalString(json, path, "description"),
                cursor.flag(json, path, "isDeprecated"),
                cursor.flag(json, path, "isIsolated"),
                cursor.optionalArray(json, path, "methods", Schema::method));
    }

    private static CentralDocs.Listener listener(Cursor cursor, JsonObject json, String path) {
        return new CentralDocs.Listener(
                objectDecl(cursor, json, path),
                cursor.optionalArray(json, path, "lifeCycleMethods", Schema::method));
    }

    private static CentralDocs.Annotation annotation(Cursor cursor, JsonObject json, String path) {
        return new CentralDocs.Annotation(
                cursor.requiredString(json, path, "name"),
                cursor.optionalString(json, path, "description"),
                cursor.optionalString(json, path, "attachmentPoints"),
                cursor.flag(json, path, "isDeprecated"),
                cursor.optionalObject(json, path, "type", Schema::typeNode));
    }

    /**
     * Accumulates issues so a drifted payload reports all of them, and turns a key plus an owner path
     * into the dotted path a reviewer greps the payload for.
     */
    private static final class Cursor {

        /** The most {@code []} pairs a type node is believed to carry: far past any real one, far short of harm. */
        static final int MAX_COUNT = 255;

        private final List<Failure.SchemaIssue> issues = new ArrayList<>();

        static String child(String path, String key) {
            return path.isEmpty() ? key : path + "." + key;
        }

        void issue(String path, String message) {
            issues.add(new Failure.SchemaIssue(path, message));
        }

        private static String describe(JsonElement value) {
            if (value == null) {
                return "nothing";
            }
            if (value.isJsonNull()) {
                return "null";
            }
            if (value.isJsonArray()) {
                return "an array";
            }
            if (value.isJsonObject()) {
                return "an object";
            }
            JsonPrimitive primitive = value.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return "a boolean";
            }
            return primitive.isNumber() ? "a number" : "a string";
        }

        JsonObject object(JsonElement value, String path) {
            if (value != null && value.isJsonObject()) {
                return value.getAsJsonObject();
            }
            issue(path, "expected an object, received " + describe(value));
            return new JsonObject();
        }

        String requiredString(JsonObject owner, String path, String key) {
            JsonElement value = owner.get(key);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                return value.getAsString();
            }
            issue(child(path, key), "expected a string, received " + describe(value));
            return "";
        }

        Optional<String> optionalString(JsonObject owner, String path, String key) {
            JsonElement value = owner.get(key);
            if (value == null || value.isJsonNull()) {
                return Optional.empty();
            }
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                return Optional.of(value.getAsString());
            }
            issue(child(path, key), "expected a string, received " + describe(value));
            return Optional.empty();
        }

        boolean requiredBoolean(JsonObject owner, String path, String key) {
            JsonElement value = owner.get(key);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                return value.getAsBoolean();
            }
            issue(child(path, key), "expected a boolean, received " + describe(value));
            return false;
        }

        /** An optional boolean, absent meaning false — which is how Central spells "not set". */
        boolean flag(JsonObject owner, String path, String key) {
            JsonElement value = owner.get(key);
            if (value == null || value.isJsonNull()) {
                return false;
            }
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                return value.getAsBoolean();
            }
            issue(child(path, key), "expected a boolean, received " + describe(value));
            return false;
        }

        /**
         * A non-negative count, absent meaning zero — the same convention as {@link #flag}.
         *
         * <p>Absent is not drift: it is read as "how many {@code []} pairs", and zero is what a node with no
         * array carries anyway. A value that is not a number IS drift, because that is a shape change — and so is one
         * that is not a whole number up to {@link #MAX_COUNT}, since the count is spent as that many {@code []}.
         */
        int count(JsonObject owner, String path, String key) {
            JsonElement value = owner.get(key);
            if (value == null || value.isJsonNull()) {
                return 0;
            }
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                BigDecimal number = value.getAsBigDecimal();
                if (number.signum() >= 0 && number.compareTo(BigDecimal.valueOf(MAX_COUNT)) <= 0
                        && number.stripTrailingZeros().scale() <= 0) {
                    return number.intValueExact();
                }
                issue(child(path, key), "expected a whole number from 0 to " + MAX_COUNT + ", received "
                        + value.getAsString());
                return 0;
            }
            issue(child(path, key), "expected a number, received " + describe(value));
            return 0;
        }

        <T> T requiredObject(JsonObject owner, String path, String key, Nested<T> shape) {
            return nested(owner.get(key), child(path, key), shape);
        }

        <T> Optional<T> optionalObject(JsonObject owner, String path, String key, Nested<T> shape) {
            JsonElement value = owner.get(key);
            if (value == null || value.isJsonNull()) {
                return Optional.empty();
            }
            return Optional.of(nested(value, child(path, key), shape));
        }

        // A non-object value is one issue, not one per missing key: the placeholder read's own issues are dropped,
        // and the placeholder itself is never used, since any issue fails the parse.
        private <T> T nested(JsonElement value, String path, Nested<T> shape) {
            if (value != null && value.isJsonObject()) {
                return shape.read(this, value.getAsJsonObject(), path);
            }
            object(value, path);
            int reported = issues.size();
            T placeholder = shape.read(this, new JsonObject(), path);
            issues.subList(reported, issues.size()).clear();
            return placeholder;
        }

        /** A required array. Missing or mistyped is drift; empty is a package with none of them. */
        <T> List<T> array(JsonObject owner, String path, String key, Nested<T> shape) {
            String childPath = child(path, key);
            JsonElement value = owner.get(key);
            if (value == null || !value.isJsonArray()) {
                issue(childPath, "expected an array, received " + describe(value));
                return List.of();
            }
            return items(value.getAsJsonArray(), childPath, shape);
        }

        /**
         * A declaration bucket: ABSENT means the module has none of that kind, not that the payload
         * drifted.
         *
         * <p>Central omits a bucket when it is empty rather than sending {@code []}; requiring them refused
         * ~15% of packages outright ({@code pinecone.vector}, {@code weaviate}, {@code azure_cosmosdb},
         * {@code sendgrid}). A key that IS present and is not an array is still drift.
         */
        <T> List<T> bucket(JsonObject owner, String path, String key, Nested<T> shape) {
            JsonElement value = owner.get(key);
            if (value == null || value.isJsonNull()) {
                return List.of();
            }
            String childPath = child(path, key);
            if (!value.isJsonArray()) {
                issue(childPath, "expected an array, received " + describe(value));
                return List.of();
            }
            return items(value.getAsJsonArray(), childPath, shape);
        }

        <T> Optional<List<T>> optionalArray(JsonObject owner, String path, String key, Nested<T> shape) {
            JsonElement value = owner.get(key);
            if (value == null || value.isJsonNull()) {
                return Optional.empty();
            }
            String childPath = child(path, key);
            if (!value.isJsonArray()) {
                issue(childPath, "expected an array, received " + describe(value));
                return Optional.empty();
            }
            return Optional.of(items(value.getAsJsonArray(), childPath, shape));
        }

        private <T> List<T> items(JsonArray array, String path, Nested<T> shape) {
            List<T> parsed = new ArrayList<>(array.size());
            for (int index = 0; index < array.size(); index++) {
                String itemPath = path + "." + index;
                JsonElement item = array.get(index);
                if (item.isJsonObject()) {
                    parsed.add(shape.read(this, item.getAsJsonObject(), itemPath));
                } else {
                    object(item, itemPath);
                }
            }
            return parsed;
        }
    }

    private interface Nested<T> {
        T read(Cursor cursor, JsonObject json, String path);
    }
}
