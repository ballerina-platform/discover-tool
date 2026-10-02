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

package io.ballerina.tools.discover.central;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ballerina.tools.discover.QualifiedName;
import io.ballerina.tools.discover.Version;

/**
 * Does this payload actually describe the package and version we asked for?
 *
 * <p>A cache keyed by coordinates is only as good as its answer to that question. The key comes from our own
 * argv, so a mismatch means the file on disk is not what its path claims — a partially-written entry from an
 * older layout, a hand-copied file, a rename that went wrong. Any of those would otherwise serve one package's
 * signatures under another package's name, which is the single worst thing this reader could do.
 *
 * <p>It runs on the RAW JSON rather than the schema's output because the schema strips the fields it needs: a
 * module has no {@code version} or {@code isDefaultModule} in the IR. Adding them to the schema instead would make
 * them required reads and turn a cosmetic upstream change into a failed lookup, which is the trade the schema
 * deliberately does not make.
 *
 * <p>The cache-side and live-side rules ask the same of a page. {@code apiDocsVersion} is not part of either:
 * Central's pages from before it existed ({@code ballerina/graphql} 1.8.0) carry only {@code docsData} and
 * {@code searchData}, and requiring it of a cached entry made every such page a miss that was refetched, written
 * and deleted again on each run. A foreign or partial file is still rejected — by these coordinate checks, by the
 * parse that follows, and by writes that are atomic in the first place.
 *
 * <p>Module matching uses the REQUESTED name, for the same reason module selection does: a check that verifies
 * one module while the renderer reads another verifies nothing.
 *
 * @since 0.1.0
 */
public final class Coordinates {

    private Coordinates() {
    }

