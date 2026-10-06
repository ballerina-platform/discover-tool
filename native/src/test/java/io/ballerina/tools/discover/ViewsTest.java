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

import io.ballerina.tools.discover.model.Library;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.JsonRenderer;
import io.ballerina.tools.discover.render.TextRenderer;
import io.ballerina.tools.discover.symbols.PathTree;
import io.ballerina.tools.discover.symbols.Surface;
import io.ballerina.tools.discover.views.Containers;
import io.ballerina.tools.discover.views.Readme;
import io.ballerina.tools.discover.views.Types;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

/**
 * Every bucket's answers, snapshotted in both renderings, plus the composition rules that decide their shape.
 *
 * <p>{@link ViewsAgreeTest} proves a view never invents a signature; this proves the answers themselves do not
 * change silently. The two together are why a rendering change has to be a reviewable diff rather than something an
 * agent discovers at run time.
 *
 * <p>These snapshots are also where the ORDERING is pinned. The path tree sorts ties with a locale collator, not
 * with {@code String::compareTo}, and the two disagree on real github segments — {@code compareTo} moves
 * {@code {owner}} from position 2 to position 10 by putting punctuation before letters. Nothing else in the suite
 * would notice.
 *
 * @since 0.1.0
 */
public class ViewsTest {

    /**
     * What shape a fixture's bare {@code client} listing answers with. Real shapes, not estimates — surveyed
     * directly against every fixture's own {@code Surface.Container} entries.
     *
     * <p>Grouping ({@link Shape#PATH_GROUPS}) and pagination ({@link Shape#METHOD_LIST} with a {@code next}) are
     * what engage once a listing is actually over {@value Containers#MAX_ENTRIES}; under it, the same shapes still
     * appear, just flat. {@code ballerinax__sap}'s one client mixes resources with named methods, redis's and
     * postgresql's mix remote methods with normal ones, and {@code ballerina__log}/{@code ballerina__xlsx} declare
     * no client at all.
     */
    private static final Map<String, Shape> CLIENT_SHAPE = Map.ofEntries(
            // Resource-only: grouped by path segment once over the ceiling (github, slack), flat under it (gmail).
            Map.entry("ballerinax__github", Shape.PATH_GROUPS),
            Map.entry("ballerinax__slack", Shape.PATH_GROUPS),
            Map.entry("ballerinax__googleapis.gmail", Shape.RESOURCE_LIST),
            // Methods of one call form only: paginated once over the ceiling, flat under it.
            Map.entry("ballerinax__twilio", Shape.METHOD_LIST),
            Map.entry("ballerinax__googleapis.sheets", Shape.METHOD_LIST),
            Map.entry("ballerina__graphql", Shape.METHOD_LIST),
            // Remote methods beside normal ones: split by call form, paged as one sequence over the ceiling.
            Map.entry("ballerinax__redis", Shape.MIXED_LISTING),
            Map.entry("ballerinax__postgresql", Shape.MIXED_LISTING),
            // Several client containers in one bucket — a roster regardless of any one container's own size.
            Map.entry("ballerina__http", Shape.CONTAINER_ROSTER),
            Map.entry("ballerinax__kafka", Shape.CONTAINER_ROSTER),
            Map.entry("ballerinax__sap", Shape.MIXED_LISTING),
            Map.entry("ballerina__log", Shape.EMPTY_BUCKET),
            Map.entry("ballerina__xlsx", Shape.EMPTY_BUCKET));

    private enum Shape { CONTAINER_ROSTER, PATH_GROUPS, RESOURCE_LIST, METHOD_LIST, MIXED_LISTING, EMPTY_BUCKET }

    @DataProvider(name = "fixtures")
    public Object[][] fixtures() {
        return FixtureCorpus.fixtureRows();
    }

    private static DiscoverResult result(Result<DiscoverResult> view, String what) {
        Assert.assertTrue(view.isOk(), what + " failed: " + (view.isOk() ? "" : view.failure().describe()));
        return view.value();
    }

    private static DiscoverResult render(LoadedPackage loaded, Surface.Scope scope, List<String> selectors) {
        return result(Containers.render(loaded, scope, new Containers.Options(selectors)),
                scope.verb() + " " + selectors);
    }

    private static <T extends DiscoverResult> T as(Class<T> shape, DiscoverResult result) {
        Assert.assertTrue(shape.isInstance(result), "expected " + shape.getSimpleName() + ", got " + result);
        return shape.cast(result);
    }

    private static DiscoverResult.Signature signature(LoadedPackage loaded, Surface.Scope scope, String... selectors) {
        return as(DiscoverResult.Signature.class, render(loaded, scope, List.of(selectors)));
    }

    // -----------------------------------------------------------------------
    // Snapshots
    // -----------------------------------------------------------------------

    /**
     * Every bucket's bare listing, in both renderings — one file per fixture per renderer, one section per bucket,
     * because the four buckets share one implementation.
     */
    @Test(dataProvider = "fixtures")
    public void everyBucketsListingIsUnchanged(String slug) {
        LoadedPackage loaded = FixtureCorpus.loadedFixture(slug);
        StringBuilder text = new StringBuilder();
        StringBuilder json = new StringBuilder();
        for (Surface.Scope scope : Surface.Scope.values()) {
            DiscoverResult listing = render(loaded, scope, List.of());
            TextRenderer.Context where = new TextRenderer.Context(
                    loaded.qualified().qualified(), null, List.of(scope.verb()), null);
            text.append("== ").append(scope.verb()).append(" ==\n").append(TextRenderer.render(listing, where))
                    .append("\n");
            json.append(JsonRenderer.render(listing)).append("\n");
        }
        Result<DiscoverResult> types = Types.render(loaded, new Types.Options(List.of(), null, 1));
        Assert.assertTrue(types.isOk(), slug + ": " + (types.isOk() ? "" : types.failure().describe()));
        text.append("== type ==\n").append(TextRenderer.render(types.value(), new TextRenderer.Context(
                loaded.qualified().qualified(), null, List.of("type"), null))).append("\n");
        json.append(JsonRenderer.render(types.value())).append("\n");
        FixtureCorpus.matchesSnapshot(FixtureCorpus.SNAPSHOTS_DIR.resolve(slug + ".buckets.txt"),
                text.toString(), slug + " text");
        FixtureCorpus.matchesSnapshot(FixtureCorpus.SNAPSHOTS_DIR.resolve(slug + ".buckets.json"),
                json.toString(), slug + " json");
    }

    /** One of each shape a leaf takes, so a change to any of them shows in a diff. */
    @Test
    public void curatedTypeLeavesAreUnchanged() {
        String[][] leaves = {
                {"ballerina__http", "ClientConfiguration"}, {"ballerina__http", "ClientError"},
                {"ballerina__http", "StatusCodeResponse"}, {"ballerina__http", "ResourceConfig"},
                {"ballerina__http", "Client"}, {"ballerina__graphql", "ID"},
                {"ballerinax__github", "ConnectionConfig"}, {"ballerinax__googleapis.sheets", "ConnectionConfig"},
                {"ballerinax__kafka", "TopicPartitionOffset"}, {"ballerinax__kafka", "NoSuchType"}};
        StringBuilder text = new StringBuilder();
        StringBuilder json = new StringBuilder();
        for (String[] leaf : leaves) {
            LoadedPackage loaded = FixtureCorpus.loadedFixture(leaf[0]);
            Result<DiscoverResult> answer = Types.render(loaded, new Types.Options(List.of(leaf[1]), null, 1));
            text.append("== ").append(leaf[0]).append(" type ").append(leaf[1]).append(" ==\n");
            if (answer.isOk()) {
                text.append(TextRenderer.render(answer.value(), new TextRenderer.Context(
                        loaded.qualified().qualified(), null, List.of("type", leaf[1]), null))).append("\n");
                json.append(JsonRenderer.render(answer.value())).append("\n");
            } else {
                text.append(answer.failure().describe()).append("\n");
                json.append(answer.failure().describe()).append("\n");
            }
        }
        FixtureCorpus.matchesSnapshot(FixtureCorpus.SNAPSHOTS_DIR.resolve("type-leaves.txt"), text.toString(),
                "type leaves text");
        FixtureCorpus.matchesSnapshot(FixtureCorpus.SNAPSHOTS_DIR.resolve("type-leaves.json"), json.toString(),
                "type leaves json");
    }

