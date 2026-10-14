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

import io.ballerina.tools.discover.central.HttpOptions;
import io.ballerina.tools.discover.central.HttpTransport;
import io.ballerina.tools.discover.cli.Cli;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One exit code per action the caller takes next: every failure kind, and each cause that splits one, run end to
 * end in both output modes.
 */
public class ExitCodesTest {

    private static final String KAFKA = "ballerinax/kafka";

    private static final String KAFKA_VERSION = "4.6.5";

    private static final String VERSIONS = "[\"" + KAFKA_VERSION + "\"]";

    private record Case(String kind, int code, List<String> argv, HttpOptions http, String projectDir) {

        Case(String kind, int code, List<String> argv, HttpOptions http) {
            this(kind, code, argv, http, null);
        }
    }

    private static HttpOptions failing(HttpTransport.Reply reply) {
        return HttpOptions.builder()
                .transport(FakeTransport.always(reply))
                .maxAttempts(1).baseDelayMs(1).sleeper(millis -> { }).build();
    }

    private static HttpOptions docsAnswering(String body) {
        return CliTest.options(FakeTransport.routing(url -> url.contains("/docs/")
                ? FakeTransport.ok(body)
                : FakeTransport.ok(VERSIONS)));
    }

    private static HttpOptions registryAnswering(String body) {
        return failing(FakeTransport.ok(body));
    }

    private static HttpOptions parentUnreachable() {
        return CliTest.options(FakeTransport.routing(url -> url.endsWith("/" + KAFKA + ".sub")
                ? FakeTransport.status(404)
                : new HttpTransport.Reply.Failed(HttpTransport.Reply.Problem.UNCONNECTED, "Connection refused")));
    }

    private static String malformedLock() {
        Path project = CliTest.tempDir();
        CliTest.write(project.resolve("Ballerina.toml"), "[package]\norg = \"acme\"\nname = \"app\"\n");
        CliTest.write(project.resolve("Dependencies.toml"),
                "[[package]]\norg = \"ballerinax\"\nname = \"kafka\"\nversion = \"4.6\"\n");
        return project.toString();
    }

    private static HttpOptions failed(HttpTransport.Reply.Problem problem, Integer status) {
        return failing(new HttpTransport.Reply.Failed(problem, problem.name(), status));
    }

    @Test
    public void everyFailureKindAndCauseExitsWithTheCodeForWhatToDoNext() {
        HttpOptions kafka = CliTest.centralFor("ballerinax__kafka", KAFKA_VERSION);
        HttpOptions graphql = CliTest.options(CliTest.graphqlCentral());
        HttpOptions search = CliTest.options(CentralSearchTest.central());
        List<Case> cases = List.of(
                new Case(null, Failure.ANSWERED, List.of("package", KAFKA), kafka),
                new Case(null, Failure.ANSWERED, List.of("client", "-p", KAFKA, "--filter", "zzznope"), kafka),
                new Case(null, Failure.ANSWERED, List.of("search", "zzznope", "-p", KAFKA), kafka),
                new Case("package-not-found", Failure.NOT_FOUND, List.of("package", "no-such-org/no-such-pkg"),
                        failing(FakeTransport.status(404))),
                new Case("package-not-found", Failure.NOT_FOUND, List.of("package", KAFKA), registryAnswering("[]")),
                new Case("package-not-found", Failure.NOT_FOUND,
                        List.of("package", "ballerina/graphql", "--module", "nosuch"), graphql),
                new Case("symbol-not-found", Failure.NOT_FOUND,
                        List.of("method", "sendd", "-p", KAFKA, "--client", "Producer"), kafka),
                new Case("symbol-not-found", Failure.NOT_FOUND, List.of("client", "NoSuchContainer", "-p", KAFKA),
                        kafka),
                new Case("symbol-not-found", Failure.NOT_FOUND,
                        List.of("method", "send", "-p", KAFKA, "--class", "Producer"), kafka),
                new Case("symbol-not-found", Failure.NOT_FOUND,
                        List.of("readme", "-p", "ballerina/graphql", "--module", "dataloader"), graphql),
                new Case("validation", Failure.USAGE, List.of("nonsense"), CliTest.never()),
                new Case("validation", Failure.USAGE, List.of("package", "ballerina/http:2.16"), CliTest.never()),
                new Case("validation", Failure.USAGE, List.of("client", "Consumer"), CliTest.never()),
                new Case("validation", Failure.USAGE, List.of("client", "-p", KAFKA, "--nonesuch"), CliTest.never()),
                new Case("validation", Failure.USAGE, List.of("function", "a", "b", "-p", KAFKA), CliTest.never()),
                new Case("validation", Failure.USAGE,
                        List.of("method", "send", "-p", KAFKA, "--client", "A", "--class", "B"), CliTest.never()),
                new Case("validation", Failure.USAGE,
                        List.of("method", "poll", "x", "-p", KAFKA, "--client", "Consumer"), kafka),
                new Case("validation", Failure.USAGE, List.of("client", "-p", KAFKA, "--page", "2"), kafka),
                new Case("validation", Failure.USAGE, List.of("search", "kafka", "--page", "9"), search),
                new Case("validation", Failure.USAGE, List.of("readme", "1", "-p", KAFKA, "--filter", "zzznope"),
                        kafka),
                new Case("validation", Failure.USAGE, List.of("package", KAFKA), CliTest.never(), malformedLock()),
                new Case("ambiguous", Failure.AMBIGUOUS, List.of("method", "close", "-p", KAFKA), kafka),
                new Case("ambiguous", Failure.AMBIGUOUS, List.of("class", "close", "-p", KAFKA), kafka),
                new Case("ambiguous", Failure.AMBIGUOUS, List.of("readme", "Kafka", "-p", KAFKA), kafka),
                new Case("upstream", Failure.UNAVAILABLE, List.of("package", KAFKA),
                        failed(HttpTransport.Reply.Problem.UNCONNECTED, null)),
                new Case("upstream", Failure.UNAVAILABLE, List.of("package", KAFKA),
                        failed(HttpTransport.Reply.Problem.UNRESOLVED, null)),
                new Case("upstream", Failure.UNAVAILABLE, List.of("package", KAFKA + ".sub"), parentUnreachable()),
                new Case("timeout", Failure.UNAVAILABLE, List.of("package", KAFKA),
                        failing(new HttpTransport.Reply.TimedOut())),
                new Case("upstream", Failure.BAD_ANSWER, List.of("package", KAFKA), failing(FakeTransport.status(500))),
                new Case("upstream", Failure.BAD_ANSWER, List.of("package", KAFKA), failing(FakeTransport.status(407))),
                new Case("upstream", Failure.BAD_ANSWER, List.of("package", KAFKA),
                        failed(HttpTransport.Reply.Problem.PROXY_REJECTED, null)),
                new Case("upstream", Failure.BAD_ANSWER, List.of("package", KAFKA), docsAnswering("{\"unterminated")),
                new Case("upstream", Failure.BAD_ANSWER, List.of("package", KAFKA),
                        failed(HttpTransport.Reply.Problem.TUNNEL, 502)),
                new Case("schema-drift", Failure.BAD_ANSWER, List.of("package", KAFKA),
                        docsAnswering("{\"docsData\":{\"modules\":[]}}")),
                new Case("schema-drift", Failure.BAD_ANSWER, List.of("package", KAFKA),
                        registryAnswering("{\"versions\":[]}")),
                new Case("schema-drift", Failure.BAD_ANSWER, List.of("package", KAFKA),
                        registryAnswering("[\"latest\"]")));
        for (Case run : cases) {
            assertJson(run);
            assertText(run);
        }
    }

