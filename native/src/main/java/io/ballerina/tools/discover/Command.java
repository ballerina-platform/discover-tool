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
import java.util.Map;
import java.util.Optional;

/**
 * Every command this tool prints, built in one place and written in one order:
 * {@code bal discover <noun> [name] -p <org>/<name>[:<version>] [--client|--class|--service <C>]
 * [--accessor <accessor>] [--module <m>] [--filter <keyword>] [--page <n>]}.
 *
 * <p>The {@code package} noun takes its coordinate as its name rather than with {@code -p}, and {@code search}
 * without a package asks Central.
 *
 * <p>Every word is shell-quoted as it needs, except a {@link #placeholder}, which is written as its label.
 *
 * @param noun what is asked for: {@code client}, {@code method}, {@code package}
 * @param names the names after the noun
 * @param target the package and module it is asked of, or {@code null} for a Central search
 * @param owner the container a {@code method} or {@code resource} is declared by, or {@code null}
 * @param accessor the accessor that selects one {@code resource} method of the path, or {@code null}
 * @param filter the {@code --filter} keyword, or {@code null}
 * @param page the {@code --page} number, written only past the first page
 * @since 0.1.0
 */
public record Command(String noun, List<String> names, Target target, Owner owner, String accessor, String filter,
        int page) {

    /** The program name every text-mode report starts with. */
    public static final String PROGRAM = "bal discover";

    public static final String PREFIX = PROGRAM + " ";

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

    public static final String FLAG_PREFIX = "--";
    public static final String END_OF_OPTIONS = "--";
    public static final String FLAG_START = "-";
    public static final String PACKAGE_FLAG = "-p";
    public static final String PACKAGE_LONG_FLAG = FLAG_PREFIX + PACKAGE;
    public static final String CLIENT_FLAG = FLAG_PREFIX + CLIENT;
    public static final String CLASS_FLAG = FLAG_PREFIX + CLASS;
    public static final String SERVICE_FLAG = FLAG_PREFIX + SERVICE;
    public static final String MODULE_FLAG = "--module";
    public static final String FILTER_FLAG = "--filter";
    public static final String PAGE_FLAG = "--page";
    public static final String ACCESSOR_FLAG = "--accessor";

    /** The container flags, in the order the usage lists them. */
    public static final List<String> OWNER_FLAGS = List.of(CLIENT_FLAG, CLASS_FLAG, SERVICE_FLAG);

    public static final int FIRST_PAGE = 1;

    // No argument can hold a NUL, so a word marked with one was never typed.
    private static final char PLACEHOLDER_MARK = '\0';

    private static final Map<String, String> OWNER_KINDS = Map.of(
            CLIENT_FLAG, CLIENT, CLASS_FLAG, CLASS, SERVICE_FLAG, SERVICE);

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
        return of(noun, List.of(names));
    }

    public static Command of(String noun, List<String> names) {
        return new Command(noun, names, null, null, null, null, FIRST_PAGE);
    }

    /** {@code bal discover package <pkg> [--module <m>]}: a package's overview. */
    public static Command overview(Target target) {
        return of(PACKAGE).in(target);
    }

    /** A word written as {@code label} itself, never quoted: what a reader replaces, such as {@code <name>}. */
    public static String placeholder(String label) {
        return PLACEHOLDER_MARK + label;
    }

    public static boolean isPlaceholder(String word) {
        return !word.isEmpty() && word.charAt(0) == PLACEHOLDER_MARK;
    }

    public Command in(Target target) {
        return new Command(noun, names, target, owner, accessor, filter, page);
    }

    public Command with(String... more) {
        return with(List.of(more));
    }

    public Command with(List<String> more) {
        List<String> all = new ArrayList<>(names);
        all.addAll(more);
        return withNames(all);
    }

    public Command withNames(List<String> others) {
        return new Command(noun, others, target, owner, accessor, filter, page);
    }

    public Command ownedBy(String kind, String name) {
        return ownedBy(new Owner(kind, name));
    }

    public Command ownedBy(Owner other) {
        return new Command(noun, names, target, other, accessor, filter, page);
    }

    /** This command selecting the {@code resource} method its path declares for {@code other}, or none. */
    public Command accessedBy(String other) {
        return new Command(noun, names, target, owner, other, filter, page);
    }

    /** This command narrowed by {@code keyword}, or as it is when {@code keyword} is {@code null}. */
    public Command filtered(String keyword) {
        return new Command(noun, names, target, owner, accessor, keyword, page);
    }

    /** This command on page {@code number}; the first page writes no {@code --page}. */
    public Command paged(int number) {
        return new Command(noun, names, target, owner, accessor, filter, number);
    }

    // A name that starts with a dash would read as a flag, so such names go last, after `--`.
    public String text() {
        StringBuilder text = new StringBuilder(PREFIX).append(noun);
        if (target != null && PACKAGE.equals(noun)) {
            text.append(' ').append(word(target.pkg()));
        }
        boolean dashed = names.stream().anyMatch(name -> name.startsWith(FLAG_START));
        if (!dashed) {
            names.forEach(name -> text.append(' ').append(word(name)));
        }
        if (target != null && !PACKAGE.equals(noun)) {
            flag(text, PACKAGE_FLAG, target.pkg());
        }
        if (owner != null) {
            flag(text, owner.flag(), owner.name());
        }
        if (accessor != null) {
            flag(text, ACCESSOR_FLAG, accessor);
        }
        if (target != null && target.module() != null) {
            flag(text, MODULE_FLAG, target.module());
        }
        if (filter != null) {
            flag(text, FILTER_FLAG, filter);
        }
        if (page != FIRST_PAGE) {
            flag(text, PAGE_FLAG, String.valueOf(page));
        }
        if (dashed) {
            text.append(' ').append(END_OF_OPTIONS);
            names.forEach(name -> text.append(' ').append(word(name)));
        }
        return text.toString();
    }

    /** What follows {@code bal discover}. */
    public String arguments() {
        return text().substring(PREFIX.length());
    }

    /**
     * The command {@link #text()} wrote, read back word by word; empty for any other text. Lets a renderer that holds
     * only a result's printed command reshape it, a name for a placeholder, without touching any other word.
     */
    public static Optional<Command> parse(String text) {
        if (text == null || !text.startsWith(PREFIX)) {
            return Optional.empty();
        }
        List<String> words = ShellWords.split(text.substring(PREFIX.length()));
        if (words.isEmpty()) {
            return Optional.empty();
        }
        String noun = words.get(0);
        List<String> names = new ArrayList<>();
        String pkg = null;
        String module = null;
        String filter = null;
        Owner owner = null;
        String accessor = null;
        int page = FIRST_PAGE;
        for (int i = 1; i < words.size(); i++) {
            String word = words.get(i);
            if (END_OF_OPTIONS.equals(word)) {
                names.addAll(words.subList(i + 1, words.size()));
                break;
            }
            boolean flag = PACKAGE_FLAG.equals(word) || MODULE_FLAG.equals(word) || FILTER_FLAG.equals(word)
                    || PAGE_FLAG.equals(word) || ACCESSOR_FLAG.equals(word) || OWNER_KINDS.containsKey(word);
            if (!flag) {
                if (PACKAGE.equals(noun) && pkg == null) {
                    pkg = word;
                } else {
                    names.add(word);
                }
                continue;
            }
            if (++i == words.size()) {
                return Optional.empty();
            }
            String value = words.get(i);
            switch (word) {
                case PACKAGE_FLAG -> pkg = value;
                case MODULE_FLAG -> module = value;
                case FILTER_FLAG -> filter = value;
                case ACCESSOR_FLAG -> accessor = value;
                case PAGE_FLAG -> {
                    if (!value.chars().allMatch(Character::isDigit)) {
                        return Optional.empty();
                    }
                    page = Integer.parseInt(value);
                }
                default -> owner = new Owner(OWNER_KINDS.get(word), value);
            }
        }
        Target target = pkg == null ? null : new Target(pkg, module);
        return Optional.of(new Command(noun, names, target, owner, accessor, filter, page));
    }

    @Override
    public String toString() {
        return text();
    }

    private static void flag(StringBuilder text, String flag, String value) {
        text.append(' ').append(flag).append(' ').append(word(value));
    }

    private static String word(String name) {
        return isPlaceholder(name) ? name.substring(1) : Texts.shellWord(name);
    }

    // Splits a command line as a POSIX shell does: whitespace between words, quotes and backslashes inside one.
    private static final class ShellWords {

        private ShellWords() {
        }

        static List<String> split(String line) {
            List<String> words = new ArrayList<>();
            StringBuilder word = null;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == ' ') {
                    if (word != null) {
                        words.add(word.toString());
                        word = null;
                    }
                    continue;
                }
                word = word == null ? new StringBuilder() : word;
                if (c == '\\' && i + 1 < line.length()) {
                    word.append(line.charAt(++i));
                } else if (c == '\'' || c == '"') {
                    int end = line.indexOf(c, i + 1);
                    end = end < 0 ? line.length() : end;
                    word.append(line, i + 1, end);
                    i = end;
                } else {
                    word.append(c);
                }
            }
            if (word != null) {
                words.add(word.toString());
            }
            return words;
        }
    }
}
