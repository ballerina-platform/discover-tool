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

/**
 * Proof that {@link Loader} reaches a package only through the {@link PackageRepository} it is handed, never
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

        private final Result<CentralClient.ResolvedVersion> version;
        private final Result<CentralDocs> docs;
        private int resolveCalls;
        private int fetchCalls;

        private FakeRepository(Result<CentralClient.ResolvedVersion> version, Result<CentralDocs> docs) {
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
        Loader.LoadOptions options = new Loader.LoadOptions(httpThatMustNotReachTheNetwork(), null, repository);

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
        Loader.LoadOptions options = new Loader.LoadOptions(httpThatMustNotReachTheNetwork(), null, repository);

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
        Loader.LoadOptions options = new Loader.LoadOptions(httpThatMustNotReachTheNetwork(), null, repository);

        Result<LoadedPackage> loaded = Loader.loadPackage(PKG, options);

        Assert.assertFalse(loaded.isOk());
        Assert.assertTrue(loaded.failure() instanceof Failure.PackageNotFound);
    }
}
