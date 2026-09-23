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
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

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
        private int resolveCalls;
        private int fetchCalls;

        private FakeRepository(Result<CentralClient.ResolvedVersion> version, Result<CentralDocs> docs) {
            this("fake", version, docs);
        }

        private FakeRepository(String id, Result<CentralClient.ResolvedVersion> version, Result<CentralDocs> docs) {
            this.id = id;
            this.version = version;
            this.docs = docs;
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
                new Loader.LoadOptions(httpThatMustNotReachTheNetwork(), null, List.of(repository));

        Result<LoadedPackage> loaded = Loader.loadPackage(PKG, options);

        Assert.assertTrue(loaded.isOk(), loaded.isOk() ? "" : loaded.failure().describe());
        Assert.assertEquals(loaded.value().version(), version);
        Assert.assertEquals(repository.resolveCalls, 1);
        Assert.assertEquals(repository.fetchCalls, 1);
    }

    @Test
    public void aVersionFailureFromTheRepositoryPropagatesWithoutEverFetchingDocs() {
        FakeRepository repository = new FakeRepository(
                Result.err(new Failure.PackageNotFound("ballerinax/kafka", "not on this repository")),
                Result.err(new Failure.PackageNotFound("ballerinax/kafka", "must not be reached")));
        Loader.LoadOptions options =
                new Loader.LoadOptions(httpThatMustNotReachTheNetwork(), null, List.of(repository));

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
                Result.err(new Failure.PackageNotFound("ballerinax/kafka:4.6.5", "gone from this repository")));
        Loader.LoadOptions options =
                new Loader.LoadOptions(httpThatMustNotReachTheNetwork(), null, List.of(repository));

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
                Result.err(new Failure.PackageNotFound("ballerinax/kafka", "not on this repository")),
                Result.err(new Failure.PackageNotFound("ballerinax/kafka", "must not be reached")));
        FakeRepository second = new FakeRepository(
                Result.ok(new CentralClient.ResolvedVersion(version, false)),
                Result.ok(FixtureCorpus.loadFixture(SLUG)));
        Loader.LoadOptions options = new Loader.LoadOptions(
                httpThatMustNotReachTheNetwork(), null, List.of(first, second));

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
        // "Local Central" cache next to real Central would not necessarily agree with.
        FakeRepository resolvesButFailsToFetch = new FakeRepository(
                Result.ok(new CentralClient.ResolvedVersion(Version.parse("4.6.5").value(), false)),
                Result.err(new Failure.PackageNotFound("ballerinax/kafka:4.6.5", "gone from this repository")));
        FakeRepository second = new FakeRepository(
                Result.err(new Failure.PackageNotFound("ballerinax/kafka", "must not be reached")),
                Result.err(new Failure.PackageNotFound("ballerinax/kafka", "must not be reached")));
        Loader.LoadOptions options = new Loader.LoadOptions(
                httpThatMustNotReachTheNetwork(), null, List.of(resolvesButFailsToFetch, second));

        Result<LoadedPackage> loaded = Loader.loadPackage(PKG, options);

        // The first repository's own docs failure is the one that surfaces, not a resolve retried on the second.
        Assert.assertFalse(loaded.isOk());
        Assert.assertTrue(loaded.failure() instanceof Failure.PackageNotFound);
        Assert.assertEquals(second.resolveCalls, 1, "the second repository still gets its own attempt");
        Assert.assertEquals(second.fetchCalls, 0, "the second repository never resolved, so it is never fetched");
    }
}
