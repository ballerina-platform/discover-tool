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

package io.ballerina.tools.discover.cli;

import io.ballerina.tools.discover.central.ConnectProxy;
import io.ballerina.tools.discover.central.HttpOptions;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * A proxy's username and password reach it inside the {@code CONNECT} tunnel every request to Central goes through.
 *
 * <p>The JDK refuses Basic credentials there unless a system property says otherwise, and reads that property once,
 * when the first HTTP client is built. Only a fresh JVM shows whether the tool sets it in time, so each case runs in
 * one, configured from a {@code Settings.toml} the way {@code bal pull} is.
 *
 * @since 0.1.0
 */
public class TunnelAuthenticationTest {

    private static final String SECRET = "secret";
    private static final String WRONG = "wrong";
    private static final String USER = "alice";
    private static final String CENTRAL_TUNNEL = "CONNECT api.central.ballerina.io:443 HTTP/1.1 ";
    private static final String INVALID_TUNNEL = "CONNECT central.invalid:443 HTTP/1.1 ";
    private static final long FORK_TIMEOUT_SECONDS = 120;

    private record Run(int exitCode, String stdout, String stderr) { }

    private static Run fork(int proxyPort, String password, List<String> jvmOptions, String... args)
            throws IOException, InterruptedException {
        Path home = Files.createTempDirectory("bal-discover-tunnel-");
        Files.writeString(home.resolve("Settings.toml"), "[proxy]\nhost = \"127.0.0.1\"\nport = " + proxyPort
                + "\nusername = \"" + USER + "\"\npassword = \"" + password + "\"\n", StandardCharsets.UTF_8);
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.addAll(jvmOptions);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(ForkedRun.class.getName());
        command.addAll(List.of(args));
        Path out = home.resolve("stdout");
        Path err = home.resolve("stderr");
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(home.toFile())
                .redirectOutput(out.toFile())
                .redirectError(err.toFile());
        builder.environment().put("BALLERINA_HOME_DIR", home.toString());
        builder.environment().put("BAL_DISCOVER_CACHE", "off");
        Process process = builder.start();
        Assert.assertTrue(process.waitFor(FORK_TIMEOUT_SECONDS, TimeUnit.SECONDS), "the forked run did not finish");
        return new Run(process.exitValue(), Files.readString(out, StandardCharsets.UTF_8),
                Files.readString(err, StandardCharsets.UTF_8));
    }

    private static Run tool(ConnectProxy proxy, String password, List<String> jvmOptions)
            throws IOException, InterruptedException {
        return fork(proxy.port(), password, jvmOptions, ForkedRun.TOOL, "ballerina/http", "--output", "json");
    }

    @Test
    public void theToolSendsTheConfiguredCredentialsInsideTheTunnel() throws IOException, InterruptedException {
        try (ConnectProxy proxy = new ConnectProxy(ConnectProxy.basic(USER, SECRET))) {
            tool(proxy, SECRET, List.of());
            Assert.assertEquals(proxy.requests().get(0), CENTRAL_TUNNEL);
            Assert.assertTrue(proxy.requests().contains(CENTRAL_TUNNEL + ConnectProxy.basic(USER, SECRET)),
                    proxy.requests().toString());
        }
    }

    @Test
    public void aRejectedPasswordIsSentOnceAndReportedAgainstTheSettingsFile()
            throws IOException, InterruptedException {
        try (ConnectProxy proxy = new ConnectProxy(ConnectProxy.basic(USER, SECRET))) {
            Run run = tool(proxy, WRONG, List.of());
            Assert.assertEquals(proxy.requests(), List.of(CENTRAL_TUNNEL,
                    CENTRAL_TUNNEL + ConnectProxy.basic(USER, WRONG)));
            Assert.assertEquals(run.exitCode(), 1, run.stderr());
            Assert.assertTrue(run.stderr().contains("rejected the username and password in the [proxy] table of "),
                    run.stderr());
            Assert.assertTrue(run.stderr().contains("Settings.toml"), run.stderr());
        }
    }

    @Test
    public void basicCredentialsTheUserDisabledAreNeverSentAndSaySo() throws IOException, InterruptedException {
        try (ConnectProxy proxy = new ConnectProxy(ConnectProxy.basic(USER, SECRET))) {
            Run run = tool(proxy, SECRET, List.of("-D" + HttpOptions.TUNNELING_DISABLED_SCHEMES + "=Basic"));
            Assert.assertTrue(proxy.requests().stream().allMatch(CENTRAL_TUNNEL::equals), proxy.requests().toString());
            Assert.assertEquals(run.exitCode(), 1, run.stderr());
            Assert.assertTrue(run.stderr().contains("disables Basic"), run.stderr());
            Assert.assertFalse(run.stderr().contains("rejected"), run.stderr());
        }
    }

    // The tunnel closes at once, so the JDK may retry the request once with the credentials it now holds.
    @Test
    public void aTransportAnswersTheTunnelsChallengeWithTheConfiguredCredentials()
            throws IOException, InterruptedException {
        try (ConnectProxy proxy = new ConnectProxy(ConnectProxy.basic(USER, SECRET))) {
            fork(proxy.port(), SECRET, List.of(), ForkedRun.TRANSPORT, String.valueOf(proxy.port()), USER, SECRET,
                    "1");
            List<String> requests = proxy.requests();
            Assert.assertTrue(requests.size() >= 2, requests.toString());
            Assert.assertEquals(requests.get(0), INVALID_TUNNEL);
            Assert.assertTrue(requests.subList(1, requests.size()).stream()
                    .allMatch((INVALID_TUNNEL + ConnectProxy.basic(USER, SECRET))::equals), requests.toString());
        }
    }

    @Test
    public void eachRequestSendsARejectedPasswordOnceAndSaysSo() throws IOException, InterruptedException {
        try (ConnectProxy proxy = new ConnectProxy(ConnectProxy.basic(USER, SECRET))) {
            Run run = fork(proxy.port(), WRONG, List.of(), ForkedRun.TRANSPORT, String.valueOf(proxy.port()), USER,
                    WRONG, "2");
            String rejected = INVALID_TUNNEL + ConnectProxy.basic(USER, WRONG);
            Assert.assertEquals(proxy.requests(), List.of(INVALID_TUNNEL, rejected, INVALID_TUNNEL, rejected));
            Assert.assertEquals(run.stdout().lines().filter(line -> line.contains("PROXY_REJECTED")).count(), 2,
                    run.stdout());
        }
    }
}
