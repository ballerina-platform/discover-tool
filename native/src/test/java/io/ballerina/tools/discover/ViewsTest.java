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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ballerina.tools.discover.constructs.Decl;
import io.ballerina.tools.discover.constructs.Node;
import io.ballerina.tools.discover.constructs.Payload;
import io.ballerina.tools.discover.model.Library;
import io.ballerina.tools.discover.model.Pipeline;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.JsonRenderer;
import io.ballerina.tools.discover.render.TextRenderer;
import io.ballerina.tools.discover.symbols.PathTree;
import io.ballerina.tools.discover.symbols.Surface;
import io.ballerina.tools.discover.views.Containers;
import io.ballerina.tools.discover.views.Overview;
import io.ballerina.tools.discover.views.Readme;
import io.ballerina.tools.discover.views.Types;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
     * What shape a fixture's bare {@code client} listing answers with, surveyed against every fixture's own
     * {@code Surface.Container} entries.
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

    private static Failure.SymbolNotFound miss(LoadedPackage loaded, Surface.Scope scope, String... selectors) {
        Result<DiscoverResult> view = Containers.render(loaded, scope, new Containers.Options(List.of(selectors)));
        Assert.assertFalse(view.isOk(), scope.verb() + " " + List.of(selectors) + " answered: "
                + (view.isOk() ? view.value() : ""));
        Assert.assertTrue(view.failure() instanceof Failure.SymbolNotFound, view.failure().describe());
        Failure.SymbolNotFound failure = (Failure.SymbolNotFound) view.failure();
        Assert.assertTrue(failure.candidates().isEmpty(), "a miss never guesses: " + failure.candidates());
        Assert.assertTrue(failure.suggestion().contains("`" + failure.command() + "`"), failure.suggestion());
        return failure;
    }

    private static void assertMiss(Failure.SymbolNotFound failure, String message, String suggestion,
            String command) {
        Assert.assertEquals(failure.message(), message);
        Assert.assertEquals(failure.suggestion(), suggestion);
        Assert.assertEquals(failure.command(), command);
    }

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
                    loaded.qualified().qualified(), null, List.of(scope.verb()), null, null, null);
            text.append("== ").append(scope.verb()).append(" ==\n").append(TextRenderer.render(listing, where))
                    .append("\n");
            json.append(JsonRenderer.render(listing)).append("\n");
        }
        Result<DiscoverResult> types = Types.render(loaded, new Types.Options(List.of(), null, 1));
        Assert.assertTrue(types.isOk(), slug + ": " + (types.isOk() ? "" : types.failure().describe()));
        text.append("== type ==\n").append(TextRenderer.render(types.value(), new TextRenderer.Context(
                loaded.qualified().qualified(), null, List.of("type"), null, null, null))).append("\n");
        json.append(JsonRenderer.render(types.value())).append("\n");
        FixtureCorpus.matchesSnapshot(FixtureCorpus.SNAPSHOTS_DIR.resolve(slug + ".buckets.txt"),
                text.toString(), slug + " text");
        FixtureCorpus.matchesSnapshot(FixtureCorpus.SNAPSHOTS_DIR.resolve(slug + ".buckets.json"),
                json.toString(), slug + " json");
    }

    /** The first answer about every fixture stays inside the entry ceiling, and every bucket it lists is there. */
    @Test(dataProvider = "fixtures")
    public void theOverviewStaysInsideTheEntryCeiling(String slug) {
        LoadedPackage loaded = FixtureCorpus.loadedFixture(slug);
        DiscoverResult.Overview overview = Overview.of(loaded);
        int rows = overview.sections().stream().mapToInt(section -> section.rows().size()).sum()
                + (overview.types() == null ? 0 : 1) + (overview.readme() == null ? 0 : 1)
                + overview.submodules().size();
        Assert.assertTrue(rows <= Containers.MAX_ENTRIES, slug + ": " + rows + " rows");
        for (Surface.Scope scope : Surface.Scope.values()) {
            boolean listed = overview.sections().stream().anyMatch(section -> section.bucket().equals(scope.verb()));
            Assert.assertEquals(listed, !Surface.of(loaded.library(), scope).isEmpty(), slug + " " + scope);
        }
    }

    @Test
    public void anOverviewOverTheCeilingTakesRowsFromTheLargestSectionFirst() {
        Payload payload = Payload.pkg();
        List<Decl> clients = new ArrayList<>();
        List<Decl> classes = new ArrayList<>();
        List<Decl> functions = new ArrayList<>();
        List<Decl> services = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            services.add(Decl.serviceType("S" + i));
            clients.add(Decl.client("C" + i, Decl.method("send").on("isRemote")));
            classes.add(Decl.client("K" + i, Decl.method("run")));
            functions.add(Decl.method("f" + i));
        }
        payload.with("clients", clients.toArray(Decl[]::new));
        payload.with("classes", classes.toArray(Decl[]::new));
        payload.with("functions", functions.toArray(Decl[]::new));
        payload.with("serviceTypes", services.toArray(Decl[]::new));
        payload.with("records", Decl.record("R"));
        DiscoverResult.Overview overview = Overview.of(fromPayload(payload));
        Assert.assertEquals(overview.sections().stream().map(section -> section.rows().size()).toList(),
                List.of(10, 10, 10, 9), "four sections of 10 and the type line are 41: the last of the largest "
                        + "gives one up");
        Assert.assertEquals(overview.sections().stream().map(DiscoverResult.Overview.Section::total).toList(),
                List.of(15, 15, 15, 15));
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
                        loaded.qualified().qualified(), null, List.of("type", leaf[1]), null, null, null)))
                        .append("\n");
                json.append(JsonRenderer.render(answer.value())).append("\n");
            } else {
                text.append(answer.failure().describeText()).append("\n");
                json.append(answer.failure().describe()).append("\n");
            }
        }
        FixtureCorpus.matchesSnapshot(FixtureCorpus.SNAPSHOTS_DIR.resolve("type-leaves.txt"), text.toString(),
                "type leaves text");
        FixtureCorpus.matchesSnapshot(FixtureCorpus.SNAPSHOTS_DIR.resolve("type-leaves.json"), json.toString(),
                "type leaves json");
    }

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
                    answers.add(result(Containers.render(loaded, scope,
                            new Containers.Options(List.of(container.name()), "zzznosuchmember", 1)), "--filter"));
                    // A one-letter keyword is the widest search a caller can make short of none at all.
                    answers.add(result(Containers.render(loaded, scope,
                            new Containers.Options(List.of(container.name()), "e", 1)), "--filter e"));
                }
            }
            for (DiscoverResult answer : answers) {
                checked += assertInsideTheCeiling(slug + " " + scope.verb(), answer);
            }
        }
        Assert.assertTrue(checked > 0, slug + ": no listing was checked");
    }

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
            default -> null;
        };
        if (window == null) {
            return 0;
        }
        Assert.assertTrue(window.listed() <= Containers.MAX_ENTRIES,
                label + ": " + window.listed() + " entries listed, over the ceiling");
        Assert.assertEquals(window.listed(), window.shown(), label + ": shown disagrees with what is listed");
        if (window.shown() < window.total()) {
            Assert.assertNotNull(window.next(), label + ": cut off with no next command");
        }
        return 1;
    }

    private static int pagesBefore(DiscoverResult.Paging paging, int total, int shown) {
        return paging == null ? 0 : total - shown - paging.remaining();
    }

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

    @Test
    public void aSingleResultIsAnsweredInFullRatherThanByPrintingItsNameBack() {
        // `send` is an EXACT member name, so it wins over the substring pass that would also match
        // `sendWithMetadata` — which is what makes "exactly one result" reachable at all.
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
    public void aMemberNameTypedWhereTheContainerGoesFailsWithItsOwnersCommand() {
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        // Its one owner is in the bucket asked, so it is answered; the answer names the container.
        Assert.assertEquals(signature(http, Surface.Scope.CLASS, "toStringValue").container(), "Cookie");
        LoadedPackage github = FixtureCorpus.loadedFixture("ballerinax__github");
        Assert.assertEquals(signature(github, Surface.Scope.CLIENT, "gists/public", "get").path(), "gists/public");
        // Its one owner is in another bucket, so the failure names that bucket and the container.
        assertMiss(miss(http, Surface.Scope.CLIENT, "getStatusCodeRecord"),
                "'getStatusCodeRecord' is not a client in " + http.label() + ".",
                "It is a method of Response: `bal discover ballerina/http class Response getStatusCodeRecord`.",
                "bal discover ballerina/http class Response getStatusCodeRecord");
    }

    @Test
    public void aMemberOnSeveralContainersFailsWithEachOwnersCommand() {
        LoadedPackage kafka = FixtureCorpus.loadedFixture("ballerinax__kafka");
        // `commit` is a keyword, declared `'commit`: listed `commit`, and typed either way.
        for (String typed : List.of("commit", "'commit")) {
            Result<DiscoverResult> answer = Containers.render(kafka, Surface.Scope.CLIENT,
                    new Containers.Options(List.of(typed)));
            Failure.SymbolNotFound failure = (Failure.SymbolNotFound) answer.failure();
            Assert.assertEquals(failure.message(), "'" + typed + "' is declared by Caller and Consumer in "
                    + kafka.label() + ".");
            Assert.assertNull(failure.command());
            Assert.assertEquals(failure.candidates(), List.of("bal discover ballerinax/kafka client Caller commit",
                    "bal discover ballerinax/kafka client Consumer commit"));
        }
    }

    @Test
    public void aNameAskedInTheWrongBucketFailsWithTheCommandOfTheBucketThatHoldsIt() {
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        assertMiss(miss(http, Surface.Scope.CLIENT, "Response"), "'Response' is not a client in " + http.label() + ".",
                "It is a class: `bal discover ballerina/http class Response`.",
                "bal discover ballerina/http class Response");
        assertMiss(miss(http, Surface.Scope.CLIENT, "Cookie", "toStringValue"),
                "'Cookie' is not a client in " + http.label() + ".",
                "It is a class: `bal discover ballerina/http class Cookie toStringValue`.",
                "bal discover ballerina/http class Cookie toStringValue");
        assertMiss(miss(http, Surface.Scope.CLASS, "Client"), "'Client' is not a class in " + http.label() + ".",
                "It is a client: `bal discover ballerina/http client Client`.",
                "bal discover ballerina/http client Client");
        assertMiss(miss(http, Surface.Scope.CLIENT, "ClientConfiguration"),
                "'ClientConfiguration' is not a client in " + http.label() + ".",
                "It is a record: `bal discover ballerina/http type ClientConfiguration`.",
                "bal discover ballerina/http type ClientConfiguration");
        assertMiss(miss(http, Surface.Scope.CLASS, "Listener"), "'Listener' is not a class in " + http.label() + ".",
                "It is a listener: `bal discover ballerina/http type Listener`.",
                "bal discover ballerina/http type Listener");
        assertMiss(miss(http, Surface.Scope.CLASS, "HTTP_1_1"), "'HTTP_1_1' is not a class in " + http.label() + ".",
                "It is a member of the enum HttpVersion: `bal discover ballerina/http type HttpVersion`.",
                "bal discover ballerina/http type HttpVersion");
        assertMiss(miss(http, Surface.Scope.MODULE, "Client"), "'Client' is not a function in " + http.label() + ".",
                "It is a client: `bal discover ballerina/http client Client`.",
                "bal discover ballerina/http client Client");
    }

    @Test
    public void aContainerThatIsNotThereFailsWithTheBucketsListing() {
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        assertMiss(miss(http, Surface.Scope.CLIENT, "client"), "'client' is not a client in " + http.label() + ".",
                "List the clients: `bal discover ballerina/http client`.", "bal discover ballerina/http client");
        assertMiss(miss(http, Surface.Scope.MODULE, "nosuch"), "'nosuch' is not a function in " + http.label() + ".",
                "List the functions: `bal discover ballerina/http funcs`.", "bal discover ballerina/http funcs");
        // A name nothing in the bucket declares, whether as a container, a member or a path.
        LoadedPackage github = FixtureCorpus.loadedFixture("ballerinax__github");
        assertMiss(miss(github, Surface.Scope.CLIENT, "gist"), "'gist' is not a client in " + github.label() + ".",
                "List the clients: `bal discover ballerinax/github client`.", "bal discover ballerinax/github client");
    }

    @Test
    public void aMemberThatIsNotThereFailsWithItsContainersListing() {
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        for (String typed : new String[] {"gett", "GET"}) {
            assertMiss(miss(http, Surface.Scope.CLIENT, "Client", typed),
                    "'" + typed + "' is not a member of Client in " + http.label() + ".",
                    "List its members: `bal discover ballerina/http client Client`.",
                    "bal discover ballerina/http client Client");
        }
        LoadedPackage kafka = FixtureCorpus.loadedFixture("ballerinax__kafka");
        for (String typed : new String[] {"sendd", "Send", "end"}) {
            assertMiss(miss(kafka, Surface.Scope.CLIENT, "Producer", typed),
                    "'" + typed + "' is not a member of Producer in " + kafka.label() + ".",
                    "List its members: `bal discover ballerinax/kafka client Producer`.",
                    "bal discover ballerinax/kafka client Producer");
        }
    }

    @Test
    public void aPathIsTypedWholeAndExactlyAsPrintedOrItFailsAtTheLevelItReached() {
        LoadedPackage github = FixtureCorpus.loadedFixture("ballerinax__github");
        String client = "bal discover ballerinax/github client Client";
        assertMiss(miss(github, Surface.Scope.CLIENT, "Client", "caches"),
                "'caches' is not a member of Client in " + github.label() + ".",
                "List its members: `" + client + "`.", client);
        for (String spelling : new String[] {"repos/actions", "repos/[owner]/[repo]", "repos/{owner}/{repo}",
                "repos/owner/repo", "repos/*/*"}) {
            assertMiss(miss(github, Surface.Scope.CLIENT, "Client", spelling),
                    "'" + spelling + "' is not a resource path of Client in " + github.label() + ".",
                    "List what is under repos: `" + client + " repos`.", client + " repos");
        }
        assertMiss(miss(github, Surface.Scope.CLIENT, "Client", "repos/:owner/:repo/caches"),
                "'repos/:owner/:repo/caches' is not a resource path of Client in " + github.label() + ".",
                "List what is under repos/:owner/:repo: `" + client + " repos/:owner/:repo`.",
                client + " repos/:owner/:repo");
        assertMiss(miss(github, Surface.Scope.CLIENT, "Client", "gists/public", "head"),
                "'gists/public head' is not a resource method of Client in " + github.label() + ".",
                "List what is under gists/public: `" + client + " gists/public`.", client + " gists/public");
        // Left out, the one container that declares the path is settled, and the miss is the same.
        for (List<String> typed : List.of(List.of("gists/public", "head"), List.of("head", "gists/public"))) {
            Result<DiscoverResult> leftOut = Containers.render(github, Surface.Scope.CLIENT,
                    new Containers.Options(typed));
            Assert.assertFalse(leftOut.isOk(), typed.toString());
            Failure.SymbolNotFound failure = (Failure.SymbolNotFound) leftOut.failure();
            Assert.assertEquals(failure.message(), "'" + String.join(" ", typed) + "' is not a resource method of "
                    + "Client in " + github.label() + ".");
            Assert.assertEquals(failure.command(), client + " gists/public", typed.toString());
        }
        // A path split across two positionals is read as a path and an accessor, and is neither.
        assertMiss(miss(github, Surface.Scope.CLIENT, "Client", "repos/:owner/:repo", "issues"),
                "'repos/:owner/:repo issues' is not a resource method of Client in " + github.label() + ".",
                "List what is under repos/:owner/:repo: `" + client + " repos/:owner/:repo`.",
                client + " repos/:owner/:repo");
        // A keyword segment is printed plain, and Ballerina's own spelling of it names the same segment.
        for (String typed : List.of("gists/public", "gists/'public")) {
            Assert.assertEquals(signature(github, Surface.Scope.CLIENT, "Client", typed, "get").path(),
                    "gists/public");
        }
    }

    @Test
    public void aRestParameterResourceIsACallShorthandNotAPathToMatch() {
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        DiscoverResult.Signature get = signature(http, Surface.Scope.CLIENT, "Client", ":...path", "get");
        // Quoted from what the tool prints, not re-spelled from memory: the rest-parameter form is
        // `[PathParamType... path]`, the ellipsis on the type, however Central spaced it.
        Assert.assertTrue(get.declaration().contains("resource function get [PathParamType... path]"),
                get.declaration());
        // `get` is the remote method there, and nothing reads `foo`.
        Result<DiscoverResult> unread = Containers.render(http, Surface.Scope.CLIENT,
                new Containers.Options(List.of("Client", "get", "foo")));
        Assert.assertTrue(unread.failure() instanceof Failure.Validation, unread.toString());
    }

    @Test
    public void aConstructorIsPartOfTheContainerAndIsReachable() {
        // `init` is the one method every caller has to write, so it is individually addressable even though
        // a listing never counts it.
        DiscoverResult.Signature init = signature(
                FixtureCorpus.loadedFixture("ballerinax__googleapis.sheets"), Surface.Scope.CLIENT, "Client", "init");
        Assert.assertEquals(init.kind(), "constructor");
        Assert.assertEquals(init.form(), "new");
        Assert.assertTrue(init.declaration().contains("function init("), init.declaration());
    }

    @Test
    public void aClientWithBothHalvesIsAnsweredWithBothSplitByCallForm() {
        // `ballerina/http`'s `Client` declares resource functions AND named ones (`execute`, `forward`, `submit`,
        // …); listing only the resources would leave the rest unreachable.
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
        Assert.assertTrue(text.contains("\n\nResource methods (->)\n  "), text);
        Assert.assertTrue(text.contains("\n\nRemote methods (->)\n  "), text);
        Assert.assertTrue(text.contains("\n\nMethods (.)\n  "), text);
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
        // The renderer is chosen by SCOPE rather than by shape.
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        DiscoverResult.Signature funcs = signature(http, Surface.Scope.MODULE, "getDefaultListener");
        Assert.assertNull(funcs.container());
        Assert.assertEquals(funcs.kind(), "function");
        Assert.assertTrue(funcs.declaration().contains("public isolated function "), funcs.declaration());
        DiscoverResult.Signature cookie = signature(http, Surface.Scope.CLASS, "Cookie", "toStringValue");
        Assert.assertFalse(cookie.declaration().contains("\npublic isolated function "),
                "a member does not carry public");
    }

    @Test
    public void aSelectorResolvesOnlyInTheSpellingTheListingsPrint() {
        LoadedPackage slack = FixtureCorpus.loadedFixture("ballerinax__slack");
        for (List<String> selectors : List.of(List.of("Client", "post", "chat.postMessage"),
                List.of("Client", "chat.postMessage", "post"))) {
            DiscoverResult.Signature post =
                    as(DiscoverResult.Signature.class, render(slack, Surface.Scope.CLIENT, selectors));
            Assert.assertTrue(post.declaration().contains("resource function post chat\\.postMessage"),
                    selectors + " did not resolve:\n" + post.declaration());
        }
        // The declaration's own escaping spells the same segment; a path and its accessor are still two arguments.
        Assert.assertEquals(signature(slack, Surface.Scope.CLIENT, "Client", "post", "chat\\.postMessage").path(),
                "chat.postMessage");
        miss(slack, Surface.Scope.CLIENT, "Client", "post chat.postMessage");

        LoadedPackage github = FixtureCorpus.loadedFixture("ballerinax__github");
        Assert.assertTrue(resourcePaths(render(github, Surface.Scope.CLIENT,
                List.of("Client", "repos/:owner/:repo/code-scanning/alerts", "get"))).stream()
                .anyMatch(path -> path.contains("code-scanning/alerts")));
        Assert.assertFalse(resourcePaths(render(github, Surface.Scope.CLIENT,
                List.of("Client", "repos/:owner/:repo/code\\-scanning/alerts", "get"))).isEmpty());
    }

    /**
     * {@code new} addresses the constructor, which Ballerina spells {@code init}.
     *
     * <p>Agents ask for {@code client ballerinax/redis Client new}, the spelling of the call site.
     *
     * <p>An INPUT alias, not an output one — the answer still prints {@code init}, because it prints what the
     * package declares.
     */
    @Test
    public void newAddressesTheConstructorUnlessAPathIsCalledNew() {
        for (String slug : FixtureCorpus.listFixtures()) {
            LoadedPackage loaded = FixtureCorpus.loadedFixture(slug);
            for (Surface.Container container : Surface.of(loaded.library(), Surface.Scope.CLIENT)) {
                if (container.constructor().isEmpty()) {
                    continue;
                }
                DiscoverResult.Signature init = as(DiscoverResult.Signature.class,
                        render(loaded, Surface.Scope.CLIENT, List.of(container.name(), "new")));
                Assert.assertTrue(init.declaration().contains("function init("),
                        slug + "/" + container.name() + ": the real name is not printed:\n" + init.declaration());
            }
        }

        // A path segment called `new` is a keyword, declared `'new`: printed `new`, and typed either way. The
        // printed command reaches it, so the path outranks the `new` alias; the constructor stays `init`.
        Payload payload = Payload.pkg();
        payload.with("clients", Decl.client("Items", Decl.method("init"),
                Decl.method("get").on("isResource").with("accessor", "get").with("resourcePath", "'new")));
        LoadedPackage items = fromPayload(payload);
        Assert.assertEquals(signature(items, Surface.Scope.CLIENT, "Items", "'new").path(), "new");
        Assert.assertEquals(signature(items, Surface.Scope.CLIENT, "Items", "new").path(), "new");
        Assert.assertEquals(signature(items, Surface.Scope.CLIENT, "Items", "init").kind(), "constructor");
    }

    @Test
    public void aPathSeveralContainersHoldNamesEachContainer() {
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        Failure.SymbolNotFound several = (Failure.SymbolNotFound) Containers.render(http, Surface.Scope.CLIENT,
                new Containers.Options(List.of(":...path", "get", "extra"))).failure();
        Assert.assertEquals(several.candidates().size(), 6, several.candidates().toString());
        Assert.assertTrue(several.candidates().contains("bal discover ballerina/http client Client :...path get"));
        Result<DiscoverResult> answer = Containers.render(http, Surface.Scope.CLIENT,
                new Containers.Options(List.of("Client", ":...path", "get", "extra")));
        Failure.Validation unread = (Failure.Validation) answer.failure();
        Assert.assertTrue(unread.message().contains("'extra' after ':...path get'"), unread.message());
        Assert.assertTrue(unread.suggestion().contains("`bal discover ballerina/http client Client :...path get`"),
                unread.suggestion());
    }

    @Test
    public void aSelectorThatMatchesNothingFailsWithTheContainersListing() {
        // A failure rather than an exit-0 answer: an agent that reads a near miss as an answer reasons from the
        // wrong thing, and the exit code is the one signal every caller checks.
        for (String slug : FixtureCorpus.listFixtures()) {
            LoadedPackage loaded = FixtureCorpus.loadedFixture(slug);
            for (Surface.Container container : Surface.of(loaded.library(), Surface.Scope.CLIENT)) {
                String listing = "bal discover " + loaded.pkgArgument() + " client " + container.name();
                assertMiss(miss(loaded, Surface.Scope.CLIENT, container.name(), "zzznosuchthing"),
                        "'zzznosuchthing' is not a member of " + container.name() + " in " + loaded.label() + ".",
                        "List its members: `" + listing + "`.", listing);
            }
        }
    }

    private static DiscoverResult clientAnswer(LoadedPackage loaded, String path) {
        return render(loaded, Surface.Scope.CLIENT, List.of("Client", path));
    }

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

        // A search that finds nothing is still its answer: an empty listing, not a failure.
        DiscoverResult.ResourceList absent = as(DiscoverResult.ResourceList.class, result(Containers.render(github,
                Surface.Scope.CLIENT, new Containers.Options(List.of(), "zzznopealsonope", 1)), "zzznopealsonope"));
        Assert.assertEquals(absent.total(), 0);
        Assert.assertTrue(absent.resources().isEmpty());
        Assert.assertNull(absent.next());
    }

    @Test
    public void aFilterThatMatchesNoContainerInABucketIsAnEmptyRoster() {
        DiscoverResult.ContainerRoster roster = as(DiscoverResult.ContainerRoster.class, result(Containers.render(
                FixtureCorpus.loadedFixture("ballerina__http"), Surface.Scope.CLIENT,
                new Containers.Options(List.of(), "zzznopealsonope", 1)), "http client --filter"));
        Assert.assertEquals(roster.total(), 0);
        Assert.assertTrue(roster.containers().isEmpty());
        Assert.assertEquals(TextRenderer.render(roster, new TextRenderer.Context("ballerina/http", null,
                List.of("client"), "zzznopealsonope", null, null)),
                "ballerina/http · client · --filter zzznopealsonope\nNo clients.");
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

    @Test
    public void anAccessorAndAPathInOneArgumentAreNotAPath() {
        // Copying a line out of a declaration gives its source spelling, not the one this tool prints.
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        for (String selector : List.of("get [PathParamType ...path]", "get :...path", "get [path]")) {
            miss(http, Surface.Scope.CLIENT, "Client", selector);
        }
        // A leading word that is NOT an accessor stays one member name, or `Producer send` would be parsed as
        // an accessor called `Producer`.
        DiscoverResult.Signature send =
                signature(FixtureCorpus.loadedFixture("ballerinax__kafka"), Surface.Scope.CLIENT, "Producer", "send");
        Assert.assertTrue(send.declaration().contains("function send("), send.declaration());
    }

    @Test
    public void aReadmeSectionIsASectionWithItsProseAndIsAddressableTwoWays() {
        // Sections keep their prose: a code-only extract would discard most of `googleapis.sheets`' readme —
        // including "if you intend to use deleteSpreadsheet you must also enable the Google Drive API", which is
        // not inferable from any signature.
        LoadedPackage sheets = FixtureCorpus.loadedFixture("ballerinax__googleapis.sheets");
        List<Readme.Section> sections = Readme.sectionsOf(sheets);
        Assert.assertFalse(sections.isEmpty(), "sheets' readme carries code in several sections");

        Result<DiscoverResult> byNumber = Readme.render(sheets, new Readme.Options("1", null, 1));
        Assert.assertTrue(byNumber.isOk(), byNumber.isOk() ? "" : byNumber.failure().describe());
        DiscoverResult.Readme numberResult = (DiscoverResult.Readme) byNumber.value();
        Assert.assertEquals(numberResult.section(), Integer.valueOf(1));

        Result<DiscoverResult> byTitle = Readme.render(sheets, new Readme.Options(sections.get(0).title(), null, 1));
        Assert.assertTrue(byTitle.isOk(), byTitle.isOk() ? "" : byTitle.failure().describe());
        Assert.assertEquals(((DiscoverResult.Readme) byTitle.value()).markdown(), numberResult.markdown(),
                "a title and its number are the same section");
    }

    @Test
    public void aSectionSelectorCombinedWithAFilterThatDoesNotMatchFailsRatherThanIgnoringTheFilter() {
        LoadedPackage sheets = FixtureCorpus.loadedFixture("ballerinax__googleapis.sheets");
        Result<DiscoverResult> view = Readme.render(
                sheets, new Readme.Options("1", "zzz_no_such_keyword_anywhere", 1));
        Assert.assertFalse(view.isOk());
        Assert.assertTrue(view.failure() instanceof Failure.Validation, view.failure().describe());
        Assert.assertTrue(view.failure().describe().contains("does not mention"), view.failure().describe());
    }

    @Test
    public void aSectionThatDoesNotExistNamesEverySectionThatDoes() {
        Result<DiscoverResult> view = Readme.render(FixtureCorpus.loadedFixture("ballerinax__googleapis.sheets"),
                new Readme.Options("999", null, 1));
        Assert.assertFalse(view.isOk());
        Assert.assertTrue(view.failure() instanceof Failure.SymbolNotFound);
        Assert.assertTrue(view.failure().describe().contains("1. "), view.failure().describe());
    }

    private static DiscoverResult type(String slug, String... names) {
        return result(Types.render(FixtureCorpus.loadedFixture(slug), new Types.Options(List.of(names), null, 1)),
                "type " + List.of(names));
    }

    @Test
    public void anErrorIsADeclarationOfKindErrorPrintedWithItsSubtypeChain() {
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
    public void aLeafListsEveryTypeItLeavesOutWithoutACeiling() {
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        DiscoverResult.TypeDeclaration config = as(DiscoverResult.TypeDeclaration.class,
                type("ballerina__http", "Listener"));
        Assert.assertFalse(config.omitted().isEmpty());
        com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(JsonRenderer.render(config))
                .getAsJsonObject();
        Assert.assertEquals(json.getAsJsonArray("omitted").size(), config.omitted().size());
        Assert.assertFalse(json.has("omittedTotal"));
        Assert.assertFalse(json.has("omittedNext"));
        String text = TextRenderer.render(config, new TextRenderer.Context(
                http.qualified().qualified(), null, List.of("type", "Listener"), null, null, null));
        Assert.assertTrue(text.contains(
                "Other types it uses, not printed (" + config.omitted().size() + ")"), text);
    }

    @Test
    public void everyTypeAPrintedTypeReferencesIsPrintedOrListed() {
        DiscoverResult.TypeDeclaration config = as(DiscoverResult.TypeDeclaration.class,
                type("ballerina__http", "ClientConfiguration"));
        List<String> accounted = new ArrayList<>(config.types().stream().map(DiscoverResult.Signature.Type::name)
                .toList());
        config.omitted().forEach(omitted -> accounted.add(omitted.name()));
        Assert.assertTrue(accounted.containsAll(List.of("ClientAuthConfig", "CredentialsConfig", "BearerTokenConfig",
                "JwtIssuerConfig", "OAuth2GrantConfig")), accounted.toString());
    }

    @Test
    public void aLocalDeclarationAndAForeignOneSharingANameAreOpenedByDifferentCommands() {
        // `ProxyConfig` is declared here AND by ballerina/http, two records with the same name and
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
                "ProxyConfig", "ballerina/http", "2.14.10",
                "bal discover ballerina/http:2.14.10 type ProxyConfig")));

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
        Library withHolder = new Library(library.name(), library.description(), declarations, library.clients(),
                library.functions(), library.listeners(), library.services(), library.annotations(),
                library.configurables());
        LoadedPackage loaded = new LoadedPackage(base.qualified(), base.version(), withHolder, base.readme(), "sub",
                List.of(), List.of("sub", "other"), base.warning(), null, () -> withHolder, Map::of);

        DiscoverResult.TypeDeclaration holder = as(DiscoverResult.TypeDeclaration.class, result(
                Types.render(loaded, new Types.Options(List.of("Holder"), null, 1)), "type Holder"));
        Assert.assertEquals(holder.foreign(), List.of(
                new DiscoverResult.Foreign("Thing0", "ballerinax/kafka.other", "1.0.0",
                        "bal discover ballerinax/kafka --module other type Thing0"),
                new DiscoverResult.Foreign("Thing1", "ballerinax/kafka", "4.6.5",
                        "bal discover ballerinax/kafka type Thing1"),
                new DiscoverResult.Foreign("Thing2", "ballerina/http", "2.1.0",
                        "bal discover ballerina/http:2.1.0 type Thing2"),
                new DiscoverResult.Foreign("Thing3", "ballerinax/kafka.unlisted", "1.0.0", null)));
        String text = TextRenderer.render(holder, new TextRenderer.Context(
                loaded.qualified().qualified(), "sub", List.of("type", "Holder"), null, null, null));
        Assert.assertTrue(text.contains("(package unknown: cannot be looked up)"), text);
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
        Assert.assertEquals(failure.message(),
                "'SHARED_MEMBER' is a member of the enums Alpha and Beta in " + loaded.label() + ".");
        Assert.assertEquals(failure.suggestion(),
                "Open the enum you mean, e.g. `bal discover ballerinax/kafka type Alpha`.");
        Assert.assertNull(failure.command(), "two fixes, so no one command");
        Assert.assertTrue(failure.candidates().isEmpty());
    }

    @Test
    public void anEnumMemberIsNamedExactlyAsDeclaredWithNoModulePrefix() {
        LoadedPackage base = FixtureCorpus.loadedFixture("ballerinax__kafka");
        Library library = base.library();
        List<io.ballerina.tools.discover.model.TypeDef> declarations = new java.util.ArrayList<>(library.typeDefs());
        declarations.add(new io.ballerina.tools.discover.model.TypeDef.Enumeration("Solo", "", List.of(
                new io.ballerina.tools.discover.model.TypeDef.Enumeration.Member("SOLO_MEMBER", ""))));
        LoadedPackage loaded = base.withLibrary(new Library(library.name(), library.description(), declarations,
                library.clients(), library.functions(), library.listeners(), library.services(),
                library.annotations(), library.configurables()));

        DiscoverResult.TypeDeclaration solo = as(DiscoverResult.TypeDeclaration.class,
                result(Types.render(loaded, new Types.Options(List.of("SOLO_MEMBER"), null, 1)), "type SOLO_MEMBER"));
        Assert.assertEquals(solo.name(), "Solo");
        Assert.assertEquals(solo.note(), "'SOLO_MEMBER' is a member of the enum Solo.");
        for (String typed : List.of("kafka:SOLO_MEMBER", "http:SOLO_MEMBER", "solo_member")) {
            Assert.assertFalse(Types.render(loaded, new Types.Options(List.of(typed), null, 1)).isOk(), typed);
        }
    }

    @Test
    public void aForeignTypeCarriesTheModuleAndVersionItWasGeneratedAgainst() {
        DiscoverResult.TypeDeclaration config = as(DiscoverResult.TypeDeclaration.class,
                type("ballerinax__github", "ConnectionConfig"));
        DiscoverResult.Foreign bearer = config.foreign().stream()
                .filter(each -> each.name().equals("BearerTokenConfig")).findFirst().orElseThrow();
        Assert.assertEquals(bearer.module(), "ballerina/http");
        Assert.assertEquals(bearer.version(), "2.15.5");
        Assert.assertEquals(bearer.command(), "bal discover ballerina/http:2.15.5 type BearerTokenConfig");
    }

    @Test
    public void aPredeclaredLanglibIsNeitherAnImportNorAnEdge() {
        // `int:Signed32` needs no import, and the command for it would answer nothing.
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
            case DiscoverResult.ServiceHandlerList handlers -> new Routed(handlers.container(), handlers.note());
            case DiscoverResult.MixedListing mixed -> new Routed(mixed.container(), mixed.note());
            default -> throw new AssertionError("not a routed container answer: " + result);
        };
    }

    @Test
    public void anObjectNamedToTypeGetsTheAnswerItsOwnBucketGives() {
        LoadedPackage sap = FixtureCorpus.loadedFixture("ballerinax__sap");
        Assert.assertEquals(type("ballerinax__sap", "Client"),
                render(sap, Surface.Scope.CLIENT, List.of("Client")));
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        Assert.assertEquals(routedTo(type("ballerina__http", "Response")), new Routed("Response", null));
        Assert.assertEquals(type("ballerina__http", "Response"), render(http, Surface.Scope.CLASS,
                List.of("Response")));
        Assert.assertEquals(routedTo(type("ballerinax__kafka", "Service")),
                new Routed("Service", "attach it to kafka:Listener"));
    }

    @Test
    public void aListenerNamedToTypePrintsItsOwnDeclarationWithTheTypesItUses() {
        DiscoverResult.TypeDeclaration listener = as(DiscoverResult.TypeDeclaration.class,
                type("ballerinax__kafka", "Listener"));
        Assert.assertEquals(listener.kind(), "listener");
        Assert.assertTrue(listener.declaration().contains("class Listener {"), listener.declaration());
        Assert.assertTrue(listener.declaration().contains("function init("), listener.declaration());
        Assert.assertTrue(listener.declaration().contains("function attach("), listener.declaration());
        Assert.assertTrue(listener.types().stream().anyMatch(each -> each.name().equals("ConsumerConfiguration")),
                listener.types().toString());
        Assert.assertTrue(listener.omitted().stream().anyMatch(each -> each.name().equals("Service")),
                listener.omitted().toString());
        Assert.assertNull(listener.note());
    }

    @Test
    public void theTypeRosterListsEveryTypeThePackageDeclaresClassesAndListenersIncluded() {
        DiscoverResult.TypeRoster roster = as(DiscoverResult.TypeRoster.class,
                result(Types.render(FixtureCorpus.loadedFixture("ballerina__http"),
                        new Types.Options(List.of(), "Listener", 1)), "type --filter Listener"));
        Map<DiscoverResult.TypeRoster.Kind, List<String>> names = new LinkedHashMap<>();
        roster.sections().forEach(section -> names.put(section.kind(),
                section.entries().stream().map(DiscoverResult.Method::name).toList()));
        Assert.assertEquals(names.get(DiscoverResult.TypeRoster.Kind.LISTENERS), List.of("Listener"),
                names.toString());
        Assert.assertTrue(roster.counts().get(DiscoverResult.TypeRoster.Kind.CLASSES) > 0, roster.counts().toString());

        DiscoverResult.TypeRoster all = as(DiscoverResult.TypeRoster.class, type("ballerina__http"));
        Assert.assertEquals(all.counts().get(DiscoverResult.TypeRoster.Kind.CLIENTS),
                Surface.of(FixtureCorpus.loadedFixture("ballerina__http").library(), Surface.Scope.CLIENT).size());
        Assert.assertEquals(all.counts().get(DiscoverResult.TypeRoster.Kind.SERVICE_TYPES), 7);
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
                "Page 9 is out of range: this listing has 67 entries on 2 pages.",
                "Pass a page from 1 to 2, e.g. `bal discover ballerinax/kafka type --page 2`.",
                "bal discover ballerinax/kafka type --page 2"));
    }

    @Test
    public void aTypeFilterMatchingOnlyDocumentationIsAnEmptyRosterWithItsDocumentationMatches() {
        DiscoverResult answer = result(Types.render(FixtureCorpus.loadedFixture("ballerinax__googleapis.sheets"),
                new Types.Options(List.of(), "server", 1)), "type --filter server");
        DiscoverResult.TypeRoster roster = as(DiscoverResult.TypeRoster.class, answer);
        Assert.assertTrue(roster.sections().isEmpty(), roster.toString());
        Assert.assertEquals(roster.documented().names(),
                List.of("ClientHttp1Settings", "ConnectionConfig", "ProxyConfig"));
        Assert.assertEquals(roster.documented().total(), 3);
        Assert.assertNull(roster.next());

        String text = TextRenderer.render(answer, new TextRenderer.Context("ballerinax/googleapis.sheets", null,
                List.of("type"), "server", null, null));
        Assert.assertTrue(text.contains("No types.\n"), text);
        Assert.assertTrue(text.contains("\nMentioned only in their documentation\n  ClientHttp1Settings\n"), text);
        Assert.assertTrue(text.endsWith("\nNext: bal discover ballerinax/googleapis.sheets type <name>"), text);
        Assert.assertEquals(JsonParser.parseString(JsonRenderer.render(answer)).getAsJsonObject()
                .getAsJsonArray("documented").get(0).toString(), "{\"name\":\"ClientHttp1Settings\","
                + "\"command\":\"bal discover ballerinax/googleapis.sheets type ClientHttp1Settings\"}");
    }

    @Test
    public void theLastPageOfADocumentationOnlyTypeSearchHasNoNextButEveryEntryOpens() {
        LoadedPackage twilio = FixtureCorpus.loadedFixture("ballerinax__twilio");
        DiscoverResult.TypeRoster last = as(DiscoverResult.TypeRoster.class, result(
                Types.render(twilio, new Types.Options(List.of(), "which", 2)), "type --filter which --page 2"));
        Assert.assertEquals(last.paging().page(), last.paging().pages());
        Assert.assertNull(last.next(), "a paged answer's next only ever turns the page");
        String text = TextRenderer.render(last, new TextRenderer.Context("ballerinax/twilio", null, List.of("type"),
                "which", null, null));
        Assert.assertTrue(text.endsWith("\nNext: bal discover ballerinax/twilio type <name>"), text);
        JsonObject json = JsonParser.parseString(JsonRenderer.render(last)).getAsJsonObject();
        Assert.assertFalse(json.has("next"), json.toString());
        json.getAsJsonArray("documented").forEach(entry -> Assert.assertEquals(
                entry.getAsJsonObject().get("command").getAsString(),
                "bal discover ballerinax/twilio type " + entry.getAsJsonObject().get("name").getAsString()));
    }

    @Test
    public void aBucketWithNothingInItStillFailsANameAnotherBucketHolds() {
        LoadedPackage twilio = FixtureCorpus.loadedFixture("ballerinax__twilio");
        assertMiss(miss(twilio, Surface.Scope.CLASS, "Client"), "'Client' is not a class in " + twilio.label() + ".",
                "It is a client: `bal discover ballerinax/twilio client Client`.",
                "bal discover ballerinax/twilio client Client");
        as(DiscoverResult.EmptyBucket.class, render(twilio, Surface.Scope.CLASS, List.of("nosuch")));
    }

    @Test
    public void aDocumentedResourceIsOnePathRowWithACommandPerAccessor() {
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        DiscoverResult rest = result(Containers.render(http, Surface.Scope.CLIENT,
                new Containers.Options(List.of("Client"), "entity", 1)), "client Client --filter entity");
        DiscoverResult.MethodList miss = as(DiscoverResult.MethodList.class, rest);
        List<String> names = miss.documented().names();
        Assert.assertEquals(names, names.stream().sorted(io.ballerina.tools.discover.Texts.LOCALE_ORDER).toList());
        DiscoverResult.ResourceList.Resource row = (DiscoverResult.ResourceList.Resource)
                miss.documented().entries().get(0);
        Assert.assertEquals(row.path(), ":...path");
        Assert.assertTrue(row.accessors().containsAll(List.of("get", "post")), row.toString());
        Assert.assertEquals(row.commands().get("get"), "bal discover ballerina/http client Client :...path get");
        JsonObject entry = JsonParser.parseString(JsonRenderer.render(rest)).getAsJsonObject()
                .getAsJsonArray("documented").get(0).getAsJsonObject();
        Assert.assertEquals(entry.get("path").getAsString(), ":...path");
        Assert.assertFalse(entry.has("name"), entry.toString());
        Assert.assertEquals(entry.getAsJsonObject("commands").get("post").getAsString(),
                "bal discover ballerina/http client Client :...path post");
        String text = TextRenderer.render(rest, new TextRenderer.Context("ballerina/http", null,
                List.of("client", "Client"), "entity", null, null));
        Assert.assertTrue(text.contains("\nMentioned only in their documentation\n  :...path  get, post"), text);
        Assert.assertTrue(text.endsWith("\nNext: bal discover ballerina/http client Client <path> <accessor>\n"
                + "Next: bal discover ballerina/http client Client <name>"), text);

        LoadedPackage github = FixtureCorpus.loadedFixture("ballerinax__github");
        DiscoverResult.ResourceList escaped = as(DiscoverResult.ResourceList.class, result(Containers.render(github,
                Surface.Scope.CLIENT, new Containers.Options(List.of("Client", "repos/:owner/:repo/'import"),
                        "start", 1)), "client Client repos/:owner/:repo/'import --filter start"));
        DiscoverResult.ResourceList.Resource importRow = (DiscoverResult.ResourceList.Resource)
                escaped.documented().entries().get(0);
        Assert.assertEquals(importRow.path(), "repos/:owner/:repo/import");
        Assert.assertEquals(importRow.commands().get("put"),
                "bal discover ballerinax/github client Client repos/:owner/:repo/import put");

        DiscoverResult limits = result(Containers.render(github, Surface.Scope.CLIENT,
                new Containers.Options(List.of("Client"), "public", 1)), "client Client --filter public");
        DiscoverResult.Documented documented = switch (limits) {
            case DiscoverResult.MethodList methods -> methods.documented();
            case DiscoverResult.PathGroups ignored -> DiscoverResult.Documented.NONE;
            case DiscoverResult.ResourceList list -> list.documented();
            default -> throw new AssertionError(limits.toString());
        };
        Assert.assertEquals(documented.names().stream().filter("user/interaction-limits"::equals).count(), 1L,
                documented.toString());
    }

    @Test
    public void aSignaturesPageCommandCarriesThePathAndTheFilter() {
        Payload payload = Payload.pkg();
        List<Decl> methods = new ArrayList<>();
        methods.add(Decl.method("get", Decl.param("zebra", Node.builtin("string"))).on("isResource")
                .with("accessor", "get").with("resourcePath", "items/first"));
        for (int index = 1; index <= 41; index++) {
            methods.add(Decl.method("get").on("isResource").with("accessor", "get")
                    .with("resourcePath", "items/n" + index).with("description", "Not a zebra."));
        }
        payload.with("clients", Decl.client("Client", methods.toArray(new Decl[0])));
        DiscoverResult.Signature signature = as(DiscoverResult.Signature.class, result(
                Containers.render(fromPayload(payload), Surface.Scope.CLIENT,
                        new Containers.Options(List.of("Client", "items"), "zebra", 1)),
                "client Client items --filter zebra"));
        Assert.assertEquals(signature.paging().pages(), 2);
        Assert.assertEquals(signature.next(), "bal discover test/pkg client Client items --filter zebra --page 2");
    }

    @Test
    public void aNameTypedInAnotherCaseIsNotTheName() {
        LoadedPackage twilio = FixtureCorpus.loadedFixture("ballerinax__twilio");
        assertMiss(miss(twilio, Surface.Scope.CLIENT, "Client", "createaccount"),
                "'createaccount' is not a member of Client in " + twilio.label() + ".",
                "List its members: `bal discover ballerinax/twilio client Client`.",
                "bal discover ballerinax/twilio client Client");
        LoadedPackage log = FixtureCorpus.loadedFixture("ballerina__log");
        assertMiss(miss(log, Surface.Scope.MODULE, "printinfo"),
                "'printinfo' is not a function in " + log.label() + ".",
                "List the functions: `bal discover ballerina/log funcs`.", "bal discover ballerina/log funcs");
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
        // `http:maxActiveConnections` from another module is `attempt to refer to non-accessible symbol` — so it
        // is not a declaration a caller can reference. The rendered document carries it as comments instead.
        String api = FixtureCorpus.renderFixture("ballerina__http");
        Assert.assertTrue(api.contains("\n// --- Configurables ---\n"), "api carries them");
        Assert.assertTrue(api.contains("// maxActiveConnections = -1    # int"), "with its default and type");
        Assert.assertTrue(api.contains("[ballerina.http]"), "and the Config.toml table name");
        Assert.assertFalse(FixtureCorpus.renderFixture("ballerinax__slack").contains("--- Configurables ---"));
        Result<DiscoverResult> byName = Types.render(FixtureCorpus.loadedFixture("ballerina__http"),
                new Types.Options(List.of("maxActiveConnections"), null, 1));
        Assert.assertFalse(byName.isOk(), "a configurable is not a declaration `type` can resolve");
        // http's parameter defaults that name a configurable keep their "not exported by this package" note,
        // which is TRUE.
        Assert.assertTrue(FixtureCorpus.readSnapshot("ballerina__http")
                .contains("not exported by this package"), "the note stands");
    }

    private static LoadedPackage fromPayload(Payload payload) {
        Library library = Pipeline.build(payload.module());
        return new LoadedPackage(QualifiedName.parse("test/pkg").value(), FixtureCorpus.FIXTURE_VERSION, library,
                Optional.empty(), null, List.of(), List.of(), null, null, () -> library, Map::of);
    }

    @Test
    public void aPageFullOfNamesWithDocumentationOnlyMatchesLeftSaysHowManyRemainInJson() {
        Payload payload = Payload.pkg();
        List<Decl> methods = new ArrayList<>();
        for (int index = 1; index <= 30; index++) {
            methods.add(Decl.method("send" + index).on("isRemote"));
        }
        for (int index = 1; index <= 15; index++) {
            methods.add(Decl.method("other" + index).on("isRemote").with("description", "Can send it."));
        }
        payload.with("clients", Decl.client("Client", methods.toArray(new Decl[0])));
        DiscoverResult.MethodList first = as(DiscoverResult.MethodList.class, result(
                Containers.render(fromPayload(payload), Surface.Scope.CLIENT,
                        new Containers.Options(List.of("Client"), "send", 1)), "client Client --filter send"));
        Assert.assertEquals(first.shown(), 30);
        Assert.assertEquals(first.total(), 30);
        Assert.assertEquals(first.documented().names().size(), 10);

        JsonObject json = JsonParser.parseString(JsonRenderer.render(first)).getAsJsonObject();
        Assert.assertEquals(json.get("shown").getAsInt(), json.get("total").getAsInt(),
                "shown/total count the names only");
        Assert.assertEquals(json.get("remaining").getAsInt(), 5, "so the documentation-only rest is counted here");
        Assert.assertEquals(json.get("pages").getAsInt(), 2);
        Assert.assertEquals(json.get("next").getAsString(),
                "bal discover test/pkg client Client --filter send --page 2");

        DiscoverResult.MethodList second = as(DiscoverResult.MethodList.class, result(
                Containers.render(fromPayload(payload), Surface.Scope.CLIENT,
                        new Containers.Options(List.of("Client"), "send", 2)), "client Client --filter send --page 2"));
        Assert.assertEquals(JsonParser.parseString(JsonRenderer.render(second)).getAsJsonObject()
                .get("remaining").getAsInt(), 0);
    }

    @Test
    public void aContainerNameIsPrintedPlainInEveryCommandBuiltOnIt() {
        Payload payload = Payload.pkg();
        payload.with("clients",
                Decl.client("'Client", Decl.method("send").on("isRemote"), Decl.method("receive").on("isRemote")),
                Decl.client("'Other", Decl.method("send").on("isRemote")));
        LoadedPackage loaded = fromPayload(payload);

        DiscoverResult.MethodList methods = as(DiscoverResult.MethodList.class, result(Containers.render(loaded,
                Surface.Scope.CLIENT, new Containers.Options(List.of("'Client"))), "client 'Client"));
        Assert.assertEquals(methods.methods().stream().map(DiscoverResult.Method::command).toList(), List.of(
                "bal discover test/pkg client Client receive",
                "bal discover test/pkg client Client send"));

        Result<DiscoverResult> send = Containers.render(loaded, Surface.Scope.CLIENT,
                new Containers.Options(List.of("send")));
        Failure.SymbolNotFound both = (Failure.SymbolNotFound) send.failure();
        Assert.assertEquals(both.candidates(), List.of("bal discover test/pkg client Client send",
                "bal discover test/pkg client Other send"));
        Assert.assertEquals(as(DiscoverResult.Signature.class, render(loaded, Surface.Scope.CLIENT,
                List.of("receive"))).container(), "Client");
    }

    @Test
    public void aKeywordNamedPathParameterIsEscapedInTheDeclaration() {
        Payload payload = Payload.pkg();
        payload.with("clients", Decl.client("Items", Decl.method("get").on("isResource").with("accessor", "get")
                .with("resourcePath", "items/[string type]")));
        DiscoverResult.Signature get = as(DiscoverResult.Signature.class, result(Containers.render(
                fromPayload(payload), Surface.Scope.CLIENT, new Containers.Options(List.of("Items", "items/:type",
                        "get"))), "client Items items/:type get"));
        Assert.assertTrue(get.declaration().contains("resource function get items/[string 'type]()"),
                get.declaration());
    }

    @Test
    public void aNameTwoContainersReadDifferentlyOffersEachItsOwnCommand() {
        Payload payload = Payload.pkg();
        payload.with("clients",
                Decl.client("Paths", Decl.method("get").on("isResource").with("accessor", "get")
                        .with("resourcePath", "foo")),
                Decl.client("Methods", Decl.method("foo").on("isRemote")));
        LoadedPackage loaded = fromPayload(payload);
        // `foo get` is a path and its accessor on Paths, and `foo` a remote method of Methods: each owner's
        // command carries what that owner reads, so each runs.
        Failure.SymbolNotFound both = (Failure.SymbolNotFound) Containers.render(loaded, Surface.Scope.CLIENT,
                new Containers.Options(List.of("foo", "get", "extra"))).failure();
        Assert.assertEquals(both.candidates(), List.of("bal discover test/pkg client Methods foo",
                "bal discover test/pkg client Paths foo get"));
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
