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
import io.ballerina.tools.discover.symbols.PathTree;
import io.ballerina.tools.discover.symbols.Surface;
import io.ballerina.tools.discover.views.Containers;
import io.ballerina.tools.discover.views.Guide;
import io.ballerina.tools.discover.views.TypeView;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The report documents, snapshotted, plus the composition rules that decide their shape.
 *
 * <p>{@link ViewsAgreeTest} proves a view never invents a signature; this proves the documents themselves do not
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
     * <p>Naming a container by itself now reaches the RFC's result IR for ANY container whose entries are purely
     * resources or purely remote/normal methods, whether or not the ceiling is actually crossed — "one source,
     * multiple renderers" applies to every response, not just the ones over the ceiling. Grouping ({@link
     * DiscoverResultKind#PATH_GROUPS}) and pagination ({@link DiscoverResultKind#METHOD_LIST} with a {@code next})
     * are what engage once a listing is actually over {@value Containers#MAX_ENTRIES}; under it, the same shapes
     * still appear, just flat. {@link DiscoverResultKind#CONTAINER_ROSTER} is the other structured shape a
     * {@code client} bucket can take — several client containers in one bucket, unrelated to any one container's
     * own entry count.
     *
     * <p>Only three fixtures are absent from this map: {@code ballerina__log} and {@code ballerina__xlsx} declare
     * no client at all (a scope-empty Markdown message), and {@code ballerinax__sap}'s one Client mixes resources
     * and named methods, which stays the Markdown {@code mixedAnswer} — see {@link Containers}'s own note on why
     * that combination has no JSON shape yet.
     */
    private static final Map<String, DiscoverResultKind> STRUCTURED_CLIENT_SHAPE = Map.ofEntries(
            // Resource-only: grouped by path segment once over the ceiling (github, slack), flat under it (gmail).
            Map.entry("ballerinax__github", DiscoverResultKind.PATH_GROUPS),
            Map.entry("ballerinax__slack", DiscoverResultKind.PATH_GROUPS),
            Map.entry("ballerinax__googleapis.gmail", DiscoverResultKind.RESOURCE_LIST),
            // Remote/normal-method-only: paginated once over the ceiling, flat under it.
            Map.entry("ballerinax__twilio", DiscoverResultKind.METHOD_LIST),
            Map.entry("ballerinax__redis", DiscoverResultKind.METHOD_LIST),
            Map.entry("ballerinax__googleapis.sheets", DiscoverResultKind.METHOD_LIST),
            Map.entry("ballerina__graphql", DiscoverResultKind.METHOD_LIST),
            Map.entry("ballerinax__postgresql", DiscoverResultKind.METHOD_LIST),
            // Several client containers in one bucket — a roster regardless of any one container's own size.
            Map.entry("ballerina__http", DiscoverResultKind.CONTAINER_ROSTER),
            Map.entry("ballerinax__kafka", DiscoverResultKind.CONTAINER_ROSTER));

    private enum DiscoverResultKind { CONTAINER_ROSTER, PATH_GROUPS, RESOURCE_LIST, METHOD_LIST }

    @DataProvider(name = "fixtures")
    public Object[][] fixtures() {
        return FixtureCorpus.fixtureRows();
    }

    private static Path viewSnapshot(String slug, String view) {
        return FixtureCorpus.SNAPSHOTS_DIR.resolve(slug + "." + view + ".md");
    }

    /** The Markdown text of a bare bucket listing, or empty when it answered on the structured IR instead. */
    private static java.util.Optional<String> render(String slug, Surface.Scope scope) {
        Result<Containers.Answer> view = Containers.render(
                FixtureCorpus.loadedFixture(slug), scope, Containers.Options.bare());
        Assert.assertTrue(view.isOk(), scope.verb() + " failed for " + slug + ": "
                + (view.isOk() ? "" : view.failure().describe()));
        return view.value() instanceof Containers.Answer.Markdown markdown
                ? java.util.Optional.of(markdown.text())
                : java.util.Optional.empty();
    }

    // -----------------------------------------------------------------------
    // Snapshots
    // -----------------------------------------------------------------------

    /**
     * One snapshot per scope, because the three verbs share one implementation — for every bucket that still
     * answers in Markdown. The five (fixture, scope) pairs over the entry ceiling answer on the result IR
     * instead ({@link #structuredListingsMatchTheSurveyedShape} pins those), which quotes no Ballerina to
     * snapshot here.
     */
    @Test(dataProvider = "fixtures")
    public void everyScopesListingIsUnchanged(String slug) {
        for (Surface.Scope scope : Surface.Scope.values()) {
            if (Surface.of(FixtureCorpus.libraryFor(slug), scope).isEmpty()) {
                continue;
            }
            render(slug, scope).ifPresent(document -> FixtureCorpus.matchesSnapshot(
                    viewSnapshot(slug, scope.verb()), document, slug + " " + scope.verb()));
        }
    }

    // -----------------------------------------------------------------------
    // The entry ceiling
    // -----------------------------------------------------------------------

    /**
     * Every bucket listing stays inside the RFC's {@value Containers#MAX_ENTRIES}-entry ceiling — the property
     * the byte budget used to hold from the OTHER side (bytes of quoted Ballerina). Checked generically, over
     * every fixture and every bucket: a structured answer's own {@code shown} can never exceed the ceiling,
     * whatever shape it took to get there.
     */
    @Test(dataProvider = "fixtures")
    public void everyStructuredListingStaysInsideTheEntryCeiling(String slug) {
        for (Surface.Scope scope : Surface.Scope.values()) {
            if (Surface.of(FixtureCorpus.libraryFor(slug), scope).isEmpty()) {
                continue;
            }
            Containers.Answer answer = expectAnswer(
                    Containers.render(FixtureCorpus.loadedFixture(slug), scope, Containers.Options.bare()),
                    slug + " " + scope.verb());
            if (!(answer instanceof Containers.Answer.Structured structured)) {
                continue;
            }
            int shown = switch (structured.result()) {
                case DiscoverResult.ContainerRoster roster -> roster.containers().size();
                case DiscoverResult.PathGroups groups -> groups.groups().size();
                case DiscoverResult.ResourceList resources -> resources.shown();
                case DiscoverResult.MethodList methods -> methods.shown();
                case DiscoverResult.BucketList ignored -> 0;
            };
            Assert.assertTrue(shown <= Containers.MAX_ENTRIES,
                    slug + " " + scope.verb() + ": " + shown + " entries shown, over the ceiling");
        }
    }

    /**
     * Every fixture's bare {@code client} listing answers with the shape surveyed directly against real fixture
     * data — see {@link #STRUCTURED_CLIENT_SHAPE}.
     */
    @Test(dataProvider = "fixtures")
    public void structuredListingsMatchTheSurveyedShape(String slug) {
        Containers.Answer answer = expectAnswer(
                Containers.render(FixtureCorpus.loadedFixture(slug), Surface.Scope.CLIENT, Containers.Options.bare()),
                slug + " client");
        DiscoverResultKind expected = STRUCTURED_CLIENT_SHAPE.get(slug);
        if (expected == null) {
            Assert.assertTrue(answer instanceof Containers.Answer.Markdown,
                    slug + ": expected a flat Markdown client listing, got " + answer);
            return;
        }
        Assert.assertTrue(answer instanceof Containers.Answer.Structured,
                slug + ": expected a structured client listing, got " + answer);
        DiscoverResult result = ((Containers.Answer.Structured) answer).result();
        switch (expected) {
            case CONTAINER_ROSTER -> Assert.assertTrue(result instanceof DiscoverResult.ContainerRoster,
                    slug + ": expected a container roster, got " + result);
            case PATH_GROUPS -> Assert.assertTrue(result instanceof DiscoverResult.PathGroups,
                    slug + ": expected path groups, got " + result);
            case RESOURCE_LIST -> Assert.assertTrue(result instanceof DiscoverResult.ResourceList,
                    slug + ": expected a flat resource list, got " + result);
            case METHOD_LIST -> Assert.assertTrue(result instanceof DiscoverResult.MethodList,
                    slug + ": expected a method list, got " + result);
        }
    }

    private static Containers.Answer expectAnswer(Result<Containers.Answer> view, String what) {
        Assert.assertTrue(view.isOk(), what + " failed: " + (view.isOk() ? "" : view.failure().describe()));
        return view.value();
    }

    /** The Markdown text of an answer expected to have no ceiling problem — everything below the ceiling test. */
    private static String markdown(Result<Containers.Answer> view, String what) {
        Containers.Answer answer = expectAnswer(view, what);
        Assert.assertTrue(answer instanceof Containers.Answer.Markdown,
                what + ": expected Markdown, got a structured answer instead");
        return ((Containers.Answer.Markdown) answer).text();
    }

    /**
     * True for the one Markdown shape that means "this selector matched nothing" — the shape a resolution test
     * is checking the ABSENCE of, whether the successful case lands as Markdown (one entry, or a mixed
     * resource+method container) or as a structured resource/method listing.
     */
    private static boolean isNothingMatched(Containers.Answer answer) {
        return answer instanceof Containers.Answer.Markdown markdown
                && markdown.text().contains("| Matched | nothing");
    }

    // -----------------------------------------------------------------------
    // Resolution and tolerance
    // -----------------------------------------------------------------------

    @Test
    public void aSingleResultIsAnsweredInFullRatherThanByPrintingItsNameBack() {
        // T15. An exact one-of-many name match printed the name and forced a second call for the signature the
        // caller had already identified. One result is the case where the richest tier always fits.
        LoadedPackage kafka = FixtureCorpus.loadedFixture("ballerinax__kafka");
        String document = markdown(Containers.render(kafka, Surface.Scope.CLIENT,
                new Containers.Options(List.of("Producer", "send"))), "Producer send");
        // `send` is an EXACT member name, so it wins over the substring pass that would also have matched
        // `sendWithMetadata` — which is what makes "exactly one result" reachable at all.
        Assert.assertTrue(document.contains("| Showing | the declaration in full"), document);
        Assert.assertTrue(document.contains("remote function send("), document);
        // The FULL tier is the only one that prints the `# +` parameter rows, which is what makes it richer rather
        // than merely shorter than the listing.
        Assert.assertTrue(document.contains("# + "), document);
        // And the types the signature names arrive with it, one level deep, so the common flow is one call.
        Assert.assertTrue(document.contains("## The types it names"), document);
    }

    @Test
    public void aMemberNameResolvesAndNamesItsOwnerRatherThanFailing() {
        // T3, the sweep's most-hit ergonomic bug: a name that was not a container was discarded, and the
        // suggestion rebuilt the command WITHOUT it, so following the advice looped.
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        String document = markdown(Containers.render(http, Surface.Scope.CLASS,
                new Containers.Options(List.of("toStringValue"))), "toStringValue");
        Assert.assertTrue(document.contains("| Note | `toStringValue` is declared on `Cookie`"), document);
        Assert.assertTrue(document.contains("bal discover ballerina/http class Cookie toStringValue"), document);
    }

    @Test
    public void aMemberOnSeveralContainersIsARosterOfOwnersNotAFailure() {
        // The other half of T3. Picking one owner silently is what the path side refuses to do, so the answer is
        // the owners with counts and the command that opens each.
        LoadedPackage kafka = FixtureCorpus.loadedFixture("ballerinax__kafka");
        String document = markdown(Containers.render(kafka, Surface.Scope.CLIENT,
                new Containers.Options(List.of("commit"))), "commit");
        Assert.assertTrue(document.contains("owners"), document);
        Assert.assertTrue(document.contains("`bal discover ballerinax/kafka client Caller commit`"), document);
        Assert.assertTrue(document.contains("`bal discover ballerinax/kafka client Consumer commit`"), document);
    }

    @Test
    public void aVerbGivenAnotherKindsSymbolStillAnswersAndNamesTheCanonicalVerb() {
        // T6. `ops <pkg> <constant>` failed with a client-ambiguity error for a name the package declares plainly.
        // Without tolerance every kind guess risks a wasted round trip; with it the split costs one printed line.
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");

        // A class, asked of `client`. Cookie's own class-scope answer is now the structured IR (it declares
        // several methods), so the kind-tolerance note travels as that answer's own `note` field rather than a
        // Markdown facts row — see DiscoverResult's own note on why that field exists.
        Containers.Answer asClient = expectAnswer(Containers.render(http, Surface.Scope.CLIENT,
                new Containers.Options(List.of("Cookie"))), "Cookie");
        Assert.assertTrue(asClient instanceof Containers.Answer.Structured, asClient.toString());
        DiscoverResult cookieResult = ((Containers.Answer.Structured) asClient).result();
        String note = cookieResult instanceof DiscoverResult.MethodList methodList ? methodList.note() : null;
        Assert.assertNotNull(note, cookieResult.toString());
        Assert.assertTrue(note.contains("`Cookie` is addressed by `class`"), note);
        Assert.assertTrue(note.contains("bal discover ballerina/http class Cookie"), note);

        // A record, asked of `client`: not a callable at all. There is no more bucket that answers for a bare
        // declaration name — `type` had no RFC equivalent and was dropped along with it — so this now fails with
        // near-miss candidates rather than answering from the code register.
        Result<Containers.Answer> asType = Containers.render(http, Surface.Scope.CLIENT,
                new Containers.Options(List.of("ClientConfiguration")));
        Assert.assertFalse(asType.isOk(), "no bucket answers for a non-callable declaration any more");
        Assert.assertTrue(asType.failure() instanceof Failure.SymbolNotFound, asType.failure().describe());
    }

    @Test
    public void aClientIsALegalArgumentToClassAndItsSelectorGrammarFollowsIt() {
        // The claim a design revision got wrong: HTTP-verb parsing cannot be confined to one verb, because in
        // Ballerina a client IS a class. `ballerina/http:Client` declares seven resource functions either way.
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        String document = markdown(Containers.render(http, Surface.Scope.CLASS,
                new Containers.Options(List.of("Client", "get", "path"))), "Client get path");
        Assert.assertTrue(document.contains("is addressed by `client`"), document);
        // Quoted from what the tool prints, not re-spelled from memory: the rest-parameter form is
        // `[PathParamType ...path]`, with the ellipsis bound to the NAME. An assertion written the other way round
        // passes only against a renderer that has the same bug.
        Assert.assertTrue(document.contains("resource function get [PathParamType ...path]"), document);

        // And on a container WITHOUT resource functions the same token is a member name, finds none, and the
        // recovery names what that container actually declares.
        String cookie = markdown(Containers.render(http, Surface.Scope.CLASS,
                new Containers.Options(List.of("Cookie", "get"))), "Cookie get");
        Assert.assertTrue(cookie.contains("| Matched | nothing on `Cookie`"), cookie);
        Assert.assertTrue(cookie.contains("toStringValue"), cookie);
    }

    @Test
    public void aConstructorIsPartOfTheContainerAndIsReachable() {
        // T14: `ops` could not address one, and the only document that carried it was `overview --client <Name>`,
        // which no longer exists. `init` is the one method every caller has to write.
        LoadedPackage sheets = FixtureCorpus.loadedFixture("ballerinax__googleapis.sheets");
        // sheets' Client is remote-only and over the ceiling (44 methods incl. init), so the bare listing is now
        // structured — see structuredListingsMatchTheSurveyedShape. `init` is still individually addressable.
        String byName = markdown(Containers.render(sheets, Surface.Scope.CLIENT,
                new Containers.Options(List.of("Client", "init"))), "Client init");
        Assert.assertTrue(byName.contains("function init("), byName);
    }

    @Test
    public void aClientWithBothHalvesIsAnsweredWithBothSplitByCallForm() {
        // `ballerina/http`'s `Client` declares 7 resource functions and 19 named ones. The shipped view printed
        // the 7 under a fact row reading `(7 of 7)` and said nothing about `execute`, `forward`, `submit`, the
        // promise set or the circuit-breaker controls — reachable from no verb in the tool.
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        String document = markdown(Containers.render(http, Surface.Scope.CLIENT,
                new Containers.Options(List.of("Client"))), "Client");
        Assert.assertTrue(document.contains("## Resource functions —"), document);
        Assert.assertTrue(document.contains("## Remote functions —"), document);
        // The call form is printed on every section heading, because `->` versus `.` is the fact a caller came for
        // and both signature errors in the 2026-08-15 sweep came from its absence.
        Assert.assertTrue(document.contains(", call with `->`"), document);
        Assert.assertTrue(document.contains("## Normal functions —"), document);
        Assert.assertTrue(document.contains(", call with `.`"), document);
        Assert.assertTrue(document.contains("execute"), document);
        Assert.assertTrue(document.contains("getCookieStore"), document);
    }

    @Test
    public void aScopeWithNothingInItSaysWhereTheCallableSurfaceIs() {
        // A honest empty answer rather than an implied absence: kafka declares no module-level function, and the
        // reply names the verbs that DO have something plus their counts.
        String document = markdown(Containers.render(FixtureCorpus.loadedFixture("ballerinax__kafka"),
                Surface.Scope.MODULE, Containers.Options.bare()), "kafka funcs");
        Assert.assertTrue(document.contains("| Module functions | this package declares none |"), document);
        Assert.assertTrue(document.contains("`bal discover ballerinax/kafka client`"), document);
        Assert.assertTrue(document.contains("`bal discover ballerinax/kafka class`"), document);
    }

    @Test
    public void aModuleFunctionCarriesPublicAndAMemberDoesNot() {
        // The renderer is chosen by SCOPE rather than by shape, which is the same split the API document draws.
        // Asserting the keyword is what catches the wrong one. The bare funcs listing is now the structured IR
        // (http declares 7 standalone functions), which carries no declaration text at all, so this resolves ONE
        // by name to reach the full Markdown signature — same as the member case right below it.
        String funcs = markdown(Containers.render(FixtureCorpus.loadedFixture("ballerina__http"),
                Surface.Scope.MODULE, new Containers.Options(List.of("getDefaultListener"))), "getDefaultListener");
        Assert.assertTrue(funcs.contains("public isolated function "), funcs);
        // Cookie's own bare listing is now the structured IR too (it declares several methods), so this
        // resolves ONE member by name — the same reason the module-function half above does — and checks that
        // ITS signature carries no `public`, which a class member never does.
        String cookie = markdown(Containers.render(FixtureCorpus.loadedFixture("ballerina__http"),
                Surface.Scope.CLASS, new Containers.Options(List.of("Cookie", "toStringValue"))), "Cookie");
        Assert.assertFalse(cookie.contains("\npublic isolated function "),
                "a member does not carry public");
    }

    /**
     * A selector spelled the way the document PRINTS it resolves, in one token or in two.
     *
     * <p>The measured defect: {@code client ballerinax/slack Client "post chat\.postMessage"} matched nothing,
     * while the same request with the accessor as its own argument matched. Ballerina escapes the dot in a path
     * segment, so {@code chat\.postMessage} is what every fenced signature this tool emits contains — and an
     * agent that copies one back as a single quoted argument was told there is no such operation, on a client
     * declaring 174. It cost a real run one call.
     *
     * <p>The cause is not the tree, which has always taken both spellings: it is that a one-token selector is
     * matched against an entry's LABEL, and the label is built unescaped for prose. So the two spellings met on
     * a comparison that had never been given the same normalisation the path walk has, and the register split
     * that the whole design rests on leaked into the argument grammar.
     */
    @Test
    public void aSelectorResolvesInTheSpellingTheDocumentPrints() {
        LoadedPackage slack = FixtureCorpus.loadedFixture("ballerinax__slack");
        for (List<String> selectors : List.of(
                List.of("Client", "post chat\\.postMessage"),
                List.of("Client", "post chat.postMessage"),
                List.of("Client", "post", "chat\\.postMessage"),
                List.of("Client", "post", "chat.postMessage"))) {
            String document = markdown(Containers.render(slack, Surface.Scope.CLIENT,
                    new Containers.Options(selectors)), selectors.toString());
            Assert.assertTrue(document.contains("resource function post chat\\.postMessage"),
                    selectors + " did not resolve:\n" + document);
        }

        // The same for github's `-`, which needs the same escape INSIDE a fence and appears in 40-odd paths, but
        // reads back unescaped — `readableSegment`'s own reasoning: `code\-scanning` is correct source, and
        // `code-scanning` is what is typeable and reportable outside one. `alerts` has more than one accessor,
        // so this lands on the structured IR's own `path` field rather than one full fenced signature.
        LoadedPackage github = FixtureCorpus.loadedFixture("ballerinax__github");
        for (String path : List.of("code\\-scanning/alerts", "code-scanning/alerts")) {
            Containers.Answer answer = expectAnswer(Containers.render(github, Surface.Scope.CLIENT,
                    new Containers.Options(List.of("Client", "get repos/{owner}/{repo}/" + path))), path);
            Assert.assertTrue(resourcePaths(answer).stream().anyMatch(p -> p.contains("code-scanning/alerts")),
                    path + ": " + resourcePaths(answer));
        }
    }

    /**
     * A miss in the CODE register is bounded, like a miss in the report register already was.
     *
     * <p>Measured: {@code client ballerinax/github Client nosuchthingatall -r} answered with 42,746 bytes — every
     * one of 903 labels, on a single line, for a typo. The report register answers the same miss in about 800 and
     * points at the listing, so the budget rule held everywhere except the one path a caller reaches
     * by making a mistake, which is the path least worth spending 10,000 tokens on.
     *
     * <p>Bounded by the same {@code MAX_LISTING_BYTES}, and it must still name the recovery — an empty answer
     * that offers no next command is exactly the failure this guards against.
     */
    /**
     * {@code new} addresses the constructor, which Ballerina spells {@code init}.
     *
     * <p>Measured twice, in two separate sweeps: an agent asked for
     * {@code client ballerinax/redis Client new}, was told nothing matched on a container declaring 112 members,
     * and found it on the next call as {@code init}. One wasted round trip each time, for a guess that is correct
     * in most languages an agent has read.
     *
     * <p>An INPUT alias, not an output one — the document still prints {@code init}, because it prints what the
     * package declares. That is the same trade already made for {@code {owner}} / {@code [string owner]} and for
     * the escaped path spellings: the tool accepts what a caller will plausibly type and quotes only what is real.
     *
     * <p><b>A REAL {@code new} wins.</b> github declares {@code repos/[string owner]/[string repo]/codespaces/'new},
     * so on that client the token addresses an operation and the alias must not shadow it. The path walk runs before
     * the alias for exactly this reason, and the second half of this test pins it — an alias that outranked a
     * declared name would be the tool answering a different question from the one asked.
     */
    @Test
    public void newAddressesTheConstructorUnlessSomethingIsReallyCalledThat() {
        for (String slug : FixtureCorpus.listFixtures()) {
            LoadedPackage loaded = FixtureCorpus.loadedFixture(slug);
            for (Surface.Container container : Surface.of(loaded.library(), Surface.Scope.CLIENT)) {
                if (container.constructor().isEmpty() || declaresNew(container)) {
                    continue;
                }
                String document = markdown(Containers.render(loaded, Surface.Scope.CLIENT,
                        new Containers.Options(List.of(container.name(), "new"))),
                        slug + "/" + container.name() + " new");
                Assert.assertFalse(document.contains("| Matched | nothing"),
                        slug + "/" + container.name() + ": `new` found no constructor:\n" + document);
                Assert.assertTrue(document.contains("function init("),
                        slug + "/" + container.name() + ": the real name is not printed:\n" + document);
            }
        }

        // And the declared one wins where there is one.
        String github = markdown(Containers.render(FixtureCorpus.loadedFixture("ballerinax__github"),
                Surface.Scope.CLIENT, new Containers.Options(List.of("Client", "new"))), "github Client new");
        Assert.assertTrue(github.contains("codespaces/'new("),
                "a declared `new` must outrank the constructor alias:\n" + github);
    }

    /** Does this container hold anything genuinely named {@code new} — a member, or a path segment? */
    private static boolean declaresNew(Surface.Container container) {
        return container.memberNames().stream().anyMatch(name -> name.equalsIgnoreCase("new"))
                || container.operations().stream()
                        .anyMatch(operation -> operation.segments().stream()
                                .anyMatch(segment -> PathTree.readableSegment(segment).equalsIgnoreCase("new")));
    }

    @Test
    public void aMissInTheCodeRegisterIsBoundedAndStillNamesTheRecovery() {
        for (String slug : FixtureCorpus.listFixtures()) {
            LoadedPackage loaded = FixtureCorpus.loadedFixture(slug);
            for (Surface.Container container : Surface.of(loaded.library(), Surface.Scope.CLIENT)) {
                String document = markdown(Containers.render(loaded, Surface.Scope.CLIENT,
                        new Containers.Options(
                                List.of(container.name(), "zzznosuchthing"), null, true, false, 1)),
                        slug + "/" + container.name() + " -r zzznosuchthing");
                Assert.assertTrue(Texts.byteLength(document) <= 4_000,
                        slug + "/" + container.name() + ": a miss cost "
                                + Texts.byteLength(document) + " bytes:\n" + document);
                // Still Ballerina, and still a way out.
                Assert.assertFalse(document.contains("| "), slug + ": a table in the code register");
                Assert.assertTrue(document.contains("bal discover " + loaded.qualified().qualified() + " client"),
                        slug + ": the miss offers no next command:\n" + document);
            }
        }
    }

    @Test
    public void aSelectorThatMatchesNothingIsAnsweredWithWhatIsThere() {
        // Exit 0 with the alternatives rather than a failure: an empty selection is a fact about the container,
        // and the caller's next move is in the document.
        for (String slug : FixtureCorpus.listFixtures()) {
            for (Surface.Container container : Surface.of(
                    FixtureCorpus.libraryFor(slug), Surface.Scope.CLIENT)) {
                String document = markdown(Containers.render(FixtureCorpus.loadedFixture(slug),
                        Surface.Scope.CLIENT,
                        new Containers.Options(List.of(container.name(), "zzznosuchthing"))),
                        slug + "/" + container.name() + " zzznosuchthing");
                Assert.assertTrue(document.contains("| Requested | `zzznosuchthing` |"), document);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Anchored paths, and locating one segment under a matched prefix
    // -----------------------------------------------------------------------

    @Test
    public void aWildcardNamesEveryBranchItAlsoMatchedRatherThanTakingTheBusiestSilently() {
        // GITHUB-02. `*` matches any child and children are ordered busiest-first, so `repos/*/*` meant "the
        // busiest branch" and returned 420 of 421 under exit 0 with nothing in the header to say so. The
        // container is over the ceiling here (grouped, structured), so the branch not taken travels as the
        // structured answer's own `note` rather than a Markdown facts row.
        LoadedPackage github = FixtureCorpus.loadedFixture("ballerinax__github");
        String note = clientNote(github, "repos/*/*");
        // Named by where it GOES, not where it forks: `repos/{templateOwner}` alone is not an address.
        Assert.assertNotNull(note, "repos/*/* should name the branch it did not take");
        Assert.assertTrue(note.contains("also matched `repos/:templateOwner/:templateRepo/generate` (1), "
                + "not included here"), note);
        // And only when a branch was actually dropped, or it is noise on every other lookup: `repos` alone never
        // walks into the parameter level at all, so there is no fork to report yet.
        Assert.assertNull(clientNote(github, "repos"), "no fork was walked into yet");
    }

    @Test
    public void aTrailingSegmentIsLocatedUnderTheMatchedPrefixWhenItIsUnambiguous() {
        // `repos/owner/repo/caches` is a real request: `caches` exists, at `.../actions/caches`. The anchored
        // answer — "no `caches` under `repos/{owner}/{repo}`" — is correct and costs a round trip.
        LoadedPackage github = FixtureCorpus.loadedFixture("ballerinax__github");
        Containers.Answer answer = clientAnswer(github, "repos/owner/repo/caches");
        DiscoverResult.ResourceList resources = (DiscoverResult.ResourceList) structuredResultOf(answer);
        Assert.assertEquals(resources.note(), "relocated to `repos/:owner/:repo/actions/caches` — the only "
                + "match for that segment under the requested prefix");
        Assert.assertTrue(resources.resources().stream()
                        .anyMatch(resource -> resource.path().equals("repos/:owner/:repo/actions/caches")),
                resources.toString());
    }

    @Test
    public void aTrailingSegmentFoundInSeveralPlacesIsListedRatherThanPicked() {
        // Rule 2, and it is the whole reason anchoring exists: picking one of several is the failure the anchored
        // walk was built to prevent, so the answer stops at the list — still the Markdown `nothingMatched`
        // fallback, since an ambiguous relocation resolves to no entries at all rather than to too many.
        LoadedPackage github = FixtureCorpus.loadedFixture("ballerinax__github");
        // `secrets` exists under `actions`, `codespaces` AND `dependabot`, and they are three different APIs.
        String document = client(github, "repos/owner/repo/secrets");
        Assert.assertTrue(document.contains("3 paths carry that segment"), document);
        Assert.assertTrue(document.contains("repos/:owner/:repo/actions/secrets"), document);
        Assert.assertTrue(document.contains("repos/:owner/:repo/dependabot/secrets"), document);
        // Every path it offers has to be one the tree actually holds, which is asserted exhaustively in
        // ViewsAgreeTest; here the point is that more than one was found and none was chosen.
        Assert.assertFalse(document.contains("| Located |"), document);
    }

    @Test
    public void placeholderSpellingsAllAddressTheSameSegment() {
        // Already shipped and kept: an agent that copied a path out of a fenced signature types `[string owner]`,
        // one that read it off a tree types `{owner}`, and one that typed it from memory types `owner`. All three
        // reach the exact same structured answer — records compare by value, so equality is the whole check.
        LoadedPackage github = FixtureCorpus.loadedFixture("ballerinax__github");
        DiscoverResult bare = structuredResultOf(clientAnswer(github, "repos/owner/repo"));
        Assert.assertEquals(structuredResultOf(clientAnswer(github, "repos/{owner}/{repo}")), bare);
        Assert.assertEquals(structuredResultOf(clientAnswer(github, "repos/[string owner]/[string repo]")), bare);
        Assert.assertNotNull(clientNote(github, "repos/*/*"));
    }

    /** The structured result inside a {@code client} bucket answer that is expected not to be Markdown. */
    private static DiscoverResult structuredResultOf(Containers.Answer answer) {
        Assert.assertTrue(answer instanceof Containers.Answer.Structured, answer.toString());
        return ((Containers.Answer.Structured) answer).result();
    }

    /** The {@code note} field of a structured resource answer, or {@code null} for a flat resource list too. */
    private static String clientNote(LoadedPackage loaded, String path) {
        return switch (structuredResultOf(clientAnswer(loaded, path))) {
            case DiscoverResult.ResourceList resources -> resources.note();
            case DiscoverResult.PathGroups groups -> groups.note();
            default -> throw new AssertionError("not a resource-shaped answer for " + path);
        };
    }

    private static Containers.Answer clientAnswer(LoadedPackage loaded, String path) {
        return expectAnswer(Containers.render(loaded, Surface.Scope.CLIENT,
                new Containers.Options(List.of("Client", path))), "Client " + path);
    }

    private static String client(LoadedPackage loaded, String path) {
        return markdown(Containers.render(loaded, Surface.Scope.CLIENT,
                new Containers.Options(List.of("Client", path))), "Client " + path);
    }

    /** A structured answer's resource paths, for a test that does not care which listing shape it landed on. */
    private static List<String> resourcePaths(Containers.Answer answer) {
        return switch (answer) {
            case Containers.Answer.Structured structured -> switch (structured.result()) {
                case DiscoverResult.ResourceList resources ->
                        resources.resources().stream().map(DiscoverResult.ResourceList.Resource::path).toList();
                case DiscoverResult.PathGroups groups ->
                        groups.groups().stream().map(DiscoverResult.PathGroups.Group::name).toList();
                default -> throw new AssertionError("not a resource-shaped answer: " + structured.result());
            };
            case Containers.Answer.Markdown markdown -> throw new AssertionError(
                    "expected a structured resource answer, got Markdown: " + markdown.text());
        };
    }

    @Test
    public void searchingAContainerFiltersOnParameterAndTypeNamesTooNotOnlyOnTheName() {
        // The reason `--filter` searches the signature rather than the name alone: an agent that knows it holds
        // an `ActionsCacheList` and wants the call returning one has no other way to ask. github's client is
        // over the ceiling, so a `--filter`-narrowed answer is the structured IR — never grouped or paginated,
        // since a filter is a claim the caller already knows roughly what they want.
        Containers.Answer answer = expectAnswer(Containers.render(
                FixtureCorpus.loadedFixture("ballerinax__github"), Surface.Scope.CLIENT,
                new Containers.Options(List.of(), "ActionsCacheList", false, false, 1)), "ActionsCacheList");
        List<String> paths = resourcePaths(answer);
        Assert.assertFalse(paths.isEmpty(), "ActionsCacheList should match at least one resource path");
    }

    /**
     * {@code --filter} is a single keyword, not a path matcher — the RFC's own "simple case-insensitive
     * substring/keyword match", replacing this tool's earlier {@code -s} flag's unordered AND over every
     * whitespace/slash-split token. A multi-segment path narrows through the POSITIONAL selector instead
     * ({@code aSelectorResolvesInTheSpellingTheDocumentPrints} and friends already cover that walk); this is
     * what a single keyword — including one built out of segment-shaped text — still finds as a contiguous
     * substring.
     */
    @Test
    public void filterIsASingleKeywordSubstringNotAPathMatcher() {
        LoadedPackage github = FixtureCorpus.loadedFixture("ballerinax__github");
        // One literal segment, contiguous in the space-joined surface text of any resource under it.
        Containers.Answer answer = expectAnswer(Containers.render(github, Surface.Scope.CLIENT,
                new Containers.Options(List.of(), "caches", false, false, 1)), "caches");
        Assert.assertFalse(resourcePaths(answer).isEmpty(), "caches should match at least one resource path");

        // A query that is genuinely absent still reports nothing, or the fix would just be a match-everything.
        // That "nothing" is the Markdown `nothingMatched` fallback — an empty selection is a fact about the
        // container, answered with what IS there, never an empty structured listing.
        Containers.Answer absent = expectAnswer(Containers.render(github, Surface.Scope.CLIENT,
                new Containers.Options(List.of(), "zzznopealsonope", false, false, 1)), "zzznopealsonope");
        Assert.assertTrue(isNothingMatched(absent), "a genuinely absent keyword must match nothing: " + absent);
    }

    /**
     * The escaped spelling is searchable too, because it is the one the documents print.
     *
     * <p>The same normalisation the positional selector got: a caller who copies
     * {@code chat\.postMessage} out of a fenced signature and puts it behind {@code --filter} is copying this
     * tool's own output.
     */
    @Test
    public void searchingAContainerAcceptsTheEscapedSpelling() {
        // slack's Client has exactly one resource matching either spelling, so the filtered selection narrows to
        // one entry and answers in full — the same `fullAnswer` a one-of-many name match reaches.
        LoadedPackage slack = FixtureCorpus.loadedFixture("ballerinax__slack");
        for (String query : List.of("chat\\.postMessage", "chat.postMessage")) {
            String answer = markdown(Containers.render(slack, Surface.Scope.CLIENT,
                    new Containers.Options(List.of(), query, false, false, 1)), query);
            Assert.assertTrue(answer.contains("chat\\.postMessage"), query + " did not find it: " + answer);
        }
    }

    /**
     * A path parameter answers to brackets WITHOUT the type, which is what half-remembering produces.
     *
     * <p>Measured: an agent typed {@code repos/[owner]/[repo]/issues} — the declaration form
     * {@code [string owner]} with the type dropped — and matched nothing on 903 resource functions. Three
     * spellings resolved and this fourth did not.
     *
     * <p>It was not merely unhandled, it was mis-parsed: the old pattern required something before the name, so
     * on {@code [owner]} it backtracked and read the parameter's name as {@code r}.
     */
    @Test
    public void aPathParameterAnswersToBracketsWithoutTheType() {
        LoadedPackage github = FixtureCorpus.loadedFixture("ballerinax__github");
        for (String path : List.of(
                "repos/[owner]/[repo]/issues",
                "repos/[string owner]/[string repo]/issues",
                "repos/{owner}/{repo}/issues",
                "repos/owner/repo/issues")) {
            Containers.Answer answer = expectAnswer(Containers.render(github, Surface.Scope.CLIENT,
                    new Containers.Options(List.of("Client", path))), path);
            Assert.assertFalse(isNothingMatched(answer), path + " did not resolve: " + answer);
        }
    }

    /**
     * An accessor and a path in ONE argument reach the path walk, whatever spelling the path is in.
     *
     * <p>A caller copying a line out of a fenced signature copies the whole thing —
     * {@code get [PathParamType ...path]} — as a single quoted argument. Split across two arguments every
     * spelling already resolved; as one token only the display spelling did, because a one-token selector was
     * compared against an entry's LABEL and never handed to the walk that understands the other spellings.
     *
     * <p>So the one-token form now splits on its first word when that word is an accessor the container
     * declares. Member names cannot contain whitespace, which is what makes the split safe.
     */
    @Test
    public void anAccessorAndAPathInOneArgumentReachThePathWalk() {
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        for (String selector : List.of(
                "get [PathParamType ...path]",
                "get [path]",
                "get {...path}")) {
            Containers.Answer answer = expectAnswer(Containers.render(http, Surface.Scope.CLIENT,
                    new Containers.Options(List.of("Client", selector))), selector);
            Assert.assertFalse(isNothingMatched(answer), selector + " did not resolve: " + answer);
        }

        // A leading word that is NOT an accessor stays one member name, or `Producer send` would be parsed as
        // an accessor called `Producer`.
        String member = markdown(Containers.render(FixtureCorpus.loadedFixture("ballerinax__kafka"),
                Surface.Scope.CLIENT, new Containers.Options(List.of("Producer", "send"))), "Producer send");
        Assert.assertTrue(member.contains("function send("), member);
    }

    // -----------------------------------------------------------------------
    // The guide
    // -----------------------------------------------------------------------

    @Test
    public void aGuideChunkIsASectionWithItsProseAndIsAddressableTwoWays() {
        // A code-only extract would have discarded about 85% of `googleapis.sheets`' 178-line readme — including
        // "if you intend to use deleteSpreadsheet you must also enable the Google Drive API", which is not
        // inferable from any signature and is the difference between a connector that works and one that 403s.
        LoadedPackage sheets = FixtureCorpus.loadedFixture("ballerinax__googleapis.sheets");
        List<Guide.Chunk> chunks = Guide.chunksOf(sheets);
        Assert.assertFalse(chunks.isEmpty(), "sheets' readme carries code in several sections");

        Result<String> byNumber = Guide.render(sheets, new Guide.Options("1", null, null));
        Assert.assertTrue(byNumber.isOk(), byNumber.isOk() ? "" : byNumber.failure().describe());
        Assert.assertTrue(byNumber.value().contains("| Chunk | 1 of "), byNumber.value());

        Result<String> byTitle = Guide.render(
                sheets, new Guide.Options(chunks.get(0).title(), null, null));
        Assert.assertTrue(byTitle.isOk(), byTitle.isOk() ? "" : byTitle.failure().describe());
        Assert.assertEquals(byTitle.value(), byNumber.value(), "a title and its number are the same chunk");
    }

    @Test
    public void aChunkThatDoesNotExistNamesEveryChunkThatDoes() {
        Result<String> view = Guide.render(FixtureCorpus.loadedFixture("ballerinax__googleapis.sheets"),
                new Guide.Options("999", null, null));
        Assert.assertFalse(view.isOk());
        Assert.assertTrue(view.failure() instanceof Failure.SymbolNotFound);
        Assert.assertTrue(view.failure().describe().contains("1. "), view.failure().describe());
    }

    @Test
    public void aModuleThatPublishesNoGuideIsAFailureThatNamesTheOnesThatDo() {
        // All-or-nothing, like `type`: a `--module` typo answering with every module's readme would be a partial
        // answer under exit 0, which is the silent class this CLI refuses everywhere.
        Result<String> view = Guide.render(
                FixtureCorpus.loadedFixture("ballerinax__kafka"), new Guide.Options("kafkaa"));
        Assert.assertFalse(view.isOk());
        Assert.assertTrue(view.failure().describe().contains("kafka"), view.failure().describe());
    }

    // -----------------------------------------------------------------------
    // The code register
    // -----------------------------------------------------------------------

    @Test
    public void theSubtypeChainIsWhatTheErrorDeclarationsAreFor() {
        LoadedPackage http = FixtureCorpus.loadedFixture("ballerina__http");
        Result<String> view = TypeView.render(http, new TypeView.Options(
                List.of("Error", "ClientRequestError", "SslError"), false));
        Assert.assertTrue(view.isOk(), view.isOk() ? "" : view.failure().describe());
        String document = view.value();
        // Unlearnable before the detail patch: all 56 rendered as `type X error;`.
        Assert.assertTrue(document.contains(
                "\npublic type ClientRequestError distinct (ApplicationResponseError & error<Detail>);\n"));
        Assert.assertTrue(document.contains("\npublic type SslError distinct ClientError;\n"));
        Assert.assertTrue(document.contains("\npublic type Error distinct error;\n"));
        // The rule came here with the declarations, from an `overview` section that no longer exists;
        // without the move it would simply have been deleted.
        Assert.assertTrue(document.contains("// The subtype chain is what `is` tests against"), document);
        // And it is not printed for a lookup that resolved no error, or it is noise on every other one.
        Result<String> plain = TypeView.render(http, new TypeView.Options(List.of("Response"), false));
        Assert.assertTrue(plain.isOk());
        Assert.assertFalse(plain.value().contains("The subtype chain"), plain.value());
    }

    @Test
    public void typeSearchesTheRosterAndNamesWhatItWillNotPrint() {
        // A bare `type <pkg>` must not become a second `api`, so it needs a name or a query. With a query the
        // code register's own two-tier rule applies: surface matches are declarations, documentation-only ones are
        // a `//` line of names, and over budget nothing is rendered and every match is named — which keeps "exit 0
        // means stdout is complete" true where a truncated set of records would not.
        Result<String> narrow = TypeView.render(FixtureCorpus.loadedFixture("ballerinax__kafka"),
                new TypeView.Options(List.of(), "TopicPartition", false));
        Assert.assertTrue(narrow.isOk(), narrow.isOk() ? "" : narrow.failure().describe());
        Assert.assertTrue(narrow.value().contains("// Search: \"TopicPartition\" —"), narrow.value());
        Assert.assertTrue(narrow.value().contains("public type TopicPartition record"), narrow.value());

        Result<String> wide = TypeView.render(FixtureCorpus.loadedFixture("ballerinax__github"),
                new TypeView.Options(List.of(), "repo", false));
        Assert.assertTrue(wide.isOk(), wide.isOk() ? "" : wide.failure().describe());
        Assert.assertTrue(wide.value().contains("over the " + Texts.count(TypeView.MAX_SEARCH_BYTES)
                + "-byte budget"), wide.value());
        Assert.assertTrue(wide.value().contains("// Matched: "), wide.value());
        Assert.assertFalse(wide.value().contains("public type "), "nothing is rendered over budget");
    }

    @Test
    public void aFooterNamesACollisionRatherThanClaimingTheLocalNameIsForeign() {
        // SHEETS-03. `ProxyConfig` sat in a list headed "not included above" in an output that declares a
        // `ProxyConfig` twelve lines earlier — two records, same name, same arity, different fields. The foreign
        // entry has to stay, because the field line needs that import; what was missing is which is which. sheets
        // has TWO such names, not the one the audit found.
        Result<String> view = TypeView.render(
                FixtureCorpus.loadedFixture("ballerinax__googleapis.sheets"),
                new TypeView.Options(List.of("ConnectionConfig"), true));
        Assert.assertTrue(view.isOk());
        Assert.assertTrue(view.value().contains("public type ProxyConfig record {|"), view.value());
        Assert.assertTrue(view.value().contains(
                "//   note: OAuth2RefreshTokenGrantConfig, ProxyConfig above are this package's own "
                        + "declarations of those names, not http's"), view.value());
    }

    @Test
    public void aClientIsAddressableByNameLikeAnyOtherDeclaration() {
        // SAP-09. `type ballerinax/sap Client` failed, asserting the package had no such declaration, and steered
        // the reader to `ClientError` — in a package where the client is 1 of 4 things Central publishes. The name
        // index was built from TypeDefs, and a client was not one.
        LoadedPackage sap = FixtureCorpus.loadedFixture("ballerinax__sap");
        Result<String> view = TypeView.render(sap, new TypeView.Options(List.of("Client"), false));
        Assert.assertTrue(view.isOk(), view.isOk() ? "" : view.failure().describe());
        Assert.assertTrue(view.value().contains("public isolated client class Client {"), view.value());
        // Byte-identical to the same declaration inside the api document, which is the whole `type` contract — and
        // the reason the separate client renderer is gone rather than kept in step by hand.
        String api = FixtureCorpus.renderFixture("ballerinax__sap");
        String declaration = view.value().substring(view.value().indexOf("# The `sap`"));
        Assert.assertTrue(api.contains(declaration.strip()), declaration);
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
        Result<String> byName = TypeView.render(FixtureCorpus.loadedFixture("ballerina__http"),
                new TypeView.Options(List.of("maxActiveConnections"), false));
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