    private static void assertJson(Case run) {
        CliTest.Capture capture = new CliTest.Capture();
        String label = String.join(" ", run.argv());
        Assert.assertEquals(Cli.run(run.argv(), capture.streams(), run.http(), run.projectDir()), run.code(),
                label + " -> " + capture.stderr());
        if (run.kind() == null) {
            Assert.assertEquals(capture.stderr(), "", label);
            Assert.assertFalse(capture.stdout().isEmpty(), label);
            return;
        }
        Assert.assertEquals(capture.stdout(), "", label);
        Assert.assertEquals(capture.field("kind"), run.kind(), label);
    }

    private static void assertText(Case run) {
        List<String> argv = new ArrayList<>(run.argv());
        argv.addAll(List.of("--output", "text"));
        CliTest.Capture capture = new CliTest.Capture();
        String label = String.join(" ", argv);
        Assert.assertEquals(Cli.run(argv, capture.streams(), run.http(), run.projectDir()), run.code(),
                label + " -> " + capture.stderr());
        if (run.kind() == null) {
            Assert.assertEquals(capture.stderr(), "", label);
            Assert.assertFalse(capture.stdout().isEmpty(), label);
            return;
        }
        Assert.assertEquals(capture.stdout(), "", label);
        Assert.assertTrue(capture.stderr().startsWith("error: "), label + " -> " + capture.stderr());
    }

    @Test
    public void everyFailureKindMapsToOneExitCode() {
        record Kind(Failure failure, String kind, int code) { }
        List<Kind> kinds = List.of(
                new Kind(new Failure.PackageNotFound("a/b", null, null, "s"), "package-not-found", 1),
                new Kind(new Failure.SymbolNotFound("a/b", "1.0.0", null, List.of("x"), "m", "s", null, List.of()),
                        "symbol-not-found", 1),
                new Kind(new Failure.Validation("m", "s"), "validation", 2),
                new Kind(new Failure.Ambiguous("a/b", "1.0.0", null, List.of("x"), "m", "s", Failure.OWNERS,
                        List.of()), "ambiguous", 3),
                new Kind(new Failure.Upstream("u", 1, "m", "s", null, false), "upstream", 4),
                new Kind(new Failure.Timeout("u", 1, "s"), "timeout", 4),
                new Kind(new Failure.Upstream("u", 1, "m", "s", 500, true), "upstream", 5),
                new Kind(new Failure.Upstream("u", 1, "m", "s", null, true), "upstream", 5),
                new Kind(new Failure.Upstream("u", 1, "m", "s", 407, false), "upstream", 5),
                new Kind(new Failure.SchemaDrift("a/b", "1.0.0", null, List.of(), "s"), "schema-drift", 5),
                new Kind(new Failure.Internal("m", "s"), "internal", 6));
        for (Kind kind : kinds) {
            Assert.assertEquals(kind.failure().kind(), kind.kind());
            Assert.assertEquals(kind.failure().exitCode(), kind.code(), kind.failure().toString());
        }
    }
}
