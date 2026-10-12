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
import io.ballerina.compiler.syntax.tree.ImportDeclarationNode;
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
import io.ballerina.tools.discover.cache.DocsCache;
import io.ballerina.tools.discover.central.CentralClient;
import io.ballerina.tools.discover.central.HttpOptions;
import io.ballerina.tools.discover.central.PackageRepository;
import io.ballerina.tools.discover.model.InlineRecords;
import io.ballerina.tools.discover.model.ObjectInclusions;
import io.ballerina.tools.text.TextDocuments;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A module's object inclusions and inline-record details, read from its published source with the Ballerina parser.
 *
 * <p>The parser is the {@code bal} distribution's own ({@code bre/lib}), which is the classpath this tool runs
 * on — compiled against, never bundled.
 *
 * <p>Everything here degrades to empty rather than failing: an unreachable archive, an unreadable one, a parser
 * missing from the classpath. The caller then answers from the docs payload alone.
 *
 * @since 0.1.0
 */
public final class SourceInclusions {

    /**
     * Which derivation produced a cached answer. Bumped whenever what {@link #parse} records changes, so an answer
     * an older reader cached is never served in place of what this one would derive.
     */
    public static final String DERIVATION = "5";

    private static final String INCLUSIONS = "inclusions";
    private static final String INLINE_RECORDS = "inlineRecords";
    private static final String NAME = "name";
    private static final String OPTIONAL = "optional";
    private static final String DEFAULT = "default";

    private static final String BAL_SUFFIX = ".bal";

    private SourceInclusions() {
    }

    /**
     * The facts of {@code moduleId} at the resolved version, from the cache when it holds them, otherwise from
     * {@code repository}'s published source — and then cached, since a version's source never changes.
     */
    public static Optional<SourceFacts> load(PackageRepository repository, QualifiedName qualified,
            CentralClient.ResolvedVersion resolved, String moduleId, HttpOptions options) {
        DocsCache cache = options.cache();
        DocsCache.DocsKey key = new DocsCache.DocsKey(
                repository.id(), qualified.org(), qualified.name(), resolved.version().text());
        if (!options.refresh()) {
            Optional<SourceFacts> cached = fromJson(cache.readInclusions(key, moduleId, DERIVATION));
            if (cached.isPresent()) {
                return cached;
            }
        }
        Optional<SourceFacts> read = repository.fetchModuleSources(qualified, resolved, moduleId, options)
                .flatMap(SourceInclusions::parseFacts);
        read.ifPresent(facts -> cache.writeInclusions(key, moduleId, DERIVATION, toJson(facts)));
        return read;
    }

    /**
     * Every object type declared across {@code files} that includes something, with what it includes: a bare
     * name for this module's own type, {@code org/module:Name} for another module's — the prefix a file wrote
     * resolved through that file's own imports, so {@code import ballerinax/cdc as c; *c:Service;} and
     * {@code *cdc:Service;} record the same thing. A prefix no import declares is dropped.
     */
    public static Optional<ObjectInclusions> parse(Map<String, String> files) {
        return parseFacts(files).map(SourceFacts::inclusions);
    }

    /** {@link #parse} together with what the files' inline records declare. */
    public static Optional<SourceFacts> parseFacts(Map<String, String> files) {
        try {
            Map<String, List<String>> byType = new LinkedHashMap<>();
            if (files.keySet().stream().noneMatch(file -> file.endsWith(BAL_SUFFIX))) {
                return Optional.empty();
            }
            List<SyntaxTree> trees = new ArrayList<>();
            files.entrySet().stream()
                    .filter(file -> file.getKey().endsWith(BAL_SUFFIX))
                    .forEach(file -> trees.add(SyntaxTree.from(TextDocuments.from(file.getValue()), file.getKey())));
            trees.forEach(tree -> collect(tree, byType));
            return Optional.of(new SourceFacts(new ObjectInclusions(byType),
                    InlineRecordSource.collect(trees.stream().<Node>map(SyntaxTree::rootNode).toList())));
        } catch (RuntimeException | LinkageError unparseable) {
            return Optional.empty();
        }
    }

    private static void collect(SyntaxTree tree, Map<String, List<String>> byType) {
        ModulePartNode root = tree.rootNode();
        Map<String, String> imports = imports(root);
        for (ModuleMemberDeclarationNode member : root.members()) {
            if (!(member instanceof TypeDefinitionNode definition)) {
                continue;
            }
            objectOf(definition.typeDescriptor()).ifPresent(object -> {
                List<String> included = new ArrayList<>();
                for (Node objectMember : object.members()) {
                    if (objectMember instanceof TypeReferenceNode reference) {
                        nameOf(reference.typeName(), imports).ifPresent(included::add);
                    }
                }
                if (!included.isEmpty()) {
                    byType.put(definition.typeName().text(), included);
                }
            });
        }
    }

