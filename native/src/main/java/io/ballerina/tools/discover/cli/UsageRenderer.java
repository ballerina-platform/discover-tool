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
 * The synopsis and the option rows, rendered from {@link Commands}' picocli model rather than hand-written.
 *
 * <p>An option's name, label and description are declared once, on {@link Commands}, and rendered rather than
 * repeated. What a noun's names are called is {@link Usage}'s, since the grammar declares one list for every noun.
 *
 * @since 0.1.0
 */
final class UsageRenderer {

    /** The widest line the help may reach. */
    static final int WIDTH = 80;

    /** Where a two-column row's right column starts; a longer label puts it on a line of its own. */
    static final int OPTION_COLUMN = 30;

    static final String INDENT = "  ";

    private static final int GAP = 2;

    private static final String USAGE_LEAD = "Usage: ";

    private static final String USAGE = USAGE_LEAD + Command.PREFIX;

    private static final String OPTIONS_SLOT = "[options]";

    private UsageRenderer() {
    }

    /** The {@code Usage:} line for {@code noun}'s {@code --help}, or the root's when null, wrapped. */
    static String synopsis(Commands.Grammar grammar, String noun) {
        return wrap(USAGE, slots(grammar, noun), USAGE.length());
    }

    /** {@code noun}'s synopsis as a further line under another's {@code Usage:}, aligned with it. */
    static String alternativeSynopsis(Commands.Grammar grammar, String noun) {
        String lead = " ".repeat(USAGE_LEAD.length()) + Command.PREFIX;
        return wrap(lead, slots(grammar, noun), USAGE.length());
    }

    /** The same synopsis on one line, for the end of a usage error. */
    static String usageLine(Commands.Grammar grammar, String noun) {
        return USAGE + String.join(" ", slots(grammar, noun));
    }

    /** The root synopsis on one unwrapped line, for the launcher's one-line usage. */
    static String synopsisLine(Commands.Grammar grammar) {
        return Command.PREFIX + String.join(" ", slots(grammar, null));
    }

    // The noun and its names, the package, the container flags as one slot (only one is ever given), the rest.
    private static List<String> slots(Commands.Grammar grammar, String noun) {
        List<String> slots = new ArrayList<>();
        slots.add(noun == null ? Usage.ROOT_LABEL : noun + Usage.label(noun));
        Set<String> required = Usage.requiredFlags(noun);
        for (OptionSpec option : visible(grammar.spec(noun))) {
            String name = option.shortestName();
            if (Command.PACKAGE_FLAG.equals(name)) {
                String spelled = name + " " + option.paramLabel();
                slots.add(required.contains(name) ? spelled : "[" + spelled + "]");
            } else if (noun != null && Command.OWNER_FLAGS.get(0).equals(name)) {
                slots.add("[" + ownerLabel(option) + "]");
            } else if (noun != null && Command.ACCESSOR_FLAG.equals(name)) {
                slots.add("[" + name + " " + option.paramLabel() + "]");
            }
        }
        slots.add(OPTIONS_SLOT);
        return slots;
    }

    /**
     * Every option {@code noun} takes, the container flags as one row described by {@code owners}: the required
     * ones first, then the rest by long name. {@code turnedDown} names the options it parses but rejects.
     */
    static List<Row> optionRows(Commands.Grammar grammar, String noun, String owners, Set<String> turnedDown) {
        return rows(visible(grammar.spec(noun)).stream()
                .filter(option -> !turnedDown.contains(option.longestName()))
                .toList(), Usage.requiredFlags(noun), owners);
    }

    private static List<Row> rows(List<OptionSpec> options, Set<String> required, String owners) {
        List<OptionSpec> ordered = options.stream()
                .sorted(Comparator.comparing((OptionSpec option) -> !required.contains(option.shortestName()))
                        .thenComparing(OptionSpec::longestName))
                .toList();
        List<Row> rows = new ArrayList<>();
        for (OptionSpec option : ordered) {
            String name = option.shortestName();
            if (!Command.OWNER_FLAGS.contains(name)) {
                rows.add(new Row(label(option), String.join(" ", option.description())));
            } else if (Command.OWNER_FLAGS.get(0).equals(name)) {
                rows.add(new Row(ownerLabel(option), owners));
            }
        }
        return rows;
    }

    private static List<OptionSpec> visible(CommandSpec spec) {
        return spec.options().stream().filter(option -> !option.hidden()).toList();
    }

    private static String ownerLabel(OptionSpec option) {
        return String.join("|", Command.OWNER_FLAGS) + " " + option.paramLabel();
    }

    private static String label(OptionSpec option) {
        List<String> names = new ArrayList<>(List.of(option.names()));
        names.sort(Comparator.comparingInt(String::length));
        String spelled = String.join(", ", names);
        return option.arity().max() == 0 ? spelled : spelled + " " + option.paramLabel();
    }

    /**
     * One row of a two-column table.
     *
     * @param label the left column
     * @param text the right column, one line
     */
    record Row(String label, String text) { }

    /** Rows whose right column starts at {@code column}; a label too long for it puts the text below it. */
    static String table(List<Row> rows, int column) {
        StringBuilder text = new StringBuilder();
        for (Row row : rows) {
            String lead = INDENT + row.label();
            if (lead.length() + GAP > column) {
                text.append(lead).append("\n").append(" ".repeat(column));
            } else {
                text.append(lead).append(" ".repeat(column - lead.length()));
            }
            text.append(row.text()).append("\n");
        }
        return text.toString();
    }

    private static String wrap(String lead, List<String> tokens, int indent) {
        StringBuilder text = new StringBuilder(lead);
        int column = lead.length();
        boolean first = true;
        for (String token : tokens) {
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
