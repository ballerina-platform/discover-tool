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

import io.ballerina.tools.discover.model.ClientClass;
import io.ballerina.tools.discover.model.Fn;
import io.ballerina.tools.discover.model.TypeDef;
import io.ballerina.tools.discover.model.TypeRef;
import io.ballerina.tools.discover.render.Identifiers;
import io.ballerina.tools.discover.symbols.Declarations;
import io.ballerina.tools.discover.symbols.PathTree;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;

/**
 * The two indexes, and the discovery corpus.
 *
 * <p>The path corpus at the bottom is built from lookup terms agents actually used, each pinned with its hit
 * count. That makes a regression a reviewable diff, and a ZERO-hit pin impossible to mistake for a working index.
 *
 * @since 0.1.0
 */
public class SymbolsTest {

    /** Real lookups, each with the number of operations it addresses. */
    @DataProvider(name = "recordedLookups")
    public Object[][] recordedLookups() {
        return new Object[][] {
                {"ballerinax__github", "repos", 421},
                {"ballerinax__github", "repos/:owner/:repo", 420},
                {"ballerinax__github", "repos/:owner/:repo/issues", 30},
                {"ballerinax__github", "repos/:owner/:repo/pulls", 31},
                {"ballerinax__github", "repos/:owner/:repo/stargazers", 1},
                {"ballerinax__github", "user", 93},
                {"ballerinax__github", "orgs", 200},
                {"ballerinax__github", "search", 7},
                {"ballerinax__googleapis.gmail", "users", 32},
                {"ballerinax__slack", "chat.postMessage", 1},
                {"ballerinax__slack", "conversations.list", 1},
        };
    }

    private static ClientClass clientOf(String slug) {
        List<ClientClass> clients = FixtureCorpus.libraryFor(slug).clients();
        Assert.assertFalse(clients.isEmpty(), slug + " has no clients");
        return clients.get(0);
    }

    private static ClientClass clientOf(String slug, String name) {
        return FixtureCorpus.libraryFor(slug).clients().stream()
                .filter(candidate -> candidate.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError(slug + " has no client " + name));
    }

    private static PathTree github() {
        return PathTree.build(PathTree.operationsOf(clientOf("ballerinax__github")));
    }

    @Test
    public void aDeclaredSegmentIsSpelledAsTheTreePrintsItWhereverItsRestMarkSits() {
        Assert.assertEquals(PathTree.displayDeclared("'import"), "import");
        Assert.assertEquals(PathTree.displayDeclared("[string owner]"), ":owner");
        for (String rest : List.of("[string... path]", "[string ...path]", "[string...path]")) {
            Assert.assertEquals(PathTree.displayDeclared(rest), ":...path", rest);
        }
    }

    @Test
    public void aTypedWordIsUnescapedPerSegmentAndPlainTextPerWord() {
        Assert.assertEquals(PathTree.plainSelector("gists/'public"), "gists/public");
        Assert.assertEquals(PathTree.plainSelector("code\\-scanning/alerts"), "code-scanning/alerts");
        Assert.assertEquals(Identifiers.plainText("post chat\\.postMessage 'import"), "post chat.postMessage import");
        Assert.assertEquals(Identifiers.plainText("O'Brien"), "O'Brien", "an apostrophe inside a name is part of it");
    }

    @Test
    public void aSegmentReadsInProseWithoutTheEscapingItNeedsInSource() {
        // Central publishes github's paths as Ballerina writes them. Those spellings are right inside a fence
        // and unusable as a shell argument, which is where the agent has to type them.
        Assert.assertEquals(Identifiers.plain("code\\-scanning"), "code-scanning");
        Assert.assertEquals(Identifiers.plain("'import"), "import");
        Assert.assertEquals(Identifiers.plain("app\\-manifests"), "app-manifests");
        Assert.assertEquals(Identifiers.plain("rate_limit"), "rate_limit",
                "an underscore needs no escaping and keeps none");
        Assert.assertEquals(Identifiers.plain("repos"), "repos");
    }

