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

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Another module, as Central publishes it: an org, a module path and the version this package was documented
 * against.
 *
 * <p>Three answers derived from it that do not agree, so no single formatted string can stand for it:
 *
 * <ul>
 *   <li>{@link #importPath()} — what an {@code import} statement takes, so a segment that is a Ballerina
 *       keyword has to be quoted. {@code ballerina/lang.'int}, not {@code ballerina/lang.int}.
 *   <li>{@link #coordinate()} — what this CLI's {@code <org/name>} argument takes, which rejects the quote.
 *   <li>{@link #isPredeclared()} — whether an import is needed AT ALL, which for a langlib module it is not.
 * </ul>
 *
 * @param orgName Central's {@code orgName} — {@code ballerina}, {@code ballerinax}
 * @param moduleName Central's {@code moduleName} — a dotted module path, NOT necessarily a package name
 * @param version the version of that module this package's payload was published against; may be blank
 * @since 0.1.0
 */
public record ModuleRef(String orgName, String moduleName, String version) {

    /**
     * Every Ballerina keyword, so a module-path segment that is one can be quoted in an import.
     *
     * <p>Taken from the compiler's own {@code SyntaxKind} — its 103 {@code *_KEYWORD} constants for
     * 2201.13.2, less the two that are not identifiers ({@code !is} and {@code _}).
     */
    private static final Set<String> KEYWORDS = Set.of(
            "public", "private", "remote", "abstract", "client", "import", "function", "const", "listener",
            "service", "xmlns", "annotation", "type", "record", "object", "as", "on", "resource", "final",
            "source", "worker", "parameter", "field", "isolated", "returns", "return", "external", "true",
            "false", "if", "else", "while", "check", "checkpanic", "panic", "continue", "break", "typeof",
            "is", "null", "lock", "fork", "trap", "in", "foreach", "table", "key", "let", "new", "from",
            "where", "select", "start", "flush", "configurable", "wait", "do", "transaction", "transactional",
            "commit", "rollback", "retry", "enum", "match", "conflict", "limit", "join", "outer", "equals",
            "class", "order", "by", "ascending", "descending", "natural", "int", "byte", "float", "decimal",
            "string", "boolean", "xml", "json", "handle", "any", "anydata", "never", "var", "map", "future",
            "typedesc", "error", "stream", "readonly", "distinct", "fail", "re", "group", "collect");

    /**
     * The langlib modules whose prefix is in scope with no import, measured with the compiler.
     *
     * <p>{@code ballerina/lang.X} is pre-declared exactly when {@code X} names a basic type, because it is the
     * type keyword itself that puts the prefix in scope. So {@code int:Signed32} and {@code xml:Element}
     * compile in a file with no imports, and {@code value:Cloneable} does not — {@code undefined module
     * 'value'} — even though all three are {@code lang.*}. That is why the test is this set and not
     * {@code startsWith("lang.")}: three of langlib's nineteen modules ({@code array}, {@code regexp},
     * {@code value}) do need the import, and two more ({@code runtime}, {@code transaction}) are not type
     * names at all.
     *
     * <p>A langlib module added later whose name is a type keyword we do not list would draw a note it does
     * not need — visible and harmless, where the reverse (withholding a needed import) is not.
     */
    private static final Set<String> PREDECLARED_LANGLIB = Set.of(
            "boolean", "decimal", "error", "float", "function", "future", "int", "map", "object", "stream",
            "string", "table", "typedesc", "xml");

    private static final String LANGLIB_PREFIX = "lang.";

    /** Central's placeholder for "no version", which it sends for every langlib reference. */
    private static final String NO_VERSION = "0.0.0";

    public ModuleRef(String orgName, String moduleName) {
        this(orgName, moduleName, "");
    }

    /** {@code ballerinax/googleapis.gmail} → {@code gmail}: the alias an import puts in scope. */
    public String prefix() {
        String[] parts = moduleName.split("\\.", -1);
        return parts.length == 0 ? moduleName : parts[parts.length - 1];
    }

    /** Is this module's prefix in scope without an import? Then there is no import advice to give. */
    public boolean isPredeclared() {
        return "ballerina".equals(orgName)
                && moduleName.startsWith(LANGLIB_PREFIX)
                && PREDECLARED_LANGLIB.contains(moduleName.substring(LANGLIB_PREFIX.length()));
    }

    /**
     * What an {@code import} statement takes, quoted where Ballerina requires it.
     *
     * <p>Per SEGMENT, because that is where the requirement is: {@code ballerina/lang.'int} quotes the
     * keyword and leaves {@code lang} alone. Quoting a segment that does not need it also compiles, but the
     * advice is compared against what real sources write, and no source writes {@code ballerina/'http}.
     */
    public String importPath() {
        String quoted = Arrays.stream(moduleName.split("\\.", -1))
                .map(segment -> isKeyword(segment) ? "'" + segment : segment)
                .collect(Collectors.joining("."));
        return orgName + "/" + quoted;
    }

    /** Does an identifier need {@link #importPath()}'s quoting — a resource path segment included? */
    public static boolean isKeyword(String identifier) {
        return KEYWORDS.contains(identifier);
    }

    /**
     * The module's {@code org/moduleName}, unquoted.
     *
     * <p>Deliberately different from {@link #importPath()}: {@code QualifiedName} rejects the apostrophe. This is
     * a MODULE path, not always a package name — {@code ballerinax/aws.auth} is the {@code auth} module of the
     * {@code ballerinax/aws} package — so it is not, on its own, an {@code <org>/<package>} argument this CLI
     * accepts: that argument always names a package, and a submodule is reached through {@code --module}.
     */
    public String coordinate() {
        return orgName + "/" + moduleName;
    }

    /** The version to pin a follow-up lookup to, absent when Central published none. */
    public Optional<String> pinnedVersion() {
        return version == null || version.isBlank() || NO_VERSION.equals(version)
                ? Optional.empty()
                : Optional.of(version);
    }
}
