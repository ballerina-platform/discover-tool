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

package io.ballerina.tools.discover.cli;

import io.ballerina.tools.discover.render.DiscoverResult;
import picocli.CommandLine;

import java.util.List;

/**
 * The argument grammar, declared once: {@code bal discover <org/name> [bucket] [args...] [flags]}.
 *
 * <p>Positional drill-down, package first — a different shape from the tool's earlier verb-first grammar
 * ({@code bal discover <verb> <org/name> [args]}), not a rename of it. There is exactly one picocli command now:
 * {@code bucket} is a plain positional value rather than a subcommand keyword, because in the RFC's design a
 * package resolves to its buckets, a bucket resolves to its members, and neither step is a mode switch.
 *
 * <p><b>WHAT IS NOT HERE YET.</b> {@code --module} is a real part of the target grammar but deliberately absent
 * from this class until module addressing lands — a flag that parses and is then silently dropped is the exact
 * class of mistake this grammar refuses everywhere else, so a flag is declared here only once something consumes
 * it. {@code --filter} and {@code --page} are here now that {@code Containers} reads them. {@code --output} only
 * changes the bare-package bucket listing and the {@code client}/{@code class}/{@code funcs} buckets' own
 * over-the-ceiling responses — the still-Markdown answers those buckets can also produce (exactly one result, or
 * a container mixing resource paths with named methods) keep their Markdown shape regardless of {@code --output}
 * until they are rewritten onto {@link DiscoverResult} too. {@code -s/--search}, {@code -r/--resolve-types} and
 * {@code --all} are gone for good: the RFC has no equivalent for any of them (see the RFC-alignment plan's
 * "Decisions locked in").
 *
 * @since 0.1.0
 */
final class Commands {

    private Commands() {
    }

    /**
     * The parser and the one holder it fills.
     *
     * @param line the picocli parser
     * @param root the argument holder
     */
    record Grammar(CommandLine line, Root root) {

        static Grammar create() {
            Root root = new Root();
            CommandLine line = new CommandLine(root);
            return new Grammar(line, root);
        }
    }

    /** Buckets wired into dispatch so far. {@code service} and {@code readme} join this set as their own items land. */
    static final List<String> BUCKETS = List.of("client", "class", "funcs");

    /**
     * The one command. {@code pkg} is optional at the grammar level — {@code bal discover} with nothing else is a
     * usage request, not a missing-argument failure — and {@link Cli} tells the two apart.
     */
    @CommandLine.Command(name = "discover")
    static final class Root {

        @CommandLine.Option(names = {"-h", "--help"}, usageHelp = true, description = "This text.")
        boolean help;

        @CommandLine.Option(names = "--refresh",
                description = "Ignore any cached copy and rewrite it. Worth passing only when a name should "
                        + "exist and does not.")
        boolean refresh;

        @CommandLine.Option(names = "--output", paramLabel = "<json|text>",
                description = "Override the TTY-detected default: human text at an interactive terminal, JSON "
                        + "otherwise.")
        String output;

        @CommandLine.Option(names = "--filter", paramLabel = "<keyword>",
                description = "Narrow an already-selected bucket to entries whose name, path, parameter or type "
                        + "matches this keyword, client-side. Never sent to Central as a query.")
        String filter;

        @CommandLine.Option(names = "--page", paramLabel = "<n>", defaultValue = "1",
                description = "1-indexed. Only meaningful once a listing of remote or normal methods is over "
                        + "the entry ceiling; ignored otherwise.")
        int page;

        @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "<org/name>")
        String pkg;

        /**
         * The bucket, then whatever it takes: a container name, a member, an accessor and a path.
         *
         * <p>No minimum arity, for the reason recorded across this grammar already: picocli 4.0.1 lets a
         * variable-arity positional with a minimum consume an unrecognised flag as its value, so an empty list is
         * legal here and {@link Cli} tells a bare package from a package with a bucket.
         */
        @CommandLine.Parameters(index = "1..*", paramLabel = "[bucket] [args...]")
        List<String> rest;
    }
}