    @Test
    public void aPathIsMatchedOnlyAsTheToolPrintsIt() {
        PathTree tree = github();
        Assert.assertTrue(PathTree.resolve(tree, PathTree.splitPath("repos/:owner/:repo/code-scanning"))
                instanceof PathTree.Resolution.Found);
        for (String spelling : new String[] {"gists/public", "gists/'public", "repos/:owner/:repo/code\\-scanning"}) {
            Assert.assertTrue(PathTree.resolve(tree, PathTree.splitPath(spelling)) instanceof PathTree.Resolution.Found,
                    spelling + ": Ballerina's escape spells the same segment");
        }
        for (String spelling : new String[] {"repos/*/*", "repos/{owner}/{repo}",
                "repos/owner/repo", "repos/[owner]/[repo]", "repos/[string owner]/[string repo]", "Repos",
                "/repos", "repos/"}) {
            Assert.assertTrue(PathTree.resolve(tree, PathTree.splitPath(spelling))
                    instanceof PathTree.Resolution.Missing, spelling);
        }
    }

    @Test
    public void theTreeAccountsForEveryOperationExactlyOnce() {
        for (String slug : new String[] {
                "ballerinax__github", "ballerinax__slack", "ballerinax__googleapis.gmail"}) {
            for (ClientClass client : FixtureCorpus.libraryFor(slug).clients()) {
                List<PathTree.Operation> operations = PathTree.operationsOf(client);
                if (operations.isEmpty()) {
                    continue;
                }
                PathTree tree = PathTree.build(operations);
                Assert.assertEquals(tree.total(), operations.size(), slug + " " + client.name() + ": total");
                Assert.assertEquals(PathTree.operationsUnder(tree).size(), operations.size(),
                        slug + " " + client.name() + ": walk");
            }
        }
    }

    @Test
    public void githubs903OperationsReduceTo36TopLevelSegments() {
        // The measurement the whole discovery argument rests on: a substring search for `repos` returns 484
        // hits, and this is 36 lines.
        PathTree tree = github();
        Assert.assertEquals(tree.total(), 903);
        Assert.assertEquals(tree.children().size(), 36);
        Assert.assertEquals(tree.children().get(0).segment(), "repos");
        Assert.assertEquals(tree.children().get(0).total(), 421,
                "busiest subtree first, because the tree is read to choose");
    }

    @Test
    public void slacksPathsDoNotNestAndTheTreeSaysSo() {
        // 174 operations across 174 distinct top segments: its paths are RPC-style dotted names, so level 1 IS
        // the complete list. An agent told to descend a flat list spends a turn discovering there is nowhere to
        // go.
        PathTree tree = PathTree.build(PathTree.operationsOf(clientOf("ballerinax__slack")));
        Assert.assertEquals(tree.total(), 174);
        Assert.assertEquals(tree.children().size(), 174);
        Assert.assertTrue(tree.children().stream().allMatch(child -> child.children().isEmpty()));
    }

    @Test
    public void childrenAreOrderedByOperationCountThenAlphabetically() {
        PathTree tree = github();
        for (int index = 1; index < tree.children().size(); index++) {
            PathTree previous = tree.children().get(index - 1);
            PathTree current = tree.children().get(index);
            boolean ordered = previous.total() > current.total()
                    || (previous.total() == current.total()
                            && Texts.compareLocale(previous.segment(), current.segment()) <= 0);
            Assert.assertTrue(ordered, previous.segment() + "(" + previous.total() + ") before "
                    + current.segment() + "(" + current.total() + ")");
        }
    }

    @Test
    public void aPathIsMatchedFromTheFirstSegmentNeverAsASuffix() {
        PathTree tree = github();
        PathTree.Resolution.Found anchored =
                (PathTree.Resolution.Found) PathTree.resolve(tree, PathTree.splitPath("repos/:owner/:repo"));
        Assert.assertEquals(anchored.node().operations().size(), 3);

        // The counter-measurements, which are why this walks the tree instead of filtering strings. Two
        // unrelated subtrees END in the same three segments, so a SUFFIX match returns nine operations for a
        // path that has three — and a SUBSTRING match returns 426.
        List<PathTree.Operation> all = PathTree.operationsUnder(tree);
        String target = "repos/:owner/:repo";
        List<String> bySuffix = all.stream()
                .map(operation -> String.join("/", operation.segments()))
                .filter(path -> path.endsWith(target))
                .toList();
        long bySubstring = all.stream()
                .map(operation -> String.join("/", operation.segments()))
                .filter(path -> path.contains(target))
                .count();
        Assert.assertEquals(bySuffix.size(), 9);
        Assert.assertEquals(bySubstring, 426);

        List<String> strangers = bySuffix.stream()
                .filter(path -> !path.startsWith(target))
                .distinct()
                .sorted()
                .toList();
        Assert.assertEquals(strangers, List.of(
                "orgs/:org/teams/:teamSlug/repos/:owner/:repo",
                "teams/:teamId/repos/:owner/:repo"),
                "these two are what an unanchored match mixes in, and they are about team access");
    }

