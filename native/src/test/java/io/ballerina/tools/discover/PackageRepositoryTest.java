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

import io.ballerina.tools.discover.central.CentralClient;
import io.ballerina.tools.discover.central.HttpOptions;
import io.ballerina.tools.discover.central.PackageRepository;
import io.ballerina.tools.discover.central.schema.CentralDocs;
import io.ballerina.tools.discover.central.schema.Schema;
import io.ballerina.tools.discover.model.Service;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Proof that {@link Loader} reaches a package only through the {@link PackageRepository} list it is handed, never
 * through Ballerina Central by name — the cheapest possible check that the seam introduced ahead of the
 * multi-source repository work is real, not just a type that happens to have one implementer.
 *
 * @since 0.1.0
 */
public class PackageRepositoryTest {

    private static final String SLUG = "ballerinax__kafka";
    private static final QualifiedName PKG = QualifiedName.parse("ballerinax/kafka").value();

    /** A repository that never touches HTTP at all — every answer is canned. */
    private static final class FakeRepository implements PackageRepository {

        private final String id;
        private final Result<CentralClient.ResolvedVersion> version;
        private final Result<CentralDocs> docs;
        private final Optional<Map<String, String>> sources;
        private Result<CentralDocs> modulePage;
        private int resolveCalls;
        private int fetchCalls;
        private int moduleFetchCalls;
        private int sourceCalls;

        private FakeRepository(Result<CentralClient.ResolvedVersion> version, Result<CentralDocs> docs) {
            this("fake", version, docs);
        }

        private FakeRepository(String id, Result<CentralClient.ResolvedVersion> version, Result<CentralDocs> docs) {
            this(id, version, docs, Optional.empty());
        }

        private FakeRepository(String id, Result<CentralClient.ResolvedVersion> version, Result<CentralDocs> docs,
                Optional<Map<String, String>> sources) {
            this.id = id;
            this.version = version;
            this.docs = docs;
            this.sources = sources;
        }

        @Override
        public Optional<Map<String, String>> fetchModuleSources(QualifiedName qualified,
                CentralClient.ResolvedVersion resolved, String moduleId, HttpOptions options) {
            sourceCalls++;
            return sources;
        }

        @Override
        public Result<CentralClient.ResolvedVersion> resolveVersion(QualifiedName qualified, HttpOptions options) {
            resolveCalls++;
            return version;
        }

        @Override
        public Result<CentralDocs> fetchDocs(
                QualifiedName qualified, CentralClient.ResolvedVersion resolved, HttpOptions options) {
            fetchCalls++;
            return docs;
        }

        @Override
        public Result<CentralDocs> fetchModuleDocs(QualifiedName qualified, String submodule,
                CentralClient.ResolvedVersion resolved, HttpOptions options) {
            moduleFetchCalls++;
            if (modulePage == null) {
                throw new AssertionError("this repository was not set up to serve a submodule");
            }
            return modulePage;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String describe() {
            return "fake, in-memory";
        }
    }

    private static HttpOptions httpThatMustNotReachTheNetwork() {
        return HttpOptions.builder().transport(FakeTransport.never()).build();
    }

    @Test
    public void loaderReadsExclusivelyThroughTheInjectedRepository() {
        Version version = Version.parse("4.6.5").value();
        FakeRepository repository = new FakeRepository(
                Result.ok(new CentralClient.ResolvedVersion(version, false)),
                Result.ok(FixtureCorpus.loadFixture(SLUG)));
        Loader.LoadOptions options =
                new Loader.LoadOptions(httpThatMustNotReachTheNetwork(), null, List.of(repository), null, null);

        Result<LoadedPackage> loaded = Loader.loadPackage(PKG, options);

        Assert.assertTrue(loaded.isOk(), loaded.isOk() ? "" : loaded.failure().describe());
        Assert.assertEquals(loaded.value().version(), version);
        Assert.assertEquals(repository.resolveCalls, 1);
        Assert.assertEquals(repository.fetchCalls, 1);
        Assert.assertEquals(repository.sourceCalls, 0, "kafka's one service type is its listener's target");
    }