    // -----------------------------------------------------------------------
    // The entry ceiling
    // -----------------------------------------------------------------------

    /**
     * Every bucket listing stays inside the RFC's {@value Containers#MAX_ENTRIES}-entry ceiling, whatever shape it
     * took to get there.
     */
    @Test(dataProvider = "fixtures")
    public void everyListingStaysInsideTheEntryCeiling(String slug) {
        LoadedPackage loaded = FixtureCorpus.loadedFixture(slug);
        int checked = 0;
        for (Surface.Scope scope : Surface.Scope.values()) {
            List<DiscoverResult> answers = new java.util.ArrayList<>(List.of(render(loaded, scope, List.of())));
            for (Surface.Container container : Surface.of(loaded.library(), scope)) {
                if (!container.isModule()) {
                    answers.add(render(loaded, scope, List.of(container.name())));
                    answers.add(render(loaded, scope, List.of(container.name(), "zzznosuchmember")));
                    // A one-letter substring is the widest selection a caller can make short of none at all.
                    answers.add(render(loaded, scope, List.of(container.name(), "e")));
                }
            }
            for (DiscoverResult answer : answers) {
                checked += assertInsideTheCeiling(slug + " " + scope.verb(), answer);
            }
        }
        Assert.assertTrue(checked > 0, slug + ": no listing was checked");
    }

    /** One answer, and any listing nested inside it. Returns how many listings were checked. */
    private static int assertInsideTheCeiling(String label, DiscoverResult answer) {
        record Window(int listed, int shown, int total, String next) { }
        Window window = switch (answer) {
            case DiscoverResult.ContainerRoster roster -> new Window(
                    roster.containers().size() + roster.notAttachable().size(),
                    roster.containers().size() + roster.notAttachable().size(), roster.total(), roster.next());
            case DiscoverResult.PathGroups groups -> new Window(groups.resources().size() + groups.groups().size(),
                    groups.resources().size() + groups.groups().size(), groups.total(), groups.next());
            case DiscoverResult.ResourceList resources -> new Window(resources.resources().size(),
                    resources.shown(), resources.total() - pagesBefore(resources.paging(), resources.total(),
                            resources.shown()), resources.next());
            case DiscoverResult.MethodList methods -> new Window(methods.methods().size(), methods.shown(),
                    methods.total() - pagesBefore(methods.paging(), methods.total(), methods.shown()),
                    methods.next());
            case DiscoverResult.MixedListing mixed -> new Window(
                    mixed.resources().size() + mixed.remote().size() + mixed.normal().size(), mixed.shown(),
                    mixed.total() - pagesBefore(mixed.paging(), mixed.total(), mixed.shown()), mixed.next());
            case DiscoverResult.Owners owners -> new Window(owners.owners().size(), owners.owners().size(),
                    owners.total(), owners.next());
            case DiscoverResult.NoMatch noMatch -> noMatch.available() == null ? null
                    : new Window(0, 0, 0, null);
            default -> null;
        };
        if (window == null) {
            return 0;
        }
        int nested = answer instanceof DiscoverResult.NoMatch noMatch
                ? assertInsideTheCeiling(label + " (available)", noMatch.available())
                : 0;
        Assert.assertTrue(window.listed() <= Containers.MAX_ENTRIES,
                label + ": " + window.listed() + " entries listed, over the ceiling");
        Assert.assertEquals(window.listed(), window.shown(), label + ": shown disagrees with what is listed");
        if (window.shown() < window.total()) {
            Assert.assertNotNull(window.next(), label + ": cut off with no next command");
        }
        return nested + 1;
    }

    /** Entries on the pages before this one, so a later page's {@code shown < total} is read against what is left. */
    private static int pagesBefore(DiscoverResult.Paging paging, int total, int shown) {
        return paging == null ? 0 : total - shown - paging.remaining();
    }

    /** Every fixture's bare {@code client} listing answers with the shape surveyed — see {@link #CLIENT_SHAPE}. */
    @Test(dataProvider = "fixtures")
    public void clientListingsMatchTheSurveyedShape(String slug) {
        DiscoverResult result = render(FixtureCorpus.loadedFixture(slug), Surface.Scope.CLIENT, List.of());
        Shape expected = CLIENT_SHAPE.get(slug);
        Assert.assertNotNull(expected, slug + " has no surveyed shape");
        Class<? extends DiscoverResult> shape = switch (expected) {
            case CONTAINER_ROSTER -> DiscoverResult.ContainerRoster.class;
            case PATH_GROUPS -> DiscoverResult.PathGroups.class;
            case RESOURCE_LIST -> DiscoverResult.ResourceList.class;
            case METHOD_LIST -> DiscoverResult.MethodList.class;
            case MIXED_LISTING -> DiscoverResult.MixedListing.class;
            case EMPTY_BUCKET -> DiscoverResult.EmptyBucket.class;
        };
        as(shape, result);
    }

    // -----------------------------------------------------------------------
    // Resolution and tolerance
    // -----------------------------------------------------------------------

    @Test
    public void aSingleResultIsAnsweredInFullRatherThanByPrintingItsNameBack() {
        // T15. An exact one-of-many name match printed the name and forced a second call for the signature the
        // caller had already identified. `send` is an EXACT member name, so it wins over the substring pass that
        // would also have matched `sendWithMetadata` — which is what makes "exactly one result" reachable at all.
        DiscoverResult.Signature send =
                signature(FixtureCorpus.loadedFixture("ballerinax__kafka"), Surface.Scope.CLIENT, "Producer", "send");
        Assert.assertEquals(send.container(), "Producer");
        Assert.assertEquals(send.kind(), "remote");
        Assert.assertEquals(send.name(), "send");
        Assert.assertEquals(send.form(), "->");
        Assert.assertTrue(send.declaration().contains("remote function send("), send.declaration());
        // The `# +` parameter rows are what make it richer rather than merely shorter than the listing.
        Assert.assertTrue(send.declaration().contains("# + "), send.declaration());
        Assert.assertFalse(send.params().isEmpty(), send.toString());
        Assert.assertNotNull(send.returns(), send.toString());
        // And the types the signature names arrive with it, one level deep, so the common flow is one call.
        Assert.assertFalse(send.types().isEmpty(), send.toString());
    }

    @Test
    public void aResourceSignatureCarriesItsPathAndAccessorAndKeepsPathParametersOutOfParams() {
        DiscoverResult.Signature caches = signature(FixtureCorpus.loadedFixture("ballerinax__github"),
                Surface.Scope.CLIENT, "Client", "delete", "repos/:owner/:repo/actions/caches");
        Assert.assertEquals(caches.kind(), "resource");
        Assert.assertNull(caches.name());
        Assert.assertEquals(caches.accessor(), "delete");
        Assert.assertEquals(caches.path(), "repos/:owner/:repo/actions/caches");
        Assert.assertTrue(caches.params().stream().noneMatch(param -> param.name().equals("owner")),
                caches.params().toString());
        Assert.assertTrue(caches.params().stream().anyMatch(param -> "inclusion".equals(param.kind())
                && param.type().equals("ActionsDeleteActionsCacheByKeyQueries")), caches.params().toString());
    }

    @Test
    public void aMemberNameResolvesAndNamesItsOwnerRatherThanFailing() {
        // T3, the sweep's most-hit ergonomic bug: a name that was not a container was discarded, and the
        // suggestion rebuilt the command WITHOUT it, so following the advice looped.
        DiscoverResult.Signature answer =
                signature(FixtureCorpus.loadedFixture("ballerina__http"), Surface.Scope.CLASS, "toStringValue");
        Assert.assertTrue(answer.note().contains("'toStringValue' is declared on Cookie"), answer.note());
        Assert.assertTrue(answer.note().contains("bal discover ballerina/http class Cookie toStringValue"),
                answer.note());
    }

