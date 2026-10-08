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
    }

    @Test
    public void aVersionAfterTheNameIsSplitOffWhateverDotsTheNameHas() {
        Coordinate gmail = Coordinate.parse("ballerinax/googleapis.gmail:4.2.1").value();
        Assert.assertEquals(gmail.qualified().qualified(), "ballerinax/googleapis.gmail");
        Assert.assertEquals(gmail.version().text(), "4.2.1");
        Assert.assertEquals(Coordinate.parse("ballerina/http:2.15.7-alpha.1").value().version().text(),
                "2.15.7-alpha.1");
    }

    @Test
    public void anImportsKeywordEscapeIsRejectedWithTheUnescapedForm() {
        for (String[] each : new String[][] {
                {"ballerinax/'client.config:1.0.0", "Run `bal discover ballerinax/client.config:1.0.0`."},
                {"ballerina/lang.'int", "Run `bal discover ballerina/lang.int`."},
                {"'ballerina/http", "Run `bal discover ballerina/http`."}}) {
            Result<Coordinate> parsed = Coordinate.parse(each[0]);
            Assert.assertFalse(parsed.isOk(), each[0]);
            Failure.Validation failure = (Failure.Validation) parsed.failure();
            Assert.assertEquals(failure.message(), "A package name is written unescaped, but " + each[0]
                    + " quotes part of it the way an import does.");
            Assert.assertEquals(failure.suggestion(), each[1]);
        }
    }

    @Test
    public void surroundingWhitespaceIsNotTrimmedAway() {
        for (String input : List.of(" ballerina/http", "ballerina/http ", "ballerina/http: 2.15.7",
                "ballerina/http:2.15.7 ")) {
            Assert.assertFalse(Coordinate.parse(input).isOk(), "'" + input + "'");
        }
    }

    @Test
    public void anIncompleteVersionIsRejectedWithTheFullForm() {
        for (String input : List.of("ballerina/http:2.15", "ballerina/http:2", "ballerina/http:latest",
                "ballerina/http:2.15.7:1", "ballerina/http:2.15.7..", "ballerina/http:2.15.7-",
                "ballerina/http:2.15.7.1", "ballerina/http:2.15.7+", "ballerina/http:2.15.7-alpha..1",
                "ballerina/http:2.15.7-01", "ballerina/http:v2.15")) {
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

    private static Failure.Validation rejected(String input) {
        Result<Coordinate> parsed = Coordinate.parse(input);
        Assert.assertFalse(parsed.isOk(), input);
        return (Failure.Validation) parsed.failure();
    }

    @Test
    public void nothingAfterTheColonIsWordedAsSuch() {
        Failure.Validation failure = rejected("ballerina/http:");
        Assert.assertEquals(failure.message(), "Nothing follows the ':' in 'ballerina/http:'.");
        Assert.assertEquals(failure.suggestion(), "Write a version after it, ballerina/http:<major>.<minor>.<patch>, "
                + "or drop the ':' to read the version your project locks or Central's latest: ballerina/http");
    }

    @Test
    public void aLeadingVOrZeroIsCorrectedToTheVersionItSpells() {
        Failure.Validation prefixed = rejected("ballerinax/kafka:v4.6.5");
        Assert.assertEquals(prefixed.message(),
                "'v4.6.5' in 'ballerinax/kafka:v4.6.5' is not a version: a version has no leading v.");
        Assert.assertEquals(prefixed.suggestion(), "Run `bal discover ballerinax/kafka:4.6.5`.");

        Failure.Validation zero = rejected("ballerina/http:02.15.07");
        Assert.assertEquals(zero.message(),
                "'02.15.07' in 'ballerina/http:02.15.07' is not a version: a number in a version has no leading zero.");
        Assert.assertEquals(zero.suggestion(), "Run `bal discover ballerina/http:2.15.7`.");
        Assert.assertTrue(Coordinate.parse("ballerina/http:0.10.0-0.alpha").isOk());
    }

    @Test
    public void aMistypedNameIsAnsweredWithTheCallersOwnPackage() {
        Failure.Validation npm = rejected("ballerinax/kafka@4.6.5");
        Assert.assertEquals(npm.message(), "Invalid package name 'ballerinax/kafka@4.6.5': a version follows a ':', "
                + "not an '@'.");
        Assert.assertEquals(npm.suggestion(), "Run `bal discover ballerinax/kafka:4.6.5`.");

        Failure.Validation bare = rejected("kafka");
        Assert.assertEquals(bare.message(),
                "Invalid package name 'kafka': a package is named with its organization, <org>/kafka.");
        Assert.assertEquals(bare.suggestion(), "`bal search kafka` lists the packages that match, with their "
                + "organizations.");
    }
}
