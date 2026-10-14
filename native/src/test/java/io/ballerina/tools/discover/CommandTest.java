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

import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.TextRenderer;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;

/**
 * Every printed command is built by {@link Command}: each typed word quoted as the shell needs, a placeholder never,
 * and the text read back word for word.
 *
 * @since 0.1.0
 */
public class CommandTest {

    private static final Command.Target KAFKA = new Command.Target("ballerinax/kafka", null);

    @Test
    public void aTypedNameThatLooksLikeAPlaceholderIsQuoted() {
        Assert.assertEquals(Command.of(Command.SEARCH, "<name>").in(KAFKA).text(),
                "bal discover search \"<name>\" -p ballerinax/kafka");
        Assert.assertEquals(Command.of(Command.SEARCH, Command.placeholder("<name>")).in(KAFKA).text(),
                "bal discover search <name> -p ballerinax/kafka");
    }

    @Test
    public void thePageIsWrittenLastAndOnlyPastTheFirst() {
        Command listing = Command.of(Command.METHOD).in(KAFKA).ownedBy(Command.CLIENT, "Producer").filtered("send");
        Assert.assertEquals(listing.paged(Command.FIRST_PAGE).text(),
                "bal discover method -p ballerinax/kafka --client Producer --filter send");
        Assert.assertEquals(listing.paged(2).text(),
                "bal discover method -p ballerinax/kafka --client Producer --filter send --page 2");
    }

    @DataProvider(name = "printed")
    public Object[][] printed() {
        return new Object[][] {
                {Command.overview(new Command.Target("ballerinax/kafka:4.6.6", "sub"))},
                {Command.of(Command.RESOURCE, "gists/public", "get").in(KAFKA).ownedBy(Command.CLIENT, "Client")},
                {Command.of(Command.README, "Data serialization").in(KAFKA).filtered("it's \"quoted\"").paged(3)},
                {Command.of(Command.SEARCH, "a b", "$HOME")},
                {Command.of(Command.METHOD, "foo").in(new Command.Target("a/b", "foo")).ownedBy(Command.CLASS, "C")}};
    }

    @Test(dataProvider = "printed")
    public void aPrintedCommandReadsBackAsItself(Command command) {
        Assert.assertEquals(Command.parse(command.text()).orElseThrow(), command, command.text());
    }

    @Test
    public void textThisToolDidNotPrintDoesNotParse() {
        Assert.assertTrue(Command.parse("bal pull ballerinax/kafka").isEmpty());
        Assert.assertTrue(Command.parse("bal discover method -p").isEmpty());
        Assert.assertTrue(Command.parse("bal discover client --page two -p a/b").isEmpty());
    }

    @Test
    public void aNextPatternSwapsTheMemberNameNotAFlagValueThatSpellsTheSame() {
        Command.Target sub = new Command.Target("ballerinax/kafka", "foo");
        List<DiscoverResult.Method> methods = List.of("bar", "foo").stream()
                .map(name -> new DiscoverResult.Method(name, Command.of(Command.METHOD, name).in(sub)
                        .ownedBy(Command.CLIENT, "C").text()))
                .toList();
        String text = TextRenderer.render(new DiscoverResult.MethodList("C", methods, false, 2, 2, null, null,
                DiscoverResult.Documented.NONE, null, null), TextRenderer.Context.NONE);
        Assert.assertTrue(text.contains("Next: bal discover method <name> -p ballerinax/kafka --client C --module foo"),
                text);
        Assert.assertFalse(text.contains("--module <name>"), text);
    }
}
