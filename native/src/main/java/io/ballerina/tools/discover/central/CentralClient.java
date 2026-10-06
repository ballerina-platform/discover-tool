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

package io.ballerina.tools.discover.central;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import io.ballerina.tools.discover.Failure;
import io.ballerina.tools.discover.QualifiedName;
import io.ballerina.tools.discover.Result;
import io.ballerina.tools.discover.Texts;
import io.ballerina.tools.discover.Version;
import io.ballerina.tools.discover.cache.DocsCache;
import io.ballerina.tools.discover.central.schema.CentralDocs;
import io.ballerina.tools.discover.central.schema.Schema;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything that talks to Ballerina Central.
 *
 * <p>This is the boundary: it is the only module that handles untyped JSON off a socket, and the only one that
 * can fail for reasons outside the process. Callers get a {@link Result} — a network hiccup is a value here,
 * not an exception threading through the render pipeline.
 *
 * @since 0.1.0
 */
public final class CentralClient {

    public static final String CENTRAL_BASE_URL = "https://api.central.ballerina.io/2.0/";

    /**
     * This repository's own identity, for the cache key's repository dimension — see {@link DocsCache}. Stable
     * and filesystem-safe, unlike {@link CentralRepository#describe()}, which is free-form prose for a human.
     */
    public static final String REPOSITORY_ID = "central";

    /**
     * How long Central's answer to "what is the latest version" is believed.
     *
     * <p>The measured lookup episode runs 70 to 260 seconds, so ten minutes spans a whole episode without a
     * second registry round trip — they cost 1.0 to 1.5s each — while a package published mid-run is still
     * picked up. It is the one mutable response this reader caches; a docs payload for a named version is
     * immutable and never expires.
     */
    public static final long LATEST_TTL_MS = 600_000;

    private CentralClient() {
    }

    /**
     * A version, and what a caller may need to know about where it came from.
     *
     * @param version the resolved version
     * @param stale the registry was unreachable and this came off disk unverified
     * @param supplied the CALLER chose this version — {@code --version}, or a {@code Dependencies.toml} the
     *     reader was pointed at — rather than the reader resolving it. It decides what a later 404 from the
     *     docs endpoint means: a version the caller chose is theirs to correct, and one the reader resolved is
     *     not, so advice about changing it would name a command they never wrote.
     * @param pinned the version came from {@code --version} itself, so a command printed for the caller must
     *     repeat it
     */
    public record ResolvedVersion(Version version, boolean stale, boolean supplied, boolean pinned) {

        public ResolvedVersion(Version version, boolean stale) {
            this(version, stale, false, false);
        }
    }

    // -----------------------------------------------------------------------
    // The retry loop
    // -----------------------------------------------------------------------

    /**
     * Statuses worth trying again. A 404 is an answer — the package is not there — and retrying it only spends
     * the caller's budget.
     */
    private static boolean isRetryableStatus(int status) {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    /** {@code Retry-After} in either of its legal forms, as milliseconds, or {@code -1}. */
    public static long parseRetryAfter(String header, long nowMs) {
        if (header == null) {
            return -1;
        }
        String trimmed = header.trim();
        if (trimmed.isEmpty()) {
            return -1;
        }
        try {
            return Long.parseLong(trimmed) * 1000;
        } catch (NumberFormatException notAnInteger) {
            // Fall through to the date form.
        }
        try {
            ZonedDateTime when = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME);
            return Math.max(0, when.toInstant().toEpochMilli() - nowMs);
        } catch (DateTimeParseException notADate) {
            return -1;
        }
    }

    /** Exponential backoff with up to 25% jitter, so parallel callers do not resonate. */
    public static long backoffMs(int attempt, long baseDelayMs, double jitter) {
        return (long) Math.floor(baseDelayMs * Math.pow(2, attempt) * (1 + jitter * 0.25));
    }

