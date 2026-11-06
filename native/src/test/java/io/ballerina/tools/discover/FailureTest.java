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
 * A failure as either mode writes it: {@code bal discover: error: <message>}, then the suggestion, the command that
 * fixes it and the rest, indented; or one JSON object with {@code "severity":"error"}.
 *
 * @since 0.1.0
 */
public class FailureTest {

    private static final String PREFIX = "bal discover: error: ";

    @Test
    public void aUsageFailureIsTheMessageThenTheSuggestion() {
        Assert.assertEquals(new Failure.Validation("'x' is not a bucket", "The buckets are client.").describeText(),
                PREFIX + "'x' is not a bucket\n  The buckets are client.");
    }

    @Test
    public void aMissingPackageNamesTheCoordinateItLookedFor() {
        Assert.assertEquals(new Failure.PackageNotFound("ballerina/http", "9.9.9", null, "Write one.").describeText(),
                PREFIX + "package not found on Central: ballerina/http:9.9.9\n  Write one.");
        Assert.assertEquals(new Failure.PackageNotFound("ballerina/htp", null, null, "Check it.").describeText(),
                PREFIX + "package not found on Central: ballerina/htp\n  Check it.");
        Assert.assertEquals(new Failure.PackageNotFound("ballerina/graphql", "1.17.0", "nosuch", "List them.")
                        .describeText(),
                PREFIX + "module not found on Central: ballerina/graphql:1.17.0, module nosuch\n  List them.");
    }

    @Test
    public void candidatesAndIssuesGetLinesOfTheirOwn() {
        Assert.assertEquals(new Failure.SymbolNotFound("ballerinax/kafka", "4.6.5", null, List.of("9"),
                        "no readme section of ballerinax/kafka:4.6.5 is numbered or titled '9'", "Pick one.", null,
                        List.of("1. Producer", "2. Consumer")).describeText(),
                PREFIX + "no readme section of ballerinax/kafka:4.6.5 is numbered or titled '9'\n  Pick one.\n"
                        + "  candidates:\n    1. Producer\n    2. Consumer");
        Assert.assertEquals(new Failure.SchemaDrift("ballerinax/kafka", "4.6.5", null,
                        List.of(new Failure.SchemaIssue("docsData.modules", "expected an array")), "Report it.")
                        .describeText(),
                PREFIX + "Central's answer for ballerinax/kafka:4.6.5 has a shape this tool does not understand; "
                        + "Central's docs format may have changed\n  Report it.\n"
                        + "  issues:\n    docsData.modules: expected an array");
    }

    @Test
    public void aMissWithOneFixPutsItsCommandOnATryLine() {
        Failure miss = new Failure.SymbolNotFound("ballerina/graphql", "1.17.0", "subgraph", List.of("Nope"),
                "'Nope' is not a class in ballerina/graphql:1.17.0, module subgraph", "List the classes.",
                "bal discover class -p ballerina/graphql --module subgraph", List.of());
        Assert.assertEquals(miss.describeText(), PREFIX + "'Nope' is not a class in ballerina/graphql:1.17.0, "
                + "module subgraph\n  List the classes.\n  Try: bal discover class -p ballerina/graphql --module "
                + "subgraph");
        Assert.assertEquals(miss.describe(), "{\"severity\":\"error\",\"kind\":\"symbol-not-found\",\"message\":"
                + "\"'Nope' is not a class in ballerina/graphql:1.17.0, module subgraph\",\"qualified\":"
                + "\"ballerina/graphql\",\"version\":\"1.17.0\",\"module\":\"subgraph\",\"requested\":[\"Nope\"],"
                + "\"suggestion\":\"List the classes.\","
                + "\"command\":\"bal discover class -p ballerina/graphql --module subgraph\"}");
    }

