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
     * The caller's arguments are wrong — nothing upstream was contacted.
     *
     * @param message what was wrong with the arguments
     * @param suggestion the corrected command to run instead
     */
    record Validation(String message, String suggestion) implements Failure { }

    /**
     * Central has no such package, no such version of it, or no such module at that version.
     *
     * @param qualified the {@code org/name} that was not found, or whose module was not
     * @param version the version that was looked for, or {@code null} when none was reached
     * @param module the {@code --module} that was looked for, or {@code null} for the package's default module
     * @param suggestion what to try instead
     */
    record PackageNotFound(String qualified, String version, String module, String suggestion) implements Failure { }

    /**
     * Central answered, but not usefully — a 4xx/5xx, a network error, a bad body. {@code status} is
     * {@code null} when the failure happened before a status line existed.
     *
     * @param url the request that failed
     * @param attempts how many times it was retried before giving up
     * @param message what Central's answer, or the transport, said
     * @param suggestion what to try next
     * @param status the HTTP status Central answered with, or {@code null}
     */
    record Upstream(String url, int attempts, String message, String suggestion, Integer status)
            implements Failure { }

    /**
     * Central did not answer inside the budget.
     *
     * @param url the request that timed out
     * @param budgetMs the budget it exceeded, in milliseconds
     * @param suggestion what to try next
     */
    record Timeout(String url, long budgetMs, String suggestion) implements Failure { }

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

    /** No answer came back at all: the connection, the proxy, DNS or TLS failed. */
    String NETWORK_SUGGESTION = "Check your network connection and the [proxy] settings in "
            + "~/.ballerina/Settings.toml, then run the same command again.";

    /** Central answered, with an error status or a body that is not JSON. */
    String UPSTREAM_SUGGESTION = "Central returned an error; run the same command again later.";

    String TIMEOUT_SUGGESTION = "A large package is slow on a cold fetch, so run the same command again; if it "
            + "keeps timing out, check your network connection and the [proxy] settings in "
            + "~/.ballerina/Settings.toml.";

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

    /** What to do next — every kind carries one. */
    String suggestion();

    /**
     * The failure as a text-mode run writes it to stderr: {@code error: <headline>}, a full sentence, then the
     * suggestion and anything else the failure carries on lines of their own, indented, with no trailing newline.
     */
    default String describeText() {
        List<String> lines = new ArrayList<>();
        lines.add("error: " + switch (this) {
            case Validation f -> f.message();
            case PackageNotFound f -> (f.module() == null ? "Package" : "Module") + " not found on Central: "
                    + coordinate(f.qualified(), f.version(), f.module()) + ".";
            case Upstream f -> "Request to " + f.url() + " failed after " + f.attempts()
                    + (f.attempts() == 1 ? " attempt" : " attempts")
                    + (f.message() == null ? "." : ": " + f.message() + ".");
            case Timeout f -> "Central did not answer " + f.url() + " within " + seconds(f.budgetMs()) + ".";
            case SchemaDrift f -> "Central's answer for " + coordinate(f.qualified(), f.version(), f.module())
                    + " has a shape this tool does not understand; Central's docs format may have changed.";
            case SymbolNotFound f -> f.module() != null && f.requested().equals(List.of(f.module()))
                    ? "No module '" + f.module() + "' in " + coordinate(f.qualified(), f.version(), null) + "."
                    : "No match for " + f.requested().stream().map(name -> "'" + name + "'")
                            .collect(Collectors.joining(" ")) + " in "
                            + coordinate(f.qualified(), f.version(), f.module()) + ".";
            case Internal f -> "Unexpected internal failure: " + f.message() + (f.message().endsWith(".") ? "" : ".");
        });
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

    /** The one line a failing run writes to stderr in JSON mode: a single JSON object. */
    default String describe() {
        JsonObject json = new JsonObject();
        json.addProperty("kind", kind());
        switch (this) {
            case Validation f -> {
                json.addProperty("message", f.message());
                json.addProperty("suggestion", f.suggestion());
            }
            case PackageNotFound f -> {
                coordinates(json, f.qualified(), f.version(), f.module());
                json.addProperty("suggestion", f.suggestion());
            }
            case Upstream f -> {
                json.addProperty("url", f.url());
                json.addProperty("attempts", f.attempts());
                json.addProperty("message", f.message());
                json.addProperty("suggestion", f.suggestion());
                if (f.status() != null) {
                    json.addProperty("status", f.status());
                }
            }
            case Timeout f -> {
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
            case Internal f -> {
                json.addProperty("message", f.message());
                json.addProperty("suggestion", f.suggestion());
            }
        }
        return json.toString();
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
