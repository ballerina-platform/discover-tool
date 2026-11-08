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
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Every noun takes at most one positional: a resource path's method is its {@code --accessor}, and a search's
 * keywords are one query.
 */
public class OnePositionalTest {

    private static final String GITHUB = "ballerinax/github";

    private static final String SLACK = "ballerinax/slack";

    private static final String HTTP = "ballerina/http";

    private static final String GITHUB_CLIENT = " -p " + GITHUB + " --client Client";

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
    public void aPathWithOneAccessorAnswersWithItsSignature() {
        JsonObject signature = answer("ballerinax__github", "resource", "gists/public", "-p", GITHUB);
        Assert.assertEquals(signature.get("path").getAsString(), "gists/public");
        Assert.assertEquals(signature.get("accessor").getAsString(), "get");
    }

    @Test
    public void aPathWithSeveralAccessorsListsThemEachWithItsCommand() {
        JsonObject listing = answer("ballerinax__github", "resource", "gists/:gistId/star", "-p", GITHUB);
        JsonObject row = listing.getAsJsonArray("resources").get(0).getAsJsonObject();
        Assert.assertEquals(row.get("path").getAsString(), "gists/:gistId/star");
        Assert.assertEquals(row.getAsJsonArray("accessors").toString(), "[\"get\",\"put\",\"delete\"]");
        for (String accessor : List.of("get", "put", "delete")) {
            String command = row.getAsJsonObject("commands").get(accessor).getAsString();
            Assert.assertEquals(command, "bal discover resource gists/:gistId/star" + GITHUB_CLIENT + " --accessor "
                    + accessor);
            JsonObject opened = answer("ballerinax__github", command.substring(Command.PREFIX.length()).split(" "));
            Assert.assertEquals(opened.get("accessor").getAsString(), accessor);
        }

        CliTest.Capture text = new CliTest.Capture();
        Assert.assertEquals(Cli.run(List.of("resource", "gists/:gistId/star", "-p", GITHUB), text.streams(),
                central("ballerinax__github"), null, true), 0, text.stderr());
        Assert.assertTrue(text.stdout().endsWith("\nNext: bal discover resource <path>" + GITHUB_CLIENT
                + " --accessor <accessor>\n"), text.stdout());
    }

    @Test
    public void anAccessorSelectsOneMethodOfThePath() {
        JsonObject put = answer("ballerinax__github", "resource", "gists/:gistId/star", "--accessor", "put", "-p",
                GITHUB);
        Assert.assertEquals(put.get("accessor").getAsString(), "put");
        Assert.assertTrue(put.get("declaration").getAsString().contains("resource function put gists/"),
                put.toString());
    }

    @DataProvider(name = "dotted")
    public Object[][] dotted() {
        return new Object[][] {{"reactions.get", "get"}, {"chat.delete", "post"}, {"reactions\\.get", "get"}};
    }

    @Test(dataProvider = "dotted")
    public void aDottedPathIsOneSegmentWithOrWithoutItsAccessor(String path, String accessor) {
        String plain = path.replace("\\", "");
        JsonObject alone = answer("ballerinax__slack", "resource", path, "-p", SLACK);
        Assert.assertEquals(alone.get("path").getAsString(), plain);
        Assert.assertEquals(alone.get("accessor").getAsString(), accessor);
        JsonObject named = answer("ballerinax__slack", "resource", path, "--accessor", accessor, "-p", SLACK);
        Assert.assertEquals(named.get("path").getAsString(), plain);
    }

    @Test
    public void aDottedPathIsNeverSplitIntoAPathAndAnAccessor() {
        CliTest.Capture split = failure(Failure.RUNTIME, "ballerinax__slack", "resource", "reactions", "--accessor",
                "get", "-p", SLACK);
        Assert.assertEquals(split.field("kind"), "symbol-not-found", split.stderr());
        CliTest.Capture joined = failure(Failure.RUNTIME, "ballerinax__slack", "resource", "reactions.get.get", "-p",
                SLACK);
        Assert.assertEquals(joined.field("kind"), "symbol-not-found", joined.stderr());
        CliTest.Capture wrong = failure(Failure.RUNTIME, "ballerinax__slack", "resource", "chat.delete",
                "--accessor", "get", "-p", SLACK);
        Assert.assertEquals(wrong.failure().getAsJsonArray("candidates").toString(), "[\"post\"]", wrong.stderr());
    }

