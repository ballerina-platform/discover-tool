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

import io.ballerina.projects.util.ProjectUtils;
import io.ballerina.tools.discover.Command;
import io.ballerina.tools.discover.Coordinate;
import io.ballerina.tools.discover.Failure;
import io.ballerina.tools.discover.LoadedPackage;
import io.ballerina.tools.discover.Loader;
import io.ballerina.tools.discover.QualifiedName;
import io.ballerina.tools.discover.Result;
import io.ballerina.tools.discover.Texts;
import io.ballerina.tools.discover.Version;
import io.ballerina.tools.discover.Warning;
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
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
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
 *   <li>stderr — any warning, each its own report, and on failure one {@link Failure}, in the run's output mode: one
 *       JSON object a report, or {@code bal discover: error: <message>} with the suggestion and the command that
 *       fixes it indented below it
 *   <li>exit 0 — success, and stdout is COMPLETE
 *   <li>exit 2 — a usage error ({@link Failure#exitCode()}): the command line is malformed, or what it names matches
 *       several things; in text mode it is followed by the synopsis and a pointer to {@code --help}
 *   <li>exit 1 — any other failure
 * </ul>
 *
 * <p>{@code bal discover <noun> [name] -p <org>/<name>[:<version>]} is one picocli subcommand per noun; see
 * {@link Commands}.
 *
 * <p>No {@link System#exit} here, and no environment read: the cache, the transport and the project directory
 * arrive as arguments, so a test drives the real command against a recorded payload and a temporary directory.
 * {@link DiscoverTool} is the only place that touches the process.
 *
 * @since 0.1.0
 */
public final class Cli {

    private static final String PLACEHOLDER_PACKAGE = "<org>/<name>";

    private static final String WRITE_VERSION_AFTER = "To read a specific version, write it after the package.";

    private static final String INVALID_ARGUMENTS = "invalid arguments";

    /** What is typed for a noun out of habit: the old grammar's bucket names, and plurals. */
    private static final Map<String, String> NOUN_ALIASES = Map.ofEntries(
            Map.entry("funcs", Command.FUNCTION), Map.entry("func", Command.FUNCTION),
            Map.entry("functions", Command.FUNCTION), Map.entry("methods", Command.METHOD),
            Map.entry("clients", Command.CLIENT), Map.entry("services", Command.SERVICE),
            Map.entry("classes", Command.CLASS), Map.entry("types", Command.TYPE),
            Map.entry("resources", Command.RESOURCE), Map.entry("packages", Command.PACKAGE));

    /** The nouns whose names are a member of a container, which a container flag names. */
    private static final Set<String> MEMBER_NOUNS = Set.of(Command.METHOD, Command.RESOURCE);

    private static final String PLACEHOLDER_QUERY = "<query>";

    /** How a resource is written, for a usage error that has no command to offer. */
    private static final String RESOURCE_FORM = "Write the path alone, and put its accessor in "
            + Command.ACCESSOR_FLAG + ".";

    /** The short form {@code --module} had before the CLI tool guide reserved {@code -m}. */
    private static final String RETIRED_MODULE_SHORT = "-m";


    private static final String PATH_SEPARATOR = "/";

    /** What joins a flag to its value in one word, {@code --flag=value}. */
    private static final String VALUE_SEPARATOR = "=";

    /** The accessors a resource is most often declared with, which tell a resource's last name from a stray word. */
    private static final Set<String> RESOURCE_ACCESSORS =
            Set.of("get", "post", "put", "delete", "patch", "head", "options", "default");


    /** How many names a noun takes: one, its query for {@code search}; a resource's accessor is a flag. */
    private static final int MAX_NAMES = 1;

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
     * @param interactive whether stdout is an interactive terminal — the {@code --output-format} default in the
     *     absence of the flag. Discovered by {@link DiscoverTool} from {@link Terminal}, never read here: a
     *     test drives both defaults without a real terminal, the same reason the cache and the transport are
     *     injected.
     */
    public static int run(
            List<String> argv, Streams streams, HttpOptions http, String projectDir, boolean interactive) {
        if (argv.isEmpty()) {
            streams.out().accept(Usage.root());
            return Failure.ANSWERED;
        }

        Commands.Grammar grammar = Commands.Grammar.create();
        boolean json = jsonOutput(argv, interactive, grammar);
        Failure.Validation flag = valuedSwitch(grammar, argv);
        if (flag == null) {
            flag = retiredModuleShort(grammar, argv);
        }
        if (flag != null) {
            return new UsageErrors(grammar, typedNoun(grammar, argv), streams, json).error(flag);
        }
        Failure.Validation misnamed = misnamedNoun(argv);
        if (misnamed != null) {
            return new UsageErrors(grammar, null, streams, json).error(misnamed);
        }
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
            Failure.Validation accessor = unparsedAccessor(argv, cause);
            if (accessor != null) {
                return usage.error(accessor);
            }
            Failure.Validation specific = describeParseError(cause);
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
            return Failure.ANSWERED;
        }
        Invocation typed = Invocation.of(grammar.root(), nounName, nounName == null ? null : grammar.nouns()
                .get(nounName));
        Invocation call = typed.asBareCoordinate() ? typed.asPackageOverview() : typed;
        UsageErrors usage = new UsageErrors(grammar, call.noun(), streams, json);
        if (parsed.isVersionHelpRequested() && call.noun() == null && call.rest().isEmpty()) {
            streams.out().accept(ToolVersion.text() + "\n");
            return Failure.ANSWERED;
        }
        // The version is the root's alone, so a noun, or a bare coordinate standing for package, takes no -V.
        if (parsed.isVersionHelpRequested() && call.noun() != null) {
            return usage.error(unknownFlag(versionSpelling(argv), call.noun()));
        }
        Failure.Validation misuse = misuse(grammar, call, literalWords(argv));
        if (misuse != null) {
            return usage.error(misuse);
        }
        if (Command.SEARCH.equals(nounName) && call.pkg() == null) {
            return searchCentral(call, streams, http, json, usage);
        }
        return lookup(call, streams, http, projectDir, json, usage);
    }

    // What makes the command line itself unusable, before anything is fetched; null when it is well formed.
    private static Failure.Validation misuse(Commands.Grammar grammar, Invocation call, int literal) {
        String repeated = Invocation.repeatedFlag(grammar.root(), call.noun() == null ? null
                : grammar.nouns().get(call.noun()));
        if (repeated != null) {
            return new Failure.Validation(repeated + " is given both before the noun and after it",
                    "Pass " + repeated + " once.");
        }
        if (!call.rest().isEmpty()) {
            String flag = firstFlag(call.rest(), literal);
            if (flag != null) {
                return unknownFlag(flag, null);
            }
            String first = call.rest().get(0);
            return first.contains(PATH_SEPARATOR) ? oldForm(call) : unknownNoun(first, call);
        }
        if (call.noun() == null) {
            return new Failure.Validation("name what to read: " + String.join(", ", Command.NOUNS),
                    "For a package's overview, write " + Failure.quoted(Command.PREFIX + Command.PACKAGE + " "
                            + PLACEHOLDER_PACKAGE) + ".");
        }
        Failure.Validation option = unknownOption(grammar, call, literal);
        if (option != null) {
            return option;
        }
        if (!MEMBER_NOUNS.contains(call.noun()) && !call.owners().isEmpty()) {
            return unknownFlag(call.owners().get(0).flag(), call.noun());
        }
        if (!Command.RESOURCE.equals(call.noun()) && call.accessor() != null) {
            return accessorElsewhere(grammar, call, literal);
        }
        Failure.Validation output = rejectInvalidOutput(call.output());
        if (output != null) {
            return output;
        }
        Failure.Validation module = rejectInvalidModule(call.module());
        if (module != null) {
            return module;
        }
        if (Command.SEARCH.equals(call.noun()) && call.pkg() == null) {
            return tooManyNames(call);
        }
        return Command.PACKAGE.equals(call.noun()) ? packageMisuse(call) : scopedMisuse(call);
    }

    // `--accessor` on a noun other than `resource`: the same command without it, once that one is well formed.
    private static Failure.Validation accessorElsewhere(Commands.Grammar grammar, Invocation call, int literal) {
        String message = Command.ACCESSOR_FLAG + " applies only to " + Command.RESOURCE;
        String drop = "Drop " + Command.ACCESSOR_FLAG + ".";
        Invocation without = call.withAccessor(null);
        Failure.Validation further = misuse(grammar, without, literal);
        if (further != null) {
            return new Failure.Validation(message + "; " + further.message(), further.suggestion(),
                    further.command(), null, null, further.owners());
        }
        if (Command.SEARCH.equals(call.noun()) && call.pkg() == null) {
            return new Failure.Validation(message, drop,
                    call.paged(Command.of(Command.SEARCH, call.names())).text());
        }
        return without.coordinate() == null
                ? new Failure.Validation(message, drop)
                : new Failure.Validation(message, drop, without.paged(without.command()).text());
    }

    private static Failure.Validation packageMisuse(Invocation call) {
        if (call.pkg() != null) {
            String pkg = call.names().isEmpty() ? call.pkg() : call.names().get(0);
            String command = call.paged(Command.overview(new Command.Target(pkg, call.module()))
                    .filtered(call.filter())).text();
            return new Failure.Validation("package takes the package as its name, not after -p",
                    "Write the package as the name.", command);
        }
        if (call.names().isEmpty()) {
            return new Failure.Validation("package needs the package to read",
                    "Write " + Failure.quoted(Command.PREFIX + Command.PACKAGE + " " + PLACEHOLDER_PACKAGE)
                            + ", e.g. 'bal discover package ballerinax/github'.");
        }
        return tooManyNames(call);
    }

    private static Failure.Validation scopedMisuse(Invocation call) {
        String misplaced = misplacedPackage(call);
        if (misplaced != null) {
            return packageAsName(call, misplaced);
        }
        if (call.pkg() == null) {
            return missingPackage(call);
        }
        if (call.owners().size() > 1) {
            return severalOwnerFlags(call);
        }
        Failure.Validation tooMany = tooManyNames(call);
        if (tooMany != null || call.accessor() == null || !call.names().isEmpty()) {
            return tooMany;
        }
        String listing = call.paged(call.command().accessedBy(null)).text();
        return new Failure.Validation(Command.ACCESSOR_FLAG + " names a method of one path, and no path is given",
                "Write the path before " + Command.ACCESSOR_FLAG + "; list the paths with " + Failure.quoted(listing)
                        + ".");
    }

    // A name that is a package coordinate, typed where a name goes. With -p given, a slashed name where a path or a
    // readme title may go is the package only when it is the same package.
    private static String misplacedPackage(Invocation call) {
        List<String> names = call.names();
        if (Command.SEARCH.equals(call.noun()) || names.isEmpty()) {
            return null;
        }
        if (call.pkg() == null) {
            return Command.RESOURCE.equals(call.noun()) ? resourcePackage(call) : namePackage(names);
        }
        String candidate = names.getFirst();
        if (names.size() > MAX_NAMES || !isCoordinate(candidate)) {
            return null;
        }
        boolean slashed = Command.RESOURCE.equals(call.noun()) || Command.README.equals(call.noun())
                || (CONTAINER_NOUNS.containsKey(call.noun()) && !Command.FUNCTION.equals(call.noun()));
        return !slashed || samePackage(candidate, call.pkg()) ? candidate : null;
    }

    // A resource path reads as a coordinate too, so with one name more than a path and its accessor the first name is
    // the package when it is one and the rest are a path and its accessor, else the last.
    private static String resourcePackage(Invocation call) {
        return resourcePackages(call).stream().findFirst().orElse(null);
    }

    private static List<String> resourcePackages(Invocation call) {
        if (accessorMoved(call).names().size() <= MAX_NAMES) {
            return List.of();
        }
        List<String> names = call.names();
        return Stream.of(names.getFirst(), names.getLast())
                .distinct()
                .filter(name -> isCoordinate(name)
                        && accessorMoved(call.withNames(without(names, name))).names().size() == MAX_NAMES)
                .toList();
    }

    // One name more than the noun takes: the last name, or the first when a slashed name follows it.
    private static String namePackage(List<String> names) {
        if (names.size() > MAX_NAMES + 1) {
            return null;
        }
        if (isCoordinate(names.getLast())) {
            return names.getLast();
        }
        return names.size() > MAX_NAMES && names.get(1).contains(PATH_SEPARATOR) && isCoordinate(names.getFirst())
                ? names.getFirst()
                : null;
    }

    private static List<String> without(List<String> names, String pkg) {
        List<String> others = new ArrayList<>(names);
        others.remove(pkg);
        return others;
    }

    static boolean isCoordinate(String word) {
        return word.contains(PATH_SEPARATOR) && Coordinate.parse(word).isOk();
    }

    private static boolean samePackage(String typed, String pkg) {
        Result<Coordinate> given = Coordinate.parse(pkg);
        return given.isOk() && Coordinate.parse(typed).value().qualified().equals(given.value().qualified());
    }

    private static Failure.Validation packageAsName(Invocation call, String pkg) {
        List<String> either = call.pkg() == null && Command.RESOURCE.equals(call.noun())
                ? resourcePackages(call)
                : List.of();
        if (either.size() > 1) {
            return new Failure.Validation("'" + either.getFirst() + "' and '" + either.getLast()
                    + "' both read as a package; name the package with -p",
                    "Name the package with -p and the path alone.");
        }
        List<String> others = without(call.names(), pkg);
        String target = call.pkg() == null ? pkg : call.pkg();
        String where = call.pkg() == null ? "name it with -p" : "-p names the package already";
        String message = "'" + pkg + "' is a package, not a " + call.noun() + " name; " + where;
        return new Failure.Validation(message, "Name the package with -p.",
                call.paged(accessorMoved(call.withNames(others)).command(new Command.Target(target, call.module())))
                        .text());
    }

    // A resource's names with the word typed last read before the package is: as its --accessor when it is one of
    // the accessors resources are most often declared with.
    private static Invocation accessorMoved(Invocation call) {
        List<String> names = call.names();
        boolean typedAfter = Command.RESOURCE.equals(call.noun()) && names.size() > MAX_NAMES
                && call.accessor() == null && isAccessor(names.getLast());
        return typedAfter ? call.withNames(names.subList(0, names.size() - 1)).withAccessor(names.getLast()) : call;
    }

    private static boolean isAccessor(String word) {
        return RESOURCE_ACCESSORS.contains(word);
    }

    // Never a package the caller did not give: the search that finds one, or the command's shape.
    private static Failure.Validation missingPackage(Invocation call) {
        String message = call.noun() + " needs the package it reads: -p " + Commands.PACKAGE_LABEL;
        Invocation moved = accessorMoved(call);
        if (Command.RESOURCE.equals(call.noun()) && moved.names().size() > MAX_NAMES) {
            moved = moved.withNames(moved.names().subList(0, MAX_NAMES))
                    .withAccessor(Command.placeholder(Command.ACCESSOR_LABEL));
        }
        Command shape = moved.command(new Command.Target(Command.placeholder(PLACEHOLDER_PACKAGE), call.module()))
                .filtered(null);
        String write = "Write " + Failure.quoted(shape.text());
        if (call.names().isEmpty() || Command.README.equals(call.noun())) {
            return new Failure.Validation(message, write + "; find the package with "
                    + Failure.quoted(Command.of(Command.SEARCH, Command.placeholder(PLACEHOLDER_QUERY)).text())
                    + ".");
        }
        String name = Containers.searchKeyword(call.echoed().get(0)).orElse(call.echoed().get(0));
        String search = Command.of(Command.SEARCH, name).text();
        return new Failure.Validation(message, "Search Central for the package that declares '" + name
                + "', then " + Character.toLowerCase(write.charAt(0)) + write.substring(1) + ".", search);
    }

    private static Failure.Validation severalOwnerFlags(Invocation call) {
        List<Failure.Match> rows = call.owners().stream()
                .map(owner -> new Failure.Match(owner.flag() + " " + owner.name(),
                        call.paged(call.ownedBy(owner).command()).text()))
                .toList();
        return new Failure.Validation("a " + call.noun() + " has one container; pass one of "
                + String.join(", ", Command.OWNER_FLAGS), "Keep the one that declares it.", rows);
    }

    private static Failure.Validation tooManyNames(Invocation call) {
        List<String> names = call.names();
        if (names.size() <= MAX_NAMES) {
            return null;
        }
        Failure.Validation version = rejectVersionArguments(call);
        if (version != null) {
            return version;
        }
        // Which of a member's names is a container or a path is known only once the package is read.
        if (MEMBER_NOUNS.contains(call.noun())) {
            return null;
        }
        if (Command.SEARCH.equals(call.noun())) {
            Invocation quoted = call.withNames(List.of(String.join(" ", names)));
            Command fixed = call.pkg() == null ? Command.of(Command.SEARCH, quoted.names()) : quoted.command();
            return new Failure.Validation(tooMany(call.noun(), names.size()),
                    "Quote the keywords as one query.", call.paged(fixed).text());
        }
        Command fixed;
        if (CONTAINER_NOUNS.containsKey(call.noun()) && !Command.FUNCTION.equals(call.noun())) {
            Command member = memberOf(call.noun(), call.echoed());
            if (member == null) {
                return new Failure.Validation(tooMany(call.noun(), names.size()), RESOURCE_FORM);
            }
            fixed = call.retarget(member);
        } else if (Command.README.equals(call.noun())) {
            fixed = call.withNames(List.of(String.join(" ", names))).command();
        } else {
            fixed = call.withNames(names.subList(0, MAX_NAMES)).command();
        }
        String command = call.paged(fixed).text();
        return new Failure.Validation(tooMany(call.noun(), names.size()), "Drop the names it does not take.",
                command);
    }

    private static String tooMany(String noun, int given) {
        String takes = switch (noun) {
            case Command.RESOURCE -> "one path, and its accessor as " + Command.ACCESSOR_FLAG;
            case Command.README -> "one section, a title in quotes";
            case Command.SEARCH -> "one query, in quotes";
            default -> "one name";
        };
        return noun + " takes " + takes + "; got " + given;
    }

    // `client C m` read as the member it names: a resource when a path or an accessor follows, else the method,
    // and any word after a method dropped. Null for a resource whose words are more than a path and its accessor.
    private static Command memberOf(String noun, List<String> names) {
        List<String> member = names.subList(1, names.size());
        boolean resource = member.getFirst().contains(PATH_SEPARATOR)
                || (member.size() > MAX_NAMES && isAccessor(member.getLast()));
        Command meant = resource ? resourceMeant(member) : Command.of(Command.METHOD, member.getFirst());
        return meant == null ? null : meant.ownedBy(noun, names.getFirst());
    }

    // A resource's words as the command they mean: the path, and a second word as its --accessor when it is an
    // accessor. Null when any other word follows the path, since no command keeps it.
    private static Command resourceMeant(List<String> words) {
        boolean accessed = words.size() == MAX_NAMES + 1 && isAccessor(words.getLast());
        if (words.size() > MAX_NAMES && !accessed) {
            return null;
        }
        return Command.of(Command.RESOURCE, words.getFirst()).accessedBy(accessed ? words.getLast() : null);
    }

    // `literal`: how many of the last names follow `--`, where a leading dash is part of the name.
    private static Failure.Validation unknownOption(Commands.Grammar grammar, Invocation call, int literal) {
        String unknown = call.names().subList(0, Math.max(0, call.names().size() - literal)).stream()
                .filter(name -> name.startsWith(Command.FLAG_START) && name.length() > 1)
                .findFirst()
                .orElse(null);
        if (unknown == null) {
            return null;
        }
        return isAccessorFlag(unknown)
                ? accessorElsewhere(grammar, call.withNames(droppingAccessor(call.names(), unknown)), literal)
                : unknownFlag(unknown, call.noun());
    }

    private static boolean isAccessorFlag(String word) {
        return word.equals(Command.ACCESSOR_FLAG) || word.startsWith(Command.ACCESSOR_FLAG + VALUE_SEPARATOR);
    }

    // `words` without the `--accessor` word `flag`, and its value when that is the next word.
    private static List<String> droppingAccessor(List<String> words, String flag) {
        List<String> kept = new ArrayList<>(words);
        int at = kept.indexOf(flag);
        kept.remove(at);
        if (flag.equals(Command.ACCESSOR_FLAG) && at < kept.size()) {
            kept.remove(at);
        }
        return kept;
    }

    // `--accessor` where the parsed noun has no room for it: the command line without it, read as any other is.
    private static Failure.Validation unparsedAccessor(List<String> argv, CommandLine.ParameterException cause) {
        if (!(cause instanceof CommandLine.UnmatchedArgumentException unmatched)
                || unmatched.getUnmatched().isEmpty() || !isAccessorFlag(unmatched.getUnmatched().getFirst())) {
            return null;
        }
        List<String> rest = droppingAccessor(argv, unmatched.getUnmatched().getFirst());
        Commands.Grammar probe = Commands.Grammar.create();
        try {
            if (!probe.line().parseArgs(rest.toArray(new String[0])).hasSubcommand()) {
                return null;
            }
        } catch (CommandLine.ParameterException unparsed) {
            return null;
        }
        String noun = probe.line().getParseResult().subcommand().commandSpec().name();
        return accessorElsewhere(probe, Invocation.of(probe.root(), noun, probe.nouns().get(noun)), literalWords(rest));
    }

    private static Failure.Validation unknownFlag(String flag, String noun) {
        return new Failure.Validation("unknown option '" + flag + "'", helpSuggestion(noun));
    }

    private static String versionSpelling(List<String> argv) {
        return argv.contains(Commands.VERSION_SHORT) ? Commands.VERSION_SHORT : Commands.VERSION_FLAG;
    }

    // The first word of `words` that is a flag, leaving out the last `literal` words, which follow `--`.
    private static String firstFlag(List<String> words, int literal) {
        return words.subList(0, Math.max(0, words.size() - literal)).stream()
                .filter(word -> word.startsWith(Command.FLAG_START) && word.length() > 1)
                .findFirst()
                .orElse(null);
    }

    /** The suggestion of a usage error that only the help answers: where {@code noun}'s help is. */
    static String helpSuggestion(String noun) {
        return "Run " + Failure.quoted(helpCommand(noun)) + " for the usage.";
    }

    static String helpCommand(String noun) {
        return Command.PREFIX + (noun == null ? "" : noun + " ") + Commands.HELP_FLAG;
    }

    // The package-first form this tool once took: the command to run now, with the old bucket mapped.
    private static Failure.Validation oldForm(Invocation call) {
        List<String> words = new ArrayList<>(call.rest());
        if (call.noun() != null) {
            words.add(call.noun());
            words.addAll(call.names());
        }
        String typed = words.remove(0);
        String pkg = typed;
        if (!words.isEmpty() && Version.isComplete(words.get(0))) {
            pkg = Coordinate.text(pkg, words.remove(0));
        }
        String bucket = words.isEmpty() ? null : words.get(0);
        List<String> selectors = words.size() > 1
                ? words.subList(1, words.size()).stream().map(PathTree::plainSelector).toList()
                : List.of();
        Command mapped = oldBucket(bucket, selectors);
        String message = "'" + typed + "' is not a noun; name the package with -p";
        if (mapped == null) {
            return new Failure.Validation(message, "Name the noun first and the package with -p. " + RESOURCE_FORM);
        }
        String command = call.paged(mapped.in(new Command.Target(pkg, call.module())).filtered(call.filter()))
                .text();
        return new Failure.Validation(message, "Name the noun first and the package with -p.", command);
    }

    private static Command oldBucket(String bucket, List<String> selectors) {
        if (bucket == null) {
            return Command.of(Command.PACKAGE);
        }
        String noun = NOUN_ALIASES.getOrDefault(bucket, bucket);
        if (!Command.NOUNS.contains(noun)) {
            return Command.of(Command.SEARCH, bucket);
        }
        if (CONTAINER_NOUNS.containsKey(noun) && !Command.FUNCTION.equals(noun) && selectors.size() > 1) {
            return memberOf(noun, selectors);
        }
        if ((Command.README.equals(noun) || Command.SEARCH.equals(noun)) && !selectors.isEmpty()) {
            return Command.of(noun, String.join(" ", selectors));
        }
        if (Command.RESOURCE.equals(noun) && !selectors.isEmpty()) {
            return resourceMeant(selectors);
        }
        return Command.of(noun, selectors);
    }

    // A word where the noun goes is most often a name (`go doc` leaves the kind out too): the search finds it.
    private static Failure.Validation unknownNoun(String token, Invocation call) {
        String search = Command.of(Command.SEARCH, token)
                .in(call.pkg() == null ? null : new Command.Target(call.pkg(), call.module())).text();
        return new Failure.Validation("'" + token + "' is not a noun; the nouns are " + String.join(", ",
                Command.NOUNS), "Search for what is named '" + token + "'.", search);
    }

    // An old bucket name or a plural typed as the noun: the same command with the noun it meant. Read off argv
    // before parsing, since the noun decides which flags parse.
    private static Failure.Validation misnamedNoun(List<String> argv) {
        Commands.Grammar probe = Commands.Grammar.create();
        int at = firstWord(argv, valueOptions(probe));
        String noun = at < 0 ? null : NOUN_ALIASES.get(argv.get(at));
        if (noun == null) {
            return null;
        }
        String message = "'" + argv.get(at) + "' is not a noun; the noun is " + noun;
        List<String> fixed = new ArrayList<>(argv);
        fixed.set(at, noun);
        Failure.Validation fixedNoun = fixedNoun(probe, fixed, noun, message);
        return fixedNoun != null ? fixedNoun : new Failure.Validation(message, "Write " + noun + " in its place.");
    }

    // The command with the noun fixed, folding in the fix of whatever else it still gets wrong; null when the rest
    // does not parse or names nothing runnable.
    private static Failure.Validation fixedNoun(Commands.Grammar probe, List<String> fixed, String noun,
            String message) {
        Invocation call;
        try {
            if (!probe.line().parseArgs(fixed.toArray(new String[0])).hasSubcommand()) {
                return null;
            }
            call = Invocation.of(probe.root(), noun, probe.nouns().get(noun));
        } catch (CommandLine.ParameterException unparsed) {
            return null;
        }
        Failure.Validation further = misuse(probe, call, literalWords(fixed));
        if (further != null) {
            return new Failure.Validation(message + "; " + further.message(), further.suggestion(), further.command(),
                    further.qualified(), further.version(), further.owners());
        }
        if (call.coordinate() == null) {
            return null;
        }
        String command = call.paged(accessorMoved(call).command()).text();
        return new Failure.Validation(message, "Write " + noun + " in its place.", command);
    }

    private static int literalWords(List<String> argv) {
        int end = argv.indexOf(Command.END_OF_OPTIONS);
        return end < 0 ? 0 : argv.size() - end - 1;
    }

    // The index of the first word that is neither a flag nor a flag's value, or -1.
    private static int firstWord(List<String> argv, Set<String> takesValue) {
        for (int i = 0; i < argv.size(); i++) {
            String token = argv.get(i);
            if (Command.END_OF_OPTIONS.equals(token)) {
                return i + 1 < argv.size() ? i + 1 : -1;
            }
            if (!token.startsWith(Command.FLAG_START)) {
                return i;
            }
            if (takesValue.contains(token)) {
                i++;
            }
        }
        return -1;
    }

    private static int searchCentral(Invocation call, Streams streams, HttpOptions http, boolean json,
            UsageErrors usage) {
        String query = query(call);
        Failure.Validation invalid = SearchCentral.misuse(query, call.module(), call.filter());
        if (invalid == null) {
            invalid = pageBeforeFirst(call.page(), SearchCentral.command(query));
        }
        if (invalid != null) {
            return usage.error(invalid);
        }
        Result<DiscoverResult> found = SearchCentral.render(query, call.page(), http.withRefresh(call.refresh()));
        if (!found.isOk()) {
            return usage.report(found.failure());
        }
        TextRenderer.Context where = new TextRenderer.Context(null, null, List.of(Command.SEARCH, query), null, null,
                null);
        streams.out().accept((json ? JsonRenderer.render(found.value())
                : TextRenderer.render(found.value(), where)) + "\n");
        return Failure.ANSWERED;
    }

    private static int lookup(Invocation call, Streams streams, HttpOptions http, String projectDir, boolean json,
            UsageErrors usage) {
        Result<Coordinate> coordinate = Coordinate.parse(call.coordinate(),
                corrected -> call.paged(call.command(new Command.Target(corrected, call.module()))).text());
        if (!coordinate.isOk()) {
            return usage.report(coordinate.failure());
        }
        QualifiedName qualified = coordinate.value().qualified();
        Version version = coordinate.value().version();
        Invocation pinned = call.at(coordinate.value());

        Failure.Validation argumentError = validate(pinned);
        if (argumentError != null) {
            return usage.error(argumentError);
        }

        // `--refresh` is only known once arguments are parsed, so the injected options are rebuilt here.
        HttpOptions resolved = http.withRefresh(call.refresh());
        Loader.LoadOptions options = new Loader.LoadOptions(
                resolved, projectDir, List.of(CentralRepository.INSTANCE), call.module(), version);
        Result<LoadedPackage> loaded = Loader.loadPackage(qualified, options);
        if (!loaded.isOk()) {
            return usage.report(loaded.failure().retargeted(pinned::retargeted));
        }
        String warning = loaded.value().warning();
        if (warning != null) {
            streams.errorOut().accept(new Warning(Warning.STALE_CACHE, warning).describe(json) + "\n");
        }

        Failure meant = memberMeant(loaded.value(), call);
        if (meant != null) {
            return usage.report(readAt(meant, loaded.value()));
        }
        Result<DiscoverResult> answer = answer(loaded.value(), call);
        if (!answer.isOk()) {
            return usage.report(readAt(answer.failure(), loaded.value()));
        }
        if (call.page() != Command.FIRST_PAGE && answer.value().paging() == null) {
            return usage.report(readAt(notPaged(pinned), loaded.value()));
        }
        String read = loaded.value().version().text();
        TextRenderer.Context where = new TextRenderer.Context(qualified.qualified(), call.module(), call.trail(),
                call.filter(), Version.textOf(version), read);
        JsonRenderer.Where about = new JsonRenderer.Where(qualified.qualified(), read, call.module());
        streams.out().accept((json ? JsonRenderer.render(answer.value(), about)
                : TextRenderer.render(answer.value(), where)) + "\n");
        return Failure.ANSWERED;
    }

    // More names than a method or a resource takes, read against the package: the member command they meant, one
    // per container when several declare it; a path then a word that is not its accessor, the miss that word is as
    // --accessor; else what to write instead.
    private static Failure memberMeant(LoadedPackage loaded, Invocation call) {
        if (!MEMBER_NOUNS.contains(call.noun()) || call.names().size() <= MAX_NAMES) {
            return null;
        }
        boolean resource = Command.RESOURCE.equals(call.noun());
        String message = tooMany(call.noun(), call.names().size());
        List<Command> meant = Containers.memberMeant(loaded,
                resource ? Containers.Ask.RESOURCE : Containers.Ask.METHOD, call.owner(), call.names());
        if (meant.isEmpty()) {
            return accessorMiss(loaded, call, message).orElse(new Failure.Validation(message, RESOURCE_FORM));
        }
        List<Command> fixed = meant.stream()
                .map(command -> command.accessor() != null || call.accessor() == null
                        ? command
                        : command.accessedBy(call.accessor()))
                .map(command -> call.paged(command.filtered(call.filter())))
                .toList();
        if (fixed.size() > 1) {
            List<Failure.Match> rows = fixed.stream()
                    .map(command -> new Failure.Match(command.owner().flag() + " " + command.owner().name(),
                            command.text()))
                    .toList();
            return new Failure.Validation(message, "Put the accessor in " + Command.ACCESSOR_FLAG
                    + ", and name the container with its flag.", rows);
        }
        boolean pathFirst = meant.getFirst().names().equals(List.of(PathTree.plainSelector(call.names().get(0))));
        String suggestion = resource && pathFirst
                ? "Put the accessor in " + Command.ACCESSOR_FLAG + "."
                : "Name the container with its flag and the member alone.";
        return new Failure.Validation(message, suggestion, fixed.getFirst().text());
    }

    // `resource <path> <word>` whose word the path does not declare: the miss of `--accessor <word>`, which lists
    // the path's accessors, or the paths near it when the path itself misses.
    private static Optional<Failure> accessorMiss(LoadedPackage loaded, Invocation call, String message) {
        if (!Command.RESOURCE.equals(call.noun()) || call.accessor() != null
                || call.names().size() != MAX_NAMES + 1) {
            return Optional.empty();
        }
        Invocation asked = call.withNames(call.names().subList(0, MAX_NAMES)).withAccessor(call.names().getLast());
        Result<DiscoverResult> answer = answerFrom(loaded, asked);
        if (answer.isOk() || !(answer.failure() instanceof Failure.SymbolNotFound miss)) {
            return Optional.empty();
        }
        return Optional.of(new Failure.SymbolNotFound(miss.qualified(), miss.version(), miss.module(),
                miss.requested(), message + "; " + miss.message(), RESOURCE_FORM + " " + miss.suggestion(),
                miss.command(), miss.candidates()));
    }

    private static Result<DiscoverResult> answer(LoadedPackage loaded, Invocation call) {
        Result<DiscoverResult> plain = answerFrom(loaded, call);
        if (!plain.isOk() || !loaded.bound().settles(() -> JsonRenderer.render(plain.value()))) {
            return plain;
        }
        LoadedPackage settled = loaded.withSource();
        return settled.library() == loaded.library() ? plain : answerFrom(settled, call);
    }

    /**
     * What {@code argv} — a noun, its names and its flags, no package — answers of {@code loaded}, parsed and
     * dispatched as {@link #run} does it, without fetching anything. Lets a test ask a view the way a caller does.
     *
     * @throws IllegalArgumentException when {@code argv} does not parse to a noun
     */
    public static Result<DiscoverResult> answerOf(LoadedPackage loaded, List<String> argv) {
        Commands.Grammar grammar = Commands.Grammar.create();
        CommandLine.ParseResult parsed;
        try {
            parsed = grammar.line().parseArgs(argv.toArray(new String[0]));
        } catch (CommandLine.ParameterException cause) {
            throw new IllegalArgumentException(cause.getMessage(), cause);
        }
        if (!parsed.hasSubcommand()) {
            throw new IllegalArgumentException("No noun in " + argv);
        }
        String noun = parsed.subcommand().commandSpec().name();
        return answerFrom(loaded, Invocation.of(grammar.root(), noun, grammar.nouns().get(noun)));
    }

    private static Result<DiscoverResult> answerFrom(LoadedPackage loaded, Invocation call) {
        String name = call.names().isEmpty() ? null : call.names().get(0);
        String filter = call.filter();
        int page = call.page();
        return switch (call.noun()) {
            case Command.PACKAGE -> filter == null
                    ? Result.ok(Overview.of(loaded))
                    : Search.render(loaded, new Search.Options(filter, page));
            case Command.SEARCH -> Search.render(loaded, new Search.Options(name, page));
            case Command.README -> Readme.render(loaded, new Readme.Options(name, filter, page));
            case Command.TYPE -> Types.render(loaded, new Types.Options(call.names(), filter, page));
            case Command.FUNCTION -> Containers.render(loaded, Surface.Scope.MODULE,
                    Containers.Options.function(name, filter, page));
            case Command.METHOD -> Containers.render(loaded, call.ownerScope(),
                    Containers.Options.method(call.ownerName(), name, filter, page));
            case Command.RESOURCE -> Containers.render(loaded, call.ownerScope(),
                    Containers.Options.resource(call.ownerName(), name, call.accessor(), filter, page));
            default -> Containers.render(loaded, CONTAINER_NOUNS.get(call.noun()),
                    Containers.Options.container(name, filter, page));
        };
    }

    private static Failure readAt(Failure failure, LoadedPackage loaded) {
        String qualified = loaded.qualified().qualified();
        String version = loaded.version().text();
        return switch (failure) {
            case Failure.Validation validation -> validation.about(qualified, version);
            case Failure.PageNotFound missing -> missing.about(qualified, version);
            default -> failure;
        };
    }

    private static Failure.Validation notPaged(Invocation call) {
        String command = call.command().text();
        return new Failure.Validation("this answer has only one page, so --page does not apply", "Drop --page.",
                command);
    }

    /**
     * The run's output mode: a valid {@code --output-format} in argv, else JSON unless stdout is a terminal. Read off
     * argv, not the parsed flag, so a parse failure is still written in the mode the caller asked for.
     */
    public static boolean jsonOutput(List<String> argv, boolean interactive) {
        return jsonOutput(argv, interactive, Commands.Grammar.create());
    }

    // Every flag spelling that takes a value, at the root or under any noun.
    private static Set<String> valueOptions(Commands.Grammar grammar) {
        return optionNames(grammar, option -> option.arity().min() > 0);
    }

    // Every spelling of the options `kept` keeps, at the root or under any noun.
    private static Set<String> optionNames(Commands.Grammar grammar, Predicate<CommandLine.Model.OptionSpec> kept) {
        return Stream.concat(Stream.of(grammar.line()), grammar.line().getSubcommands().values().stream())
                .flatMap(line -> line.getCommandSpec().options().stream())
                .filter(kept)
                .flatMap(option -> Arrays.stream(option.names()))
                .collect(Collectors.toSet());
    }

    private static boolean jsonOutput(List<String> argv, boolean interactive, Commands.Grammar grammar) {
        Set<String> takesValue = valueOptions(grammar);
        for (int i = 0; i < argv.size(); i++) {
            String token = argv.get(i);
            String value = null;
            if (token.startsWith(Commands.OUTPUT_FLAG + VALUE_SEPARATOR)) {
                value = token.substring(Commands.OUTPUT_FLAG.length() + 1);
            } else if (takesValue.contains(token) && i + 1 < argv.size()) {
                i++;
                value = token.equals(Commands.OUTPUT_FLAG) ? argv.get(i) : null;
            }
            if (Commands.JSON_OUTPUT.equals(value) || Commands.TEXT_OUTPUT.equals(value)) {
                return Commands.JSON_OUTPUT.equals(value);
            }
        }
        return !interactive;
    }

    private static Failure.Validation validate(Invocation call) {
        if (call.filter() != null && call.filter().isBlank()) {
            return new Failure.Validation("the --filter option needs a keyword",
                    "Write --filter <keyword>, or drop it to list everything.");
        }
        Failure.Validation page = pageBeforeFirst(call.page(), call.command().text());
        if (page != null) {
            return page;
        }
        Failure.Validation version = rejectVersionArguments(call);
        if (version != null) {
            return version;
        }
        if (Command.TYPE.equals(call.noun())) {
            return Types.misuse(call.command(Command.TYPE),
                    new Types.Options(call.names(), call.filter(), call.page()));
        }
        if (Command.SEARCH.equals(call.noun())) {
            return Search.misuse(call.command(Command.SEARCH), query(call), call.filter());
        }
        return null;
    }

    private static String query(Invocation call) {
        return call.names().isEmpty() ? null : call.names().get(0);
    }

    private static Failure.Validation pageBeforeFirst(int page, String firstPage) {
        if (page >= Command.FIRST_PAGE) {
            return null;
        }
        return new Failure.Validation("page " + page + " is out of range: pages are numbered from 1",
                "Drop --page for the first page.", firstPage);
    }

    private static Failure.Validation writtenVersion(String message, Invocation call, QualifiedName qualified,
            String version) {
        String command = call.paged(call.at(new Coordinate(qualified, Version.parse(version).value())).command())
                .text();
        return new Failure.Validation(message, WRITE_VERSION_AFTER, command);
    }

    private static Failure.Validation rejectInvalidOutput(String output) {
        if (output == null || Commands.JSON_OUTPUT.equals(output) || Commands.TEXT_OUTPUT.equals(output)) {
            return null;
        }
        return new Failure.Validation(
                "'" + output + "' is not a valid " + Commands.OUTPUT_FLAG + " value",
                "Pass " + Commands.OUTPUT_FLAG + " " + Commands.JSON_OUTPUT + " or " + Commands.OUTPUT_FLAG + " "
                        + Commands.TEXT_OUTPUT + ".");
    }

    // Checked locally, before anything is fetched: a module name Ballerina itself would reject names nothing.
    private static Failure.Validation rejectInvalidModule(String module) {
        if (module == null || !module.isEmpty() && ProjectUtils.validateModuleName(module)) {
            return null;
        }
        return new Failure.Validation("'" + module + "' is not a valid " + Command.MODULE_FLAG + " value",
                "Pass a submodule's name, e.g. " + Command.MODULE_FLAG + " httpscerr.");
    }

    // The noun the command line names, read before it is parsed, or null.
    private static String typedNoun(Commands.Grammar grammar, List<String> argv) {
        int at = firstWord(argv, valueOptions(grammar));
        return at >= 0 && Command.NOUNS.contains(argv.get(at)) ? argv.get(at) : null;
    }

    // A switch written with a value, `--refresh=true`: a switch takes none. Read off argv, since picocli accepts
    // a boolean's own spelling as one.
    private static Failure.Validation valuedSwitch(Commands.Grammar grammar, List<String> argv) {
        Set<String> switches = optionNames(grammar, option -> option.arity().max() == 0);
        for (int i = 0; i < argv.size() && !Command.END_OF_OPTIONS.equals(argv.get(i)); i++) {
            String token = argv.get(i);
            int equals = token.indexOf('=');
            if (token.startsWith(Command.FLAG_START) && equals > 0 && switches.contains(token.substring(0, equals))) {
                String name = token.substring(0, equals);
                return new Failure.Validation(name + " takes no value", "Write " + name + " without a value.",
                        typedCommand(argv, i, name));
            }
        }
        return null;
    }

    // The short form --module once had: the same command with the long form, since the flag was this tool's own.
    private static Failure.Validation retiredModuleShort(Commands.Grammar grammar, List<String> argv) {
        Set<String> takesValue = valueOptions(grammar);
        for (int i = 0; i < argv.size() && !Command.END_OF_OPTIONS.equals(argv.get(i)); i++) {
            String token = argv.get(i);
            if (takesValue.contains(token)) {
                i++;
            } else if (RETIRED_MODULE_SHORT.equals(token)) {
                return new Failure.Validation("unknown option '" + token + "'; " + Command.MODULE_FLAG
                        + " has no short form", "Write " + Command.MODULE_FLAG + " in its place.",
                        typedCommand(argv, i, Command.MODULE_FLAG));
            }
        }
        return null;
    }

    // The command as typed, with the word at `at` replaced.
    private static String typedCommand(List<String> argv, int at, String replacement) {
        List<String> words = new ArrayList<>(argv);
        words.set(at, replacement);
        return Command.PREFIX + words.stream().map(Texts::shellWord).collect(Collectors.joining(" "));
    }

    // A complete version typed as a name: it belongs after the package.
    private static Failure.Validation rejectVersionArguments(Invocation call) {
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
        return writtenVersion("'" + misplaced + "' looks like a version", call.withNames(others),
                coordinate.value().qualified(), misplaced);
    }

    // The failure a parse error is, or null when picocli's own first line says it best.
    private static Failure.Validation describeParseError(CommandLine.ParameterException cause) {
        if (cause instanceof CommandLine.UnmatchedArgumentException unmatched) {
            List<String> tokens = unmatched.getUnmatched();
            String noun = cause.getCommandLine().getCommandSpec().parent() == null
                    ? null
                    : cause.getCommandLine().getCommandSpec().name();
            return unknownFlag(tokens.isEmpty() ? "" : tokens.get(0), noun);
        }
        if (cause instanceof CommandLine.OverwrittenOptionException overwritten
                && overwritten.getOverwritten() instanceof CommandLine.Model.OptionSpec option) {
            return new Failure.Validation(
                    option.longestName() + " is given more than once", "Pass " + option.longestName() + " once.");
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
            return new Failure.Validation(
                    "'" + cause.getValue() + "' is not a valid " + option.longestName() + " value",
                    "Write " + option.longestName() + " " + option.paramLabel() + ".");
        }
        return null;
    }

    private static Failure.Validation missingValue(CommandLine.Model.OptionSpec option) {
        String name = option.longestName();
        String label = option.paramLabel();
        return new Failure.Validation(
                "the " + name + " option needs a value",
                "Write " + name + " " + label + " or " + name + VALUE_SEPARATOR + label + ".");
    }

    private static String firstLine(String message) {
        String line = message == null || message.isEmpty() ? INVALID_ARGUMENTS : message.split("\n", -1)[0];
        String bare = line.endsWith(".") ? line.substring(0, line.length() - 1) : line;
        return bare.isEmpty() ? INVALID_ARGUMENTS : Character.toLowerCase(bare.charAt(0)) + bare.substring(1);
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

        /** A failure in the run's output mode: a usage error as {@link #error} writes it, any other as it is. */
        int report(Failure failure) {
            if (Failure.USAGE_KIND.equals(failure.coreKind())) {
                return error(failure);
            }
            streams.errorOut().accept(failure.describe(json) + "\n");
            return failure.exitCode();
        }

        int error(Failure failure) {
            if (json) {
                streams.errorOut().accept(failure.describe() + "\n");
                return failure.exitCode();
            }
            boolean pointsToHelp = failure.suggestion().equals(helpSuggestion(noun));
            streams.errorOut().accept(failure.describeText(!pointsToHelp) + "\n"
                    + UsageRenderer.usageLine(grammar, noun) + "\n"
                    + "Try " + Failure.quoted(helpCommand(noun)) + " for more information.\n");
            return failure.exitCode();
        }

        int helpError(String message) {
            return error(new Failure.Validation(message, helpSuggestion(noun)));
        }
    }
}
