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

import io.ballerina.tools.discover.model.TypeDef;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.TypeDefs;
import io.ballerina.tools.discover.symbols.Declarations;
import io.ballerina.tools.discover.symbols.Names;
import io.ballerina.tools.discover.symbols.PathTree;
import io.ballerina.tools.discover.symbols.Surface;
import io.ballerina.tools.discover.views.Closure;
import io.ballerina.tools.discover.views.Containers;
import io.ballerina.tools.discover.views.Types;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * THE test that makes the addressed verbs safe, and a reviewer should refuse them without it.
 *
 * <p>The risk the verbs introduce is not that a document looks wrong — it is that one of them shows a signature the
 * package does not have, while {@code api} shows the right one, and nothing in either document says they disagree.
 * An agent then writes code against a signature that came from a summariser's shortcut. The committed {@code api}
 * snapshots are the oracle: they are byte-exact against the recorded payloads, so anything a view emits has to be
 * findable in them verbatim.
 *
 * <p>This is a GATE, not a suite. A summariser permitted to invent a shorter spelling must pick one, and no test
 * written afterwards can catch a spelling nothing else in the tool produces — which is exactly what a hand-written
 * design sample did four times in one snippet: {@code 'key} lost its apostrophe, a type name was shortened to one
 * that does not exist, an included-record parameter became two invented ones, and {@code isolated resource
 * function} was dropped along with the {@code ->} call form it implies.
 *
 * <p>Five properties, over every fixture:
 *
 * <ol>
 *   <li>every declaration a single-result answer quotes — its own, and every type it names — appears in that
 *       fixture's {@code api} snapshot;
 *   <li>every {@code type <Name>} body is {@code renderTypeDef} of that declaration exactly;
 *   <li>every declaration resolves through {@code type}, and every name {@code type} resolves is in the index —
 *       in both directions;
 *   <li>every path the tree offers is reachable, and every path reached is one the tree offers;
 *   <li>closures terminate, do not repeat a declaration, and stay inside their budget.
 * </ol>
 *
 * @since 0.1.0
 */
public class ViewsAgreeTest {

    @DataProvider(name = "fixtures")
    public Object[][] fixtures() {
        return FixtureCorpus.fixtureRows();
    }

    private static Set<String> snapshotLines(String slug) {
        Set<String> lines = new HashSet<>();
        for (String line : FixtureCorpus.readSnapshot(slug).split("\n", -1)) {
            lines.add(line.stripLeading());
        }
        return lines;
    }

    /** Every path in the tree, as token lists. */
    private static List<List<String>> allPaths(PathTree node, List<String> prefix) {
        List<List<String>> paths = new ArrayList<>();
        for (PathTree child : node.children()) {
            List<String> path = new ArrayList<>(prefix);
            path.add(child.segment());
            paths.add(path);
            paths.addAll(allPaths(child, path));
        }
        return paths;
    }

    private static DiscoverResult expectAnswer(Result<DiscoverResult> view, String what) {
        Assert.assertTrue(view.isOk(), what + " failed: "
                + (view.isOk() ? "" : view.failure().describe()));
        return view.value();
    }

    // -----------------------------------------------------------------------
    // 1. Signatures agree with the API document
    // -----------------------------------------------------------------------

    /** Across every fixture — a single fixture legitimately checks zero, see below. */
    private static final AtomicInteger TOTAL_CHECKED = new AtomicInteger();

    /** How many single-callable answers the corpus walk reached, across every fixture. */
    private static final AtomicInteger SIGNATURES = new AtomicInteger();

    /**
     * Below the corpus's own count — every named member plus up to {@value #OPERATIONS_PER_CONTAINER} operations
     * per container — by a margin, so adding a fixture never fails this, but a selector grammar that quietly stops
     * resolving members does.
     */
    private static final int MINIMUM_SIGNATURES = 900;

    /** How many resource operations per container are drilled into — github alone has 903. */
    private static final int OPERATIONS_PER_CONTAINER = 25;

    /**
     * Every single-result answer: every named member of every container, and the first
     * {@value #OPERATIONS_PER_CONTAINER} resource operations of each, drilled into one at a time.
     */
    @Test(dataProvider = "fixtures")
    public void everyDeclarationASingleResultQuotesIsInTheApiSnapshotVerbatim(String slug) {
        Set<String> snapshot = snapshotLines(slug);
        LoadedPackage context = FixtureCorpus.loadedFixture(slug);

        for (Surface.Scope scope : Surface.Scope.values()) {
            for (Surface.Container container : Surface.of(context.library(), scope)) {
                List<String> owner = container.isModule() ? List.of() : List.of(container.name());
                List<List<String>> selectors = new ArrayList<>();
                container.memberNames().forEach(name -> selectors.add(with(owner, name)));
                container.operations().stream().limit(OPERATIONS_PER_CONTAINER).forEach(operation ->
                        selectors.add(with(owner, operation.fn().accessor(), String.join("/", operation.segments()))));
                for (List<String> selector : selectors) {
                    DiscoverResult answer = expectAnswer(Containers.render(context, scope,
                            new Containers.Options(selector)), scope.verb() + " " + selector);
                    // An exact member name or an exact path plus its accessor names ONE callable; anything else
                    // here is the selector grammar failing to reach something the package declares.
                    Assert.assertTrue(answer instanceof DiscoverResult.Signature, slug + " " + scope.verb() + " "
                            + selector + " did not resolve to one callable: " + answer.getClass().getSimpleName());
                    DiscoverResult.Signature signature = (DiscoverResult.Signature) answer;
                    SIGNATURES.incrementAndGet();
                    List<String> quoted = new ArrayList<>(List.of(signature.declaration().split("\n", -1)));
                    signature.types().forEach(type -> quoted.addAll(List.of(type.declaration().split("\n", -1))));
                    for (String line : quoted) {
                        if (line.isBlank()) {
                            continue;
                        }
                        Assert.assertTrue(snapshot.contains(line.stripLeading()),
                                slug + " " + scope.verb() + " " + selector + " quotes a line api does not:\n  "
                                        + line);
                        TOTAL_CHECKED.incrementAndGet();
                    }
                }
            }
        }
    }

    private static List<String> with(List<String> prefix, String... more) {
        List<String> selector = new ArrayList<>(prefix);
        selector.addAll(List.of(more));
        return selector;
    }

    @AfterClass(alwaysRun = true)
    public void atLeastOneSignatureWasActuallyChecked() {
        Assert.assertTrue(TOTAL_CHECKED.get() > 0,
                "nothing was checked across any fixture, so the test above passed vacuously");
        Assert.assertTrue(SIGNATURES.get() >= MINIMUM_SIGNATURES,
                SIGNATURES.get() + " single-callable answers checked, fewer than " + MINIMUM_SIGNATURES);
    }

    // -----------------------------------------------------------------------
    // 3 and 4. Declarations resolve by name, and print exactly
    // -----------------------------------------------------------------------

    @Test(dataProvider = "fixtures")
    public void everyDeclarationResolvesThroughTypeAndPrintsIdentically(String slug) {
        LoadedPackage context = FixtureCorpus.loadedFixture(slug);
        Declarations index = Declarations.index(context.library().typeDefs());
        Assert.assertFalse(index.names().isEmpty());

        for (String name : index.names()) {
            TypeDef typeDef = index.get(name);
            Assert.assertNotNull(typeDef);
            Result<DiscoverResult> view = Types.render(context, new Types.Options(List.of(name), null, 1));
            Assert.assertTrue(view.isOk(), "type could not resolve " + name + ", which the index holds");
            if (typeDef instanceof TypeDef.ObjectDef) {
                Assert.assertFalse(view.value() instanceof DiscoverResult.TypeDeclaration,
                        "type " + name + " is an object, which another bucket answers");
                continue;
            }
            // Byte-exact: `type` IS `renderTypeDef`, so anything else means a view started reformatting.
            Assert.assertEquals(((DiscoverResult.TypeDeclaration) view.value()).declaration(),
                    TypeDefs.renderTypeDef(typeDef), "type " + name + " did not print renderTypeDef's output");
        }
    }

    @Test(dataProvider = "fixtures")
    public void nothingOutsideTheDeclarationIndexResolvesThroughType(String slug) {
        LoadedPackage context = FixtureCorpus.loadedFixture(slug);
        Declarations index = Declarations.index(context.library().typeDefs());
        Set<String> held = new HashSet<>(index.names());
        // The other direction. Scoped to DECLARATIONS deliberately: operations are addressed by path and `type`
        // does not take one, so demanding a bijection over all of github's 8,837 symbols would be unsatisfiable.
        for (String invented : new String[] {"NoSuchDeclarationAnywhere", "zzzz", "__", "Client Name"}) {
            if (held.contains(invented)) {
                continue;
            }
            Result<DiscoverResult> view = Types.render(context, new Types.Options(List.of(invented), null, 1));
            Assert.assertFalse(view.isOk(), invented + " must not resolve");
            Assert.assertTrue(view.failure() instanceof Failure.SymbolNotFound);
        }
    }

    @Test
    public void aNameThatNormalisesOntoSeveralDeclarationsIsAFailureNeverASilentPick() {
        // Real, not theoretical: `ballerina/http` has 61 constant-versus-class collisions of the
        // STATUS_ACCEPTED / StatusAccepted shape.
        Declarations index = Declarations.index(FixtureCorpus.libraryFor("ballerina__http").typeDefs());
        List<String> collisions = index.names().stream()
                .filter(name -> Names.match(name.toLowerCase(java.util.Locale.ROOT), index.names())
                        instanceof Names.Match.Ambiguous)
                .toList();
        Assert.assertFalse(collisions.isEmpty(),
                "the corpus should contain at least one normalisation collision");

        Result<DiscoverResult> view = Types.render(
                FixtureCorpus.loadedFixture("ballerina__http"),
                new Types.Options(List.of(collisions.get(0).toLowerCase(java.util.Locale.ROOT)), null, 1));
        Assert.assertFalse(view.isOk());
        Failure.SymbolNotFound failure = (Failure.SymbolNotFound) view.failure();
        Assert.assertTrue(failure.candidates().size() > 1, "every colliding name has to be listed");
        // A COLLISION and a MISS both arrive with several candidates, and they need different advice: telling the
        // caller "several declarations match" when none did sends them to re-run with a name they never asked for.
        Assert.assertTrue(failure.suggestion().contains("normalise to the same name"), failure.suggestion());
    }

    @Test
    public void aMissIsNotDescribedAsACollisionEvenThoughBothCarryCandidates() {
        Result<DiscoverResult> view = Types.render(
                FixtureCorpus.loadedFixture("ballerina__http"),
                new Types.Options(List.of("NoSuchType"), null, 1));
        Assert.assertFalse(view.isOk());
        Failure.SymbolNotFound failure = (Failure.SymbolNotFound) view.failure();
        Assert.assertFalse(failure.candidates().isEmpty(), "near misses are still offered");
        Assert.assertTrue(failure.suggestion().startsWith("No declaration matched."), failure.suggestion());
        Assert.assertTrue(failure.suggestion().contains("type --filter <keyword>"), failure.suggestion());
        Assert.assertFalse(failure.suggestion().contains("normalise to the same name"));
    }

    // -----------------------------------------------------------------------
    // 5. Every path the tree offers is reachable
    // -----------------------------------------------------------------------

    @Test(dataProvider = "fixtures")
    public void everyPathTheTreeOffersIsReachableByAContainerVerb(String slug) {
        // Hoisted: the view takes the loaded package, and building it inside the per-path loop would re-derive a
        // 12.4MB fixture once for each of github's 900-odd tree paths.
        LoadedPackage context = FixtureCorpus.loadedFixture(slug);
        for (Surface.Container container : Surface.of(context.library(), Surface.Scope.CLIENT)) {
            List<PathTree.Operation> operations = container.operations();
            if (operations.isEmpty()) {
                continue;
            }
            PathTree tree = PathTree.build(operations);
            for (List<String> path : allPaths(tree, List.of())) {
                // A navigation affordance that dead-ends is a test failure, not a wasted agent turn.
                Assert.assertTrue(PathTree.resolve(tree, path) instanceof PathTree.Resolution.Found,
                        container.name() + ": " + String.join("/", path) + " is offered but unreachable");
                expectAnswer(Containers.render(context, Surface.Scope.CLIENT, new Containers.Options(
                                List.of(container.name(), String.join("/", path)))),
                        container.name() + " " + String.join("/", path));
            }
        }
    }

    @Test(dataProvider = "fixtures")
    public void everyPathTheTreeAcceptsIsOneItOffers(String slug) {
        // The OTHER direction of the same property. Tree→verb proves no affordance dead-ends; verb→tree proves
        // nothing is reachable that the tree never showed, which is what would make a path an agent could stumble
        // into but never be told about.
        LoadedPackage context = FixtureCorpus.loadedFixture(slug);
        for (Surface.Container container : Surface.of(context.library(), Surface.Scope.CLIENT)) {
            List<PathTree.Operation> operations = container.operations();
            if (operations.isEmpty()) {
                continue;
            }
            PathTree tree = PathTree.build(operations);
            List<List<String>> paths = allPaths(tree, List.of());
            Set<String> offered = new HashSet<>();
            paths.forEach(path -> offered.add(String.join("/", path)));

            for (List<String> path : paths) {
                PathTree.Resolution resolution = PathTree.resolve(tree, path);
                Assert.assertTrue(resolution instanceof PathTree.Resolution.Found);
                String landed = String.join("/", ((PathTree.Resolution.Found) resolution).path());
                Assert.assertTrue(offered.contains(landed), "resolved to an unoffered path: " + landed);
            }

            // A wildcard cannot reach anything the tree does not hold either.
            for (List<String> path : paths) {
                List<String> wildcarded = path.stream()
                        .map(segment -> segment.startsWith("{") ? "*" : segment)
                        .toList();
                PathTree.Resolution resolution = PathTree.resolve(tree, wildcarded);
                if (!(resolution instanceof PathTree.Resolution.Found found)) {
                    continue;
                }
                Assert.assertTrue(offered.contains(String.join("/", found.path())),
                        "a wildcard reached an unoffered path: " + String.join("/", found.path()));
            }

            // And neither can segment LOCATION, which is the one relaxation of anchoring in the design. It may
            // land deeper than the request; it may never land somewhere the tree does not offer.
            for (List<String> path : paths) {
                PathTree.Located located = PathTree.locate(tree, path);
                if (located.resolution() instanceof PathTree.Resolution.Found found) {
                    Assert.assertTrue(offered.contains(String.join("/", found.path())),
                            "location reached an unoffered path: " + String.join("/", found.path()));
                }
                for (List<String> alternative : located.alternatives()) {
                    Assert.assertTrue(offered.contains(String.join("/", alternative)),
                            "location offered an unoffered path: " + String.join("/", alternative));
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 5. Closures terminate, do not repeat, and stay bounded
    // -----------------------------------------------------------------------

    @Test(dataProvider = "fixtures")
    public void leafClosuresTerminateAndNeverRepeatForEveryDeclaration(String slug) {
        LoadedPackage context = FixtureCorpus.loadedFixture(slug);
        Declarations index = Declarations.index(context.library().addressable());
        for (String name : index.names()) {
            // A record whose field is an array of itself is ordinary, so this is a real cycle risk rather than a
            // hypothetical one. The walk returning at all is the assertion; an unguarded one would not.
            Closure.Result closure = Closure.leaf(List.of(name), index);
            Assert.assertEquals(new HashSet<>(closure.names()).size(), closure.names().size(),
                    "the closure repeated a declaration for " + name);
            Assert.assertTrue(closure.names().isEmpty() || closure.names().get(0).equals(name),
                    "the root comes first for " + name);
        }
    }

    /**
     * The closure is BOUNDED, and anything it dropped is NAMED.
     *
     * <p>T7. {@code ClientConfiguration}'s closure was 38 declarations and 24,183 bytes handed back whole, with no
     * bound at all. A budget that dropped names silently would be worse than the dump; a name is a legal
     * {@code type} argument, so naming them keeps a truncated closure actionable.
     */
    @Test(dataProvider = "fixtures")
    public void aTruncatedClosureNamesEveryTypeItDroppedAndEachIsResolvable(String slug) {
        LoadedPackage context = FixtureCorpus.loadedFixture(slug);
        Declarations index = Declarations.index(context.library().addressable());
        int truncated = 0;
        for (String name : index.names()) {
            Closure.Result closure = Closure.of(List.of(name), index, Closure.LEAF_BYTES, Closure.UNBOUNDED);
            if (!closure.truncated()) {
                continue;
            }
            truncated++;
            for (String omitted : closure.omitted()) {
                Assert.assertNotNull(index.get(omitted), name + " omitted an unresolvable name: " + omitted);
                Assert.assertFalse(closure.names().contains(omitted),
                        name + " listed " + omitted + " as omitted and printed it too");
            }
        }
        // Not every fixture has a closure large enough to truncate, so this is a report rather than a floor —
        // but `ballerina/http` does, and that is the case the budget was measured against.
        if ("ballerina__http".equals(slug)) {
            Assert.assertTrue(truncated > 0, "http's ClientConfiguration closure was 24,183 bytes unbounded");
        }
    }

    @Test
    public void anInclusionDoesNotConsumeALeafsDepth() {
        // `ClientConfiguration` is `*CommonClientConfiguration` plus one field; the included record's own field
        // types are the configuration a caller sets, and would be hidden one level further down otherwise.
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        DiscoverResult.TypeDeclaration config = (DiscoverResult.TypeDeclaration) expectAnswer(
                Types.render(http, new Types.Options(List.of("ClientConfiguration"), null, 1)),
                "type ClientConfiguration");
        List<String> named = config.types().stream().map(DiscoverResult.Signature.Type::name).toList();
        Assert.assertTrue(named.contains("CommonClientConfiguration"), named.toString());
        Assert.assertTrue(named.contains("HttpVersion"), "a field of the included record: " + named);
    }

    @Test
    public void aSingleResultReachesTheIncludedRecordParameterItNames() {
        // The caches DELETE on github takes `*ActionsDeleteActionsCacheByKeyQueries` — an included record whose
        // FIELDS are the call's named arguments — and no signature line spells those out, so the flow used to cost
        // two calls and the design sample that skipped it invented two parameters instead.
        LoadedPackage context = FixtureCorpus.loadedFixture("ballerinax__github");
        DiscoverResult view = expectAnswer(Containers.render(context, Surface.Scope.CLIENT,
                new Containers.Options(List.of("Client", "delete", "repos/{owner}/{repo}/actions/caches"))),
                "caches delete");
        Assert.assertTrue(view instanceof DiscoverResult.Signature, view.toString());
        DiscoverResult.Signature signature = (DiscoverResult.Signature) view;
        Assert.assertTrue(signature.declaration().contains(
                "resource function delete repos/[string owner]/[string repo]/actions/caches("),
                signature.declaration());
        Assert.assertTrue(signature.declaration().contains("*ActionsDeleteActionsCacheByKeyQueries queries"),
                signature.declaration());
        String types = signature.types().stream()
                .map(DiscoverResult.Signature.Type::declaration)
                .reduce("", (left, right) -> left + "\n" + right);
        Assert.assertTrue(types.contains("public type ActionsDeleteActionsCacheByKeyQueries record"), types);
        Assert.assertTrue(types.contains("public type ActionsCacheList record"), types);
        // And the field the design sample re-spelled as `key` keeps its apostrophe, because it is quoted rather
        // than re-written: `key` is a Ballerina keyword.
        Assert.assertTrue(types.contains("'key?;"), types);
    }

    @Test
    public void aLeafFollowsAChainInBreadthFirstOrderAndStopsAtThePackageBoundary() {
        DiscoverResult.TypeDeclaration error = (DiscoverResult.TypeDeclaration) expectAnswer(
                Types.render(FixtureCorpus.loadedFixture("ballerina__http"),
                        new Types.Options(List.of("ClientRequestError"), null, 1)), "type ClientRequestError");
        List<String> order = error.types().stream().map(DiscoverResult.Signature.Type::name).toList();
        // The root, then everything it names: a shallow field can no longer be pushed past the budget by a deep
        // one, and the chain a reader needs is complete.
        Assert.assertTrue(order.contains("ApplicationResponseError"), order.toString());
        Assert.assertFalse(order.contains("ClientRequestError"), "the root is the declaration, not a named type");
        Assert.assertTrue(order.contains("Detail"), "the detail record the patch unlocked has to be reachable");
    }
}
