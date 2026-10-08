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
import com.google.gson.JsonPrimitive;
import io.ballerina.tools.discover.central.schema.CentralDocs;
import io.ballerina.tools.discover.central.schema.Schema;
import io.ballerina.tools.discover.constructs.Decl;
import io.ballerina.tools.discover.constructs.Node;
import io.ballerina.tools.discover.constructs.Payload;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.util.List;

/**
 * Schema drift is reported once per cause, at the path where the payload went wrong.
 *
 * @since 0.1.0
 */
public class SchemaTest {

    private static final String FIELD = "docsData.modules.0.records.0.fields.0";

    private static JsonObject withOneField() {
        return Payload.pkg().with("records", Decl.record("Row", Decl.field("id", Node.builtin("string")))).raw();
    }

    private static JsonObject field(JsonObject raw) {
        return raw.getAsJsonObject("docsData").getAsJsonArray("modules").get(0).getAsJsonObject()
                .getAsJsonArray("records").get(0).getAsJsonObject().getAsJsonArray("fields").get(0)
                .getAsJsonObject();
    }

    private static List<Failure.SchemaIssue> issues(JsonObject raw) {
        Result<CentralDocs> parsed = Schema.parse(raw, "test/pkg", null);
        Assert.assertFalse(parsed.isOk(), "expected drift");
        return ((Failure.SchemaDrift) parsed.failure()).issues();
    }

    @Test
    public void aMissingRequiredObjectIsOneIssue() {
        JsonObject raw = withOneField();
        field(raw).remove("type");
        Assert.assertEquals(issues(raw), List.of(
                new Failure.SchemaIssue(FIELD + ".type", "expected an object, received nothing")));
    }

    @Test
    public void aRequiredObjectOfTheWrongKindIsOneIssueNotOnePerKeyItLacks() {
        JsonObject raw = withOneField();
        field(raw).addProperty("type", "string");
        Assert.assertEquals(issues(raw), List.of(
                new Failure.SchemaIssue(FIELD + ".type", "expected an object, received a string")));
    }

    @Test
    public void anArrayElementThatIsNoObjectIsOneIssue() {
        JsonObject raw = withOneField();
        raw.getAsJsonObject("docsData").getAsJsonArray("modules").get(0).getAsJsonObject()
                .getAsJsonArray("records").add(new JsonPrimitive(7));
        Assert.assertEquals(issues(raw), List.of(
                new Failure.SchemaIssue("docsData.modules.0.records.1", "expected an object, received a number")));
    }

    @Test
    public void anArrayDimensionCountIsAWholeNumberInRange() {
        for (String count : List.of("1000000000", "-1", "2.5", "1e300")) {
            JsonObject raw = withOneField();
            field(raw).getAsJsonObject("type").add("arrayDimensions", new JsonPrimitive(new BigDecimal(count)));
            List<Failure.SchemaIssue> issues = issues(raw);
            Assert.assertEquals(issues.size(), 1, count + " -> " + issues);
            Assert.assertEquals(issues.get(0).path(), FIELD + ".type.arrayDimensions");
            Assert.assertTrue(issues.get(0).message().startsWith("expected a whole number from 0 to 255"),
                    issues.get(0).message());
        }

        JsonObject raw = withOneField();
        field(raw).getAsJsonObject("type").addProperty("arrayDimensions", 2);
        Result<CentralDocs> parsed = Schema.parse(raw, "test/pkg", null);
        Assert.assertTrue(parsed.isOk(), parsed.isOk() ? "" : parsed.failure().describe());
        CentralDocs.Field.Declared id = (CentralDocs.Field.Declared)
                parsed.value().modules().get(0).records().get(0).declaredFields().get(0);
        Assert.assertEquals(id.type().arrayDimensions(), 2);
    }
}
