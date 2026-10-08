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
import java.util.Comparator;
import java.util.regex.Matcher;
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

    private static final String NUMBER = "(0|[1-9]\\d*)";

    private static final String PRE_RELEASE_IDENTIFIER = "(0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)";

    private static final String BUILD_IDENTIFIER = "[0-9A-Za-z-]+";

    private static final Pattern COMPLETE = Pattern.compile("^" + NUMBER + "\\." + NUMBER + "\\." + NUMBER
            + "(-" + PRE_RELEASE_IDENTIFIER + "(\\." + PRE_RELEASE_IDENTIFIER + ")*)?"
            + "(\\+" + BUILD_IDENTIFIER + "(\\." + BUILD_IDENTIFIER + ")*)?$");

    private static final Pattern PRECEDENCE_PARTS = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-([^+]+))?");

    private static final String IDENTIFIER_SEPARATOR = "\\.";

    private static final String DIGITS = "\\d+";

    private static final int CORE_PARTS = 3;

    private static final int PRE_RELEASE_PART = 4;

    /** SemVer precedence, lowest first; text that is not {@code major.minor.patch} sorts after, by text. */
    public static final Comparator<String> PRECEDENCE = Version::comparePrecedence;

    private final String text;

    private Version(String text) {
        this.text = text;
    }

    public String text() {
        return text;
    }

    public static String textOf(Version version) {
        return version == null ? null : version.text();
    }

    /**
     * {@code .} and {@code ..} pass the pattern but are path traversal once the docs cache joins a segment
     * into a file path. The cache re-checks its segments; this is the outer of two independent guards.
     */
    static boolean isTraversal(String segment) {
        return ".".equals(segment) || "..".equals(segment);
    }

    /** Whether {@code input} is a full SemVer version: {@code major.minor.patch[-pre-release][+build]}. */
    public static boolean isComplete(String input) {
        return COMPLETE.matcher(input).matches();
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

    private static int comparePrecedence(String left, String right) {
        Matcher a = PRECEDENCE_PARTS.matcher(left);
        Matcher b = PRECEDENCE_PARTS.matcher(right);
        boolean leftOrdered = a.find();
        boolean rightOrdered = b.find();
        if (leftOrdered != rightOrdered) {
            return leftOrdered ? -1 : 1;
        }
        if (!leftOrdered) {
            return left.compareTo(right);
        }
        for (int part = 1; part <= CORE_PARTS; part++) {
            int order = new BigInteger(a.group(part)).compareTo(new BigInteger(b.group(part)));
            if (order != 0) {
                return order;
            }
        }
        return comparePreRelease(a.group(PRE_RELEASE_PART), b.group(PRE_RELEASE_PART));
    }

    // A version without a pre-release outranks one with it.
    private static int comparePreRelease(String left, String right) {
        if (left == null || right == null) {
            return Boolean.compare(left == null, right == null);
        }
        String[] a = left.split(IDENTIFIER_SEPARATOR);
        String[] b = right.split(IDENTIFIER_SEPARATOR);
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int order = compareIdentifier(a[i], b[i]);
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private static int compareIdentifier(String left, String right) {
        boolean leftNumeric = left.matches(DIGITS);
        boolean rightNumeric = right.matches(DIGITS);
        if (leftNumeric && rightNumeric) {
            return new BigInteger(left).compareTo(new BigInteger(right));
        }
        if (leftNumeric != rightNumeric) {
            return leftNumeric ? -1 : 1;
        }
        return left.compareTo(right);
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
