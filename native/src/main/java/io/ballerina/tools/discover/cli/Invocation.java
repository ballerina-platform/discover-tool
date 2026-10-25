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
import io.ballerina.tools.discover.symbols.PathTree;
import io.ballerina.tools.discover.symbols.Surface;

import java.util.ArrayList;
import java.util.List;

/**
 * One command line as the caller typed it, the root's flags merged under the noun's: what is asked, and the
 * command that asks it again — which every failure's fix and every page turn is built on.
 *
 * @param noun the noun, or {@code null} when none was given
 * @param names the names after the noun, as typed
 * @param rest what was typed where the noun goes but is not one: an old package-first command, or a mistyped noun
 * @param pkg the {@code -p} value, or {@code null}
 * @param coordinate the package as it is read: {@code package}'s name, or {@code pkg}
 * @param owners the container flags given, in the order they are declared
 * @param output the {@code --output} value, or {@code null}
 * @param filter the {@code --filter} value, or {@code null}
 * @param page the {@code --page} value
 * @param module the {@code --module} value, or {@code null}
 * @param version the {@code --version} value, which is never valid, or {@code null}
 * @param refresh whether {@code --refresh} was given
 * @since 0.1.0
 */
record Invocation(String noun, List<String> names, List<String> rest, String pkg, String coordinate,
        List<Command.Owner> owners, String output, String filter, int page, String module, String version,
        boolean refresh) {

    static Invocation of(Commands.Root root, String noun, Commands.Noun values) {
        Commands.Flags top = root.flags;
        Commands.Flags own = values == null ? top : values.flags;
        List<String> names = values == null ? List.of() : List.copyOf(values.names());
        String pkg = values == null || values.pkg() == null ? root.pkg.pkg : values.pkg();
        String coordinate = Command.PACKAGE.equals(noun) ? (names.isEmpty() ? null : names.get(0)) : pkg;
        return new Invocation(noun, names, root.rest == null ? List.of() : List.copyOf(root.rest), pkg, coordinate,
                owners(root, values),
                either(own.output, top.output), either(own.filter, top.filter),
                own.page != Command.FIRST_PAGE ? own.page : top.page, either(own.module, top.module),
                either(own.version, top.version), own.refresh || top.refresh);
    }

    /** Whether the line is one package coordinate and nothing else: the package overview, written without its noun. */
    boolean asBareCoordinate() {
        return noun == null && pkg == null && rest.size() == 1 && Cli.isCoordinate(rest.get(0));
    }

    /** This bare coordinate as the {@code package} command it stands for. */
    Invocation asPackageOverview() {
        String named = rest.get(0);
        return new Invocation(Command.PACKAGE, List.of(named), List.of(), pkg, named, owners, output, filter, page,
                module, version, refresh);
    }

    // The container flags typed before the noun, then after it.
    private static List<Command.Owner> owners(Commands.Root root, Commands.Noun values) {
        List<Command.Owner> owners = new ArrayList<>(root.owners.given());
        if (values != null) {
            owners.addAll(values.owners());
        }
        return List.copyOf(owners);
    }

    private static String either(String own, String top) {
        return own != null ? own : top;
    }

    /** A flag given both before the noun and after it, or {@code null}: neither may silently win. */
    static String repeatedFlag(Commands.Root root, Commands.Noun values) {
        if (values == null) {
            return null;
        }
        Commands.Flags top = root.flags;
        Commands.Flags own = values.flags;
        if (root.pkg.pkg != null && values.pkg() != null) {
            return Command.PACKAGE_LONG_FLAG;
        }
        if (!root.owners.given().isEmpty() && !values.owners().isEmpty()) {
            return root.owners.given().get(0).flag();
        }
        if (top.output != null && own.output != null) {
            return Commands.OUTPUT_FLAG;
        }
        if (top.filter != null && own.filter != null) {
            return Command.FILTER_FLAG;
        }
        if (top.page != Command.FIRST_PAGE && own.page != Command.FIRST_PAGE) {
            return Command.PAGE_FLAG;
        }
        if (top.module != null && own.module != null) {
            return Command.MODULE_FLAG;
        }
        return top.refresh && own.refresh ? Commands.REFRESH_FLAG : null;
    }

    /** The names as a printed command spells them: a name or path without Ballerina's escapes. */
    List<String> echoed() {
        if (Command.README.equals(noun) || Command.SEARCH.equals(noun)) {
            return names;
        }
        return names.stream().map(PathTree::plainSelector).toList();
    }

    /** The noun and names a text answer's header echoes. */
    List<String> trail() {
        if (Command.PACKAGE.equals(noun)) {
            return List.of();
        }
        List<String> trail = new ArrayList<>();
        trail.add(noun);
        trail.addAll(echoed());
        return trail;
    }

    /** This command again, with the package as read. */
    Command command() {
        return command(new Command.Target(coordinate, module));
    }

    /** This command again, asked of {@code target}. */
    Command command(Command.Target target) {
        if (Command.PACKAGE.equals(noun)) {
            return Command.overview(target).filtered(filter);
        }
        Command command = Command.of(noun, echoed()).in(target).filtered(filter);
        return owner() == null ? command : command.ownedBy(owner());
    }

    /** The bare {@code noun}, asked of this command's package. */
    Command command(String other) {
        return Command.of(other).in(new Command.Target(coordinate, module));
    }

    /** {@code fixed} asked of this command's package and narrowed by its filter, as a whole command line. */
    Command retarget(Command fixed) {
        return fixed.in(new Command.Target(coordinate, module)).filtered(filter);
    }

    /** A command a lower layer printed for another package or module, made this command asked of that one. */
    Command retargeted(Command overview) {
        return paged(command(overview.target()));
    }

    /** {@code command} on this command's page. */
    Command paged(Command command) {
        return command.paged(page);
    }

    /** This command with the package written as {@code read}. */
    Invocation at(Coordinate read) {
        String text = read.target(null).pkg();
        List<String> written = names;
        if (Command.PACKAGE.equals(noun) && !names.isEmpty()) {
            written = new ArrayList<>(names);
            written.set(0, text);
        }
        return new Invocation(noun, List.copyOf(written), rest, pkg == null ? null : text, text, owners,
                output, filter, page, module, version, refresh);
    }

    Invocation withNames(List<String> others) {
        return new Invocation(noun, List.copyOf(others), rest, pkg, coordinate, owners, output, filter, page,
                module, version, refresh);
    }

    /** The container flag given, or {@code null}; {@link Cli} rejects more than one. */
    Command.Owner owner() {
        return owners.isEmpty() ? null : owners.get(0);
    }

    /** This command with only {@code kept} of its container flags. */
    Invocation ownedBy(Command.Owner kept) {
        return new Invocation(noun, names, rest, pkg, coordinate, List.of(kept), output, filter, page, module,
                version, refresh);
    }

    /** The scope the container flag names, or {@code null} without one. */
    Surface.Scope ownerScope() {
        return owner() == null ? null : Surface.Scope.ofCommandNoun(owner().kind()).orElse(null);
    }

    String ownerName() {
        return owner() == null ? null : owner().name();
    }
}
