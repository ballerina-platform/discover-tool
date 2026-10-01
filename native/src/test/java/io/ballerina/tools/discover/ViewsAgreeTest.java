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
import io.ballerina.tools.discover.views.TypeView;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            Result<String> view = TypeView.render(context, new TypeView.Options(List.of(name), false));
            Assert.assertTrue(view.isOk(), "type could not resolve " + name + ", which the index holds");
            TypeDef typeDef = index.get(name);
            Assert.assertNotNull(typeDef);
            // Byte-exact, not merely "contains": `type` IS `renderTypeDef` plus a header, so anything else means a
            // view started reformatting declarations.
            Assert.assertTrue(view.value().contains("\n" + TypeDefs.renderTypeDef(typeDef) + "\n"),
                    "type " + name + " did not print renderTypeDef's output exactly");
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
            Result<String> view = TypeView.render(context, new TypeView.Options(List.of(invented), false));
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

        Result<String> view = TypeView.render(
                FixtureCorpus.loadedFixture("ballerina__http"),
                new TypeView.Options(List.of(collisions.get(0).toLowerCase(java.util.Locale.ROOT)), false));
        Assert.assertFalse(view.isOk());
        Failure.SymbolNotFound failure = (Failure.SymbolNotFound) view.failure();
        Assert.assertTrue(failure.candidates().size() > 1, "every colliding name has to be listed");
        // A COLLISION and a MISS both arrive with several candidates, and they need different advice: telling the
        // caller "several declarations match" when none did sends them to re-run with a name they never asked for.
        Assert.assertTrue(failure.suggestion().contains("normalise to the same name"), failure.suggestion());
    }

    @Test
    public void aMissIsNotDescribedAsACollisionEvenThoughBothCarryCandidates() {
        Result<String> view = TypeView.render(
                FixtureCorpus.loadedFixture("ballerina__http"),
                new TypeView.Options(List.of("NoSuchType"), false));
        Assert.assertFalse(view.isOk());
        Failure.SymbolNotFound failure = (Failure.SymbolNotFound) view.failure();
        Assert.assertFalse(failure.candidates().isEmpty(), "near misses are still offered");
        Assert.assertTrue(failure.suggestion().startsWith("No declaration matched."), failure.suggestion());
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

    /**
     * The name a declaration line declares.
     *
     * <p>{@code public} is not optional in the pattern even though every declaration carries it, and the
     * non-emptiness assertion below is why: when the qualifier landed, an anchored {@code ^type} stopped matching
     * and this test went green over an empty list. A duplicate-detection test that finds no declarations passes for
     * the same reason it would pass on an empty document.
     */
    private static final Pattern DECLARED = Pattern.compile(
            "^public (?:type|enum|(?:[a-z]+ )*class) ([A-Za-z0-9_']+)"
                    + "|^public const (?:[^\\s=]+ )?([A-Za-z0-9_']+) ="
                    // `public final <type> <name> = <init>;` — the module-level variable form. Its type may
                    // contain spaces (`readonly & Continue`), so the name is the token before ` = ` or `;`.
                    + "|^public final .+?([A-Za-z0-9_']+)(?: = |;)",
            Pattern.MULTILINE);

    @Test(dataProvider = "fixtures")
    public void resolvedClosuresTerminateForEveryDeclaration(String slug) {
        LoadedPackage context = FixtureCorpus.loadedFixture(slug);
        Declarations index = Declarations.index(context.library().typeDefs());
        for (String name : index.names()) {
            // A record whose field is an array of itself is ordinary, so this is a real cycle risk rather than a
            // hypothetical one. The view returning at all is the assertion; an unguarded walk would not.
            Result<String> view = TypeView.render(context, new TypeView.Options(List.of(name), true));
            Assert.assertTrue(view.isOk(), "-r failed on " + name);

            // And no declaration is printed twice inside one closure. `const` puts the TYPE before the name, so a
            // naive capture reads `string` three times and reports a duplicate that is not one.
            List<String> declared = new ArrayList<>();
            Matcher matcher = DECLARED.matcher(view.value());
            while (matcher.find()) {
                declared.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
            }
            Assert.assertFalse(declared.isEmpty(),
                    "-r printed no declaration line this pattern recognises for " + name
                            + "; a duplicate check over an empty list passes vacuously");
            Assert.assertEquals(new HashSet<>(declared).size(), declared.size(),
                    "-r repeated a declaration for " + name);
        }
    }

    /**
     * The closure is BOUNDED, and anything it dropped is NAMED.
     *
     * <p>T7. {@code type ballerina/http ClientConfiguration --deps} was 38 declarations, 505 lines and 24,183
     * bytes handed back whole, with no bound at all. A budget that dropped names silently would be worse than the
     * dump; a name is a legal {@code type} argument, so naming them keeps a truncated closure actionable.
     */
    @Test(dataProvider = "fixtures")
    public void aTruncatedClosureNamesEveryTypeItDroppedAndEachIsResolvable(String slug) {
        LoadedPackage context = FixtureCorpus.loadedFixture(slug);
        Declarations index = Declarations.index(context.library().addressable());
        int truncated = 0;
        for (String name : index.names()) {
            Closure.Result closure =
                    Closure.of(List.of(name), index, Closure.MAX_BYTES, Closure.UNBOUNDED);
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
    public void depsFollowsAChainInOrderAndStopsAtThePackageBoundary() {
        Result<String> view = TypeView.render(
                FixtureCorpus.loadedFixture("ballerina__http"),
                new TypeView.Options(List.of("ClientRequestError"), true));
        Assert.assertTrue(view.isOk());
        List<String> order = new ArrayList<>();
        Matcher matcher =
                Pattern.compile("^public type ([A-Za-z0-9_]+) ", Pattern.MULTILINE).matcher(view.value());
        while (matcher.find()) {
            order.add(matcher.group(1));
        }
        Assert.assertTrue(order.size() >= 4, "the closure printed " + order.size() + " declarations: " + order);
        // BREADTH-FIRST now, where it used to be depth-first: the root, then everything it names, then their
        // references. The chain is still complete and still in a reader's order; what changed is that a shallow
        // field can no longer be pushed past the budget by a deep one.
        Assert.assertEquals(order.get(0), "ClientRequestError");
        Assert.assertTrue(order.contains("ApplicationResponseError"), order.toString());
        Assert.assertTrue(order.contains("ClientError"), order.toString());
        Assert.assertTrue(order.contains("Detail"), "the detail record the patch unlocked has to be reachable");
    }

    @Test
    public void depsNamesCrossPackageEdgesInsteadOfFetchingThem() {
        // `http:ConnectionConfig` has a LOCAL closure of one and fifteen external edges; crossing the boundary
        // would hide a five-second cold fetch inside an answer the caller expects to be warm.
        Result<String> view = TypeView.render(
                FixtureCorpus.loadedFixture("ballerinax__github"),
                new TypeView.Options(List.of("ConnectionConfig"), true));
        Assert.assertTrue(view.isOk());
        Assert.assertTrue(view.value().contains(
                "\n// Declared in other modules, not included above:\n"));
        // That the command below is to be RUN rather than adapted was a line of `--help` prose, read
        // before there was a command to apply it to. It belongs on the line above the commands.
        Assert.assertTrue(view.value().contains("// Run one of these verbatim"), view.value());
        // The version Central published for the edge is PRINTED, so a reader can see which version these
        // signatures were generated against — and is NOT an argument, because resolution is internal now and the
        // project's own Dependencies.toml pins the far side of the edge.
        Assert.assertTrue(view.value().contains("<-  ballerina/http 2.15.5"), view.value());
        Assert.assertFalse(view.value().contains("--version"), "versions are no longer arguments");
        Assert.assertTrue(Pattern.compile("^// {3}bal discover type ballerina/http .* -r$", Pattern.MULTILINE)
                .matcher(view.value()).find(), view.value());
    }

    @Test
    public void theCrossPackageFooterIsWhatResolvingBuysAndIsNotPrintedWithoutIt() {
        // SAP-04's second half. `-r` is what asks for cross-package edges to be named rather than followed, so a
        // caller could not otherwise tell from the flag whether edge naming had been requested.
        TypeView.Options bare = new TypeView.Options(List.of("ConnectionConfig"), false);
        Result<String> plain = TypeView.render(FixtureCorpus.loadedFixture("ballerinax__github"), bare);
        Assert.assertTrue(plain.isOk());
        Assert.assertFalse(plain.value().contains("// Declared in other modules"),
                "the footer is what -r buys: " + plain.value());
        // The per-line note still names the module, so a bare `type` is not left silent about the boundary.
        Assert.assertTrue(plain.value().contains("FROM ballerina/http module"), plain.value());
    }

    @Test
    public void aPredeclaredLanglibIsNeitherAnImportNorAnEdge() {
        // GMAIL-01. `int:Signed32` needs no import — `undefined module` is what the OTHER langlib modules get —
        // so the note had nothing to advise, and the command the footer printed for it answers
        // `// Unknown type: Signed32`. Both are gone; the type expression is untouched.
        Result<String> view = TypeView.render(
                FixtureCorpus.loadedFixture("ballerinax__googleapis.gmail"),
                new TypeView.Options(List.of("Profile"), true));
        Assert.assertTrue(view.isOk());
        Assert.assertTrue(view.value().contains("int:Signed32 messagesTotal?;\n"), view.value());
        Assert.assertFalse(view.value().contains("lang.int"), view.value());
        // `lang.value` is NOT pre-declared, measured with the compiler, so its note stays.
        Result<String> context = TypeView.render(
                FixtureCorpus.loadedFixture("ballerina__graphql"),
                new TypeView.Options(List.of("Context"), false));
        Assert.assertTrue(context.isOk());
        Assert.assertTrue(
                context.value().contains("Cloneable FROM ballerina/lang.value module"), context.value());
    }
}
