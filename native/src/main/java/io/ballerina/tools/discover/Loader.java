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
import io.ballerina.tools.discover.central.CentralRepository;
import io.ballerina.tools.discover.central.DependenciesToml;
import io.ballerina.tools.discover.central.HttpOptions;
import io.ballerina.tools.discover.central.PackageRepository;
import io.ballerina.tools.discover.central.schema.CentralDocs;
import io.ballerina.tools.discover.model.FromCentral;
import io.ballerina.tools.discover.model.Pipeline;
import io.ballerina.tools.discover.views.Readmes;

import java.util.List;
import java.util.function.Function;

/**
 * The whole capability in two steps: resolve which version to read, then read it once.
 *
 * <p>They are separate because the resolution rule is the part with a cost. An explicit version is free; a
 * {@code Dependencies.toml} is a file read; asking Central is a round trip. Callers that already know the
 * version should never pay for the ones that follow.
 *
 * <p>{@link #loadPackage} is deliberately the only load: all five verbs read the same payload and differ only
 * in which document they write from it, so a verb cannot be cheap because it skipped work another verb does.
 *
 * @since 0.1.0
 */
public final class Loader {

    private Loader() {
    }

    /**
     * How the version is pinned, on top of the transport options.
     *
     * <p>There is no {@code version} field and no way for a caller to supply one. That is the design: version
     * resolution is INTERNAL (§3.8), and the only input to it is which project the process is standing in.
     *
     * @param http the transport options to fetch and cache through
     * @param projectDir the Ballerina project the lookup is running inside, or {@code null} when it is not in one
     * @param repositories where the package's version and docs payload come from, in priority order — see
     *     {@link PackageRepository}. Tried in order for each lookup, first success wins; {@link CentralRepository}
     *     is the only one and the only element this phase ever populates, but the shape is a list because the
     *     RFC's multi-source repository interface (Central, a local "Local Central" cache, Artifactory) is
     *     several sources considered for ONE lookup, not one source picked ahead of time.
     */
    public record LoadOptions(HttpOptions http, String projectDir, List<PackageRepository> repositories) {

        public LoadOptions {
            if (repositories.isEmpty()) {
                throw new IllegalArgumentException("at least one repository is required");
            }
        }

        public LoadOptions(HttpOptions http, String projectDir) {
            this(http, projectDir, List.of(CentralRepository.INSTANCE));
        }

        public static LoadOptions of(HttpOptions http) {
            return new LoadOptions(http, null, List.of(CentralRepository.INSTANCE));
        }
    }

    /**
     * Every repository in priority order, first success wins — the shared shape both {@link #resolveVersion} and
     * {@link #loadPackage} try candidates with.
     *
     * <p>When every repository fails, the LAST failure is returned rather than the first: a fast local source
     * (a "Local Central" cache) is expected to sit ahead of a slower authoritative one in the list, so the final
     * attempt is usually the one whose answer is worth reporting.
     */
    private static <T> Result<T> tryEachRepository(
            List<PackageRepository> repositories, Function<PackageRepository, Result<T>> attempt) {
        Result<T> last = null;
        for (PackageRepository repository : repositories) {
            last = attempt.apply(repository);
            if (last.isOk()) {
                return last;
            }
        }
        return last;
    }

    /**
     * Which version of the package to read.
     *
     * <p>{@code Dependencies.toml} outranks Central's latest deliberately: once a build has resolved the
     * package, the locked version is the one the component will actually compile against, and reading a newer one
     * produces signatures that do not exist for this caller. It also bypasses the versions-list TTL entirely, so
     * caching changes nothing about this precedence.
     *
     * <p>The KNOWN LIMIT, accepted: outside a project, a lookup resolves Central's latest, which may differ from
     * what a build would pick. For a package not yet in the dependency graph, latest IS the correct answer — it is
     * what adding the import would resolve to.
     */
    public static Result<CentralClient.ResolvedVersion> resolveVersion(
            QualifiedName qualified, LoadOptions options) {
        if (options.projectDir() != null) {
            String locked = DependenciesToml.lockedVersion(options.projectDir(), qualified);
            if (locked != null) {
                return fixed(locked);
            }
        }
        return tryEachRepository(options.repositories(),
                repository -> repository.resolveVersion(qualified, options.http()));
    }

