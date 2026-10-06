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
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;

public class UsageRendererTest {

    @Test
    public void aHiddenFlagIsNeverListedAndOneOrNoFlagIsStillASentence() {
        Assert.assertEquals(UsageRenderer.knownFlags(CommandSpec.create()), "This command takes no flags.");

        CommandSpec one = CommandSpec.create()
                .addOption(OptionSpec.builder("--page").arity("1").build())
                .addOption(OptionSpec.builder("--all").hidden(true).build());
        Assert.assertEquals(UsageRenderer.knownFlags(one), "The only known flag is --page.");

        CommandSpec two = CommandSpec.create()
                .addOption(OptionSpec.builder("-h", "--help").usageHelp(true).build())
                .addOption(OptionSpec.builder("--all").hidden(true).build())
                .addOption(OptionSpec.builder("--page").arity("1").build());
        Assert.assertEquals(UsageRenderer.knownFlags(two), "Known flags are --page and --help/-h.");
    }
}
