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
import java.util.stream.Collectors;

/**
 * Failures are values, not thrown classes.
 *
 * <p>The question every failure has to answer is "what does the agent reading this do next". The
 * answer is carried by {@link #kind()} and {@link #describe()}, not by the exit code, which only tells a
 * malformed command line (2) from every other failure (1), so the discriminator has to be in the JSON. A sealed
 * hierarchy makes both switches exhaustive with no {@code default}, so a new failure mode fails the
 * build until someone names it and decides what it tells the caller.
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
     *     fix; the suggestion quotes it
     */
    record Validation(String message, String suggestion, String command) implements Failure {

        public Validation(String message, String suggestion) {
            this(message, suggestion, null);
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
     *     fix; the suggestion quotes it
     */
    record PackageNotFound(String qualified, String version, String module, String suggestion, String command)
            implements Failure {

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
     * The package parsed, but no declaration matched the name the caller asked for. {@code
     * candidates} is what the index does hold — either near-misses of the requested name, or the
     * whole roster when there were none.
     *
     * @param qualified the {@code org/name} that was searched
     * @param version the version that was searched, or {@code null} when unknown
     * @param module the {@code --module} that was searched, or {@code null} for the package's default module
     * @param requested the name (or names) the caller asked for
     * @param candidates the near-misses, or the whole roster when there were none
     * @param suggestion what to try next
     */
    record SymbolNotFound(String qualified, String version, String module, List<String> requested,
            List<String> candidates, String suggestion) implements Failure { }

    /**
     * A defect in this tool: something in the pipeline threw, which nothing is designed to do.
     *
     * @param message what was thrown
     * @param suggestion what to do about it
     */
    record Internal(String message, String suggestion) implements Failure { }

    String INTERNAL_SUGGESTION = "This is a defect in bal discover, not in the arguments. Report it with the "
            + "command that produced it.";

    /**
     * The route left when Central's payload itself is unreadable: a resolved version's {@code .bala} carries the
     * same signatures Central serves, but only if some build already pulled it. It forbids writing the call from a
     * remembered API — the failure this tool exists to prevent, and measurably what a blocked agent does otherwise.
     */
    String OFFLINE_FALLBACK = "read the resolved version's sources under "
            + "`~/.ballerina/repositories/central.ballerina.io/bala/<org>/<name>/`, which exist if a "
            + "build already pulled the package — those are the signatures Central publishes. Never "
            + "fall back to a remembered signature.";

    String UPSTREAM_SUGGESTION = "Central returned an error; run the same command again later.";

    /**
     * Addressed to a human on purpose: no argument the agent can change will make a payload this
     * tool cannot parse. The fallback still applies — a package Central mis-serves is intact on
     * disk — so reporting the drift and getting on with the work are not alternatives.
     */
    String SCHEMA_DRIFT_SUGGESTION = "Central's answer no longer matches what this tool reads, so no change "
            + "of arguments will help. Report the `issues` paths, then " + OFFLINE_FALLBACK;

    /** The discriminator an agent branches on, and the JSON field of the same name. */
    default String kind() {
        return switch (this) {
            case Validation ignored -> "validation";
            case PackageNotFound ignored -> "package-not-found";
            case Upstream ignored -> "upstream";
            case Timeout ignored -> "timeout";
            case SchemaDrift ignored -> "schema-drift";
            case SymbolNotFound ignored -> "symbol-not-found";
            case Internal ignored -> "internal";
        };
    }

    String suggestion();

    /** What went wrong, as one full sentence: the {@code message} of either output mode. */
    default String headline() {
        return switch (this) {
            case Validation f -> f.message();
            case PackageNotFound f -> (f.module() == null ? "Package" : "Module") + " not found on Central: "
                    + coordinate(f.qualified(), f.version(), f.module()) + ".";
            case Upstream f -> f.message() + (f.attempts() > 1 ? " (" + f.attempts() + " attempts)." : ".");
            case Timeout f -> "Central did not answer " + f.url() + " within " + seconds(f.budgetMs()) + ".";
            case SchemaDrift f -> "Central's answer for " + coordinate(f.qualified(), f.version(), f.module())
                    + " has a shape this tool does not understand; Central's docs format may have changed.";
            case SymbolNotFound f -> "No match for " + f.requested().stream().map(name -> "'" + name + "'")
                    .collect(Collectors.joining(" ")) + " in " + coordinate(f.qualified(), f.version(), f.module())
                    + ".";
            case Internal f -> "Unexpected internal failure: " + f.message() + (f.message().endsWith(".") ? "" : ".");
        };
    }

    /**
     * The failure as a text-mode run writes it to stderr: {@code error: <headline>}, then the suggestion and anything
     * else the failure carries on lines of their own, indented, with no trailing newline.
     */
    default String describeText() {
        List<String> lines = new ArrayList<>();
        lines.add("error: " + headline());
        lines.add("  " + suggestion());
        if (this instanceof SchemaDrift f) {
            lines.add("  issues:");
            f.issues().forEach(issue -> lines.add("    " + issue.path() + ": " + issue.message()));
        }
        if (this instanceof SymbolNotFound f && !f.candidates().isEmpty()) {
            lines.add("  candidates:");
            f.candidates().forEach(candidate -> lines.add("    " + candidate));
        }
        return String.join("\n", lines);
    }

    private static String seconds(long millis) {
        String amount = millis % 1000 == 0 ? Long.toString(millis / 1000)
                : BigDecimal.valueOf(millis, 3).stripTrailingZeros().toPlainString();
        return amount + (millis == 1000 ? " second" : " seconds");
    }

    private static String coordinate(String qualified, String version, String module) {
        return (version == null ? qualified : qualified + ":" + version) + (module == null ? "" : ", module " + module);
    }

    /**
     * The one line a failing run writes to stderr in JSON mode: a single JSON object, whose {@code message} is the
     * {@link #headline()}.
     */
    default String describe() {
        JsonObject json = new JsonObject();
        json.addProperty("kind", kind());
        json.addProperty("message", headline());
        switch (this) {
            case Validation f -> {
                json.addProperty("suggestion", f.suggestion());
                command(json, f.command());
            }
            case PackageNotFound f -> {
                coordinates(json, f.qualified(), f.version(), f.module());
                json.addProperty("suggestion", f.suggestion());
                command(json, f.command());
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
                json.add("candidates", strings(f.candidates()));
                json.addProperty("suggestion", f.suggestion());
            }
            case Internal f -> json.addProperty("suggestion", f.suggestion());
        }
        return json.toString();
    }

    private static void command(JsonObject json, String command) {
        if (command != null) {
            json.addProperty("command", command);
        }
    }

    /**
     * This failure with its runnable command lengthened by {@code tail}, in {@code command} and where the suggestion
     * quotes it: what a layer that knew only part of the caller's command leaves for the CLI to finish.
     */
    default Failure lengthened(String tail) {
        return switch (this) {
            case Validation f when f.command() != null && !tail.isEmpty() -> new Validation(f.message(),
                    lengthened(f.suggestion(), f.command(), tail), f.command() + tail);
            case PackageNotFound f when f.command() != null && !tail.isEmpty() -> new PackageNotFound(f.qualified(),
                    f.version(), f.module(), lengthened(f.suggestion(), f.command(), tail), f.command() + tail);
            default -> this;
        };
    }

    private static String lengthened(String suggestion, String command, String tail) {
        return suggestion.replace(quoted(command), quoted(command + tail));
    }

    static String quoted(String command) {
        return "`" + command + "`";
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
