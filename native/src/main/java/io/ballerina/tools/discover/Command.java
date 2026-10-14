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

package io.ballerina.tools.discover;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Every command this tool prints, built in one place and written in one order:
 * {@code bal discover <noun> [name ...] -p <org>/<name>[:<version>] [--client|--class|--service <C>] [--module <m>]
 * [--filter <keyword>]}. {@code --page} is appended last by whoever turns a page.
 *
 * <p>The {@code package} noun takes its coordinate as its name rather than with {@code -p}, and {@code search}
 * without a package asks Central.
 *
 * @param noun what is asked for: {@code client}, {@code method}, {@code package}
 * @param names the names after the noun, as this tool prints them; a placeholder in angle brackets is written
 *     unquoted
 * @param target the package and module it is asked of, or {@code null} for a Central search
 * @param owner the container a {@code method} or {@code resource} is declared by, or {@code null}
 * @param filter the {@code --filter} keyword, or {@code null}
 * @since 0.1.0
 */
public record Command(String noun, List<String> names, Target target, Owner owner, String filter) {

    public static final String PREFIX = "bal discover ";

    public static final String PACKAGE = "package";
    public static final String SEARCH = "search";
    public static final String CLIENT = "client";
    public static final String SERVICE = "service";
    public static final String CLASS = "class";
    public static final String FUNCTION = "function";
    public static final String TYPE = "type";
    public static final String METHOD = "method";
    public static final String RESOURCE = "resource";
    public static final String README = "readme";

    /** Every noun, in the order the usage lists them. */
    public static final List<String> NOUNS = List.of(
            PACKAGE, SEARCH, CLIENT, SERVICE, CLASS, FUNCTION, TYPE, METHOD, RESOURCE, README);

    /** The nouns a container flag applies to. */
    public static final List<String> MEMBER_NOUNS = List.of(METHOD, RESOURCE);

    public static final String PACKAGE_FLAG = "-p";
    public static final String MODULE_FLAG = "--module";
    public static final String FILTER_FLAG = "--filter";
    public static final String PAGE_FLAG = "--page";
    public static final String FLAG_PREFIX = "--";

    /**
     * @param pkg {@code org/name}, with {@code :version} when the command pins one
     * @param module the submodule, or {@code null} for the package's default module
     */
    public record Target(String pkg, String module) { }

    /**
     * @param kind the container's noun, {@code client}, {@code class} or {@code service}, which its flag is named for
     * @param name the container's name
     */
    public record Owner(String kind, String name) {

        public String flag() {
            return FLAG_PREFIX + kind;
        }
    }

    public Command {
        names = List.copyOf(names);
    }

    public static Command of(String noun, String... names) {
        return new Command(noun, List.of(names), null, null, null);
    }

    public static Command of(String noun, List<String> names) {
        return new Command(noun, names, null, null, null);
    }

    /** {@code bal discover package <pkg> [--module <m>]}: a package's overview. */
    public static Command overview(Target target) {
        return of(PACKAGE).in(target);
    }

    /**
     * Where an overview command this tool printed points — the inverse of {@link #overview(Target)}'s text — or
     * empty for any other command.
     */
    public static Optional<Target> targetOf(String overview) {
        String start = PREFIX + PACKAGE + " ";
        if (!overview.startsWith(start)) {
            return Optional.empty();
        }
        String[] words = overview.substring(start.length()).split(" ");
        if (words.length == 1) {
            return Optional.of(new Target(words[0], null));
        }
        return words.length == 3 && MODULE_FLAG.equals(words[1])
                ? Optional.of(new Target(words[0], words[2]))
                : Optional.empty();
    }

    public Command in(Target target) {
        return new Command(noun, names, target, owner, filter);
    }

    public Command in(String pkg, String module) {
        return in(new Target(pkg, module));
    }

    public Command with(String... more) {
        return with(List.of(more));
    }

    public Command with(List<String> more) {
        List<String> all = new ArrayList<>(names);
        all.addAll(more);
        return new Command(noun, all, target, owner, filter);
    }

    public Command ownedBy(String kind, String name) {
        return new Command(noun, names, target, new Owner(kind, name), filter);
    }

    public Command withoutOwner() {
        return new Command(noun, names, target, null, filter);
    }

    /** This command narrowed by {@code keyword}, or as it is when {@code keyword} is {@code null}. */
    public Command filtered(String keyword) {
        return new Command(noun, names, target, owner, keyword);
    }

    public String text() {
        StringBuilder text = new StringBuilder(PREFIX).append(noun);
        names.forEach(name -> text.append(' ').append(word(name)));
        if (target != null) {
            text.append(PACKAGE.equals(noun) ? " " : " " + PACKAGE_FLAG + " ").append(Texts.shellWord(target.pkg()));
        }
        if (owner != null) {
            text.append(' ').append(owner.flag()).append(' ').append(Texts.shellWord(owner.name()));
        }
        if (target != null && target.module() != null) {
            text.append(' ').append(MODULE_FLAG).append(' ').append(Texts.shellWord(target.module()));
        }
        if (filter != null) {
            text.append(' ').append(FILTER_FLAG).append(' ').append(Texts.shellWord(filter));
        }
        return text.toString();
    }

    @Override
    public String toString() {
        return text();
    }

    private static String word(String name) {
        return name.startsWith("<") && name.endsWith(">") ? name : Texts.shellWord(name);
    }
}
