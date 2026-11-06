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

import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.views.Readme;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;

/**
 * How a readme is cut into sections, and what a bare, selected or filtered {@code readme} answers.
 *
 * @since 0.1.0
 */
public class ReadmeSectionsTest {

    private static LoadedPackage withReadme(String markdown) {
        LoadedPackage base = FixtureCorpus.loadedFixture("ballerinax__kafka");
        return new LoadedPackage(base.qualified(), base.version(), base.library(), Optional.of(markdown),
                base.module(), base.submodules(), base.modules(), base.warning(), base.pinned(), base.bound(),
                base.handlers());
    }

    private static List<String> titles(String markdown) {
        return Readme.sectionsOf(withReadme(markdown)).stream().map(Readme.Section::title).toList();
    }

    @Test
    public void everyHeadingOpensASectionProseOnlyOnesIncluded() {
        Assert.assertEquals(titles("intro\n\n# A\ntext\n## B\n```\ncode\n```\n### C\nmore"),
                List.of("Introduction", "A", "B", "C"));
    }

    @Test
    public void aReadmeWithWindowsLineEndingsIsCutIntoSectionsToo() {
        Assert.assertEquals(titles("# A\r\ntext\r\n## B\r\nmore\r\n"), List.of("A", "B"));
    }

    @Test
    public void aLineOfInlineCodeStartingWithBackticksOpensNoFence() {
        Assert.assertEquals(titles("# A\n```x``` is inline\n# B\ntext"), List.of("A", "B"));
    }

    @Test
    public void aReadmeOpeningWithAHeadingHasNoIntroduction() {
        Assert.assertEquals(titles("# A\ntext"), List.of("A"));
    }

    @Test
    public void aHashtagOrIndentedCodeLineIsNotAHeading() {
        Assert.assertEquals(titles("# A\n#hashtag is prose\n    # indented code\n## B"), List.of("A", "B"));
    }

    @Test
    public void closingHashesAndEmptyHeadingsAreRead() {
        Assert.assertEquals(titles("## A ##\ntext\n#\ntext"), List.of("A", "Untitled"));
    }

    @Test
    public void aHeadingLookalikeInsideABacktickOrTildeFenceDoesNotSplit() {
        Assert.assertEquals(titles("# A\n```bash\n# comment\n```\n# B\n~~~\n# not a heading\n~~~\n# C"),
                List.of("A", "B", "C"));
    }

    @Test
    public void aFenceIsClosedOnlyByTheSameMarkerAtLeastAsLong() {
        Assert.assertEquals(titles("# A\n````\n```\n# inside\n~~~\n````\n# B"), List.of("A", "B"));
        Assert.assertEquals(titles("# A\n~~~\n```\n# inside\n~~~\n# B"), List.of("A", "B"));
    }

    @Test
    public void aSmallReadmeIsPrintedWhole() {
        DiscoverResult result = Readme.render(withReadme("# A\ntext\n# B\nmore"), Readme.Options.BARE).value();
        Assert.assertTrue(result instanceof DiscoverResult.Readme, result.toString());
    }

    @Test
    public void aLargeReadmeListsEverySectionUncapped() {
        StringBuilder markdown = new StringBuilder();
        int sections = 60;
        for (int i = 1; i <= sections; i++) {
            markdown.append("# Section ").append(i).append('\n').append("x".repeat(Readme.WHOLE_LIMIT_BYTES / 40))
                    .append('\n');
        }
        DiscoverResult result = Readme.render(withReadme(markdown.toString()), Readme.Options.BARE).value();
        DiscoverResult.ReadmeSections listing = (DiscoverResult.ReadmeSections) result;
        Assert.assertEquals(listing.sections().size(), sections);
        Assert.assertEquals(listing.total(), sections);
        Assert.assertNull(listing.paging());
        Assert.assertNull(listing.next());
        Assert.assertFalse(listing.matching());
        Assert.assertEquals(listing.sections().get(59).command(), "bal discover readme 60 -p ballerinax/kafka");
    }

    @Test
    public void aTitleSeveralSectionsShareIsAmbiguousListingEachWithItsNumber() {
        LoadedPackage loaded = withReadme("# Example\none\n# Other\n# Example\ntwo");
        Result<DiscoverResult> view = Readme.render(loaded, new Readme.Options("example", null, 1));
        Failure.Ambiguous failure = (Failure.Ambiguous) view.failure();
        Assert.assertEquals(failure.exitCode(), Failure.USAGE);
        Assert.assertEquals(failure.matchesKey(), Failure.SECTIONS);
        Assert.assertEquals(failure.matches(), List.of(
                new Failure.Match("1. Example", "bal discover readme 1 -p ballerinax/kafka"),
                new Failure.Match("3. Example", "bal discover readme 3 -p ballerinax/kafka")));
        Assert.assertTrue(failure.describe().contains("\"sections\":[{\"name\":\"1. Example\""),
                failure.describe());
    }

    @Test
    public void aFilterMatchesTheWayEveryOtherFilterDoes() {
        LoadedPackage loaded = withReadme("# A\nsend a message\n# B\nnothing");
        DiscoverResult.Readme only = (DiscoverResult.Readme) Readme.render(
                loaded, new Readme.Options(null, "Send  ", 1)).value();
        Assert.assertEquals(only.title(), "A");
    }
}
