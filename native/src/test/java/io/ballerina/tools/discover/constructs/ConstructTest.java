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

package io.ballerina.tools.discover.constructs;

import io.ballerina.tools.discover.FixtureCorpus;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Every Ballerina syntax dimension, one construct at a time.
 *
 * <p>The recorded corpus answers "did anything move?". This answers the two questions it cannot: WHICH
 * construct moved, and what about constructs no recorded package happens to use. Line coverage does not
 * stand in for this: a defect on a covered line needs an assertion about the language to be caught.
 *
 * <p>Failures come in two kinds and the message says which:
 *
 * <ul>
 *   <li><b>a regression</b> — a construct with no open finding changed, or one with an open finding changed
 *       into something that is still not its declaration. Revert or explain.</li>
 *   <li><b>a fix landed</b> — the output now equals the construct's real declaration. Promote the row:
 *       move {@code shouldRender} into {@code renders} and drop the finding.</li>
 * </ul>
 *
 * @since 0.1.0
 */
public class ConstructTest {

    /** A register ID: the package prefix the audit used, then a two-digit number. */
    private static final Pattern FINDING = Pattern.compile("^[A-Z0-9]+-[0-9]{2}$");

    /**
     * The syntax families this suite claims to cover. An assertion rather than a comment, so deleting the
     * last case of a family fails rather than quietly narrowing what the suite means.
     */
    private static final Set<String> DIMENSIONS = Set.of(
            "records", "fields", "types", "errors", "enums", "constants", "objects", "callables",
            "services", "annotations");

    @DataProvider(name = "constructs")
    public Object[][] constructs() {
        List<Construct> cases = Constructs.all();
        Object[][] rows = new Object[cases.size()][1];
        for (int index = 0; index < cases.size(); index++) {
            rows[index][0] = cases.get(index);
        }
        return rows;
    }

    /**
     * The regression net. One construct, one payload, one exact expected document body.
     *
     * <p>Per-case rather than one big snapshot on purpose: a fix to closed records fails
     * {@code records/closed} and nothing else.
     */
    @Test(dataProvider = "constructs")
    public void aConstructRendersExactlyAsRecorded(Construct construct) {
        String actual = construct.actual();
        if (actual.equals(construct.renders())) {
            return;
        }
        Assert.fail(diagnose(construct, actual));
    }

    private static String diagnose(Construct construct, String actual) {
        boolean fixed = actual.equals(construct.shouldRender());
        StringBuilder message = new StringBuilder();
        message.append(construct.id()).append(" — ").append(construct.claim()).append("\n\n");
        if (fixed) {
            message.append("A FIX LANDED. The output is now the construct's real declaration, which is "
                            + "what ").append(construct.finding())
                    .append(" asked for.\nPromote this row: move `shouldRender` into `renders`, drop the "
                            + "finding, and tick ").append(construct.finding())
                    .append(" off in the fidelity register.\n");
            return message.toString();
        }
        message.append(construct.hasOpenGap()
                        ? "A REGRESSION, on a construct that was ALREADY wrong (" + construct.finding()
                                + "). It changed into something that is still not its declaration.\n"
                        : "A REGRESSION. This construct was faithful and no longer is.\n")
                .append("\n")
                .append(FixtureCorpus.firstDifference(construct.renders(), actual))
                .append("\n\nrecorded:\n")
                .append(indent(construct.renders()))
                .append("\nactual:\n")
                .append(indent(actual));
        if (construct.hasOpenGap()) {
            message.append("\nthe declaration it should be (").append(construct.finding()).append("):\n")
                    .append(indent(construct.shouldRender()));
        }
        return message.toString();
    }

    private static String indent(String text) {
        return text.lines().map(line -> "  | " + line).collect(Collectors.joining("\n")) + "\n";
    }

    @Test
    public void everySyntaxFamilyHasAtLeastOneCase() {
        Set<String> covered = Constructs.all().stream()
                .map(Construct::dimension)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Assert.assertEquals(covered, DIMENSIONS,
                "the suite's claimed coverage and its actual cases disagree");
    }

    @Test
    public void everyCaseIdIsUniqueAndEveryFindingIsWellFormed() {
        Set<String> ids = new LinkedHashSet<>();
        for (Construct construct : Constructs.all()) {
            Assert.assertTrue(ids.add(construct.id()), "duplicate construct id: " + construct.id());
            Assert.assertFalse(construct.claim().isBlank(), construct.id() + " states no claim");
            for (String finding : construct.findings()) {
                Assert.assertTrue(FINDING.matcher(finding).matches(),
                        construct.id() + " names " + finding + ", which is not a register ID");
            }
        }
    }

    /**
     * A construct with no open finding must be faithful, and one with a finding must have a gap. Cheap, and
     * it catches the tempting way to make a failure go away: nulling {@code shouldRender} so the case
     * asserts today's output and claims it is correct.
     */
    @Test
    public void aCaseWithNoFindingClaimsToBeFaithful() {
        for (Construct construct : Constructs.all()) {
            Assert.assertEquals(construct.hasOpenGap(), construct.finding() != null,
                    construct.id() + " disagrees with itself about whether it has an open finding");
        }
    }

    /**
     * Which findings have a construct-level test, by name.
     *
     * <p>A set rather than a count, so a change produces a reviewable diff. A finding LEAVES this list when it
     * is fixed; its cases then assert the right answer as {@code renders}.
     *
     * <p>The one left is not ours to close: Central publishes enum member descriptions but never a member's
     * VALUE ({@code VERIFY-CA}, {@code pgoutput} and {@code all_tables} appear nowhere in postgresql's payload
     * or on Central's own page), so {@code enums/member-values} pins a gap that reading Central more carefully
     * cannot close.
     */
    private static final List<String> PINNED = List.of("PSQL-04");

    @Test
    public void theOpenGapTallyIsWhatTheRegisterSays() {
        List<Construct> cases = Constructs.all();
        long faithful = cases.stream().filter(construct -> !construct.hasOpenGap()).count();
        long broken = cases.size() - faithful;

        Map<String, List<String>> byFinding = new TreeMap<>();
        for (Construct construct : cases) {
            for (String finding : construct.findings()) {
                byFinding.computeIfAbsent(finding, key -> new ArrayList<>()).add(construct.id());
            }
        }

        Assert.assertEquals(cases.size(), 85, "the construct matrix changed size");
        Assert.assertEquals(faithful, 84, "the number of constructs we render faithfully changed");
        Assert.assertEquals(broken, 1, "the number of constructs with an open finding changed");
        Assert.assertEquals(List.copyOf(byFinding.keySet()), PINNED,
                "the set of findings this suite pins changed; case index: " + byFinding);
    }
}