    @Test
    public void aPathThatRunsOutNamesTheSegmentsThatMatchedBeforeIt() {
        PathTree.Resolution resolution =
                PathTree.resolve(github(), PathTree.splitPath("repos/:owner/:repo/nonesuch"));
        Assert.assertEquals(((PathTree.Resolution.Missing) resolution).matched(), List.of("repos", ":owner", ":repo"));
        Assert.assertEquals(((PathTree.Resolution.Missing) PathTree.resolve(github(),
                PathTree.splitPath("repos/actions/runs"))).matched(), List.of("repos"),
                "a path that leaves out its parameter segments stops where they were left out");
    }

    @Test
    public void aFirstSegmentThatIsWrongReportsNothingMatched() {
        // Not a partial path.
        PathTree.Resolution resolution = PathTree.resolve(github(), PathTree.splitPath("nonesuch/repos"));
        Assert.assertEquals(((PathTree.Resolution.Missing) resolution).matched(), List.of());
        Assert.assertEquals(((PathTree.Resolution.Missing) PathTree.resolve(github(), PathTree.splitPath("caches")))
                .matched(), List.of(), "a segment deep inside a path is not found from the top");
    }

    @Test
    public void theRootResourceIsTheSegmentADot() {
        PathTree.Resolution resolution = PathTree.resolve(github(), PathTree.splitPath("."));
        Assert.assertEquals(((PathTree.Resolution.Found) resolution).node().operations().size(), 1);
        Assert.assertEquals(PathTree.join(List.of("repos", ":owner")), "repos/:owner");
    }

    @Test
    public void aDuplicateNameKeepsTheFirstDeclaration() {
        // So a collision cannot pass unnoticed.
        Declarations index = Declarations.index(List.of(
                new TypeDef.ErrorDef("ClientError", "the correction", false, Optional.empty()),
                new TypeDef.Alias("ClientError", "Central's placeholder", new TypeRef(""))));
        Assert.assertEquals(index.names(), List.of("ClientError"));
        Assert.assertTrue(index.get("ClientError") instanceof TypeDef.ErrorDef);
    }

    @Test
    public void githubsDeclarationRosterIs1227NamesAndItsOperationsAreNotInIt() {
        Declarations index = Declarations.index(FixtureCorpus.libraryFor("ballerinax__github").typeDefs());
        // Including github's three `anyDataTypes` declarations.
        Assert.assertEquals(index.names().size(), 1227);
        // Operations are addressed by path, not by name, which is why the oracle's bijection is scoped to
        // declarations.
        Assert.assertNull(index.get("repos"));
    }

    @Test(dataProvider = "recordedLookups")
    public void theRecordedLookupResolves(String fixture, String path, int operations) {
        PathTree tree = PathTree.build(PathTree.operationsOf(clientOf(fixture)));
        PathTree.Resolution resolution = PathTree.resolve(tree, PathTree.splitPath(path));
        Assert.assertTrue(resolution instanceof PathTree.Resolution.Found, path + " is unreachable");
        int found = PathTree.operationsUnder(((PathTree.Resolution.Found) resolution).node()).size();
        Assert.assertEquals(found, operations, path + " moved from " + operations + " to " + found);
        Assert.assertTrue(found > 0, "a zero-hit pin cannot masquerade as a working index");
    }

    @Test
    public void everyOperationInTheCorpusCarriesAReturnType() {
        // The return type is what a lookup usually comes for; a signature rendered without one is silently
        // useless.
        for (String slug : new String[] {
                "ballerinax__github", "ballerinax__slack", "ballerinax__googleapis.gmail"}) {
            for (ClientClass client : FixtureCorpus.libraryFor(slug).clients()) {
                for (Fn fn : client.functions()) {
                    Assert.assertFalse(fn.returns().type().name().isEmpty(),
                            slug + " " + client.name() + ": a signature with no return type");
                }
            }
        }
    }

    @Test
    public void httpsFourResourceClientsEachCarrySeven() {
        // Several clients carry the same resource paths, so a path alone never picks a client.
        for (String name : new String[] {"Client", "FailoverClient", "LoadBalanceClient", "StatusCodeClient"}) {
            Assert.assertEquals(PathTree.operationsOf(clientOf("ballerina__http", name)).size(), 7, name);
        }
    }
}
