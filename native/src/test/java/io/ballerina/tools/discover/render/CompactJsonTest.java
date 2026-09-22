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

package io.ballerina.tools.discover.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * The RFC's compact-but-array-per-line JSON shape: object fields inline, array elements broken one per line —
 * syntactically identical to fully-indented JSON either way, which is what every assertion here checks by
 * round-tripping through a real parser rather than comparing strings alone.
 *
 * @since 0.1.0
 */
public class CompactJsonTest {

    @Test
    public void anEmptyObjectAndAnEmptyArrayStayOnOneLine() {
        Assert.assertEquals(CompactJson.write(new JsonObject()), "{}");
        Assert.assertEquals(CompactJson.write(new JsonArray()), "[]");
    }

    @Test
    public void objectFieldsStayInlineOnOneLine() {
        JsonObject json = new JsonObject();
        json.addProperty("shown", 15);
        json.addProperty("total", 199);
        String text = CompactJson.write(json);
        Assert.assertEquals(text.lines().count(), 1, text);
        Assert.assertEquals(text, "{\"shown\":15,\"total\":199}");
    }

    @Test
    public void arrayElementsGoOnePerLine() {
        JsonArray array = new JsonArray();
        array.add("get");
        array.add("post");
        array.add("delete");
        String text = CompactJson.write(array);
        Assert.assertEquals(text, "[\n\"get\",\n\"post\",\n\"delete\"\n]");
    }

    @Test
    public void anArrayOfObjectsBreaksTheArrayNotTheObjects() {
        JsonArray array = new JsonArray();
        JsonObject repos = new JsonObject();
        repos.addProperty("name", "repos");
        repos.addProperty("count", 275);
        JsonObject orgs = new JsonObject();
        orgs.addProperty("name", "orgs");
        orgs.addProperty("count", 124);
        array.add(repos);
        array.add(orgs);

        String text = CompactJson.write(array);
        Assert.assertEquals(text, "[\n{\"name\":\"repos\",\"count\":275},\n{\"name\":\"orgs\",\"count\":124}\n]");
    }

    @Test
    public void stringsAreEscapedCorrectly() {
        JsonObject json = new JsonObject();
        json.addProperty("message", "a \"quoted\" value\nwith a newline");
        String text = CompactJson.write(json);
        // The whole point: whatever escaping Gson's own writer uses is what a caller's parser already handles.
        JsonObject parsed = JsonParser.parseString(text).getAsJsonObject();
        Assert.assertEquals(parsed.get("message").getAsString(), "a \"quoted\" value\nwith a newline");
    }

    @Test
    public void theResultParsesBackToTheSameTree() {
        JsonObject json = new JsonObject();
        JsonArray methods = new JsonArray();
        methods.add("get");
        methods.add("post");
        json.add("methods", methods);
        json.addProperty("shown", 2);
        json.addProperty("total", 2);

        String text = CompactJson.write(json);
        Assert.assertEquals(JsonParser.parseString(text), json, "a parser sees no difference from fully-indented JSON");
    }

    @Test
    public void nullIsWrittenAsTheJsonLiteral() {
        Assert.assertEquals(CompactJson.write(com.google.gson.JsonNull.INSTANCE), "null");
    }
}
