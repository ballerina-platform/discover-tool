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

import io.ballerina.tools.discover.Coordinate;
import io.ballerina.tools.discover.views.Search;
import io.ballerina.tools.discover.views.SearchCentral;

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
                .append("\n").append(UsageRenderer.prose(BUCKETS, 0))
                .append("\n").append(UsageRenderer.prose(SEARCH, 0))
                .append("\n").append(UsageRenderer.prose(VERSION, 0))
                .append(UsageRenderer.prose(EXIT_CODES, 0));
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

              1. Find a package or a symbol:  bal discover search kafka send
              2. What a package offers:       bal discover ballerinax/kafka
              3. Find a name inside it:       bal discover ballerinax/kafka search send
              4. One call, fully addressed:   bal discover ballerinax/kafka client Producer send
                                              bal discover ballerinax/github client Client gists/public get
              5. Its container, left out:     bal discover ballerinax/kafka client send
              6. A record, enum or error:     bal discover ballerinax/github type ConnectionConfig
              7. One specific version:        bal discover ballerina/http:2.15.7 client Client
            """;

    private static final String VERSION =
            "With no version after the package, the one your project's Dependencies.toml locks is read (found "
                    + "from the current directory up), else Central's latest.";

    private static final String EXIT_CODES = "Exit codes: 0 an answer, 1 a failure, 2 a usage error.";

    private static final String BUCKETS =
            "Buckets (the word after the package): " + String.join(", ", Commands.BUCKETS)
                    + ". Leave it out for the package's overview: its clients, service types, classes and "
                    + "functions, types and readme, each with the command that opens it. The container may be left "
                    + "out when exactly one in the bucket declares the member.";

    private static final String SEARCH =
            Coordinate.COMMAND + SearchCentral.VERB + " <keyword> ... asks Central for packages and the symbols "
                    + "that match every keyword, " + SearchCentral.PACKAGES_PER_PAGE + " and "
                    + SearchCentral.SYMBOLS_PER_PAGE + " a page, ranked by Central. " + Coordinate.COMMAND
                    + "<org>/<name> " + Search.BUCKET + " <keyword> ... matches every name in one package, "
                    + "locally; each keyword is a case-insensitive part of the name.";
}