    @Test
    public void aMemberOnSeveralContainersIsARosterOfOwnersNotAFailure() {
        // The other half of T3. Picking one owner silently is what the path side refuses to do, so the answer is
        // the owners with counts and the command that opens each.
        DiscoverResult.Owners owners = as(DiscoverResult.Owners.class,
                render(FixtureCorpus.loadedFixture("ballerinax__kafka"), Surface.Scope.CLIENT, List.of("commit")));
        Assert.assertEquals(owners.requested(), "commit");
        List<String> commands = owners.owners().stream().map(DiscoverResult.Owners.Owner::command).toList();
        Assert.assertTrue(commands.contains("bal discover ballerinax/kafka client Caller commit"), commands.toString());
        Assert.assertTrue(commands.contains("bal discover ballerinax/kafka client Consumer commit"),
                commands.toString());
        Assert.assertEquals(owners.total(), owners.owners().size());
    }

    @Test
    public void aVerbGivenAnotherKindsSymbolStillAnswersAndNamesTheCanonicalVerb() {
        // T6. `ops <pkg> <constant>` failed with a client-ambiguity error for a name the package declares plainly.
        // Without tolerance every kind guess risks a wasted round trip; with it the split costs one printed line.
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");

        DiscoverResult.MethodList cookie =
                as(DiscoverResult.MethodList.class, render(http, Surface.Scope.CLIENT, List.of("Cookie")));
        Assert.assertNotNull(cookie.note(), cookie.toString());
        Assert.assertTrue(cookie.note().contains("'Cookie' is addressed by class"), cookie.note());
        Assert.assertTrue(cookie.note().contains("bal discover ballerina/http class Cookie"), cookie.note());

        // A record, asked of `client`: not a callable, so the type bucket answers it and says so.
        DiscoverResult.TypeDeclaration asType = as(DiscoverResult.TypeDeclaration.class,
                render(http, Surface.Scope.CLIENT, List.of("ClientConfiguration")));
        Assert.assertEquals(asType.kind(), "record");
        Assert.assertTrue(asType.note().contains("'ClientConfiguration' is addressed by type"), asType.note());
        Assert.assertTrue(asType.note().contains("bal discover ballerina/http type ClientConfiguration"),
                asType.note());
    }

    @Test
    public void aClientIsALegalArgumentToClassAndItsSelectorGrammarFollowsIt() {
        // The claim a design revision got wrong: HTTP-verb parsing cannot be confined to one verb, because in
        // Ballerina a client IS a class. `ballerina/http:Client` declares seven resource functions either way.
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        DiscoverResult.Signature get = signature(http, Surface.Scope.CLASS, "Client", "get", "path");
        Assert.assertTrue(get.note().contains("is addressed by client — showing it. "
                + "Canonical: bal discover ballerina/http client Client get path"), get.note());
        // Quoted from what the tool prints, not re-spelled from memory: the rest-parameter form is
        // `[PathParamType ...path]`, with the ellipsis bound to the NAME.
        Assert.assertTrue(get.declaration().contains("resource function get [PathParamType ...path]"),
                get.declaration());

        // And on a container WITHOUT resource functions the same token is a member name, finds none, and the
        // recovery names what that container actually declares.
        DiscoverResult.NoMatch cookie =
                as(DiscoverResult.NoMatch.class, render(http, Surface.Scope.CLASS, List.of("Cookie", "get")));
        Assert.assertEquals(cookie.container(), "Cookie");
        Assert.assertTrue(TextRenderer.render(cookie.available()).contains("toStringValue"), cookie.toString());
    }

    @Test
    public void aConstructorIsPartOfTheContainerAndIsReachable() {
        // T14: `init` is the one method every caller has to write, so it is individually addressable even though
        // a listing never counts it.
        DiscoverResult.Signature init = signature(
                FixtureCorpus.loadedFixture("ballerinax__googleapis.sheets"), Surface.Scope.CLIENT, "Client", "init");
        Assert.assertEquals(init.kind(), "constructor");
        Assert.assertEquals(init.form(), "new");
        Assert.assertTrue(init.declaration().contains("function init("), init.declaration());
    }

    @Test
    public void aClientWithBothHalvesIsAnsweredWithBothSplitByCallForm() {
        // `ballerina/http`'s `Client` declares 7 resource functions and 19 named ones. The shipped view printed
        // the 7 and said nothing about `execute`, `forward`, `submit`, the promise set or the circuit-breaker
        // controls — reachable from no verb in the tool.
        DiscoverResult.MixedListing mixed = as(DiscoverResult.MixedListing.class,
                render(FixtureCorpus.loadedFixture("ballerina__http"), Surface.Scope.CLIENT, List.of("Client")));
        Assert.assertFalse(mixed.resources().isEmpty(), mixed.toString());
        Assert.assertTrue(mixed.remote().stream().anyMatch(method -> method.name().equals("execute")),
                mixed.remote().toString());
        Assert.assertTrue(mixed.normal().stream().anyMatch(method -> method.name().equals("getCookieStore")),
                mixed.normal().toString());
        Assert.assertEquals(mixed.shown(), mixed.total());
        Assert.assertEquals(mixed.total(), mixed.resources().size() + mixed.remote().size() + mixed.normal().size());
        // The call form is printed on every section, because `->` versus `.` is the fact a caller came for.
        String text = TextRenderer.render(mixed);
        Assert.assertTrue(text.contains("\n\nResources (->)\n  "), text);
        Assert.assertTrue(text.contains("\n\nRemote (->)\n  "), text);
        Assert.assertTrue(text.contains("\n\nNormal (.)\n  "), text);
    }

    @Test
    public void aScopeWithNothingInItSaysWhereTheCallableSurfaceIs() {
        // An honest empty answer rather than an implied absence: kafka declares no module-level function, and the
        // reply names the buckets that DO have something plus their counts.
        DiscoverResult.EmptyBucket empty = as(DiscoverResult.EmptyBucket.class,
                render(FixtureCorpus.loadedFixture("ballerinax__kafka"), Surface.Scope.MODULE, List.of()));
        Assert.assertEquals(empty.bucket(), "funcs");
        List<String> commands = empty.elsewhere().stream().map(DiscoverResult.EmptyBucket.Elsewhere::command).toList();
        Assert.assertTrue(commands.contains("bal discover ballerinax/kafka client"), commands.toString());
        Assert.assertTrue(commands.contains("bal discover ballerinax/kafka class"), commands.toString());
    }

    @Test
    public void aModuleFunctionCarriesPublicAndAMemberDoesNot() {
        // The renderer is chosen by SCOPE rather than by shape, which is the same split the API document draws.
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        DiscoverResult.Signature funcs = signature(http, Surface.Scope.MODULE, "getDefaultListener");
        Assert.assertNull(funcs.container());
        Assert.assertEquals(funcs.kind(), "function");
        Assert.assertTrue(funcs.declaration().contains("public isolated function "), funcs.declaration());
        DiscoverResult.Signature cookie = signature(http, Surface.Scope.CLASS, "Cookie", "toStringValue");
        Assert.assertFalse(cookie.declaration().contains("\npublic isolated function "),
                "a member does not carry public");
    }

