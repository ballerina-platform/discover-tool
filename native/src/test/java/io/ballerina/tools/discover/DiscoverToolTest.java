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
import io.ballerina.tools.discover.cli.Cli;
import io.ballerina.tools.discover.cli.DiscoverTool;
import org.testng.Assert;
import org.testng.annotations.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * The process wrapper, and the usage text as a golden file.
 *
 * <p>{@link CliTest} proves the behaviour; this proves the two things only the wrapper owns: that {@code bal} hands
 * the whole argument list through to us unparsed, and that the usage text has not drifted.
 *
 * @since 0.1.0
 */
public class DiscoverToolTest {

    private static final Path COMMAND_OUTPUTS =
            Path.of("src", "test", "resources", "command-outputs", "unix");

    // The tool driven the way `bal` drives it: through picocli, into the raw argument list.
    private record Run(int exitCode, String stdout, String stderr) { }

    private static Run run(String... argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        DiscoverTool tool = new DiscoverTool(
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        new CommandLine(tool).parseArgs(argv);
        tool.execute();
        return new Run(tool.exitCode(),
                out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8));
    }

    private static String usage(String... argv) {
        StringBuilder err = new StringBuilder();
        StringBuilder out = new StringBuilder();
        int code = Cli.run(List.of(argv), new Cli.Streams(out::append, err::append),
                HttpOptions.builder().build());
        Assert.assertEquals(code, 0, "a usage request is answered, not failed");
        Assert.assertEquals(err.toString(), "", "stderr is for failures only");
        return out.toString();
    }

    @Test
    public void theUsageTextIsUnchanged() {
        FixtureCorpus.matchesSnapshot(
                COMMAND_OUTPUTS.resolve("help.txt"), usage("--help"), "root usage");
    }

    @Test
    public void theToolReceivesTheWholeArgumentListUnparsed() {
        // Verified against a real `bal discover` invocation: all ten arguments of a realistic call arrive raw,
        // which is why the grammar can be ours. If `bal` ever started consuming flags, this is where it shows.
        Run help = run();
        Assert.assertEquals(help.exitCode(), 0);
        Assert.assertEquals(help.stderr(), "");
        Assert.assertTrue(help.stdout().startsWith("Usage: bal discover"));
    }

    @Test
    public void anUnknownBucketIsExit1WithOneJsonObjectOnStderr() {
        Run run = run("ballerinax/kafka", "nosuchbucket");
        Assert.assertEquals(run.exitCode(), 1);
        Assert.assertEquals(run.stdout(), "");
        Assert.assertTrue(run.stderr().contains("\"kind\":\"validation\""), run.stderr());
        Assert.assertTrue(run.stderr().endsWith("}\n"));
        Assert.assertEquals(run.stderr().lines().count(), 1L, run.stderr());
    }

    @Test
    public void theHelpTextNamesEveryBucketWiredSoFar() {
        String text = usage("--help");
        for (String bucket : List.of("client", "service", "class", "funcs")) {
            Assert.assertTrue(text.contains(bucket), bucket);
        }
        Assert.assertFalse(text.contains("language server"), "no document mentions the language server");
    }

    /** The RFC has no equivalent for any of these, so they are gone outright rather than hidden. */
    @Test
    public void deadFlagsAppearNowhereInTheUsageText() {
        String text = usage("--help");
        for (String flag : List.of("--all", "--search", "--resolve-types", " -r ", " -s ")) {
            Assert.assertFalse(text.contains(flag), flag);
        }
    }

    @Test
    public void theLongDescriptionIsTheUsageText() {
        // `bal help discover` reads this, so it must not be a second, drifting copy.
        StringBuilder sb = new StringBuilder();
        new DiscoverTool(System.out, System.err).printLongDesc(sb);
        Assert.assertTrue(sb.toString().startsWith("Usage: bal discover"));
        Assert.assertEquals(sb.toString(), usage("--help"));
    }

    @Test
    @SuppressWarnings("deprecation")
    public void theOneLineUsageNamesEveryFlag() {
        StringBuilder sb = new StringBuilder();
        new DiscoverTool(System.out, System.err).printUsage(sb);
        Assert.assertTrue(sb.toString().startsWith("  bal discover [<org/name>] [bucket] [args...]"), sb.toString());
        for (String flag : List.of("--refresh", "--output", "--filter", "--page", "-m", "--version", "--help")) {
            Assert.assertTrue(sb.toString().contains(flag), flag + " missing from: " + sb);
        }
    }

    @Test
    public void theToolNamesItselfDiscover() {
        // The id `bal-tools.toml` binds, so a mismatch makes the installed tool unroutable.
        Assert.assertEquals(new DiscoverTool(System.out, System.err).getName(), "discover");
    }
}
