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

package io.ballerina.tools.discover.symbols;

import io.ballerina.tools.discover.Texts;
import io.ballerina.tools.discover.model.ClientClass;
import io.ballerina.tools.discover.model.Fn;
import io.ballerina.tools.discover.render.Identifiers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Operations by path.
 *
 * <p>A path tree needs no prior knowledge of the API: {@code ballerinax/github}'s 903 operations reduce to 36
 * top-level segments, and each level names the next.
 *
 * @param segment the display segment this node is reached by; empty at the root
 * @param isParam whether the segment is a path parameter rather than a literal
 * @param operations operations that END here — the ones a caller at this path can invoke
 * @param children the segments below, busiest first
 * @param total operations at or under this node; what the tree prints beside each segment
 * @since 0.1.0
 */
public record PathTree(
        String segment, boolean isParam, List<Operation> operations, List<PathTree> children, int total) {

    /**
     * A resource function plus its path in the display spelling the tree navigates by.
     *
     * @param fn the resource function
     * @param segments its path, in display spelling
     */
    public record Operation(Fn.Resource fn, List<String> segments) { }

    public static final String SEPARATOR = "/";

    /** How a requested path resolved against the tree. */
    public sealed interface Resolution {

        /**
         * The path resolved.
         *
         * @param node the tree node the path resolved to
         * @param path the resolved path, as printed
         */
        record Found(PathTree node, List<String> path) implements Resolution { }

        /**
         * A segment matched nothing.
         *
         * @param matched the segments before it, which did resolve
         */
        record Missing(List<String> matched) implements Resolution { }
    }

    /**
     * A whole selector unescaped — every segment of it, not just the first.
     *
     * <p>A selector copied out of a fenced signature ({@code post chat\.postMessage}) carries an accessor and
     * several segments in one argument, and is compared against an entry's label rather than walked.
     *
     * <p>The {@code '} strip is anchored at a segment boundary rather than global: an apostrophe INSIDE a name is
     * part of it, and {@code 'key} is only a quoted identifier where a segment starts.
     */
    public static String readableSelector(String text) {
        return text.replaceAll("(^|(?<=[ /]))'", "").replaceAll("\\\\(?=[^A-Za-z0-9_])", "");
    }

    /**
     * How a path segment is printed and typed: {@code repos}, {@code public} for the keyword Ballerina writes
     * {@code 'public}, or {@code :owner} for a parameter. Central publishes literal segments as Ballerina writes
     * them ({@code code\-scanning}, {@code 'import}); those spellings belong inside a fence, where a line quotes
     * source, and are read back as the same segment when typed.
     *
     * <p>Deliberately NOT the {@code [string owner]} declaration spelling, which belongs inside a fenced block
     * where it is a quotation of source. Colon-prefixed rather than brace-wrapped ({@code {owner}}) — the RFC's
     * own choice, verified safe across bash/zsh/fish/PowerShell where both the bracket declaration spelling and
     * a brace form were tried first and rejected: brackets fail outright under zsh's default glob-matching, and
     * braces were never confirmed safe as widely.
     */
    public static String displaySegment(Fn.PathSegment segment) {
        return switch (segment) {
            case Fn.PathSegment.Literal literal -> Identifiers.plain(literal.text());
            case Fn.PathSegment.Parameter parameter -> parameter.type().endsWith("...")
                    ? ":..." + parameter.name()
                    : ":" + parameter.name();
        };
    }

    public static List<Operation> operationsOf(ClientClass client) {
        List<Operation> operations = new ArrayList<>();
        for (Fn fn : client.functions()) {
            if (fn instanceof Fn.Resource resource) {
                operations.add(new Operation(
                        resource, resource.paths().stream().map(PathTree::displaySegment).toList()));
            }
        }
        return List.copyOf(operations);
    }

    public static PathTree build(List<Operation> operations) {
        MutableNode root = new MutableNode("", false);
        for (Operation operation : operations) {
            MutableNode node = root;
            for (int index = 0; index < operation.segments().size(); index++) {
                String segment = operation.segments().get(index);
                boolean isParam = index < operation.fn().paths().size()
                        && operation.fn().paths().get(index) instanceof Fn.PathSegment.Parameter;
                node = node.children.computeIfAbsent(segment, key -> new MutableNode(key, isParam));
            }
            node.operations.add(operation);
        }
        return root.freeze();
    }

    /** Every operation at or under a node, in tree order. */
    public static List<Operation> operationsUnder(PathTree node) {
        List<Operation> collected = new ArrayList<>(node.operations());
        for (PathTree child : node.children()) {
            collected.addAll(operationsUnder(child));
        }
        return List.copyOf(collected);
    }

    /** A path as typed, one token per segment, each read as the plain segment it names. */
    public static List<String> splitPath(String path) {
        return Arrays.stream(path.split(SEPARATOR, -1)).map(Identifiers::plain).toList();
    }

    /** A path as printed. */
    public static String join(List<String> segments) {
        return String.join(SEPARATOR, segments);
    }

    /**
     * Walk a path from the FIRST segment, one token per level, each matching a segment exactly as printed.
     *
     * <p>Anchoring is the whole contract. A suffix match for {@code repos/:owner/:repo} on github returns NINE
     * operations rather than three, because {@code orgs/:org/teams/:slug/repos/:owner/:repo} and
     * {@code teams/:id/repos/:owner/:repo} share the suffix and belong to unrelated subtrees, and the caller has
     * no way to tell.
     */
    public static Resolution resolve(PathTree root, List<String> tokens) {
        PathTree node = root;
        List<String> matched = new ArrayList<>();
        for (String token : tokens) {
            Optional<PathTree> next = node.children().stream()
                    .filter(child -> child.segment().equals(token))
                    .findFirst();
            if (next.isEmpty()) {
                return new Resolution.Missing(List.copyOf(matched));
            }
            node = next.get();
            matched.add(token);
        }
        return new Resolution.Found(node, List.copyOf(matched));
    }

    /** The tree under construction. Frozen in one pass so the public shape can stay immutable. */
    private static final class MutableNode {

        private final String segment;
        private final boolean isParam;
        private final List<Operation> operations = new ArrayList<>();
        private final Map<String, MutableNode> children = new LinkedHashMap<>();

        private MutableNode(String segment, boolean isParam) {
            this.segment = segment;
            this.isParam = isParam;
        }

        private PathTree freeze() {
            List<PathTree> frozen = new ArrayList<>();
            for (MutableNode child : children.values()) {
                frozen.add(child.freeze());
            }
            frozen.sort(Comparator.comparingInt(PathTree::total).reversed()
                    .thenComparing(PathTree::segment, Texts.LOCALE_ORDER));
            int total = operations.size() + frozen.stream().mapToInt(PathTree::total).sum();
            return new PathTree(segment, isParam, List.copyOf(operations), List.copyOf(frozen), total);
        }
    }
}
