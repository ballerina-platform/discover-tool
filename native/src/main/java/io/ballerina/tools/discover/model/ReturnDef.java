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

/**
 * What a callable returns. {@code description} is {@code null} when Central published none.
 *
 * <p>An empty type name is "returns nothing" and renders as no {@code returns} clause at all, as the source
 * writes it. Not {@code nil}: that is the English name of the type Ballerina spells {@code ()}.
 *
 * @param type the returned type, or empty for "returns nothing"
 * @param description the return's own documentation, or {@code null} when Central published none
 * @since 0.1.0
 */
public record ReturnDef(TypeRef type, String description) {

    private static final ReturnDef NONE = new ReturnDef(new TypeRef(""), null);

    /** A callable Central published no return parameter for. */
    public static ReturnDef none() {
        return NONE;
    }

    public boolean hasType() {
        return !type.name().isEmpty();
    }

    public boolean hasDescription() {
        return description != null && !description.isEmpty();
    }
}
