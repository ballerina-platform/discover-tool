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

import io.ballerina.tools.discover.Command;
import io.ballerina.tools.discover.Coordinate;
import io.ballerina.tools.discover.Failure;
import io.ballerina.tools.discover.LoadedPackage;
import io.ballerina.tools.discover.Loader;
import io.ballerina.tools.discover.QualifiedName;
import io.ballerina.tools.discover.Result;
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
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
 * <p>{@code bal discover <noun> [name ...] -p <org>/<name>[:<version>]} is one picocli subcommand per noun; see
 * {@link Commands}.
 *
 * <p>No {@link System#exit} here, and no environment read: the cache, the transport and the project directory
 * arrive as arguments, so a test drives the real command against a recorded payload and a temporary directory.
 * {@link DiscoverTool} is the only place that touches the process.
 *
 * @since 0.1.0
 */
public final class Cli {

    private static final String OUTPUT_FLAG = "--output";

    private static final String HELP_FLAG = "--help";

    static final int FAILED = 1;

    private static final int USAGE_ERROR = 2;

    private static final int FIRST_PAGE = 1;

    private static final String PLACEHOLDER_PACKAGE = "<org>/<name>";

    private static final String PLACEHOLDER_VERSION = "<version>";

    private static final String WRITE_VERSION = "To read a specific version, write it after the package: ";

    /** The old grammar's word for the module's functions, mapped when an old command is rewritten. */
    private static final String OLD_FUNCTIONS = "funcs";

    private static final String PATH_SEPARATOR = "/";

    private static final String FLAG_START = "-";

    /** How many names each noun takes; {@code search} takes any number of keywords. */
    private static final Map<String, Integer> MAX_NAMES = Map.of(
            Command.PACKAGE, 1, Command.CLIENT, 1, Command.SERVICE, 1, Command.CLASS, 1, Command.FUNCTION, 1,
            Command.TYPE, 1, Command.METHOD, 1, Command.RESOURCE, 2, Command.README, 1);

    /** The nouns that answer with containers, and the scope each lists. */
    private static final Map<String, Surface.Scope> CONTAINER_NOUNS = Map.of(
            Command.CLIENT, Surface.Scope.CLIENT, Command.SERVICE, Surface.Scope.SERVICE,
            Command.CLASS, Surface.Scope.CLASS, Command.FUNCTION, Surface.Scope.MODULE);

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
        CommandLine.ParseResult parsed;
        try {
            parsed = grammar.line().parseArgs(argv.toArray(new String[0]));
        } catch (CommandLine.ParameterException cause) {
            // picocli's own error printing is deliberately never used: stderr has to hold exactly one `Failure`
            // object, and picocli would write a usage block beside it.
            String noun = cause.getCommandLine() == grammar.line()
                    ? null
                    : cause.getCommandLine().getCommandSpec().name();
            UsageErrors usage = new UsageErrors(grammar, noun, streams, json);
            Failure specific = describeParseError(cause);
            return specific == null
                    ? usage.helpError(firstLine(cause.getMessage()))
                    : usage.error(specific);
        }

        CommandLine.ParseResult sub = parsed.hasSubcommand() ? parsed.subcommand() : null;
        String nounName = sub == null ? null : sub.commandSpec().name();
        // A usage request is answered, not failed: it goes to stdout at exit 0, because "exit 0 means stdout is
        // complete" is what a redirecting caller relies on.
        if (parsed.isUsageHelpRequested() || sub != null && sub.isUsageHelpRequested()) {
            streams.out().accept(nounName == null ? Usage.root() : Usage.noun(nounName));
            return 0;
        }
        UsageErrors usage = new UsageErrors(grammar, nounName, streams, json);
        Invocation call = Invocation.of(grammar.root(), nounName, nounName == null ? null : grammar.nouns()
                .get(nounName));
        Failure misuse = misuse(grammar, call);
        if (misuse != null) {
            return usage.error(misuse);
        }
        if (Command.SEARCH.equals(nounName) && call.pkg() == null) {
            return searchCentral(call, streams, http, json, usage);
        }
        return lookup(call, streams, http, projectDir, json, usage);
    }

    // What makes the command line itself unusable, before anything is fetched; null when it is well formed.
    private static Failure misuse(Commands.Grammar grammar, Invocation call) {
        if (call.version() != null) {
            return versionFlag(call);
        }
        if (!call.rest().isEmpty()) {
            String first = call.rest().get(0);
            return first.contains(PATH_SEPARATOR) ? oldForm(call) : unknownNoun(first, call);
        }
        if (call.noun() == null) {
            return new Failure.Validation("Name what to read: " + String.join(", ", Command.NOUNS) + ".",
                    "For a package's overview, write " + Failure.quoted(Command.PREFIX + Command.PACKAGE + " "
                            + PLACEHOLDER_PACKAGE) + ".");
        }
        Failure option = unknownOption(grammar, call);
        if (option != null) {
            return option;
        }
        Failure output = rejectInvalidOutput(call.output());
        if (output != null) {
            return output;
        }
        if (Command.SEARCH.equals(call.noun()) && call.pkg() == null) {
            return null;
        }
        return Command.PACKAGE.equals(call.noun()) ? packageMisuse(call) : scopedMisuse(call);
    }

    private static Failure packageMisuse(Invocation call) {
        if (call.pkg() != null && call.names().isEmpty()) {
            String command = Command.overview(new Command.Target(call.pkg(), call.module())).filtered(call.filter())
                    .text() + call.pageArgument();
            return new Failure.Validation("package takes the package as its name, not after -p.",
                    "Run " + Failure.quoted(command) + ".", command);
        }
        if (call.names().isEmpty()) {
            return new Failure.Validation("package needs the package to read.",
                    "Write " + Failure.quoted(Command.PREFIX + Command.PACKAGE + " " + PLACEHOLDER_PACKAGE)
                            + ", e.g. bal discover package ballerinax/github.");
        }
        return tooManyNames(call);
    }

    private static Failure scopedMisuse(Invocation call) {
        if (call.pkg() == null) {
            return new Failure.Validation(call.noun() + " needs the package it reads: -p " + Commands.PACKAGE_LABEL
                    + ".", "Add it, e.g. " + Failure.quoted(Command.of(call.noun(), call.echoed())
                    .in(new Command.Target("ballerinax/github", call.module())).text()) + ".");
        }
        if (call.owners() > 1) {
            return new Failure.Validation("A " + call.noun() + " has one container; pass one of --client, --class "
                    + "and --service.", "Keep the one that declares it.");
        }
        return tooManyNames(call);
    }

    private static Failure tooManyNames(Invocation call) {
        Integer max = MAX_NAMES.get(call.noun());
        List<String> names = call.names();
        if (max == null || names.size() <= max) {
            return null;
        }
        Failure version = rejectVersionArguments(call);
        if (version != null) {
            return version;
        }
        String command = CONTAINER_NOUNS.containsKey(call.noun()) && !Command.FUNCTION.equals(call.noun())
                ? call.retarget(memberOf(call.noun(), call.echoed())).text() + call.pageArgument()
                : call.withNames(names.subList(0, max)).command().text() + call.pageArgument();
        String takes = max == 1 ? "one name" : "a path and its accessor";
        return new Failure.Validation(call.noun() + " takes " + takes + "; got " + names.size() + ".",
                "Run " + Failure.quoted(command) + ".", command);
    }

    // `client C m` read as the member it names: a method, or a resource when a path or an accessor follows.
    private static Command memberOf(String noun, List<String> names) {
        List<String> member = names.subList(1, names.size());
        boolean resource = member.size() > 1 || member.get(0).contains(PATH_SEPARATOR);
        return Command.of(resource ? Command.RESOURCE : Command.METHOD, member).ownedBy(noun, names.get(0));
    }

    private static Failure unknownOption(Commands.Grammar grammar, Invocation call) {
        String unknown = call.names().stream()
                .filter(name -> name.startsWith(FLAG_START) && name.length() > 1)
                .findFirst()
                .orElse(null);
        return unknown == null
                ? null
                : new Failure.Validation("Unknown option '" + unknown + "'.",
                        UsageRenderer.knownFlags(grammar.spec(call.noun())));
    }

    // The package-first form this tool once took: the command to run now, with the old bucket mapped.
    private static Failure oldForm(Invocation call) {
        List<String> words = new ArrayList<>(call.rest());
        if (call.noun() != null) {
            words.add(call.noun());
            words.addAll(call.names());
        }
        String pkg = words.get(0);
        String bucket = words.size() > 1 ? words.get(1) : null;
        List<String> selectors = words.size() > 2
                ? words.subList(2, words.size()).stream().map(PathTree::plainSelector).toList()
                : List.of();
        Command mapped = oldBucket(bucket, selectors);
        String command = mapped.in(new Command.Target(pkg, call.module())).filtered(call.filter()).text()
                + call.pageArgument();
        return new Failure.Validation("The package goes after -p now, and what to read comes first.",
                "Run " + Failure.quoted(command) + ".", command);
    }

    private static Command oldBucket(String bucket, List<String> selectors) {
        if (bucket == null) {
            return Command.of(Command.PACKAGE);
        }
        String noun = OLD_FUNCTIONS.equals(bucket) ? Command.FUNCTION : bucket;
        if (CONTAINER_NOUNS.containsKey(noun) && !Command.FUNCTION.equals(noun) && selectors.size() > 1) {
            return memberOf(noun, selectors);
        }
        if (Command.README.equals(noun) && !selectors.isEmpty()) {
            return Command.of(noun, String.join(" ", selectors));
        }
        return Command.NOUNS.contains(noun) ? Command.of(noun, selectors) : Command.of(Command.PACKAGE);
    }

    // A word where the noun goes is most often a name (`go doc` leaves the kind out too): the search finds it.
    private static Failure unknownNoun(String token, Invocation call) {
        String search = Command.of(Command.SEARCH, token)
                .in(call.pkg() == null ? null : new Command.Target(call.pkg(), call.module())).text();
        return new Failure.Validation("'" + token + "' is not a noun. The nouns are " + String.join(", ",
                Command.NOUNS) + ".", "To find what is named '" + token + "': " + Failure.quoted(search) + ".",
                search);
    }

    private static int searchCentral(Invocation call, Streams streams, HttpOptions http, boolean json,
            UsageErrors usage) {
        List<String> keywords = call.names();
        Failure invalid = SearchCentral.misuse(keywords, call.module(), call.filter());
        if (invalid == null) {
            invalid = pageBeforeFirst(call.page(), SearchCentral.command(keywords));
        }
        if (invalid != null) {
            return usage.error(invalid);
        }
        Result<DiscoverResult> found = SearchCentral.render(keywords, call.page(), http.withRefresh(call.refresh()));
        if (!found.isOk()) {
            return fail(found.failure(), streams, json);
        }
        TextRenderer.Context where = new TextRenderer.Context(null, null,
                List.of(SearchCentral.VERB, String.join(" ", keywords)), null, null, null);
        streams.out().accept((json ? JsonRenderer.render(found.value())
                : TextRenderer.render(found.value(), where)) + "\n");
        return 0;
    }

    private static int lookup(Invocation call, Streams streams, HttpOptions http, String projectDir, boolean json,
            UsageErrors usage) {
        Result<Coordinate> coordinate = Coordinate.parse(call.coordinate(),
                corrected -> call.command(new Command.Target(corrected, call.module())).text() + call.pageArgument());
        if (!coordinate.isOk()) {
            return usage.error(coordinate.failure());
        }
        QualifiedName qualified = coordinate.value().qualified();
        Version version = coordinate.value().version();
        Invocation pinned = call.at(coordinate.value());

        Failure argumentError = validate(pinned);
        if (argumentError != null) {
            return usage.error(argumentError);
        }

        // `--refresh` is only known once arguments are parsed, so the injected options are rebuilt here.
        HttpOptions resolved = http.withRefresh(call.refresh());
        Loader.LoadOptions options = new Loader.LoadOptions(
                resolved, projectDir, List.of(CentralRepository.INSTANCE), call.module(), version);
        Result<LoadedPackage> loaded = Loader.loadPackage(qualified, options);
        if (!loaded.isOk()) {
            return fail(loaded.failure().retargeted(pinned::retargeted), streams, json);
        }

        Result<DiscoverResult> answer = answer(loaded.value(), call);
        if (!answer.isOk()) {
            return fail(readAt(answer.failure(), loaded.value()), streams, json);
        }
        if (call.page() != FIRST_PAGE && answer.value().paging() == null) {
            return fail(readAt(notPaged(pinned), loaded.value()), streams, json);
        }
        String read = loaded.value().version().text();
        TextRenderer.Context where = new TextRenderer.Context(qualified.qualified(), call.module(), call.trail(),
                call.filter(), Version.textOf(version), read);
        JsonRenderer.Where about = new JsonRenderer.Where(qualified.qualified(), read, call.module());
        streams.out().accept((json ? JsonRenderer.render(answer.value(), about)
                : TextRenderer.render(answer.value(), where)) + "\n");
        return 0;
    }

    private static Result<DiscoverResult> answer(LoadedPackage loaded, Invocation call) {
        Result<DiscoverResult> plain = answerFrom(loaded, call);
        if (!plain.isOk() || !loaded.bound().settles(() -> JsonRenderer.render(plain.value()))) {
            return plain;
        }
        LoadedPackage settled = loaded.withSource();
        return settled.library() == loaded.library() ? plain : answerFrom(settled, call);
    }

    private static Result<DiscoverResult> answerFrom(LoadedPackage loaded, Invocation call) {
        String name = call.names().isEmpty() ? null : call.names().get(0);
        String filter = call.filter();
        int page = call.page();
        return switch (call.noun()) {
            case Command.PACKAGE -> filter == null
                    ? Result.ok(Overview.of(loaded))
                    : Search.render(loaded, new Search.Options(List.of(filter), page));
            case Command.SEARCH -> Search.render(loaded, new Search.Options(call.names(), page));
            case Command.README -> Readme.render(loaded, new Readme.Options(name, filter, page));
            case Command.TYPE -> Types.render(loaded, new Types.Options(call.names(), filter, page));
            case Command.FUNCTION -> Containers.render(loaded, Surface.Scope.MODULE,
                    Containers.Options.function(name, filter, page));
            case Command.METHOD -> Containers.render(loaded, call.ownerScope(),
                    Containers.Options.method(call.ownerName(), name, filter, page));
            case Command.RESOURCE -> Containers.render(loaded, call.ownerScope(),
                    Containers.Options.resource(call.ownerName(), call.names(), filter, page));
            default -> Containers.render(loaded, CONTAINER_NOUNS.get(call.noun()),
                    Containers.Options.container(name, filter, page));
        };
    }

    private static Failure readAt(Failure failure, LoadedPackage loaded) {
        return failure instanceof Failure.Validation validation
                ? validation.about(loaded.qualified().qualified(), loaded.version().text())
                : failure;
    }

    private static Failure notPaged(Invocation call) {
        String command = call.command().text();
        return new Failure.Validation(
                "This answer has only one page, so --page does not apply.",
                "Drop --page: " + Failure.quoted(command) + ".", command);
    }

    /**
     * The run's output mode: a valid {@code --output} in argv, else JSON unless stdout is a terminal. Read off argv,
     * not the parsed flag, so a parse failure is still written in the mode the caller asked for.
     */
    public static boolean jsonOutput(List<String> argv, boolean interactive) {
        return jsonOutput(argv, interactive, Commands.Grammar.create());
    }

    private static boolean jsonOutput(List<String> argv, boolean interactive, Commands.Grammar grammar) {
        Set<String> takesValue = Stream.concat(Stream.of(grammar.line()), grammar.line().getSubcommands().values()
                        .stream())
                .flatMap(line -> line.getCommandSpec().options().stream())
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

    private static Failure validate(Invocation call) {
        if (call.filter() != null && call.filter().isBlank()) {
            return new Failure.Validation("The --filter option needs a keyword.",
                    "Write --filter <keyword>, or drop it to list everything.");
        }
        Failure page = pageBeforeFirst(call.page(), call.command().text());
        if (page != null) {
            return page;
        }
        Failure version = rejectVersionArguments(call);
        if (version != null) {
            return version;
        }
        if (Command.TYPE.equals(call.noun())) {
            return Types.misuse(call.command(Command.TYPE).text(),
                    new Types.Options(call.names(), call.filter(), call.page()));
        }
        if (Command.SEARCH.equals(call.noun())) {
            return Search.misuse(call.command(Command.SEARCH), call.names(), call.filter());
        }
        return null;
    }

    private static Failure pageBeforeFirst(int page, String firstPage) {
        if (page >= FIRST_PAGE) {
            return null;
        }
        return new Failure.Validation("Page " + page + " is out of range: pages are numbered from 1.",
                "Drop --page for the first page: " + Failure.quoted(firstPage) + ".", firstPage);
    }

    private static Failure versionFlag(Invocation call) {
        String message = "Unknown option '" + Commands.VERSION_FLAG + "'.";
        Result<Coordinate> coordinate = call.coordinate() == null ? null : Coordinate.parse(call.coordinate());
        if (coordinate == null || !coordinate.isOk() || call.noun() == null) {
            return new Failure.Validation(message, "To read a specific version, write " + PLACEHOLDER_PACKAGE + ":"
                    + PLACEHOLDER_VERSION);
        }
        return writtenVersion(message, call, coordinate.value().qualified(), call.version());
    }

    private static Failure writtenVersion(String message, Invocation call, QualifiedName qualified, String version) {
        if (!Version.isComplete(version)) {
            return new Failure.Validation(message, WRITE_VERSION + qualified.qualified() + ":" + PLACEHOLDER_VERSION);
        }
        String command = call.at(new Coordinate(qualified, Version.parse(version).value())).command().text()
                + call.pageArgument();
        return new Failure.Validation(message, WRITE_VERSION + Failure.quoted(command) + ".", command);
    }

    private static Failure rejectInvalidOutput(String output) {
        if (output == null || Commands.JSON_OUTPUT.equals(output) || Commands.TEXT_OUTPUT.equals(output)) {
            return null;
        }
        return new Failure.Validation(
                "'" + output + "' is not a valid --output value.",
                "Pass --output " + Commands.JSON_OUTPUT + " or --output " + Commands.TEXT_OUTPUT + ".");
    }

    // A complete version typed as a name: it belongs after the package.
    private static Failure rejectVersionArguments(Invocation call) {
        if (Command.SEARCH.equals(call.noun()) || call.coordinate() == null) {
            return null;
        }
        List<String> names = call.names();
        int from = Command.PACKAGE.equals(call.noun()) ? 1 : 0;
        String misplaced = names.subList(Math.min(from, names.size()), names.size()).stream()
                .filter(Version::isComplete)
                .findFirst()
                .orElse(null);
        Result<Coordinate> coordinate = Coordinate.parse(call.coordinate());
        if (misplaced == null || !coordinate.isOk()) {
            return null;
        }
        List<String> others = new ArrayList<>(names);
        others.remove(misplaced);
        return writtenVersion("'" + misplaced + "' looks like a version.", call.withNames(others),
                coordinate.value().qualified(), misplaced);
    }

    // The failure a parse error is, or null when picocli's own first line says it best.
    private static Failure describeParseError(CommandLine.ParameterException cause) {
        if (cause instanceof CommandLine.UnmatchedArgumentException unmatched) {
            List<String> tokens = unmatched.getUnmatched();
            return new Failure.Validation(
                    "Unknown option '" + (tokens.isEmpty() ? "" : tokens.get(0)) + "'.",
                    UsageRenderer.knownFlags(cause.getCommandLine().getCommandSpec()));
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

    /**
     * Writes a usage error: the failure, then in text mode the synopsis of the noun it is about and where its help
     * is.
     *
     * @param grammar the grammar the synopsis is rendered from
     * @param noun the noun the command line named, or {@code null} for the root
     * @param streams where the failure goes
     * @param json whether the run's output mode is JSON
     */
    private record UsageErrors(Commands.Grammar grammar, String noun, Streams streams, boolean json) {

        int error(Failure failure) {
            return write(failure, true);
        }

        int helpError(String message) {
            return write(new Failure.Validation(message, "Run `" + help() + "` for usage."), false);
        }

        private String help() {
            return Command.PREFIX + (noun == null ? "" : noun + " ") + HELP_FLAG;
        }

        private int write(Failure failure, boolean pointToHelp) {
            String usage = json ? "" : "\n" + UsageRenderer.usageLine(grammar, noun)
                    + (pointToHelp ? "\nRun `" + help() + "` for details." : "");
            streams.errorOut().accept(failure.describe(json) + usage + "\n");
            return USAGE_ERROR;
        }
    }
}
