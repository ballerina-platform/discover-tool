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
 * What the caller typed for the package: {@code <org>/<name>[:<version>]}, the form {@code bal pull} takes.
 *
 * <p>The version is optional and, when written, complete: {@code ballerina/http:2.15} names no published version,
 * and reading the nearest one instead would be a guess.
 *
 * @param qualified the package
 * @param version the version written after it, or {@code null} to resolve one
 * @since 0.1.0
 */
public record Coordinate(QualifiedName qualified, Version version) {

    /** An import's keyword escape, {@code ballerinax/'client.config}: the package itself is unquoted. */
    private static final Pattern ESCAPE = Pattern.compile("(^|[/.])'");

    public static Result<Coordinate> parse(String input) {
        String trimmed = input.trim();
        int colon = trimmed.indexOf(':');
        String pkg = ESCAPE.matcher(colon < 0 ? trimmed : trimmed.substring(0, colon)).replaceAll("$1");
        Result<QualifiedName> qualified = QualifiedName.parse(pkg);
        if (!qualified.isOk()) {
            return qualified.cast();
        }
        if (colon < 0) {
            return Result.ok(new Coordinate(qualified.value(), null));
        }
        String version = trimmed.substring(colon + 1);
        if (!Version.isComplete(version)) {
            return Result.err(new Failure.Validation(
                    "'" + version + "' in '" + input + "' is not a complete version.",
                    "Write it in full, " + qualified.value().qualified() + ":<major>.<minor>.<patch>, or drop it "
                            + "to read the version your project locks or Central's latest: "
                            + qualified.value().qualified()));
        }
        return Result.ok(new Coordinate(qualified.value(), Version.parse(version).value()));
    }

    /** The version as typed, or {@code null}. */
    public String versionText() {
        return version == null ? null : version.text();
    }
}
