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
import io.ballerina.compiler.syntax.tree.DistinctTypeDescriptorNode;
import io.ballerina.compiler.syntax.tree.IntersectionTypeDescriptorNode;
import io.ballerina.compiler.syntax.tree.ModuleMemberDeclarationNode;
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.ObjectTypeDescriptorNode;
import io.ballerina.compiler.syntax.tree.ParenthesisedTypeDescriptorNode;
import io.ballerina.compiler.syntax.tree.QualifiedNameReferenceNode;
import io.ballerina.compiler.syntax.tree.SimpleNameReferenceNode;
import io.ballerina.compiler.syntax.tree.SyntaxTree;
import io.ballerina.compiler.syntax.tree.TypeDefinitionNode;
import io.ballerina.compiler.syntax.tree.TypeReferenceNode;
import io.ballerina.tools.discover.QualifiedName;
import io.ballerina.tools.discover.Version;
import io.ballerina.tools.discover.cache.DocsCache;
import io.ballerina.tools.discover.central.HttpOptions;
import io.ballerina.tools.discover.central.PackageRepository;
import io.ballerina.tools.discover.model.ObjectInclusions;
import io.ballerina.tools.text.TextDocuments;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A module's object inclusions, read from its published source with the Ballerina parser.
 *
 * <p>The parser is the {@code bal} distribution's own ({@code bre/lib}), which is the classpath this tool runs
 * on — compiled against, never bundled.
 *
 * <p>Everything here degrades to empty rather than failing: an unreachable archive, an unreadable one, a parser
 * missing from the classpath. The caller then answers from the docs payload alone, which is what it did before
 * the source was read at all.
 *
 * @since 0.1.0
 */
public final class SourceInclusions {

    private SourceInclusions() {
    }

    /**
     * The inclusions of {@code moduleId} at {@code version}, from the cache when it holds them, otherwise from
     * {@code repository}'s published source — and then cached, since a version's source never changes.
     */
    public static Optional<ObjectInclusions> load(PackageRepository repository, QualifiedName qualified,
            Version version, String moduleId, HttpOptions options) {
        DocsCache cache = options.cache();
        DocsCache.DocsKey key =
                new DocsCache.DocsKey(repository.id(), qualified.org(), qualified.name(), version.text());
        if (!options.refresh()) {
            Optional<ObjectInclusions> cached = fromJson(cache.readInclusions(key, moduleId));
            if (cached.isPresent()) {
                return cached;
            }
        }
        Optional<ObjectInclusions> read = repository.fetchModuleSources(qualified, version, moduleId, options)
                .flatMap(SourceInclusions::parse);
        read.ifPresent(inclusions -> cache.writeInclusions(key, moduleId, toJson(inclusions)));
        return read;
    }

    /** Every object type declared across {@code files} that includes something, with what it includes. */
    public static Optional<ObjectInclusions> parse(Map<String, String> files) {
        try {
            Map<String, List<String>> byType = new LinkedHashMap<>();
            files.forEach((file, text) -> collect(SyntaxTree.from(TextDocuments.from(text), file), byType));
            return Optional.of(new ObjectInclusions(byType));
        } catch (RuntimeException | LinkageError unparseable) {
            return Optional.empty();
        }
    }

    private static void collect(SyntaxTree tree, Map<String, List<String>> byType) {
        ModulePartNode root = tree.rootNode();
        for (ModuleMemberDeclarationNode member : root.members()) {
            if (!(member instanceof TypeDefinitionNode definition)) {
                continue;
            }
            objectOf(definition.typeDescriptor()).ifPresent(object -> {
                List<String> included = new ArrayList<>();
                for (Node objectMember : object.members()) {
                    if (objectMember instanceof TypeReferenceNode reference) {
                        nameOf(reference.typeName()).ifPresent(included::add);
                    }
                }
                if (!included.isEmpty()) {
                    byType.put(definition.typeName().text(), included);
                }
            });
        }
    }

    private static Optional<ObjectTypeDescriptorNode> objectOf(Node type) {
        return switch (type) {
            case ObjectTypeDescriptorNode object -> Optional.of(object);
            case DistinctTypeDescriptorNode distinct -> objectOf(distinct.typeDescriptor());
            case ParenthesisedTypeDescriptorNode parenthesised -> objectOf(parenthesised.typedesc());
            case IntersectionTypeDescriptorNode intersection ->
                    objectOf(intersection.leftTypeDesc()).or(() -> objectOf(intersection.rightTypeDesc()));
            default -> Optional.empty();
        };
    }

    private static Optional<String> nameOf(Node reference) {
        return switch (reference) {
            case SimpleNameReferenceNode simple -> Optional.of(simple.name().text());
            case QualifiedNameReferenceNode qualified ->
                    Optional.of(qualified.modulePrefix().text() + ":" + qualified.identifier().text());
            default -> Optional.empty();
        };
    }

    static JsonElement toJson(ObjectInclusions inclusions) {
        JsonObject json = new JsonObject();
        inclusions.byType().forEach((type, included) -> {
            JsonArray array = new JsonArray();
            included.forEach(array::add);
            json.add(type, array);
        });
        return json;
    }

    /** Empty for anything but an object of string arrays — a damaged entry is a miss, never a wrong answer. */
    static Optional<ObjectInclusions> fromJson(JsonElement json) {
        if (json == null || !json.isJsonObject()) {
            return Optional.empty();
        }
        Map<String, List<String>> byType = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonArray()) {
                return Optional.empty();
            }
            List<String> included = new ArrayList<>();
            for (JsonElement name : entry.getValue().getAsJsonArray()) {
                if (!name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()) {
                    return Optional.empty();
                }
                included.add(name.getAsString());
            }
            byType.put(entry.getKey(), included);
        }
        return Optional.of(new ObjectInclusions(byType));
    }
}
