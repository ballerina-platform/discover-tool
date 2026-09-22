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
            case DiscoverResult.BucketList bucketList -> {
                JsonObject json = new JsonObject();
                JsonArray buckets = new JsonArray();
                bucketList.buckets().forEach(buckets::add);
                json.add("buckets", buckets);
                if (bucketList.warning() != null) {
                    json.addProperty("warning", bucketList.warning());
                }
                yield json;
            }
        };
    }
}