    private static Result<LoadedPackage> loadHttp(FakeRepository repository) {
        Result<LoadedPackage> loaded = Loader.loadPackage(QualifiedName.parse("ballerina/http").value(),
                new Loader.LoadOptions(httpThatMustNotReachTheNetwork(), null, List.of(repository), null, null));
        return loaded.isOk() ? Result.ok(loaded.value().withBindings()) : loaded;
    }

    private static FakeRepository http(Optional<Map<String, String>> sources) {
        return new FakeRepository("fake",
                Result.ok(new CentralClient.ResolvedVersion(Version.parse("2.16.6").value(), false)),
                Result.ok(FixtureCorpus.loadFixture("ballerina__http")), sources);
    }

    /** Which service types bind is read from the source the SAME repository serves, when the docs cannot say. */
    @Test
    public void theSourceSettlesWhichServiceTypesBind() {
        FakeRepository repository = http(FixtureCorpus.recordedSources("ballerina__http"));

        Result<LoadedPackage> loaded = loadHttp(repository);

        Assert.assertTrue(loaded.isOk(), loaded.isOk() ? "" : loaded.failure().describe());
        Assert.assertEquals(repository.sourceCalls, 1);
        List<Service> services = loaded.value().library().services();
        Assert.assertEquals(services.stream().map(Service::name).toList(),
                List.of("Service", "ServiceContract", "InterceptableService"));
        Assert.assertTrue(services.stream().allMatch(Service::isConfirmed));
    }

    /** Loading reads no source; settling the bindings does, once, from the repository that served the docs. */
    @Test
    public void theSourceIsReadOnlyWhenTheBindingsAreSettled() {
        FakeRepository repository = http(FixtureCorpus.recordedSources("ballerina__http"));

        Result<LoadedPackage> loaded = Loader.loadPackage(QualifiedName.parse("ballerina/http").value(),
                new Loader.LoadOptions(httpThatMustNotReachTheNetwork(), null, List.of(repository), null, null));

        Assert.assertTrue(loaded.isOk(), loaded.isOk() ? "" : loaded.failure().describe());
        Assert.assertEquals(repository.sourceCalls, 0);
        loaded.value().withBindings();
        loaded.value().withBindings();
        Assert.assertEquals(repository.sourceCalls, 1);
    }

    /**
     * No source is never a failure: the attach target is confirmed and every other
     * service type is paired with the "not confirmed" hedge, rather than dropped.
     */
    @Test
    public void withoutTheSourceTheOtherServiceTypesAreHedgedNotDropped() {
        FakeRepository repository = http(Optional.empty());

        Result<LoadedPackage> loaded = loadHttp(repository);

        Assert.assertTrue(loaded.isOk(), loaded.isOk() ? "" : loaded.failure().describe());
        List<Service> services = loaded.value().library().services();
        Assert.assertEquals(services.size(), 7);
        Assert.assertEquals(services.stream().filter(Service::isConfirmed).map(Service::name).toList(),
                List.of("Service"));
    }

    @Test
    public void aVersionFailureFromTheRepositoryPropagatesWithoutEverFetchingDocs() {
        FakeRepository repository = new FakeRepository(
                Result.err(new Failure.PackageNotFound("ballerinax/kafka", null, null, "not on this repository")),
                Result.err(new Failure.PackageNotFound("ballerinax/kafka", null, null, "must not be reached")));
        Loader.LoadOptions options =
                new Loader.LoadOptions(httpThatMustNotReachTheNetwork(), null, List.of(repository), null, null);

        Result<LoadedPackage> loaded = Loader.loadPackage(PKG, options);

        Assert.assertFalse(loaded.isOk());
        Assert.assertTrue(loaded.failure() instanceof Failure.PackageNotFound);
        Assert.assertEquals(repository.resolveCalls, 1);
        Assert.assertEquals(repository.fetchCalls, 0, "a version that never resolved must not be fetched");
    }

