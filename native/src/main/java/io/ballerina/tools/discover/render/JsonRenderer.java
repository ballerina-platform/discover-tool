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

/**
 * {@link DiscoverResult} → the structured JSON an agent's tooling reads by default.
 *
 * <p>Field names are exactly the RFC's own worked examples where one exists. {@link CompactJson} is the only
 * thing that turns the {@link JsonObject} built here into bytes, so every response shares one layout rule.
 *
 * @since 0.1.0
 */
public final class JsonRenderer {

    private JsonRenderer() {
    }

    public static String render(DiscoverResult result) {
        return CompactJson.write(toJson(result));
    }

    private static JsonObject toJson(DiscoverResult result) {
        return switch (result) {
            case DiscoverResult.BucketList bucketList -> bucketList(bucketList);
            case DiscoverResult.ContainerRoster roster -> containerRoster(roster);
            case DiscoverResult.PathGroups groups -> pathGroups(groups);
            case DiscoverResult.ResourceList resources -> resourceList(resources);
            case DiscoverResult.MethodList methods -> methodList(methods);
        };
    }

    private static JsonObject bucketList(DiscoverResult.BucketList bucketList) {
        JsonObject json = new JsonObject();
        JsonArray buckets = new JsonArray();
        bucketList.buckets().forEach(buckets::add);
        json.add("buckets", buckets);
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
            entry.addProperty("call", container.call());
            containers.add(entry);
        }
        json.add("containers", containers);
        json.addProperty("shown", roster.containers().size());
        json.addProperty("total", roster.total());
        if (roster.next() != null) {
            json.addProperty("next", roster.next());
        }
        if (roster.warning() != null) {
            json.addProperty("warning", roster.warning());
        }
        return json;
    }

    private static JsonObject pathGroups(DiscoverResult.PathGroups groups) {
        JsonObject json = new JsonObject();
        JsonArray array = new JsonArray();
        for (DiscoverResult.PathGroups.Group group : groups.groups()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", group.name());
            entry.addProperty("count", group.count());
            entry.addProperty("call", group.call());
            array.add(entry);
        }
        json.add("groups", array);
        json.addProperty("shown", groups.groups().size());
        json.addProperty("total", groups.total());
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
        JsonArray array = new JsonArray();
        for (DiscoverResult.ResourceList.Resource resource : resources.resources()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("path", resource.path());
            JsonArray accessors = new JsonArray();
            resource.accessors().forEach(accessors::add);
            entry.add("accessors", accessors);
            // Deliberately omitted on a multi-accessor entry: a flat `call` field would have to guess which
            // accessor, which is exactly the "don't guess, be explicit or say nothing" the RFC states for this.
            if (resource.call() != null) {
                entry.addProperty("call", resource.call());
            }
            array.add(entry);
        }
        json.add("resources", array);
        json.addProperty("shown", resources.shown());
        json.addProperty("total", resources.total());
        if (resources.next() != null) {
            json.addProperty("next", resources.next());
        }
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
        JsonArray array = new JsonArray();
        methods.methods().forEach(array::add);
        json.add("methods", array);
        json.addProperty("shown", methods.shown());
        json.addProperty("total", methods.total());
        if (methods.next() != null) {
            json.addProperty("next", methods.next());
        }
        if (methods.warning() != null) {
            json.addProperty("warning", methods.warning());
        }
        if (methods.note() != null) {
            json.addProperty("note", methods.note());
        }
        return json;
    }
}