    /** A version a build already locked, taken as given rather than confirmed against the registry. */
    private static Result<CentralClient.ResolvedVersion> fixed(String input) {
        Result<Version> parsed = Version.parse(input);
        return parsed.isOk()
                ? Result.ok(new CentralClient.ResolvedVersion(parsed.value(), false, true))
                : parsed.cast();
    }

    /**
     * The one thing a caller has to be told about how this was loaded, or nothing.
     *
     * <p>Whether the bytes came off disk or off the wire is not the reader's business — the document is the
     * same either way, and the line saying so cost a row in every header while answering a question nobody
     * asked. What a caller cannot recover on their own is that the version was never confirmed: with the
     * registry unreachable the newest published version may be something else entirely, so every signature
     * below is a claim about a version this run took on faith.
     *
     * <p>Returned as {@code null} in the ordinary case, so the header carries nothing when there is nothing
     * to say. That also makes stdout run-order-independent again: the same command twice now prints the same
     * bytes, where the old provenance line printed {@code central} then {@code cache}.
     */
    public static String unverifiedWarning(boolean stale) {
        return stale
                ? "the registry was unreachable, so this version came off disk unchecked"
                : null;
    }

    /**
     * Resolve and fetch as one pair per repository, so a version resolved on one source is never fetched from
     * another — a "Local Central" cache and real Central would not necessarily agree on what a given version
     * even contains.
     *
     * <p>A locked {@code Dependencies.toml} version is the one exception: it names no repository, so every
     * repository is tried in order to serve THAT version, rather than resolve deciding which one wins.
     *
     * <p>Module selection happens exactly once, after some repository has actually answered — never inside the
     * per-repository retry. A repository that has nothing for this package is a routine fallback case, but a
     * repository that answered with a package that just doesn't contain the requested module is not: every
     * repository serving the same immutable version would disagree with the caller the same way, so retrying
     * the rest would only risk burying that specific failure under an unrelated one from a later repository.
     */
    public static Result<LoadedPackage> loadPackage(QualifiedName qualified, LoadOptions options) {
        if (options.projectDir() != null) {
            String locked = DependenciesToml.lockedVersion(options.projectDir(), qualified);
            if (locked != null) {
                Result<CentralClient.ResolvedVersion> resolved = fixed(locked);
                if (!resolved.isOk()) {
                    return resolved.cast();
                }
                Result<CentralDocs> docs = tryEachRepository(options.repositories(),
                        repository -> repository.fetchDocs(qualified, resolved.value(), options.http()));
                return docs.isOk() ? build(qualified, resolved.value(), docs.value()) : docs.cast();
            }
        }
        Result<Fetched> fetched = tryEachRepository(options.repositories(), repository -> {
            Result<CentralClient.ResolvedVersion> resolved = repository.resolveVersion(qualified, options.http());
            if (!resolved.isOk()) {
                return resolved.cast();
            }
            Result<CentralDocs> docs = repository.fetchDocs(qualified, resolved.value(), options.http());
            return docs.isOk() ? Result.ok(new Fetched(resolved.value(), docs.value())) : docs.cast();
        });
        return fetched.isOk() ? build(qualified, fetched.value().resolved(), fetched.value().docs())
                : fetched.cast();
    }

    private record Fetched(CentralClient.ResolvedVersion resolved, CentralDocs docs) { }

    private static Result<LoadedPackage> build(
            QualifiedName qualified, CentralClient.ResolvedVersion resolved, CentralDocs docs) {
        Result<CentralDocs.Module> module = FromCentral.selectModule(docs, qualified);
        if (!module.isOk()) {
            return module.cast();
        }

        return Result.ok(new LoadedPackage(
                qualified,
                resolved.version(),
                Pipeline.build(module.value()),
                Readmes.collect(docs),
                unverifiedWarning(resolved.stale())));
    }
}
