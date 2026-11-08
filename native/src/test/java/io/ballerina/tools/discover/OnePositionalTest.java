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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ballerina.tools.discover.central.HttpOptions;
import io.ballerina.tools.discover.cli.Cli;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

/**
 * Every noun takes at most one positional: a search's keywords are one query.
 */
public class OnePositionalTest {

    private static JsonObject answer(String slug, String... argv) {
        CliTest.Capture capture = new CliTest.Capture();
        Assert.assertEquals(Cli.run(List.of(argv), capture.streams(), central(slug)), 0,
                String.join(" ", argv) + ": " + capture.stderr());
        return JsonParser.parseString(capture.stdout()).getAsJsonObject();
    }

    private static CliTest.Capture failure(int expected, String slug, String... argv) {
        CliTest.Capture capture = new CliTest.Capture();
        Assert.assertEquals(Cli.run(List.of(argv), capture.streams(), slug == null ? CliTest.never() : central(slug)),
                expected, String.join(" ", argv) + ": " + capture.stderr());
        Assert.assertEquals(capture.stdout(), "", String.join(" ", argv));
        return capture;
    }

    private static HttpOptions central(String slug) {
        return CliTest.centralFor(slug, FixtureCorpus.FIXTURE_VERSION.text());
    }

    @Test
    public void aSearchsQueryIsQuotedOnlyWhenItHoldsASpace() {
        CliTest.Capture one = failure(Failure.USAGE, null, "search", "kafka", "--page", "0");
        Assert.assertEquals(one.field("command"), "bal discover search kafka");
        CliTest.Capture two = failure(Failure.USAGE, null, "search", "kafka  send", "--page", "0");
        Assert.assertEquals(two.field("command"), "bal discover search \"kafka send\"");
    }

    @Test
    public void aSearchsUnquotedKeywordsAreAUsageErrorWithTheQuotedQuery() {
        CliTest.Capture central = failure(Failure.USAGE, null, "search", "kafka", "send");
        Assert.assertEquals(central.field("message"), "search takes one query, in quotes; got 2");
        Assert.assertEquals(central.field("suggestion"), "Quote the keywords as one query.");
        Assert.assertEquals(central.field("command"), "bal discover search \"kafka send\"");

        JsonObject searched = answer("ballerinax__kafka", "search", "producer send", "-p", "ballerinax/kafka");
        Assert.assertEquals(searched.getAsJsonArray("keywords").toString(), "[\"producer\",\"send\"]");
    }
}
