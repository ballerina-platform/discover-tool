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

import java.nio.file.Path;

/**
 * A file a message names is shortened with {@code ~} only when it lies under the user's home.
 *
 * @since 0.1.0
 */
public class DisplayPathTest {

    @Test
    public void theSettingsFileIsNamedWithATildeOnlyUnderTheUsersHome() {
        Assert.assertEquals(DiscoverTool.displayPath(Path.of("/home/alice/.ballerina/Settings.toml"), "/home/alice"),
                "~/.ballerina/Settings.toml");
        Assert.assertEquals(DiscoverTool.displayPath(Path.of("/opt/bal-home/Settings.toml"), "/home/alice"),
                "/opt/bal-home/Settings.toml");
        Assert.assertEquals(DiscoverTool.displayPath(Path.of("/home/alicia/Settings.toml"), "/home/alice"),
                "/home/alicia/Settings.toml");
        Assert.assertEquals(DiscoverTool.displayPath(Path.of("/home/alice/Settings.toml"), ""),
                "/home/alice/Settings.toml");
    }
}
