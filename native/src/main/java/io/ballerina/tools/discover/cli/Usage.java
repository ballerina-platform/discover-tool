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

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The PROSE of the usage text: everything a reader cannot derive from the grammar, laid out as the Ballerina CLI
 * tool guide asks — a one-line description, {@code Usage:}, the commands, {@code Options:}, then commented
 * {@code Examples:}, no line over {@value UsageRenderer#WIDTH} columns.
 *
 * <p>It teaches only "what can I ask, and how". The rules a caller carries INTO a lookup (a truncated listing
 * means narrow further, a failure's {@code kind} is the branch to read) are the agent's standing instructions,
 * not this command's usage.
 *
 * @since 0.1.0
 */
final class Usage {

    /** What follows {@code bal discover} in the root's synopsis. */
    static final String ROOT_LABEL = "<noun> [<name> ...]";

    private static final String KAFKA = " -p ballerinax/kafka";

    private static final String GITHUB = " -p ballerinax/github";

    private static final String PRODUCER = KAFKA + " --client Producer";

    private static final String GITHUB_CLIENT = GITHUB + " --client Client";

    /** Where the root's command descriptions start: past the longest noun and its names. */
    private static final int COMMAND_COLUMN = 36;

    /**
     * One example: what it shows, then the command.
     *
     * @param comment what it shows, one line
     * @param command the command that shows it
     */
    private record Example(String comment, String command) { }

    /**
     * What one noun reads.
     *
     * @param label its names in the synopsis, with a leading space
     * @param summary one line, for the root's list of commands
     * @param description its own help's one line
     * @param examples what its own help shows
     */
    private record Noun(String label, String summary, String description, List<Example> examples) { }

    private static final Map<String, Noun> NOUNS = Map.of(
            Command.PACKAGE, new Noun(" " + Commands.PACKAGE_LABEL, "a package's overview",
                    "Show a package's overview: what it declares, each with the command that opens it",
                    List.of(new Example("The version your project locks, else Central's latest",
                                    example(Command.PACKAGE, "ballerinax/kafka")),
                            new Example("Every name in the package that holds a keyword",
                                    example(Command.PACKAGE, "ballerinax/kafka --filter send")),
                            new Example("One version", example(Command.PACKAGE, "ballerina/http:2.15.7")))),
            Command.SEARCH, new Noun(" <keyword> ...", "search Central, or one package with -p",
                    "Search Central's packages and symbols, or every name in one package with -p",
                    List.of(new Example("Central's packages and symbols that match every keyword, by rank",
                                    example(Command.SEARCH, "kafka send")),
                            new Example("Names in one package that hold every keyword, in any case",
                                    example(Command.SEARCH, "send" + KAFKA)))),
            Command.CLIENT, container("client", "clients", "Producer"),
            Command.SERVICE, new Noun(" [<name>]", "the service types, or one and its methods",
                    "Show the service types and their listeners, or what a service of one implements",
                    List.of(new Example("Every service type and the listener it attaches to",
                                    example(Command.SERVICE, KAFKA.strip())),
                            new Example("The methods a service of it implements, and its listener",
                                    example(Command.SERVICE, "Service" + KAFKA)))),
            Command.CLASS, container("class", "classes", "AvroSerializer"),
            Command.FUNCTION, new Noun(" [<name>]", "the functions, or one function's signature",
                    "Show the module's functions, or one function's signature",
                    List.of(new Example("Every function", example(Command.FUNCTION, "-p ballerina/log")),
                            new Example("Its signature, the types it uses and the readme sections that call it",
                                    example(Command.FUNCTION, "printInfo -p ballerina/log")))),
            Command.TYPE, new Noun(" [<name>]", "every type, or one declaration",
                    "Show every type the module declares, or one declaration and the types it uses",
                    List.of(new Example("Records, enums, errors, constants, listeners, classes and the rest",
                                    example(Command.TYPE, GITHUB.strip())),
                            new Example("One declaration; an enum member opens its enum",
                                    example(Command.TYPE, "ConnectionConfig" + GITHUB)))),
            Command.METHOD, new Noun(" [<name>]", "a container's methods, or one's signature",
                    "Show a container's methods, or one method's signature and how to call it",
                    List.of(new Example("The remote and plain methods of one container",
                                    example(Command.METHOD, PRODUCER.strip())),
                            new Example("Its signature, its types, how to create the client, the readme calls",
                                    example(Command.METHOD, "send" + PRODUCER)),
                            new Example("The constructor", example(Command.METHOD, "init" + PRODUCER)),
                            new Example("No container flag: the one container that declares it",
                                    example(Command.METHOD, "send" + KAFKA)))),
            Command.RESOURCE, new Noun(" [<path> [<accessor>]]", "resource paths, or one method's signature",
                    "Show a container's resource paths, or one resource method's signature",
                    List.of(new Example("The resource paths, grouped by segment when there are many",
                                    example(Command.RESOURCE, GITHUB_CLIENT.strip())),
                            new Example("The paths under one; a path parameter is written :name",
                                    example(Command.RESOURCE, "gists" + GITHUB_CLIENT)),
                            new Example("One resource method's signature: the path, then its accessor",
                                    example(Command.RESOURCE, "gists/public get" + GITHUB_CLIENT)))),
            Command.README, new Noun(" [<n>|\"<title>\"]", "the readme, or one section of it",
                    "Show the readme, or one section of it by number or title",
                    List.of(new Example("The readme, or its sections when it is long",
                                    example(Command.README, KAFKA.strip())),
                            new Example("One section, by its number or its title in quotes",
                                    example(Command.README, "7" + KAFKA)))));

    private static final String DESCRIPTION =
            "Read a Ballerina package's API off Central; trust it over memory or the web";

    /** What the root's synopsis cannot say, under its list of commands. */
    private static final List<String> NOTES = List.of(
            "Every noun but package needs -p; search takes it only to search one package.",
            Command.PREFIX + "<org>/<name> is short for " + Command.PREFIX + Command.PACKAGE + " <org>/<name>.",
            "Options may come before or after the noun.",
            "Run 'bal discover <noun> --help' for a noun's options and examples.");

    /** The options a noun parses but turns down, so its help does not offer them. */
    private static final Map<String, Set<String>> NOT_TAKEN = Map.of(Command.SEARCH, Set.of(Command.FILTER_FLAG));

    private static final List<Example> SESSION = List.of(
            new Example("Find a package or a symbol on Central", example(Command.SEARCH, "kafka send")),
            new Example("What a package offers", example(Command.PACKAGE, "ballerinax/kafka")),
            new Example("A method's signature, its container named", example(Command.METHOD, "send" + PRODUCER)),
            new Example("A resource method's signature: the path, then its accessor",
                    example(Command.RESOURCE, "gists/public get" + GITHUB_CLIENT)),
            new Example("A record, enum or error", example(Command.TYPE, "ConnectionConfig" + GITHUB)),
            new Example("One version; without one, ./Dependencies.toml's lock, else the latest",
                    example(Command.CLIENT, "Client -p ballerina/http:2.15.7")));

    private static final String OWNER_ROW = "name the container of a method or resource";

    private static final String OWN_OWNER_ROW = "name the container, unless only one declares it";

    private Usage() {
    }

    /** The whole usage text: what the tool is, the commands, the options, and how to walk it. */
    static String root() {
        Commands.Grammar grammar = Commands.Grammar.create();
        StringBuilder notes = new StringBuilder();
        NOTES.forEach(note -> notes.append(UsageRenderer.INDENT).append(note).append("\n"));
        return DESCRIPTION + "\n\n"
                + UsageRenderer.synopsis(grammar, null)
                + UsageRenderer.alternativeSynopsis(grammar, Command.PACKAGE)
                + "\nCommands:\n" + UsageRenderer.table(Command.NOUNS.stream()
                        .map(noun -> new UsageRenderer.Row(noun + label(noun), NOUNS.get(noun).summary()))
                        .toList(), COMMAND_COLUMN)
                + notes
                + "\nOptions:\n" + UsageRenderer.table(UsageRenderer.optionRows(grammar, null, OWNER_ROW, Set.of()),
                        UsageRenderer.OPTION_COLUMN)
                + examples(SESSION);
    }

    /** One noun's usage text: what it reads, its synopsis, every option it takes, and examples. */
    static String noun(String noun) {
        Commands.Grammar grammar = Commands.Grammar.create();
        Noun about = NOUNS.get(noun);
        List<UsageRenderer.Row> options = UsageRenderer.optionRows(grammar, noun, OWN_OWNER_ROW,
                NOT_TAKEN.getOrDefault(noun, Set.of()));
        return about.description() + "\n\n"
                + UsageRenderer.synopsis(grammar, noun)
                + "\nOptions:\n" + UsageRenderer.table(options, UsageRenderer.OPTION_COLUMN)
                + examples(about.examples());
    }

    private static String examples(List<Example> examples) {
        StringBuilder text = new StringBuilder("\nExamples:\n");
        for (Example example : examples) {
            text.append(UsageRenderer.INDENT).append("# ").append(example.comment()).append("\n")
                    .append(UsageRenderer.INDENT).append(example.command()).append("\n");
        }
        return text.toString();
    }

    /** {@code noun}'s names in a synopsis, with a leading space. */
    static String label(String noun) {
        return NOUNS.get(noun).label();
    }

    /**
     * The flags {@code noun}'s synopsis writes as required: the package, except where it is optional. The root's
     * synopsis is every noun's but {@code package}, which has a synopsis line of its own.
     */
    static Set<String> requiredFlags(String noun) {
        return Command.PACKAGE.equals(noun) || Command.SEARCH.equals(noun)
                ? Set.of()
                : Set.of(Command.PACKAGE_FLAG);
    }

    private static Noun container(String noun, String plural, String example) {
        return new Noun(" [<name>]", "the " + plural + ", or one " + noun + "'s members",
                "Show the package's " + plural + ", or one " + noun + "'s members, each with its command",
                List.of(new Example("Every " + noun + ", with its method counts", example(noun, KAFKA.strip())),
                        new Example("Its resource paths, remote methods and methods",
                                example(noun, example + KAFKA))));
    }

    private static String example(String noun, String rest) {
        return Command.PREFIX + noun + " " + rest;
    }
}
