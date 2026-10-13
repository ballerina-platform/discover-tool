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
import io.ballerina.tools.discover.symbols.PathTree;
import io.ballerina.tools.discover.symbols.Surface;
import io.ballerina.tools.discover.views.Containers;
import io.ballerina.tools.discover.views.Overview;
import io.ballerina.tools.discover.views.Readme;
import io.ballerina.tools.discover.views.Search;
import io.ballerina.tools.discover.views.SearchCentral;
import io.ballerina.tools.discover.views.Types;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * {@code bal discover} — argv in, exit code out.
 *
 * <p>The stream discipline is the contract:
 *
 * <ul>
 *   <li>stdout — the requested document, and nothing else: no progress, no banner. A usage request is a request
 *       like any other, so {@code --help} is that document
 *   <li>stderr — on failure, one {@link Failure} and nothing else, in the run's output mode: one JSON object,
 *       or {@code error: <message>} with the suggestion indented below it
 *   <li>exit 0 — success, and stdout is COMPLETE
 *   <li>exit 2 — a usage error: the command line itself is malformed, so nothing was looked up. In text mode
 *       the failure is followed by the synopsis and a pointer to {@code --help}
 *   <li>exit 1 — every other failure. What to do next is {@code kind} and {@code suggestion} in the failure,
 *       never the code
 * </ul>
 *
 * <p>{@code bal discover <org>/<name>[:<version>] [bucket] [name ...]} is one picocli command, and {@code bucket} a
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

    private static final String OUTPUT_FLAG = "--output";

    private static final String HELP = Coordinate.COMMAND + "--help";

    private static final String RUN_HELP = "Run `" + HELP + "` for usage.";

    static final int FAILED = 1;

    private static final int USAGE_ERROR = 2;

    private static final int FIRST_PAGE = 1;

    private static final String PLACEHOLDER_PACKAGE = "<org>/<name>";

    private static final String PLACEHOLDER_VERSION = "<version>";

    private static final String WRITE_VERSION = "To read a specific version, write it after the package: ";

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
        boolean json = jsonOutput(argv, interactive, grammar);
        UsageErrors usage = new UsageErrors(grammar, streams, json);
        CommandLine.ParseResult parsed;
        try {
            parsed = grammar.line().parseArgs(argv.toArray(new String[0]));
        } catch (CommandLine.ParameterException cause) {
            // picocli's own error printing is deliberately never used: stderr has to hold exactly one `Failure`
            // object, and picocli would write a usage block beside it.
            Failure specific = describeParseError(cause, grammar);
            return specific == null
                    ? usage.helpError(firstLine(cause.getMessage()))
                    : usage.error(specific);
        }

        // A usage request is answered, not failed: it goes to stdout at exit 0, because "exit 0 means stdout is
        // complete" is what a redirecting caller relies on.
        if (parsed.isUsageHelpRequested()) {
            streams.out().accept(Usage.root());
            return 0;
        }

        Commands.Root root = grammar.root();
        if (root.version != null) {
            return usage.error(versionFlag(root));
        }
        if (SearchCentral.VERB.equals(root.pkg)) {
            return searchCentral(root, streams, http, json, usage);
        }
        if (root.pkg == null) {
            return usage.error(new Failure.Validation(
                    "A package is required.",
                    "Pass '<org>/<name>', e.g. bal discover ballerinax/github."));
        }

        Result<Coordinate> coordinate = Coordinate.parse(root.pkg, Coordinate.moduleArgument(root.module) + tail(root));
        if (!coordinate.isOk()) {
            return usage.error(coordinate.failure());
        }
        QualifiedName qualified = coordinate.value().qualified();

        Failure argumentError = validate(root, coordinate.value());
        if (argumentError != null) {
            return usage.error(argumentError);
        }
        Version version = coordinate.value().version();

        // Checked before the package is ever fetched: a mistyped bucket is a fact about the argument list, not
        // about the package, and costs a round trip to Central if left until after the load.
        List<String> rest = rest(root);
        String bucket = rest.isEmpty() ? null : rest.get(0);
        if (bucket != null && !Commands.BUCKETS.contains(bucket)) {
            return usage.error(unknownBucket(bucket, coordinate.value().command(root.module)));
        }

        String filter = root.filter;
        Failure extra = extraNames(bucket, rest, filter, root, qualified, version);
        if (extra != null) {
            return usage.error(extra);
        }

        // `--refresh` is only known once arguments are parsed, so the injected options are rebuilt here.
        HttpOptions resolved = http.withRefresh(root.refresh);
        Loader.LoadOptions options = new Loader.LoadOptions(
                resolved, projectDir, List.of(CentralRepository.INSTANCE), root.module, version);
        Result<LoadedPackage> loaded = Loader.loadPackage(qualified, options);
        if (!loaded.isOk()) {
            return fail(loaded.failure().lengthened(tail(root)), streams, json);
        }

        Result<DiscoverResult> answer = answer(loaded.value(), bucket, rest, filter, root.page);
        if (!answer.isOk()) {
            return fail(readAt(answer.failure(), loaded.value()), streams, json);
        }
        if (root.page != FIRST_PAGE && answer.value().paging() == null) {
            return fail(readAt(notPaged(root, coordinate.value(), filter), loaded.value()), streams, json);
        }
        String read = loaded.value().version().text();
        List<String> shown = echoed(rest);
        TextRenderer.Context where = new TextRenderer.Context(qualified.qualified(), root.module, shown,
                filter, Version.textOf(version), read);
        JsonRenderer.Where about = new JsonRenderer.Where(qualified.qualified(), read, root.module);
        streams.out().accept((json ? JsonRenderer.render(answer.value(), about)
                : TextRenderer.render(answer.value(), where)) + "\n");
        return 0;
    }

    private static int searchCentral(Commands.Root root, Streams streams, HttpOptions http, boolean json,
            UsageErrors usage) {
        List<String> keywords = rest(root);
        Failure invalid = rejectInvalidOutput(root);
        if (invalid == null) {
            invalid = SearchCentral.misuse(keywords, root.module, root.filter);
        }
        if (invalid == null) {
            invalid = pageBeforeFirst(root.page, SearchCentral.command(keywords));
        }
        if (invalid != null) {
            return usage.error(invalid);
        }
        Result<DiscoverResult> found = SearchCentral.render(keywords, root.page, http.withRefresh(root.refresh));
        if (!found.isOk()) {
            return fail(found.failure(), streams, json);
        }
        TextRenderer.Context where = new TextRenderer.Context(null, null,
                List.of(SearchCentral.VERB, String.join(" ", keywords)), null, null, null);
        streams.out().accept((json ? JsonRenderer.render(found.value())
                : TextRenderer.render(found.value(), where)) + "\n");
        return 0;
    }

    private static Result<DiscoverResult> answer(
            LoadedPackage loaded, String bucket, List<String> rest, String filter, int page) {
        Result<DiscoverResult> plain = answerFrom(loaded, bucket, rest, filter, page);
        if (!plain.isOk() || !loaded.bound().settles(() -> JsonRenderer.render(plain.value()))) {
            return plain;
        }
        LoadedPackage settled = loaded.withSource();
        return settled.library() == loaded.library() ? plain : answerFrom(settled, bucket, rest, filter, page);
    }

    private static Result<DiscoverResult> answerFrom(
            LoadedPackage loaded, String bucket, List<String> rest, String filter, int page) {
        if (bucket == null) {
            return filter == null
                    ? Result.ok(Overview.of(loaded))
                    : Search.render(loaded, new Search.Options(List.of(filter), page));
        }
        List<String> selectors = rest.subList(1, rest.size());
        if (Search.BUCKET.equals(bucket)) {
            return Search.render(loaded, new Search.Options(selectors, page));
        }
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

    private static Failure extraNames(String bucket, List<String> rest, String filter, Commands.Root root,
            QualifiedName qualified, Version version) {
        if (bucket == null) {
            return null;
        }
        List<String> selectors = rest.subList(1, rest.size());
        String command = new Coordinate(qualified, version).command(root.module) + " " + bucket;
        if (Types.BUCKET.equals(bucket)) {
            return Types.misuse(command, new Types.Options(selectors, filter, root.page));
        }
        if (Search.BUCKET.equals(bucket)) {
            return Search.misuse(new Coordinate(qualified, version).command(root.module) + " " + Search.BUCKET,
                    selectors, filter);
        }
        if (Surface.Scope.MODULE.verb().equals(bucket)) {
            return Containers.extraFunctionNames(command, new Containers.Options(selectors, filter, root.page));
        }
        return null;
    }

    private static String tail(List<String> rest, String filter, int page) {
        StringBuilder words = new StringBuilder();
        echoed(rest).forEach(word -> words.append(' ').append(Texts.shellWord(word)));
        if (filter != null) {
            words.append(" --filter ").append(Texts.shellWord(filter));
        }
        if (page != FIRST_PAGE) {
            words.append(" --page ").append(page);
        }
        return words.toString();
    }

    // The typed words as this tool prints them: a name or path spelled without Ballerina's escapes, as every answer
    // prints it. A readme title and a search keyword are text, not identifiers, so they are echoed as typed.
    private static List<String> echoed(List<String> rest) {
        if (rest.isEmpty() || Readme.BUCKET.equals(rest.get(0)) || Search.BUCKET.equals(rest.get(0))) {
            return rest;
        }
        return rest.stream().map(PathTree::plainSelector).toList();
    }

    private static String tail(Commands.Root root) {
        return tail(rest(root), root.filter, root.page);
    }

    private static List<String> rest(Commands.Root root) {
        return root.rest == null ? List.of() : root.rest;
    }

    private static Failure readAt(Failure failure, LoadedPackage loaded) {
        return failure instanceof Failure.Validation validation
                ? validation.about(loaded.qualified().qualified(), loaded.version().text())
                : failure;
    }

    private static Failure notPaged(Commands.Root root, Coordinate coordinate, String filter) {
        String command = coordinate.command(root.module) + tail(rest(root), filter, FIRST_PAGE);
        return new Failure.Validation(
                "This answer has only one page, so --page does not apply.",
                "Drop --page: " + Failure.quoted(command) + ".", command);
    }

    // A name typed where the bucket goes is most often a member's (`go doc` leaves the bucket out too): the search
    // finds whichever container declares it.
    private static Failure unknownBucket(String token, String pkg) {
        String search = pkg + " " + Search.BUCKET + " " + Texts.shellWord(token);
        return new Failure.Validation(
                "'" + token + "' is not a bucket.",
                "The buckets are " + String.join(", ", Commands.BUCKETS) + ". To find what is named '" + token
                        + "': " + Failure.quoted(search) + ".", search);
    }

    /**
     * The run's output mode: a valid {@code --output} in argv, else JSON unless stdout is a terminal. Read off argv,
     * not the parsed flag, so a parse failure is still written in the mode the caller asked for.
     */
    public static boolean jsonOutput(List<String> argv, boolean interactive) {
        return jsonOutput(argv, interactive, Commands.Grammar.create());
    }

    private static boolean jsonOutput(List<String> argv, boolean interactive, Commands.Grammar grammar) {
        Set<String> takesValue = grammar.line().getCommandSpec().options().stream()
                .filter(option -> option.arity().min() > 0)
                .flatMap(option -> Arrays.stream(option.names()))
                .collect(Collectors.toSet());
        for (int i = 0; i < argv.size(); i++) {
            String token = argv.get(i);
            String value = null;
            if (token.startsWith(OUTPUT_FLAG + "=")) {
                value = token.substring(OUTPUT_FLAG.length() + 1);
            } else if (takesValue.contains(token) && i + 1 < argv.size()) {
                i++;
                value = token.equals(OUTPUT_FLAG) ? argv.get(i) : null;
            }
            if (Commands.JSON_OUTPUT.equals(value) || Commands.TEXT_OUTPUT.equals(value)) {
                return Commands.JSON_OUTPUT.equals(value);
            }
        }
        return !interactive;
    }

    private static Failure validate(Commands.Root root, Coordinate coordinate) {
        Failure output = rejectInvalidOutput(root);
        if (output != null) {
            return output;
        }
        if (root.filter != null && root.filter.isBlank()) {
            return new Failure.Validation("The --filter option needs a keyword.",
                    "Write --filter <keyword>, or drop it to list everything.");
        }
        Failure page = pageBeforeFirst(root.page, coordinate.command(root.module) + tail(rest(root), root.filter,
                FIRST_PAGE));
        return page != null ? page : rejectVersionArguments(root, coordinate.qualified());
    }

    private static Failure pageBeforeFirst(int page, String firstPage) {
        if (page >= FIRST_PAGE) {
            return null;
        }
        return new Failure.Validation("Page " + page + " is out of range: pages are numbered from 1.",
                "Drop --page for the first page: " + Failure.quoted(firstPage) + ".", firstPage);
    }

    private static Failure versionFlag(Commands.Root root) {
        String message = "Unknown option '" + Commands.VERSION_FLAG + "'.";
        Result<Coordinate> coordinate = root.pkg == null ? null : Coordinate.parse(root.pkg);
        if (coordinate == null || !coordinate.isOk()) {
            return new Failure.Validation(message, "To read a specific version, write " + PLACEHOLDER_PACKAGE + ":"
                    + PLACEHOLDER_VERSION);
        }
        return writtenVersion(message, root, coordinate.value().qualified(), root.version, tail(root));
    }

    private static Failure writtenVersion(String message, Commands.Root root, QualifiedName qualified,
            String version, String tail) {
        if (!Version.isComplete(version)) {
            return new Failure.Validation(message, WRITE_VERSION + qualified.qualified() + ":" + PLACEHOLDER_VERSION);
        }
        String command = new Coordinate(qualified, Version.parse(version).value()).command(root.module) + tail;
        return new Failure.Validation(message, WRITE_VERSION + Failure.quoted(command) + ".", command);
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

    private static Failure rejectVersionArguments(Commands.Root root, QualifiedName qualified) {
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
        List<String> others = new ArrayList<>(tokens);
        others.remove(misplaced);
        return writtenVersion("'" + misplaced + "' looks like a version.", root, qualified, misplaced,
                tail(others, root.filter, root.page));
    }

    // The failure a parse error is, or null when picocli's own first line says it best.
    private static Failure describeParseError(CommandLine.ParameterException cause, Commands.Grammar grammar) {
        if (cause instanceof CommandLine.UnmatchedArgumentException unmatched) {
            List<String> tokens = unmatched.getUnmatched();
            // Every positional fits `[bucket] [name ...]`, so only an option is ever left unmatched.
            return new Failure.Validation(
                    "Unknown option '" + (tokens.isEmpty() ? "" : tokens.get(0)) + "'.",
                    UsageRenderer.knownFlags(grammar));
        }
        if (cause instanceof CommandLine.OverwrittenOptionException overwritten
                && overwritten.getOverwritten() instanceof CommandLine.Model.OptionSpec option) {
            return new Failure.Validation(
                    option.longestName() + " is given more than once.", "Pass " + option.longestName() + " once.");
        }
        if (cause instanceof CommandLine.MissingParameterException missing) {
            return missing.getMissing().stream()
                    .filter(CommandLine.Model.OptionSpec.class::isInstance)
                    .map(CommandLine.Model.OptionSpec.class::cast)
                    .findFirst()
                    .map(Cli::missingValue)
                    .orElse(null);
        }
        if (cause.getArgSpec() instanceof CommandLine.Model.OptionSpec option && cause.getValue() != null) {
            if (option.type() == boolean.class) {
                return new Failure.Validation(
                        option.longestName() + " takes no value.",
                        "Write " + option.longestName() + ".");
            }
            return new Failure.Validation(
                    "'" + cause.getValue() + "' is not a valid " + option.longestName() + " value.",
                    "Write " + option.longestName() + " " + option.paramLabel() + ".");
        }
        return null;
    }

    private static Failure missingValue(CommandLine.Model.OptionSpec option) {
        String name = option.longestName();
        String label = option.paramLabel();
        return new Failure.Validation(
                "The " + name + " option needs a value.",
                "Write " + name + " " + label + " or " + name + "=" + label + ".");
    }

    private static String firstLine(String message) {
        String line = message == null ? "Invalid arguments" : message.split("\n", -1)[0];
        return line.endsWith(".") ? line : line + ".";
    }

    private static int fail(Failure failure, Streams streams, boolean json) {
        streams.errorOut().accept(failure.describe(json) + "\n");
        return FAILED;
    }

    private record UsageErrors(Commands.Grammar grammar, Streams streams, boolean json) {

        int error(Failure failure) {
            return write(failure, true);
        }

        int helpError(String message) {
            return write(new Failure.Validation(message, RUN_HELP), false);
        }

        private int write(Failure failure, boolean pointToHelp) {
            String usage = json ? "" : "\n" + UsageRenderer.usageLine(grammar)
                    + (pointToHelp ? "\nRun `" + HELP + "` for details." : "");
            streams.errorOut().accept(failure.describe(json) + usage + "\n");
            return USAGE_ERROR;
        }
    }
}
