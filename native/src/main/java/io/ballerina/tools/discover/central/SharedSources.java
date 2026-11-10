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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A repository whose module sources are fetched once per module: the inclusions and the handler description both
 * read the same bala, which can be tens of megabytes, so a run that needs both must not download it twice.
 *
 * @since 0.1.0
 */
public final class SharedSources implements PackageRepository {

    private record Key(String qualified, String version, String moduleId) { }

    private final PackageRepository delegate;
    private final Map<Key, Optional<Map<String, String>>> fetched = new HashMap<>();

    public SharedSources(PackageRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public Result<CentralClient.ResolvedVersion> resolveVersion(QualifiedName qualified, HttpOptions options) {
        return delegate.resolveVersion(qualified, options);
    }

    @Override
    public Result<CentralDocs> fetchDocs(
            QualifiedName qualified, CentralClient.ResolvedVersion resolved, HttpOptions options) {
        return delegate.fetchDocs(qualified, resolved, options);
    }

    @Override
    public Optional<Map<String, String>> fetchModuleSources(
            QualifiedName qualified, CentralClient.ResolvedVersion resolved, String moduleId, HttpOptions options) {
        return fetched.computeIfAbsent(new Key(qualified.qualified(), resolved.version().text(), moduleId),
                key -> delegate.fetchModuleSources(qualified, resolved, moduleId, options));
    }

    @Override
    public Result<CentralDocs> fetchModuleDocs(
            QualifiedName qualified, String submodule, CentralClient.ResolvedVersion resolved, HttpOptions options) {
        return delegate.fetchModuleDocs(qualified, submodule, resolved, options);
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public String describe() {
        return delegate.describe();
    }
}