    /**
     * What one attempt produced, before the URL and the attempt count are known.
     *
     * <p>Separate from {@link Failure} on purpose: the retry loop branches on {@code retryable} and
     * {@code retryAfterMs}, which a finished {@code Failure} has no field for and no caller should see.
     */
    private sealed interface Outcome {

        record Body(JsonElement value) implements Outcome { }

        /**
         * {@code retryAfterMs} is negative when upstream did not say.
         *
         * @param message what upstream, or the transport, said
         * @param status the HTTP status the attempt failed with, or {@code null}
         * @param timedOut whether the attempt failed by timing out
         * @param retryable whether another attempt is worth making
         * @param retryAfterMs how long to wait before retrying, or negative when upstream did not say
         */
        record Spent(String message, Integer status, boolean timedOut, boolean retryable, long retryAfterMs)
                implements Outcome { }
    }

    private static Outcome attemptFetch(String url, HttpOptions options) {
        HttpTransport.Reply reply = options.transport().get(url, options.timeoutMs());
        return switch (reply) {
            case HttpTransport.Reply.TimedOut ignored -> new Outcome.Spent(null, null, true, true, -1);
            case HttpTransport.Reply.Failed failed ->
                    new Outcome.Spent(failed.message(), null, false, true, -1);
            case HttpTransport.Reply.Answered answered -> {
                if (!answered.isOk()) {
                    boolean retryable = isRetryableStatus(answered.status());
                    long retryAfterMs =
                            retryable ? parseRetryAfter(answered.retryAfter(), options.now()) : -1;
                    yield new Outcome.Spent(
                            "HTTP " + answered.status(), answered.status(), false, retryable, retryAfterMs);
                }
                try {
                    JsonElement parsed = JsonParser.parseString(answered.body());
                    yield new Outcome.Body(parsed == null ? JsonNull.INSTANCE : parsed);
                } catch (RuntimeException malformed) {
                    // Upstream serving something that is not JSON is not a transient condition.
                    yield new Outcome.Spent(
                            "malformed JSON: " + malformed.getMessage(), null, false, false, -1);
                }
            }
        };
    }

    /**
     * GET a JSON document, retrying the failures that are worth retrying and stopping at a wall-clock budget.
     *
     * <p>Retries live here and nowhere else: Central is a remote service that can 429 or 5xx for reasons that
     * pass, whereas everything else this package does is local and deterministic.
     */
    public static Result<JsonElement> fetchJson(String url, HttpOptions options) {
        long deadline = options.now() + options.budgetMs();
        Outcome.Spent last = null;

        for (int attempt = 0; attempt < options.maxAttempts(); attempt++) {
            if (attempt > 0) {
                long remaining = deadline - options.now();
                if (remaining <= 0) {
                    break;
                }
                long wait = last != null && last.retryAfterMs() >= 0
                        ? last.retryAfterMs()
                        : backoffMs(attempt - 1, options.baseDelayMs(), options.jitter());
                options.sleep(Math.min(remaining, wait));
            }
            switch (attemptFetch(url, options)) {
                case Outcome.Body body -> {
                    return Result.ok(body.value());
                }
                case Outcome.Spent spent -> {
                    if (!spent.retryable()) {
                        return Result.err(toFailure(spent, url, attempt + 1, options.budgetMs()));
                    }
                    last = spent;
                }
            }
        }

        if (last == null) {
            return Result.err(new Failure.Upstream(
                    url, options.maxAttempts(), "no attempt was made", Failure.UPSTREAM_SUGGESTION, null));
        }
        return Result.err(toFailure(last, url, options.maxAttempts(), options.budgetMs()));
    }

    private static Failure toFailure(Outcome.Spent spent, String url, int attempts, long budgetMs) {
        if (spent.timedOut()) {
            return new Failure.Timeout(url, budgetMs, Failure.TIMEOUT_SUGGESTION);
        }
        return new Failure.Upstream(
                url, attempts, spent.message(), Failure.UPSTREAM_SUGGESTION, spent.status());
    }