    /**
     * A selector spelled the way the document PRINTS it resolves, in one token or in two.
     *
     * <p>The measured defect: {@code client ballerinax/slack Client "post chat\.postMessage"} matched nothing,
     * while the same request with the accessor as its own argument matched. Ballerina escapes the dot in a path
     * segment, so {@code chat\.postMessage} is what every quoted signature this tool emits contains — and an
     * agent that copies one back as a single quoted argument was told there is no such operation.
     */
    @Test
    public void aSelectorResolvesInTheSpellingTheDocumentPrints() {
        LoadedPackage slack = FixtureCorpus.loadedFixture("ballerinax__slack");
        for (List<String> selectors : List.of(
                List.of("Client", "post chat\\.postMessage"),
                List.of("Client", "post chat.postMessage"),
                List.of("Client", "post", "chat\\.postMessage"),
                List.of("Client", "post", "chat.postMessage"))) {
            DiscoverResult.Signature post =
                    as(DiscoverResult.Signature.class, render(slack, Surface.Scope.CLIENT, selectors));
            Assert.assertTrue(post.declaration().contains("resource function post chat\\.postMessage"),
                    selectors + " did not resolve:\n" + post.declaration());
        }

        // The same for github's `-`, which needs the same escape inside a declaration but reads back unescaped —
        // and an accessor with a path names exactly that operation.
        LoadedPackage github = FixtureCorpus.loadedFixture("ballerinax__github");
        for (String path : List.of("code\\-scanning/alerts", "code-scanning/alerts")) {
            DiscoverResult answer = render(github, Surface.Scope.CLIENT,
                    List.of("Client", "get repos/{owner}/{repo}/" + path));
            Assert.assertTrue(resourcePaths(answer).stream().anyMatch(p -> p.contains("code-scanning/alerts")),
                    path + ": " + resourcePaths(answer));
        }
    }

    /**
     * {@code new} addresses the constructor, which Ballerina spells {@code init}.
     *
     * <p>Measured twice, in two separate sweeps: an agent asked for {@code client ballerinax/redis Client new}, was
     * told nothing matched on a container declaring 112 members, and found it on the next call as {@code init}.
     *
     * <p>An INPUT alias, not an output one — the answer still prints {@code init}, because it prints what the
     * package declares. <b>A REAL {@code new} wins.</b> github declares
     * {@code repos/[string owner]/[string repo]/codespaces/'new}, so on that client the token addresses an
     * operation and the alias must not shadow it.
     */
    @Test
    public void newAddressesTheConstructorUnlessSomethingIsReallyCalledThat() {
        for (String slug : FixtureCorpus.listFixtures()) {
            LoadedPackage loaded = FixtureCorpus.loadedFixture(slug);
            for (Surface.Container container : Surface.of(loaded.library(), Surface.Scope.CLIENT)) {
                if (container.constructor().isEmpty() || declaresNew(container)) {
                    continue;
                }
                DiscoverResult.Signature init = as(DiscoverResult.Signature.class,
                        render(loaded, Surface.Scope.CLIENT, List.of(container.name(), "new")));
                Assert.assertTrue(init.declaration().contains("function init("),
                        slug + "/" + container.name() + ": the real name is not printed:\n" + init.declaration());
            }
        }

        // And the declared one wins where there is one.
        String github = TextRenderer.render(render(FixtureCorpus.loadedFixture("ballerinax__github"),
                Surface.Scope.CLIENT, List.of("Client", "new")));
        Assert.assertTrue(github.contains("codespaces/'new"),
                "a declared `new` must outrank the constructor alias:\n" + github);
        Assert.assertFalse(github.contains("function init("), github);
    }

    /** Does this container hold anything genuinely named {@code new} — a member, or a path segment? */
    private static boolean declaresNew(Surface.Container container) {
        return container.memberNames().stream().anyMatch(name -> name.equalsIgnoreCase("new"))
                || container.operations().stream()
                        .anyMatch(operation -> operation.segments().stream()
                                .anyMatch(segment -> PathTree.readableSegment(segment).equalsIgnoreCase("new")));
    }

    @Test
    public void aSelectorThatMatchesNothingIsAnsweredWithWhatIsThere() {
        // Exit 0 with the alternatives rather than a failure: an empty selection is a fact about the container,
        // and the caller's next move is in the answer.
        for (String slug : FixtureCorpus.listFixtures()) {
            LoadedPackage loaded = FixtureCorpus.loadedFixture(slug);
            for (Surface.Container container : Surface.of(loaded.library(), Surface.Scope.CLIENT)) {
                DiscoverResult.NoMatch miss = as(DiscoverResult.NoMatch.class,
                        render(loaded, Surface.Scope.CLIENT, List.of(container.name(), "zzznosuchthing")));
                Assert.assertEquals(miss.requested(), "zzznosuchthing");
                Assert.assertEquals(miss.next(),
                        "bal discover " + loaded.pkgArgument() + " client " + container.name());
                Assert.assertNotNull(miss.available(), slug + "/" + container.name());
            }
        }
    }

    @Test
    public void aNearMissIsOfferedAsACandidate() {
        DiscoverResult.NoMatch miss = as(DiscoverResult.NoMatch.class, render(
                FixtureCorpus.loadedFixture("ballerinax__kafka"), Surface.Scope.CLIENT, List.of("Producer", "sendd")));
        Assert.assertTrue(miss.candidates().contains("send"), miss.candidates().toString());
        Assert.assertTrue(TextRenderer.render(miss).contains("\n\nDid you mean\n  send\n"), TextRenderer.render(miss));
    }

    // -----------------------------------------------------------------------
    // Anchored paths, and locating one segment under a matched prefix
    // -----------------------------------------------------------------------

    @Test
    public void aWildcardNamesEveryBranchItAlsoMatchedRatherThanTakingTheBusiestSilently() {
        // GITHUB-02. `*` matches any child and children are ordered busiest-first, so `repos/*/*` meant "the
        // busiest branch" and returned 420 of 421 under exit 0 with nothing to say so.
        LoadedPackage github = FixtureCorpus.loadedFixture("ballerinax__github");
        String note = clientNote(github, "repos/*/*");
        // Named by where it GOES, not where it forks: `repos/{templateOwner}` alone is not an address.
        Assert.assertNotNull(note, "repos/*/* should name the branch it did not take");
        Assert.assertTrue(note.contains("also matched repos/:templateOwner/:templateRepo/generate (1), "
                + "not included here"), note);
        // And only when a branch was actually dropped, or it is noise on every other lookup.
        Assert.assertNull(clientNote(github, "repos"), "no fork was walked into yet");
    }

    @Test
    public void aTrailingSegmentIsLocatedUnderTheMatchedPrefixWhenItIsUnambiguous() {
        // `repos/owner/repo/caches` is a real request: `caches` exists, at `.../actions/caches`. The anchored
        // answer — "no `caches` under `repos/{owner}/{repo}`" — is correct and costs a round trip.
        DiscoverResult.ResourceList resources = as(DiscoverResult.ResourceList.class,
                clientAnswer(FixtureCorpus.loadedFixture("ballerinax__github"), "repos/owner/repo/caches"));
        Assert.assertEquals(resources.note(), "relocated to repos/:owner/:repo/actions/caches — the only "
                + "match for that path under the requested prefix");
        Assert.assertTrue(resources.resources().stream()
                        .anyMatch(resource -> resource.path().equals("repos/:owner/:repo/actions/caches")),
                resources.toString());
    }

    @Test
    public void aShorthandThatSkipsParametersAtAnyDepthIsLocatedAndRelocatedNoted() {
        LoadedPackage github = FixtureCorpus.loadedFixture("ballerinax__github");
        DiscoverResult.ResourceList resources =
                as(DiscoverResult.ResourceList.class, clientAnswer(github, "repos/actions/runs"));
        Assert.assertEquals(resources.note(), "relocated to repos/:owner/:repo/actions/runs — the only "
                + "match for that path under the requested prefix");
        Assert.assertTrue(resources.resources().stream()
                        .allMatch(resource -> resource.path().startsWith("repos/:owner/:repo/actions/runs")),
                resources.toString());
        Assert.assertEquals(clientAnswer(github, "repos/:owner/:repo/actions/runs").getClass(),
                resources.getClass());
    }

