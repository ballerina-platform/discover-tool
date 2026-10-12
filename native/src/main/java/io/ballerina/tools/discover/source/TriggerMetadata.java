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

package io.ballerina.tools.discover.source;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ballerina.tools.discover.QualifiedName;
import io.ballerina.tools.discover.cache.DocsCache;
import io.ballerina.tools.discover.central.Bala;
import io.ballerina.tools.discover.central.CentralClient;
import io.ballerina.tools.discover.central.HttpOptions;
import io.ballerina.tools.discover.central.PackageRepository;
import io.ballerina.tools.discover.model.ModuleRef;
import io.ballerina.tools.discover.model.ServiceHandlers;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads a package's {@code metadata/trigger-metadata.json} ("Ballerina Trigger Construct Spec", v1) for the
 * handlers of its service types.
 *
 * <p>Lenient on purpose: a major version this reader does not know, a file that is not JSON and a service type
 * whose types cannot be written down all yield no handlers for what they affect, never a partly wrong list, and
 * never a failure.
 *
 * @since 0.1.0
 */
public final class TriggerMetadata {

    /** The name of the cache slot, next to the inclusions', that holds the file. */
    private static final String DERIVATION = "trigger-metadata-1";
    private static final Pattern SUPPORTED_VERSION = Pattern.compile("v1\\.\\d+");
    private static final String NAME = "name";
    private static final String TYPE = "type";
    private static final String DOC = "doc";
    private static final String DEPRECATED = "deprecated";
    private static final String PRESENCE = "presence";
    private static final String REQUIRED = "required";
    private static final String ID = "id";
    private static final String SHAPE = "shape";
    private static final String ELEMENT_TYPE = "elementType";
    private static final String NIL = "()";
    private static final String RULES = "rules";
    private static final String SERVICE_TYPE = "serviceType";
    private static final String HANDLER_SUBJECT = "handler";
    private static final String KIND = "kind";
    private static final String ADD_MODE = "addMode";
    private static final String MANY = "many";

    private TriggerMetadata() {
    }

    /**
     * The handlers of {@code moduleId} at the resolved version, from the cache when it holds them, otherwise from
     * {@code repository}'s published package — and then cached, since a version's files never change. A package
     * that publishes no description is cached too, as an empty object, so its archive is not fetched again.
     *
     * @param prefix the alias the described module is written with — {@code kafka} in {@code kafka:Caller}
     * @return each service type that declares its handlers in the file, by the type's name
     */
    public static Map<String, ServiceHandlers> load(PackageRepository repository, QualifiedName qualified,
            CentralClient.ResolvedVersion resolved, String moduleId, String prefix, HttpOptions options) {
        DocsCache cache = options.cache();
        DocsCache.DocsKey key = new DocsCache.DocsKey(
                repository.id(), qualified.org(), qualified.name(), resolved.version().text());
        JsonElement cached = options.refresh() ? null : cache.readInclusions(key, moduleId, DERIVATION);
        if (cached != null) {
            return parse(cached, prefix);
        }
        Optional<Map<String, String>> files = repository.fetchModuleSources(qualified, resolved, moduleId, options);
        if (files.isEmpty()) {
            return Map.of();
        }
        JsonElement published = Optional.ofNullable(files.get().get(Bala.TRIGGER_METADATA))
                .map(TriggerMetadata::read)
                .orElse(new JsonObject());
        cache.writeInclusions(key, moduleId, DERIVATION, published);
        return parse(published, prefix);
    }

    /**
     * @param root the file, parsed
     * @param prefix the alias the described module is written with
     */
    public static Map<String, ServiceHandlers> parse(JsonElement root, String prefix) {
        Map<String, ServiceHandlers> byType = new LinkedHashMap<>();
        try {
            JsonObject file = root.getAsJsonObject();
            if (!SUPPORTED_VERSION.matcher(file.get("version").getAsString()).matches()) {
                return byType;
            }
            JsonArray topRules = file.has(RULES) ? file.getAsJsonArray(RULES) : new JsonArray();
            for (JsonElement element : file.getAsJsonArray("serviceTypes")) {
                try {
                    JsonObject serviceType = element.getAsJsonObject();
                    serviceHandlers(serviceType, topRules, prefix).ifPresent(handlers ->
                            byType.put(serviceType.getAsJsonObject(TYPE).get(NAME).getAsString(), handlers));
                } catch (RuntimeException unwritable) {
                    // This service type's handlers cannot be written down; the others still can.
                }
            }
        } catch (RuntimeException unreadable) {
            return Map.of();
        }
        return byType;
    }

    private static JsonElement read(String json) {
        try {
            return JsonParser.parseString(json);
        } catch (RuntimeException unreadable) {
            return new JsonObject();
        }
    }

    private static Optional<ServiceHandlers> serviceHandlers(
            JsonObject serviceType, JsonArray topRules, String prefix) {
        JsonObject handlers = serviceType.getAsJsonObject("handlers");
        if (handlers == null || !handlers.has("options") || booleanOf(handlers, "backedByConcreteType")) {
            return Optional.empty();
        }
        List<ServiceHandlers.Handler> parsed = new ArrayList<>();
        Map<String, String> namesById = new LinkedHashMap<>();
        for (JsonElement option : handlers.getAsJsonArray("options")) {
            JsonObject object = option.getAsJsonObject();
            parsed.add(handler(object, prefix));
            namesById.put(object.get(ID).getAsString(), nameOf(object));
        }
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        String id = serviceType.get(ID).getAsString();
        List<ServiceHandlers.Rule> rules = new ArrayList<>();
        if (serviceType.has(RULES)) {
            serviceType.getAsJsonArray(RULES).forEach(rule -> rules.add(rule(rule.getAsJsonObject(), namesById)));
        }
        for (JsonElement top : topRules) {
            if (ranges(top.getAsJsonObject(), id)) {
                rules.add(rule(top.getAsJsonObject(), namesById));
            }
        }
        return Optional.of(new ServiceHandlers(parsed, rules));
    }