    // -----------------------------------------------------------------------
    // Version resolution
    // -----------------------------------------------------------------------

    /**
     * The version to read a package at.
     *
     * <p>The coordinate is always one literal, complete package name; a submodule is reached only through
     * {@code --module}. A dotted name is still far more often a package than a module of one —
     * {@code ballerinax/googleapis.sheets} is its own package — so the full name is the only thing asked about
     * on the way to an answer, and costs one round trip like any other.
     *
     * <p>Only when the registry has no row for a dotted name are its prefixes asked about, and then only to
     * word the failure: {@code ballerinax/aws.auth} is the {@code auth} module of {@code ballerinax/aws}, and the
     * caller is better served by the command that reads it than by a bare "not found". The module is never read
     * in the package's place — that answer would carry a coordinate the caller cannot import, build against, or
     * pass back to this tool.
     */
    public static Result<ResolvedVersion> resolveLatestVersion(QualifiedName qualified, HttpOptions options) {
        Result<ResolvedVersion> direct = resolvePublishedVersion(qualified, options);
        if (direct.isOk() || !(direct.failure() instanceof Failure.PackageNotFound)
                || containingPackages(qualified).isEmpty()) {
            return direct;
        }
        return Result.err(notAPackage(qualified, null, options));
    }

    /**
     * The packages a dotted coordinate could be a module of, longest first.
     *
     * <p>Empty for an undotted name, which is every ordinary package — so the common path adds no work.
     */
    private static List<QualifiedName> containingPackages(QualifiedName qualified) {
        List<QualifiedName> parents = new ArrayList<>();
        String name = qualified.name();
        for (int dot = name.lastIndexOf('.'); dot > 0; dot = name.lastIndexOf('.', dot - 1)) {
            Result<QualifiedName> parent = QualifiedName.parse(qualified.org() + "/" + name.substring(0, dot));
            if (parent.isOk()) {
                parents.add(parent.value());
            }
        }
        return parents;
    }

    /**
     * A dotted name the registry has no package for, explained by the package it is a module of when there is
     * one.
     *
     * <p>{@code a.b.c} can be a module of {@code a.b} or of {@code a}, so each prefix is asked in turn, and the
     * first that exists is asked which modules it publishes — so the {@code --module} command is offered only
     * for a module that is really there. A prefix the registry cannot answer for at all ends the probe with the
     * plain failure: claiming nothing contains the module, when one of the packages that might was never
     * actually checked, would send the caller to fix a spelling that may be right.
     */
    private static Failure notAPackage(QualifiedName qualified, String pin, HttpOptions options) {
        List<QualifiedName> parents = containingPackages(qualified);
        for (QualifiedName parent : parents) {
            Result<ResolvedVersion> probed = resolvePublishedVersion(parent, options);
            if (probed.isOk()) {
                return moduleOf(qualified, parent, probed.value().version(), pin, options);
            }
            if (!(probed.failure() instanceof Failure.PackageNotFound)) {
                return notFound(qualified);
            }
        }
        StringBuilder tried = new StringBuilder();
        for (QualifiedName parent : parents) {
            tried.append(tried.isEmpty() ? "" : ", ").append(parent.qualified());
        }
        return new Failure.PackageNotFound(
                qualified.qualified(),
                "Central publishes no package under this name, and none of the packages it could be a module "
                        + "of exists either (tried " + tried + "). Check the org/name spelling; "
                        + "`bal search <keyword>` lists what Central publishes.");
    }