    @Test
    public void aShorthandThatMatchesSeveralRealPathsIsListedRatherThanPicked() {
        LoadedPackage github = FixtureCorpus.loadedFixture("ballerinax__github");
        DiscoverResult.NoMatch miss =
                as(DiscoverResult.NoMatch.class, clientAnswer(github, "repos/secrets/public-key"));
        Assert.assertTrue(miss.paths().size() > 1, miss.toString());
        for (DiscoverResult.NoMatch.Alternative alternative : miss.paths()) {
            Assert.assertTrue(alternative.path().startsWith("repos/:owner/:repo/"), alternative.path());
            Assert.assertTrue(alternative.command().contains(alternative.path()), alternative.command());
        }
    }

    @Test
    public void aTrailingSegmentFoundInSeveralPlacesIsListedRatherThanPicked() {
        // Rule 2, and it is the whole reason anchoring exists: picking one of several is the failure the anchored
        // walk was built to prevent, so the answer stops at the list, each with the command that opens it.
        LoadedPackage github = FixtureCorpus.loadedFixture("ballerinax__github");
        // `secrets` exists under `actions`, `codespaces` AND `dependabot`, and they are three different APIs.
        DiscoverResult.NoMatch miss =
                as(DiscoverResult.NoMatch.class, clientAnswer(github, "repos/owner/repo/secrets"));
        List<String> paths = miss.paths().stream().map(DiscoverResult.NoMatch.Alternative::path).toList();
        Assert.assertEquals(paths.size(), 3, paths.toString());
        Assert.assertTrue(paths.contains("repos/:owner/:repo/actions/secrets"), paths.toString());
        Assert.assertTrue(paths.contains("repos/:owner/:repo/dependabot/secrets"), paths.toString());
        Assert.assertTrue(miss.paths().get(0).command().startsWith("bal discover ballerinax/github client Client "),
                miss.paths().get(0).command());
        // The paths ARE the way forward, so no generic listing competes with them.
        Assert.assertNull(miss.available());
    }

    @Test
    public void placeholderSpellingsAllAddressTheSameSegment() {
        // An agent that copied a path out of a declaration types `[string owner]`, one that read it off a tree
        // types `{owner}`, and one that typed it from memory types `owner`. All three reach the exact same answer —
        // records compare by value, so equality is the whole check.
        LoadedPackage github = FixtureCorpus.loadedFixture("ballerinax__github");
        DiscoverResult bare = clientAnswer(github, "repos/owner/repo");
        Assert.assertEquals(clientAnswer(github, "repos/{owner}/{repo}"), bare);
        Assert.assertEquals(clientAnswer(github, "repos/[string owner]/[string repo]"), bare);
        Assert.assertEquals(clientAnswer(github, "repos/:owner/:repo"), bare);
    }

    /** The {@code note} field of a resource-shaped answer. */
    private static String clientNote(LoadedPackage loaded, String path) {
        return switch (clientAnswer(loaded, path)) {
            case DiscoverResult.ResourceList resources -> resources.note();
            case DiscoverResult.PathGroups groups -> groups.note();
            default -> throw new AssertionError("not a resource-shaped answer for " + path);
        };
    }

    private static DiscoverResult clientAnswer(LoadedPackage loaded, String path) {
        return render(loaded, Surface.Scope.CLIENT, List.of("Client", path));
    }

    /** An answer's resource paths, for a test that does not care which listing shape it landed on. */
    private static List<String> resourcePaths(DiscoverResult answer) {
        return switch (answer) {
            case DiscoverResult.ResourceList resources ->
                    resources.resources().stream().map(DiscoverResult.ResourceList.Resource::path).toList();
            case DiscoverResult.PathGroups groups ->
                    groups.groups().stream().map(DiscoverResult.PathGroups.Group::name).toList();
            case DiscoverResult.Signature signature when signature.path() != null -> List.of(signature.path());
            default -> throw new AssertionError("not a resource-shaped answer: " + answer);
        };
    }

    @Test
    public void searchingAContainerFiltersOnParameterAndTypeNamesTooNotOnlyOnTheName() {
        // The reason `--filter` searches the signature rather than the name alone: an agent that knows it holds
        // an `ActionsCacheList` and wants the call returning one has no other way to ask.
        DiscoverResult answer = result(Containers.render(
                FixtureCorpus.loadedFixture("ballerinax__github"), Surface.Scope.CLIENT,
                new Containers.Options(List.of(), "ActionsCacheList", 1)), "ActionsCacheList");
        Assert.assertFalse(resourcePaths(answer).isEmpty(), "ActionsCacheList should match at least one path");
    }

    /**
     * {@code --filter} is a single keyword, not a path matcher — the RFC's own "simple case-insensitive
     * substring/keyword match". A multi-segment path narrows through the POSITIONAL selector instead.
     */
    @Test
    public void filterIsASingleKeywordSubstringNotAPathMatcher() {
        LoadedPackage github = FixtureCorpus.loadedFixture("ballerinax__github");
        DiscoverResult answer = result(Containers.render(github, Surface.Scope.CLIENT,
                new Containers.Options(List.of(), "caches", 1)), "caches");
        Assert.assertFalse(resourcePaths(answer).isEmpty(), "caches should match at least one resource path");

        // A query that is genuinely absent still reports nothing, or the fix would just be a match-everything —
        // answered with what IS there, never an empty listing.
        DiscoverResult absent = result(Containers.render(github, Surface.Scope.CLIENT,
                new Containers.Options(List.of(), "zzznopealsonope", 1)), "zzznopealsonope");
        DiscoverResult.NoMatch miss = as(DiscoverResult.NoMatch.class, absent);
        Assert.assertEquals(miss.requested(), "zzznopealsonope");
    }

    @Test
    public void aFilterThatMatchesNoContainerInABucketIsAnsweredWithTheBucket() {
        DiscoverResult.NoMatch miss = as(DiscoverResult.NoMatch.class, result(Containers.render(
                FixtureCorpus.loadedFixture("ballerina__http"), Surface.Scope.CLIENT,
                new Containers.Options(List.of(), "zzznopealsonope", 1)), "http client --filter"));
        Assert.assertNull(miss.container());
        as(DiscoverResult.ContainerRoster.class, miss.available());
        Assert.assertEquals(miss.next(), "bal discover ballerina/http client");
    }

    /**
     * The escaped spelling is searchable too, because it is the one the declarations print.
     */
    @Test
    public void searchingAContainerAcceptsTheEscapedSpelling() {
        // slack's Client has exactly one resource matching either spelling, so the filtered selection narrows to
        // one entry and answers in full.
        LoadedPackage slack = FixtureCorpus.loadedFixture("ballerinax__slack");
        for (String query : List.of("chat\\.postMessage", "chat.postMessage")) {
            DiscoverResult.Signature post = as(DiscoverResult.Signature.class, result(Containers.render(
                    slack, Surface.Scope.CLIENT, new Containers.Options(List.of(), query, 1)), query));
            Assert.assertTrue(post.declaration().contains("chat\\.postMessage"), query + ": " + post.declaration());
        }
    }

    /**
     * A path parameter answers to brackets WITHOUT the type, which is what half-remembering produces.
     *
     * <p>Measured: an agent typed {@code repos/[owner]/[repo]/issues} — the declaration form
     * {@code [string owner]} with the type dropped — and matched nothing on 903 resource functions.
     */
    @Test
    public void aPathParameterAnswersToBracketsWithoutTheType() {
        LoadedPackage github = FixtureCorpus.loadedFixture("ballerinax__github");
        for (String path : List.of(
                "repos/[owner]/[repo]/issues",
                "repos/[string owner]/[string repo]/issues",
                "repos/{owner}/{repo}/issues",
                "repos/:owner/:repo/issues",
                "repos/owner/repo/issues")) {
            DiscoverResult answer = clientAnswer(github, path);
            Assert.assertFalse(answer instanceof DiscoverResult.NoMatch, path + " did not resolve: " + answer);
        }
    }

