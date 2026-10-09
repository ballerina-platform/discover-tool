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

import java.nio.file.Path;
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
 * <p>{@link #loadPackage} is deliberately the only load: every bucket reads the same payload and differs only in
 * what it renders from it.
 *
 * @since 0.1.0
 */
public final class Loader {

    private Loader() {
    }

    /**
     * How the version is pinned, on top of the transport options.
     *
     * <p>The version is resolved, not asked for, unless the caller writes one in the coordinate: an explicit
     * version outranks a locked {@code Dependencies.toml} one, which outranks Central's latest.
     *
     * @param http the transport options to fetch and cache through
     * @param projectDir the Ballerina project the lookup is running inside, or {@code null} when it is not in one
     * @param repositories where the package's version and docs payload come from, in priority order, first success
     *     wins — see {@link PackageRepository}. A list because the RFC's multi-source repository interface considers
     *     several sources for ONE lookup, not one source picked ahead of time.
     * @param module the {@code --module} value, or {@code null} for the package's own default module — read from
     *     the submodule's own page, since a package's page carries its default module alone
     * @param version the version written in the coordinate ({@code org/name:version}), or {@code null} to
     *     resolve one
     */
    public record LoadOptions(HttpOptions http, String projectDir, List<PackageRepository> repositories,
            String module, Version version) {

        public LoadOptions {
            if (repositories.isEmpty()) {
                throw new IllegalArgumentException("at least one repository is required");
            }
        }
    }

    // The LAST failure is returned: a fast local source sits ahead of the slower authoritative one, so the final
    // attempt's answer is the one worth reporting.
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
        Result<CentralClient.ResolvedVersion> chosen = chosenVersion(qualified, options);
        if (chosen != null) {
            return chosen;
        }
        return tryEachRepository(options.repositories(),
                repository -> repository.resolveVersion(qualified, options.http()));
    }

    // null when the version is neither written nor locked, so the repositories resolve it.
    private static Result<CentralClient.ResolvedVersion> chosenVersion(QualifiedName qualified, LoadOptions options) {
        if (options.version() != null) {
            return Result.ok(CentralClient.ResolvedVersion.written(options.version()));
        }
        if (options.projectDir() == null) {
            return null;
        }
        String locked = DependenciesToml.lockedVersion(options.projectDir(), qualified);
        if (locked == null) {
            return null;
        }
        Path lock = DependenciesToml.lockFile(options.projectDir());
        if (Version.isComplete(locked)) {
            return Result.ok(CentralClient.ResolvedVersion.locked(Version.parse(locked).value(), lock));
        }
        return Result.err(new Failure.Validation(
                lock + " locks " + qualified.qualified() + " at '" + locked + "', which is not a complete version.",
                "Fix the " + qualified.qualified() + " entry in " + lock + ", or delete the file and run `bal build` "
                        + "to regenerate it; or write a version after the package: " + qualified.qualified()
                        + ":<version>"));
    }

    /**
     * The one thing a caller must be told about how this was loaded — that the version was never confirmed
     * against an unreachable registry — or {@code null}. Whether the bytes came off disk or the wire is
     * deliberately not reported, so the same command prints the same bytes run after run.
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
     * <p>Under {@code --module} the version is still the PACKAGE's, and the page read is the submodule's own (its
     * {@code relatedModules} names the package's other modules). Every repository is asked for that page before
     * any package page is read, since a later one may publish it. Only when every repository that has the package
     * lacks the page is a package page read — purely so selection fails naming the submodules it does publish. Any
     * other failure is reported as it is, never turned into a missing module.
     *
     * <p>Module selection happens once, after some repository answered — never inside the per-repository retry:
     * every repository serving the same immutable version would lack the module the same way, so retrying would
     * only bury that failure under an unrelated one from a later repository.
     */
    public static Result<LoadedPackage> loadPackage(QualifiedName qualified, LoadOptions options) {
        Function<PackageRepository, Result<CentralClient.ResolvedVersion>> resolve = repository ->
                repository.resolveVersion(qualified, options.http());
        Result<CentralClient.ResolvedVersion> chosen = chosenVersion(qualified, options);
        if (chosen != null) {
            if (!chosen.isOk()) {
                return chosen.cast();
            }
            resolve = repository -> chosen;
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
        Result<CentralDocs.Module> module = FromCentral.selectModule(docs, qualified, submodule, resolved.version(),
                options.version());
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
                moduleNamesOf(docs, qualified),
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

    private static List<String> moduleNamesOf(CentralDocs docs, QualifiedName qualified) {
        int prefix = qualified.name().length() + 1;
        return FromCentral.submodulesOf(docs, qualified).stream()
                .map(module -> module.id().substring(prefix))
                .toList();
    }

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
