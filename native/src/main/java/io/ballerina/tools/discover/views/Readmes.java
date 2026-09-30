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

package io.ballerina.tools.discover.views;

import io.ballerina.tools.discover.central.schema.CentralDocs;

import java.util.Optional;

/**
 * The resolved module's own readme — the answer to "how is this used", which a signature cannot give.
 *
 * <p>Central serves it as the module's {@code description}, byte-identical to the {@code docs/README.md} a
 * published {@code .bala} carries — verified against {@code ballerinax/kafka@4.6.5}, 7,463 bytes, zero diff.
 * Reading it here rather than off disk is what makes it available BEFORE a build has resolved the package,
 * which is exactly when a connector nobody has written against is hardest to guess at.
 *
 * @since 0.1.0
 */
public final class Readmes {

    private Readmes() {
    }

    /**
     * The resolved module's own guide, trimmed, or empty when it publishes none.
     *
     * <p>Reads only the one module {@link io.ballerina.tools.discover.Loader} already resolved — a package's
     * OTHER modules (submodules, or modules a multi-module payload happens to carry) are a {@code --module}
     * question, not this one's.
     */
    public static Optional<String> of(CentralDocs.Module module) {
        String markdown = module.description().orElse("").trim();
        return markdown.isEmpty() ? Optional.empty() : Optional.of(markdown);
    }
}
