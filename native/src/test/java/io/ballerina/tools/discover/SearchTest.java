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
import io.ballerina.tools.discover.cli.Cli;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.JsonRenderer;
import io.ballerina.tools.discover.render.TextRenderer;
import io.ballerina.tools.discover.views.Search;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

/**
 * {@code bal discover <pkg> search <keywords...>}: every name of one package, matched locally.
 *
 * @since 0.1.0
 */
public class SearchTest {

    private static DiscoverResult.Search search(String slug, int page, String... keywords) {
        Result<DiscoverResult> answer = Search.render(FixtureCorpus.loadedFixture(slug),
                new Search.Options(List.of(keywords), page));
        Assert.assertTrue(answer.isOk(), answer.isOk() ? "" : answer.failure().describe());
        return (DiscoverResult.Search) answer.value();
    }

    private static List<String> names(DiscoverResult.Search search, DiscoverResult.Search.Kind kind) {
        return search.groups().stream().filter(group -> group.kind() == kind)
                .flatMap(group -> group.hits().stream())
                .map(hit -> hit.container() == null ? hit.name() : hit.container() + " " + hit.name())
                .toList();
    }

    @Test
    public void aKeywordFindsTheMethodsNamedForItWithTheirContainersCommand() {
        DiscoverResult.Search send = search("ballerinax__kafka", 1, "send");
        Assert.assertEquals(names(send, DiscoverResult.Search.Kind.REMOTE),
                List.of("Producer send", "Producer sendWithMetadata"));
        Assert.assertEquals(send.groups().get(0).hits().get(0).command(),
                "bal discover method send -p ballerinax/kafka --client Producer");
        Assert.assertNull(send.paging());
    }

    @Test
    public void everyKeywordMustMatchAndAResourceIsSearchedByItsPathAndAccessor() {
        DiscoverResult.Search post = search("ballerinax__github", 1, "repos/:owner/:repo/issues", "post");
        Assert.assertEquals(post.counts(), java.util.Map.of(DiscoverResult.Search.Kind.RESOURCES, 6),
                names(post, DiscoverResult.Search.Kind.RESOURCES).toString());
        DiscoverResult.Search.Hit first = post.groups().get(0).hits().get(0);
        Assert.assertEquals(first.name(), "repos/:owner/:repo/issues");
        Assert.assertEquals(first.detail(), "post");
        Assert.assertEquals(first.command(),
                "bal discover resource repos/:owner/:repo/issues post -p ballerinax/github --client Client");
    }

    @Test
    public void matchingIgnoresCaseAndBallerinasEscapes() {
        Assert.assertEquals(names(search("ballerinax__kafka", 1, "COMMIT"), DiscoverResult.Search.Kind.REMOTE),
                List.of("Caller commit", "Caller commitOffset", "Consumer commit", "Consumer commitOffset",
                        "Consumer getCommittedOffset"), "'commit is declared escaped and found plain");
        List<String> scanning = names(search("ballerinax__github", 1, "code\\-scanning"),
                DiscoverResult.Search.Kind.RESOURCES);
        Assert.assertTrue(scanning.contains("Client repos/:owner/:repo/code-scanning/alerts"), scanning.toString());
        Assert.assertTrue(scanning.stream().allMatch(name -> name.contains("code-scanning")), scanning.toString());
    }

    @Test
    public void typesListenersAndReadmeTitlesAreSearchedToo() {
        DiscoverResult.Search listener = search("ballerinax__kafka", 1, "listener");
        Assert.assertEquals(names(listener, DiscoverResult.Search.Kind.LISTENERS), List.of("Listener"));
        DiscoverResult.Search config = search("ballerinax__kafka", 1, "configuration");
        Assert.assertTrue(names(config, DiscoverResult.Search.Kind.TYPES).contains("ProducerConfiguration"),
                config.toString());
        DiscoverResult.Search readme = search("ballerinax__kafka", 1, "serial");
        Assert.assertFalse(names(readme, DiscoverResult.Search.Kind.README).isEmpty(), readme.toString());
        Assert.assertTrue(readme.groups().stream().filter(group -> group.kind() == DiscoverResult.Search.Kind.README)
                .findFirst().orElseThrow().hits().get(0).command().matches("bal discover readme \\d+ -p "
                        + "ballerinax/kafka"));
    }

    @Test
    public void manyMatchesPageAsOneSequenceInKindOrder() {
        DiscoverResult.Search first = search("ballerinax__github", 1, "issue");
        Assert.assertEquals(first.shown(), 40);
        Assert.assertEquals(first.paging().page(), 1);
        Assert.assertEquals(first.next(), "bal discover search issue -p ballerinax/github --page 2");
        Assert.assertEquals(first.groups().get(0).kind(), DiscoverResult.Search.Kind.RESOURCES);
        DiscoverResult.Search last = search("ballerinax__github", first.paging().pages(), "issue");
        Assert.assertNull(last.next());
        Assert.assertEquals(last.groups().get(last.groups().size() - 1).kind(), DiscoverResult.Search.Kind.README);
    }

    @Test
    public void noMatchIsAnAnswerPointingAtTheOverview() {
        DiscoverResult.Search none = search("ballerinax__kafka", 1, "zzz");
        Assert.assertEquals(none.total(), 0);
        String text = TextRenderer.render(none, new TextRenderer.Context("ballerinax/kafka", null,
                List.of("search", "zzz"), null, null, null));
        Assert.assertTrue(text.endsWith("No name in this package holds 'zzz'.\n\nNext: bal discover package "
                + "ballerinax/kafka"),
                text);
        JsonObject json = JsonParser.parseString(JsonRenderer.render(none)).getAsJsonObject();
        Assert.assertEquals(json.get("overview").getAsString(), "bal discover package ballerinax/kafka");
        Assert.assertEquals(json.get("total").getAsInt(), 0);
    }

    @Test
    public void aSearchNeedsAKeywordAndTakesNoFilter() {
        CliTest.Capture none = new CliTest.Capture();
        Assert.assertEquals(Cli.run(List.of("search", "-p", "ballerinax/kafka"), none.streams(), CliTest.never()), 2);
        Assert.assertEquals(none.field("kind"), "validation");
        CliTest.Capture filtered = new CliTest.Capture();
        Assert.assertEquals(Cli.run(List.of("search", "send", "-p", "ballerinax/kafka", "--filter", "x"),
                filtered.streams(), CliTest.never()), 2);
        Assert.assertEquals(filtered.field("command"), "bal discover search send x -p ballerinax/kafka");
    }

    @Test
    public void aFilterOnThePackageItselfSearchesIt() {
        CliTest.Capture filtered = new CliTest.Capture();
        Assert.assertEquals(Cli.run(List.of("package", "ballerinax/kafka", "--filter", "send"), filtered.streams(),
                CliTest.centralFor("ballerinax__kafka", "4.6.5")), 0, filtered.stderr());
        CliTest.Capture searched = new CliTest.Capture();
        Assert.assertEquals(Cli.run(List.of("search", "send", "-p", "ballerinax/kafka"), searched.streams(),
                CliTest.centralFor("ballerinax__kafka", "4.6.5")), 0, searched.stderr());
        Assert.assertEquals(filtered.stdout(), searched.stdout());
    }
}
