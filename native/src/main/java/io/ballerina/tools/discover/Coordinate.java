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

import java.math.BigInteger;
import java.util.Locale;
import java.util.regex.Matcher;
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

    private static final char VERSION_SEPARATOR = ':';
    private static final char NPM_SEPARATOR = '@';
    private static final char QUOTE = '\'';
    private static final String VERSION_PREFIX = "v";
    private static final String ORG_PLACEHOLDER = "org";
    private static final String COMMAND = "bal discover ";
    private static final String COMPLETE_FORM = ":<major>.<minor>.<patch>";
    private static final Pattern THREE_NUMBERS = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");

    public static Result<Coordinate> parse(String input) {
        int colon = input.indexOf(VERSION_SEPARATOR);
        String pkg = colon < 0 ? input : input.substring(0, colon);
        if (pkg.indexOf(QUOTE) >= 0) {
            return fix("A package name is written unescaped, but " + input + " quotes part of it the way an "
                    + "import does.", input.replace(String.valueOf(QUOTE), ""));
        }
        Result<QualifiedName> qualified = QualifiedName.parse(pkg);
        if (!qualified.isOk()) {
            return invalidName(input, qualified);
        }
        if (colon < 0) {
            return Result.ok(new Coordinate(qualified.value(), null));
        }
        return version(qualified.value(), input, input.substring(colon + 1));
    }

    private static Result<Coordinate> version(QualifiedName qualified, String input, String version) {
        if (Version.isComplete(version)) {
            return Result.ok(new Coordinate(qualified, Version.parse(version).value()));
        }
        String pkg = qualified.qualified();
        if (version.isEmpty()) {
            return Result.err(new Failure.Validation("Nothing follows the ':' in '" + input + "'.",
                    "Write a version after it, " + pkg + COMPLETE_FORM + ", or drop the ':' to read the version "
                            + "your project locks or Central's latest: " + pkg));
        }
        String unprefixed = version.substring(VERSION_PREFIX.length());
        if (version.toLowerCase(Locale.ROOT).startsWith(VERSION_PREFIX) && Version.isComplete(unprefixed)) {
            return fix("'" + version + "' in '" + input + "' is not a version: a version has no leading v.",
                    pkg + VERSION_SEPARATOR + unprefixed);
        }
        Matcher numbers = THREE_NUMBERS.matcher(version);
        if (numbers.matches()) {
            String stripped = new BigInteger(numbers.group(1)) + "." + new BigInteger(numbers.group(2)) + "."
                    + new BigInteger(numbers.group(3));
            return fix("'" + version + "' in '" + input + "' is not a version: a number in a version has no "
                    + "leading zero.", pkg + VERSION_SEPARATOR + stripped);
        }
        return Result.err(new Failure.Validation("'" + version + "' in '" + input + "' is not a complete version.",
                "Write it in full, " + pkg + COMPLETE_FORM + ", or drop it to read the version your project locks "
                        + "or Central's latest: " + pkg));
    }

    private static Result<Coordinate> invalidName(String input, Result<QualifiedName> invalid) {
        int at = input.lastIndexOf(NPM_SEPARATOR);
        if (at > 0) {
            String rewritten = input.substring(0, at) + VERSION_SEPARATOR + input.substring(at + 1);
            if (parse(rewritten).isOk()) {
                return fix("Invalid package name '" + input + "': a version follows a ':', not an '@'.",
                        rewritten);
            }
        }
        if (input.indexOf('/') < 0 && QualifiedName.parse(ORG_PLACEHOLDER + "/" + input).isOk()) {
            return Result.err(new Failure.Validation(
                    "Invalid package name '" + input + "': a package is named with its organization, <org>/"
                            + input + ".",
                    "`bal search " + input + "` lists the packages that match, with their organizations."));
        }
        return invalid.cast();
    }

    private static Result<Coordinate> fix(String message, String corrected) {
        return Result.err(new Failure.Validation(message, "Run `" + COMMAND + corrected + "`."));
    }

    /**
     * The words that read this coordinate again, at {@code module} when it is not {@code null}, quoted for the shell:
     * what every printed command starts with after {@code bal discover}.
     */
    public String argument(String module) {
        return argument(qualified.qualified(), version == null ? null : version.text(), module);
    }

    /** {@link #argument(String)} for a package and version already in text. */
    public static String argument(String qualified, String version, String module) {
        return Texts.shellWord(version == null ? qualified : qualified + VERSION_SEPARATOR + version)
                + (module == null ? "" : " --module " + Texts.shellWord(module));
    }
}
