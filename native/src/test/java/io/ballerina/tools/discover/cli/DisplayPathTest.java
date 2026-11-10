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

    private static final String HOME = Path.of("/home/alice").toString();

    @Test
    public void theSettingsFileIsNamedWithATildeOnlyUnderTheUsersHome() {
        Path settings = Path.of(HOME, ".ballerina", "Settings.toml");
        Assert.assertEquals(DiscoverTool.displayPath(settings, HOME),
                "~" + settings.getFileSystem().getSeparator() + Path.of(".ballerina", "Settings.toml"));
        assertShownWhole(Path.of("/opt/bal-home/Settings.toml"), HOME);
        assertShownWhole(Path.of("/home/alicia/Settings.toml"), HOME);
        assertShownWhole(Path.of(HOME, "Settings.toml"), "");
    }

    private static void assertShownWhole(Path file, String userHome) {
        Assert.assertEquals(DiscoverTool.displayPath(file, userHome), file.toString());
    }
}
