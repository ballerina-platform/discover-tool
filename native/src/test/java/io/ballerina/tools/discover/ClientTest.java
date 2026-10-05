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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ballerina.tools.discover.central.CentralClient;
import io.ballerina.tools.discover.central.DependenciesToml;
import io.ballerina.tools.discover.central.HttpOptions;
import io.ballerina.tools.discover.central.HttpTransport;
import io.ballerina.tools.discover.central.schema.CentralDocs;
import io.ballerina.tools.discover.central.schema.Schema;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;

/**
 * The boundary, driven without a network: which failures are worth retrying, which are answers, and what each one
 * costs the caller.
 *
 * @since 0.1.0
 */
public class ClientTest {

    private static final QualifiedName GITHUB = QualifiedName.parse("ballerinax/github").value();

    /** Fast enough that the retry path costs a test run nothing. */
    private static HttpOptions.Builder fast(HttpTransport transport) {
        return HttpOptions.builder()
                .transport(transport)
                .maxAttempts(3)
                .baseDelayMs(1)
                .budgetMs(5_000)
                .timeoutMs(1_000)
                .sleeper(millis -> {
                    // The backoff is asserted by `backoffMs` directly; sleeping here only slows the suite.
                });
    }

    // -----------------------------------------------------------------------
    // Retries
    // -----------------------------------------------------------------------

    @Test
    public void a503IsRetriedAndTheRetrysAnswerIsUsed() {
        FakeTransport transport = FakeTransport.scripted(List.of(
                FakeTransport.status(503), FakeTransport.ok("{\"hello\":\"world\"}")));
        Result<JsonElement> result =
                CentralClient.fetchJson("https://example.test/x", fast(transport).build());
        Assert.assertTrue(result.isOk());
        Assert.assertEquals(result.value().getAsJsonObject().get("hello").getAsString(), "world");
        Assert.assertEquals(transport.calls(), 2);
    }

    @Test
    public void retriesStopAtMaxAttemptsAndReportHowManyWereSpent() {
        FakeTransport transport = FakeTransport.scripted(List.of(
                FakeTransport.status(502), FakeTransport.status(502), FakeTransport.status(502)));
        Result<JsonElement> result =
                CentralClient.fetchJson("https://example.test/x", fast(transport).build());
        Assert.assertFalse(result.isOk());
        Failure.Upstream failure = (Failure.Upstream) result.failure();
        Assert.assertEquals(failure.attempts(), 3);
        Assert.assertEquals(transport.calls(), 3);
    }

    @Test
    public void a404IsAnAnswerNotAHiccupSoItIsNeverRetried() {
        FakeTransport transport = FakeTransport.always(FakeTransport.status(404));
        Result<JsonElement> result =
                CentralClient.fetchJson("https://example.test/x", fast(transport).build());
        Assert.assertFalse(result.isOk());
        Assert.assertTrue(result.failure() instanceof Failure.Upstream);
        Assert.assertEquals(transport.calls(), 1);
    }

    @Test
    public void aBodyThatIsNotJsonIsNotRetriedEither() {
        FakeTransport transport =
                FakeTransport.always(FakeTransport.ok("<html>maintenance</html>"));
        Result<JsonElement> result =
                CentralClient.fetchJson("https://example.test/x", fast(transport).build());
        Assert.assertFalse(result.isOk());
        Assert.assertEquals(transport.calls(), 1);
    }

    @Test
    public void aRequestThatNeverAnswersBecomesATimeoutNotAHang() {
        FakeTransport transport = FakeTransport.always(new HttpTransport.Reply.TimedOut());
        Result<JsonElement> result = CentralClient.fetchJson(
                "https://example.test/slow", fast(transport).maxAttempts(1).build());
        Assert.assertFalse(result.isOk());
        Assert.assertTrue(result.failure() instanceof Failure.Timeout);
    }

