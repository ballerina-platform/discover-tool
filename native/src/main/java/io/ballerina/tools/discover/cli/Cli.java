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

import io.ballerina.tools.discover.Failure;
import io.ballerina.tools.discover.LoadedPackage;
import io.ballerina.tools.discover.Loader;
import io.ballerina.tools.discover.QualifiedName;
import io.ballerina.tools.discover.Result;
import io.ballerina.tools.discover.central.HttpOptions;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.Documents;
import io.ballerina.tools.discover.render.JsonRenderer;
import io.ballerina.tools.discover.render.TextRenderer;
import io.ballerina.tools.discover.symbols.Surface;
import io.ballerina.tools.discover.views.Containers;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * {@code bal discover} — argv in, exit code out.
 *
 * <p>The stream discipline is the contract:
 *
 * <ul>
 *   <li>stdout — the requested document, and nothing else: no progress, no banner. A usage request is a request
 *       like any other, so {@code --help} is that document
 *   <li>stderr — on failure, one JSON object matching {@link Failure}, and nothing else
 *   <li>exit 0 — success, and stdout is COMPLETE
 *   <li>exit 1 — every failure, whatever went wrong. What to do next is {@code kind} and {@code suggestion} in
 *       the JSON, never the code
 * </ul>
 *
 * <p>PACKAGE-FIRST, not verb-first. {@code bal discover <org/name> [bucket] [args...]} resolves the package, then
 * the bucket, then the rest — there is exactly one picocli command, and {@code bucket} is a plain positional value
 * rather than a subcommand, since a package resolving to a bucket resolving to a member is one drill-down, not a
 * mode switch.
 *
 * <p>No {@link System#exit} here, and no environment read: the cache, the transport and the project directory
 * arrive as arguments, so a test drives the real command against a recorded payload and a temporary directory.
 * {@link DiscoverTool} is the only place that touches the process.
 *
 * @since 0.1.0
 */
public final class Cli {

    private Cli() {
    }

    /**
     * Where the two streams go. Injected so a test can capture both without a subprocess.
     *
     * @param out where stdout goes
     * @param errorOut where stderr goes
     */
    public record Streams(Consumer<String> out, Consumer<String> errorOut) { }

    public static int run(List<String> argv, Streams streams, HttpOptions http) {
        return run(argv, streams, http, null);
    }

    /**
     * @param projectDir the Ballerina project the process is standing in, or {@code null}. Discovered by
     *     {@link DiscoverTool}, never by a flag — see {@link Commands}
     */
    public static int run(List<String> argv, Streams streams, HttpOptions http, String projectDir) {
        return run(argv, streams, http, projectDir, false);
    }

    /**
     * @param interactive whether stdout is an interactive terminal — the {@code --output} default in the absence
     *     of the flag. Discovered by {@link DiscoverTool} from {@code System.console()}, never read here: a test
     *     drives both defaults without a real terminal, the same reason the cache and the transport are injected.
     */
    public static int run(
            List<String> argv, Streams streams, HttpOptions http, String projectDir, boolean interactive) {
        if (argv.isEmpty()) {
            streams.out().accept(Usage.root());
            return 0;
        }

        Commands.Grammar grammar = Commands.Grammar.create();
        CommandLine.ParseResult parsed;
        try {
            parsed = grammar.line().parseArgs(argv.toArray(new String[0]));
        } catch (CommandLine.ParameterException cause) {
            // picocli's own error printing is deliberately never used: stderr has to hold exactly one `Failure`
            // object, and picocli would write a usage block beside it.
            return fail(describeParseError(cause), streams);
        }

        // A usage request is answered, not failed: it goes to stdout at exit 0, because "exit 0 means stdout is
        // complete" is what a redirecting caller relies on.
        if (parsed.isUsageHelpRequested()) {
            streams.out().accept(Usage.root());
            return 0;
        }

        Commands.Root root = grammar.root();
        if (root.pkg == null) {
            return fail(new Failure.Validation(
                    "A package is required.",
                    "Pass 'org/name', e.g. bal discover ballerinax/github."), streams);
        }

        Failure argumentError = validate(root);
        if (argumentError != null) {
            return fail(argumentError, streams);
        }

        Result<QualifiedName> qualified = QualifiedName.parse(root.pkg);
        if (!qualified.isOk()) {
            return fail(qualified.failure(), streams);
        }

        // Checked before the package is ever fetched: a mistyped bucket is a fact about the argument list, not
        // about the package, and costs a round trip to Central if left until after the load.
        List<String> rest = root.rest == null ? List.of() : root.rest;
        String bucket = rest.isEmpty() ? null : rest.get(0);
        if (bucket != null && !Commands.BUCKETS.contains(bucket)) {
            return fail(unknownBucket(bucket), streams);
        }

        // `--refresh` is only known once arguments are parsed. The transport and the cache arrive from the process
        // wrapper, so the options are rebuilt here rather than there.
        HttpOptions resolved = http.withRefresh(root.refresh);
        Loader.LoadOptions options = new Loader.LoadOptions(resolved, projectDir);
        Result<LoadedPackage> loaded = Loader.loadPackage(qualified.value(), options);
        if (!loaded.isOk()) {
            return fail(loaded.failure(), streams);
        }

        if (bucket == null) {
            // The one response already built on the RFC's result IR: one source, rendered by whichever of the
            // two renderers `--output` (or the TTY default) selects. The buckets `Containers` still answers keep
            // their own Markdown-report shape below, regardless of `--output`, until that view moves onto the
            // IR too.
            DiscoverResult result = bucketList(loaded.value());
            boolean json = jsonOutput(root.output, interactive);
            streams.out().accept((json ? JsonRenderer.render(result) : TextRenderer.render(result)) + "\n");
            return 0;
        }

        Containers.Options containerOptions =
                new Containers.Options(rest.subList(1, rest.size()), root.filter, false, false, root.page);
        Result<Containers.Answer> answer = switch (bucket) {
            case "client" -> Containers.render(loaded.value(), Surface.Scope.CLIENT, containerOptions);
            case "class" -> Containers.render(loaded.value(), Surface.Scope.CLASS, containerOptions);
            case "funcs" -> Containers.render(loaded.value(), Surface.Scope.MODULE, containerOptions);
            default -> throw new IllegalStateException("unreachable: validated above");
        };
        if (!answer.isOk()) {
            return fail(answer.failure(), streams);
        }
        switch (answer.value()) {
            case Containers.Answer.Markdown markdown ->
                    // The one point every Markdown document passes through, which is why the length stamp goes
                    // here — see Documents.withLength for what it defends against. The structured case below
                    // carries no such stamp: it is not part of the RFC's shape for that response.
                    streams.out().accept(Documents.withLength(markdown.text()));
            case Containers.Answer.Structured structured -> {
                boolean json = jsonOutput(root.output, interactive);
                streams.out().accept((json
                        ? JsonRenderer.render(structured.result())
                        : TextRenderer.render(structured.result())) + "\n");
            }
        }
        return 0;
    }

    // -----------------------------------------------------------------------
    // Dispatch
    // -----------------------------------------------------------------------

    private static Failure unknownBucket(String token) {
        return new Failure.Validation(
                "'" + token + "' is not a bucket.",
                "The buckets are " + String.join(", ", Commands.BUCKETS) + ".");
    }

    /**
     * No bucket: which of them this package actually has.
     *
     * <p>Deliberately minimal for now — a bucket list and nothing else. Submodules, once {@code --module} exists
     * to target one, are a bare-package fact too and land beside this.
     */
    private static DiscoverResult.BucketList bucketList(LoadedPackage loaded) {
        List<String> buckets = new ArrayList<>();
        for (Surface.Scope scope : Surface.Scope.values()) {
            if (!Surface.of(loaded.library(), scope).isEmpty()) {
                buckets.add(scope.verb());
            }
        }
        return new DiscoverResult.BucketList(List.copyOf(buckets), loaded.warning());
    }

    /** {@code --output}, or the TTY default when it was not passed. */
    private static boolean jsonOutput(String output, boolean interactive) {
        return output != null ? "json".equals(output) : !interactive;
    }

    // -----------------------------------------------------------------------
    // Argument errors
    // -----------------------------------------------------------------------

    private static Failure validate(Commands.Root root) {
        Failure output = rejectInvalidOutput(root);
        if (output != null) {
            return output;
        }
        return rejectVersionArguments(root);
    }

    private static Failure rejectInvalidOutput(Commands.Root root) {
        if (root.output == null || "json".equals(root.output) || "text".equals(root.output)) {
            return null;
        }
        return new Failure.Validation(
                "'" + root.output + "' is not a valid --output value.",
                "Pass --output json or --output text.");
    }

    /** A version, as Central publishes them. No bucket name, selector or path can look like one. */
    private static final Pattern VERSION_SHAPED =
            Pattern.compile("^\\d+\\.\\d+\\.\\d+([-+.].*)?$");

    /**
     * A version passed as an argument, when versions are no longer arguments.
     *
     * <p>Version resolution is internal — see {@link Loader} — so a version-shaped token after the package is a
     * caller carrying an old habit. Left alone it would be read as a bucket or a selector and reported as
     * {@code validation}/{@code symbol-not-found} on a "bucket" called {@code 4.6.5}, which names neither the
     * mistake nor what to do.
     */
    private static Failure rejectVersionArguments(Commands.Root root) {
        List<String> tokens = root.rest;
        if (tokens == null) {
            return null;
        }
        String misplaced = tokens.stream()
                .filter(token -> VERSION_SHAPED.matcher(token).matches())
                .findFirst()
                .orElse(null);
        if (misplaced == null) {
            return null;
        }
        return new Failure.Validation(
                "'" + misplaced + "' looks like a version, and this tool does not take one.",
                "The version is resolved from your project: run inside the component whose "
                        + "Dependencies.toml locks it, and the lookup matches what `bal build` compiles "
                        + "against. Outside a project it is Central's latest. Drop the argument.");
    }

    /**
     * picocli's parse errors, as this command's contract. Every one of them is {@code validation} with a
     * {@code suggestion}, because the recovery is always an edit to the argument list.
     */
    private static Failure describeParseError(CommandLine.ParameterException cause) {
        if (cause instanceof CommandLine.UnmatchedArgumentException unmatched) {
            List<String> tokens = unmatched.getUnmatched();
            String token = tokens.isEmpty() ? "" : tokens.get(0);
            if (token.startsWith("-")) {
                return new Failure.Validation(
                        "Unknown option '" + token + "'.",
                        "Known flags are --refresh and --help. Run with --help for usage.");
            }
            return new Failure.Validation(
                    "Unexpected argument '" + token + "'.",
                    "Run `bal discover --help` for usage.");
        }
        if (cause instanceof CommandLine.MissingParameterException missing) {
            return new Failure.Validation(
                    firstLine(missing.getMessage()),
                    "Pass the value after the flag, or as --flag=value.");
        }
        return new Failure.Validation(
                firstLine(cause.getMessage()),
                "Run with --help for usage.");
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "invalid arguments";
        }
        return message.split("\n", -1)[0];
    }

    /** One code for every failure. The JSON is where a caller reads what happened and what to do about it. */
    private static int fail(Failure failure, Streams streams) {
        streams.errorOut().accept(failure.describe() + "\n");
        return 1;
    }
}
