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

import io.ballerina.tools.discover.constructs.Decl;
import io.ballerina.tools.discover.constructs.Payload;
import io.ballerina.tools.discover.model.Library;
import io.ballerina.tools.discover.model.ObjectInclusions;
import io.ballerina.tools.discover.model.Pipeline;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.symbols.Surface;
import io.ballerina.tools.discover.views.Containers;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The service roster pages like every other listing: the bindable service types and the ones no listener accepts
 * are one listing laid end to end, so together they stay under the ceiling and the page command reaches both.
 *
 * @since 0.1.0
 */
public class ServiceRosterTest {

    private static final int BOUND = 45;

    private static final int INTERCEPTORS = 5;

    private static LoadedPackage manyServiceTypes() {
        Payload payload = Payload.pkg().with("listeners", Decl.listenerAttaching("Service", "Listener"))
                .with("serviceTypes", Decl.serviceType("Service"));
        Map<String, List<String>> inclusions = new LinkedHashMap<>();
        for (int index = 1; index < BOUND; index++) {
            payload.with("serviceTypes", Decl.serviceType("Bound" + index));
            inclusions.put("Bound" + index, List.of("Service"));
        }
        for (int index = 1; index <= INTERCEPTORS; index++) {
            payload.with("serviceTypes", Decl.serviceType("Interceptor" + index));
        }
        Library library = Pipeline.build(payload.module(), Optional.of(new ObjectInclusions(inclusions)));
        return new LoadedPackage(QualifiedName.parse("test/pkg").value(), FixtureCorpus.FIXTURE_VERSION, library,
                Optional.empty(), null, List.of(), null);
    }

    private static DiscoverResult.ContainerRoster page(int number) {
        Result<DiscoverResult> answer = Containers.render(manyServiceTypes(), Surface.Scope.SERVICE,
                new Containers.Options(List.of(), null, number));
        Assert.assertTrue(answer.isOk(), answer.isOk() ? "" : answer.failure().describe());
        return (DiscoverResult.ContainerRoster) answer.value();
    }

    @Test
    public void bindableAndNotAttachableShareOnePagedListing() {
        DiscoverResult.ContainerRoster first = page(1);
        Assert.assertEquals(first.containers().size(), Containers.MAX_ENTRIES);
        Assert.assertEquals(first.total(), BOUND);
        Assert.assertTrue(first.notAttachable().isEmpty());
        Assert.assertEquals(first.notAttachableTotal(), INTERCEPTORS);
        Assert.assertEquals(first.next(), "bal discover test/pkg service --page 2");

        DiscoverResult.ContainerRoster second = page(2);
        Assert.assertEquals(second.containers().size(), BOUND - Containers.MAX_ENTRIES);
        Assert.assertEquals(second.notAttachable().stream().map(DiscoverResult.ContainerRoster.NotAttachable::name)
                .toList(), List.of("Interceptor1", "Interceptor2", "Interceptor3", "Interceptor4", "Interceptor5"));
        Assert.assertNull(second.next());
        Assert.assertEquals(second.paging().remaining(), 0);
    }

    @Test
    public void aPagePastBothSectionsIsRejected() {
        Assert.assertFalse(Containers.render(manyServiceTypes(), Surface.Scope.SERVICE,
                new Containers.Options(List.of(), null, 3)).isOk());
    }
}
