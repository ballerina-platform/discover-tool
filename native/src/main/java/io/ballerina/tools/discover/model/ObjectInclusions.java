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

package io.ballerina.tools.discover.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which types each object type of one module includes ({@code *Service;}), as its published source writes them —
 * a bare name for the module's own type, {@code prefix:Name} for another module's.
 *
 * <p>The docs payload publishes no inclusion for a service object type, and inclusion is the only way a
 * {@code distinct service object} type becomes a subtype of another — so this is what tells
 * {@code http:InterceptableService} (which writes {@code *Service;}) apart from {@code http:RequestInterceptor}
 * (which does not), two declarations the payload renders identically.
 *
 * @param byType each object type that includes something, by name, to what it includes, in source order
 * @since 0.1.0
 */
public record ObjectInclusions(Map<String, List<String>> byType) {

    public ObjectInclusions {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        byType.forEach((type, included) -> copy.put(type, List.copyOf(included)));
        byType = java.util.Collections.unmodifiableMap(copy);
    }

    /**
     * Everything {@code type} includes, directly or through a type of this module it includes — never
     * {@code type} itself, and safe against an inclusion cycle.
     */
    public Set<String> closure(String type) {
        Set<String> reached = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(byType.getOrDefault(type, List.of()));
        while (!pending.isEmpty()) {
            String next = pending.pop();
            if (!next.equals(type) && reached.add(next)) {
                pending.addAll(byType.getOrDefault(next, List.of()));
            }
        }
        return reached;
    }
}
