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
import com.google.gson.JsonObject;

import java.util.List;

/**
 * {@link DiscoverResult} → the structured JSON an agent's tooling reads by default.
 *
 * <p>Field names are exactly the RFC's own worked examples where one exists. Every answer is written as ONE line —
 * an agent's tooling cuts output with {@code head}/{@code tail} by line, so a line per array element is a cut
 * through the middle of the answer — with fields in the order they are added here.
 *
 * @since 0.1.0
 */
public final class JsonRenderer {

    private JsonRenderer() {
    }

    public static String render(DiscoverResult result) {
        return toJson(result).toString();
    }

    private static JsonObject toJson(DiscoverResult result) {
        return switch (result) {
            case DiscoverResult.BucketList bucketList -> bucketList(bucketList);
            case DiscoverResult.ContainerRoster roster -> containerRoster(roster);
            case DiscoverResult.PathGroups groups -> pathGroups(groups);
            case DiscoverResult.ResourceList resources -> resourceList(resources);
            case DiscoverResult.MethodList methods -> methodList(methods);
            case DiscoverResult.Readme readme -> readme(readme);
            case DiscoverResult.ReadmeChunks chunks -> readmeChunks(chunks);
            case DiscoverResult.Signature signature -> signature(signature);
            case DiscoverResult.MixedListing mixed -> mixedListing(mixed);
            case DiscoverResult.NoMatch noMatch -> noMatch(noMatch);
            case DiscoverResult.Owners owners -> owners(owners);
            case DiscoverResult.EmptyBucket empty -> emptyBucket(empty);
            case DiscoverResult.TypeRoster roster -> typeRoster(roster);
            case DiscoverResult.TypeDeclaration declaration -> typeDeclaration(declaration);
        };
    }

    private static JsonObject bucketList(DiscoverResult.BucketList bucketList) {
        JsonObject json = new JsonObject();
        JsonArray buckets = new JsonArray();
        bucketList.buckets().forEach(buckets::add);
        json.add("buckets", buckets);
        if (!bucketList.submodules().isEmpty()) {
            JsonArray submodules = new JsonArray();
            for (DiscoverResult.BucketList.Submodule submodule : bucketList.submodules()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", submodule.name());
                entry.addProperty("summary", submodule.summary());
                entry.addProperty("command", submodule.command());
                submodules.add(entry);
            }
            json.add("submodules", submodules);
        }
        if (bucketList.warning() != null) {
            json.addProperty("warning", bucketList.warning());
        }
        return json;
    }

    private static JsonObject containerRoster(DiscoverResult.ContainerRoster roster) {
        JsonObject json = new JsonObject();
        JsonArray containers = new JsonArray();
        for (DiscoverResult.ContainerRoster.Container container : roster.containers()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", container.name());
            if (container.resources() > 0) {
                entry.addProperty("resources", container.resources());
            }
            if (container.remote() > 0) {
                entry.addProperty("remote", container.remote());
            }
            if (container.normal() > 0) {
                entry.addProperty("normal", container.normal());
            }
            if (container.listener() != null) {
                entry.addProperty("listener", container.listener());
            }
            if (container.unconfirmed() != null) {
                entry.addProperty("confirmed", false);
            }
            entry.addProperty("command", container.command());
            containers.add(entry);
        }
        json.add("containers", containers);
        json.addProperty("shown", roster.containers().size() + roster.notAttachable().size());
        json.addProperty("total", roster.total());
        addPaging(json, roster.paging());
        if (roster.next() != null) {
            json.addProperty("next", roster.next());
        }
        if (!roster.notAttachable().isEmpty()) {
            JsonArray notAttachable = new JsonArray();
            for (DiscoverResult.ContainerRoster.NotAttachable type : roster.notAttachable()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", type.name());
                entry.addProperty("command", type.command());
                notAttachable.add(entry);
            }
            json.add("notAttachable", notAttachable);
        }
        if (roster.notAttachableTotal() > roster.notAttachable().size()) {
            json.addProperty("notAttachableTotal", roster.notAttachableTotal());
        }
        if (roster.warning() != null) {
            json.addProperty("warning", roster.warning());
        }
        return json;
    }

