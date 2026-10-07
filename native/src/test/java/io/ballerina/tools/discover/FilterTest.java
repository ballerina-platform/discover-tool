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

import io.ballerina.tools.discover.model.Fn;
import io.ballerina.tools.discover.model.Param;
import io.ballerina.tools.discover.model.ReturnDef;
import io.ballerina.tools.discover.model.TypeRef;
import io.ballerina.tools.discover.symbols.Filter;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

/**
 * {@code --filter}: one case-insensitive substring of the whole keyword, against an entry's surface first and its
 * documentation second.
 *
 * @since 0.1.0
 */
public class FilterTest {

    private static final Fn.Resource RUN = new Fn.Resource("get",
            List.of(new Fn.PathSegment.Literal("actions"), new Fn.PathSegment.Literal("runs"),
                    new Fn.PathSegment.Parameter("string", "run_id")),
            "Get a workflow run.",
            List.of(new Param("run_id", "The run.", new TypeRef("string"), null, Param.Form.NORMAL)),
            new ReturnDef(new TypeRef("WorkflowRun|error"), null), false, true);

    private static final Fn.Resource GIST = new Fn.Resource("get",
            List.of(new Fn.PathSegment.Literal("gists"), new Fn.PathSegment.Literal("'public")),
            "List public gists.", List.of(), ReturnDef.none(), false, true);

    private static final Fn.Remote SEND = new Fn.Remote("sendMessage", "Posts a workflow run summary.",
            List.of(), ReturnDef.none(), false, true);

    private static Filter.Split<Fn> filter(String keyword) {
        return Filter.apply(keyword, List.of(RUN, GIST, SEND), Filter::surfaceOf, Filter::docsOf);
    }

    @Test
    public void aKeywordMatchesASegmentANameOrAType() {
        Assert.assertEquals(filter("runs").surface(), List.of(RUN));
        Assert.assertEquals(filter("MESSAGE").surface(), List.of(SEND));
        Assert.assertEquals(filter("WorkflowRun").surface(), List.of(RUN));
    }

    @Test
    public void aKeywordWrittenAsAPathMatchesThePath() {
        Assert.assertEquals(filter("actions/runs").surface(), List.of(RUN));
        Assert.assertEquals(filter("runs/:run_id").surface(), List.of(RUN));
        Assert.assertEquals(filter("gists/'public").surface(), List.of(GIST));
        Assert.assertTrue(filter("runs/actions").isEmpty());
    }

    @Test
    public void aDocumentationOnlyMatchIsNamedApart() {
        Filter.Split<Fn> split = filter("workflow run");
        Assert.assertEquals(split.surface(), List.of());
        Assert.assertEquals(split.documented(), List.of(RUN, SEND));
        Assert.assertEquals(split.total(), 2);
    }

    @Test
    public void aBlankKeywordKeepsEverything() {
        Assert.assertEquals(filter(" ").surface(), List.of(RUN, GIST, SEND));
        Assert.assertTrue(Filter.matches(null, "anything"));
    }
}
