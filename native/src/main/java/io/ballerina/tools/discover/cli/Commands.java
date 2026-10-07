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

import io.ballerina.tools.discover.symbols.Surface;
import io.ballerina.tools.discover.views.Readme;
import io.ballerina.tools.discover.views.Types;
import picocli.CommandLine;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * The argument grammar, declared once: {@code bal discover <org/name> [bucket] [args...] [flags]}.
 *
 * <p>{@code --module}/{@code -m} applies uniformly across every bucket including {@code readme}: {@link Cli} reads
 * it once before any bucket is dispatched, since which module a coordinate resolves to is a fact about the package
 * lookup, not about any one bucket.
 *
 * @since 0.1.0
 */
final class Commands {

    private Commands() {
    }

    record Grammar(CommandLine line, Root root) {

        static Grammar create() {
            Root root = new Root();
            CommandLine line = new CommandLine(root);
            return new Grammar(line, root);
        }
    }

    static final List<String> BUCKETS = Stream.concat(
            Arrays.stream(Surface.Scope.values()).map(Surface.Scope::verb),
            Stream.of(Types.BUCKET, Readme.BUCKET)).toList();

    static final String JSON_OUTPUT = "json";
    static final String TEXT_OUTPUT = "text";

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

        @CommandLine.Option(names = "--output", paramLabel = "<" + JSON_OUTPUT + "|" + TEXT_OUTPUT + ">",
                description = "Override the TTY-detected default: human text at an interactive terminal, JSON "
                        + "otherwise.")
        String output;

        @CommandLine.Option(names = "--filter", paramLabel = "<keyword>",
                description = "Narrow an already-selected bucket to entries whose name, path, parameter or type "
                        + "matches this keyword, client-side. Never sent to Central as a query.")
        String filter;

        @CommandLine.Option(names = "--page", paramLabel = "<n>", defaultValue = "1",
                description = "1-indexed. Turns the page of any listing over the entry ceiling: a roster, a "
                        + "level of path groups, methods, resource paths, a container listed by call form, the type "
                        + "declarations, documentation-only matches, and readme sections narrowed by --filter. A "
                        + "page outside the listing, or against an answer that does not page, is an error.")
        int page;

        @CommandLine.Option(names = {"--module", "-m"}, paramLabel = "<name>",
                description = "Target this submodule instead of the package's default module, the same "
                        + "everywhere including readme. The bare submodule name only — <org/name> is always "
                        + "resolved as one literal, complete package coordinate.")
        String module;

        @CommandLine.Option(names = "--version", paramLabel = "<version>",
                description = "Read this exact version of the package instead of the one the project locks or "
                        + "Central's latest. Carried into every command the answer prints.")
        String version;

        @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "<org/name>")
        String pkg;

        /**
         * The bucket, then whatever it takes: a container name, a member, an accessor and a path.
         *
         * <p>No minimum arity: picocli 4.0.1 lets a variable-arity positional with a minimum consume an
         * unrecognised flag as its value, so {@link Cli} tells a bare package from a package with a bucket.
         */
        @CommandLine.Parameters(index = "1..*", paramLabel = "[bucket] [args...]")
        List<String> rest;
    }
}
