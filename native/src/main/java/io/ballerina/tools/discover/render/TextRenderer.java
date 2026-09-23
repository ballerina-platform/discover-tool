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

/**
 * {@link DiscoverResult} → the human-oriented text the RFC shows at an interactive terminal.
 *
 * <p>Deliberately terse — the RFC's own worked examples are one or two lines for exactly this shape
 * ({@code client, service, funcs, readme}), which is a real departure from this tool's earlier Markdown-report
 * convention (headings, a facts table, a {@code ## Next} section). That convention is not carried forward here:
 * it belongs to the buckets still rendered by {@code views.Containers} until the RFC rewrite reaches them too.
 *
 * @since 0.1.0
 */
public final class TextRenderer {

    private TextRenderer() {
    }

    public static String render(DiscoverResult result) {
        return switch (result) {
            case DiscoverResult.BucketList bucketList -> renderBucketList(bucketList);
        };
    }

    private static String renderBucketList(DiscoverResult.BucketList bucketList) {
        String list = bucketList.buckets().isEmpty() ? "none" : String.join(", ", bucketList.buckets());
        return bucketList.warning() == null ? list : "Warning: " + bucketList.warning() + "\n" + list;
    }
}
