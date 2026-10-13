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
import io.ballerina.tools.discover.model.ServiceHandlers;
import io.ballerina.tools.discover.symbols.Surface;

import java.util.List;
import java.util.Map;

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

    /** Each bucket's key in an overview: what one of its rows is, plural, as the type roster names them. */
    private static final Map<String, String> OVERVIEW_KEYS = Map.of(
            Surface.Scope.CLIENT.verb(), DiscoverResult.TypeRoster.Kind.CLIENTS.key(),
            Surface.Scope.SERVICE.verb(), DiscoverResult.TypeRoster.Kind.SERVICE_TYPES.key(),
            Surface.Scope.CLASS.verb(), DiscoverResult.TypeRoster.Kind.CLASSES.key(),
            Surface.Scope.MODULE.verb(), Surface.Scope.MODULE.plural());

    private JsonRenderer() {
    }

    public static String render(DiscoverResult result) {
        return toJson(result).toString();
    }

    /**
     * {@code result} led by the package it answers about — {@code qualified}, {@code version} and, under
     * {@code --module}, {@code module} — named as a failure names them.
     *
     * @param where the package, its version and module, or {@code null} for an answer about no one package
     */
    public static String render(DiscoverResult result, Where where) {
        JsonObject answer = new JsonObject();
        if (where != null) {
            answer.addProperty("qualified", where.qualified());
            answer.addProperty("version", where.version());
            addIfPresent(answer, "module", where.module());
        }
        toJson(result).entrySet().forEach(field -> answer.add(field.getKey(), field.getValue()));
        return answer.toString();
    }

    /**
     * The package an answer is about.
     *
     * @param qualified {@code org/name}
     * @param version the version that was read
     * @param module the {@code --module} submodule, or {@code null} for the default module
     */
    public record Where(String qualified, String version, String module) { }

    private static JsonObject toJson(DiscoverResult result) {
        return switch (result) {
            case DiscoverResult.Overview overview -> overview(overview);
            case DiscoverResult.ContainerRoster roster -> containerRoster(roster);
            case DiscoverResult.PathGroups groups -> pathGroups(groups);
            case DiscoverResult.ResourceList resources -> resourceList(resources);
            case DiscoverResult.MethodList methods -> methodList(methods);
            case DiscoverResult.ServiceHandlerList handlers -> serviceHandlers(handlers);
            case DiscoverResult.ServiceExamples examples -> serviceExamples(examples);
            case DiscoverResult.Readme readme -> readme(readme);
            case DiscoverResult.ReadmeSections sections -> readmeSections(sections);
            case DiscoverResult.Signature signature -> signature(signature);
            case DiscoverResult.MixedListing mixed -> mixedListing(mixed);
            case DiscoverResult.EmptyBucket empty -> emptyBucket(empty);
            case DiscoverResult.TypeRoster roster -> typeRoster(roster);
            case DiscoverResult.TypeDeclaration declaration -> typeDeclaration(declaration);
            case DiscoverResult.Search search -> search(search);
            case DiscoverResult.CentralSearch search -> centralSearch(search);
        };
    }

    private static JsonObject overview(DiscoverResult.Overview overview) {
        JsonObject json = new JsonObject();
        for (DiscoverResult.Overview.Section section : overview.sections()) {
            JsonObject entry = new JsonObject();
            JsonArray rows = new JsonArray();
            for (DiscoverResult.Overview.Row row : section.rows()) {
                JsonObject item = new JsonObject();
                item.addProperty("name", row.name());
                addIfPositive(item, "resources", row.resources());
                addIfPositive(item, "remote", row.remote());
                addIfPositive(item, "normal", row.normal());
                item.addProperty("command", row.command());
                rows.add(item);
            }
            entry.add("entries", rows);
            entry.addProperty("shown", section.rows().size());
            entry.addProperty("total", section.total());
            entry.addProperty("command", section.command());
            if (overview.members() != null && Surface.Scope.CLIENT.verb().equals(section.bucket())) {
                JsonObject members = toJson(overview.members());
                members.remove("warning");
                entry.add("members", members);
            }
            json.add(OVERVIEW_KEYS.getOrDefault(section.bucket(), section.bucket()), entry);
        }
        if (overview.types() != null) {
            JsonObject types = new JsonObject();
            JsonObject counts = new JsonObject();
            overview.types().counts().forEach((kind, count) -> counts.addProperty(kind.key(), count));
            types.add("counts", counts);
            types.addProperty("command", overview.types().command());
            json.add("types", types);
        }
        if (overview.readme() != null) {
            JsonObject readme = new JsonObject();
            readme.addProperty("sections", overview.readme().sections());
            readme.addProperty("command", overview.readme().command());
            json.add("readme", readme);
        }
        if (!overview.submodules().isEmpty()) {
            JsonArray submodules = new JsonArray();
            for (DiscoverResult.Overview.Submodule submodule : overview.submodules()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", submodule.name());
                entry.addProperty("summary", submodule.summary());
                entry.addProperty("command", submodule.command());
                submodules.add(entry);
            }
            json.add("submodules", submodules);
        }
        addIfPresent(json, "warning", overview.warning());
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
                entry.addProperty("unconfirmedReason", container.unconfirmed());
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

    private static JsonObject serviceHandlers(DiscoverResult.ServiceHandlerList list) {
        JsonObject json = new JsonObject();
        json.addProperty("container", list.container());
        JsonArray handlers = new JsonArray();
        for (ServiceHandlers.Handler handler : list.handlers()) {
            JsonObject entry = new JsonObject();
            addIfPresent(entry, "name", handler.name());
            entry.addProperty("kind", handler.kind().label());
            entry.addProperty("signature", handler.signature());
            entry.addProperty("required", handler.required());
            if (handler.repeatable()) {
                entry.addProperty("repeatable", true);
            }
            if (!handler.accessors().isEmpty()) {
                entry.add("accessors", strings(handler.accessors()));
            }
            addIfPresent(entry, "doc", handler.doc());
            addIfPresent(entry, "deprecated", handler.deprecated());
            JsonArray params = new JsonArray();
            for (ServiceHandlers.Param param : handler.params()) {
                JsonObject parameter = new JsonObject();
                addIfPresent(parameter, "name", param.name());
                parameter.addProperty("type", param.type());
                parameter.addProperty("required", param.required());
                if (param.repeatable()) {
                    parameter.addProperty("repeatable", true);
                }
                addIfPresent(parameter, "doc", param.doc());
                addIfPresent(parameter, "deprecated", param.deprecated());
                params.add(parameter);
            }
            entry.add("params", params);
            addIfPresent(entry, "returns", handler.returns());
            handlers.add(entry);
        }
        json.add("handlers", handlers);
        if (!list.rules().isEmpty()) {
            JsonArray rules = new JsonArray();
            for (ServiceHandlers.Rule rule : list.rules()) {
                JsonObject entry = new JsonObject();
                addIfPresent(entry, "requirement", rule.requirement());
                if (!rule.handlers().isEmpty()) {
                    entry.add("handlers", strings(rule.handlers()));
                }
                addIfPresent(entry, "message", rule.message());
                rules.add(entry);
            }
            json.add("rules", rules);
        }
        addIfPresent(json, "warning", list.warning());
        addIfPresent(json, "note", list.note());
        return json;
    }

    private static JsonObject serviceExamples(DiscoverResult.ServiceExamples examples) {
        JsonObject json = new JsonObject();
        json.addProperty("container", examples.container());
        json.add("methods", new JsonArray());
        json.addProperty("description", examples.description());
        json.add("examples", sectionRows(examples.sections()));
        addIfPresent(json, "next", examples.next());
        addNotices(json, examples.warning(), examples.note());
        return json;
    }

    private static JsonObject readme(DiscoverResult.Readme readme) {
        JsonObject json = new JsonObject();
        json.addProperty("readme", readme.markdown());
        json.addProperty("lines", readme.lines());
        if (readme.section() != null) {
            json.addProperty("section", readme.section());
            json.addProperty("of", readme.of());
            json.addProperty("title", readme.title());
        }
        if (readme.warning() != null) {
            json.addProperty("warning", readme.warning());
        }
        return json;
    }

    private static JsonObject readmeSections(DiscoverResult.ReadmeSections sections) {
        JsonObject json = new JsonObject();
        json.add("sections", sectionRows(sections.sections()));
        json.addProperty("matching", sections.matching());
        json.addProperty("shown", sections.sections().size());
        json.addProperty("total", sections.total());
        addPaging(json, sections.paging());
        if (sections.next() != null) {
            json.addProperty("next", sections.next());
        }
        if (sections.warning() != null) {
            json.addProperty("warning", sections.warning());
        }
        return json;
    }

    private static JsonArray sectionRows(List<DiscoverResult.ReadmeSections.Section> sections) {
        JsonArray array = new JsonArray();
        for (DiscoverResult.ReadmeSections.Section section : sections) {
            JsonObject entry = new JsonObject();
            entry.addProperty("number", section.number());
            entry.addProperty("title", section.title());
            entry.addProperty("lines", section.lines());
            entry.addProperty("command", section.command());
            array.add(entry);
        }
        return array;
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
        if (signature.create() != null) {
            JsonObject create = new JsonObject();
            create.addProperty("code", signature.create().code());
            addIfPresent(create, "init", signature.create().init());
            addIfPresent(create, "command", signature.create().command());
            json.add("create", create);
        }
        if (!signature.callers().isEmpty()) {
            json.add("callers", sectionRows(signature.callers()));
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
        roster.sections().forEach(section -> sections.add(section.kind().key(), methods(section.entries())));
        json.add("sections", sections);
        JsonObject counts = new JsonObject();
        roster.counts().forEach((kind, count) -> addIfPositive(counts, kind.key(), count));
        json.add("counts", counts);
        json.addProperty("shown", roster.shown());
        json.addProperty("total", roster.total());
        addPaging(json, roster.paging());
        addIfPresent(json, "next", roster.next());
        addDocumented(json, roster.documented());
        addIfPresent(json, "warning", roster.warning());
        return json;
    }

    private static JsonObject search(DiscoverResult.Search search) {
        JsonObject json = new JsonObject();
        json.add("keywords", strings(search.keywords()));
        for (DiscoverResult.Search.Group group : search.groups()) {
            JsonArray hits = new JsonArray();
            group.hits().forEach(hit -> hits.add(hit(group.kind(), hit)));
            json.add(group.kind().key(), hits);
        }
        JsonObject counts = new JsonObject();
        search.counts().forEach((kind, count) -> counts.addProperty(kind.key(), count));
        json.add("counts", counts);
        json.addProperty("shown", search.shown());
        json.addProperty("total", search.total());
        addPaging(json, search.paging());
        addIfPresent(json, "next", search.next());
        if (search.total() == 0) {
            json.addProperty("overview", search.overview());
        }
        addIfPresent(json, "warning", search.warning());
        return json;
    }

    private static JsonObject centralSearch(DiscoverResult.CentralSearch search) {
        JsonObject json = new JsonObject();
        json.add("keywords", strings(search.keywords()));
        JsonArray packages = new JsonArray();
        for (DiscoverResult.CentralSearch.PackageHit hit : search.packages()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("qualified", hit.qualified());
            entry.addProperty("version", hit.version());
            entry.addProperty("summary", hit.summary());
            entry.addProperty("command", hit.command());
            packages.add(entry);
        }
        json.add("packages", window(packages, search.packagesTotal()));
        JsonArray symbols = new JsonArray();
        for (DiscoverResult.CentralSearch.SymbolHit hit : search.symbols()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("qualified", hit.qualified());
            entry.addProperty("version", hit.version());
            addIfPresent(entry, "module", hit.module());
            entry.addProperty("kind", hit.kind());
            addIfPresent(entry, "container", hit.container());
            addIfPresent(entry, "name", hit.name());
            addIfPresent(entry, "path", hit.path());
            addIfPresent(entry, "accessor", hit.accessor());
            entry.addProperty("command", hit.command());
            symbols.add(entry);
        }
        json.add("symbols", window(symbols, search.symbolsTotal()));
        addPaging(json, search.paging());
        addIfPresent(json, "next", search.next());
        return json;
    }

    private static JsonObject window(JsonArray entries, int total) {
        JsonObject json = new JsonObject();
        json.add("entries", entries);
        json.addProperty("shown", entries.size());
        json.addProperty("total", total);
        return json;
    }

    private static JsonObject hit(DiscoverResult.Search.Kind kind, DiscoverResult.Search.Hit hit) {
        JsonObject entry = new JsonObject();
        switch (kind) {
            case RESOURCES -> {
                entry.addProperty("container", hit.container());
                entry.addProperty("path", hit.name());
                entry.addProperty("accessor", hit.detail());
            }
            case README -> {
                entry.addProperty("number", Integer.parseInt(hit.detail()));
                entry.addProperty("title", hit.name());
            }
            case TYPES -> {
                entry.addProperty("name", hit.name());
                entry.addProperty("kind", hit.detail());
            }
            default -> {
                addIfPresent(entry, "container", hit.container());
                entry.addProperty("name", hit.name());
            }
        }
        entry.addProperty("command", hit.command());
        return entry;
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
            JsonArray entries = new JsonArray();
            for (DiscoverResult.Documented.Entry entry : documented.entries()) {
                entries.add(switch (entry) {
                    case DiscoverResult.Method method -> methods(List.of(method)).get(0);
                    case DiscoverResult.ResourceList.Resource resource -> resources(List.of(resource)).get(0);
                });
            }
            json.add("documented", entries);
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
            json.addProperty("remaining", paging.remaining());
        }
    }

    private static void addNotices(JsonObject json, String warning, String note) {
        addIfPresent(json, "warning", warning);
        addIfPresent(json, "note", note);
    }
}