    /**
     * The failure for a dotted name whose prefix is a package, worded by what that version's registry row says:
     * the module is listed, it is not, or the row could not be read. Fetched through {@link #fetchJson}'s retries
     * but not cached: it is asked only on this failure path, and the command it leads to never needs it again.
     */
    private static Failure moduleOf(
            QualifiedName qualified, QualifiedName parent, Version version, String pin, HttpOptions options) {
        String submodule = qualified.name().substring(parent.name().length() + 1);
        String command = "`bal discover " + Texts.shellWord(parent.qualified()) + " --module "
                + Texts.shellWord(submodule) + pinArgument(pin) + "`";
        String url = CENTRAL_BASE_URL + "registry/packages/" + encode(parent.org()) + "/" + encode(parent.name())
                + "/" + encode(version.text());
        Result<JsonElement> response = fetchJson(url, options);
        Optional<List<String>> listed = response.isOk() ? Coordinates.moduleNames(response.value()) : Optional.empty();
        String suggestion;
        if (listed.isEmpty()) {
            suggestion = "'" + qualified.qualified() + "' is not a package, but " + parent.qualified()
                    + " is, and Central could not say which modules it publishes. If '" + submodule
                    + "' is one of them, read it with " + command + ".";
        } else if (listed.get().contains(qualified.name())) {
            suggestion = "'" + qualified.qualified() + "' is not a package: it is the '" + submodule
                    + "' module of the " + parent.qualified() + " package. Read it with " + command + ".";
        } else {
            List<String> modules = listed.get();
            suggestion = "'" + qualified.qualified() + "' is not a package, and " + parent.qualified()
                    + " publishes no '" + submodule + "' module (its modules are " + String.join(", ", modules)
                    + "). Check the name; `bal search <keyword>` lists what Central publishes.";
        }
        return new Failure.PackageNotFound(qualified.qualified(), suggestion);
    }

    /**
     * The latest published version of one package.
     *
     * <p>{@code registry/packages/<org>/<name>} answers with just this package's versions, newest first. The
     * alternative — listing the org and filtering client-side — costs about 45 seconds for {@code ballerinax},
     * which has roughly a thousand packages, and that cost lands on every lookup an agent makes without an
     * explicit version.
     */
    private static Result<ResolvedVersion> resolvePublishedVersion(QualifiedName qualified, HttpOptions options) {
        DocsCache cache = options.cache();
        DocsCache.PackageKey key = new DocsCache.PackageKey(REPOSITORY_ID, qualified.org(), qualified.name());

        // `--refresh` re-resolves unconditionally. An earlier draft made the re-download conditional on the
        // version having changed, which made the flag a no-op in exactly the case its own error message
        // recommends it for.
        if (!options.refresh()) {
            DocsCache.LatestEntry entry = cache.readLatest(key);
            // The lower bound matters as much as the TTL: a clock that jumped backwards leaves a
            // future-stamped entry looking fresh forever.
            if (entry != null && options.now() >= entry.atMs()
                    && options.now() - entry.atMs() < LATEST_TTL_MS) {
                Result<Version> cached = Version.parse(entry.version());
                if (cached.isOk()) {
                    return Result.ok(new ResolvedVersion(cached.value(), false));
                }
            }
        }

        String url = CENTRAL_BASE_URL + "registry/packages/" + encode(qualified.org())
                + "/" + encode(qualified.name());
        Result<JsonElement> response = fetchJson(url, options);
        if (!response.isOk()) {
            // Central answers an unpublished org/name with a 400, not a 404. Either way the fact is "no such
            // package", and reporting it as a transport error would send the caller looking at the network for
            // a typo.
            Integer status = response.failure() instanceof Failure.Upstream upstream ? upstream.status() : null;
            if (status != null && (status == 400 || status == 404)) {
                return Result.err(notFound(qualified));
            }
            Version offline = offlineVersion(cache, key, options);
            if (offline != null) {
                return Result.ok(new ResolvedVersion(offline, true));
            }
            return response.cast();
        }

        String latest = Coordinates.newestVersion(response.value());
        if (latest == null) {
            return Result.err(notFound(qualified));
        }
        // Through the parser rather than trusted: this is a string off the network, and the cache turns it
        // into a path segment.
        Result<Version> parsed = Version.parse(latest);
        if (!parsed.isOk()) {
            return parsed.cast();
        }
        cache.writeLatest(key, new DocsCache.LatestEntry(parsed.value().text(), options.now()));
        return Result.ok(new ResolvedVersion(parsed.value(), false));
    }