    private static JsonObject pathGroups(DiscoverResult.PathGroups groups) {
        JsonObject json = new JsonObject();
        addIfPresent(json, "container", groups.container());
        if (!groups.resources().isEmpty()) {
            json.add("resources", resources(groups.resources()));
        }
        JsonArray array = new JsonArray();
        for (DiscoverResult.PathGroups.Group group : groups.groups()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", group.name());
            entry.addProperty("count", group.count());
            entry.addProperty("command", group.command());
            array.add(entry);
        }
        json.add("groups", array);
        JsonObject counts = new JsonObject();
        addIfPositive(counts, "resources", groups.counts().resources());
        addIfPositive(counts, "groups", groups.counts().groups());
        json.add("counts", counts);
        json.addProperty("shown", groups.resources().size() + groups.groups().size());
        json.addProperty("total", groups.total());
        addPaging(json, groups.paging());
        if (groups.next() != null) {
            json.addProperty("next", groups.next());
        }
        if (groups.warning() != null) {
            json.addProperty("warning", groups.warning());
        }
        if (groups.note() != null) {
            json.addProperty("note", groups.note());
        }
        return json;
    }

    private static JsonObject resourceList(DiscoverResult.ResourceList resources) {
        JsonObject json = new JsonObject();
        addIfPresent(json, "container", resources.container());
        json.add("resources", resources(resources.resources()));
        json.addProperty("shown", resources.shown());
        json.addProperty("total", resources.total());
        addPaging(json, resources.paging());
        if (resources.next() != null) {
            json.addProperty("next", resources.next());
        }
        addDocumented(json, resources.documented());
        if (resources.warning() != null) {
            json.addProperty("warning", resources.warning());
        }
        if (resources.note() != null) {
            json.addProperty("note", resources.note());
        }
        return json;
    }

    private static JsonObject methodList(DiscoverResult.MethodList methods) {
        JsonObject json = new JsonObject();
        addIfPresent(json, "container", methods.container());
        json.add("methods", methods(methods.methods()));
        json.addProperty("shown", methods.shown());
        json.addProperty("total", methods.total());
        addPaging(json, methods.paging());
        if (methods.next() != null) {
            json.addProperty("next", methods.next());
        }
        addDocumented(json, methods.documented());
        if (methods.warning() != null) {
            json.addProperty("warning", methods.warning());
        }
        if (methods.note() != null) {
            json.addProperty("note", methods.note());
        }
        return json;
    }

    private static JsonObject readme(DiscoverResult.Readme readme) {
        JsonObject json = new JsonObject();
        json.addProperty("readme", readme.markdown());
        json.addProperty("lines", readme.lines());
        if (readme.chunk() != null) {
            json.addProperty("chunk", readme.chunk());
            json.addProperty("of", readme.of());
            json.addProperty("title", readme.title());
        }
        if (readme.warning() != null) {
            json.addProperty("warning", readme.warning());
        }
        return json;
    }

    private static JsonObject readmeChunks(DiscoverResult.ReadmeChunks chunks) {
        JsonObject json = new JsonObject();
        JsonArray array = new JsonArray();
        for (DiscoverResult.ReadmeChunks.Chunk chunk : chunks.chunks()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("number", chunk.number());
            entry.addProperty("title", chunk.title());
            entry.addProperty("lines", chunk.lines());
            entry.addProperty("command", chunk.command());
            array.add(entry);
        }
        json.add("chunks", array);
        json.addProperty("shown", chunks.chunks().size());
        json.addProperty("total", chunks.total());
        addPaging(json, chunks.paging());
        if (chunks.next() != null) {
            json.addProperty("next", chunks.next());
        }
        if (chunks.warning() != null) {
            json.addProperty("warning", chunks.warning());
        }
        return json;
    }

    private static JsonArray resources(List<DiscoverResult.ResourceList.Resource> resources) {
        JsonArray array = new JsonArray();
        for (DiscoverResult.ResourceList.Resource resource : resources) {
            JsonObject entry = new JsonObject();
            entry.addProperty("path", resource.path());
            entry.add("accessors", strings(resource.accessors()));
            JsonObject commands = new JsonObject();
            resource.commands().forEach(commands::addProperty);
            entry.add("commands", commands);
            array.add(entry);
        }
        return array;
    }