    /**
     * An accessor and a path in ONE argument reach the path walk, whatever spelling the path is in — which is
     * what copying a whole line out of a declaration as a single quoted argument produces.
     */
    @Test
    public void anAccessorAndAPathInOneArgumentReachThePathWalk() {
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        for (String selector : List.of(
                "get [PathParamType ...path]",
                "get [path]",
                "get {...path}")) {
            DiscoverResult answer = render(http, Surface.Scope.CLIENT, List.of("Client", selector));
            Assert.assertFalse(answer instanceof DiscoverResult.NoMatch, selector + " did not resolve: " + answer);
        }

        // A leading word that is NOT an accessor stays one member name, or `Producer send` would be parsed as
        // an accessor called `Producer`.
        DiscoverResult.Signature send =
                signature(FixtureCorpus.loadedFixture("ballerinax__kafka"), Surface.Scope.CLIENT, "Producer", "send");
        Assert.assertTrue(send.declaration().contains("function send("), send.declaration());
    }

    // -----------------------------------------------------------------------
    // The readme
    // -----------------------------------------------------------------------

    @Test
    public void aReadmeChunkIsASectionWithItsProseAndIsAddressableTwoWays() {
        // A code-only extract would have discarded about 85% of `googleapis.sheets`' 178-line readme — including
        // "if you intend to use deleteSpreadsheet you must also enable the Google Drive API", which is not
        // inferable from any signature and is the difference between a connector that works and one that 403s.
        LoadedPackage sheets = FixtureCorpus.loadedFixture("ballerinax__googleapis.sheets");
        List<Readme.Chunk> chunks = Readme.chunksOf(sheets);
        Assert.assertFalse(chunks.isEmpty(), "sheets' readme carries code in several sections");

        Result<DiscoverResult> byNumber = Readme.render(sheets, new Readme.Options("1", null, 1));
        Assert.assertTrue(byNumber.isOk(), byNumber.isOk() ? "" : byNumber.failure().describe());
        DiscoverResult.Readme numberResult = (DiscoverResult.Readme) byNumber.value();
        Assert.assertEquals(numberResult.chunk(), Integer.valueOf(1));

        Result<DiscoverResult> byTitle = Readme.render(sheets, new Readme.Options(chunks.get(0).title(), null, 1));
        Assert.assertTrue(byTitle.isOk(), byTitle.isOk() ? "" : byTitle.failure().describe());
        Assert.assertEquals(((DiscoverResult.Readme) byTitle.value()).markdown(), numberResult.markdown(),
                "a title and its number are the same chunk");
    }

    @Test
    public void aChunkSelectorCombinedWithAFilterThatDoesNotMatchFailsRatherThanIgnoringTheFilter() {
        LoadedPackage sheets = FixtureCorpus.loadedFixture("ballerinax__googleapis.sheets");
        Result<DiscoverResult> view = Readme.render(
                sheets, new Readme.Options("1", "zzz_no_such_keyword_anywhere", 1));
        Assert.assertFalse(view.isOk());
        Assert.assertTrue(view.failure() instanceof Failure.Validation, view.failure().describe());
        Assert.assertTrue(view.failure().describe().contains("does not match"), view.failure().describe());
    }

    @Test
    public void aChunkThatDoesNotExistNamesEveryChunkThatDoes() {
        Result<DiscoverResult> view = Readme.render(FixtureCorpus.loadedFixture("ballerinax__googleapis.sheets"),
                new Readme.Options("999", null, 1));
        Assert.assertFalse(view.isOk());
        Assert.assertTrue(view.failure() instanceof Failure.SymbolNotFound);
        Assert.assertTrue(view.failure().describe().contains("1. "), view.failure().describe());
    }

    // -----------------------------------------------------------------------
    // The code register
    // -----------------------------------------------------------------------

    private static DiscoverResult type(String slug, String... names) {
        return result(Types.render(FixtureCorpus.loadedFixture(slug), new Types.Options(List.of(names), null, 1)),
                "type " + List.of(names));
    }

    @Test
    public void anErrorIsADeclarationOfKindErrorPrintedWithItsSubtypeChain() {
        // Unlearnable before the detail patch: all 56 rendered as `type X error;`.
        DiscoverResult.TypeDeclaration request =
                as(DiscoverResult.TypeDeclaration.class, type("ballerina__http", "ClientRequestError"));
        Assert.assertEquals(request.kind(), "error");
        Assert.assertTrue(request.declaration().endsWith("public type ClientRequestError distinct "
                + "(ApplicationResponseError & error<Detail>);"), request.declaration());
        Assert.assertTrue(as(DiscoverResult.TypeDeclaration.class, type("ballerina__http", "SslError"))
                .declaration().endsWith("public type SslError distinct ClientError;"));
        Assert.assertEquals(as(DiscoverResult.TypeDeclaration.class, type("ballerina__http", "Error"))
                .kind(), "error");
        Assert.assertFalse(type("ballerina__http", "Response") instanceof DiscoverResult.TypeDeclaration,
                "a class is routed to its bucket, never reported as a declaration");
    }

    @Test
    public void typeFilterNarrowsTheRosterAndPagesWhatIsTooLongToShow() {
        DiscoverResult.TypeRoster narrow = as(DiscoverResult.TypeRoster.class, result(
                Types.render(FixtureCorpus.loadedFixture("ballerinax__kafka"),
                        new Types.Options(List.of(), "TopicPartition", 1)), "type --filter"));
        Assert.assertTrue(narrow.sections().stream().flatMap(section -> section.entries().stream())
                .anyMatch(entry -> entry.name().equals("TopicPartition")), narrow.toString());

        DiscoverResult.TypeRoster wide = as(DiscoverResult.TypeRoster.class, result(
                Types.render(FixtureCorpus.loadedFixture("ballerinax__github"),
                        new Types.Options(List.of(), "repo", 1)), "type --filter repo"));
        Assert.assertEquals(wide.shown(), Containers.MAX_ENTRIES);
        Assert.assertTrue(wide.total() > Containers.MAX_ENTRIES, "github has far more than one page of repo types");
        Assert.assertNotNull(wide.paging());
        Assert.assertEquals(wide.next(), "bal discover ballerinax/github type --filter repo --page 2");
    }

    @Test
    public void aClosureThatDropsMoreThanTheCeilingReportsTheTrueTotal() {
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        DiscoverResult.TypeDeclaration status = as(DiscoverResult.TypeDeclaration.class,
                type("ballerina__http", "StatusCodeResponse"));
        Assert.assertEquals(status.omitted().size(), Containers.MAX_ENTRIES);
        Assert.assertEquals(status.omittedTotal(), 47);
        Assert.assertEquals(status.omittedNext(), "bal discover ballerina/http type");
        com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(JsonRenderer.render(status))
                .getAsJsonObject();
        Assert.assertEquals(json.get("omittedTotal").getAsInt(), 47);
        Assert.assertEquals(json.get("omittedNext").getAsString(), "bal discover ballerina/http type");
        Assert.assertEquals(json.getAsJsonArray("omitted").size(), 40);
        String text = TextRenderer.render(status, new TextRenderer.Context(
                http.qualified().qualified(), null, List.of("type", "StatusCodeResponse"), null));
        Assert.assertTrue(text.contains("Past the closure budget (40 of 47)"), text);
        Assert.assertTrue(text.contains("\n... 7 more, narrow further\nNext: bal discover ballerina/http type"), text);
        Assert.assertEquals(text.lines().filter(line -> line.startsWith("Past the closure budget")).count(), 1L);

        DiscoverResult.TypeDeclaration config = as(DiscoverResult.TypeDeclaration.class,
                type("ballerina__http", "ClientError"));
        Assert.assertEquals(config.omittedTotal(), config.omitted().size());
        Assert.assertNull(config.omittedNext());
        Assert.assertFalse(JsonRenderer.render(config).contains("omittedTotal"));
        Assert.assertFalse(JsonRenderer.render(config).contains("omittedNext"));
    }

