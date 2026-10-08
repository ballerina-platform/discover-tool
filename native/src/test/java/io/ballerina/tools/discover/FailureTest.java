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

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

/**
 * A failure as a text-mode run writes it: what went wrong, then the suggestion and the rest, indented.
 *
 * @since 0.1.0
 */
public class FailureTest {

    @Test
    public void aValidationFailureIsTheMessageThenTheSuggestion() {
        Assert.assertEquals(new Failure.Validation("'x' is not a bucket.", "The buckets are client.").describeText(),
                "error: 'x' is not a bucket.\n  The buckets are client.");
    }

    @Test
    public void aMissingPackageNamesTheCoordinateItLookedFor() {
        Assert.assertEquals(new Failure.PackageNotFound("ballerina/http", "9.9.9", null, "Write one.").describeText(),
                "error: Package not found on Central: ballerina/http:9.9.9.\n  Write one.");
        Assert.assertEquals(new Failure.PackageNotFound("ballerina/htp", null, null, "Check it.").describeText(),
                "error: Package not found on Central: ballerina/htp.\n  Check it.");
        Assert.assertEquals(new Failure.PackageNotFound("ballerina/graphql", "1.17.0", "nosuch", "List them.")
                        .describeText(),
                "error: Module not found on Central: ballerina/graphql:1.17.0, module nosuch.\n  List them.");
    }

    @Test
    public void candidatesAndIssuesGetLinesOfTheirOwn() {
        Assert.assertEquals(new Failure.SymbolNotFound("ballerinax/kafka", "4.6.5", null, List.of("Prod"),
                        List.of("Producer", "Consumer"), "Pick one.").describeText(),
                "error: No match for 'Prod' in ballerinax/kafka:4.6.5.\n  Pick one.\n  candidates:\n    Producer\n"
                        + "    Consumer");
        Assert.assertEquals(new Failure.SchemaDrift("ballerinax/kafka", "4.6.5", null,
                        List.of(new Failure.SchemaIssue("docsData.modules", "expected an array")), "Report it.")
                        .describeText(),
                "error: Central's answer for ballerinax/kafka:4.6.5 has a shape this tool does not understand; "
                        + "Central's docs format may have changed.\n  Report it.\n"
                        + "  issues:\n    docsData.modules: expected an array");
    }

    @Test
    public void aMissWithinAModuleNamesTheModule() {
        Failure miss = new Failure.SymbolNotFound("ballerina/graphql", "1.17.0", "subgraph", List.of("Nope"),
                List.of(), "Pick one.");
        Assert.assertEquals(miss.describeText(),
                "error: No match for 'Nope' in ballerina/graphql:1.17.0, module subgraph.\n  Pick one.");
        Assert.assertEquals(miss.describe(), "{\"kind\":\"symbol-not-found\",\"message\":\"No match for 'Nope' in "
                + "ballerina/graphql:1.17.0, module subgraph.\",\"qualified\":\"ballerina/graphql\",\"version\":"
                + "\"1.17.0\",\"module\":\"subgraph\",\"requested\":[\"Nope\"],\"candidates\":[],\"suggestion\":"
                + "\"Pick one.\"}");
    }

    @Test
    public void aRequestToCentralNamesThePackageItWasFor() {
        Failure upstream = new Failure.Upstream("https://x.test/a", 3,
                "Central answered https://x.test/a with HTTP 503", "Retry.", 503, true)
                .about("ballerina/http", "2.15.7");
        Assert.assertEquals(upstream.describe(), "{\"kind\":\"upstream\",\"message\":\"Central answered "
                + "https://x.test/a with HTTP 503 (3 attempts).\",\"qualified\":\"ballerina/http\",\"version\":"
                + "\"2.15.7\",\"url\":\"https://x.test/a\",\"attempts\":3,\"suggestion\":\"Retry.\",\"status\":503,"
                + "\"reached\":true}");
        Failure timeout = new Failure.Timeout("https://x.test/a", 1000, "Retry.").about("ballerina/http", null);
        Assert.assertEquals(timeout.describe(), "{\"kind\":\"timeout\",\"message\":\"Central did not answer "
                + "https://x.test/a within 1 second.\",\"qualified\":\"ballerina/http\",\"url\":\"https://x.test/a\","
                + "\"budgetMs\":1000,\"suggestion\":\"Retry.\"}");
    }

    @Test
    public void anUpstreamFailureSaysWhereAndHowOften() {
        Assert.assertEquals(new Failure.Upstream("https://x.test/a", 3,
                "Central answered https://x.test/a with HTTP 503", "Retry.", 503, true).describeText(),
                "error: Central answered https://x.test/a with HTTP 503 (3 attempts).\n  Retry.");
        Assert.assertEquals(new Failure.Upstream("https://x.test/a", 1, "Could not resolve the host x.test", "Retry.",
                null, false).describeText(), "error: Could not resolve the host x.test.\n  Retry.");
        Assert.assertEquals(new Failure.Timeout("https://x.test/a", 30000, "Retry.").describeText(),
                "error: Central did not answer https://x.test/a within 30 seconds.\n  Retry.");
        Assert.assertEquals(new Failure.Timeout("https://x.test/a", 1000, "Retry.").describeText(),
                "error: Central did not answer https://x.test/a within 1 second.\n  Retry.");
        Assert.assertEquals(new Failure.Timeout("https://x.test/a", 1500, "Retry.").describeText(),
                "error: Central did not answer https://x.test/a within 1.5 seconds.\n  Retry.");
    }

    @Test
    public void anInternalFailureIsWrittenLikeAnyOtherInEitherMode() {
        Failure internal = new Failure.Internal("boom", Failure.INTERNAL_SUGGESTION);
        Assert.assertEquals(internal.describeText(), "error: Unexpected internal failure: boom.\n  This is a defect "
                + "in bal discover, not in the arguments. Report it with the command that produced it.");
        Assert.assertEquals(internal.describe(), "{\"kind\":\"internal\",\"message\":\"Unexpected internal failure: "
                + "boom.\",\"suggestion\":"
                + "\"This is a defect in bal discover, not in the arguments. Report it with the command that produced "
                + "it.\"}");
    }
}
