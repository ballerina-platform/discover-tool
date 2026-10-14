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

package io.ballerina.tools.discover.views;

import io.ballerina.tools.discover.FixtureCorpus;
import io.ballerina.tools.discover.LoadedPackage;
import io.ballerina.tools.discover.QualifiedName;
import io.ballerina.tools.discover.constructs.Payload;
import io.ballerina.tools.discover.model.Library;
import io.ballerina.tools.discover.model.Pipeline;
import io.ballerina.tools.discover.render.DiscoverResult;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a readme's code is, the fence rules {@link Readme} and {@link ReadmeCalls} share, and the uses
 * {@link ReadmeUsage} counts in it.
 *
 * @since 0.1.0
 */
public class ReadmeCodeTest {

    @Test
    public void anUnclosedFenceRunsToTheEnd() {
        Assert.assertEquals(Readme.fencedCodeOf("Prose.\n```ballerina\nint a = 1;\n\nint b = 2;"),
                "int a = 1;\n\nint b = 2;\n");
    }

    @Test
    public void aFenceClosesOnlyOnTheSameMarkerAtLeastAsLong() {
        Assert.assertEquals(Readme.fencedCodeOf("````md\n```\ninner\n```\n`````\nprose"), "```\ninner\n```\n");
        Assert.assertEquals(Readme.fencedCodeOf("~~~\n```\nkept\n~~~\nprose"), "```\nkept\n");
    }

    @Test
    public void aLineOfInlineCodeIsNotAFence() {
        Assert.assertEquals(Readme.fencedCodeOf("```inline``` prose\nmore prose"), "");
        Assert.assertEquals(Readme.codeOf("```inline``` prose"), "inline\n");
    }

    @Test
    public void aDoubleBacktickSpanHoldsABacktick() {
        Assert.assertEquals(Readme.codeOf("Write ``a ` b`` here."), "a ` b\n");
    }

    @Test
    public void anUnbalancedBacktickStaysInsideItsParagraph() {
        Assert.assertEquals(Readme.codeOf("It's ` alone.\n\nThen `Status` here."), "Status\n");
        Assert.assertEquals(Readme.codeOf("A span `split\nacross` lines."), "split\nacross\n");
    }

    @Test
    public void anEscapedBacktickOpensNoSpan() {
        Assert.assertEquals(Readme.codeOf("An escaped \\` mark, then `Name`."), "Name\n");
    }

    @Test
    public void aForeignQualifiedRowMatchesItsQualifiedUse() {
        Map<String, Integer> counts = ReadmeUsage.counts(withReadme("```ballerina\nservice cdc:Service on l {}\n"
                + "http:Client c = check new;\nClient d = check new;\npkg:Client e = check new;\n```"));
        Assert.assertEquals(counts.get("cdc:Service"), Integer.valueOf(1));
        Assert.assertEquals(counts.get("Client"), Integer.valueOf(2));
        Assert.assertEquals(counts.get("http:Client"), Integer.valueOf(1));
        List<DiscoverResult.Overview.Row> rows = List.of(row("Other"), row("cdc:Service"));
        Assert.assertEquals(ReadmeUsage.ranked(rows, counts).stream().map(DiscoverResult.Overview.Row::name).toList(),
                List.of("cdc:Service", "Other"));
    }

    private static DiscoverResult.Overview.Row row(String name) {
        return new DiscoverResult.Overview.Row(name, 0, 0, 0, "");
    }

    private static LoadedPackage withReadme(String readme) {
        Library library = Pipeline.build(Payload.pkg().module());
        return new LoadedPackage(QualifiedName.parse("test/pkg").value(), FixtureCorpus.FIXTURE_VERSION, library,
                Optional.of(readme), null, List.of(), List.of(), null, null, () -> library, Map::of);
    }
}
