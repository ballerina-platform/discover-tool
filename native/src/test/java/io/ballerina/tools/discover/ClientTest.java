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
import io.ballerina.tools.discover.central.ProxySettings;
import io.ballerina.tools.discover.central.schema.CentralDocs;
import io.ballerina.tools.discover.central.schema.Schema;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.ArrayList;
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

    private static final Path LOCK = Path.of("/work/app/Dependencies.toml");

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

    /**
     * A version a PROJECT locked that Central does not publish.
     *
     * <p>The "supplied version" path ({@code --version}, or a {@code Dependencies.toml} lock): "omit the
     * version to take the latest" would name a step the caller did not take. The failure lists the newest
     * published versions instead, which is why no {@code versions} verb is needed — and the list is fetched on a
     * path that has already failed, so it degrades to no list rather than to a different failure.
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
        Assert.assertEquals(failure.qualified(), "ballerinax/github");
        Assert.assertEquals(failure.version(), "9.9.9");
        Assert.assertTrue(failure.suggestion().contains("the version " + LOCK + " locks"), failure.suggestion());
        Assert.assertTrue(failure.suggestion().contains("run `bal build` to regenerate it"), failure.suggestion());
        Assert.assertTrue(failure.suggestion().endsWith("write a published version after the package: "
                + "ballerinax/github:<version>"), failure.suggestion());
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

    @Test
    public void aMissingModulePageNamesTheListingCommandAndRepeatsAPinnedVersionOnly() {
        QualifiedName graphql = QualifiedName.parse("ballerina/graphql").value();
        FakeTransport transport = FakeTransport.always(FakeTransport.status(404));
        CentralClient.ResolvedVersion pinned =
                CentralClient.ResolvedVersion.written(Version.parse("1.17.0").value());
        CentralClient.ResolvedVersion locked = supplied("1.17.0");

        Failure.PackageNotFound withPin = (Failure.PackageNotFound) CentralClient.fetchModuleDocs(
                graphql, "nosuch", pinned, fast(transport).build()).failure();
        Assert.assertEquals(withPin.qualified(), "ballerina/graphql");
        Assert.assertEquals(withPin.version(), "1.17.0");
        Assert.assertEquals(withPin.module(), "nosuch");
        Assert.assertTrue(withPin.suggestion().endsWith("module: `bal discover ballerina/graphql:1.17.0`."),
                withPin.suggestion());

        for (CentralClient.ResolvedVersion unpinned : List.of(locked, resolved("1.17.0"))) {
            Failure.PackageNotFound without = (Failure.PackageNotFound) CentralClient.fetchModuleDocs(
                    graphql, "nosuch", unpinned, fast(transport).build()).failure();
            Assert.assertTrue(without.suggestion().endsWith("module: `bal discover ballerina/graphql`."),
                    without.suggestion());
            Assert.assertFalse(without.suggestion().contains("1.17.0"), without.suggestion());
        }
    }

    @Test
    public void aWrittenVersionCentralDoesNotPublishNamesTheOnesItDoes() {
        FakeTransport transport = FakeTransport.routing(url -> url.contains("/docs/")
                ? FakeTransport.status(404)
                : FakeTransport.ok("[\"6.0.0\", \"5.1.0\"]"));
        CentralClient.ResolvedVersion written =
                CentralClient.ResolvedVersion.written(Version.parse("9.9.9").value());
        Failure.PackageNotFound failure = (Failure.PackageNotFound) CentralClient.fetchDocs(
                GITHUB, written, fast(transport).build()).failure();
        Assert.assertEquals(failure.suggestion(), "Central does not publish 'ballerinax/github' at 9.9.9; published "
                + "versions are 6.0.0, 5.1.0. Write one of them after the package: ballerinax/github:<version>");
    }

    @Test
    public void aWrittenVersionOfANameCentralDoesNotPublishIsReportedAsAModuleOfItsPackage() {
        QualifiedName auth = QualifiedName.parse("ballerinax/aws.auth").value();
        FakeTransport transport = registry(Map.of(
                "ballerinax/aws", "[\"1.0.2\"]",
                "ballerinax/aws/1.0.2", modules("aws", "aws.auth")));
        CentralClient.ResolvedVersion written =
                CentralClient.ResolvedVersion.written(Version.parse("9.9.9").value());
        Failure.PackageNotFound failure = (Failure.PackageNotFound) CentralClient.fetchDocs(
                auth, written, fast(transport).build()).failure();
        Assert.assertEquals(failure.qualified(), "ballerinax/aws.auth");
        Assert.assertEquals(failure.version(), "9.9.9");
        Assert.assertEquals(failure.suggestion(), "'ballerinax/aws.auth' is not a package: it is the 'auth' module "
                + "of the ballerinax/aws package. Read it with `bal discover ballerinax/aws:9.9.9 --module auth`.");
    }

    @Test
    public void aWrittenVersionOfAPackageCentralDoesNotPublishAtAllIsASpellingMiss() {
        for (int registryStatus : new int[] {400, 404}) {
            FakeTransport routed = FakeTransport.routing(url -> url.contains("/docs/")
                    ? FakeTransport.status(404)
                    : FakeTransport.status(registryStatus));
            Failure.PackageNotFound failure = (Failure.PackageNotFound) CentralClient.fetchDocs(
                    QualifiedName.parse("ballerinax/kafak").value(),
                    CentralClient.ResolvedVersion.written(Version.parse("4.6.5").value()),
                    fast(routed).build()).failure();
            Assert.assertEquals(failure.version(), "4.6.5");
            Assert.assertEquals(failure.suggestion(),
                    "Check the org/name spelling; `bal search <keyword>` lists what Central publishes.");
        }
    }

    @Test
    public void aMissingVersionIsAnsweredWithThePublishedVersionsNearestIt() {
        StringBuilder listing = new StringBuilder("[");
        for (int minor = 17; minor >= 8; minor--) {
            for (int patch = 3; patch >= 0; patch--) {
                listing.append(listing.length() > 1 ? "," : "").append("\"2.").append(minor).append('.')
                        .append(patch).append('"');
            }
        }
        FakeTransport transport = FakeTransport.routing(url -> url.contains("/docs/")
                ? FakeTransport.status(404)
                : FakeTransport.ok(listing.append(']').toString()));
        Failure.PackageNotFound failure = (Failure.PackageNotFound) CentralClient.fetchDocs(GITHUB,
                CentralClient.ResolvedVersion.written(Version.parse("2.9.99").value()),
                fast(transport).build()).failure();
        Assert.assertEquals(failure.suggestion(), "Central does not publish 'ballerinax/github' at 2.9.99; the "
                + "published versions nearest it are 2.11.0, 2.10.3, 2.10.2, 2.10.1, 2.10.0, 2.9.3, 2.9.2, 2.9.1, "
                + "2.9.0, 2.8.3 and 30 others. Write one of them after the package: ballerinax/github:<version>");
    }

    @Test
    public void versionsAreOrderedBySemVerPrecedence() {
        List<String> versions = new ArrayList<>(List.of("2.10.0", "2.9.1", "2.10.0-beta.11",
                "2.10.0-beta.2", "2.10.0-alpha", "10.0.0", "not-semver"));
        versions.sort(Version.PRECEDENCE);
        Assert.assertEquals(versions, List.of("2.9.1", "2.10.0-alpha", "2.10.0-beta.2", "2.10.0-beta.11", "2.10.0",
                "10.0.0", "not-semver"));
    }

    private static CentralClient.ResolvedVersion supplied(String version) {
        return CentralClient.ResolvedVersion.locked(Version.parse(version).value(), LOCK);
    }

    private static CentralClient.ResolvedVersion resolved(String version) {
        return CentralClient.ResolvedVersion.latest(Version.parse(version).value(), false);
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

    // The coordinate is always one literal package name. A dotted name the registry has no row for is probed
    // against its prefixes only to say which package it is a module of — never to read the module in the
    // package's place.

    private static final QualifiedName AWS_AUTH = QualifiedName.parse("ballerinax/aws.auth").value();

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
    public void anEmptyModuleListIsUnknownSinceARealRowListsAtLeastTheDefaultModule() {
        FakeTransport transport = registry(Map.of(
                "ballerinax/aws", "[\"1.0.1\"]",
                "ballerinax/aws/1.0.1", modules()));
        String suggestion = suggestion(CentralClient.resolveLatestVersion(AWS_AUTH, fast(transport).build()));
        Assert.assertTrue(suggestion.contains("Central could not say which modules it publishes"), suggestion);
    }

    @Test
    public void aModuleListWithAnUnreadableEntryIsUnknownRatherThanShort() {
        // The entry that could not be read may be the very module asked about.
        FakeTransport transport = registry(Map.of(
                "ballerinax/aws", "[\"1.0.1\"]",
                "ballerinax/aws/1.0.1", "{\"modules\":[{\"name\":\"aws\"},{\"name\":42}]}"));
        String suggestion = suggestion(CentralClient.resolveLatestVersion(AWS_AUTH, fast(transport).build()));
        Assert.assertTrue(suggestion.contains("Central could not say which modules it publishes"), suggestion);
        Assert.assertFalse(suggestion.contains("publishes no 'auth' module"), suggestion);
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

    @Test
    public void everyFailureTheOutsideWorldCanCauseCarriesASuggestion() {
        // These three fire during a Central outage, when the reader has nothing else to offer.
        Result<JsonElement> timeout = CentralClient.fetchJson("https://example.invalid/x",
                fast(FakeTransport.always(new HttpTransport.Reply.TimedOut())).maxAttempts(1).build());
        Assert.assertTrue(timeout.failure() instanceof Failure.Timeout);
        Assert.assertFalse(((Failure.Timeout) timeout.failure()).suggestion().isEmpty());

        Result<JsonElement> upstream = CentralClient.fetchJson("https://example.invalid/x",
                fast(FakeTransport.always(FakeTransport.status(500))).maxAttempts(1).build());
        Assert.assertTrue(upstream.failure() instanceof Failure.Upstream);
        Assert.assertFalse(((Failure.Upstream) upstream.failure()).suggestion().isEmpty());

        Result<CentralDocs> drift = Schema.parse(
                com.google.gson.JsonParser.parseString("{\"docsData\":{\"modules\":[]}}"), "x/y", "1.0.0");
        Assert.assertFalse(drift.isOk());
        Failure.SchemaDrift failure = (Failure.SchemaDrift) drift.failure();
        // Addressed to a human on purpose: no argument the agent can change will make a payload this reader
        // cannot parse.
        Assert.assertTrue(failure.suggestion().contains("Report the"));
    }

    @Test
    public void schemaDriftIsAnsweredFromDiskNeverFromMemoryAndIsNotARetry() {
        Assert.assertTrue(Failure.SCHEMA_DRIFT_SUGGESTION.contains("bala/<org>/<name>/"),
                Failure.SCHEMA_DRIFT_SUGGESTION);
        Assert.assertTrue(Failure.SCHEMA_DRIFT_SUGGESTION.contains("Never fall back to a remembered signature"),
                Failure.SCHEMA_DRIFT_SUGGESTION);
        Assert.assertFalse(Failure.SCHEMA_DRIFT_SUGGESTION.contains("run the same command"),
                "schema drift is not a retry: no change of arguments will help");
    }

    private static final String URL = "https://api.central.ballerina.io/2.0/docs/ballerina/http";
    private static final String SETTINGS = "~/home/Settings.toml";
    private static final ProxySettings OPEN_PROXY = new ProxySettings("127.0.0.1", 3128, "", "");
    private static final ProxySettings AUTHENTICATING_PROXY = new ProxySettings("127.0.0.1", 3128, "alice", "pw");

    private static Failure failing(HttpTransport.Reply reply, ProxySettings proxy) {
        return CentralClient.fetchJson(URL,
                fast(FakeTransport.always(reply)).proxy(proxy).settingsFile(SETTINGS).build()).failure();
    }

    private static HttpTransport.Reply failed(HttpTransport.Reply.Problem problem, String message) {
        return new HttpTransport.Reply.Failed(problem, message);
    }

    private static HttpTransport.Reply tunnel(int status) {
        return new HttpTransport.Reply.Failed(HttpTransport.Reply.Problem.TUNNEL, "Tunnel failed, got: " + status,
                status);
    }

    @Test
    public void aTunnelTheProxyRefusesIsNotRetriedButOneItCouldNotOpenIs() {
        FakeTransport refused = FakeTransport.always(tunnel(403));
        Failure.Upstream policy = (Failure.Upstream) CentralClient.fetchJson(URL,
                fast(refused).proxy(OPEN_PROXY).build()).failure();
        Assert.assertEquals(refused.calls(), 1);
        Assert.assertEquals(policy.status(), Integer.valueOf(403));

        FakeTransport gateway = FakeTransport.always(tunnel(502));
        CentralClient.fetchJson(URL, fast(gateway).proxy(OPEN_PROXY).build());
        Assert.assertEquals(gateway.calls(), 3);
    }

    @Test
    public void noAttemptAtAllIsADefect() {
        Failure failure = CentralClient.fetchJson(URL,
                fast(FakeTransport.always(FakeTransport.status(200))).maxAttempts(0).build()).failure();
        Assert.assertEquals(failure.kind(), "internal");
    }

    @Test
    public void aRequestWithNoAnswerNamesTheHostOrTheProxyAndWhatToCheck() {
        Assert.assertEquals(failing(failed(HttpTransport.Reply.Problem.UNCONNECTED, "connection refused"), null)
                .describeText(), "error: Could not reach api.central.ballerina.io: connection refused (3 attempts).\n"
                + "  Check your network connection, and the [proxy] table in ~/home/Settings.toml if your network "
                + "needs a proxy, then run the same command again.");
        Assert.assertEquals(failing(failed(HttpTransport.Reply.Problem.UNCONNECTED, "connection refused"), OPEN_PROXY)
                .describeText(), "error: Could not connect to the proxy 127.0.0.1:3128 set in ~/home/Settings.toml: "
                + "connection refused (3 attempts).\n  Check that the proxy is running and that host and port under "
                + "[proxy] in ~/home/Settings.toml are right, then run the same command again.");
        Assert.assertEquals(failing(failed(HttpTransport.Reply.Problem.UNRESOLVED, "x"), null).describeText(),
                "error: Could not resolve the host api.central.ballerina.io.\n  Check your network connection, and "
                        + "the [proxy] table in ~/home/Settings.toml if your network needs a proxy, then run the same "
                        + "command again.");
        Assert.assertEquals(failing(failed(HttpTransport.Reply.Problem.UNRESOLVED, "x"),
                        new ProxySettings("no-such-proxy.invalid", 3128, "", "")).describeText(),
                "error: Could not resolve the proxy host no-such-proxy.invalid.\n  Check host under [proxy] in "
                        + "~/home/Settings.toml, then run the same command again.");
        Assert.assertEquals(failing(failed(HttpTransport.Reply.Problem.TLS, "its certificate has expired"), null)
                .describeText(), "error: Could not open a secure connection to api.central.ballerina.io: its "
                + "certificate has expired.\n  Check the system clock, and any proxy or firewall that intercepts "
                + "HTTPS, then run the same command again.");
        Assert.assertEquals(failing(tunnel(502), OPEN_PROXY).describeText(),
                "error: The proxy 127.0.0.1:3128 set in ~/home/Settings.toml could not connect to "
                        + "api.central.ballerina.io: HTTP 502 (3 attempts).\n  The proxy answered but could not "
                        + "reach api.central.ballerina.io; run the same command again later, or check that the proxy "
                        + "allows it.");
    }

    @Test
    public void aProxyAskingForCredentialsSaysWhetherTheyWereMissingOrWrongAndIsNotRetried() {
        Failure.Upstream missing = (Failure.Upstream) failing(FakeTransport.status(407), OPEN_PROXY);
        Assert.assertEquals(missing.describeText(), "error: The proxy 127.0.0.1:3128 requires authentication.\n"
                + "  Set both username and password under [proxy] in ~/home/Settings.toml, then run the same command "
                + "again.");
        Assert.assertEquals(missing.attempts(), 1);
        Assert.assertFalse(missing.reached());

        Failure.Upstream wrong = (Failure.Upstream) failing(
                failed(HttpTransport.Reply.Problem.PROXY_REJECTED, "No credentials provided"), AUTHENTICATING_PROXY);
        Assert.assertEquals(wrong.describeText(), "error: The proxy 127.0.0.1:3128 rejected the username and "
                + "password in the [proxy] table of ~/home/Settings.toml.\n  Correct username and password under "
                + "[proxy] in ~/home/Settings.toml, then run the same command again.");
        Assert.assertEquals(wrong.attempts(), 1);
        Assert.assertEquals(failing(FakeTransport.status(407), AUTHENTICATING_PROXY).describeText(),
                wrong.describeText());
    }

    @Test
    public void anAnswerFromCentralIsBlamedOnCentralAndATimeoutNamesTheSettingsFile() {
        Failure.Upstream status = (Failure.Upstream) failing(FakeTransport.status(503), OPEN_PROXY);
        Assert.assertEquals(status.describeText(), "error: Central answered " + URL + " with HTTP 503 (3 attempts).\n"
                + "  Central returned an error; run the same command again later.");
        Assert.assertTrue(status.reached());
        Assert.assertEquals(status.status(), Integer.valueOf(503));

        Failure.Upstream malformed = (Failure.Upstream) failing(FakeTransport.ok("{"), null);
        Assert.assertTrue(malformed.describeText().startsWith("error: Central answered " + URL
                + " with a body that is not JSON: "), malformed.describeText());
        Assert.assertTrue(malformed.reached());
        Assert.assertNull(malformed.status());

        Assert.assertEquals(failing(new HttpTransport.Reply.TimedOut(), null).suggestion(), "A large package is slow "
                + "on a cold fetch, so run the same command again; if it keeps timing out, check your network "
                + "connection and the [proxy] table in ~/home/Settings.toml.");
    }

    @Test
    public void aRequestThatCannotBeMadeIsADefectNotANetworkProblem() {
        Failure failure = failing(failed(HttpTransport.Reply.Problem.BAD_URL, "Illegal character in path"), null);
        Assert.assertEquals(failure.kind(), "internal");
        Assert.assertEquals(failure.suggestion(), Failure.INTERNAL_SUGGESTION);
    }

    @Test
    public void aNetworkErrorIsRetriedAndThenReportedAsUpstream() {
        FakeTransport transport = FakeTransport.always(failed(HttpTransport.Reply.Problem.UNCONNECTED,
                "connection refused"));
        Result<JsonElement> result =
                CentralClient.fetchJson("https://example.invalid/x", fast(transport).build());
        Assert.assertFalse(result.isOk());
        Failure.Upstream failure = (Failure.Upstream) result.failure();
        Assert.assertEquals(failure.attempts(), 3);
        Assert.assertNull(failure.status(), "a request that never answered has no status line");
        Assert.assertFalse(failure.reached());
        Assert.assertTrue(failure.describe().contains("\"reached\":false"), failure.describe());
    }

    @Test
    public void anAbsentDeclarationBucketIsAModuleWithNoneOfThemNotDrift() {
        // Central OMITS a bucket when the module has none of that kind (pinecone.vector, weaviate,
        // azure_cosmosdb, sendgrid), so requiring every bucket would refuse those packages outright.
        JsonObject module = onlyModule("ballerinax__sap");
        int configurablesBefore = module.getAsJsonArray("configurables").size();
        module.remove("records");

        Result<CentralDocs> result = Schema.parse(wrap(module), "ballerinax/sap", "1.3.1");
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

        Result<CentralDocs> result = Schema.parse(wrap(module), "ballerinax/sap", "1.3.1");
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
                com.google.gson.JsonParser.parseString("{\"docsData\":{\"modules\":[]}}"), "x/y", "1.0.0");
        Assert.assertFalse(result.isOk());
    }

    @Test
    public void theValidatorReportsEveryMismatchAtOnceRatherThanTheFirst() {
        // The person reading a drift failure is about to extend the schema and needs the whole list; a
        // fail-fast validator turns one review into four round trips. Mistyped rather than absent: an
        // absent bucket is a module with none of that kind, so a module carrying only id and orgName
        // parses as an empty module rather than reporting thirty issues.
        JsonObject module = onlyModule("ballerinax__sap");
        for (String bucket : List.of("records", "clients", "functions", "errors", "constants", "enums")) {
            module.addProperty(bucket, "no longer an array");
        }

        Result<CentralDocs> result = Schema.parse(wrap(module), "ballerinax/sap", "1.3.1");
        Assert.assertFalse(result.isOk());
        Failure.SchemaDrift failure = (Failure.SchemaDrift) result.failure();
        Assert.assertEquals(failure.issues().size(), 6,
                "six mistyped buckets should report six paths, got " + failure.issues().size());
        Assert.assertTrue(failure.issues().stream()
                .anyMatch(issue -> issue.path().equals("docsData.modules.0.clients")));
    }

    @Test
    public void aModuleCarryingNoDeclarationBucketsAtAllStillParses() {
        // The shape Central actually serves for a package with nothing in most buckets.
        String payload = "{\"docsData\":{\"modules\":[{\"id\":\"kafka\",\"orgName\":\"ballerinax\"}]}}";
        Result<CentralDocs> result =
                Schema.parse(com.google.gson.JsonParser.parseString(payload), "ballerinax/kafka", "4.6.5");
        Assert.assertTrue(result.isOk(), "a module with no declarations is empty, not drifted");
        Assert.assertTrue(result.value().modules().get(0).clients().isEmpty());
    }

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