    private static Map<String, String> imports(ModulePartNode root) {
        Map<String, String> imports = new LinkedHashMap<>();
        for (ImportDeclarationNode declaration : root.imports()) {
            if (declaration.orgName().isEmpty()) {
                continue;
            }
            List<String> segments = new ArrayList<>();
            declaration.moduleName().forEach(segment -> segments.add(unquoted(segment.text())));
            if (segments.isEmpty()) {
                continue;
            }
            String prefix = declaration.prefix()
                    .map(alias -> unquoted(alias.prefix().text()))
                    .orElse(segments.get(segments.size() - 1));
            imports.put(prefix, declaration.orgName().get().orgName().text() + "/" + String.join(".", segments));
        }
        return imports;
    }

    private static String unquoted(String identifier) {
        return identifier.startsWith("'") ? identifier.substring(1) : identifier;
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

    private static Optional<String> nameOf(Node reference, Map<String, String> imports) {
        return switch (reference) {
            case SimpleNameReferenceNode simple -> Optional.of(simple.name().text());
            case QualifiedNameReferenceNode qualified ->
                    Optional.ofNullable(imports.get(unquoted(qualified.modulePrefix().text())))
                            .map(module -> module + ":" + qualified.identifier().text());
            default -> Optional.empty();
        };
    }

    static JsonElement toJson(SourceFacts facts) {
        JsonObject json = new JsonObject();
        json.add(INCLUSIONS, toJson(facts.inclusions()));
        JsonArray records = new JsonArray();
        facts.inlineRecords().byFields().values().forEach(members -> {
            JsonArray array = new JsonArray();
            members.forEach((name, member) -> {
                JsonObject entry = new JsonObject();
                entry.addProperty(NAME, name);
                entry.addProperty(OPTIONAL, member.optional());
                if (member.defaultValue() != null) {
                    entry.addProperty(DEFAULT, member.defaultValue());
                }
                array.add(entry);
            });
            records.add(array);
        });
        json.add(INLINE_RECORDS, records);
        return json;
    }

    static Optional<SourceFacts> fromJson(JsonElement json) {
        if (json == null || !json.isJsonObject()) {
            return Optional.empty();
        }
        JsonObject object = json.getAsJsonObject();
        Optional<ObjectInclusions> inclusions = fromInclusionsJson(object.get(INCLUSIONS));
        Optional<InlineRecords.Declared> records = inlineRecordsFromJson(object.get(INLINE_RECORDS));
        if (inclusions.isEmpty() || records.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new SourceFacts(inclusions.get(), records.get()));
    }

    private static JsonElement toJson(ObjectInclusions inclusions) {
        JsonObject json = new JsonObject();
        inclusions.byType().forEach((type, included) -> {
            JsonArray array = new JsonArray();
            included.forEach(array::add);
            json.add(type, array);
        });
        return json;
    }

    private static Optional<InlineRecords.Declared> inlineRecordsFromJson(JsonElement json) {
        if (json == null || !json.isJsonArray()) {
            return Optional.empty();
        }
        Map<List<String>, Map<String, InlineRecords.Member>> byFields = new LinkedHashMap<>();
        for (JsonElement record : json.getAsJsonArray()) {
            if (!record.isJsonArray()) {
                return Optional.empty();
            }
            Map<String, InlineRecords.Member> members = new LinkedHashMap<>();
            for (JsonElement element : record.getAsJsonArray()) {
                if (!element.isJsonObject() || !isString(element.getAsJsonObject().get(NAME))
                        || !element.getAsJsonObject().has(OPTIONAL)) {
                    return Optional.empty();
                }
                JsonObject entry = element.getAsJsonObject();
                JsonElement defaultValue = entry.get(DEFAULT);
                members.put(entry.get(NAME).getAsString(), new InlineRecords.Member(
                        entry.get(OPTIONAL).getAsBoolean(),
                        defaultValue == null ? null : defaultValue.getAsString()));
            }
            byFields.put(InlineRecords.Declared.key(members.keySet()), members);
        }
        return Optional.of(new InlineRecords.Declared(byFields));
    }

    private static boolean isString(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    /** Empty for anything but an object of string arrays — a damaged entry is a miss, never a wrong answer. */
    private static Optional<ObjectInclusions> fromInclusionsJson(JsonElement json) {
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