    @Test
    public void aDocsFailureFromTheRepositoryPropagatesUnchanged() {
        FakeRepository repository = new FakeRepository(
                Result.ok(new CentralClient.ResolvedVersion(Version.parse("4.6.5").value(), false)),
                Result.err(new Failure.PackageNotFound("ballerinax/kafka", "4.6.5", null,
                        "gone from this repository")));
        Loader.LoadOptions options =
                new Loader.LoadOptions(httpThatMustNotReachTheNetwork(), null, List.of(repository), null, null);

        Result<LoadedPackage> loaded = Loader.loadPackage(PKG, options);

        Assert.assertFalse(loaded.isOk());
        Assert.assertTrue(loaded.failure() instanceof Failure.PackageNotFound);
    }

    @Test
    public void severalRepositoriesAreTriedInOrderUntilOneAnswers() {
        // The shape the RFC's multi-source interface needs: Central, a "Local Central" cache and Artifactory are
        // all candidates for the SAME lookup, not one source picked ahead of time. A repository that has nothing
        // for this package is a routine outcome, not a failure worth stopping on.
        Version version = Version.parse("4.6.5").value();
        FakeRepository first = new FakeRepository(
                Result.err(new Failure.PackageNotFound("ballerinax/kafka", null, null, "not on this repository")),
                Result.err(new Failure.PackageNotFound("ballerinax/kafka", null, null, "must not be reached")));
        FakeRepository second = new FakeRepository(
                Result.ok(new CentralClient.ResolvedVersion(version, false)),
                Result.ok(FixtureCorpus.loadFixture(SLUG)));
        Loader.LoadOptions options = new Loader.LoadOptions(
                httpThatMustNotReachTheNetwork(), null, List.of(first, second), null, null);

        Result<LoadedPackage> loaded = Loader.loadPackage(PKG, options);

        Assert.assertTrue(loaded.isOk(), loaded.isOk() ? "" : loaded.failure().describe());
        Assert.assertEquals(loaded.value().version(), version);
        Assert.assertEquals(first.resolveCalls, 1, "the first repository has to be asked before it is skipped");
        Assert.assertEquals(first.fetchCalls, 0, "a repository that never resolved must not be fetched from");
        Assert.assertEquals(second.resolveCalls, 1);
        Assert.assertEquals(second.fetchCalls, 1);
    }

    @Test
    public void aVersionResolvedOnOneRepositoryIsFetchedFromThatSameRepositoryNotTheNext() {
        // The bug this pairing exists to prevent: a version resolved on repository A but fetched from B, which a
        // "Local Central" cache next to real Central would not necessarily agree with. `second` fails too, so
        // `tryEachRepository`'s documented last-wins policy makes ITS failure the one that surfaces — the pairing
        // itself is what `resolvesButFailsToFetch.fetchCalls` below actually pins down.
        FakeRepository resolvesButFailsToFetch = new FakeRepository(
                Result.ok(new CentralClient.ResolvedVersion(Version.parse("4.6.5").value(), false)),
                Result.err(new Failure.PackageNotFound("ballerinax/kafka", "4.6.5", null,
                        "gone from this repository")));
        FakeRepository second = new FakeRepository(
                Result.err(new Failure.PackageNotFound("ballerinax/kafka", null, null,
                        "second repository has no answer")),
                Result.err(new Failure.PackageNotFound("ballerinax/kafka", null, null, "must not be reached")));
        Loader.LoadOptions options = new Loader.LoadOptions(
                httpThatMustNotReachTheNetwork(), null, List.of(resolvesButFailsToFetch, second), null, null);

        Result<LoadedPackage> loaded = Loader.loadPackage(PKG, options);

        Assert.assertFalse(loaded.isOk());
        Assert.assertTrue(loaded.failure() instanceof Failure.PackageNotFound);
        Assert.assertEquals(((Failure.PackageNotFound) loaded.failure()).suggestion(),
                "second repository has no answer", "last-wins means the second repository's own failure surfaces");
        Assert.assertEquals(resolvesButFailsToFetch.fetchCalls, 1,
                "the version it resolved must be fetched from itself, not left unfetched");
        Assert.assertEquals(second.resolveCalls, 1, "the second repository still gets its own attempt");
        Assert.assertEquals(second.fetchCalls, 0, "the second repository never resolved, so it is never fetched");
    }

