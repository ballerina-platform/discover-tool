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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ballerina.tools.discover.Failure;
import io.ballerina.tools.discover.Result;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Ballerina Central's two searches: packages ({@code registry/packages/?q=}, what {@code bal search} asks) and
 * symbols across every package ({@code registry/search-symbols?q=}). Each answers one window of Central's own
 * ranking and the total it matched. Not cached: the ranking changes as Central does.
 *
 * @since 0.1.0
 */
public final class CentralSearch {

    private static final String PACKAGES_URL = CentralClient.CENTRAL_BASE_URL + "registry/packages/";
    private static final String SYMBOLS_URL = CentralClient.CENTRAL_BASE_URL + "registry/search-symbols";
    private static final String PACKAGES = "packages";
    private static final String SYMBOLS = "symbols";
    private static final String COUNT = "count";
    // The answer was received; it is its shape that is wrong, so it is reported as one attempt, not retried.
    private static final int ONE_ANSWER = 1;

    private CentralSearch() {
    }

    /**
     * One window of a ranking.
     *
     * @param <T> what is ranked
     * @param items the window, in Central's order
     * @param total how many Central matched in all
     */
    public record Window<T>(List<T> items, int total) { }

    /**
     * @param org the package's organization
     * @param name the package's name
     * @param version the version Central matched
     * @param summary the package's summary, verbatim
     */
    public record PackageRow(String org, String name, String version, String summary) { }

    /**
     * @param org the declaring package's organization
     * @param name the declaring package's name
     * @param module the declaring module's name, the package's own for its default module
     * @param version the version Central matched
     * @param type Central's {@code symbolType}: {@code client-method}, {@code record}
     * @param parent the declaring client, class or service type, or empty
     * @param symbol the symbol's name; a resource method's is its accessor, a space and its path
     * @param resource whether it is a resource method
     * @param remote whether it is a remote method
     */
    public record SymbolRow(String org, String name, String module, String version, String type, String parent,
            String symbol, boolean resource, boolean remote) { }

    public static Result<Window<PackageRow>> packages(String query, int limit, int offset, HttpOptions options) {
        return window(PACKAGES_URL, PACKAGES, query, limit, offset, options, row -> new PackageRow(
                Json.text(row, "organization"), Json.text(row, "name"), Json.text(row, "version"),
                Json.text(row, "summary")));
    }

    public static Result<Window<SymbolRow>> symbols(String query, int limit, int offset, HttpOptions options) {
        return window(SYMBOLS_URL, SYMBOLS, query, limit, offset, options, row -> new SymbolRow(
                Json.text(row, "organization"), Json.text(row, "name"), Json.text(row, "moduleName"),
                Json.text(row, "version"), Json.text(row, "symbolType"), Json.text(row, "symbolParent"),
                Json.text(row, "symbolName"), flag(row, "isResource"), flag(row, "isRemote")));
    }

    private static <T> Result<Window<T>> window(String base, String key, String query, int limit, int offset,
            HttpOptions options, Function<JsonObject, T> read) {
        String url = base + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&limit=" + limit
                + "&offset=" + offset;
        Result<JsonElement> response = CentralClient.fetchJson(url, options);
        if (!response.isOk()) {
            return response.cast();
        }
        JsonElement body = response.value();
        JsonElement rows = body.isJsonObject() ? body.getAsJsonObject().get(key) : null;
        JsonElement count = body.isJsonObject() ? body.getAsJsonObject().get(COUNT) : null;
        if (rows == null || !rows.isJsonArray() || count == null || !count.isJsonPrimitive()
                || !count.getAsJsonPrimitive().isNumber()) {
            return Result.err(new Failure.Upstream(url, ONE_ANSWER, "Central answered " + url + " without a '" + key
                    + "' list and a '" + COUNT + "'", Failure.UPSTREAM_SUGGESTION, null, true));
        }
        List<T> items = new ArrayList<>();
        for (JsonElement row : (JsonArray) rows) {
            if (row.isJsonObject()) {
                items.add(read.apply(row.getAsJsonObject()));
            }
        }
        return Result.ok(new Window<>(List.copyOf(items), count.getAsInt()));
    }

    private static boolean flag(JsonObject row, String key) {
        JsonElement value = row.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }
}
