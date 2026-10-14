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
 * @param owner the container flag given, or {@code null}
 * @param owners how many container flags were given
 * @param output the {@code --output} value, or {@code null}
 * @param filter the {@code --filter} value, or {@code null}
 * @param page the {@code --page} value
 * @param module the {@code --module} value, or {@code null}
 * @param version the {@code --version} value, which is never valid, or {@code null}
 * @param refresh whether {@code --refresh} was given
 * @since 0.1.0
 */
record Invocation(String noun, List<String> names, List<String> rest, String pkg, String coordinate,
        Command.Owner owner, int owners, String output, String filter, int page, String module, String version,
        boolean refresh) {

    private static final int FIRST_PAGE = 1;

    static Invocation of(Commands.Root root, String noun, Commands.Noun values) {
        Commands.Flags top = root.flags;
        Commands.Flags own = values == null ? top : values.flags;
        List<String> names = values == null ? List.of() : List.copyOf(values.names());
        String pkg = values == null || values.pkg() == null ? root.pkg.pkg : values.pkg();
        String coordinate = Command.PACKAGE.equals(noun) ? (names.isEmpty() ? null : names.get(0)) : pkg;
        return new Invocation(noun, names, root.rest == null ? List.of() : List.copyOf(root.rest), pkg, coordinate,
                values == null ? null : values.owner(), values == null ? 0 : values.owners(),
                either(own.output, top.output), either(own.filter, top.filter),
                own.page != FIRST_PAGE ? own.page : top.page, either(own.module, top.module),
                either(own.version, top.version), own.refresh || top.refresh);
    }

    private static String either(String own, String top) {
        return own != null ? own : top;
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
        return owner == null ? command : command.ownedBy(owner.kind(), owner.name());
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
    String retargeted(String overview) {
        return Command.targetOf(overview).map(target -> command(target).text() + pageArgument()).orElse(overview);
    }

    /** {@code --page} and its value with a leading space, or empty on the first page. */
    String pageArgument() {
        return page == FIRST_PAGE ? "" : " " + Command.PAGE_FLAG + " " + page;
    }

    /** This command with the package written as {@code read}. */
    Invocation at(Coordinate read) {
        String text = read.target(null).pkg();
        List<String> written = names;
        if (Command.PACKAGE.equals(noun) && !names.isEmpty()) {
            written = new ArrayList<>(names);
            written.set(0, text);
        }
        return new Invocation(noun, List.copyOf(written), rest, pkg == null ? null : text, text, owner, owners,
                output, filter, page, module, version, refresh);
    }

    Invocation withNames(List<String> others) {
        return new Invocation(noun, List.copyOf(others), rest, pkg, coordinate, owner, owners, output, filter, page,
                module, version, refresh);
    }

    /** The scope the container flag names, or {@code null} without one. */
    Surface.Scope ownerScope() {
        return owner == null ? null : Surface.Scope.ofVerb(owner.kind()).orElse(null);
    }

    String ownerName() {
        return owner == null ? null : owner.name();
    }
}