    private static boolean ranges(JsonObject rule, String serviceTypeId) {
        for (JsonElement subject : rule.getAsJsonArray("subjects")) {
            JsonElement owner = subject.getAsJsonObject().get(SERVICE_TYPE);
            if (owner != null && serviceTypeId.equals(owner.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static String nameOf(JsonObject object) {
        String name = object.get(NAME).getAsString();
        return ServiceHandlers.ANY.equals(name) ? null : name;
    }

    private static ServiceHandlers.Handler handler(JsonObject object, String prefix) {
        ServiceHandlers.Kind kind = ServiceHandlers.Kind.RESOURCE.label().equals(object.get(KIND).getAsString())
                ? ServiceHandlers.Kind.RESOURCE
                : ServiceHandlers.Kind.REMOTE;
        boolean repeatable = MANY.equals(string(object, ADD_MODE));
        List<String> accessors = new ArrayList<>();
        if (kind == ServiceHandlers.Kind.RESOURCE && object.has("accessor")) {
            object.getAsJsonObject("accessor").getAsJsonArray("values")
                    .forEach(value -> accessors.add(value.getAsString()));
        }
        List<ServiceHandlers.Param> params = new ArrayList<>();
        if (object.has("params")) {
            for (JsonElement param : object.getAsJsonArray("params")) {
                params.add(param(param.getAsJsonObject(), prefix));
            }
        }
        String returns = object.has("returns") && object.getAsJsonObject("returns").has(TYPE)
                ? type(object.getAsJsonObject("returns").get(TYPE), prefix)
                : null;
        return new ServiceHandlers.Handler(nameOf(object), kind, REQUIRED.equals(string(object, PRESENCE)),
                repeatable, accessors, string(object, DOC), string(object, DEPRECATED), params, returns);
    }

    private static ServiceHandlers.Param param(JsonObject object, String prefix) {
        return new ServiceHandlers.Param(object.has(NAME) ? object.get(NAME).getAsString() : null,
                type(object.get(TYPE), prefix), REQUIRED.equals(string(object, PRESENCE)),
                MANY.equals(string(object, ADD_MODE)), string(object, DOC), string(object, DEPRECATED));
    }

    private static ServiceHandlers.Rule rule(JsonObject object, Map<String, String> namesById) {
        List<String> handlers = new ArrayList<>();
        for (JsonElement subject : object.getAsJsonArray("subjects")) {
            JsonObject owner = subject.getAsJsonObject();
            if (HANDLER_SUBJECT.equals(string(owner, KIND))) {
                String id = string(owner, ID);
                String name = namesById.get(id);
                handlers.add(name == null ? id.substring(id.lastIndexOf('.') + 1) : name);
            }
        }
        String id = string(object, "rule");
        String requirement = switch (id == null ? "" : id) {
            case "structure.exactlyOne" -> "exactly one of";
            case "structure.atMostOne" -> "at most one of";
            case "structure.atLeastOne" -> "at least one of";
            case "structure.allOrNone" -> "all or none of";
            default -> null;
        };
        return new ServiceHandlers.Rule(requirement, requirement == null ? List.of() : handlers,
                string(object, "message"));
    }

    private static String type(JsonElement node, String prefix) {
        if (node.isJsonArray()) {
            return union(node.getAsJsonArray(), prefix);
        }
        JsonObject object = node.getAsJsonObject();
        if (object.has(SHAPE)) {
            return shaped(object, prefix);
        }
        String name = object.get(NAME).getAsString();
        if (booleanOf(object, "builtin")) {
            return name;
        }
        if (object.has("packageInfo")) {
            JsonObject module = object.getAsJsonObject("packageInfo");
            return new ModuleRef(module.get("org").getAsString(), module.get("moduleName").getAsString()).prefix()
                    + ":" + name;
        }
        return prefix + ":" + name;
    }

    private static String shaped(JsonObject object, String prefix) {
        String element = type(object.get(ELEMENT_TYPE), prefix);
        return switch (object.get(SHAPE).getAsString()) {
            case "array" -> (element.contains("|") ? "(" + element + ")" : element) + "[]";
            case "readonly" -> "readonly & " + element;
            case "stream" -> object.has("completionType")
                    ? "stream<" + element + ", " + type(object.get("completionType"), prefix) + ">"
                    : "stream<" + element + ">";
            default -> throw new IllegalArgumentException("unknown shape");
        };
    }

    private static String union(JsonArray members, String prefix) {
        List<String> rendered = new ArrayList<>();
        boolean nilable = false;
        for (JsonElement member : members) {
            String text = type(member, prefix);
            if (NIL.equals(text)) {
                nilable = true;
            } else {
                rendered.add(text);
            }
        }
        if (rendered.isEmpty()) {
            return NIL;
        }
        String joined = String.join("|", rendered);
        if (!nilable) {
            return joined;
        }
        return rendered.size() > 1 ? "(" + joined + ")?" : joined + "?";
    }

    private static boolean booleanOf(JsonObject object, String key) {
        return object.has(key) && object.get(key).getAsBoolean();
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
    }
}