    /**
     * The best version answer available with the registry unreachable: an expired {@code latest} entry first,
     * then the newest docs payload already on disk.
     *
     * <p>Without this, a warm cached payload plus one registry blip is a hard failure that can burn the
     * client's full budget — four times over in a four-invocation episode. The {@code stale} flag is how the
     * caller learns to say so on the provenance line rather than claiming a version it did not verify.
     */
    private static Version offlineVersion(DocsCache cache, DocsCache.PackageKey key, HttpOptions options) {
        DocsCache.LatestEntry expired = cache.readLatest(key);
        if (expired != null) {
            Result<Version> parsed = Version.parse(expired.version());
            // Only trust a stamp that is not from the future; a bogus one is no better than the listing below.
            if (parsed.isOk() && options.now() >= expired.atMs()) {
                return parsed.value();
            }
        }
        for (String candidate : cache.listVersions(key)) {
            Result<Version> parsed = Version.parse(candidate);
            if (parsed.isOk()) {
                return parsed.value();
            }
        }
        return null;
    }

    /**
     * The registry had no row for this name.
     *
     * <p>A dotted name gets its own, more specific advice from {@link #notAPackage} whenever the registry can
     * say which package, if any, the name is a module of.
     */
    private static Failure notFound(QualifiedName qualified) {
        return new Failure.PackageNotFound(
                qualified.qualified(),
                "Check the org/name spelling; `bal search <keyword>` lists what Central publishes.");
    }

    // -----------------------------------------------------------------------
    // The docs payload
    // -----------------------------------------------------------------------

    /**
     * The API docs for one published version, from disk when they are already there.
     *
     * <p>This is where the cache belongs: above the retry loop, so a hit costs no attempt, and below the
     * schema, so what gets stored is not derived from our own code. It is also the reason the addressed verbs
     * are affordable at all — at 4.9 to 6.6 seconds and 12.4MB per invocation the CLI can only be asked once
     * per package, which is what forces a 22,829-line document to be navigated by hand. Once re-opening a
     * package is cheap, four precise questions beat one big answer.
     *
     * <p>ANY problem with a cached entry is a miss, never a failure: a missing file, an unreadable one, a
     * truncated one, one that is not JSON, one the schema no longer accepts, one whose coordinates do not
     * match its own path. Each of those drops the entry and uses the network, so a corrupt entry cannot
     * produce a wrong document and heals on the next successful fetch.
     *
     * <p>{@code --refresh} skips the read but never drops the entry: only a fetch that succeeds replaces it, so a
     * refresh while Central is unreachable leaves the cached copy for every later run.
     */
    public static Result<CentralDocs> fetchDocs(
            QualifiedName qualified, ResolvedVersion resolved, HttpOptions options) {
        Version version = resolved.version();
        DocsCache cache = options.cache();
        DocsCache.DocsKey key =
                new DocsCache.DocsKey(REPOSITORY_ID, qualified.org(), qualified.name(), version.text());
        String label = qualified.versioned(version);

        if (!options.refresh()) {
            JsonElement cached = cache.readDocs(key);
            if (cached != null) {
                Result<CentralDocs> parsed = Coordinates.match(cached, qualified, version)
                        ? Schema.parse(cached, label)
                        : null;
                if (parsed != null && parsed.isOk()) {
                    return parsed;
                }
                cache.removeDocs(key);
            }
        }

        String url = CENTRAL_BASE_URL + "docs/" + encode(qualified.org())
                + "/" + encode(qualified.name()) + "/" + encode(version.text());
        Result<JsonElement> response = fetchJson(url, options);
        if (!response.isOk()) {
            // 404 here is specific: the org/name may well exist, this VERSION does not — and which half the
            // caller can act on depends on who chose the version. A version they passed is theirs to correct.
            // One the reader resolved is not: telling them to "omit the version" names what they already did.
            if (response.failure() instanceof Failure.Upstream upstream
                    && upstream.status() != null && upstream.status() == 404) {
                return Result.err(new Failure.PackageNotFound(label, missingVersion(
                        qualified, version, resolved.supplied(), options)));
            }
            return response.cast();
        }
        // A version for a module path can still arrive from a `latest` entry an older build of this reader wrote
        // under the module's own key; the page itself says what it is.
        if (Coordinates.describesSubmodule(response.value(), qualified)) {
            return Result.err(notAPackage(qualified, pinOf(resolved), options));
        }
        Result<CentralDocs> parsed = Schema.parse(response.value(), label);
        if (!parsed.isOk()) {
            return parsed.cast();
        }
        // Written only after it parses: a payload this reader cannot read is not worth storing, and storing it
        // would make every later run pay the same drift.
        cache.writeDocs(key, response.value());
        return parsed;
    }

