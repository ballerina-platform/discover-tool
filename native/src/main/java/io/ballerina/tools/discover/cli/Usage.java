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

/**
 * The PROSE of the usage text: everything a reader cannot derive from the grammar.
 *
 * <p>It teaches only "what can I ask, and how". The rules a caller carries INTO a lookup (a truncated listing
 * means narrow further, a failure's {@code kind} is the branch to read) are the agent's standing instructions,
 * not this command's usage.
 *
 * @since 0.1.0
 */
final class Usage {

    private Usage() {
    }

    /** The whole usage text: what the tool is, what it can be asked, and how to walk it. */
    static String root() {
        Commands.Grammar grammar = Commands.Grammar.create();
        StringBuilder text = new StringBuilder()
                .append(UsageRenderer.synopsis(grammar))
                .append("\n").append(UsageRenderer.prose(INTRO, 0))
                .append("\n").append(SESSION)
                .append("\n").append(UsageRenderer.prose(BUCKETS, 0));
        String flags = UsageRenderer.flagList(grammar);
        if (!flags.isEmpty()) {
            text.append("\n").append(flags);
        }
        return text.toString();
    }

    private static final String INTRO =
            "Read a Ballerina package off Central. A signature from here is the source for what compiles; a "
                    + "readme, a web search and a remembered API are not.";

    /**
     * The typical session, as commands.
     *
     * <p>Written out rather than generated: the VALUE of this block is the specific packages and the specific
     * selector shapes, which no model of the grammar knows.
     */
    private static final String SESSION = """
            Typical session:

              1. What can I ask?              bal discover ballerinax/github
              2. A bucket, listed or one:     bal discover ballerinax/github client
                                              bal discover ballerinax/github client Client
              3. One call, fully addressed:   bal discover ballerinax/github client "gists/'public" get
              4. A record, enum or error:     bal discover ballerinax/github type ConnectionConfig
            """;

    private static final String BUCKETS =
            "Buckets, addressed as the second positional: " + String.join(", ", Commands.BUCKETS)
                    + ". No bucket lists which of them this package has.";
}
