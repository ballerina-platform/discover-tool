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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * A run that dies of anything, an exhausted heap included, ends as a failure in its output mode, never a stack trace.
 *
 * @since 0.1.0
 */
public class GuardedRunTest {

    private static String stderrOf(IntSupplier run, List<String> argv, int[] code) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        code[0] = DiscoverTool.guarded(run, argv, false, new PrintStream(err, true, StandardCharsets.UTF_8));
        return err.toString(StandardCharsets.UTF_8);
    }

    @Test
    public void anOutOfMemoryErrorBecomesAnInternalFailureInJson() {
        int[] code = new int[1];
        String err = stderrOf(() -> {
            throw new OutOfMemoryError("Java heap space");
        }, List.of("package", "ballerina/http"), code);
        Assert.assertEquals(code[0], Cli.FAILED);
        Assert.assertTrue(err.contains("\"kind\":\"internal\""), err);
        Assert.assertTrue(err.contains("Java heap space"), err);
        Assert.assertEquals(err.lines().count(), 1L, err);
    }

    @Test
    public void aRuntimeExceptionBecomesAnInternalFailureInTextWhenAskedFor() {
        int[] code = new int[1];
        String err = stderrOf(() -> {
            throw new IllegalStateException("broken");
        }, List.of("package", "ballerina/http", "--output", "text"), code);
        Assert.assertEquals(code[0], Cli.FAILED);
        Assert.assertTrue(err.startsWith("error: Unexpected internal failure: broken."), err);
    }

    @Test
    public void aRunThatReturnsKeepsItsCode() {
        int[] code = new int[1];
        Assert.assertEquals(stderrOf(() -> 0, List.of(), code), "");
        Assert.assertEquals(code[0], 0);
    }
}
