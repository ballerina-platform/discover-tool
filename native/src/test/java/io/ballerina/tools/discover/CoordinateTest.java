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

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

/**
 * The package argument as typed: {@code <org>/<name>[:<version>]}.
 *
 * @since 0.1.0
 */
public class CoordinateTest {

    @Test
    public void aBareNameHasNoVersion() {
        Coordinate coordinate = Coordinate.parse("ballerina/http").value();
        Assert.assertEquals(coordinate.qualified().qualified(), "ballerina/http");
        Assert.assertNull(coordinate.version());
        Assert.assertNull(coordinate.versionText());
    }

    @Test
    public void aVersionAfterTheNameIsSplitOffWhateverDotsTheNameHas() {
        Coordinate gmail = Coordinate.parse("ballerinax/googleapis.gmail:4.2.1").value();
        Assert.assertEquals(gmail.qualified().qualified(), "ballerinax/googleapis.gmail");
        Assert.assertEquals(gmail.versionText(), "4.2.1");
        Assert.assertEquals(Coordinate.parse("ballerina/http:2.15.7-alpha.1").value().versionText(), "2.15.7-alpha.1");
    }

    @Test
    public void anImportsKeywordEscapeIsDropped() {
        Coordinate escaped = Coordinate.parse("ballerinax/'client.config:1.0.0").value();
        Assert.assertEquals(escaped.qualified().qualified(), "ballerinax/client.config");
        Assert.assertEquals(escaped.versionText(), "1.0.0");
        Assert.assertEquals(Coordinate.parse("ballerina/lang.'int").value().qualified().qualified(),
                "ballerina/lang.int");
    }

    @Test
    public void anIncompleteVersionIsRejectedWithTheFullForm() {
        for (String input : List.of("ballerina/http:2.15", "ballerina/http:2", "ballerina/http:latest",
                "ballerina/http:", "ballerina/http:2.15.7:1")) {
            Result<Coordinate> parsed = Coordinate.parse(input);
            Assert.assertFalse(parsed.isOk(), input);
            Failure.Validation failure = (Failure.Validation) parsed.failure();
            Assert.assertTrue(failure.message().contains("is not a complete version"), failure.message());
            Assert.assertTrue(failure.suggestion().contains("ballerina/http:<major>.<minor>.<patch>"),
                    failure.suggestion());
        }
    }

    @Test
    public void aBadNameIsRejectedBeforeTheVersionIsRead() {
        Result<Coordinate> parsed = Coordinate.parse("ballerina:2.15.7");
        Assert.assertFalse(parsed.isOk());
        Assert.assertTrue(((Failure.Validation) parsed.failure()).message().startsWith("Invalid package name"));
    }
}
