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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * The RFC's JSON shape: object fields inline, one array element per line.
 *
 * <p>Syntactically identical to fully-indented JSON — a parser sees no difference — but far more
 * line-count-efficient than either a single unbroken line (hard for a human skimming a large array to scan) or
 * fully-indented JSON (a line per object field too, which is what actually costs the line count an agent's own
 * tooling truncates against). Gson's stock pretty-printer has no mode between "everything on one line" and
 * "everything indented," which is why this exists rather than a formatting flag.
 *
 * @since 0.1.0
 */
public final class CompactJson {

    private CompactJson() {
    }

    public static String write(JsonElement element) {
        StringBuilder out = new StringBuilder();
        write(element, out);
        return out.toString();
    }

    private static void write(JsonElement element, StringBuilder out) {
        if (element == null || element.isJsonNull()) {
            out.append("null");
        } else if (element.isJsonArray()) {
            writeArray(element.getAsJsonArray(), out);
        } else if (element.isJsonObject()) {
            writeObject(element.getAsJsonObject(), out);
        } else {
            // A primitive's own toString() is already compact, correctly-escaped JSON — Gson implements it, so
            // there is nothing this class needs to add for a leaf value.
            out.append(element);
        }
    }

    private static void writeArray(JsonArray array, StringBuilder out) {
        if (array.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append("[\n");
        for (int index = 0; index < array.size(); index++) {
            write(array.get(index), out);
            out.append(index < array.size() - 1 ? ",\n" : "\n");
        }
        out.append("]");
    }

    private static void writeObject(JsonObject object, StringBuilder out) {
        out.append("{");
        boolean first = true;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!first) {
                out.append(",");
            }
            first = false;
            out.append(new com.google.gson.JsonPrimitive(entry.getKey()));
            out.append(":");
            write(entry.getValue(), out);
        }
        out.append("}");
    }
}
