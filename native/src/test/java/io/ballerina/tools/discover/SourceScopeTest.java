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

import io.ballerina.tools.discover.central.HttpOptions;
import io.ballerina.tools.discover.cli.Cli;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The package source is read for the {@code service} bucket alone — the only answer that shows which listener a
 * service type binds to. {@code ballerina/http} is the case that would read it: four of its seven service types
 * are no listener's attach target.
 *
 * @since 0.1.0
 */
public class SourceScopeTest {

    private static final String VERSION_ENTRY = "/registry/packages/ballerina/http/2.16.6";

    /** Central replayed, recording every request for the version entry or an archive. */
    private static FakeTransport central(List<String> sourceRequests) {
        String docs = FixtureCorpus.loadRawFixture("ballerina__http").toString();
        return FakeTransport.routing(url -> {
            if (url.contains("/docs/")) {
                return FakeTransport.ok(docs);
            }
            if (url.endsWith(VERSION_ENTRY)) {
                sourceRequests.add(url);
                return FakeTransport.status(404);
            }
            return FakeTransport.ok("[\"2.16.6\"]");
        }).downloading(url -> {
            sourceRequests.add(url);
            return Optional.empty();
        });
    }

    private static List<String> sourceRequestsOf(List<String> argv) {
        List<String> requests = new ArrayList<>();
        HttpOptions http = HttpOptions.builder().transport(central(requests)).baseDelayMs(1)
                .sleeper(millis -> {
                })
                .build();
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        int code = Cli.run(argv, new Cli.Streams(out::append, err::append), http);
        Assert.assertEquals(code, 0, argv + " failed with " + err);
        return requests;
    }

    @DataProvider(name = "otherAnswers")
    public Object[][] otherAnswers() {
        return new Object[][] {
                {List.of("ballerina/http")},
                {List.of("ballerina/http", "client")},
                {List.of("ballerina/http", "client", "Client")},
                {List.of("ballerina/http", "class")},
                {List.of("ballerina/http", "funcs")},
                {List.of("ballerina/http", "readme")},
        };
    }

    @Test(dataProvider = "otherAnswers")
    public void noOtherAnswerReadsTheSource(List<String> argv) {
        Assert.assertEquals(sourceRequestsOf(argv), List.of(), argv + " asked for the package source");
    }

    @Test
    public void theServiceBucketDoes() {
        Assert.assertFalse(sourceRequestsOf(List.of("ballerina/http", "service")).isEmpty());
    }
}
