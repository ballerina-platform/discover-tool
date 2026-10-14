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
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * The synopsis and the flag list, rendered from {@link Commands}' picocli model rather than hand-written.
 *
 * <p>A flag's name, label and description are declared once, on {@link Commands}, and rendered rather than
 * repeated. What a noun's names are called is {@link Usage}'s, since the grammar declares one list for every noun.
 *
 * @since 0.1.0
 */
final class UsageRenderer {

    /** The widest line the text may reach. Chosen to fit an 80-column terminal with a little slack. */
    private static final int WIDTH = 84;

    private static final int GAP = 3;

    private static final String USAGE = "Usage: " + Command.PREFIX;

    /** Flags rendered first, in this order: the package, then the container. */
    private static final List<String> LEADING = List.of(Command.PACKAGE_FLAG, "--client", "--class", "--service");

    private UsageRenderer() {
    }

    /** The {@code Usage:} line for {@code noun}'s {@code --help}, or the root's when null, wrapped. */
    static String synopsis(Commands.Grammar grammar, String noun) {
        return wrap(USAGE, slots(grammar, noun), USAGE.length());
    }

    static String usageLine(Commands.Grammar grammar, String noun) {
        return USAGE + String.join(" ", slots(grammar, noun));
    }

    /** The root synopsis on one unwrapped line, {@code --help} included, for the launcher's one-line usage. */
    static String synopsisLine(Commands.Grammar grammar) {
        List<String> slots = new ArrayList<>(slots(grammar, null));
        grammar.spec(null).options().stream()
                .filter(OptionSpec::usageHelp)
                .forEach(help -> slots.add("[" + String.join("|", help.names()) + "]"));
        return Command.PREFIX + String.join(" ", slots);
    }

    static String knownFlags(CommandSpec spec) {
        List<OptionSpec> options = spec.options().stream()
                .filter(option -> !option.hidden())
                .sorted(Comparator.comparing(OptionSpec::usageHelp))
                .toList();
        List<String> names = options.stream()
                .map(option -> {
                    List<String> spellings = new ArrayList<>(List.of(option.names()));
                    spellings.sort(Comparator.comparingInt(String::length).reversed());
                    return String.join("/", spellings);
                })
                .toList();
        if (names.size() < 2) {
            return names.isEmpty() ? "This command takes no flags." : "The only known flag is " + names.get(0) + ".";
        }
        return "Known flags are " + String.join(", ", names.subList(0, names.size() - 1))
                + " and " + names.get(names.size() - 1) + ".";
    }

    private static List<String> slots(Commands.Grammar grammar, String noun) {
        List<String> slots = new ArrayList<>();
        slots.add(noun == null ? Usage.ROOT_LABEL : noun + Usage.label(noun));
        Set<String> required = Usage.requiredFlags(noun);
        for (OptionSpec option : orderedOptions(grammar.spec(noun))) {
            slots.add(slot(option, required.contains(option.shortestName())));
        }
        return slots;
    }

    private static String slot(OptionSpec option, boolean required) {
        String name = option.shortestName();
        String spelled = option.arity().max() == 0 ? name : name + " " + option.paramLabel();
        return required ? spelled : "[" + spelled + "]";
    }

    /** Every flag {@code noun} accepts, minus {@code --help} — a row saying "this is help" is not worth one. */
    static String flagList(Commands.Grammar grammar, String noun) {
        List<Row> rows = orderedOptions(grammar.spec(noun)).stream()
                .map(option -> new Row(label(option), description(option)))
                .toList();
        return rows.isEmpty() ? "" : table(rows);
    }

    private static List<OptionSpec> orderedOptions(CommandSpec spec) {
        return spec.options().stream()
                .filter(option -> !option.usageHelp() && !option.hidden())
                .sorted(Comparator.comparingInt(UsageRenderer::rank))
                .toList();
    }

    private static int rank(OptionSpec option) {
        int leading = LEADING.indexOf(option.shortestName());
        return leading < 0 ? LEADING.size() : leading;
    }

    private static String label(OptionSpec option) {
        List<String> names = new ArrayList<>(List.of(option.names()));
        names.sort(Comparator.comparingInt(String::length));
        String spelled = String.join(", ", names);
        return option.arity().max() == 0 ? spelled : spelled + " " + option.paramLabel();
    }

    private static String description(OptionSpec option) {
        return String.join(" ", option.description());
    }

    /**
     * One row of a two-column table.
     *
     * @param label the left column
     * @param text the right column, wrapped beside it
     */
    record Row(String label, String text) { }

    static String table(List<Row> rows) {
        int labelWidth = rows.stream().mapToInt(row -> row.label().length()).max().orElse(0);
        int indent = 2 + labelWidth + GAP;
        StringBuilder text = new StringBuilder();
        for (Row row : rows) {
            String lead = "  " + row.label() + " ".repeat(indent - 2 - row.label().length());
            text.append(paragraph(lead, row.text(), indent));
        }
        return text.toString();
    }

    /** A paragraph of prose, wrapped to {@link #WIDTH} and hanging at {@code indent}. */
    static String prose(String body, int indent) {
        return paragraph(" ".repeat(indent), body, indent);
    }

    private static String paragraph(String lead, String body, int indent) {
        return wrap(lead, List.of(body.trim().split("\\s+")), indent);
    }

    private static String wrap(String lead, List<String> tokens, int indent) {
        StringBuilder text = new StringBuilder(lead);
        int column = lead.length();
        boolean first = true;
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            if (!first && column + 1 + token.length() > WIDTH) {
                text.append("\n").append(" ".repeat(indent));
                column = indent;
            } else if (!first) {
                text.append(" ");
                column++;
            }
            text.append(token);
            column += token.length();
            first = false;
        }
        return text.append("\n").toString();
    }
}
