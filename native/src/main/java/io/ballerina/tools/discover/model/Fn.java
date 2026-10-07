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
 * A function on a client, or at module scope.
 *
 * <p>A sealed hierarchy rather than one record with nullable fields: a resource function's accessor and its
 * path are SEPARATE fields, so they cannot be merged into one string, and the renderer switches over the cases
 * with no {@code default}, so a callable shape nobody renders is a compile error rather than a silently
 * dropped function.
 *
 * <p>{@code accessor} is a plain string rather than a closed set of HTTP methods: Ballerina's resource
 * accessor is an identifier, and {@code subscribe} (websub, graphql) is as legal as {@code get}.
 * Constraining it would reject real packages without buying anything.
 *
 * @since 0.1.0
 */
public sealed interface Fn {

    String description();

    List<Param> params();

    ReturnDef returns();

    /**
     * Whether calling this is discouraged. Central publishes {@code isDeprecated} on every callable form.
     */
    boolean isDeprecated();

    /**
     * Whether the callable is {@code isolated}.
     *
     * <p>It matters to a caller that is itself {@code isolated} — an isolated function may only call isolated
     * ones — and when a service contract has to be matched exactly, because the compiler's
     * {@code mismatched function signatures} message does not print the qualifier: it reports an expected and a
     * found signature that are textually identical.
     */
    boolean isIsolated();

    record Constructor(
            String description,
            List<Param> params,
            ReturnDef returns,
            boolean isDeprecated,
            boolean isIsolated)
            implements Fn {

        /** The name Ballerina declares every constructor under. */
        public static final String NAME = "init";
    }

    record Remote(
            String name,
            String description,
            List<Param> params,
            ReturnDef returns,
            boolean isDeprecated,
            boolean isIsolated)
            implements Fn, Standalone { }

    record Normal(
            String name,
            String description,
            List<Param> params,
            ReturnDef returns,
            boolean isDeprecated,
            boolean isIsolated)
            implements Fn, Standalone { }

    record Resource(
            String accessor,
            List<PathSegment> paths,
            String description,
            List<Param> params,
            ReturnDef returns,
            boolean isDeprecated,
            boolean isIsolated)
            implements Fn { }

    /**
     * A function at module scope. A constructor belongs to a class and a resource function to a client,
     * so neither can appear there — which is why {@link Library} names this type rather than {@code Fn}.
     */
    sealed interface Standalone extends Fn permits Remote, Normal {

        String name();
    }

    /**
     * One segment of a resource path. {@code Literal} carries the raw segment — including the odd
     * bracketed form Central emits without a type — while {@code Parameter} keeps the declared type and
     * name apart.
     */
    sealed interface PathSegment {

        record Literal(String text) implements PathSegment { }

        record Parameter(String type, String name) implements PathSegment { }
    }
}
