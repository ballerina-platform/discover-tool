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

/**
 * Whether stdout is a terminal: the C runtime's {@code isatty} where native access is granted, else the console.
 * The test runner's stdout is a pipe, so both answer no.
 *
 * @since 0.1.0
 */
public class TerminalTest {

    @Test
    public void withoutNativeAccessTheConsoleAnswers() {
        Assert.assertEquals(Terminal.stdoutIsTerminal(false), Terminal.consoleIsTerminal());
        Assert.assertFalse(Terminal.stdoutIsTerminal(false));
    }

    @Test
    public void theCRuntimesIsattyResolvesOnThisPlatform() {
        Assert.assertTrue(Terminal.isatty().isPresent(), System.getProperty("os.name"));
        Assert.assertFalse(Terminal.stdoutIsTerminal(true));
    }
}