    @Test
    public void aRequestToCentralNamesThePackageItWasFor() {
        Failure upstream = new Failure.Upstream("https://x.test/a", 3,
                "Central answered https://x.test/a with HTTP 503", "Retry.", 503, true)
                .about("ballerina/http", "2.15.7");
        Assert.assertEquals(upstream.describe(), "{\"severity\":\"error\",\"kind\":\"unavailable\",\"message\":"
                + "\"Central answered https://x.test/a with HTTP 503 (3 attempts)\",\"qualified\":\"ballerina/http\","
                + "\"version\":\"2.15.7\",\"url\":\"https://x.test/a\",\"attempts\":3,\"suggestion\":\"Retry.\","
                + "\"status\":503,\"reached\":true}");
        Failure timeout = new Failure.Timeout("https://x.test/a", 1000, "Retry.").about("ballerina/http", null);
        Assert.assertEquals(timeout.describe(), "{\"severity\":\"error\",\"kind\":\"timeout\",\"message\":"
                + "\"Central did not answer https://x.test/a within 1 second\",\"qualified\":\"ballerina/http\","
                + "\"url\":\"https://x.test/a\",\"budgetMs\":1000,\"suggestion\":\"Retry.\"}");
    }

    @Test
    public void anUpstreamFailureSaysWhereAndHowOften() {
        Assert.assertEquals(new Failure.Upstream("https://x.test/a", 3,
                "Central answered https://x.test/a with HTTP 503", "Retry.", 503, true).describeText(),
                PREFIX + "Central answered https://x.test/a with HTTP 503 (3 attempts)\n  Retry.");
        Assert.assertEquals(new Failure.Upstream("https://x.test/a", 1, "could not resolve the host x.test", "Retry.",
                null, false).describeText(), PREFIX + "could not resolve the host x.test\n  Retry.");
        Assert.assertEquals(new Failure.Timeout("https://x.test/a", 30000, "Retry.").describeText(),
                PREFIX + "Central did not answer https://x.test/a within 30 seconds\n  Retry.");
        Assert.assertEquals(new Failure.Timeout("https://x.test/a", 1000, "Retry.").describeText(),
                PREFIX + "Central did not answer https://x.test/a within 1 second\n  Retry.");
        Assert.assertEquals(new Failure.Timeout("https://x.test/a", 1500, "Retry.").describeText(),
                PREFIX + "Central did not answer https://x.test/a within 1.5 seconds\n  Retry.");
    }

    @Test
    public void anInternalFailureIsWrittenLikeAnyOtherInEitherMode() {
        Failure internal = new Failure.Internal("boom", Failure.INTERNAL_SUGGESTION);
        Assert.assertEquals(internal.describeText(), PREFIX + "unexpected internal failure: boom\n  This is a defect "
                + "in bal discover, not in the arguments. Report it with the command that produced it.");
        Assert.assertEquals(internal.describe(), "{\"severity\":\"error\",\"kind\":\"internal\",\"message\":"
                + "\"unexpected internal failure: boom\",\"suggestion\":"
                + "\"This is a defect in bal discover, not in the arguments. Report it with the command that produced "
                + "it.\"}");
        Assert.assertEquals(new Failure.Internal("", Failure.INTERNAL_SUGGESTION).headline(),
                "unexpected internal failure");
    }

    @Test
    public void aUsageErrorAndAnAmbiguousNameExit2AndEveryOtherFailureExits1() {
        Assert.assertEquals(new Failure.Validation("m", "s").coreKind(), "usage");
        Assert.assertEquals(new Failure.Ambiguous("a/b", "1.0.0", null, List.of("x"), "m", "s", Failure.OWNERS,
                List.of()).coreKind(), "usage");
        Assert.assertEquals(new Failure.Internal("m", "s").coreKind(), "runtime");
    }

    @Test
    public void aWarningIsItsOwnReportInEitherMode() {
        Warning warning = new Warning(Warning.STALE_CACHE, "Central could not be reached");
        Assert.assertEquals(warning.describe(false), "bal discover: warning: Central could not be reached");
        Assert.assertEquals(warning.describe(true),
                "{\"severity\":\"warning\",\"kind\":\"stale-cache\",\"message\":\"Central could not be reached\"}");
    }
}
