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
 * {@link Texts#shellWord}: every argument a printed command carries reads back as itself in a POSIX shell.
 *
 * @since 0.1.0
 */
public class TextsTest {

    @Test
    public void aSafeWordIsLeftBare() {
        for (String word : List.of("repos/:owner/:repo", "get", "chat.postMessage", ":...path", "code-scanning")) {
            Assert.assertEquals(Texts.shellWord(word), word);
        }
    }

    @Test
    public void globAndShellMetacharactersAreQuoted() {
        for (String word : List.of("repos/*", "a?", "[x]", "a;b", "a|b", "a&b", "a<b", "a>b", "(a)", "{a}",
                "#a", "~a", "a b", "gists/'public", "=ls", "a@b", "@a", "a,b")) {
            Assert.assertEquals(Texts.shellWord(word), "\"" + word + "\"", word);
        }
    }

    @Test
    public void anEqualsSignPastTheFirstCharacterIsLeftBare() {
        Assert.assertEquals(Texts.shellWord("a=b"), "a=b");
    }

    @Test
    public void whatDoubleQuotesWouldStillExpandIsSingleQuoted() {
        Assert.assertEquals(Texts.shellWord("$HOME"), "'$HOME'");
        Assert.assertEquals(Texts.shellWord("a`b`"), "'a`b`'");
        Assert.assertEquals(Texts.shellWord("say \"hi\""), "'say \"hi\"'");
        Assert.assertEquals(Texts.shellWord("it's $x"), "'it'\\''s $x'");
    }
}