    private static JsonArray methods(List<DiscoverResult.Method> methods) {
        JsonArray array = new JsonArray();
        for (DiscoverResult.Method method : methods) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", method.name());
            entry.addProperty("command", method.command());
            array.add(entry);
        }
        return array;
    }

    private static JsonObject signature(DiscoverResult.Signature signature) {
        JsonObject json = new JsonObject();
        addIfPresent(json, "container", signature.container());
        json.addProperty("kind", signature.kind());
        addIfPresent(json, "name", signature.name());
        addIfPresent(json, "accessor", signature.accessor());
        addIfPresent(json, "path", signature.path());
        json.addProperty("form", signature.form());
        json.addProperty("declaration", signature.declaration());
        JsonArray params = new JsonArray();
        for (DiscoverResult.Signature.Parameter param : signature.params()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", param.name());
            entry.addProperty("type", param.type());
            addIfPresent(entry, "default", param.defaultValue());
            addIfPresent(entry, "kind", param.kind());
            if (!param.description().isEmpty()) {
                entry.addProperty("description", param.description());
            }
            params.add(entry);
        }
        json.add("params", params);
        addIfPresent(json, "returns", signature.returns());
        if (signature.deprecated()) {
            json.addProperty("deprecated", true);
        }
        json.add("types", types(signature.types()));
        if (!signature.omitted().isEmpty()) {
            json.add("omitted", methods(signature.omitted()));
        }
        if (!signature.foreign().isEmpty()) {
            json.add("foreign", foreign(signature.foreign()));
        }
        addDocumented(json, signature.documented());
        addPaging(json, signature.paging());
        addIfPresent(json, "next", signature.next());
        addNotices(json, signature.warning(), signature.note());
        return json;
    }

    private static JsonObject mixedListing(DiscoverResult.MixedListing mixed) {
        JsonObject json = new JsonObject();
        addIfPresent(json, "container", mixed.container());
        if (!mixed.resources().isEmpty()) {
            json.add("resources", resources(mixed.resources()));
        }
        if (!mixed.remote().isEmpty()) {
            json.add("remote", methods(mixed.remote()));
        }
        if (!mixed.normal().isEmpty()) {
            json.add("normal", methods(mixed.normal()));
        }
        JsonObject counts = new JsonObject();
        addIfPositive(counts, "resources", mixed.counts().resources());
        addIfPositive(counts, "remote", mixed.counts().remote());
        addIfPositive(counts, "normal", mixed.counts().normal());
        json.add("counts", counts);
        json.addProperty("shown", mixed.shown());
        json.addProperty("total", mixed.total());
        addPaging(json, mixed.paging());
        addIfPresent(json, "next", mixed.next());
        addDocumented(json, mixed.documented());
        addNotices(json, mixed.warning(), mixed.note());
        return json;
    }

    private static JsonObject noMatch(DiscoverResult.NoMatch noMatch) {
        JsonObject json = new JsonObject();
        json.addProperty("requested", noMatch.requested());
        addIfPresent(json, "container", noMatch.container());
        json.add("candidates", strings(noMatch.candidates()));
        if (!noMatch.paths().isEmpty()) {
            JsonArray paths = new JsonArray();
            for (DiscoverResult.NoMatch.Alternative alternative : noMatch.paths()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("path", alternative.path());
                entry.addProperty("command", alternative.command());
                paths.add(entry);
            }
            json.add("paths", paths);
        }
        if (noMatch.available() != null) {
            json.add("available", toJson(noMatch.available()));
        }
        addIfPresent(json, "next", noMatch.next());
        addDocumented(json, noMatch.documented());
        addPaging(json, noMatch.paging());
        addNotices(json, noMatch.warning(), noMatch.note());
        return json;
    }

    private static JsonObject owners(DiscoverResult.Owners owners) {
        JsonObject json = new JsonObject();
        json.addProperty("requested", owners.requested());
        JsonArray array = new JsonArray();
        for (DiscoverResult.Owners.Owner owner : owners.owners()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", owner.name());
            entry.addProperty("matches", owner.matches());
            entry.addProperty("command", owner.command());
            array.add(entry);
        }
        json.add("owners", array);
        json.addProperty("shown", owners.owners().size());
        json.addProperty("total", owners.total());
        addPaging(json, owners.paging());
        addIfPresent(json, "next", owners.next());
        addNotices(json, owners.warning(), owners.note());
        return json;
    }

    private static JsonObject emptyBucket(DiscoverResult.EmptyBucket empty) {
        JsonObject json = new JsonObject();
        json.addProperty("bucket", empty.bucket());
        json.addProperty("total", 0);
        JsonArray elsewhere = new JsonArray();
        for (DiscoverResult.EmptyBucket.Elsewhere other : empty.elsewhere()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("bucket", other.bucket());
            entry.addProperty("count", other.count());
            entry.addProperty("command", other.command());
            elsewhere.add(entry);
        }
        json.add("elsewhere", elsewhere);
        addIfPresent(json, "warning", empty.warning());
        return json;
    }

    private static JsonObject typeRoster(DiscoverResult.TypeRoster roster) {
        JsonObject json = new JsonObject();
        JsonObject sections = new JsonObject();
        roster.sections().forEach(section -> sections.add(section.kind(), methods(section.entries())));
        json.add("sections", sections);
        JsonObject counts = new JsonObject();
        roster.counts().forEach((kind, count) -> addIfPositive(counts, kind, count));
        json.add("counts", counts);
        json.addProperty("shown", roster.shown());
        json.addProperty("total", roster.total());
        addPaging(json, roster.paging());
        addIfPresent(json, "next", roster.next());
        addDocumented(json, roster.documented());
        addNotices(json, roster.warning(), roster.note());
        return json;
    }

    private static JsonObject typeDeclaration(DiscoverResult.TypeDeclaration declaration) {
        JsonObject json = new JsonObject();
        json.addProperty("name", declaration.name());
        json.addProperty("kind", declaration.kind());
        json.addProperty("declaration", declaration.declaration());
        json.add("types", types(declaration.types()));
        if (!declaration.omitted().isEmpty()) {
            json.add("omitted", methods(declaration.omitted()));
        }
        if (!declaration.foreign().isEmpty()) {
            json.add("foreign", foreign(declaration.foreign()));
        }
        addNotices(json, declaration.warning(), declaration.note());
        return json;
    }

    private static JsonArray types(List<DiscoverResult.Signature.Type> types) {
        JsonArray array = new JsonArray();
        for (DiscoverResult.Signature.Type type : types) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", type.name());
            entry.addProperty("declaration", type.declaration());
            array.add(entry);
        }
        return array;
    }

    private static JsonArray foreign(List<DiscoverResult.Foreign> foreign) {
        JsonArray array = new JsonArray();
        for (DiscoverResult.Foreign type : foreign) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", type.name());
            entry.addProperty("module", type.module());
            addIfPresent(entry, "version", type.version());
            addIfPresent(entry, "command", type.command());
            array.add(entry);
        }
        return array;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static void addIfPresent(JsonObject json, String field, String value) {
        if (value != null) {
            json.addProperty(field, value);
        }
    }

    private static void addDocumented(JsonObject json, DiscoverResult.Documented documented) {
        if (documented.total() > 0) {
            json.add("documented", strings(documented.names()));
            json.addProperty("documentedTotal", documented.total());
        }
    }

    private static void addIfPositive(JsonObject json, String field, int value) {
        if (value > 0) {
            json.addProperty(field, value);
        }
    }

    private static void addPaging(JsonObject json, DiscoverResult.Paging paging) {
        if (paging != null) {
            json.addProperty("page", paging.page());
            json.addProperty("pages", paging.pages());
        }
    }

    private static void addNotices(JsonObject json, String warning, String note) {
        addIfPresent(json, "warning", warning);
        addIfPresent(json, "note", note);
    }
}
