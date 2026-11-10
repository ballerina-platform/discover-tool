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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What an inline record's members declare that Central's docs payload drops: whether each is optional and what it
 * defaults to (ballerina-lang#44782).
 *
 * <p>An inline record has no name to look up by, so it is identified by the names of its fields. A set of names two
 * different declarations share with different answers is ambiguous and recovers nothing.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface InlineRecords {

    /** No source was read: every inline record stays as the payload describes it. */
    InlineRecords NONE = names -> Optional.empty();

    /**
     * @param optional whether the field is declared with {@code ?}
     * @param defaultValue the default, as source text, or {@code null}
     */
    record Member(boolean optional, String defaultValue) { }

    /** The members by field name for the inline record declaring exactly {@code fieldNames}, when known. */
    Optional<Map<String, Member>> recover(Collection<String> fieldNames);

    /**
     * Inline records as a module's source declares them.
     *
     * @param byFields each record's members by name, keyed by its sorted field names
     */
    record Declared(Map<List<String>, Map<String, Member>> byFields) implements InlineRecords {

        public Declared {
            byFields = Collections.unmodifiableMap(new HashMap<>(byFields));
        }

        @Override
        public Optional<Map<String, Member>> recover(Collection<String> fieldNames) {
            return Optional.ofNullable(byFields.get(key(fieldNames)));
        }

        public static List<String> key(Collection<String> fieldNames) {
            List<String> sorted = new ArrayList<>(fieldNames);
            Collections.sort(sorted);
            return sorted;
        }
    }
}
