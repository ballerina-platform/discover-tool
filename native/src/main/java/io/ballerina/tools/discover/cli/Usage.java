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
import io.ballerina.tools.discover.views.SearchCentral;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The PROSE of the usage text: everything a reader cannot derive from the grammar.
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

    private static final String EXAMPLE_PACKAGE = "ballerinax/kafka";

    private static final String EXAMPLE_GITHUB = "ballerinax/github";

    /**
     * What one noun reads.
     *
     * @param label its names in the synopsis, with a leading space, or empty for none
     * @param summary one line, for the root's list of nouns
     * @param description its own help's paragraph
     * @param examples commands its own help shows
     */
    private record Noun(String label, String summary, String description, List<String> examples) { }

    private static final Map<String, Noun> NOUNS = Map.of(
            Command.PACKAGE, new Noun(" " + Commands.PACKAGE_LABEL,
                    "A package's overview.",
                    "The package's overview: its clients, service types, classes and functions, its types and its "
                            + "readme, each with the command that opens it. The package is this noun's name, not a "
                            + "-p flag. With --filter, every name in the package that holds the keyword.",
                    List.of(example(Command.PACKAGE, EXAMPLE_PACKAGE), example(Command.PACKAGE,
                            "ballerina/http:2.15.7"))),
            Command.SEARCH, new Noun(" <keyword> ...",
                    "Central's packages and symbols, or with -p every name in one package.",
                    "Without -p, asks Central for the packages and the symbols that match every keyword, "
                            + SearchCentral.PACKAGES_PER_PAGE + " and " + SearchCentral.SYMBOLS_PER_PAGE
                            + " a page, ranked by Central. With -p, matches every name in that package, locally; "
                            + "each keyword is a case-insensitive part of the name.",
                    List.of(example(Command.SEARCH, "kafka send"), example(Command.SEARCH, "send -p "
                            + EXAMPLE_PACKAGE))),
            Command.CLIENT, container("client", "clients", "Producer"),
            Command.SERVICE, new Noun(" [<name>]",
                    "The service types, or one with what a service of it implements.",
                    "Without a name, the package's service types and the listener each attaches to. With one, "
                            + "the methods a service of it implements, and its listener.",
                    List.of(example(Command.SERVICE, "-p " + EXAMPLE_PACKAGE),
                            example(Command.SERVICE, "Service -p " + EXAMPLE_PACKAGE))),
            Command.CLASS, container("class", "classes", "AvroSerializer"),
            Command.FUNCTION, new Noun(" [<name>]",
                    "The module's functions, or one function's signature.",
                    "Without a name, the module's functions. With one, its signature, the types it uses and the "
                            + "readme sections that call it.",
                    List.of(example(Command.FUNCTION, "-p ballerina/log"),
                            example(Command.FUNCTION, "printInfo -p ballerina/log"))),
            Command.TYPE, new Noun(" [<name>]",
                    "Every type, or one declaration.",
                    "Without a name, every type the module declares: records, enums, errors, aliases, constants, "
                            + "annotations, listeners and every class, client and service type. With one, its "
                            + "declaration and the types it uses; an enum member opens its enum.",
                    List.of(example(Command.TYPE, "-p " + EXAMPLE_GITHUB),
                            example(Command.TYPE, "ConnectionConfig -p " + EXAMPLE_GITHUB))),
            Command.METHOD, new Noun(" [<name>]",
                    "A container's methods, or one method's signature.",
                    "Without a name, the remote and plain methods of the container --client, --class or "
                            + "--service names. With one, its signature, the types it uses, how to create its "
                            + "container and the readme sections that call it; init is the constructor. The "
                            + "container flag may be left out when exactly one container declares the method.",
                    List.of(example(Command.METHOD, "-p " + EXAMPLE_PACKAGE + " --client Producer"),
                            example(Command.METHOD, "send -p " + EXAMPLE_PACKAGE + " --client Producer"),
                            example(Command.METHOD, "send -p " + EXAMPLE_PACKAGE))),
            Command.RESOURCE, new Noun(" [<path> [<accessor>]]",
                    "A container's resource paths, or one resource method's signature.",
                    "Without a path, the resource paths of the container --client names, grouped by segment once "
                            + "there are more than fit. With a path, the paths under it; with its accessor too, "
                            + "that resource method's signature. A path parameter is written :name. The "
                            + "container flag may be left out when exactly one container declares the path.",
                    List.of(example(Command.RESOURCE, "-p " + EXAMPLE_GITHUB + " --client Client"),
                            example(Command.RESOURCE, "gists -p " + EXAMPLE_GITHUB + " --client Client"),
                            example(Command.RESOURCE, "gists/public get -p " + EXAMPLE_GITHUB
                                    + " --client Client"))),
            Command.README, new Noun(" [<n>|\"<title>\"]",
                    "The readme, or one section of it.",
                    "Without a section, the readme, or its sections when it is long. With a section's number or "
                            + "title, that section.",
                    List.of(example(Command.README, "-p " + EXAMPLE_PACKAGE),
                            example(Command.README, "7 -p " + EXAMPLE_PACKAGE))));

    private static final String INTRO =
            "Read a Ballerina package off Central. A signature from here is the source for what compiles; a "
                    + "readme, a web search and a remembered API are not.";

    private static final String NAMES =
            "A name means one thing; no name, a listing. Every noun but package takes the package with -p; "
                    + "search takes it only to search one package.";

    /**
     * The typical session, as commands.
     *
     * <p>Written out rather than generated: the VALUE of this block is the specific packages and the specific
     * name shapes, which no model of the grammar knows.
     */
    private static final String SESSION = """
            Typical session:

              1. Find a package or a symbol:  bal discover search kafka send
              2. What a package offers:       bal discover package ballerinax/kafka
              3. Find a name inside it:       bal discover search send -p ballerinax/kafka
              4. One call, fully addressed:   bal discover method send -p ballerinax/kafka --client Producer
                                              bal discover resource gists/public get -p ballerinax/github \\
                                                  --client Client
              5. Its container, left out:     bal discover method send -p ballerinax/kafka
              6. A record, enum or error:     bal discover type ConnectionConfig -p ballerinax/github
              7. One specific version:        bal discover client Client -p ballerina/http:2.15.7
            """;

    private static final String VERSION =
            "With no version after the package, the one your project's Dependencies.toml locks is read (found "
                    + "from the current directory up), else Central's latest.";

    private static final String EXIT_CODES = "Exit codes: 0 an answer, 1 a failure, 2 a usage error.";

    private static final String MORE = "Run `bal discover <noun> --help` for one noun's names, flags and examples.";

    private Usage() {
    }

    /** The whole usage text: what the tool is, what it can be asked, and how to walk it. */
    static String root() {
        Commands.Grammar grammar = Commands.Grammar.create();
        StringBuilder text = new StringBuilder()
                .append(UsageRenderer.synopsis(grammar, null))
                .append("\n").append(UsageRenderer.prose(INTRO, 0))
                .append("\nNouns:\n").append(UsageRenderer.table(Command.NOUNS.stream()
                        .map(noun -> new UsageRenderer.Row(noun + label(noun), NOUNS.get(noun).summary()))
                        .toList()))
                .append("\n").append(UsageRenderer.prose(NAMES, 0))
                .append("\n").append(SESSION)
                .append("\n").append(UsageRenderer.prose(VERSION, 0))
                .append(UsageRenderer.prose(EXIT_CODES, 0))
                .append(UsageRenderer.prose(MORE, 0));
        String flags = UsageRenderer.flagList(grammar, null);
        if (!flags.isEmpty()) {
            text.append("\n").append(flags);
        }
        return text.toString();
    }

    /** One noun's usage text: its synopsis, what it reads, examples, and its flags. */
    static String noun(String noun) {
        Commands.Grammar grammar = Commands.Grammar.create();
        Noun about = NOUNS.get(noun);
        StringBuilder text = new StringBuilder()
                .append(UsageRenderer.synopsis(grammar, noun))
                .append("\n").append(UsageRenderer.prose(about.description(), 0))
                .append("\nExamples:\n");
        about.examples().forEach(example -> text.append("  ").append(example).append("\n"));
        text.append("\n").append(UsageRenderer.prose(EXIT_CODES, 0))
                .append("\n").append(UsageRenderer.flagList(grammar, noun));
        return text.toString();
    }

    /** {@code noun}'s names in a synopsis, with a leading space, or empty when it takes none. */
    static String label(String noun) {
        return NOUNS.get(noun).label();
    }

    /** The flags {@code noun}'s synopsis writes as required: the package, except where it is optional. */
    static Set<String> requiredFlags(String noun) {
        return noun == null || Command.PACKAGE.equals(noun) || Command.SEARCH.equals(noun)
                ? Set.of()
                : Set.of(Command.PACKAGE_FLAG);
    }

    private static Noun container(String noun, String plural, String example) {
        return new Noun(" [<name>]",
                "The " + plural + ", or one " + noun + "'s members.",
                "Without a name, the package's " + plural + ", each with its method counts. With one, its "
                        + "members: resource paths, remote methods and methods, each with the command that opens "
                        + "it.",
                List.of(example(noun, "-p " + EXAMPLE_PACKAGE), example(noun, example + " -p " + EXAMPLE_PACKAGE)));
    }

    private static String example(String noun, String rest) {
        return Command.PREFIX + noun + " " + rest;
    }
}
