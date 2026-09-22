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

import java.util.List;

/**
 * One structured shape {@code bal discover} can answer with — the "one source, multiple renderers" the RFC asks
 * for. A view builds one of these; {@link TextRenderer} and {@link JsonRenderer} are the only two things that turn
 * it into bytes, so the two renderers can never drift into describing different data.
 *
 * <p>Only the shape a real caller reaches today is defined so far — the bare-package bucket listing. The RFC also
 * names a roster, a grouped (path- or page-bounded) listing, a resource list and a single signature; those join
 * this sealed set as the views that produce them are rewritten onto it (the {@code Containers} rewrite), rather
 * than being pre-declared here ahead of knowing their exact fields.
 *
 * @since 0.1.0
 */
public sealed interface DiscoverResult {

    /**
     * A package's (or a targeted module's) own top-level buckets — the answer to a bare
     * {@code bal discover <org/name>} with no bucket and no selector.
     *
     * @param buckets the buckets this package actually declares, in a fixed order
     * @param warning why the loaded version cannot be trusted, or {@code null} when it was confirmed against the
     *     registry — see {@code Loader.unverifiedWarning}
     */
    record BucketList(List<String> buckets, String warning) implements DiscoverResult {

        public BucketList(List<String> buckets) {
            this(buckets, null);
        }
    }
}
