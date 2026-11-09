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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
        Assert.assertEquals(one.field("message"), "resource takes one path, and its accessor as --accessor; got 2");
        Assert.assertEquals(one.field("suggestion"), "Put the accessor in --accessor.");
        Assert.assertEquals(one.field("command"), "bal discover resource chat.delete -p " + SLACK
                + " --client Client --accessor post");
    }

    @Test
    public void theOldPathThenAccessorFormDeclaredBySeveralContainersListsEachOnesCommand() {
        CliTest.Capture several = failure(Failure.USAGE, "ballerina__http", "resource", ":...path", "get", "-p", HTTP);
        Assert.assertFalse(several.failure().has("command"), several.stderr());
        JsonArray owners = several.failure().getAsJsonArray("owners");
        Assert.assertTrue(owners.size() > 1, several.stderr());
        for (JsonElement owner : owners) {
            String command = owner.getAsJsonObject().get("command").getAsString();
            Assert.assertTrue(command.startsWith("bal discover resource :...path -p " + HTTP + " --"), command);
            Assert.assertTrue(command.endsWith(" --accessor get"), command);
            JsonObject opened = answer("ballerina__http", command.substring(Command.PREFIX.length()).split(" "));
            Assert.assertEquals(opened.get("accessor").getAsString(), "get", command);
        }
    }

    @DataProvider(name = "notAnAccessor")
    public Object[][] notAnAccessor() {
        return new Object[][] {{"gists/:gistId/star", "post", "[\"get\",\"put\",\"delete\"]"},
                {"gists/public", "GET", "[\"get\"]"}};
    }

    @Test(dataProvider = "notAnAccessor")
    public void aWordAfterThePathThatIsNotItsAccessorMissesWithThePathsAccessors(String path, String word,
            String accessors) {
        CliTest.Capture miss = failure(Failure.RUNTIME, "ballerinax__github", "resource", path, word, "-p", GITHUB);
        Assert.assertEquals(miss.field("kind"), "symbol-not-found", miss.stderr());
        Assert.assertTrue(miss.field("message").startsWith("resource takes one path, and its accessor as --accessor;"
                + " got 2; '" + word + "' is not an accessor of " + path), miss.stderr());
        Assert.assertEquals(miss.failure().getAsJsonArray("candidates").toString(), accessors, miss.stderr());
        Assert.assertFalse(miss.failure().has("command"), miss.stderr());
    }

    @Test
    public void anAccessorOnAnotherNounIsAUsageErrorWithTheCommandWithoutIt() {
        List<List<String>> argvs = List.of(List.of("method", "send", "--accessor", "get", "-p", "ballerinax/kafka"),
                List.of("method", "send", "-p", "ballerinax/kafka", "--accessor", "get"),
                List.of("method", "send", "-p", "ballerinax/kafka", "--accessor=get"),
                List.of("--accessor", "get", "client", "-p", GITHUB),
                List.of("search", "kafka", "--accessor", "get"),
                List.of("package", GITHUB, "--accessor", "get"));
        List<String> commands = List.of("bal discover method send -p ballerinax/kafka",
                "bal discover method send -p ballerinax/kafka", "bal discover method send -p ballerinax/kafka",
                "bal discover client -p " + GITHUB, "bal discover search kafka", "bal discover package " + GITHUB);
        for (int i = 0; i < argvs.size(); i++) {
            CliTest.Capture capture = failure(Failure.USAGE, null, argvs.get(i).toArray(String[]::new));
            Assert.assertEquals(capture.field("message"), "--accessor applies only to resource", capture.stderr());
            Assert.assertEquals(capture.field("suggestion"), "Drop --accessor.", capture.stderr());
            Assert.assertEquals(capture.field("command"), commands.get(i), capture.stderr());
        }
        CliTest.Capture worse = failure(Failure.USAGE, null, "client", "A", "B", "--accessor", "get", "-p", GITHUB);
        Assert.assertTrue(worse.field("message").startsWith("--accessor applies only to resource; client takes one "
                + "name"), worse.stderr());
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
    public void aPathWithOneAccessorAndPathsUnderItLists() {
        JsonObject listing = answer("ballerinax__github", "resource", "users/:username", "-p", GITHUB, "--client",
                "Client");
        JsonArray resources = listing.getAsJsonArray("resources");
        Assert.assertTrue(resources.size() > 1, listing.toString());
        JsonObject own = resources.get(0).getAsJsonObject();
        Assert.assertEquals(own.get("path").getAsString(), "users/:username", listing.toString());
        Assert.assertEquals(own.getAsJsonArray("accessors").size(), 1, listing.toString());
    }

    @Test
    public void aFilterNarrowsAResourceListingToOneAccessor() {
        JsonObject listing = answer("ballerinax__github", "resource", "gists", "-p", GITHUB, "--client", "Client",
                "--filter", "get");
        JsonArray resources = listing.getAsJsonArray("resources");
        Assert.assertTrue(resources.size() > 1, listing.toString());
        resources.forEach(resource -> Assert.assertEquals(
                resource.getAsJsonObject().getAsJsonArray("accessors").toString(), "[\"get\"]", listing.toString()));
        JsonObject one = answer("ballerinax__github", "resource", "gists", "-p", GITHUB, "--client", "Client",
                "--filter", "put");
        Assert.assertEquals(one.get("accessor").getAsString(), "put", "the one put under gists: " + one);
    }

    @Test
    public void anAccessorIsMatchedAsSpelled() {
        CliTest.Capture upper = failure(Failure.RUNTIME, "ballerinax__github", "resource", "gists/:gistId/star",
                "--accessor", "GET", "-p", GITHUB);
        Assert.assertEquals(upper.failure().getAsJsonArray("candidates").toString(), "[\"get\",\"put\",\"delete\"]",
                upper.stderr());
    }

    @Test
    public void aPathEndingInASlashMissesSayingSo() {
        CliTest.Capture slash = failure(Failure.RUNTIME, "ballerinax__github", "resource", "gists/public/", "-p",
                GITHUB, "--client", "Client");
        Assert.assertTrue(slash.field("message").endsWith("; a resource path does not end in /"), slash.stderr());
        Assert.assertEquals(slash.field("suggestion"),
                "Name one of the resource paths that start with gists/public below.", slash.stderr());
        Assert.assertEquals(slash.failure().getAsJsonArray("candidates").toString(), "[\"gists/public\"]",
                slash.stderr());
    }

    @Test
    public void aPackageTypedBeforeThePathIsThePackage() {
        for (String path : List.of("gists/public", "gists")) {
            CliTest.Capture before = failure(Failure.USAGE, null, "resource", GITHUB, path);
            Assert.assertEquals(before.field("message"), "'" + GITHUB + "' is a package, not a resource name; name it "
                    + "with -p", before.stderr());
            String command = before.field("command");
            Assert.assertEquals(command, "bal discover resource " + path + " -p " + GITHUB);
            answer("ballerinax__github", command.substring(Command.PREFIX.length()).split(" "));
        }
    }

    @Test
    public void thePackageFirstFormMovesOnlyAnAccessorToItsFlag() {
        CliTest.Capture verb = failure(Failure.USAGE, null, GITHUB, "resource", "gists/public", "get");
        Assert.assertEquals(verb.field("command"), "bal discover resource gists/public -p " + GITHUB
                + " --accessor get", verb.stderr());
        for (List<String> words : List.of(List.of("gists", "public", "get"), List.of("gists/public", "frob"))) {
            List<String> argv = new ArrayList<>(List.of(GITHUB, "resource"));
            argv.addAll(words);
            CliTest.Capture none = failure(Failure.USAGE, null, argv.toArray(String[]::new));
            Assert.assertFalse(none.failure().has("command"), none.stderr());
            Assert.assertTrue(none.field("suggestion").endsWith("Write the path alone, and put its accessor in "
                    + "--accessor."), none.stderr());
        }
    }

    @Test
    public void aContainersMemberIsAResourceOnlyWithAPathOrAPathAndItsAccessor() {
        CliTest.Capture method = failure(Failure.USAGE, null, "client", "Client", "get", "-p", GITHUB);
        Assert.assertEquals(method.field("command"), "bal discover method get -p " + GITHUB + " --client Client");
        CliTest.Capture accessed = failure(Failure.USAGE, null, "client", "Client", "gists/public", "get", "-p",
                GITHUB);
        Assert.assertEquals(accessed.field("command"), "bal discover resource gists/public -p " + GITHUB
                + " --client Client --accessor get");
        CliTest.Capture stray = failure(Failure.USAGE, null, "client", "Client", "gists/public", "frob", "-p", GITHUB);
        Assert.assertFalse(stray.failure().has("command"), stray.stderr());
        Assert.assertEquals(stray.field("suggestion"), "Write the path alone, and put its accessor in --accessor.");
    }

    @Test
    public void aMissingPackagesShapeNeverKeepsAWordThatIsNotAnAccessor() {
        CliTest.Capture shape = failure(Failure.USAGE, null, "resource", "gists/:gistId/star", "frob");
        Assert.assertTrue(shape.field("suggestion").endsWith("then write 'bal discover resource gists/:gistId/star "
                + "-p <org>/<name> --accessor <accessor>'."), shape.stderr());
    }

    @Test
    public void aSearchNeedsAQuery() {
        for (String query : List.of("", "   ")) {
            CliTest.Capture empty = failure(Failure.USAGE, null, "search", query);
            Assert.assertEquals(empty.field("message"), "the search needs a query of one or more keywords",
                    empty.stderr());
        }
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

        JsonObject searched = answer("ballerinax__github", "search", "gists star", "-p", GITHUB);
        Assert.assertEquals(searched.getAsJsonArray("keywords").toString(), "[\"gists\",\"star\"]");
        Assert.assertTrue(searched.toString().contains("gists/:gistId/star"), searched.toString());
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