    @Test
    public void theRootPathIsADot() {
        for (List<String> accessor : List.of(List.<String>of(), List.of("--accessor", "get"))) {
            List<String> argv = new ArrayList<>(List.of("resource", ".", "-p", GITHUB));
            argv.addAll(accessor);
            JsonObject root = answer("ballerinax__github", argv.toArray(String[]::new));
            Assert.assertEquals(root.get("path").getAsString(), ".");
            Assert.assertEquals(root.get("accessor").getAsString(), "get");
        }
    }

    @Test
    public void aRestParameterPathListsItsAccessorsAndTakesOneByFlag() {
        JsonObject listing = answer("ballerina__http", "resource", ":...path", "-p", HTTP, "--client", "Client");
        Assert.assertEquals(listing.getAsJsonArray("resources").get(0).getAsJsonObject().getAsJsonArray("accessors")
                .size(), 7, listing.toString());
        JsonObject post = answer("ballerina__http", "resource", ":...path", "--accessor", "post", "-p", HTTP,
                "--client", "Client");
        Assert.assertEquals(post.get("path").getAsString(), ":...path");
        Assert.assertEquals(post.get("accessor").getAsString(), "post");
    }

    @Test
    public void theOldPathThenAccessorFormIsFixedWithItsContainerWhenOnlyOneDeclaresIt() {
        CliTest.Capture one = failure(Failure.USAGE, "ballerinax__slack", "resource", "chat.delete", "post", "-p",
                SLACK);
        Assert.assertEquals(one.field("command"), "bal discover resource chat.delete -p " + SLACK
                + " --client Client --accessor post");
        CliTest.Capture several = failure(Failure.USAGE, "ballerina__http", "resource", ":...path", "get", "-p", HTTP);
        Assert.assertEquals(several.field("command"), "bal discover resource :...path -p " + HTTP + " --accessor get",
                "the container is left to the ambiguity the command answers with");
    }

    @Test
    public void anAccessorIsAResourcesFlagAlone() {
        for (List<String> argv : List.of(List.of("method", "send", "--accessor", "get", "-p", "ballerinax/kafka"),
                List.of("--accessor", "get", "client", "-p", GITHUB),
                List.of("search", "kafka", "--accessor", "get"),
                List.of("package", GITHUB, "--accessor", "get"))) {
            CliTest.Capture capture = failure(Failure.USAGE, null, argv.toArray(String[]::new));
            Assert.assertEquals(capture.field("message"), "unknown option '--accessor'", capture.stderr());
        }
        JsonObject before = answer("ballerinax__github", "--accessor", "get", "resource", "gists/public", "-p", GITHUB);
        Assert.assertEquals(before.get("accessor").getAsString(), "get", "an option may come before the noun");
    }

    @Test
    public void anAccessorNeedsAPath() {
        CliTest.Capture none = failure(Failure.USAGE, null, "resource", "--accessor", "get", "-p", GITHUB,
                "--client", "Client");
        Assert.assertEquals(none.field("message"), "--accessor names a method of one path, and no path is given");
        Assert.assertEquals(none.field("suggestion"), "Write the path before --accessor; list the paths with "
                + "'bal discover resource" + GITHUB_CLIENT + "'.");
    }

    @Test
    public void anAccessorGivenBeforeAndAfterTheNounIsAUsageError() {
        CliTest.Capture twice = failure(Failure.USAGE, null, "--accessor", "get", "resource", "gists/public",
                "--accessor", "put", "-p", GITHUB);
        Assert.assertEquals(twice.field("message"), "--accessor is given both before the noun and after it");
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

    @Test
    public void anyNounGivenMoreThanOneNameSaysItTakesOne() {
        for (String noun : List.of("client", "service", "class", "function", "type", "method")) {
            CliTest.Capture capture = failure(Failure.USAGE, "ballerinax__kafka", noun, "A", "B", "-p",
                    "ballerinax/kafka");
            Assert.assertEquals(capture.field("message"), noun + " takes one name; got 2", capture.stderr());
        }
    }
}