    /**
     * One submodule's own docs page, at its package's version.
     *
     * <p>A package's page carries its default module alone; every other module of it has a page of its own at
     * {@code docs/<org>/<package>.<submodule>/<version>}, which is what {@code --module} reads. It is cached
     * under a {@link DocsCache.ModuleKey}, never a package's {@link DocsCache.DocsKey}: the page that is a miss
     * for a package lookup ({@link Coordinates#describesSubmodule}) is the answer here, and the two must never
     * share an entry.
     *
     * <p>A 404, or a page that is not a submodule of this package, is {@code package-not-found} for the dotted
     * coordinate; {@link io.ballerina.tools.discover.Loader} turns that into the caller-facing failure, because
     * only it can list what the package does publish.
     */
    public static Result<CentralDocs> fetchModuleDocs(
            QualifiedName qualified, String submodule, ResolvedVersion resolved, HttpOptions options) {
        Version version = resolved.version();
        DocsCache cache = options.cache();
        DocsCache.ModuleKey key = new DocsCache.ModuleKey(
                REPOSITORY_ID, qualified.org(), qualified.name(), submodule, version.text());
        String moduleName = qualified.name() + "." + submodule;
        String label = qualified.org() + "/" + moduleName + ":" + version.text();

        if (!options.refresh()) {
            JsonElement cached = cache.readModuleDocs(key);
            if (cached != null) {
                Result<CentralDocs> parsed = Coordinates.isModulePage(cached, qualified, submodule, version)
                        ? Schema.parse(cached, label)
                        : null;
                if (parsed != null && parsed.isOk()) {
                    return parsed;
                }
                cache.removeModuleDocs(key);
            }
        }

        String url = CENTRAL_BASE_URL + "docs/" + encode(qualified.org())
                + "/" + encode(moduleName) + "/" + encode(version.text());
        Result<JsonElement> response = fetchJson(url, options);
        if (!response.isOk()) {
            if (response.failure() instanceof Failure.Upstream upstream
                    && upstream.status() != null && upstream.status() == 404) {
                return Result.err(noSuchModulePage(label, qualified, pinOf(resolved), submodule));
            }
            return response.cast();
        }
        if (!Coordinates.isModulePage(response.value(), qualified, submodule, version)) {
            return Result.err(noSuchModulePage(label, qualified, pinOf(resolved), submodule));
        }
        Result<CentralDocs> parsed = Schema.parse(response.value(), label);
        if (!parsed.isOk()) {
            return parsed.cast();
        }
        cache.writeModuleDocs(key, response.value());
        return parsed;
    }

    private static String pinOf(ResolvedVersion resolved) {
        return resolved.pinned() ? resolved.version().text() : null;
    }

