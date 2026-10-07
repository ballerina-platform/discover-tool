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

package io.ballerina.tools.discover.model;

import io.ballerina.tools.discover.central.schema.CentralDocs;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Central's module → the finished {@link Library}, in one place.
 *
 * <p>The CLI and the recorded corpus both build through here, so the snapshots always measure the pipeline
 * the tool runs.
 *
 * <p>The order is not arbitrary. {@link Patches} runs after {@link FromCentral} because a correction needs
 * something to correct, and {@link Defaults} runs after {@code Patches} because a patch can inject the very
 * declaration that decides whether a printed default names something the document has.
 *
 * @since 0.1.0
 */
public final class Pipeline {

    private Pipeline() {
    }

    public static Library build(CentralDocs.Module module) {
        return build(module, Optional.empty());
    }

    /** @param inclusions the module's object inclusions, read from its published source, when it was read */
    public static Library build(CentralDocs.Module module, Optional<ObjectInclusions> inclusions) {
        return Defaults.markUnwritable(
                Patches.applyPatches(FromCentral.fromCentral(module, inclusions)), publishedButUnrendered(module));
    }

    private static Set<String> publishedButUnrendered(CentralDocs.Module module) {
        return Stream.concat(module.variables().stream(), module.configurables().stream())
                .map(CentralDocs.VariableDecl::name)
                .collect(Collectors.toSet());
    }
}
