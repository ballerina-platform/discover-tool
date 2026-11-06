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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * Failures are values, not thrown classes.
 *
 * <p>The question every failure has to answer is "what does the agent reading this do next". The exit code
 * ({@link #exitCode()}) answers it coarsely, from the failure's core kind (usage or runtime); {@link #kind()} and
 * {@link #describe()} carry the rest. A sealed hierarchy makes every switch exhaustive with no {@code default}, so a
 * new failure mode fails the build until someone names it and decides what it tells the caller.
 *
 * @since 0.1.0
 */
public sealed interface Failure {

    /**
     * One place a payload stopped matching the schema.
     *
     * @param path where in the payload the mismatch was found
     * @param message what was expected and what was found instead
     */
    record SchemaIssue(String path, String message) { }

    /**
     * The caller's arguments are wrong.
     *
     * @param message what was wrong with the arguments
     * @param suggestion what to do instead
     * @param command the caller's command with the fix applied, ready to run, or {@code null} when there is no one
     *     fix
     * @param qualified the {@code org/name} the arguments were for, or {@code null} when no package was reached
     * @param version the version that was read, or {@code null} when none was reached
     * @param owners when the command names several containers and must name one, each of them with the command that
     *     names it — there is then no single command to run, so {@code command} is {@code null}
     */
    record Validation(String message, String suggestion, String command, String qualified, String version,
            List<Match> owners) implements Failure {

        public Validation(String message, String suggestion, String command) {
            this(message, suggestion, command, null, null, List.of());
        }

        /** Several commands, one per container, and the caller must pick one. */
        public Validation(String message, String suggestion, List<Match> owners) {
            this(message, suggestion, null, null, null, owners);
        }

        public Validation(String message, String suggestion) {
            this(message, suggestion, (String) null);
        }

        public Validation about(String forPackage, String atVersion) {
            return new Validation(message, suggestion, command, forPackage, atVersion, owners);
        }
    }

    /**
     * A page past the end of a paged listing: the request is well formed, and the listing has fewer pages.
     *
     * @param message which page was asked for and how many there are
     * @param suggestion what to run instead
     * @param command the last page's command, ready to run
     * @param page the page that was asked for
     * @param pages how many pages the listing has
     * @param qualified the {@code org/name} the listing is of, or {@code null} for a search across Central
     * @param version the version that was read, or {@code null}
     */
    record PageNotFound(String message, String suggestion, String command, int page, int pages, String qualified,
            String version) implements Failure {

        public PageNotFound(String message, String suggestion, String command, int page, int pages) {
            this(message, suggestion, command, page, pages, null, null);
        }

        public PageNotFound about(String forPackage, String atVersion) {
            return new PageNotFound(message, suggestion, command, page, pages, forPackage, atVersion);
        }
    }

    /**
     * Central has no such package, no such version of it, or no such module at that version.
     *
     * @param qualified the {@code org/name} that was not found, or whose module was not
     * @param version the version that was looked for, or {@code null} when none was reached
     * @param module the {@code --module} that was looked for, or {@code null} for the package's default module
     * @param suggestion what to try instead
     * @param command the caller's command with the fix applied, ready to run, or {@code null} when there is no one
     *     fix
     * @param candidates the submodules the package does publish, when a {@code --module} was not found
     */
    record PackageNotFound(String qualified, String version, String module, String suggestion, Command command,
            List<String> candidates) implements Failure {

        public PackageNotFound(String qualified, String version, String module, String suggestion, Command command) {
            this(qualified, version, module, suggestion, command, List.of());
        }

        public PackageNotFound(String qualified, String version, String module, String suggestion) {
            this(qualified, version, module, suggestion, null);
        }
    }

    /**
     * A request to Central failed: Central answered with an error status or a body that is not JSON, or no answer
     * from Central came back at all — the connection, DNS, TLS or the proxy failed.
     *
     * @param url the request that failed
     * @param attempts how many attempts were made before giving up
     * @param message what went wrong, as a sentence without its full stop
     * @param suggestion what to try next
     * @param status the HTTP status the request failed with — Central's when {@code reached}, else the proxy's —
     *     or {@code null} when no status line came back
     * @param reached whether Central answered
     * @param qualified the {@code org/name} the request was for, or {@code null} when not yet known
     * @param version the version it was for, or {@code null} when none was reached
     */
    record Upstream(String url, int attempts, String message, String suggestion, Integer status, boolean reached,
            String qualified, String version) implements Failure {

        public Upstream(String url, int attempts, String message, String suggestion, Integer status,
                boolean reached) {
            this(url, attempts, message, suggestion, status, reached, null, null);
        }

        public Upstream about(String forPackage, String atVersion) {
            return new Upstream(url, attempts, message, suggestion, status, reached, forPackage, atVersion);
        }

        public Upstream suggesting(String replacement) {
            return new Upstream(url, attempts, message, replacement, status, reached, qualified, version);
        }

        /** Whether the same request may succeed later: no answer came back, or the answer was a passing one. */
        public boolean isTransient() {
            return status == null ? !reached : isTransientStatus(status, reached);
        }

        /**
         * @param fromCentral whether Central sent the status, rather than a proxy answering its {@code CONNECT}
         */
        public static boolean isTransientStatus(int status, boolean fromCentral) {
            return (fromCentral ? TRANSIENT_STATUSES : TRANSIENT_PROXY_STATUSES).contains(status);
        }
    }

    /**
     * Central did not answer inside the budget.
     *
     * @param url the request that timed out
     * @param budgetMs the budget it exceeded, in milliseconds
     * @param suggestion what to try next
     * @param qualified the {@code org/name} the request was for, or {@code null} when not yet known
     * @param version the version it was for, or {@code null} when none was reached
     */
    record Timeout(String url, long budgetMs, String suggestion, String qualified, String version)
            implements Failure {

        public Timeout(String url, long budgetMs, String suggestion) {
            this(url, budgetMs, suggestion, null, null);
        }

        public Timeout about(String forPackage, String atVersion) {
            return new Timeout(url, budgetMs, suggestion, forPackage, atVersion);
        }
    }

    /**
     * Central answered with a shape this tool does not understand.
     *
     * @param qualified the {@code org/name} whose payload drifted
     * @param version the version that payload was read at, or {@code null} when unknown
     * @param module the {@code --module} whose page drifted, or {@code null} for the package's default module
     * @param issues every place the payload stopped matching the schema
     * @param suggestion what to try next
     */
    record SchemaDrift(String qualified, String version, String module, List<SchemaIssue> issues,
            String suggestion) implements Failure { }

    /**
     * The package parsed, but nothing in it is named exactly as the caller typed, or it is not what the bucket asked
     * for holds.
     *
     * @param qualified the {@code org/name} that was searched
     * @param version the version that was searched, or {@code null} when unknown
     * @param module the {@code --module} that was searched, or {@code null} for the package's default module
     * @param requested the name (or names) the caller asked for
     * @param message what was not found, lowercase-first, with no closing period
     * @param suggestion what to try next
     * @param command the one command that fixes it, ready to run, or {@code null} when there is none
     * @param candidates every name at the level the lookup reached when there are few enough to list, or empty
     */
    record SymbolNotFound(String qualified, String version, String module, List<String> requested, String message,
            String suggestion, String command, List<String> candidates) implements Failure { }

    /**
     * What was named matches more than one thing, so there is no single command to run: each match comes with the
     * command that opens it.
     *
     * @param qualified the {@code org/name} that was searched
     * @param version the version that was searched
     * @param module the {@code --module} that was searched, or {@code null} for the package's default module
     * @param requested the name (or names) the caller asked for
     * @param message what matched more than once, as one sentence
     * @param suggestion what to do next
     * @param matchesKey the JSON field the matches are listed under: {@link #OWNERS} or {@link #SECTIONS}
     * @param matches each thing that was matched, with the command that opens it
     */
    record Ambiguous(String qualified, String version, String module, List<String> requested, String message,
            String suggestion, String matchesKey, List<Match> matches) implements Failure { }

    /**
     * One of several things a command could mean: a container that declares what was named, or a readme section.
     *
     * @param name the match's name
     * @param command the command that opens it
     */
    record Match(String name, String command) { }

    /** {@link Ambiguous#matchesKey()} for the containers that each declare what was named. */
    String OWNERS = "owners";

    /** {@link Ambiguous#matchesKey()} for the readme sections a title matches. */
    String SECTIONS = "sections";

    /** The exit code of an answer. */
    int ANSWERED = 0;

    /** Any failure but a usage error: a name, page, package, version or module not found, or Central failing. */
    int RUNTIME = 1;

    /** The command line is malformed, or what it names matches more than one thing. */
    int USAGE = 2;

    /** The core kind of {@link #USAGE}. */
    String USAGE_KIND = "usage";

    /** The core kind of {@link #RUNTIME}. */
    String RUNTIME_KIND = "runtime";

    /**
     * A defect in this tool: something in the pipeline threw.
     *
     * @param message what was thrown
     * @param suggestion what to do about it
     */
    record Internal(String message, String suggestion) implements Failure { }

    String INTERNAL_SUGGESTION = "This is a defect in bal discover, not in the arguments. Report it with the "
            + "command that produced it.";

    /** The headline of an {@link Internal} failure whose cause says nothing. */
    String INTERNAL_MESSAGE = "unexpected internal failure";

    /**
     * The route left when Central's payload itself is unreadable: a resolved version's {@code .bala} carries the
     * same signatures Central serves, but only if some build already pulled it. It forbids writing the call from a
     * remembered API — the failure this tool exists to prevent, and measurably what a blocked agent does otherwise.
     */
    String OFFLINE_FALLBACK = "read the resolved version's sources under "
            + "'~/.ballerina/repositories/central.ballerina.io/bala/<org>/<name>/', which exist if a "
            + "build already pulled the package — those are the signatures Central publishes. Never "
            + "fall back to a remembered signature.";

    /** How far a failure's suggestion and command sit under its headline. */
    String INDENT = "  ";

    /** How far a failure's listed details (candidates, matches, issues) sit under its suggestion. */
    String DETAIL_INDENT = "    ";

    /** What leads the command that fixes a failure, in text mode. */
    String TRY = "Try: ";

    /** The spaces between an owner's name column and its command. */
    int COLUMN_GAP = 2;

    String UPSTREAM_SUGGESTION = "Central returned an error; run the same command again later.";

    /** For an answer from Central that running the same command again will not change. */
    String UPSTREAM_REJECTED_SUGGESTION = "Running the same command again will not change Central's answer; "
            + OFFLINE_FALLBACK;

    /** Central's statuses that pass: throttling, and an overloaded or unreachable backend. */
    Set<Integer> TRANSIENT_STATUSES = Set.of(429, 500, 502, 503, 504);

    /** A proxy's answers to a {@code CONNECT} when it could not reach Central for now. */
    Set<Integer> TRANSIENT_PROXY_STATUSES = Set.of(502, 503, 504);

    /**
     * Addressed to a human on purpose: no argument the agent can change will make a payload this
     * tool cannot parse. The fallback still applies — a package Central mis-serves is intact on
     * disk — so reporting the drift and getting on with the work are not alternatives.
     */
    String SCHEMA_DRIFT_SUGGESTION = "Central's answer no longer matches what this tool reads, so no change "
            + "of arguments will help. Report the 'issues' paths, then " + OFFLINE_FALLBACK;

    /** The discriminator an agent branches on, and the JSON field of the same name. */
    default String kind() {
        return switch (this) {
            case Validation ignored -> USAGE_KIND;
            case PackageNotFound ignored -> "package-not-found";
            case PageNotFound ignored -> "page-not-found";
            case Upstream ignored -> "upstream";
            case Timeout ignored -> "timeout";
            case SchemaDrift ignored -> "schema-drift";
            case SymbolNotFound ignored -> "symbol-not-found";
            case Ambiguous ignored -> "ambiguous";
            case Internal ignored -> "internal";
        };
    }

    /**
     * The core kind {@link #kind()} falls under, which decides the exit code: {@value #USAGE_KIND} for a malformed
     * command line or a name that matches several things, {@value #RUNTIME_KIND} for everything else.
     */
    default String coreKind() {
        return this instanceof Validation || this instanceof Ambiguous ? USAGE_KIND : RUNTIME_KIND;
    }

    /** The process exit code, decided by {@link #coreKind()}. */
    default int exitCode() {
        return USAGE_KIND.equals(coreKind()) ? USAGE : RUNTIME;
    }

    String suggestion();

    /** What went wrong, the {@code message} of either output mode: lowercase-first, with no closing period. */
    default String headline() {
        return switch (this) {
            case Validation f -> f.message();
            case PageNotFound f -> f.message();
            case PackageNotFound f -> (f.module() == null ? "package" : "module") + " not found on Central: "
                    + coordinate(f.qualified(), f.version(), f.module());
            case Upstream f -> f.message() + (f.attempts() > 1 ? " (" + f.attempts() + " attempts)" : "");
            case Timeout f -> "Central did not answer " + f.url() + " within " + seconds(f.budgetMs());
            case SchemaDrift f -> "Central's answer for " + coordinate(f.qualified(), f.version(), f.module())
                    + " has a shape this tool does not understand; Central's docs format may have changed";
            case SymbolNotFound f -> f.message();
            case Ambiguous f -> f.message();
            case Internal f -> f.message().isEmpty() ? INTERNAL_MESSAGE : INTERNAL_MESSAGE + ": " + f.message();
        };
    }

    /** The command that fixes it, ready to run, or {@code null} when no one command does. */
    default String fixCommand() {
        return switch (this) {
            case Validation f -> f.command();
            case PageNotFound f -> f.command();
            case PackageNotFound f -> f.command() == null ? null : f.command().text();
            case SymbolNotFound f -> f.command();
            case Ambiguous ignored -> null;
            case Upstream ignored -> null;
            case Timeout ignored -> null;
            case SchemaDrift ignored -> null;
            case Internal ignored -> null;
        };
    }

    /**
     * The failure as text-mode stderr: {@code bal discover: error: <headline>}, the suggestion indented below it,
     * {@code Try: <command>} below that when one command fixes it, then any listed details; no final newline.
     */
    default String describeText() {
        return describeText(true);
    }

    /** {@link #describeText()}, leaving the suggestion out when {@code suggestion} is false. */
    default String describeText(boolean suggestion) {
        List<String> lines = new ArrayList<>();
        lines.add(Command.PROGRAM + ": error: " + headline());
        if (suggestion) {
            lines.add(INDENT + suggestion());
        }
        String command = fixCommand();
        if (command != null) {
            lines.add(INDENT + TRY + command);
        }
        if (this instanceof SchemaDrift f) {
            lines.add(INDENT + "issues:");
            f.issues().forEach(issue -> lines.add(DETAIL_INDENT + issue.path() + ": " + issue.message()));
        }
        List<String> candidates = switch (this) {
            case SymbolNotFound f -> f.candidates();
            case PackageNotFound f -> f.candidates();
            default -> List.of();
        };
        if (!candidates.isEmpty()) {
            lines.add(INDENT + "candidates:");
            candidates.forEach(candidate -> lines.add(DETAIL_INDENT + candidate));
        }
        List<Match> matches = switch (this) {
            case Ambiguous f -> f.matches();
            case Validation f -> f.owners();
            default -> List.of();
        };
        int width = matches.stream().mapToInt(match -> match.name().length()).max().orElse(0);
        matches.forEach(match -> lines.add(DETAIL_INDENT + match.name()
                + " ".repeat(width - match.name().length() + COLUMN_GAP) + match.command()));
        return String.join("\n", lines);
    }

    private static String seconds(long millis) {
        String amount = BigDecimal.valueOf(millis, 3).stripTrailingZeros().toPlainString();
        return amount + ("1".equals(amount) ? " second" : " seconds");
    }

    private static String coordinate(String qualified, String version, String module) {
        return (version == null ? qualified : qualified + ":" + version) + (module == null ? "" : ", module " + module);
    }

    default String describe(boolean json) {
        return json ? describe() : describeText();
    }

    /** The one line a failing run writes to stderr in JSON mode, with {@link #headline()} as its message. */
    default String describe() {
        JsonObject json = new JsonObject();
        json.addProperty("severity", "error");
        json.addProperty("kind", kind());
        json.addProperty("message", headline());
        switch (this) {
            case Validation f -> {
                if (f.qualified() != null) {
                    coordinates(json, f.qualified(), f.version(), null);
                }
                json.addProperty("suggestion", f.suggestion());
                command(json, f.command());
                matches(json, OWNERS, f.owners());
            }
            case PageNotFound f -> {
                if (f.qualified() != null) {
                    coordinates(json, f.qualified(), f.version(), null);
                }
                json.addProperty("suggestion", f.suggestion());
                command(json, f.command());
                json.addProperty("page", f.page());
                json.addProperty("pages", f.pages());
            }
            case PackageNotFound f -> {
                coordinates(json, f.qualified(), f.version(), f.module());
                json.addProperty("suggestion", f.suggestion());
                command(json, f.command() == null ? null : f.command().text());
                if (!f.candidates().isEmpty()) {
                    json.add("candidates", strings(f.candidates()));
                }
            }
            case Upstream f -> {
                if (f.qualified() != null) {
                    coordinates(json, f.qualified(), f.version(), null);
                }
                json.addProperty("url", f.url());
                json.addProperty("attempts", f.attempts());
                json.addProperty("suggestion", f.suggestion());
                if (f.status() != null) {
                    json.addProperty("status", f.status());
                }
                json.addProperty("reached", f.reached());
            }
            case Timeout f -> {
                if (f.qualified() != null) {
                    coordinates(json, f.qualified(), f.version(), null);
                }
                json.addProperty("url", f.url());
                json.addProperty("budgetMs", f.budgetMs());
                json.addProperty("suggestion", f.suggestion());
            }
            case SchemaDrift f -> {
                coordinates(json, f.qualified(), f.version(), f.module());
                JsonArray issues = new JsonArray();
                for (SchemaIssue issue : f.issues()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("path", issue.path());
                    entry.addProperty("message", issue.message());
                    issues.add(entry);
                }
                json.add("issues", issues);
                json.addProperty("suggestion", f.suggestion());
            }
            case SymbolNotFound f -> {
                coordinates(json, f.qualified(), f.version(), f.module());
                json.add("requested", strings(f.requested()));
                json.addProperty("suggestion", f.suggestion());
                command(json, f.command());
                if (!f.candidates().isEmpty()) {
                    json.add("candidates", strings(f.candidates()));
                }
            }
            case Ambiguous f -> {
                coordinates(json, f.qualified(), f.version(), f.module());
                json.add("requested", strings(f.requested()));
                json.addProperty("suggestion", f.suggestion());
                matches(json, f.matchesKey(), f.matches());
            }
            case Internal f -> json.addProperty("suggestion", f.suggestion());
        }
        return json.toString();
    }

    private static void matches(JsonObject json, String key, List<Match> matches) {
        if (matches.isEmpty()) {
            return;
        }
        JsonArray rows = new JsonArray();
        matches.forEach(match -> {
            JsonObject row = new JsonObject();
            row.addProperty("name", match.name());
            row.addProperty("command", match.command());
            rows.add(row);
        });
        json.add(key, rows);
    }

    private static void command(JsonObject json, String command) {
        if (command != null) {
            json.addProperty("command", command);
        }
    }

    /**
     * This failure with its {@code command}, and the suggestion's quote of it, asked of the same package by
     * {@code rewrite}: a layer below the CLI knows only the package a corrected command reaches, not the rest of
     * the caller's command.
     */
    default Failure retargeted(UnaryOperator<Command> rewrite) {
        if (this instanceof PackageNotFound f && f.command() != null) {
            Command fixed = rewrite.apply(f.command());
            return new PackageNotFound(f.qualified(), f.version(), f.module(), f.suggestion(), fixed,
                    f.candidates());
        }
        return this;
    }

    /**
     * What {@code failure} says went wrong: its innermost non-blank message, cut to its first line, without a
     * closing period; empty when no cause says anything. Never a class name.
     */
    static String causeText(Throwable failure) {
        String message = "";
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                message = cause.getMessage();
            }
        }
        String line = message.strip().lines().findFirst().orElse("").strip();
        return line.endsWith(".") ? line.substring(0, line.length() - 1) : line;
    }

    /** A command quoted inside a suggestion's prose, when it is not the one {@code command} carries. */
    static String quoted(String command) {
        return "'" + command + "'";
    }

    private static void coordinates(JsonObject json, String qualified, String version, String module) {
        json.addProperty("qualified", qualified);
        if (version != null) {
            json.addProperty("version", version);
        }
        if (module != null) {
            json.addProperty("module", module);
        }
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }
}