    @Test
    public void retryAfterIsHonouredInBothOfItsLegalForms() {
        long now = 1_700_000_000_000L;
        Assert.assertEquals(CentralClient.parseRetryAfter("120", now), 120_000);
        Assert.assertEquals(CentralClient.parseRetryAfter("  30  ", now), 30_000);
        // The date form, one minute into the future from `now`.
        String date = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.Instant.ofEpochMilli(now + 60_000).atZone(java.time.ZoneOffset.UTC));
        Assert.assertEquals(CentralClient.parseRetryAfter(date, now), 60_000);
        // A date already past is zero rather than negative, so it cannot rewind the deadline.
        String past = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
                java.time.Instant.ofEpochMilli(now - 60_000).atZone(java.time.ZoneOffset.UTC));
        Assert.assertEquals(CentralClient.parseRetryAfter(past, now), 0);
        Assert.assertEquals(CentralClient.parseRetryAfter(null, now), -1);
        Assert.assertEquals(CentralClient.parseRetryAfter("not a delay", now), -1);
    }

    @Test
    public void backoffGrowsExponentiallyAndStaysInsideItsJitterBand() {
        // The jitter exists so parallel callers do not resonate; the band is what keeps it bounded.
        for (int attempt = 0; attempt < 4; attempt++) {
            long floor = CentralClient.backoffMs(attempt, 200, 0.0);
            long ceiling = CentralClient.backoffMs(attempt, 200, 1.0);
            Assert.assertEquals(floor, (long) (200 * Math.pow(2, attempt)));
            Assert.assertEquals(ceiling, (long) (200 * Math.pow(2, attempt) * 1.25));
        }
    }

    // -----------------------------------------------------------------------
    // Version resolution
    // -----------------------------------------------------------------------

    /**
     * A version a PROJECT locked that Central does not publish.
     *
     * <p>T10, and the only reachable "supplied version" path now that there is no {@code --version} flag: the
     * version came out of a {@code Dependencies.toml}, so "omit the version to take the latest" names a step the
     * caller cannot take. The failure lists what IS published instead, which is why no {@code versions} verb is
     * needed — and the list is fetched on a path that has already failed, so it degrades to no list rather than to
     * a different failure.
     */
    @Test
    public void aVersionTheProjectLockedAndCentralDoesNotPublishNamesTheOnesItDoes() {
        FakeTransport transport = FakeTransport.routing(url -> url.contains("/docs/")
                ? FakeTransport.status(404)
                : FakeTransport.ok("[\"6.0.0\", \"5.1.0\"]"));
        Result<CentralDocs> result = CentralClient.fetchDocs(
                GITHUB, supplied("9.9.9"), fast(transport).build());
        Assert.assertFalse(result.isOk());
        Failure.PackageNotFound failure = (Failure.PackageNotFound) result.failure();
        Assert.assertEquals(failure.qualified(), "ballerinax/github:9.9.9");
        Assert.assertTrue(failure.suggestion().contains("Your project locks"), failure.suggestion());
        Assert.assertTrue(failure.suggestion().contains("published versions are 6.0.0, 5.1.0"),
                failure.suggestion());
        Assert.assertFalse(failure.suggestion().contains("omit the version"),
                "there is no version argument to omit");
    }

    @Test
    public void aRegistryThatCannotListVersionsStillReportsTheFailureItWasAsked() {
        // Best-effort, and it has to stay that way: a second failure while composing a message must not replace
        // the failure the caller actually hit.
        FakeTransport transport = FakeTransport.always(FakeTransport.status(500));
        Result<CentralDocs> result = CentralClient.fetchDocs(
                GITHUB, supplied("9.9.9"), fast(transport).build());
        Assert.assertFalse(result.isOk());
        Assert.assertTrue(result.failure() instanceof Failure.Upstream, result.failure().describe());
    }

    @Test
    public void aNotFoundOnAVersionTheReaderResolvedBlamesTheNameRatherThanTheVersion() {
        // The reader resolved the version, so "omit the version" would name a step the caller never took. It is
        // reachable when the registry lists a version Central has no docs page for, or a cached latest answer
        // outlives the page it named.
        FakeTransport transport = FakeTransport.always(FakeTransport.status(404));
        Result<CentralDocs> result = CentralClient.fetchDocs(
                GITHUB, resolved("6.0.0"), fast(transport).build());
        Assert.assertFalse(result.isOk());
        Failure.PackageNotFound failure = (Failure.PackageNotFound) result.failure();
        Assert.assertFalse(failure.suggestion().contains("omit the version"), failure.suggestion());
        Assert.assertTrue(failure.suggestion().contains("Check the name"), failure.suggestion());
    }

    /** A version the caller pinned. */
    private static CentralClient.ResolvedVersion supplied(String version) {
        return new CentralClient.ResolvedVersion(Version.parse(version).value(), false, true);
    }

    /** A version the reader resolved on the caller's behalf. */
    private static CentralClient.ResolvedVersion resolved(String version) {
        return new CentralClient.ResolvedVersion(Version.parse(version).value(), false, false);
    }

    @Test
    public void theNewestVersionIsTheFirstEntryCentralReturns() {
        FakeTransport transport =
                FakeTransport.always(FakeTransport.ok("[\"6.0.0\",\"5.4.1\",\"5.4.0\"]"));
        Result<CentralClient.ResolvedVersion> result =
                CentralClient.resolveLatestVersion(GITHUB, fast(transport).build());
        Assert.assertTrue(result.isOk());
        Assert.assertEquals(result.value().version().text(), "6.0.0");
        Assert.assertFalse(result.value().stale());
    }

    @Test
    public void centrals400ForAnUnpublishedNameReadsAsNoSuchPackage() {
        // The most common caller mistake is a typo, and Central reports it as a 400.
        FakeTransport transport = FakeTransport.always(FakeTransport.status(400));
        Result<CentralClient.ResolvedVersion> result =
                CentralClient.resolveLatestVersion(GITHUB, fast(transport).build());
        Assert.assertFalse(result.isOk());
        Assert.assertTrue(result.failure() instanceof Failure.PackageNotFound);
        Assert.assertEquals(transport.calls(), 1);
    }

    @Test
    public void anEmptyVersionListMeansThePackageDoesNotExist() {
        FakeTransport transport = FakeTransport.always(FakeTransport.ok("[]"));
        Result<CentralClient.ResolvedVersion> result =
                CentralClient.resolveLatestVersion(GITHUB, fast(transport).build());
        Assert.assertFalse(result.isOk());
        Assert.assertTrue(result.failure() instanceof Failure.PackageNotFound);
    }

    @Test
    public void theVersionCentralReportsGoesThroughTheParserRatherThanBeingTrusted() {
        // It becomes a cache path segment, and `..` satisfies Central's own format.
        FakeTransport transport = FakeTransport.always(FakeTransport.ok("[\"..\"]"));
        Result<CentralClient.ResolvedVersion> result =
                CentralClient.resolveLatestVersion(GITHUB, fast(transport).maxAttempts(1).build());
        Assert.assertFalse(result.isOk());
        Assert.assertTrue(result.failure() instanceof Failure.Validation);
    }

    // -----------------------------------------------------------------------
    // Modules of a package
    //
    // The coordinate is always one literal package name. A dotted name the registry has no row for is probed
    // against its prefixes only to say which package it is a module of — never to read the module in the
    // package's place.
    // -----------------------------------------------------------------------

    private static final QualifiedName AWS_AUTH = QualifiedName.parse("ballerinax/aws.auth").value();

    /** Routes the registry by coordinate, which is the only thing these tests need to tell calls apart. */
    private static FakeTransport registry(Map<String, String> versionsByCoordinate) {
        return FakeTransport.routing(url -> {
            for (Map.Entry<String, String> entry : versionsByCoordinate.entrySet()) {
                if (url.endsWith("/registry/packages/" + entry.getKey())) {
                    return FakeTransport.ok(entry.getValue());
                }
            }
            return FakeTransport.status(404);
        });
    }

    @Test
    public void aDottedNameThatIsItsOwnPackageNeverProbesAParent() {
        // The common case by a wide margin — `googleapis.sheets`, `googleapis.gmail`, `aws.s3` are all
        // packages, and each costs the one round trip any package does.
        QualifiedName sheets = QualifiedName.parse("ballerinax/googleapis.sheets").value();
        FakeTransport transport = registry(Map.of("ballerinax/googleapis.sheets", "[\"5.0.0\"]"));
        Result<CentralClient.ResolvedVersion> result =
                CentralClient.resolveLatestVersion(sheets, fast(transport).build());
        Assert.assertTrue(result.isOk());
        Assert.assertEquals(result.value().version().text(), "5.0.0");
        Assert.assertEquals(transport.calls(), 1);
    }

    /** One version's registry row, listing the modules it publishes. */
    private static String modules(String... names) {
        StringBuilder rows = new StringBuilder();
        for (String name : names) {
            rows.append(rows.isEmpty() ? "" : ",").append("{\"name\":\"").append(name).append("\"}");
        }
        return "{\"modules\":[" + rows + "]}";
    }

    private static String suggestion(Result<CentralClient.ResolvedVersion> result) {
        Assert.assertFalse(result.isOk());
        return ((Failure.PackageNotFound) result.failure()).suggestion();
    }

    @Test
    public void aModuleOfAPackageIsNotReadAsOneAndNamesTheCommandThatReadsIt() {
        FakeTransport transport = registry(Map.of(
                "ballerinax/aws", "[\"1.0.1\",\"1.0.0\"]",
                "ballerinax/aws/1.0.1", modules("aws", "aws.auth")));
        Result<CentralClient.ResolvedVersion> result =
                CentralClient.resolveLatestVersion(AWS_AUTH, fast(transport).build());
        Assert.assertEquals(((Failure.PackageNotFound) result.failure()).qualified(), "ballerinax/aws.auth");
        String suggestion = suggestion(result);
        Assert.assertTrue(suggestion.contains("the 'auth' module of the ballerinax/aws package"), suggestion);
        Assert.assertTrue(suggestion.contains("`bal discover ballerinax/aws --module auth`"), suggestion);
        // The module's own row, the package's, then the package version's module list — never its docs.
        Assert.assertEquals(transport.calls(), 3);
        Assert.assertTrue(transport.urls().stream().noneMatch(url -> url.contains("/docs/")),
                transport.urls().toString());
    }

    @Test
    public void theProbeTriesEachShorterPrefixAndKeepsTheWholeRemainderAsTheModule() {
        // `a.b.c` is a module of `a.b` when that exists and of `a` when it does not; `--module` composes its
        // value back onto the package name, so the remainder it is handed is `b.c`, dots and all.
        QualifiedName deep = QualifiedName.parse("ballerina/one.two.three").value();
        FakeTransport transport = registry(Map.of(
                "ballerina/one", "[\"3.1.0\"]",
                "ballerina/one/3.1.0", modules("one", "one.two.three")));
        String suggestion = suggestion(CentralClient.resolveLatestVersion(deep, fast(transport).build()));
        Assert.assertTrue(suggestion.contains("`bal discover ballerina/one --module two.three`"), suggestion);
        Assert.assertEquals(transport.calls(), 4);
    }

    @Test
    public void aRecordedRegistryRowConfirmsTheModuleTheCommandOffers() {
        String row = FixtureCorpus.loadRawRegistryRow("ballerina__graphql").toString();
        QualifiedName subgraph = QualifiedName.parse("ballerina/graphql.subgraph").value();
        FakeTransport transport = registry(Map.of(
                "ballerina/graphql", "[\"1.17.0\"]",
                "ballerina/graphql/1.17.0", row));
        String suggestion = suggestion(CentralClient.resolveLatestVersion(subgraph, fast(transport).build()));
        Assert.assertEquals(suggestion, "'ballerina/graphql.subgraph' is not a package: it is the 'subgraph' module "
                + "of the ballerina/graphql package. Read it with `bal discover ballerina/graphql --module subgraph`.");
    }

    @Test
    public void aModuleTheContainingPackageDoesNotPublishIsNotOfferedAsOne() {
        QualifiedName nope = QualifiedName.parse("ballerinax/aws.nope").value();
        FakeTransport transport = registry(Map.of(
                "ballerinax/aws", "[\"1.0.1\"]",
                "ballerinax/aws/1.0.1", modules("aws", "aws.auth")));
        String suggestion = suggestion(CentralClient.resolveLatestVersion(nope, fast(transport).build()));
        Assert.assertTrue(suggestion.contains("publishes no 'nope' module (its modules are aws, aws.auth)"),
                suggestion);
        Assert.assertFalse(suggestion.contains("--module"), suggestion);
        Assert.assertTrue(suggestion.contains("`bal search <keyword>`"), suggestion);
    }

    @Test
    public void aModuleListTheRegistryCannotServeStillOffersTheCommandWithoutClaimingTheModule() {
        FakeTransport transport = registry(Map.of("ballerinax/aws", "[\"1.0.1\"]"));
        String suggestion = suggestion(CentralClient.resolveLatestVersion(AWS_AUTH, fast(transport).build()));
        Assert.assertTrue(suggestion.contains("Central could not say which modules it publishes. If 'auth' is one "
                + "of them"), suggestion);
        Assert.assertTrue(suggestion.contains("`bal discover ballerinax/aws --module auth`"), suggestion);
    }

    @Test
    public void aModuleListLostToTheTransportIsUnknownRatherThanEmpty() {
        FakeTransport transport = FakeTransport.routing(url -> {
            if (url.endsWith("/registry/packages/ballerinax/aws")) {
                return FakeTransport.ok("[\"1.0.1\"]");
            }
            return url.endsWith("/registry/packages/ballerinax/aws/1.0.1")
                    ? FakeTransport.status(503)
                    : FakeTransport.status(404);
        });
        String suggestion = suggestion(CentralClient.resolveLatestVersion(AWS_AUTH, fast(transport).build()));
        Assert.assertTrue(suggestion.contains("Central could not say which modules it publishes"), suggestion);
        Assert.assertTrue(suggestion.contains("`bal discover ballerinax/aws --module auth`"), suggestion);
        // The row is retried like any other registry call before it is given up on.
        Assert.assertEquals(transport.urls().stream().filter(url -> url.endsWith("/aws/1.0.1")).count(), 3);
    }

    @Test
    public void aModuleListInAnUnexpectedShapeIsUnknownRatherThanEmpty() {
        FakeTransport transport = registry(Map.of(
                "ballerinax/aws", "[\"1.0.1\"]",
                "ballerinax/aws/1.0.1", "{\"modules\":\"aws, aws.auth\"}"));
        String suggestion = suggestion(CentralClient.resolveLatestVersion(AWS_AUTH, fast(transport).build()));
        Assert.assertTrue(suggestion.contains("Central could not say which modules it publishes"), suggestion);
    }

    @Test
    public void aModuleListThatIsReadButEmptyIsNotListedRatherThanUnknown() {
        FakeTransport transport = registry(Map.of(
                "ballerinax/aws", "[\"1.0.1\"]",
                "ballerinax/aws/1.0.1", modules()));
        String suggestion = suggestion(CentralClient.resolveLatestVersion(AWS_AUTH, fast(transport).build()));
        Assert.assertTrue(suggestion.contains("ballerinax/aws publishes no 'auth' module. Check the name"), suggestion);
        Assert.assertFalse(suggestion.contains("--module"), suggestion);
    }

    @Test
    public void aNameThatIsNeitherAPackageNorAModuleSaysWhatWasTried() {
        FakeTransport transport = registry(Map.of());
        Result<CentralClient.ResolvedVersion> result =
                CentralClient.resolveLatestVersion(AWS_AUTH, fast(transport).build());
        Assert.assertFalse(result.isOk());
        Failure.PackageNotFound failure = (Failure.PackageNotFound) result.failure();
        Assert.assertEquals(failure.qualified(), "ballerinax/aws.auth");
        Assert.assertTrue(failure.suggestion().contains("tried ballerinax/aws"), failure.suggestion());
        Assert.assertTrue(failure.suggestion().contains("`bal search <keyword>`"), failure.suggestion());
    }

    @Test
    public void aParentTheRegistryCannotAnswerForIsNotReportedAsMissing() {
        FakeTransport transport = FakeTransport.routing(url -> url.endsWith("/registry/packages/ballerinax/aws")
                ? FakeTransport.status(500)
                : FakeTransport.status(404));
        Result<CentralClient.ResolvedVersion> result =
                CentralClient.resolveLatestVersion(AWS_AUTH, fast(transport).maxAttempts(1).build());
        Assert.assertFalse(result.isOk());
        Failure.PackageNotFound failure = (Failure.PackageNotFound) result.failure();
        Assert.assertFalse(failure.suggestion().contains("tried"), failure.suggestion());
    }

    @Test
    public void aModuleWhoseOwnRowIsUnreachableDoesNotProbeAParent() {
        // A 500 on the module's own row is a transport fact, not "no such package", and must not be converted
        // into one by a probe that never should have started.
        FakeTransport transport = FakeTransport.always(FakeTransport.status(500));
        Result<CentralClient.ResolvedVersion> result =
                CentralClient.resolveLatestVersion(AWS_AUTH, fast(transport).maxAttempts(1).build());
        Assert.assertFalse(result.isOk());
        Assert.assertTrue(result.failure() instanceof Failure.Upstream, result.failure().getClass().getName());
    }

    // -----------------------------------------------------------------------
    // Suggestions
    // -----------------------------------------------------------------------

    @Test
    public void everyFailureTheOutsideWorldCanCauseCarriesASuggestion() {
        // Three of them used to omit it — the three that fire during a Central outage, when the reader has
        // nothing else to offer.
        Result<JsonElement> timeout = CentralClient.fetchJson("https://example.invalid/x",
                fast(FakeTransport.always(new HttpTransport.Reply.TimedOut())).maxAttempts(1).build());
        Assert.assertTrue(timeout.failure() instanceof Failure.Timeout);
        Assert.assertFalse(((Failure.Timeout) timeout.failure()).suggestion().isEmpty());

        Result<JsonElement> upstream = CentralClient.fetchJson("https://example.invalid/x",
                fast(FakeTransport.always(FakeTransport.status(500))).maxAttempts(1).build());
        Assert.assertTrue(upstream.failure() instanceof Failure.Upstream);
        Assert.assertFalse(((Failure.Upstream) upstream.failure()).suggestion().isEmpty());

        Result<CentralDocs> drift = Schema.parse(
                com.google.gson.JsonParser.parseString("{\"docsData\":{\"modules\":[]}}"), "x/y:1.0.0");
        Assert.assertFalse(drift.isOk());
        Failure.SchemaDrift failure = (Failure.SchemaDrift) drift.failure();
        // Addressed to a human on purpose: no argument the agent can change will make a payload this reader
        // cannot parse.
        Assert.assertTrue(failure.suggestion().contains("Report the"));
    }

    /**
     * The drift this exists to catch: {@code UPSTREAM_SUGGESTION} once told the agent to "write the code from
     * what you already know", while {@code --help} told it no failure is licence to guess. The failure object is
     * what an agent is reading at the moment it is blocked, so when the two disagree the object wins and the
     * tool loses the single behaviour it exists to prevent. Only asserting the content — not merely that a
     * suggestion is present — turns that divergence back into a red build.
     */
    @Test
    public void noFailureOffersARememberedSignatureAsTheWayOut() {
        List<String> whenCentralIsTheProblem = List.of(
                Failure.UPSTREAM_SUGGESTION, Failure.TIMEOUT_SUGGESTION, Failure.SCHEMA_DRIFT_SUGGESTION);
        for (String suggestion : whenCentralIsTheProblem) {
            Assert.assertTrue(suggestion.contains("bala/<org>/<name>/"),
                    "a lookup Central blocked still has an answer on disk, and has to name it: " + suggestion);
            Assert.assertTrue(suggestion.contains("Never fall back to a remembered signature"),
                    "a blocked agent guesses unless something forbids it: " + suggestion);
        }

        // Retryability is the one thing these three do not share, so it is asserted per kind.
        Assert.assertTrue(Failure.UPSTREAM_SUGGESTION.contains("once more"));
        Assert.assertTrue(Failure.TIMEOUT_SUGGESTION.contains("once more"));
        Assert.assertFalse(Failure.SCHEMA_DRIFT_SUGGESTION.contains("once more"),
                "schema drift is not a retry: no change of arguments will help");
    }

    @Test
    public void aNetworkErrorIsRetriedAndThenReportedAsUpstream() {
        FakeTransport transport =
                FakeTransport.always(new HttpTransport.Reply.Failed("network error: connection refused"));
        Result<JsonElement> result =
                CentralClient.fetchJson("https://example.invalid/x", fast(transport).build());
        Assert.assertFalse(result.isOk());
        Failure.Upstream failure = (Failure.Upstream) result.failure();
        Assert.assertEquals(failure.attempts(), 3);
        Assert.assertNull(failure.status(), "a request that never answered has no status line");
    }

    // -----------------------------------------------------------------------
    // Schema drift
    // -----------------------------------------------------------------------

    @Test
    public void anAbsentDeclarationBucketIsAModuleWithNoneOfThemNotDrift() {
        // Central OMITS a bucket when the module has none of that kind. Requiring all 30 cost the tool
        // ~15% of Central — pinecone.vector, weaviate, azure_cosmosdb and sendgrid each failed EVERY verb
        // with a wall of "expected an array, received nothing", and an agent with no readable signatures
        // hand-rolled the connector over http:Client instead.
        JsonObject module = onlyModule("ballerinax__sap");
        int configurablesBefore = module.getAsJsonArray("configurables").size();
        module.remove("records");

        Result<CentralDocs> result = Schema.parse(wrap(module), "ballerinax/sap:1.3.1");
        Assert.assertTrue(result.isOk(), "an omitted bucket must not refuse the whole package");
        CentralDocs.Module parsed = result.value().modules().get(0);
        Assert.assertTrue(parsed.records().isEmpty());
        // The trap in defaulting: "absent means empty" must not quietly become "present means empty".
        // A bucket that IS populated has to survive alongside one that is gone.
        Assert.assertEquals(parsed.configurables().size(), configurablesBefore,
                "defaulting an absent bucket must not blank a populated one");
    }

    @Test
    public void aDeclarationBucketPresentButNotAnArrayIsStillDrift() {
        // The narrowed drift signal: a key Central DOES send, in a shape the reader cannot walk, is a
        // payload that changed rather than a package that is unusual. A rename is caught instead by
        // KeySpaceTest, which snapshots the whole key space across the corpus.
        JsonObject module = onlyModule("ballerinax__sap");
        module.addProperty("records", "no longer an array");

        Result<CentralDocs> result = Schema.parse(wrap(module), "ballerinax/sap:1.3.1");
        Assert.assertFalse(result.isOk());
        Failure.SchemaDrift failure = (Failure.SchemaDrift) result.failure();
        Assert.assertEquals(
                failure.issues().stream().map(Failure.SchemaIssue::path).toList(),
                List.of("docsData.modules.0.records"));
    }

    private static JsonObject onlyModule(String fixture) {
        return FixtureCorpus.loadRawFixture(fixture).getAsJsonObject()
                .getAsJsonObject("docsData").getAsJsonArray("modules").get(0).getAsJsonObject();
    }

    private static JsonObject wrap(JsonObject module) {
        JsonObject wrapper = new JsonObject();
        JsonObject docsData = new JsonObject();
        com.google.gson.JsonArray modules = new com.google.gson.JsonArray();
        modules.add(module);
        docsData.add("modules", modules);
        wrapper.add("docsData", docsData);
        return wrapper;
    }

    @Test
    public void aPayloadWithNoModulesAtAllIsDriftNotAnEmptyLibrary() {
        Result<CentralDocs> result = Schema.parse(
                com.google.gson.JsonParser.parseString("{\"docsData\":{\"modules\":[]}}"), "x/y:1.0.0");
        Assert.assertFalse(result.isOk());
    }

    @Test
    public void theValidatorReportsEveryMismatchAtOnceRatherThanTheFirst() {
        // The person reading a drift failure is about to extend the schema and needs the whole list; a
        // fail-fast validator turns one review into four round trips. Mistyped rather than absent: an
        // absent bucket is a module with none of that kind, so a module carrying only id and orgName now
        // parses as an empty module rather than reporting thirty issues.
        JsonObject module = onlyModule("ballerinax__sap");
        for (String bucket : List.of("records", "clients", "functions", "errors", "constants", "enums")) {
            module.addProperty(bucket, "no longer an array");
        }

        Result<CentralDocs> result = Schema.parse(wrap(module), "ballerinax/sap:1.3.1");
        Assert.assertFalse(result.isOk());
        Failure.SchemaDrift failure = (Failure.SchemaDrift) result.failure();
        Assert.assertEquals(failure.issues().size(), 6,
                "six mistyped buckets should report six paths, got " + failure.issues().size());
        Assert.assertTrue(failure.issues().stream()
                .anyMatch(issue -> issue.path().equals("docsData.modules.0.clients")));
    }

    @Test
    public void aModuleCarryingNoDeclarationBucketsAtAllStillParses() {
        // The shape Central actually serves for a package with nothing in most buckets. Before the fix
        // this reported ~30 issues and refused the package outright.
        String payload = "{\"docsData\":{\"modules\":[{\"id\":\"kafka\",\"orgName\":\"ballerinax\"}]}}";
        Result<CentralDocs> result =
                Schema.parse(com.google.gson.JsonParser.parseString(payload), "ballerinax/kafka:4.6.5");
        Assert.assertTrue(result.isOk(), "a module with no declarations is empty, not drifted");
        Assert.assertTrue(result.value().modules().get(0).clients().isEmpty());
    }

    // -----------------------------------------------------------------------
    // Dependencies.toml
    // -----------------------------------------------------------------------

    @Test
    public void dependenciesTomlYieldsTheLockedVersionOfEachPackage() {
        String toml = """
                [ballerina]
                dependencies-toml-version = "2"

                [[package]]
                org = "ballerina"
                name = "http"
                version = "2.16.6"

                [[package]]
                org = "ballerinax"
                name = "github"
                version = "6.0.0"
                modules = [{org = "ballerinax", packageName = "github", moduleName = "github"}]
                """;
        Map<String, String> versions = DependenciesToml.parse(toml);
        Assert.assertEquals(versions.get("ballerina/http"), "2.16.6");
        Assert.assertEquals(versions.get("ballerinax/github"), "6.0.0");
        // The `[ballerina]` table is metadata, not a package.
        Assert.assertEquals(versions.size(), 2);
    }

    @Test
    public void aTruncatedDependenciesTomlEntryIsSkippedRatherThanHalfRead() {
        Map<String, String> versions =
                DependenciesToml.parse("[[package]]\norg = \"ballerinax\"\nname = \"github\"\n");
        Assert.assertEquals(versions.size(), 0);
    }
}