    @Test
    public void aModuleMismatchOnAnAnsweringRepositoryIsNotRetriedAgainstTheNext() {
        // The other half of the seam: a repository that resolves and fetches successfully but does not contain
        // the requested module has ANSWERED, not failed to find the package — every repository serving the same
        // immutable version would disagree with the caller the same way, so trying `second` at all would only
        // risk `tryEachRepository`'s last-wins policy burying this failure under an unrelated one.
        FakeRepository answersButWrongModule = new FakeRepository(
                Result.ok(new CentralClient.ResolvedVersion(Version.parse("4.6.5").value(), false)),
                Result.ok(FixtureCorpus.loadFixture(SLUG)));
        FakeRepository second = new FakeRepository(
                Result.err(new Failure.PackageNotFound("ballerinax/kafka", null, null, "must not be reached")),
                Result.err(new Failure.PackageNotFound("ballerinax/kafka", null, null, "must not be reached")));
        Loader.LoadOptions options = new Loader.LoadOptions(
                httpThatMustNotReachTheNetwork(), null, List.of(answersButWrongModule, second), null, null);

        Result<LoadedPackage> loaded = Loader.loadPackage(QualifiedName.parse("ballerinax/nosuchmodule").value(),
                options);

        Assert.assertFalse(loaded.isOk());
        Assert.assertTrue(loaded.failure() instanceof Failure.SchemaDrift);
        Assert.assertEquals(second.resolveCalls, 0, "a module mismatch must not trigger fallback to a repository");
        Assert.assertEquals(second.fetchCalls, 0);
    }

    private static final QualifiedName GRAPHQL = QualifiedName.parse("ballerina/graphql").value();

    private static FakeRepository graphqlRepository(String id, Result<CentralDocs> modulePage) {
        FakeRepository repository = new FakeRepository(id,
                Result.ok(new CentralClient.ResolvedVersion(Version.parse("1.17.0").value(), false)),
                Result.ok(FixtureCorpus.loadFixture("ballerina__graphql")));
        repository.modulePage = modulePage;
        return repository;
    }

    private static Result<CentralDocs> subgraphPage() {
        return Schema.parse(FixtureCorpus.loadRawModulePage("ballerina__graphql.subgraph"), "subgraph", null);
    }

    private static Result<CentralDocs> noModulePage() {
        return Result.err(new Failure.PackageNotFound("ballerina/graphql", "1.17.0", "subgraph",
                "no such page here"));
    }

    private static Result<LoadedPackage> loadSubgraph(FakeRepository... repositories) {
        return Loader.loadPackage(GRAPHQL, new Loader.LoadOptions(
                httpThatMustNotReachTheNetwork(), null, List.of(repositories), "subgraph", null));
    }

    @Test
    public void aSubmodulePageReadOnTheFirstTryCostsNoPackagePage() {
        FakeRepository only = graphqlRepository("only", subgraphPage());
        Result<LoadedPackage> loaded = loadSubgraph(only);
        Assert.assertTrue(loaded.isOk(), loaded.isOk() ? "" : loaded.failure().describe());
        Assert.assertEquals(only.moduleFetchCalls, 1);
        Assert.assertEquals(only.fetchCalls, 0);
    }

    @Test
    public void aLaterRepositoryPublishingTheSubmoduleAnswersBeforeAnyPackagePageIsRead() {
        FakeRepository first = graphqlRepository("first", noModulePage());
        FakeRepository second = graphqlRepository("second", subgraphPage());
        Result<LoadedPackage> loaded = loadSubgraph(first, second);
        Assert.assertTrue(loaded.isOk(), loaded.isOk() ? "" : loaded.failure().describe());
        Assert.assertEquals(loaded.value().module(), "subgraph");
        Assert.assertEquals(second.moduleFetchCalls, 1);
        Assert.assertEquals(first.fetchCalls + second.fetchCalls, 0, "no package page on the success path");
    }

