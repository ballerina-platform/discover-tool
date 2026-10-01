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

package io.ballerina.tools.discover.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Rows of cells, padded into aligned columns — one entry per line, for {@link TextRenderer}.
 *
 * @since 0.1.0
 */
final class TextTable {

    /**
     * How one column lines up.
     *
     * @param right whether the cells line up on their right edge
     * @param singular the unit a count column appends to a count of one, or {@code null} for a plain column
     * @param plural the unit a count column appends to any other count
     */
    record Column(boolean right, String singular, String plural) {

        static final Column LEFT = new Column(false, null, null);
        static final Column RIGHT = new Column(true, null, null);

        /** A count, right-aligned, followed by its unit; a zero count leaves the cell blank. */
        static Column count(String singular, String plural) {
            return new Column(true, singular, plural);
        }

        private boolean counted() {
            return singular != null;
        }

        private String cell(String value) {
            if (!counted()) {
                return value;
            }
            if (value.isEmpty() || "0".equals(value)) {
                return "";
            }
            return value + " " + ("1".equals(value) ? singular : plural);
        }

        private String pad(String value, int numberWidth, int width) {
            String cell = cell(value);
            if (counted() && !cell.isEmpty()) {
                int number = value.length();
                String aligned = " ".repeat(numberWidth - number) + cell;
                return aligned + " ".repeat(width - aligned.length());
            }
            String padding = " ".repeat(width - cell.length());
            return right ? padding + cell : cell + padding;
        }
    }

    private static final String GAP = "  ";

    private final List<Column> columns;
    private final List<String[]> rows = new ArrayList<>();

    TextTable(Column... columns) {
        this.columns = List.of(columns);
    }

    TextTable row(String... cells) {
        String[] row = new String[columns.size()];
        Arrays.fill(row, "");
        for (int i = 0; i < cells.length; i++) {
            row[i] = cells[i] == null ? "" : cells[i];
        }
        rows.add(row);
        return this;
    }

    boolean isEmpty() {
        return rows.isEmpty();
    }

    /**
     * Every row, one per line, each column padded to its widest cell. A column no row fills is dropped, and
     * trailing padding is trimmed, so an optional column — a count that is zero everywhere, a command no row
     * needs — costs nothing when it is empty.
     */
    List<String> lines(String indent) {
        int[] numberWidths = new int[columns.size()];
        int[] widths = new int[columns.size()];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                String cell = columns.get(i).cell(row[i]);
                if (!cell.isEmpty()) {
                    numberWidths[i] = Math.max(numberWidths[i], row[i].length());
                }
            }
        }
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                String cell = columns.get(i).cell(row[i]);
                int extra = columns.get(i).counted() && !cell.isEmpty() ? numberWidths[i] - row[i].length() : 0;
                widths[i] = Math.max(widths[i], cell.length() + extra);
            }
        }
        List<String> lines = new ArrayList<>();
        for (String[] row : rows) {
            StringBuilder line = new StringBuilder(indent);
            boolean first = true;
            for (int i = 0; i < row.length; i++) {
                if (widths[i] == 0) {
                    continue;
                }
                line.append(first ? "" : GAP).append(columns.get(i).pad(row[i], numberWidths[i], widths[i]));
                first = false;
            }
            lines.add(line.toString().stripTrailing());
        }
        return lines;
    }
}
