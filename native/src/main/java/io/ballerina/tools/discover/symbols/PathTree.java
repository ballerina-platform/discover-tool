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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** How a requested path resolved against the tree. */
    public sealed interface Resolution {

        /**
         * The path resolved. {@code alsoMatched} is the branches a wildcard token matched and did NOT take:
         * {@code *} takes the first child, and children are ordered busiest-first, so without naming the rest
         * {@code *} would silently mean "the busiest branch" rather than "this level".
         *
         * @param node the tree node the path resolved to
         * @param path the resolved path, in display spelling
         * @param alsoMatched the branches a wildcard token matched and did not take
         */
        record Found(PathTree node, List<String> path, List<Descent.Sibling> alsoMatched)
                implements Resolution { }

        /**
         * The prefix matched, then a token matched nothing. {@code children} is what was there.
         *
         * <p>Carries the child NODES rather than their names, because recovery advice has to know whether a
         * child is a path parameter to offer an example from the tree the caller is actually navigating.
         *
         * @param matched the prefix that did resolve, in display spelling
         * @param token the token that matched nothing
         * @param children what was there instead
         */
        record Missing(List<String> matched, String token, List<PathTree> children) implements Resolution {

            public List<String> available() {
                return children.stream().map(PathTree::segment).toList();
            }
        }
    }

    /**
     * Levels stepped through, the node landed on, and the sibling branches not taken at each.
     *
     * @param node the tree node descended to
     * @param path the path descended, in display spelling
     * @param skipped the sibling branches not taken at each level
     */
    public record Descent(PathTree node, List<String> path, List<Sibling> skipped) {

        public record Sibling(List<String> path, int total) { }
    }

    /**
     * A literal segment with Ballerina's identifier escaping removed.
     *
     * <p>Central publishes github's paths as Ballerina writes them: {@code code\-scanning}, because
     * {@code -} needs escaping in an identifier, and {@code 'import}, because {@code import} is a reserved
     * word. Those are correct INSIDE a fence, where the line is a quotation of source — and unusable in
     * prose or as a shell argument, which is where an agent has to type them.
     */
    public static String readableSegment(String text) {
        return text.replaceAll("^'", "").replaceAll("\\\\(?=[^A-Za-z0-9_])", "");
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
     * How a path segment reads in prose and as a shell argument: {@code repos}, or {@code :owner} for a
     * parameter.
     *
     * <p>Deliberately NOT the {@code [string owner]} declaration spelling, which belongs inside a fenced block
     * where it is a quotation of source. Colon-prefixed rather than brace-wrapped ({@code {owner}}) — the RFC's
     * own choice, verified safe across bash/zsh/fish/PowerShell where both the bracket declaration spelling and
     * a brace form were tried first and rejected: brackets fail outright under zsh's default glob-matching, and
     * braces were never confirmed safe as widely.
     */
    public static String displaySegment(Fn.PathSegment segment) {
        return switch (segment) {
            case Fn.PathSegment.Literal literal -> readableSegment(literal.text());
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

    public static List<String> splitPath(String path) {
        return java.util.Arrays.stream(path.split("/", -1)).filter(token -> !token.isEmpty()).toList();
    }

    /**
     * Walk a path from the FIRST segment, one token per level.
     *
     * <p>Anchoring is the whole contract. A suffix match for {@code repos/:owner/:repo} on github returns NINE
     * operations rather than three, because {@code orgs/:org/teams/:slug/repos/:owner/:repo} and
     * {@code teams/:id/repos/:owner/:repo} share the suffix and belong to unrelated subtrees, and the caller has
     * no way to tell.
     */
    public static Resolution resolve(PathTree root, List<String> tokens) {
        PathTree node = root;
        List<String> matched = new ArrayList<>();
        List<Descent.Sibling> alsoMatched = new ArrayList<>();
        for (String token : tokens) {
            List<PathTree> candidates = node.children().stream()
                    .filter(child -> tokenMatches(token, child))
                    .toList();
            if (candidates.isEmpty()) {
                return new Resolution.Missing(List.copyOf(matched), token, node.children());
            }
            PathTree next = candidates.get(0);
            // Every branch the token ALSO matched, named with where it goes rather than where it forks, for
            // the reason `fullSiblingPath` gives: a branch named at its fork is harder to reach than one
            // never mentioned.
            for (PathTree other : candidates.subList(1, candidates.size())) {
                List<String> otherPath = new ArrayList<>(matched);
                otherPath.add(other.segment());
                alsoMatched.add(new Descent.Sibling(fullSiblingPath(other, otherPath), other.total()));
            }
            node = next;
            matched.add(node.segment());
        }
        return new Resolution.Found(node, List.copyOf(matched), List.copyOf(alsoMatched));
    }

    /**
     * A resolution, plus where the request was RELOCATED to when it addressed a real segment at the wrong depth.
     *
     * @param resolution what the tree answered, after relocation if any
     * @param alternatives full paths the trailing segment was found at, when there was more than one
     * @param relocated whether the answer is at a deeper path than the one requested
     */
    public record Located(Resolution resolution, List<List<String>> alternatives, boolean relocated) { }

    /**
     * Resolve a path, and — when it misses — look for the REST of it deeper, under the prefix that did match.
     *
     * <p>{@code repos/owner/repo/caches} is a real request against github: {@code caches} exists, at
     * {@code repos/&#123;owner&#125;/&#123;repo&#125;/actions/caches}. So is {@code repos/actions/runs}, which
     * leaves out the parameters between the segments it names. Anchored resolution answers honestly that
     * there is no such path — correct, and it costs a round trip. So the tokens that missed are located, in
     * order, under the prefix that DID match:
     *
     * <ol>
     *   <li>exactly one occurrence → answer it, and say where it was found;
     *   <li>more than one → list them and stop. Picking one is the failure anchoring exists to prevent;
     *   <li>none → the anchored answer, which already names the failing segment and the deepest working prefix.
     * </ol>
     *
     * <p>This is not suffix matching, which {@link #resolve} explains is unsafe: the first token must match
     * directly, and a request that missed on its first token is only searched for when it is that one token.
     */
    public static Located locate(PathTree root, List<String> tokens) {
        Resolution direct = resolve(root, tokens);
        if (!(direct instanceof Resolution.Missing missing)
                || missing.matched().isEmpty() && tokens.size() != 1) {
            return new Located(direct, List.of(), false);
        }
        if (!(resolve(root, missing.matched()) instanceof Resolution.Found prefix)) {
            return new Located(direct, List.of(), false);
        }

        List<List<String>> found = new ArrayList<>();
        findSegments(prefix.node(), tokens.subList(missing.matched().size(), tokens.size()),
                new ArrayList<>(), true, found);
        List<List<String>> full = found.stream()
                .map(relative -> {
                    List<String> path = new ArrayList<>(missing.matched());
                    path.addAll(relative);
                    return List.copyOf(path);
                })
                .toList();

        if (full.size() == 1 && resolve(root, full.get(0)) instanceof Resolution.Found relocated) {
            return new Located(relocated, List.copyOf(full), true);
        }
        return new Located(direct, List.copyOf(full), false);
    }

    private static void findSegments(
            PathTree node, List<String> tokens, List<String> prefix, boolean anyDepth, List<List<String>> into) {
        for (PathTree child : node.children()) {
            List<String> path = new ArrayList<>(prefix);
            path.add(child.segment());
            if (!tokenMatches(tokens.get(0), child)) {
                if (anyDepth || child.isParam()) {
                    findSegments(child, tokens, path, anyDepth, into);
                }
            } else if (tokens.size() == 1) {
                into.add(List.copyOf(path));
            } else {
                findSegments(child, tokens.subList(1, tokens.size()), path, false, into);
            }
        }
    }

    private static boolean tokenMatches(String token, PathTree node) {
        if ("*".equals(token)) {
            return true;
        }
        if (token.equals(node.segment()) || readableSegment(token).equals(node.segment())) {
            return true;
        }
        if (!node.isParam()) {
            return false;
        }
        String bare = node.segment().replaceFirst("^:\\.{0,3}", "");
        boolean rest = node.segment().startsWith(":...");
        // Four spellings: `:owner` (what the tree prints), `{owner}` (the legacy spelling; a rest parameter's
        // is `{...path}`), `owner` (typed from memory) and `[string owner]` (the declaration form inside every
        // fenced signature this tool emits, so the likeliest thing a caller copies).
        return token.equals(bare) || token.equals(":" + bare)
                || token.equals("{" + bare + "}") || (rest && token.equals("{..." + bare + "}"))
                || declaredName(token).equals(bare);
    }

    private static String declaredName(String token) {
        Matcher declared = DECLARED_PARAM.matcher(token);
        if (!declared.matches()) {
            return "";
        }
        String[] words = declared.group(1).trim().split("\\s+");
        String last = words[words.length - 1].replaceFirst("^\\.{1,3}", "");
        return IDENTIFIER.matcher(last).matches() ? last : "";
    }

    /** Anything inside square brackets; what it contains is picked apart by {@link #declaredName}. */
    private static final Pattern DECLARED_PARAM = Pattern.compile("\\[\\s*(.+?)\\s*]");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Step down through levels that add no routing choice, and NAME what was skipped.
     *
     * <p>Drilling into {@code repos} should land on the operations, not on a level whose only content is "there
     * is a parameter here". But the skipping cannot be silent: {@code repos} genuinely has two parameter
     * children with different spellings — {@code :owner} with 420 operations and {@code :templateOwner}
     * with 1 — and collapsing to the dominant one without saying so hides an operation permanently, since
     * nothing downstream would ever mention it again.
     *
     * <p>Only parameter-only levels are stepped through. A level with a literal child is a real choice about
     * which resource to address, and choosing for the caller there would be guessing.
     */
    public static Descent autoDescend(PathTree node, List<String> path) {
        List<String> walked = new ArrayList<>(path);
        List<Descent.Sibling> skipped = new ArrayList<>();
        PathTree current = node;

        while (current.operations().isEmpty()
                && !current.children().isEmpty()
                && current.children().stream().allMatch(PathTree::isParam)) {
            PathTree dominant = current.children().get(0);
            for (PathTree sibling : current.children().subList(1, current.children().size())) {
                List<String> siblingPath = new ArrayList<>(walked);
                siblingPath.add(sibling.segment());
                skipped.add(new Descent.Sibling(fullSiblingPath(sibling, siblingPath), sibling.total()));
            }
            walked.add(dominant.segment());
            current = dominant;
        }

        return new Descent(current, List.copyOf(walked), List.copyOf(skipped));
    }

    private static List<String> fullSiblingPath(PathTree node, List<String> path) {
        List<String> walked = new ArrayList<>(path);
        PathTree current = node;
        while (current.operations().isEmpty() && current.children().size() == 1) {
            current = current.children().get(0);
            walked.add(current.segment());
        }
        return List.copyOf(walked);
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
