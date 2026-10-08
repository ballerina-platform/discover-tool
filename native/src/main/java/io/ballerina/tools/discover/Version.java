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

package io.ballerina.tools.discover;

import java.util.regex.Pattern;

/**
 * A published version, obtainable only through the parser.
 *
 * <p>The pattern is deliberately permissive: Central publishes versions this reader only ever echoes
 * back, so the check exists to reject an argument that is obviously a package name or a shell mishap,
 * not to enforce semver.
 *
 * @since 0.1.0
 */
public final class Version {

    private static final Pattern PATTERN = Pattern.compile("^[A-Za-z0-9_.+-]+$");

    private static final Pattern COMPLETE = Pattern.compile("^\\d+\\.\\d+\\.\\d+([-+.].*)?$");

    private final String text;

    private Version(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    /**
     * {@code .} and {@code ..} pass the pattern but are path traversal once the docs cache joins a segment
     * into a file path. The cache re-checks its segments; this is the outer of two independent guards.
     */
    static boolean isTraversal(String segment) {
        return ".".equals(segment) || "..".equals(segment);
    }

    /**
     * Whether a caller-typed version is one Central could publish: {@code major.minor.patch}, with any
     * pre-release or build suffix. Stricter than {@link #parse}, which also reads versions off the network, and
     * no bucket name, selector or path can look like one.
     */
    public static boolean isComplete(String input) {
        return COMPLETE.matcher(input).matches() && parse(input).isOk();
    }

    public static Result<Version> parse(String input) {
        String trimmed = input.trim();
        if (!PATTERN.matcher(trimmed).matches() || isTraversal(trimmed)) {
            return Result.err(new Failure.Validation(
                    "Invalid version '" + input + "'.",
                    "Pass a published version such as '6.0.0', or omit it to resolve the latest."));
        }
        return Result.ok(new Version(trimmed));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Version that && text.equals(that.text);
    }

    @Override
    public int hashCode() {
        return text.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }
}
