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

import io.ballerina.tools.discover.Coordinate;
import io.ballerina.tools.discover.Failure;
import io.ballerina.tools.discover.LoadedPackage;
import io.ballerina.tools.discover.Loader;
import io.ballerina.tools.discover.QualifiedName;
import io.ballerina.tools.discover.Result;
import io.ballerina.tools.discover.Texts;
import io.ballerina.tools.discover.Version;
import io.ballerina.tools.discover.central.CentralRepository;
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
 * <p>{@code bal discover <org/name>[:<version>] [bucket] [args...]} is one picocli command, and {@code bucket} a
 * plain positional value rather than a subcommand: package, then bucket, then member is one drill-down, not a mode
 * switch.
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
            return fail(describeParseError(cause, argv), streams);
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
                    "Pass '<org>/<name>', e.g. bal discover ballerinax/github."), streams);
        }

        Failure argumentError = validate(root);
        if (argumentError != null) {
            return fail(argumentError, streams);
        }

        Result<Coordinate> coordinate = Coordinate.parse(root.pkg);
        if (!coordinate.isOk()) {
            return fail(coordinate.failure(), streams);
        }
        QualifiedName qualified = coordinate.value().qualified();
        String version = coordinate.value().versionText();

        // Checked before the package is ever fetched: a mistyped bucket is a fact about the argument list, not
        // about the package, and costs a round trip to Central if left until after the load.
        List<String> rest = root.rest == null ? List.of() : root.rest;
        String bucket = rest.isEmpty() ? null : rest.get(0);
        if (bucket != null && !Commands.BUCKETS.contains(bucket)) {
            return fail(unknownBucket(bucket), streams);
        }

        // `--refresh` is only known once arguments are parsed, so the injected options are rebuilt here.
        HttpOptions resolved = http.withRefresh(root.refresh);
        Loader.LoadOptions options = new Loader.LoadOptions(
                resolved, projectDir, List.of(CentralRepository.INSTANCE), root.module, version);
        Result<LoadedPackage> loaded = Loader.loadPackage(qualified, options);
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
        TextRenderer.Context where = new TextRenderer.Context(qualified.qualified(), root.module, rest,
                filter, version);
        emit(answer.value(), where, streams, root.output, interactive);
        return 0;
    }

    private static Result<DiscoverResult> answer(
            LoadedPackage loaded, String bucket, List<String> rest, String filter, int page) {
        if (bucket == null) {
            return Result.ok(bucketList(loaded));
        }
        List<String> selectors = rest.subList(1, rest.size());
        if (Readme.BUCKET.equals(bucket)) {
            return Readme.render(loaded, new Readme.Options(
                    selectors.isEmpty() ? null : String.join(" ", selectors), filter, page));
        }
        if (Types.BUCKET.equals(bucket)) {
            return Types.render(loaded, new Types.Options(selectors, filter, page));
        }
        Surface.Scope scope = Surface.Scope.ofVerb(bucket)
                .orElseThrow(() -> new IllegalStateException("unreachable: validated above"));
        return Containers.render(loaded, scope, new Containers.Options(selectors, filter, page));
    }

    private static Failure notPaged(Commands.Root root, String filter) {
        StringBuilder command = new StringBuilder("bal discover ").append(root.pkg);
        if (root.module != null) {
            command.append(" --module ").append(Texts.shellWord(root.module));
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

    private static void emit(
            DiscoverResult result, TextRenderer.Context where, Streams streams, String output, boolean interactive) {
        boolean json = jsonOutput(output, interactive);
        streams.out().accept((json ? JsonRenderer.render(result) : TextRenderer.render(result, where)) + "\n");
    }

    private static Failure unknownBucket(String token) {
        return new Failure.Validation(
                "'" + token + "' is not a bucket.",
                "The buckets are " + String.join(", ", Commands.BUCKETS) + ".");
    }

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
            buckets.add(Types.BUCKET);
        }
        if (loaded.readme().isPresent()) {
            buckets.add(Readme.BUCKET);
        }
        List<DiscoverResult.BucketList.Submodule> submodules = loaded.submodules().stream()
                .map(submodule -> new DiscoverResult.BucketList.Submodule(
                        submodule.name(), submodule.summary(),
                        "bal discover " + loaded.pkgArgument(submodule.name())))
                .toList();
        return new DiscoverResult.BucketList(List.copyOf(buckets), submodules, loaded.warning());
    }

    private static boolean jsonOutput(String output, boolean interactive) {
        return output != null ? Commands.JSON_OUTPUT.equals(output) : !interactive;
    }

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
        return rejectVersionArguments(root);
    }

    private static Failure rejectInvalidOutput(Commands.Root root) {
        if (root.output == null || Commands.JSON_OUTPUT.equals(root.output)
                || Commands.TEXT_OUTPUT.equals(root.output)) {
            return null;
        }
        return new Failure.Validation(
                "'" + root.output + "' is not a valid --output value.",
                "Pass --output " + Commands.JSON_OUTPUT + " or --output " + Commands.TEXT_OUTPUT + ".");
    }

    private static Failure rejectVersionArguments(Commands.Root root) {
        List<String> tokens = root.rest;
        if (tokens == null) {
            return null;
        }
        String misplaced = tokens.stream()
                .filter(Version::isComplete)
                .findFirst()
                .orElse(null);
        if (misplaced == null) {
            return null;
        }
        return new Failure.Validation(
                "'" + misplaced + "' looks like a version.",
                "Write it after the package: " + packageOf(root.pkg) + ":" + misplaced);
    }

    /** The {@code <org>/<name>} part of a coordinate as typed, whatever follows it. */
    private static String packageOf(String coordinate) {
        int colon = coordinate.indexOf(':');
        return colon < 0 ? coordinate : coordinate.substring(0, colon);
    }

    private static Failure describeParseError(CommandLine.ParameterException cause, List<String> argv) {
        if (cause instanceof CommandLine.UnmatchedArgumentException unmatched) {
            List<String> tokens = unmatched.getUnmatched();
            String token = tokens.isEmpty() ? "" : tokens.get(0);
            if (VERSION_FLAG.equals(token) || token.startsWith(VERSION_FLAG + "=")) {
                return versionFlag(argv);
            }
            if (token.startsWith("-")) {
                return new Failure.Validation(
                        "Unknown option '" + token + "'.",
                        UsageRenderer.knownFlags(Commands.Grammar.create()) + " Run with --help for usage.");
            }
            return new Failure.Validation(
                    "Unexpected argument '" + token + "'.",
                    "Run `bal discover --help` for usage.");
        }
        if (cause instanceof CommandLine.MissingParameterException missing) {
            return missing.getMissing().stream()
                    .filter(CommandLine.Model.OptionSpec.class::isInstance)
                    .map(CommandLine.Model.OptionSpec.class::cast)
                    .findFirst()
                    .map(Cli::missingValue)
                    .orElseGet(() -> new Failure.Validation(firstLine(missing.getMessage()),
                            "Run with --help for usage."));
        }
        return new Failure.Validation(
                firstLine(cause.getMessage()),
                "Run with --help for usage.");
    }

    private static final String VERSION_FLAG = "--version";

    /**
     * {@code --version} is not a flag of this command: by convention it asks for a program's own version, so the
     * package version goes in the coordinate instead — what the caller is told to type, built from their own
     * package and value where argv carries them.
     */
    private static Failure versionFlag(List<String> argv) {
        String pkg = "<org>/<name>";
        String version = "<version>";
        for (int i = 0; i < argv.size(); i++) {
            String token = argv.get(i);
            String attached = token.startsWith(VERSION_FLAG + "=") ? token.substring(VERSION_FLAG.length() + 1) : "";
            if (Version.isComplete(attached)) {
                version = attached;
            } else if (token.equals(VERSION_FLAG) && i + 1 < argv.size() && Version.isComplete(argv.get(i + 1))) {
                version = argv.get(i + 1);
            } else if (pkg.startsWith("<") && !token.startsWith("-") && token.contains("/")) {
                pkg = packageOf(token);
            }
        }
        return new Failure.Validation(
                "Unknown option '" + VERSION_FLAG + "'.",
                "To read a specific version, write " + pkg + ":" + version);
    }

    private static Failure missingValue(CommandLine.Model.OptionSpec option) {
        String name = option.longestName();
        String label = option.paramLabel();
        return new Failure.Validation(
                name + " needs a value.",
                "Write " + name + " " + label + " or " + name + "=" + label + ".");
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "invalid arguments";
        }
        return message.split("\n", -1)[0];
    }

    private static int fail(Failure failure, Streams streams) {
        streams.errorOut().accept(failure.describe() + "\n");
        return 1;
    }
}
