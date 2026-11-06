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

import org.testng.Assert;
import org.testng.annotations.Test;
import picocli.CommandLine;

import java.util.List;

/**
 * The arguments {@code bal}'s launcher hands the tool, parsed the way the launcher parses them: a root command that
 * stops at its first positional, with the tool added as a subcommand and then told its parent.
 *
 * @since 0.1.0
 */
public class LauncherArgumentsTest {

    @CommandLine.Command(name = "bal")
    static final class Launcher {
    }

    private static List<String> handedOver(String... args) {
        DiscoverTool tool = new DiscoverTool(System.out, System.err);
        CommandLine launcher = new CommandLine(new Launcher());
        launcher.setStopAtPositional(true);
        launcher.addSubcommand(tool.getName(), tool);
        tool.setParentCmdParser(launcher);
        launcher.setPosixClusteredShortOptionsAllowed(false);
        launcher.parseArgs(args);
        return tool.arguments();
    }

    @Test
    public void aFlagBeforeTheNounReachesTheTool() {
        Assert.assertEquals(handedOver("discover", "-p", "ballerinax/kafka", "method", "send"),
                List.of("-p", "ballerinax/kafka", "method", "send"));
    }

    @Test
    public void anOptionWithItsValueAttachedReachesTheTool() {
        Assert.assertEquals(handedOver("discover", "--output-format=json", "client", "--client=Producer"),
                List.of("--output-format=json", "client", "--client=Producer"));
    }

    @Test
    public void flagsAfterTheNounKeepTheirOrder() {
        Assert.assertEquals(handedOver("discover", "method", "send", "-p", "ballerinax/kafka", "--client", "P"),
                List.of("method", "send", "-p", "ballerinax/kafka", "--client", "P"));
    }

    @Test
    public void anEndOfOptionsMarkerReachesTheTool() {
        Assert.assertEquals(handedOver("discover", "method", "--", "-foo", "-p", "a/b"),
                List.of("method", "--", "-foo", "-p", "a/b"));
    }

    @Test
    public void aLeadingHelpFlagIsPutBackLast() {
        Assert.assertEquals(handedOver("discover", "--help", "method"), List.of("method", "--help"));
    }
}
