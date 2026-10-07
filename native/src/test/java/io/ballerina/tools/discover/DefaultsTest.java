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

import io.ballerina.tools.discover.model.Defaults;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Set;

/**
 * Which default expressions name only what a caller can write: everything the package exports, the language's own
 * words, and other modules' qualified names.
 *
 * @since 0.1.0
 */
public class DefaultsTest {

    private static final Set<String> EXPORTED = Set.of("PUBLIC_TIMEOUT", "Mode", "INPUT");

    private static boolean writable(String expression) {
        return Defaults.isWritable(expression, EXPORTED);
    }

    @Test
    public void anExportedNameIsWritableAndAPrivateOneIsNot() {
        Assert.assertTrue(writable("PUBLIC_TIMEOUT"));
        Assert.assertFalse(writable("BASE_URL"));
        Assert.assertTrue(writable("<string?[]>[]"));
        Assert.assertTrue(writable("true"));
    }

    @Test
    public void aQualifiedNameIsAnotherModulesAndNeverScanned() {
        Assert.assertTrue(writable("http:HTTP_2_0"));
        Assert.assertTrue(writable("uuid:createType4AsString()"));
        Assert.assertTrue(writable("[http:STATUS_OK, PUBLIC_TIMEOUT]"));
        Assert.assertFalse(writable("f(http:STATUS_OK, BASE_URL)"));
    }

    @Test
    public void aRecordLiteralsKeysAreFieldsButItsValuesAreNames() {
        Assert.assertTrue(writable("[{obx: {}}]"));
        Assert.assertTrue(writable("[{in1:{}}]"));
        Assert.assertTrue(writable("{timeout: PUBLIC_TIMEOUT, 'type: INPUT}"));
        Assert.assertFalse(writable("{timeout: DEFAULT_TIMEOUT}"));
        Assert.assertFalse(writable("{timeout:DEFAULT_TIMEOUT}"));
        Assert.assertFalse(writable("{a: {}, b: [PRIVATE]}"));
        Assert.assertTrue(writable("{name: \"MCP Client\", version: \"1.0.0\"}"));
    }

    @Test
    public void bothBranchesOfATernaryAreNames() {
        Assert.assertTrue(writable("PUBLIC_TIMEOUT > 0 ? PUBLIC_TIMEOUT : INPUT"));
        Assert.assertFalse(writable("PUBLIC_TIMEOUT > 0 ? INPUT : PRIVATE_FALLBACK"));
        Assert.assertFalse(writable("PUBLIC_TIMEOUT > 0 ? PRIVATE_FALLBACK : INPUT"));
    }

    @Test
    public void aNumericLiteralHoldsNoName() {
        Assert.assertTrue(writable("1e10"));
        Assert.assertTrue(writable("0x1F"));
        Assert.assertTrue(writable("1.5e-3f"));
        Assert.assertTrue(writable("{group: 0x7fe0, element: 0x0010}"));
    }

    @Test
    public void aStringOrCommentHoldsTextNotNames() {
        Assert.assertTrue(writable("\"BASE_URL\""));
        Assert.assertTrue(writable("string `${PUBLIC_TIMEOUT} BASE_URL`"));
        Assert.assertTrue(writable("[INPUT, // StudyDate\n INPUT]"));
    }

    @Test
    public void aFieldAccessIsNotANameButASpreadIs() {
        Assert.assertTrue(writable("PUBLIC_TIMEOUT.length()"));
        Assert.assertTrue(writable("Mode?.value"));
        Assert.assertFalse(writable("[...PRIVATE_LIST]"));
    }
}
