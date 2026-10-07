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

import java.util.List;

/**
 * A client class and everything callable on it.
 *
 * <p>{@code isIsolated} is part of the declaration rather than decoration: a caller whose own function is
 * {@code isolated} can only construct and call into an isolated client.
 *
 * @param name the client class's name
 * @param description the class's own documentation, verbatim
 * @param isIsolated whether Central declared the class {@code isolated}
 * @param functions everything callable on the client
 * @since 0.1.0
 */
public record ClientClass(String name, String description, boolean isIsolated, List<Fn> functions) {

    public ClientClass withFunctions(List<Fn> replacement) {
        return new ClientClass(name, description, isIsolated, replacement);
    }

    /**
     * The same declaration as a {@link TypeDef}, so that the name index (built from {@code TypeDef}s) can
     * address a client under {@code type}.
     *
     * <p>The two shapes stay separate because a client has no fields and no {@code distinct}/{@code readonly}
     * qualifiers, and Central publishes it under its own key.
     */
    public TypeDef.ObjectDef asObjectDef() {
        return new TypeDef.ObjectDef(
                name,
                description,
                TypeDef.ObjectDef.Form.CLASS,
                TypeDef.ObjectDef.Role.CLIENT,
                false,
                false,
                isIsolated,
                false,
                List.of(),
                functions);
    }
}
