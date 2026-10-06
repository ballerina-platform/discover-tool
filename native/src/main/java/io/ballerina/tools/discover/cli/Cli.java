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
import io.ballerina.tools.discover.Texts;
import io.ballerina.tools.discover.Version;
import io.ballerina.tools.discover.central.HttpOptions;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.JsonRenderer;
import io.ballerina.tools.discover.render.TextRenderer;
import io.ballerina.tools.discover.symbols.Surface;
import io.ballerina.tools.discover.views.Containers;
import io.ballerina.tools.discover.views.Readme;
import io.ballerina.tools.discover.views.Types;
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
        Loader.LoadOptions options = new Loader.LoadOptions(resolved, projectDir, root.module, root.version);
        Result<LoadedPackage> loaded = Loader.loadPackage(qualified.value(), options);
        if (!loaded.isOk()) {
            return fail(loaded.failure(), streams);
        }

        // A blank keyword filters nothing, so it is no filter at all — normalised once, here, so no header or
        // printed command downstream ever spells an empty `--filter`.
        String filter = root.filter == null || root.filter.isBlank() ? null : root.filter;
        Result<DiscoverResult> answer = answer(loaded.value(), bucket, rest, filter, root.page);
        if (!answer.isOk()) {
            return fail(answer.failure(), streams);
        }
        if (root.page != 1 && answer.value().paging() == null) {
            return fail(notPaged(root, filter), streams);
        }
        TextRenderer.Context where = new TextRenderer.Context(qualified.value().qualified(), root.module, rest,
                filter, root.version);
        emit(answer.value(), where, streams, root.output, interactive);
        return 0;
    }

    private static Result<DiscoverResult> answer(
            LoadedPackage loaded, String bucket, List<String> rest, String filter, int page) {
        if (bucket == null) {
            return Result.ok(bucketList(loaded));
        }
        List<String> selectors = rest.subList(1, rest.size());
        if ("readme".equals(bucket)) {
            // Not derived from call-site grammar, so it shares no code with Containers — see Readme's own class
            // comment for why.
            return Readme.render(loaded, new Readme.Options(
                    selectors.isEmpty() ? null : String.join(" ", selectors), filter, page));
        }
        if ("type".equals(bucket)) {
            return Types.render(loaded, new Types.Options(selectors, filter, page));
        }
        Containers.Options options = new Containers.Options(selectors, filter, page);
        return switch (bucket) {
            case "client" -> Containers.render(loaded, Surface.Scope.CLIENT, options);
            case "service" -> Containers.render(loaded, Surface.Scope.SERVICE, options);
            case "class" -> Containers.render(loaded, Surface.Scope.CLASS, options);
            case "funcs" -> Containers.render(loaded, Surface.Scope.MODULE, options);
            default -> throw new IllegalStateException("unreachable: validated above");
        };
    }

    /**
     * {@code --page} against an answer that does not page — a bare package, one signature, a whole readme, a
     * no-match answer. Served as page 1 it would read as a page that exists; a listing's own range check is what
     * rejects a page past the end of one that does page.
     */
    private static Failure notPaged(Commands.Root root, String filter) {
        StringBuilder command = new StringBuilder("bal discover ").append(root.pkg);
        if (root.module != null) {
            command.append(" --module ").append(Texts.shellWord(root.module));
        }
        if (root.version != null) {
            command.append(" --version ").append(Texts.shellWord(root.version));
        }
        if (root.rest != null) {
            root.rest.forEach(word -> command.append(' ').append(Texts.shellWord(word)));
        }
        if (filter != null) {
            command.append(" --filter ").append(Texts.shellWord(filter));
        }
        return new Failure.Validation(
                "--page " + root.page + " is out of range: this answer is not paged.",
                "Drop --page: `" + command + "`.");
    }

    /** Renders a result with whichever of the two renderers {@code --output} (or the TTY default) selects. */
    private static void emit(
            DiscoverResult result, TextRenderer.Context where, Streams streams, String output, boolean interactive) {
        boolean json = jsonOutput(output, interactive);
        streams.out().accept((json ? JsonRenderer.render(result) : TextRenderer.render(result, where)) + "\n");
    }

    // -----------------------------------------------------------------------
    // Dispatch
    // -----------------------------------------------------------------------

    private static Failure unknownBucket(String token) {
        return new Failure.Validation(
                "'" + token + "' is not a bucket.",
                "The buckets are " + String.join(", ", Commands.BUCKETS) + ".");
    }

    /** No bucket: which of them this package (or the targeted module) actually has, and its other modules. */
    private static DiscoverResult.BucketList bucketList(LoadedPackage loaded) {
        List<String> buckets = new ArrayList<>();
        for (Surface.Scope scope : Surface.Scope.values()) {
            if (!Surface.of(loaded.library(), scope).isEmpty()) {
                buckets.add(scope.verb());
            }
        }
        // Neither is a Surface.Scope — they are not part of the callable surface — so they are appended here
        // rather than found by the loop above: `type`, then `readme` last, matching the RFC's worked examples.
        if (Types.count(loaded) > 0) {
            buckets.add("type");
        }
        if (loaded.readme().isPresent()) {
            buckets.add("readme");
        }
        List<DiscoverResult.BucketList.Submodule> submodules = loaded.submodules().stream()
                .map(submodule -> new DiscoverResult.BucketList.Submodule(
                        submodule.name(), submodule.summary(),
                        "bal discover " + loaded.pkgArgument(submodule.name())))
                .toList();
        return new DiscoverResult.BucketList(List.copyOf(buckets), submodules, loaded.warning());
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
        if (root.page < 1) {
            return new Failure.Validation(
                    "--page " + root.page + " is out of range: pages are numbered from 1.",
                    "Pass --page 1 or later, or drop it for the first page.");
        }
        Failure badVersion = rejectInvalidVersion(root);
        return badVersion != null ? badVersion : rejectVersionArguments(root);
    }

    private static Failure rejectInvalidVersion(Commands.Root root) {
        if (root.version == null
                || VERSION_SHAPED.matcher(root.version).matches() && Version.parse(root.version).isOk()) {
            return null;
        }
        return new Failure.Validation(
                "'" + root.version + "' is not a version.",
                "Pass a version as Central publishes it, e.g. --version 2.15.0.");
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
     * A version passed as a positional argument; it is a flag, {@code --version}.
     *
     * <p>Left alone, a version-shaped token after the package would be read as a bucket or a selector and
     * reported as {@code validation}/{@code symbol-not-found} on a "bucket" called {@code 4.6.5}, which names
     * neither the mistake nor what to do.
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
                "'" + misplaced + "' looks like a version, and a version is a flag, not an argument.",
                "Pass it as --version " + misplaced + ". Without it the version is resolved from your project: "
                        + "the one its Dependencies.toml locks, so a lookup matches what `bal build` compiles "
                        + "against, or outside a project Central's latest.");
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
                        "Known flags are --module/-m, --filter, --page, --output, --refresh and --help. "
                                + "Run with --help for usage.");
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