    private static String pinArgument(String pin) {
        return pin == null ? "" : " --version " + Texts.shellWord(pin);
    }

    private static Failure noSuchModulePage(String label, QualifiedName qualified, String pin, String submodule) {
        return new Failure.PackageNotFound(label, qualified.qualified() + " publishes no '" + submodule
                + "' module at this version. Run `bal discover " + Texts.shellWord(qualified.qualified())
                + pinArgument(pin) + "` to list the submodules it does publish.");
    }

    /**
     * The docs endpoint answered 404: the org/name may well exist, this VERSION does not.
     *
     * <p>T10, closed in the failure rather than in the grammar. Which half the caller can act on depends on who
     * chose the version — and since the redesign, NEITHER answer is "omit the version", because there is no
     * version argument to omit. A version this reader resolved is its own problem to explain; a version a
     * {@code Dependencies.toml} locked is a real skew between the project and Central, so the failure NAMES what
     * Central publishes instead of telling the caller to go and look, which no verb would have let them do.
     */
    private static String missingVersion(
            QualifiedName qualified, Version version, boolean supplied, HttpOptions options) {
        if (!supplied) {
            return "Central published no '" + qualified.qualified() + "' at " + version.text()
                    + ", the version resolved for it. Check the name — `bal search <keyword>` lists what Central "
                    + "publishes.";
        }
        String published = publishedVersions(qualified, options);
        return "Central does not publish '" + qualified.qualified() + "' at " + version.text()
                + ", the version named by --version or locked by your project's Dependencies.toml"
                + (published == null ? "" : "; published versions are " + published)
                + ". Pass one of them with --version, or reconcile Dependencies.toml with the registry so a "
                + "lookup and a build see the same one.";
    }

    private static final int LISTED_VERSIONS = 10;

    /**
     * The versions Central lists, as a sentence, or {@code null} when the registry cannot say.
     *
     * <p>Best-effort by design: this runs on a path that has ALREADY failed, so a second failure must degrade the
     * message rather than replace the failure the caller actually hit.
     */
    private static String publishedVersions(QualifiedName qualified, HttpOptions options) {
        String url = CENTRAL_BASE_URL + "registry/packages/" + encode(qualified.org())
                + "/" + encode(qualified.name());
        Result<JsonElement> response = fetchJson(url, options);
        if (!response.isOk()) {
            return null;
        }
        List<String> versions = Coordinates.publishedVersions(response.value());
        if (versions.isEmpty()) {
            return null;
        }
        return versions.size() <= LISTED_VERSIONS
                ? String.join(", ", versions)
                : String.join(", ", versions.subList(0, LISTED_VERSIONS)) + " and "
                        + (versions.size() - LISTED_VERSIONS) + " older";
    }

    // -----------------------------------------------------------------------
    // The package archive
    // -----------------------------------------------------------------------

    /**
     * One module's {@code .bal} files, read out of the version's published bala.
     *
     * <p>The registry's per-version entry names a signed {@code balaURL}; the archive behind it is the package
     * exactly as {@code bal pull} would fetch it. Not cached here: what is worth keeping is the small fact a
     * caller derives from the source, not the archive, which runs to tens of megabytes once a package bundles
     * its native jars.
     */
    public static Optional<Map<String, String>> fetchModuleSources(
            QualifiedName qualified, Version version, String moduleId, HttpOptions options) {
        String url = CENTRAL_BASE_URL + "registry/packages/" + encode(qualified.org())
                + "/" + encode(qualified.name()) + "/" + encode(version.text());
        Result<JsonElement> response = fetchJson(url, options);
        if (!response.isOk()) {
            return Optional.empty();
        }
        return Coordinates.balaUrl(response.value())
                .flatMap(balaUrl -> options.transport().openStream(balaUrl, options.timeoutMs()))
                .flatMap(archive -> Bala.moduleSources(archive, moduleId));
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }
}
