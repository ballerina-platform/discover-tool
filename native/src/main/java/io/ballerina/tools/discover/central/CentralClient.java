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

import java.net.URI;
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

    private static final String REGISTRY_PACKAGES_URL = CENTRAL_BASE_URL + "registry/packages/";
    private static final String DOCS_URL = CENTRAL_BASE_URL + "docs/";

    private static final int BAD_REQUEST = 400;
    private static final int NOT_FOUND = 404;
    private static final int PROXY_AUTHENTICATION_REQUIRED = 407;
    private static final int TOO_MANY_REQUESTS = 429;
    private static final int BAD_GATEWAY = 502;
    private static final int SERVICE_UNAVAILABLE = 503;
    private static final int GATEWAY_TIMEOUT = 504;

    private static final long NO_RETRY_AFTER = -1;

    /**
     * How long Central's answer to "what is the latest version" is believed.
     *
     * <p>Ten minutes spans an agent's whole lookup session (measured at 70 to 260 seconds) without a second
     * registry round trip, while a package published mid-run is still picked up. It is the one mutable response
     * this reader caches; a docs payload for a named version is immutable and never expires.
     */
    public static final long LATEST_TTL_MS = 600_000;

    private CentralClient() {
    }

    /**
     * A version, and what a caller may need to know about where it came from.
     *
     * @param version the resolved version
     * @param stale the registry was unreachable and this came off disk unverified
     * @param supplied the CALLER chose this version — in the coordinate or {@code Dependencies.toml} — so a
     *     later 404 from the docs endpoint is theirs to correct, not the reader's
     * @param pinned the version was written in the coordinate itself, so a command printed for the caller must
     *     repeat it
     */
    public record ResolvedVersion(Version version, boolean stale, boolean supplied, boolean pinned) {

        public ResolvedVersion(Version version, boolean stale) {
            this(version, stale, false, false);
        }
    }

    private static boolean isRetryableStatus(int status) {
        return status == TOO_MANY_REQUESTS || isGatewayStatus(status);
    }

    private static boolean isGatewayStatus(int status) {
        return status == BAD_GATEWAY || status == SERVICE_UNAVAILABLE || status == GATEWAY_TIMEOUT;
    }

    // A name that does not resolve, a certificate that does not verify or a password the proxy rejected is the
    // same on the next attempt, and retrying a rejected password counts against the account.
    private static boolean isRetryable(HttpTransport.Reply.Failed failed) {
        return switch (failed.problem()) {
            case UNCONNECTED, OTHER -> true;
            case TUNNEL -> failed.status() != null && isGatewayStatus(failed.status());
            case UNRESOLVED, TLS, PROXY_REJECTED, BAD_URL -> false;
        };
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
     * What one attempt produced, before the attempt count is known.
     *
     * <p>Separate from {@link Failure} on purpose: the retry loop branches on {@code retryable} and
     * {@code retryAfterMs}, which a finished {@code Failure} has no field for and no caller should see.
     */
    private sealed interface Outcome {

        record Body(JsonElement value) implements Outcome { }

        record Broken(Failure failure) implements Outcome { }

        record TimedOut() implements Outcome { }

        record Spent(Report report, boolean retryable, long retryAfterMs) implements Outcome {

            static Spent of(Report report, boolean retryable) {
                return new Spent(report, retryable, NO_RETRY_AFTER);
            }
        }
    }

    /**
     * What a failed attempt tells the caller.
     *
     * @param message what went wrong, as a sentence without its full stop
     * @param status the HTTP status the attempt failed with, or {@code null}
     * @param reached whether Central answered
     * @param suggestion what to do next
     */
    private record Report(String message, Integer status, boolean reached, String suggestion) {

        static Report unanswered(String message, String suggestion) {
            return new Report(message, null, false, suggestion);
        }
    }

    private static Outcome attemptFetch(String url, HttpOptions options) {
        HttpTransport.Reply reply = options.transport().get(url, options.timeoutMs());
        return switch (reply) {
            case HttpTransport.Reply.TimedOut ignored -> new Outcome.TimedOut();
            case HttpTransport.Reply.Failed failed -> failed.problem() == HttpTransport.Reply.Problem.BAD_URL
                    ? new Outcome.Broken(new Failure.Internal(
                            "Could not make a request to " + url + ": " + failed.message(),
                            Failure.INTERNAL_SUGGESTION))
                    : Outcome.Spent.of(noAnswer(failed, url, options), isRetryable(failed));
            case HttpTransport.Reply.Answered answered -> answer(answered, url, options);
        };
    }

    private static Outcome answer(HttpTransport.Reply.Answered answered, String url, HttpOptions options) {
        if (answered.status() == PROXY_AUTHENTICATION_REQUIRED) {
            return Outcome.Spent.of(proxyAuthentication(options), false);
        }
        if (!answered.isOk()) {
            boolean retryable = isRetryableStatus(answered.status());
            long retryAfterMs = retryable ? parseRetryAfter(answered.retryAfter(), options.now()) : NO_RETRY_AFTER;
            return new Outcome.Spent(new Report("Central answered " + url + " with HTTP " + answered.status(),
                    answered.status(), true, Failure.UPSTREAM_SUGGESTION), retryable, retryAfterMs);
        }
        try {
            JsonElement parsed = JsonParser.parseString(answered.body());
            return new Outcome.Body(parsed == null ? JsonNull.INSTANCE : parsed);
        } catch (RuntimeException malformed) {
            // Upstream serving something that is not JSON is not a transient condition.
            return Outcome.Spent.of(new Report("Central answered " + url + " with a body that is not JSON: "
                    + malformed.getMessage(), null, true, Failure.UPSTREAM_SUGGESTION), false);
        }
    }

    // Central is HTTPS, reached through a CONNECT tunnel when there is a proxy: a status inside the tunnel is
    // Central's own, so only the CONNECT's answer — a 407, or the tunnel failing — is the proxy's.
    private static Report noAnswer(HttpTransport.Reply.Failed failed, String url, HttpOptions options) {
        String host = host(url);
        String file = options.settingsFile();
        ProxySettings proxy = options.proxy();
        String through = proxy == null ? "" : " through the proxy " + address(proxy);
        return switch (failed.problem()) {
            case UNCONNECTED -> proxy == null
                    ? Report.unanswered("Could not reach " + host + ": " + failed.message(), network(file))
                    : Report.unanswered("Could not connect to the proxy " + address(proxy) + " set in " + file + ": "
                            + failed.message(), "Check that the proxy is running and that host and port under "
                            + "[proxy] in " + file + " are right, then run the same command again.");
            case UNRESOLVED -> proxy == null
                    ? Report.unanswered("Could not resolve the host " + host, network(file))
                    : Report.unanswered("Could not resolve the proxy host " + proxy.host(),
                            "Check host under [proxy] in " + file + ", then run the same command again.");
            case TLS -> Report.unanswered("Could not open a secure connection to " + host + through + ": "
                    + failed.message(), "Check the system clock, and any proxy or firewall that intercepts HTTPS, "
                    + "then run the same command again.");
            case TUNNEL -> new Report((proxy == null ? "The proxy" : "The proxy " + address(proxy) + " set in "
                    + file) + " could not connect to " + host + ": HTTP " + failed.status(), failed.status(), false,
                    "The proxy answered but could not reach " + host + "; run the same command again later, or "
                            + "check that the proxy allows it.");
            case PROXY_REJECTED -> rejected(options);
            case OTHER -> Report.unanswered("Could not reach " + host + through + ": " + failed.message(),
                    network(file));
            case BAD_URL -> throw new IllegalStateException("a malformed URL is not a network failure");
        };
    }

    private static Report proxyAuthentication(HttpOptions options) {
        ProxySettings proxy = options.proxy();
        if (proxy != null && proxy.authenticates()) {
            return rejected(options);
        }
        return new Report((proxy == null ? "A proxy" : "The proxy " + address(proxy))
                + " requires authentication", PROXY_AUTHENTICATION_REQUIRED, false,
                "Set both username and password under [proxy] in " + options.settingsFile()
                        + ", then run the same command again.");
    }

    private static Report rejected(HttpOptions options) {
        ProxySettings proxy = options.proxy();
        String file = options.settingsFile();
        return new Report((proxy == null ? "The proxy" : "The proxy " + address(proxy))
                + " rejected the username and password in the [proxy] table of " + file,
                PROXY_AUTHENTICATION_REQUIRED, false,
                "Correct username and password under [proxy] in " + file + ", then run the same command again.");
    }

    private static String network(String settingsFile) {
        return "Check your network connection, and the [proxy] table in " + settingsFile
                + " if your network needs a proxy, then run the same command again.";
    }

    private static String address(ProxySettings proxy) {
        return proxy.host() + ":" + proxy.port();
    }

    private static String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (IllegalArgumentException malformed) {
            return url;
        }
    }

    /**
     * GET a JSON document, retrying the failures that are worth retrying and stopping at a wall-clock budget.
     *
     * <p>Retries live here and nowhere else: Central is a remote service that can 429 or 5xx for reasons that
     * pass, whereas everything else this package does is local and deterministic.
     */
    public static Result<JsonElement> fetchJson(String url, HttpOptions options) {
        long deadline = options.now() + options.budgetMs();
        Outcome last = null;
        int made = 0;

        for (int attempt = 0; attempt < options.maxAttempts(); attempt++) {
            if (attempt > 0) {
                long remaining = deadline - options.now();
                if (remaining <= 0) {
                    break;
                }
                long wait = last instanceof Outcome.Spent spent && spent.retryAfterMs() >= 0
                        ? spent.retryAfterMs()
                        : backoffMs(attempt - 1, options.baseDelayMs(), options.jitter());
                options.sleep(Math.min(remaining, wait));
            }
            made++;
            last = attemptFetch(url, options);
            switch (last) {
                case Outcome.Body body -> {
                    return Result.ok(body.value());
                }
                case Outcome.Broken broken -> {
                    return Result.err(broken.failure());
                }
                case Outcome.Spent spent when !spent.retryable() -> {
                    return Result.err(toFailure(spent, url, made, options));
                }
                case Outcome.Spent ignored -> {
                }
                case Outcome.TimedOut ignored -> {
                }
            }
        }

        if (last == null) {
            return Result.err(new Failure.Internal("No request was made to " + url, Failure.INTERNAL_SUGGESTION));
        }
        return Result.err(toFailure(last, url, made, options));
    }

    private static Failure toFailure(Outcome last, String url, int attempts, HttpOptions options) {
        if (last instanceof Outcome.Spent spent) {
            Report report = spent.report();
            return new Failure.Upstream(url, attempts, report.message(), report.suggestion(), report.status(),
                    report.reached());
        }
        return new Failure.Timeout(url, options.budgetMs(), "A large package is slow on a cold fetch, so run the "
                + "same command again; if it keeps timing out, check your network connection and the [proxy] "
                + "table in " + options.settingsFile() + ".");
    }

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
                qualified.qualified(), pin, null,
                "Central publishes no package under this name, and none of the packages it could be a module "
                        + "of exists either (tried " + tried + "). Check the org/name spelling; "
                        + "`bal search <keyword>` lists what Central publishes.");
    }

    private static Failure moduleOf(
            QualifiedName qualified, QualifiedName parent, Version version, String pin, HttpOptions options) {
        String submodule = qualified.name().substring(parent.name().length() + 1);
        String command = "`bal discover " + Texts.shellWord(pinned(parent, pin)) + " --module "
                + Texts.shellWord(submodule) + "`";
        String url = REGISTRY_PACKAGES_URL + encode(parent.org()) + "/" + encode(parent.name())
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
        return new Failure.PackageNotFound(qualified.qualified(), pin, null, suggestion);
    }

    // Asks for this package's row: listing the whole org (ballerinax) and filtering costs about 45 seconds.
    private static Result<ResolvedVersion> resolvePublishedVersion(QualifiedName qualified, HttpOptions options) {
        DocsCache cache = options.cache();
        DocsCache.PackageKey key = new DocsCache.PackageKey(REPOSITORY_ID, qualified.org(), qualified.name());

        // `--refresh` re-resolves unconditionally: making it conditional on the version having changed would make
        // it a no-op in exactly the case its own error message recommends it for.
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

        String url = REGISTRY_PACKAGES_URL + encode(qualified.org())
                + "/" + encode(qualified.name());
        Result<JsonElement> response = fetchJson(url, options);
        if (!response.isOk()) {
            // Central answers an unpublished org/name with a 400, not a 404. Either way the fact is "no such
            // package", and reporting it as a transport error would send the caller looking at the network for
            // a typo.
            Integer status = response.failure() instanceof Failure.Upstream upstream ? upstream.status() : null;
            if (status != null && (status == BAD_REQUEST || status == NOT_FOUND)) {
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

    private static Failure notFound(QualifiedName qualified) {
        return new Failure.PackageNotFound(
                qualified.qualified(), null, null,
                "Check the org/name spelling; `bal search <keyword>` lists what Central publishes.");
    }

    /**
     * The API docs for one published version, from disk when they are already there.
     *
     * <p>The cache sits above the retry loop, so a hit costs no attempt, and below the schema, so what gets stored
     * is Central's payload, not something derived from our own code.
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

        if (!options.refresh()) {
            JsonElement cached = cache.readDocs(key);
            if (cached != null) {
                Result<CentralDocs> parsed = Coordinates.match(cached, qualified, version)
                        ? Schema.parse(cached, qualified.qualified(), version.text())
                        : null;
                if (parsed != null && parsed.isOk()) {
                    return parsed;
                }
                cache.removeDocs(key);
            }
        }

        String url = DOCS_URL + encode(qualified.org())
                + "/" + encode(qualified.name()) + "/" + encode(version.text());
        Result<JsonElement> response = fetchJson(url, options);
        if (!response.isOk()) {
            // 404 here is specific: the org/name may well exist, this VERSION does not — and which half the
            // caller can act on depends on who chose the version. A version they passed is theirs to correct.
            // One the reader resolved is not: telling them to "omit the version" names what they already did.
            if (response.failure() instanceof Failure.Upstream upstream
                    && upstream.status() != null && upstream.status() == NOT_FOUND) {
                if (resolved.supplied() && isUnpublishedDottedName(qualified, options)) {
                    return Result.err(notAPackage(qualified, pinOf(resolved), options));
                }
                return Result.err(new Failure.PackageNotFound(qualified.qualified(), version.text(), null,
                        missingVersion(qualified, resolved, options)));
            }
            return response.cast();
        }
        // A version for a module path can still arrive from a `latest` entry an older build of this reader wrote
        // under the module's own key; the page itself says what it is.
        if (Coordinates.describesSubmodule(response.value(), qualified)) {
            return Result.err(notAPackage(qualified, pinOf(resolved), options));
        }
        Result<CentralDocs> parsed = Schema.parse(response.value(), qualified.qualified(), version.text());
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

        if (!options.refresh()) {
            JsonElement cached = cache.readModuleDocs(key);
            if (cached != null) {
                Result<CentralDocs> parsed = Coordinates.isModulePage(cached, qualified, submodule, version)
                        ? Schema.parse(cached, qualified.qualified(), version.text())
                        : null;
                if (parsed != null && parsed.isOk()) {
                    return parsed;
                }
                cache.removeModuleDocs(key);
            }
        }

        String url = DOCS_URL + encode(qualified.org())
                + "/" + encode(moduleName) + "/" + encode(version.text());
        Result<JsonElement> response = fetchJson(url, options);
        if (!response.isOk()) {
            if (response.failure() instanceof Failure.Upstream upstream
                    && upstream.status() != null && upstream.status() == NOT_FOUND) {
                return Result.err(noSuchModulePage(version, qualified, pinOf(resolved), submodule));
            }
            return response.cast();
        }
        if (!Coordinates.isModulePage(response.value(), qualified, submodule, version)) {
            return Result.err(noSuchModulePage(version, qualified, pinOf(resolved), submodule));
        }
        Result<CentralDocs> parsed = Schema.parse(response.value(), qualified.qualified(), version.text());
        if (!parsed.isOk()) {
            return parsed.failure() instanceof Failure.SchemaDrift drift
                    ? Result.err(new Failure.SchemaDrift(
                            drift.qualified(), drift.version(), submodule, drift.issues(), drift.suggestion()))
                    : parsed.cast();
        }
        cache.writeModuleDocs(key, response.value());
        return parsed;
    }

    private static String pinOf(ResolvedVersion resolved) {
        return resolved.pinned() ? resolved.version().text() : null;
    }

    private static String pinned(QualifiedName qualified, String pin) {
        return qualified.qualified() + (pin == null ? "" : ":" + pin);
    }

    private static Failure noSuchModulePage(Version version, QualifiedName qualified, String pin, String submodule) {
        return new Failure.PackageNotFound(qualified.qualified(), version.text(), submodule, qualified.qualified()
                + " publishes no '" + submodule + "' module at this version. Run `bal discover "
                + Texts.shellWord(pinned(qualified, pin)) + "` to list the submodules it does publish.");
    }

    // A name Central has no row for, with a package it could be a module of.
    private static boolean isUnpublishedDottedName(QualifiedName qualified, HttpOptions options) {
        if (containingPackages(qualified).isEmpty()) {
            return false;
        }
        Result<ResolvedVersion> published = resolvePublishedVersion(qualified, options);
        return !published.isOk() && published.failure() instanceof Failure.PackageNotFound;
    }

    private static String missingVersion(
            QualifiedName qualified, ResolvedVersion resolved, HttpOptions options) {
        Version version = resolved.version();
        if (!resolved.supplied()) {
            return "Central published no '" + qualified.qualified() + "' at " + version.text()
                    + ", the version resolved for it. Check the name — `bal search <keyword>` lists what Central "
                    + "publishes.";
        }
        String published = publishedVersions(qualified, options);
        String listed = published == null ? "" : "; published versions are " + published;
        String coordinate = qualified.qualified() + ":<version>";
        if (resolved.pinned()) {
            return "Central does not publish '" + qualified.qualified() + "' at " + version.text() + listed
                    + (published == null ? ". Write a published version" : ". Write one of them")
                    + " after the package: " + coordinate;
        }
        return "Central does not publish '" + qualified.qualified() + "' at " + version.text()
                + ", the version your project's Dependencies.toml locks" + listed
                + ". Reconcile Dependencies.toml with the registry so a lookup and a build see the same one, or "
                + "write a published version after the package: " + coordinate;
    }

    private static final int LISTED_VERSIONS = 10;

    private static String publishedVersions(QualifiedName qualified, HttpOptions options) {
        String url = REGISTRY_PACKAGES_URL + encode(qualified.org())
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
        String url = REGISTRY_PACKAGES_URL + encode(qualified.org())
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