    public static boolean match(JsonElement raw, QualifiedName qualified, Version version) {
        if (raw == null || !raw.isJsonObject()) {
            return false;
        }
        if (describesSubmodule(raw, qualified)) {
            return false;
        }
        for (JsonObject module : modules(raw)) {
            if (describes(module, qualified, version)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Does this payload describe the {@code submodule} module page of this package version?
     *
     * <p>The mirror image of {@link #match} plus {@link #describesSubmodule}, for a cached entry and a page off the
     * wire alike: the page answering {@code docs/<org>/<package>.<submodule>/<version>} must carry that exact
     * module, at that version, flagged as NOT its package's default, and naming THIS package's default module
     * among its {@code relatedModules}. The
     * flag tells a submodule apart from a separately published package that shares the dotted name
     * ({@code ballerinax/aws.s3} is a package, not a module of {@code ballerinax/aws}), so here an absent flag is
     * not good enough. The related default module tells it apart from a submodule of a DIFFERENT package that
     * shares the prefix: with {@code org/a.b} a package publishing {@code c}, {@code org/a --module b.c} reaches
     * {@code a.b}'s page, whose default module is {@code a.b}, not {@code a}.
     */
    public static boolean isModulePage(JsonElement raw, QualifiedName qualified, String submodule, Version version) {
        String id = qualified.name() + "." + submodule;
        for (JsonObject module : modules(raw)) {
            if (id.equals(Json.string(module, "id"))
                    && qualified.org().equals(Json.string(module, "orgName"))
                    && version.text().equals(Json.string(module, "version"))
                    && flagged(module, "isDefaultModule", false)
                    && namesDefaultModule(module, qualified)) {
                return true;
            }
        }
        return false;
    }

    private static boolean namesDefaultModule(JsonObject module, QualifiedName qualified) {
        JsonElement related = module.get("relatedModules");
        if (related == null || !related.isJsonArray()) {
            return false;
        }
        for (JsonElement entry : related.getAsJsonArray()) {
            if (entry.isJsonObject()
                    && qualified.name().equals(Json.string(entry.getAsJsonObject(), "id"))
                    && qualified.org().equals(Json.string(entry.getAsJsonObject(), "orgName"))
                    && flagged(entry.getAsJsonObject(), "isDefaultModule", true)) {
                return true;
            }
        }
        return false;
    }

    /** Is the value exactly this boolean? Absent and non-boolean values are neither. */
    private static boolean flagged(JsonObject owner, String key, boolean expected) {
        JsonElement value = owner.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean() == expected;
    }

    /**
     * Is this a submodule's own page rather than a package's?
     *
     * <p>Central's docs endpoint answers for a module path as readily as for a package
     * ({@code docs/ballerinax/aws.auth/1.0.2} is the {@code auth} module of {@code ballerinax/aws}), and flags it
     * {@code isDefaultModule: false}; a package's page carries its own default module. Only an explicit
     * {@code false} counts, so a payload that omits the flag is still read as the package it was asked for.
     */
    public static boolean describesSubmodule(JsonElement raw, QualifiedName qualified) {
        for (JsonObject module : modules(raw)) {
            if (qualified.name().equals(Json.string(module, "id"))
                    && qualified.org().equals(Json.string(module, "orgName"))
                    && flagged(module, "isDefaultModule", false)) {
                return true;
            }
        }
        return false;
    }

    private static java.util.List<JsonObject> modules(JsonElement raw) {
        if (raw == null || !raw.isJsonObject()) {
            return java.util.List.of();
        }
        JsonElement docsData = raw.getAsJsonObject().get("docsData");
        if (docsData == null || !docsData.isJsonObject()) {
            return java.util.List.of();
        }
        JsonElement modules = docsData.getAsJsonObject().get("modules");
        if (modules == null || !modules.isJsonArray()) {
            return java.util.List.of();
        }
        java.util.List<JsonObject> objects = new java.util.ArrayList<>();
        for (JsonElement entry : modules.getAsJsonArray()) {
            if (entry != null && entry.isJsonObject()) {
                objects.add(entry.getAsJsonObject());
            }
        }
        return objects;
    }

    private static boolean describes(JsonObject module, QualifiedName qualified, Version version) {
        String id = Json.string(module, "id");
        if (id == null || !qualified.org().equals(Json.string(module, "orgName"))) {
            return false;
        }
        return id.equals(qualified.name()) && version.text().equals(Json.string(module, "version"));
    }

    /**
     * The module names one version's registry row lists — {@code aws} and {@code aws.auth} for
     * {@code ballerinax/aws} — or an empty list when the row is not the shape expected.
     */
    static java.util.List<String> moduleNames(JsonElement raw) {
        if (raw == null || !raw.isJsonObject()) {
            return java.util.List.of();
        }
        JsonElement modules = raw.getAsJsonObject().get("modules");
        if (modules == null || !modules.isJsonArray()) {
            return java.util.List.of();
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        for (JsonElement entry : modules.getAsJsonArray()) {
            String name = entry.isJsonObject() ? Json.string(entry.getAsJsonObject(), "name") : null;
            if (name != null && !name.isEmpty()) {
                names.add(name);
            }
        }
        return java.util.List.copyOf(names);
    }

    /** The first entry of a versions array, or {@code null} if it is not a non-empty array of strings. */
    static String newestVersion(JsonElement raw) {
        java.util.List<String> all = publishedVersions(raw);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Every version Central lists, newest first.
     *
     * <p>T10. {@code package-not-found} used to say "verify the version is published" while no verb could list
     * what was published — advice naming a step the caller had no way to take. Naming them in the failure is why
     * no {@code versions} verb is needed, and it is the only place they are ever printed.
     */
    static java.util.List<String> publishedVersions(JsonElement raw) {
        if (raw == null || !raw.isJsonArray()) {
            return java.util.List.of();
        }
        java.util.List<String> versions = new java.util.ArrayList<>();
        for (JsonElement entry : raw.getAsJsonArray()) {
            if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()
                    && !entry.getAsString().isEmpty()) {
                versions.add(entry.getAsString());
            }
        }
        return java.util.List.copyOf(versions);
    }

    /** The registry's per-version entry's {@code balaURL}, when it names one. */
    static java.util.Optional<String> balaUrl(JsonElement raw) {
        if (raw == null || !raw.isJsonObject()) {
            return java.util.Optional.empty();
        }
        String url = Json.string(raw.getAsJsonObject(), "balaURL");
        return url == null || url.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(url);
    }
}