    @Test(dataProvider = "fixtures")
    public void anOmittedTotalIsNeverBelowTheRowsListed(String slug) {
        LoadedPackage loaded = FixtureCorpus.loadedFixture(slug);
        for (String name : Types.names(loaded)) {
            Result<DiscoverResult> answer = Types.render(loaded, new Types.Options(List.of(name), null, 1));
            if (answer.isOk() && answer.value() instanceof DiscoverResult.TypeDeclaration leaf) {
                Assert.assertTrue(leaf.omittedTotal() >= leaf.omitted().size(), slug + " " + name);
                Assert.assertTrue(leaf.omitted().size() <= Containers.MAX_ENTRIES, slug + " " + name);
            }
        }
    }

    @Test
    public void aLocalDeclarationAndAForeignOneSharingANameAreOpenedByDifferentCommands() {
        // SHEETS-03. `ProxyConfig` is declared here AND by ballerina/http, two records with the same name and
        // different fields. Each is opened by its own package's command, and the foreign row says which module
        // and version it was generated against.
        String slug = "ballerinax__googleapis.sheets";
        DiscoverResult.TypeDeclaration local = as(DiscoverResult.TypeDeclaration.class, type(slug, "ProxyConfig"));
        Assert.assertEquals(local.kind(), "record");
        Assert.assertEquals(local.declaration(), "# Proxy server configurations to be used with the HTTP client "
                + "endpoint.\npublic type ProxyConfig record {|\n    # Host name of the proxy server\n"
                + "    string host = \"\";\n    # Proxy server port\n    int port = 0;\n"
                + "    # Proxy server username\n    string userName = \"\";\n    # Proxy server password\n"
                + "    string password = \"\";\n|};");
        Assert.assertTrue(local.types().isEmpty());
        Assert.assertTrue(local.foreign().isEmpty());

        DiscoverResult.TypeDeclaration config = as(DiscoverResult.TypeDeclaration.class,
                type(slug, "ConnectionConfig"));
        Assert.assertTrue(config.types().stream().anyMatch(each -> each.name().equals("ClientHttp1Settings")
                && each.declaration().contains("ProxyConfig? proxy = ();")), config.types().toString());
        List<DiscoverResult.Foreign> foreignProxies = config.foreign().stream()
                .filter(each -> each.name().equals("ProxyConfig")).toList();
        Assert.assertEquals(foreignProxies, List.of(new DiscoverResult.Foreign(
                "ProxyConfig", "ballerina/http", "2.14.10", "bal discover ballerina/http type ProxyConfig")));

        DiscoverResult.TypeRoster roster = as(DiscoverResult.TypeRoster.class, type(slug));
        List<DiscoverResult.Method> localRows = roster.sections().stream()
                .flatMap(section -> section.entries().stream())
                .filter(entry -> entry.name().equals("ProxyConfig")).toList();
        Assert.assertEquals(localRows, List.of(new DiscoverResult.Method(
                "ProxyConfig", "bal discover ballerinax/googleapis.sheets type ProxyConfig")));
        Assert.assertNotEquals(localRows.get(0).command(), foreignProxies.get(0).command());
    }

    @Test
    public void aForeignTypeInASiblingModuleIsReachedThroughModuleAndAnUnlistedOneHasNoCommand() {
        LoadedPackage base = FixtureCorpus.loadedFixture("ballerinax__kafka");
        List<io.ballerina.tools.discover.model.RecordField> fields = new java.util.ArrayList<>();
        String[][] modules = {
                {"ballerinax", "kafka.other", "1.0.0"}, {"ballerinax", "kafka", "4.6.5"},
                {"ballerina", "http", "2.1.0"}, {"ballerinax", "kafka.unlisted", "1.0.0"}};
        for (int i = 0; i < modules.length; i++) {
            io.ballerina.tools.discover.model.ModuleRef module = new io.ballerina.tools.discover.model.ModuleRef(
                    modules[i][0], modules[i][1], modules[i][2]);
            fields.add(new io.ballerina.tools.discover.model.RecordField("f" + i, "",
                    new io.ballerina.tools.discover.model.TypeRef("m" + i + ":Thing" + i, List.of(
                            new io.ballerina.tools.discover.model.TypeRef.Link.External(module, "Thing" + i)))));
        }
        Library library = base.library();
        List<io.ballerina.tools.discover.model.TypeDef> declarations = new java.util.ArrayList<>(library.typeDefs());
        declarations.add(new io.ballerina.tools.discover.model.TypeDef.Rec("Holder", "", true, false, fields));
        LoadedPackage loaded = new LoadedPackage(base.qualified(), base.version(),
                new Library(library.name(), library.description(), declarations, library.clients(),
                        library.functions(), library.listeners(), library.services(), library.annotations(),
                        library.configurables()),
                base.readme(), "sub", List.of(new LoadedPackage.Submodule("other", "")), base.warning());

        DiscoverResult.TypeDeclaration holder = as(DiscoverResult.TypeDeclaration.class, result(
                Types.render(loaded, new Types.Options(List.of("Holder"), null, 1)), "type Holder"));
        Assert.assertEquals(holder.foreign(), List.of(
                new DiscoverResult.Foreign("Thing0", "ballerinax/kafka.other", "1.0.0",
                        "bal discover ballerinax/kafka --module other type Thing0"),
                new DiscoverResult.Foreign("Thing1", "ballerinax/kafka", "4.6.5",
                        "bal discover ballerinax/kafka type Thing1"),
                new DiscoverResult.Foreign("Thing2", "ballerina/http", "2.1.0",
                        "bal discover ballerina/http type Thing2"),
                new DiscoverResult.Foreign("Thing3", "ballerinax/kafka.unlisted", "1.0.0", null)));
        String text = TextRenderer.render(holder, new TextRenderer.Context(
                loaded.qualified().qualified(), "sub", List.of("type", "Holder"), null));
        Assert.assertTrue(text.contains("(no command: package not known)"), text);
    }

    @Test
    public void aMemberOfSeveralEnumsIsNeverRoutedToOneOfThem() {
        LoadedPackage base = FixtureCorpus.loadedFixture("ballerinax__kafka");
        Library library = base.library();
        List<io.ballerina.tools.discover.model.TypeDef> declarations = new java.util.ArrayList<>(library.typeDefs());
        for (String name : List.of("Alpha", "Beta")) {
            declarations.add(new io.ballerina.tools.discover.model.TypeDef.Enumeration(name, "", List.of(
                    new io.ballerina.tools.discover.model.TypeDef.Enumeration.Member("SHARED_MEMBER", ""))));
        }
        LoadedPackage loaded = base.withLibrary(new Library(library.name(), library.description(), declarations,
                library.clients(), library.functions(), library.listeners(), library.services(),
                library.annotations(), library.configurables()));

        Result<DiscoverResult> answer =
                Types.render(loaded, new Types.Options(List.of("SHARED_MEMBER"), null, 1));
        Assert.assertFalse(answer.isOk());
        Failure.SymbolNotFound failure = (Failure.SymbolNotFound) answer.failure();
        Assert.assertEquals(failure.candidates(), List.of("Alpha", "Beta"));
        Assert.assertTrue(failure.suggestion().contains("member of several enums"), failure.suggestion());
    }

    @Test
    public void anEnumMemberQualifiedByAnotherPackageIsNotTakenForALocalOne() {
        LoadedPackage base = FixtureCorpus.loadedFixture("ballerinax__kafka");
        Library library = base.library();
        List<io.ballerina.tools.discover.model.TypeDef> declarations = new java.util.ArrayList<>(library.typeDefs());
        declarations.add(new io.ballerina.tools.discover.model.TypeDef.Enumeration("Solo", "", List.of(
                new io.ballerina.tools.discover.model.TypeDef.Enumeration.Member("SOLO_MEMBER", ""))));
        LoadedPackage loaded = base.withLibrary(new Library(library.name(), library.description(), declarations,
                library.clients(), library.functions(), library.listeners(), library.services(),
                library.annotations(), library.configurables()));

        Assert.assertTrue(Types.render(loaded, new Types.Options(List.of("kafka:SOLO_MEMBER"), null, 1)).isOk());
        Assert.assertFalse(Types.render(loaded, new Types.Options(List.of("http:SOLO_MEMBER"), null, 1)).isOk());
    }

