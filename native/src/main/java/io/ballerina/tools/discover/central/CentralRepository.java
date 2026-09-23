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

package io.ballerina.tools.discover.central;

import io.ballerina.tools.discover.QualifiedName;
import io.ballerina.tools.discover.Result;
import io.ballerina.tools.discover.central.schema.CentralDocs;

/**
 * Ballerina Central, as a {@link PackageRepository}.
 *
 * <p>A thin adapter rather than a move: {@link CentralClient}'s retry loop, backoff, version-walk and cache-read
 * logic stay exactly where they are, as the static methods a test can already drive directly — this class only
 * gives that logic an instance the {@link io.ballerina.tools.discover.Loader} can hold as an interface reference
 * instead of a static import.
 *
 * @since 0.1.0
 */
public final class CentralRepository implements PackageRepository {

    public static final CentralRepository INSTANCE = new CentralRepository();

    private CentralRepository() {
    }

    @Override
    public Result<CentralClient.ResolvedVersion> resolveVersion(QualifiedName qualified, HttpOptions options) {
        return CentralClient.resolveLatestVersion(qualified, options);
    }

    @Override
    public Result<CentralDocs> fetchDocs(
            QualifiedName qualified, CentralClient.ResolvedVersion resolved, HttpOptions options) {
        return CentralClient.fetchDocs(qualified, resolved, options);
    }

    @Override
    public String id() {
        return CentralClient.REPOSITORY_ID;
    }

    @Override
    public String describe() {
        return "Ballerina Central (" + CentralClient.CENTRAL_BASE_URL + ")";
    }
}
