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
import io.ballerina.tools.discover.views.Containers;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The argument grammar, declared once: {@code bal discover <noun> [name ...] -p <org>/<name>[:<version>] [flags]},
 * one picocli subcommand per noun.
 *
 * <p>A noun says what comes back and its names say which one: a name means one thing, no name a listing. The
 * package is a flag, not a position, so every noun takes its names the same way; {@code package} alone takes the
 * coordinate as its name. {@code --module} applies uniformly across every noun including
 * {@code readme}: {@link Cli} reads it once before any noun is answered, since which module a coordinate resolves
 * to is a fact about the package lookup, not about any one noun.
 *
 * <p>The root takes the same flags and a free positional list only so the old package-first form
 * ({@code bal discover ballerinax/kafka client Producer}) and a mistyped noun parse, and {@link Cli} can answer
 * them with the command to run instead.
 *
 * @since 0.1.0
 */
final class Commands {

    private Commands() {
    }

    static final String VERSION_FLAG = "--version";
    static final String VERSION_SHORT = "-V";

    static final String HELP_FLAG = "--help";
    static final String HELP_SHORT = "-h";
    static final String OUTPUT_FLAG = "--output-format";
    static final String REFRESH_FLAG = "--refresh";

    static final String JSON_OUTPUT = "json";
    static final String TEXT_OUTPUT = "text";

    static final String PACKAGE_LABEL = "<org>/<name>[:<version>]";

    static final String OWNER_LABEL = "<name>";

    static final String PACKAGE_DESCRIPTION = "read this package, as bal pull names it";


    /**
     * @param line the root command line, every noun registered under it
     * @param root the root's values
     * @param nouns each noun's values, by noun
     */
    record Grammar(CommandLine line, Root root, Map<String, Noun> nouns) {

        static Grammar create() {
            Root root = new Root();
            CommandLine line = new CommandLine(root);
            Map<String, Noun> nouns = new LinkedHashMap<>();
            for (String name : Command.NOUNS) {
                Noun noun = switch (name) {
                    case Command.PACKAGE -> new PackageNoun();
                    case Command.METHOD, Command.RESOURCE -> new MemberNoun();
                    default -> new ScopedNoun();
                };
                nouns.put(name, noun);
                line.addSubcommand(name, noun);
            }
            return new Grammar(line, root, nouns);
        }

        /** The spec of {@code noun}, or the root's when {@code noun} is null. */
        CommandLine.Model.CommandSpec spec(String noun) {
            return noun == null ? line.getCommandSpec() : line.getSubcommands().get(noun).getCommandSpec();
        }
    }

    /** The flags every noun takes. */
    static final class Flags {

        @CommandLine.Option(names = {HELP_SHORT, HELP_FLAG}, usageHelp = true, description = "print this help")
        boolean help;

        @CommandLine.Option(names = OUTPUT_FLAG, paramLabel = JSON_OUTPUT + "|" + TEXT_OUTPUT,
                description = "set the format (default: text on a TTY, else json)")
        String output;

        @CommandLine.Option(names = Command.FILTER_FLAG, paramLabel = "<keyword>",
                description = "narrow a listing to the names that match a keyword")
        String filter;

        @CommandLine.Option(names = Command.PAGE_FLAG, paramLabel = "<n>", defaultValue = "1",
                description = "show page n, " + Containers.MAX_ENTRIES + " entries a page (default: 1)")
        int page;

        @CommandLine.Option(names = Command.MODULE_FLAG, paramLabel = "<name>",
                description = "read this submodule by name, e.g. httpscerr")
        String module;

        @CommandLine.Option(names = REFRESH_FLAG, description = "ignore the cached copy and fetch it again")
        boolean refresh;
    }

    /** The package a noun is asked of. */
    static final class Package {

        @CommandLine.Option(names = {Command.PACKAGE_FLAG, Command.PACKAGE_LONG_FLAG}, paramLabel = PACKAGE_LABEL,
                description = PACKAGE_DESCRIPTION)
        String pkg;
    }

    /** The container a method or resource is declared by. */
    static final class Owners {

        @CommandLine.Option(names = Command.CLIENT_FLAG, paramLabel = OWNER_LABEL,
                description = "name the client that declares it")
        String client;

        @CommandLine.Option(names = Command.CLASS_FLAG, paramLabel = OWNER_LABEL,
                description = "name the class that declares it")
        String classOwner;

        @CommandLine.Option(names = Command.SERVICE_FLAG, paramLabel = OWNER_LABEL,
                description = "name the service type that declares it")
        String service;

        List<Command.Owner> given() {
            List<Command.Owner> given = new ArrayList<>();
            if (client != null) {
                given.add(new Command.Owner(Command.CLIENT, client));
            }
            if (classOwner != null) {
                given.add(new Command.Owner(Command.CLASS, classOwner));
            }
            if (service != null) {
                given.add(new Command.Owner(Command.SERVICE, service));
            }
            return given;
        }
    }

    /**
     * What the caller typed before or instead of a noun. No minimum arity: picocli 4.0.1 lets a variable-arity
     * positional with a minimum consume an unrecognised flag as its value.
     */
    @CommandLine.Command(name = "discover")
    static final class Root {

        @CommandLine.Mixin
        Flags flags = new Flags();

        @CommandLine.Mixin
        Package pkg = new Package();

        @CommandLine.Mixin
        Owners owners = new Owners();

        @CommandLine.Option(names = {VERSION_SHORT, VERSION_FLAG}, versionHelp = true,
                description = "print the tool's version")
        boolean version;

        @CommandLine.Parameters(arity = "0..*", paramLabel = "<noun>")
        List<String> rest;
    }

    /** One noun's values: its flags and its names. */
    abstract static class Noun {

        @CommandLine.Mixin
        Flags flags = new Flags();

        @CommandLine.Parameters(arity = "0..*", paramLabel = "[<name> ...]")
        List<String> names;

        List<String> names() {
            return names == null ? List.of() : names;
        }

        /** The {@code -p} value, or {@code null} for a noun that takes none. */
        abstract String pkg();

        /** The container flags given. */
        List<Command.Owner> owners() {
            return List.of();
        }
    }

    @CommandLine.Command
    static final class PackageNoun extends Noun {

        // Hidden and never valid: declared only so the CLI can say the package is this noun's name.
        @CommandLine.Option(names = {Command.PACKAGE_FLAG, Command.PACKAGE_LONG_FLAG}, hidden = true,
                paramLabel = PACKAGE_LABEL)
        String pkg;

        @Override
        String pkg() {
            return pkg;
        }
    }

    @CommandLine.Command
    static class ScopedNoun extends Noun {

        @CommandLine.Mixin
        Package pkg = new Package();

        @Override
        String pkg() {
            return pkg.pkg;
        }
    }

    @CommandLine.Command
    static final class MemberNoun extends ScopedNoun {

        @CommandLine.Mixin
        Owners owners = new Owners();

        @Override
        List<Command.Owner> owners() {
            return owners.given();
        }
    }
}
