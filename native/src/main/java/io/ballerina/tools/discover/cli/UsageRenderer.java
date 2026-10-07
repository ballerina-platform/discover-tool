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

import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The synopsis and the flag list, rendered from {@link Commands}' picocli model rather than hand-written.
 *
 * <p>A flag's name, label and description are declared once, on {@link Commands.Root}, and rendered rather than
 * repeated.
 *
 * @since 0.1.0
 */
final class UsageRenderer {

    /** The widest line the text may reach. Chosen to fit an 80-column terminal with a little slack. */
    private static final int WIDTH = 84;

    private static final int GAP = 3;

    private UsageRenderer() {
    }

    /** The {@code Usage:} line for {@code --help}, wrapped to {@link #WIDTH}. */
    static String synopsis(Commands.Grammar grammar) {
        String label = "Usage: bal discover ";
        return wrap(label, slots(grammar), label.length());
    }

    /** The synopsis on one unwrapped line, {@code --help} included, for the launcher's one-line usage. */
    static String synopsisLine(Commands.Grammar grammar) {
        List<String> slots = new ArrayList<>(slots(grammar));
        grammar.line().getCommandSpec().options().stream()
                .filter(OptionSpec::usageHelp)
                .forEach(help -> slots.add("[" + String.join("|", help.names()) + "]"));
        return "bal discover " + String.join(" ", slots);
    }

    /** Every flag the grammar accepts, {@code --help} last, as one sentence for an unknown-option failure. */
    static String knownFlags(Commands.Grammar grammar) {
        return knownFlags(grammar.line().getCommandSpec());
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

    private static List<String> slots(Commands.Grammar grammar) {
        CommandSpec spec = grammar.line().getCommandSpec();
        List<String> slots = new ArrayList<>();
        for (PositionalParamSpec positional : spec.positionalParameters()) {
            slots.add(slot(positional));
        }
        for (OptionSpec option : orderedOptions(spec)) {
            slots.add(slot(option));
        }
        return slots;
    }

    private static String slot(PositionalParamSpec positional) {
        String label = positional.paramLabel();
        if (label.startsWith("[")) {
            return label;
        }
        boolean repeats = positional.index().max() == Integer.MAX_VALUE || positional.arity().max() > 1;
        if (repeats) {
            return "[" + label + "...]";
        }
        return positional.arity().min() == 0 ? "[" + label + "]" : label;
    }

    private static String slot(OptionSpec option) {
        String name = shortestName(option);
        return "[" + (option.arity().max() == 0 ? name : name + " " + option.paramLabel()) + "]";
    }

    private static String shortestName(OptionSpec option) {
        String shortest = option.longestName();
        for (String name : option.names()) {
            if (name.length() < shortest.length()) {
                shortest = name;
            }
        }
        return shortest;
    }

    /** Every flag this grammar accepts, minus {@code --help} — a row saying "this is help" is not worth one. */
    static String flagList(Commands.Grammar grammar) {
        List<Row> rows = orderedOptions(grammar.line().getCommandSpec()).stream()
                .map(option -> new Row(label(option), description(option)))
                .toList();
        return rows.isEmpty() ? "" : table(rows);
    }

    private static List<OptionSpec> orderedOptions(CommandSpec spec) {
        return spec.options().stream()
                .filter(option -> !option.usageHelp() && !option.hidden())
                .toList();
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

    private record Row(String label, String text) { }

    private static String table(List<Row> rows) {
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