    @Test
    public void onlyWhenEveryRepositoryLacksTheSubmoduleIsAPackagePageReadToNameTheCandidates() {
        FakeRepository first = graphqlRepository("first", noModulePage());
        FakeRepository second = graphqlRepository("second", noModulePage());
        Result<LoadedPackage> loaded = loadSubgraph(first, second);
        Assert.assertFalse(loaded.isOk());
        Failure.SymbolNotFound failure = (Failure.SymbolNotFound) loaded.failure();
        Assert.assertEquals(failure.candidates(), List.of("dataloader", "subgraph"));
        Assert.assertEquals(first.fetchCalls, 1);
        Assert.assertEquals(second.fetchCalls, 0);
    }

    @Test
    public void aTransportFailureOnASubmodulePageIsReportedRatherThanAMissingModule() {
        FakeRepository first = graphqlRepository("first", Result.err(new Failure.Upstream(
                "https://example.test/docs", 3, "HTTP 503", Failure.UPSTREAM_SUGGESTION, 503, true)));
        FakeRepository second = graphqlRepository("second", noModulePage());
        Result<LoadedPackage> loaded = loadSubgraph(first, second);
        Assert.assertFalse(loaded.isOk());
        Assert.assertTrue(loaded.failure() instanceof Failure.Upstream, loaded.failure().describe());
        Assert.assertEquals(first.fetchCalls + second.fetchCalls, 0);
    }

    private static Failure.Upstream unreachable() {
        return new Failure.Upstream("https://example.test/x", 3, "HTTP 503", Failure.UPSTREAM_SUGGESTION, 503, true);
    }

    @Test
    public void aRepositoryThatCouldNotResolveThePackageIsNotTakenForOneWithoutTheSubmodule() {
        FakeRepository unreachable = new FakeRepository("unreachable", Result.err(unreachable()),
                Result.err(new Failure.PackageNotFound("ballerina/graphql", null, null, "must not be reached")));
        FakeRepository second = graphqlRepository("second", noModulePage());
        Result<LoadedPackage> loaded = loadSubgraph(unreachable, second);
        Assert.assertFalse(loaded.isOk());
        Assert.assertTrue(loaded.failure() instanceof Failure.Upstream, loaded.failure().describe());
        Assert.assertEquals(second.fetchCalls, 0);
    }

    @Test
    public void aPackagePageThatCouldNotBeReadIsReportedOverOneThatIsMissing() {
        CentralClient.ResolvedVersion version =
                new CentralClient.ResolvedVersion(Version.parse("1.17.0").value(), false);
        FakeRepository first = new FakeRepository("first", Result.ok(version), Result.err(unreachable()));
        first.modulePage = noModulePage();
        FakeRepository second = new FakeRepository("second", Result.ok(version),
                Result.err(new Failure.PackageNotFound("ballerina/graphql", "1.17.0", null, "not here")));
        second.modulePage = noModulePage();
        Result<LoadedPackage> loaded = loadSubgraph(first, second);
        Assert.assertFalse(loaded.isOk());
        Assert.assertTrue(loaded.failure() instanceof Failure.Upstream, loaded.failure().describe());
        Assert.assertEquals(first.fetchCalls + second.fetchCalls, 2);
    }

    @Test
    public void aLockedVersionAsksEveryRepositoryForTheSubmoduleAtThatVersion() throws IOException {
        Path project = Files.createTempDirectory("bal-discover-lock-");
        Files.writeString(project.resolve("Dependencies.toml"),
                "[[package]]\norg = \"ballerina\"\nname = \"graphql\"\nversion = \"1.17.0\"\n");
        FakeRepository first = graphqlRepository("first", noModulePage());
        FakeRepository second = graphqlRepository("second", subgraphPage());
        Result<LoadedPackage> loaded = Loader.loadPackage(GRAPHQL, new Loader.LoadOptions(
                httpThatMustNotReachTheNetwork(), project.toString(), List.of(first, second), "subgraph", null));
        Assert.assertTrue(loaded.isOk(), loaded.isOk() ? "" : loaded.failure().describe());
        Assert.assertEquals(loaded.value().version().text(), "1.17.0");
        Assert.assertEquals(first.resolveCalls + second.resolveCalls, 0, "the lock, not the repositories, names it");
        Assert.assertEquals(first.moduleFetchCalls + second.moduleFetchCalls, 2);
        Assert.assertEquals(first.fetchCalls + second.fetchCalls, 0);
    }
}
