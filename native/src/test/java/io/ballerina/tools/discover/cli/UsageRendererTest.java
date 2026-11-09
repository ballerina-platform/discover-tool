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

public class UsageRendererTest {

    @Test
    public void aUsageErrorsSynopsisIsOneLineNamingThePackageAndContainerFlagsItTakes() {
        Commands.Grammar grammar = Commands.Grammar.create();
        Assert.assertEquals(UsageRenderer.usageLine(grammar, null),
                "Usage: bal discover <noun> [<name>] -p <org>/<name>[:<version>] [options]");
        Assert.assertEquals(UsageRenderer.usageLine(grammar, "package"),
                "Usage: bal discover package <org>/<name>[:<version>] [options]");
        Assert.assertEquals(UsageRenderer.usageLine(grammar, "method"),
                "Usage: bal discover method [<name>] -p <org>/<name>[:<version>] [--client|--class|--service <name>] "
                        + "[options]");
        Assert.assertEquals(UsageRenderer.usageLine(grammar, "resource"),
                "Usage: bal discover resource [<path>] -p <org>/<name>[:<version>] [--client|--class|--service <name>] "
                        + "[--accessor <accessor>] [options]");
        Assert.assertEquals(UsageRenderer.usageLine(grammar, "search"),
                "Usage: bal discover search <query> [-p <org>/<name>[:<version>]] [options]");
    }
}