    @Test
    public void aForeignTypeCarriesTheModuleAndVersionItWasGeneratedAgainst() {
        DiscoverResult.TypeDeclaration config = as(DiscoverResult.TypeDeclaration.class,
                type("ballerinax__github", "ConnectionConfig"));
        DiscoverResult.Foreign bearer = config.foreign().stream()
                .filter(each -> each.name().equals("BearerTokenConfig")).findFirst().orElseThrow();
        Assert.assertEquals(bearer.module(), "ballerina/http");
        Assert.assertEquals(bearer.version(), "2.15.5");
        Assert.assertEquals(bearer.command(), "bal discover ballerina/http type BearerTokenConfig");
    }

    @Test
    public void aPredeclaredLanglibIsNeitherAnImportNorAnEdge() {
        // GMAIL-01. `int:Signed32` needs no import, and the command for it would answer nothing.
        DiscoverResult.TypeDeclaration profile = as(DiscoverResult.TypeDeclaration.class,
                type("ballerinax__googleapis.gmail", "Profile"));
        Assert.assertTrue(profile.declaration().contains("int:Signed32 messagesTotal?;\n"), profile.declaration());
        Assert.assertTrue(profile.foreign().stream().noneMatch(each -> each.module().contains("lang.int")),
                profile.foreign().toString());
    }

    private record Routed(String container, String note) { }

    private static Routed routedTo(DiscoverResult result) {
        return switch (result) {
            case DiscoverResult.MethodList methods -> new Routed(methods.container(), methods.note());
            case DiscoverResult.MixedListing mixed -> new Routed(mixed.container(), mixed.note());
            default -> throw new AssertionError("not a routed container answer: " + result);
        };
    }

    @Test
    public void anObjectNamedToTypeIsAnsweredByTheBucketThatHoldsItWithANoteSayingWhich() {
        // SAP-09. The name index must hold a client, since it is 1 of the 4 things that package publishes.
        Assert.assertEquals(routedTo(type("ballerinax__sap", "Client")), new Routed("Client",
                "'Client' is addressed by client — showing it. Canonical: bal discover ballerinax/sap client Client"));
        Assert.assertEquals(routedTo(type("ballerina__http", "Response")), new Routed("Response",
                "'Response' is addressed by class — showing it. Canonical: bal discover ballerina/http class "
                        + "Response"));
        Assert.assertEquals(routedTo(type("ballerinax__kafka", "Service")), new Routed("Service",
                "'Service' is addressed by service — showing it. Canonical: bal discover ballerinax/kafka service "
                        + "Service; binds to kafka:Listener"));
        Assert.assertEquals(routedTo(type("ballerinax__kafka", "Listener")), new Routed("Service",
                "'Listener' is a listener, shown with the service types it serves — showing it. Canonical: "
                        + "bal discover ballerinax/kafka service; binds to kafka:Listener"));
    }

    @Test
    public void aPackageDeclaringNothingNonCallableAnswersAnEmptyTypeBucket() {
        LoadedPackage base = FixtureCorpus.loadedFixture("ballerina__log");
        LoadedPackage bare = base.withLibrary(new Library("log", "", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of()));
        Assert.assertEquals(Types.count(bare), 0);
        DiscoverResult.EmptyBucket empty = as(DiscoverResult.EmptyBucket.class, result(
                Types.render(bare, new Types.Options(List.of(), null, 1)), "type"));
        Assert.assertEquals(empty.bucket(), "type");
        Assert.assertTrue(empty.elsewhere().isEmpty());
    }

    @Test
    public void aTypePageOutOfRangeIsAValidationFailureNamingTheRange() {
        Result<DiscoverResult> page = Types.render(FixtureCorpus.loadedFixture("ballerinax__kafka"),
                new Types.Options(List.of(), null, 9));
        Assert.assertFalse(page.isOk());
        Assert.assertEquals(page.failure(), new Failure.Validation(
                "--page 9 is out of range: this listing has 58 entries on 2 pages.",
                "Pass a page from 1 to 2, e.g. `bal discover ballerinax/kafka type --page 2`."));
    }

    @Test
    public void aTypeFilterMatchingOnlyDocumentationListsThemAsDocumentedAndShowsNoRows() {
        DiscoverResult.TypeRoster roster = as(DiscoverResult.TypeRoster.class, result(
                Types.render(FixtureCorpus.loadedFixture("ballerinax__googleapis.sheets"),
                        new Types.Options(List.of(), "server", 1)), "type --filter server"));
        Assert.assertTrue(roster.sections().isEmpty());
        Assert.assertEquals(roster.shown(), 0);
        Assert.assertEquals(roster.total(), 0);
        Assert.assertEquals(roster.documented().names(),
                List.of("ClientHttp1Settings", "ConnectionConfig", "ProxyConfig"));
        Assert.assertEquals(roster.documented().total(), 3);
    }

    @Test
    public void typeTakesOneNameAndNoFilterBesideIt() {
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        Result<DiscoverResult> two = Types.render(http, new Types.Options(List.of("Error", "Response"), null, 1));
        Assert.assertFalse(two.isOk());
        Assert.assertTrue(two.failure() instanceof Failure.Validation, two.failure().describe());
        Result<DiscoverResult> named = Types.render(http, new Types.Options(List.of("Error"), "x", 1));
        Assert.assertFalse(named.isOk());
        Assert.assertTrue(named.failure() instanceof Failure.Validation, named.failure().describe());
    }

    @Test
    public void addressableAddsClientsAndDeclarationsDoesNot() {
        // The two lists answer different questions and one cannot: the map counts declarations to describe the
        // type surface and names clients on their own row, so folding them in would double-count them.
        Library sap = FixtureCorpus.libraryFor("ballerinax__sap");
        Assert.assertEquals(sap.clients().size(), 1);
        Assert.assertEquals(sap.addressable().size(), sap.declarations().size() + 1);
        Assert.assertFalse(sap.declarations().stream().anyMatch(one -> "Client".equals(one.name())));
        Assert.assertTrue(sap.addressable().stream().anyMatch(one -> "Client".equals(one.name())));
    }

    @Test
    public void configurablesAreCommentsInTheApiDocumentNotDeclarations() {
        // A `configurable` is what a DEPLOYMENT sets in Config.toml, and it is module-private:
        // `http:maxActiveConnections` from another module is `attempt to refer to non-accessible symbol`,
        // measured — so it is not a declaration a caller can reference and does not belong in a callable listing.
        // The fact is not lost, only made expensive: `api` is the register that carries it, as comments rather
        // than as declarations.
        String api = FixtureCorpus.renderFixture("ballerina__http");
        Assert.assertTrue(api.contains("\n// --- Configurables ---\n"), "api carries them");
        Assert.assertTrue(api.contains("// maxActiveConnections = -1    # int"), "with its default and type");
        Assert.assertTrue(api.contains("[ballerina.http]"), "and the Config.toml table name");
        Assert.assertFalse(FixtureCorpus.renderFixture("ballerinax__slack").contains("--- Configurables ---"));
        Result<DiscoverResult> byName = Types.render(FixtureCorpus.loadedFixture("ballerina__http"),
                new Types.Options(List.of("maxActiveConnections"), null, 1));
        Assert.assertFalse(byName.isOk(), "a configurable is not a declaration `type` can resolve");
        // Eight of http's parameter defaults name a configurable, and their "not exported by this package" note
        // is TRUE.
        Assert.assertTrue(FixtureCorpus.readSnapshot("ballerina__http")
                .contains("not exported by this package"), "the note stands");
    }

    /** Sanity: the corpus still has the path shapes these tests reason about. */
    @Test
    public void githubStillHasTheTreeTheseTestsAssumeAbout() {
        PathTree tree = PathTree.build(
                Surface.byName(Surface.of(FixtureCorpus.libraryFor("ballerinax__github"),
                        Surface.Scope.CLIENT), "Client").orElseThrow().operations());
        Assert.assertEquals(tree.total(), 903);
    }
}
