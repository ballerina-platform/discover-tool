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
import io.ballerina.tools.discover.model.Bindings;
import io.ballerina.tools.discover.model.FromCentral;
import io.ballerina.tools.discover.model.Library;
import io.ballerina.tools.discover.model.Pipeline;
import io.ballerina.tools.discover.source.SourceInclusions;
import io.ballerina.tools.discover.views.Readmes;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

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
     * <p>The version is resolved, not asked for, unless the caller pins one with {@code --version}: an explicit
     * version outranks a locked {@code Dependencies.toml} one, which outranks Central's latest.
     *
     * @param http the transport options to fetch and cache through
     * @param projectDir the Ballerina project the lookup is running inside, or {@code null} when it is not in one
     * @param repositories where the package's version and docs payload come from, in priority order — see
     *     {@link PackageRepository}. Tried in order for each lookup, first success wins; {@link CentralRepository}
     *     is the only one and the only element this phase ever populates, but the shape is a list because the
     *     RFC's multi-source repository interface (Central, a local "Local Central" cache, Artifactory) is
     *     several sources considered for ONE lookup, not one source picked ahead of time.
     * @param module the {@code --module} value, or {@code null} for the package's own default module — read from
     *     the submodule's own page, since a package's page carries its default module alone
     * @param version the {@code --version} value, or {@code null} to resolve one
     */
    public record LoadOptions(HttpOptions http, String projectDir, List<PackageRepository> repositories,
            String module, String version) {

        public LoadOptions {
            if (repositories.isEmpty()) {
                throw new IllegalArgumentException("at least one repository is required");
            }
        }

        public LoadOptions(HttpOptions http, String projectDir, List<PackageRepository> repositories,
                String module) {
            this(http, projectDir, repositories, module, null);
        }

        public LoadOptions(HttpOptions http, String projectDir, List<PackageRepository> repositories) {
            this(http, projectDir, repositories, null, null);
        }

        public LoadOptions(HttpOptions http, String projectDir) {
            this(http, projectDir, List.of(CentralRepository.INSTANCE), null, null);
        }

        public LoadOptions(HttpOptions http, String projectDir, String module) {
            this(http, projectDir, List.of(CentralRepository.INSTANCE), module, null);
        }

        public LoadOptions(HttpOptions http, String projectDir, String module, String version) {
            this(http, projectDir, List.of(CentralRepository.INSTANCE), module, version);
        }

        public static LoadOptions of(HttpOptions http) {
            return new LoadOptions(http, null, List.of(CentralRepository.INSTANCE), null, null);
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
        String chosen = chosenVersion(qualified, options);
        if (chosen != null) {
            return fixed(chosen, options.version() != null);
        }
        return tryEachRepository(options.repositories(),
                repository -> repository.resolveVersion(qualified, options.http()));
    }

    /** The version the caller named, else the one a project locked, else {@code null}: resolve one. */
    private static String chosenVersion(QualifiedName qualified, LoadOptions options) {
        if (options.version() != null) {
            return options.version();
        }
        return options.projectDir() == null ? null : DependenciesToml.lockedVersion(options.projectDir(), qualified);
    }

    /** A version a build already locked, taken as given rather than confirmed against the registry. */
    private static Result<CentralClient.ResolvedVersion> fixed(String input, boolean pinned) {
        Result<Version> parsed = Version.parse(input);
        return parsed.isOk()
                ? Result.ok(new CentralClient.ResolvedVersion(parsed.value(), false, true, pinned))
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
     * <p>A pinned or locked {@code Dependencies.toml} version is the one exception: it names no repository, so every
     * repository is tried in order to serve THAT version, rather than resolve deciding which one wins.
     *
     * <p>Under {@code --module} the version is still the PACKAGE's, resolved from its literal coordinate, and the
     * page read is the submodule's own. Its {@code relatedModules} lists the package's other modules, so the
     * success path never fetches the package's page as well. Every repository is asked for the submodule's page
     * before any package page is read: one repository lacking it is the routine fallback case, and a later one
     * may publish it. Only when every repository that has the package answers that it has no such page is a
     * package page read — from those same repositories in order, each at the version it resolved — purely so
     * selection fails naming the submodules the package does publish. Any other failure on the way is reported
     * as it is, never turned into a missing module.
     *
     * <p>Module selection happens exactly once, after some repository has actually answered — never inside the
     * per-repository retry. A repository that has nothing for this package is a routine fallback case, but a
     * repository that answered with a package that just doesn't contain the requested module is not: every
     * repository serving the same immutable version would disagree with the caller the same way, so retrying
     * the rest would only risk burying that specific failure under an unrelated one from a later repository.
     */
    public static Result<LoadedPackage> loadPackage(QualifiedName qualified, LoadOptions options) {
        Function<PackageRepository, Result<CentralClient.ResolvedVersion>> resolve = repository ->
                repository.resolveVersion(qualified, options.http());
        String chosen = chosenVersion(qualified, options);
        if (chosen != null) {
            Result<CentralClient.ResolvedVersion> fixed = fixed(chosen, options.version() != null);
            if (!fixed.isOk()) {
                return fixed.cast();
            }
            resolve = repository -> fixed;
        }
        Result<Fetched> fetched = options.module() == null
                ? fetchPackage(qualified, options, resolve)
                : fetchSubmodule(qualified, options, resolve);
        return fetched.isOk() ? build(qualified, fetched.value(), options) : fetched.cast();
    }

    private static Result<Fetched> fetchPackage(QualifiedName qualified, LoadOptions options,
            Function<PackageRepository, Result<CentralClient.ResolvedVersion>> resolve) {
        return tryEachRepository(options.repositories(), repository -> {
            Result<CentralClient.ResolvedVersion> resolved = resolve.apply(repository);
            if (!resolved.isOk()) {
                return resolved.cast();
            }
            Result<CentralDocs> docs = repository.fetchDocs(qualified, resolved.value(), options.http());
            return docs.isOk() ? Result.ok(new Fetched(repository, resolved.value(), docs.value())) : docs.cast();
        });
    }

    private static Result<Fetched> fetchSubmodule(QualifiedName qualified, LoadOptions options,
            Function<PackageRepository, Result<CentralClient.ResolvedVersion>> resolve) {
        List<Candidate> withoutPage = new ArrayList<>();
        Result<Fetched> last = null;
        Result<Fetched> otherFailure = null;
        for (PackageRepository repository : options.repositories()) {
            Result<CentralClient.ResolvedVersion> resolved = resolve.apply(repository);
            if (!resolved.isOk()) {
                last = resolved.cast();
                if (!(resolved.failure() instanceof Failure.PackageNotFound)) {
                    otherFailure = last;
                }
                continue;
            }
            Result<CentralDocs> page =
                    repository.fetchModuleDocs(qualified, options.module(), resolved.value(), options.http());
            if (page.isOk()) {
                return Result.ok(new Fetched(repository, resolved.value(), page.value()));
            }
            last = page.cast();
            if (page.failure() instanceof Failure.PackageNotFound) {
                withoutPage.add(new Candidate(repository, resolved.value()));
            } else {
                otherFailure = last;
            }
        }
        if (otherFailure != null) {
            return otherFailure;
        }
        if (withoutPage.isEmpty()) {
            return last;
        }
        Result<Fetched> notFound = null;
        Result<Fetched> failed = null;
        for (Candidate candidate : withoutPage) {
            Result<CentralDocs> docs =
                    candidate.repository().fetchDocs(qualified, candidate.resolved(), options.http());
            if (docs.isOk()) {
                return Result.ok(new Fetched(candidate.repository(), candidate.resolved(), docs.value()));
            }
            if (docs.failure() instanceof Failure.PackageNotFound) {
                notFound = docs.cast();
            } else {
                failed = docs.cast();
            }
        }
        return failed != null ? failed : notFound;
    }

    /**
     * A repository that has the package, but no page for the submodule asked for.
     *
     * @param repository the repository
     * @param resolved the package version it resolved
     */
    private record Candidate(PackageRepository repository, CentralClient.ResolvedVersion resolved) { }

    /**
     * What one repository served, kept together so anything read later — the package source — comes from the
     * repository that served the docs, for the same reason resolve and fetch are paired.
     *
     * @param repository the repository that answered
     * @param resolved the version it was read at
     * @param docs the docs payload it served
     */
    private record Fetched(PackageRepository repository, CentralClient.ResolvedVersion resolved, CentralDocs docs) { }

    private static Result<LoadedPackage> build(QualifiedName qualified, Fetched fetched, LoadOptions options) {
        String submodule = options.module();
        CentralDocs docs = fetched.docs();
        CentralClient.ResolvedVersion resolved = fetched.resolved();
        Result<CentralDocs.Module> module = FromCentral.selectModule(docs, qualified, submodule, resolved.version());
        if (!module.isOk()) {
            return module.cast();
        }
        Library library = Pipeline.build(module.value());
        Supplier<Library> bound = Bindings.needsSource(module.value())
                ? memoized(() -> Pipeline.build(module.value(), SourceInclusions.load(
                        fetched.repository(), qualified, resolved, module.value().id(), options.http())))
                : () -> library;

        return Result.ok(new LoadedPackage(
                qualified,
                resolved.version(),
                library,
                Readmes.of(module.value()),
                submodule,
                submodulesOf(docs, qualified, submodule),
                unverifiedWarning(resolved.stale()),
                options.version(),
                bound));
    }

    private static <T> Supplier<T> memoized(Supplier<T> compute) {
        List<T> once = new ArrayList<>(1);
        return () -> {
            if (once.isEmpty()) {
                once.add(compute.get());
            }
            return once.get(0);
        };
    }

    /**
     * The submodules that belong to the module being addressed, name and summary only — computed off the SAME page
     * a repository already served, never a second fetch, since every page of a package names all of its modules in
     * {@code relatedModules}. For the default module ({@code submodule} is {@code null}) that is every module the
     * package publishes; for a named submodule only its own children (ids under {@code <name>.<submodule>.}), never
     * its siblings, which belong to the package's listing and not to this module's.
     */
    private static List<LoadedPackage.Submodule> submodulesOf(
            CentralDocs docs, QualifiedName qualified, String submodule) {
        String prefix = qualified.name() + ".";
        String childPrefix = submodule == null ? prefix : prefix + submodule + ".";
        return FromCentral.submodulesOf(docs, qualified).stream()
                .filter(module -> module.id().startsWith(childPrefix))
                .map(module -> new LoadedPackage.Submodule(
                        module.id().substring(prefix.length()), module.summary().orElse("").trim()))
                .toList();
    }
}
