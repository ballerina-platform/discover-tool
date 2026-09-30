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
import com.google.gson.JsonObject;
import io.ballerina.tools.discover.central.schema.CentralDocs;
import io.ballerina.tools.discover.central.schema.Schema;
import io.ballerina.tools.discover.model.FromCentral;
import io.ballerina.tools.discover.views.Readmes;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.Optional;

/**
 * The resolved module's own readme: that it is there, and that it is passed through untouched.
 *
 * @since 0.1.0
 */
public class ReadmeTest {

    @DataProvider(name = "fixtures")
    public Object[][] fixtures() {
        return FixtureCorpus.fixtureRows();
    }

    /**
     * One module of an assembled payload. A {@code null} readme means the key is absent entirely.
     *
     * @param id the module's name
     * @param readme the module's readme, or {@code null} to omit the key entirely
     */
    private record Stub(String id, String readme) { }

    /**
     * One module's worth of payload, built by taking a real module and replacing only its readme — the schema
     * requires every array, so a hand-written stub could not be parsed.
     */
    private static CentralDocs.Module moduleWith(Stub stub) {
        JsonObject template = FixtureCorpus.loadRawFixture("ballerinax__kafka")
                .getAsJsonObject().getAsJsonObject("docsData").getAsJsonArray("modules")
                .get(0).getAsJsonObject();

        JsonObject entry = template.deepCopy().getAsJsonObject();
        entry.addProperty("id", stub.id());
        if (stub.readme() == null) {
            entry.remove("description");
        } else {
            entry.addProperty("description", stub.readme());
        }
        JsonArray built = new JsonArray();
        built.add(entry);
        JsonObject docsData = new JsonObject();
        docsData.add("modules", built);
        JsonObject wrapper = new JsonObject();
        wrapper.add("docsData", docsData);

        Result<CentralDocs> parsed = Schema.parse(wrapper, "assembled");
        Assert.assertTrue(parsed.isOk(), parsed.isOk() ? "" : parsed.failure().describe());
        return parsed.value().modules().get(0);
    }

    @Test(dataProvider = "fixtures")
    public void everyPackageInTheCorpusPublishesAReadme(String slug) {
        // The readme bucket leans on this: it is most packages' largest section and the answer to "how is this
        // used". A fixture without one would make its own tests pass for the wrong reason.
        Result<CentralDocs.Module> module =
                FromCentral.selectModule(FixtureCorpus.loadFixture(slug), FixtureCorpus.qualifiedForSlug(slug));
        Assert.assertTrue(module.isOk());
        Optional<String> readme = Readmes.of(module.value());
        Assert.assertTrue(readme.isPresent(), slug + " publishes no readme");
        Assert.assertTrue(readme.get().length() > 500, slug + "'s readme is suspiciously short");
    }

    @Test
    public void theReadmeIsPassedThroughUntouchedOnlyTrimmed() {
        Optional<String> readme = Readmes.of(moduleWith(new Stub("kafka", "\n\n## Overview\n\nbody\n\n")));
        Assert.assertEquals(readme, Optional.of("## Overview\n\nbody"));
    }

    @Test
    public void aModuleWithoutAReadmeIsEmpty() {
        Assert.assertEquals(Readmes.of(moduleWith(new Stub("a", "   "))), Optional.empty());
        Assert.assertEquals(Readmes.of(moduleWith(new Stub("b", null))), Optional.empty());
        Assert.assertEquals(Readmes.of(moduleWith(new Stub("c", "real"))), Optional.of("real"));
    }
}
