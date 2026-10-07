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

import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Text conventions the whole reader shares, gathered here because getting one of them wrong moves
 * every snapshot in the corpus.
 *
 * @since 0.1.0
 */
public final class Texts {

    /**
     * Locale-aware ordering, which is what the documents are ordered by.
     *
     * <p>{@code String::compareTo} is NOT the same relation: on real resource-path segments it moves
     * {@code {owner}} from position 2 to position 10, because it compares UTF-16 code units and puts
     * every punctuation character before every letter. That silently reorders the path tree, the
     * external footer and the near-miss candidates — the three places ordering is part of the output.
     * A collator is the relation the source documents were written under.
     *
     * <p>The {@code compareTo} tie-break is what keeps it a TOTAL order: a collator reports 0 for
     * strings it considers equivalent, and a comparator that returns 0 for distinct strings makes a
     * sort's outcome depend on the input order.
     */
    private static final Collator COLLATOR = Collator.getInstance(Locale.US);

    public static final Comparator<String> LOCALE_ORDER = Texts::compareLocale;

    private static final Pattern SAFE_WORD = Pattern.compile("[A-Za-z0-9_./:%+-][A-Za-z0-9_./:%+=-]*");

    private static final Pattern DOUBLE_QUOTE_UNSAFE = Pattern.compile("[\"$`\\\\!]");

    private Texts() {
    }

    public static int compareLocale(String left, String right) {
        int collated = COLLATOR.compare(left, right);
        return collated != 0 ? collated : left.compareTo(right);
    }

    /** UTF-8 bytes, not UTF-16 units: the closure budget a signature inlines its types under is in bytes. */
    public static int byteLength(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** {@code 1234} → {@code 1,234}. */
    public static String count(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    /**
     * One argument as a POSIX shell reads it back unchanged. A word made only of characters no shell treats
     * specially is left bare — {@code =} only past the first character, since zsh expands a leading one to a
     * command's path; anything else is double-quoted, which keeps the keyword-escaping apostrophe
     * ({@code gists/'public}) readable, unless it holds a character double quotes do not neutralise, in which
     * case it is single-quoted.
     */
    public static String shellWord(String token) {
        if (SAFE_WORD.matcher(token).matches()) {
            return token;
        }
        if (!DOUBLE_QUOTE_UNSAFE.matcher(token).find()) {
            return "\"" + token + "\"";
        }
        return "'" + token.replace("'", "'\\''") + "'";
    }
}
