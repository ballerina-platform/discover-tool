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

import java.util.List;
import java.util.stream.Collectors;

/**
 * The handler methods one service type of a package declares it accepts, as the package's own trigger metadata
 * describes them — what the docs payload cannot say for a service type that is only a marker
 * ({@code distinct service object {}}).
 *
 * @param handlers every method a service of this type may define, in the order the package lists them
 * @param rules constraints over those methods that are not visible in any one of them
 * @since 0.1.0
 */
public record ServiceHandlers(List<Handler> handlers, List<Rule> rules) {

    /** What the package writes for "any name" and "any accessor". */
    public static final String ANY = "*";

    public ServiceHandlers {
        handlers = List.copyOf(handlers);
        rules = List.copyOf(rules);
    }

    /** Whether a handler is a {@code remote} method or a {@code resource} method. */
    public enum Kind {
        REMOTE("remote"),
        RESOURCE("resource");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * @param name the method name, or {@code null} for a shape the user names every instance of
     * @param kind remote or resource
     * @param required whether a service must define it
     * @param repeatable whether the service may define it any number of times, under names of its own
     * @param accessors for a resource handler, the accessors it accepts ({@code *} for any); empty otherwise
     * @param doc what it is for and when it fires
     * @param deprecated why it should not be used, or {@code null}
     * @param params its parameters, in declaration order
     * @param returns its return type, or {@code null} when it declares none
     * @param id the package's own id for it, last segment only ({@code query}): what a {@link Rule} names a handler
     *     with no {@code name} by
     */
    public record Handler(String name, Kind kind, boolean required, boolean repeatable, List<String> accessors,
            String doc, String deprecated, List<Param> params, String returns, String id) {

        public Handler {
            accessors = List.copyOf(accessors);
            params = List.copyOf(params);
        }

        /** How a service would declare it: {@code remote function onError(kafka:Error err) returns error?}. */
        public String signature() {
            String declared = kind == Kind.RESOURCE
                    ? "resource function " + (accessors.isEmpty() || accessors.contains(ANY)
                            ? "<accessor>" : String.join("|", accessors)) + " <path>"
                    : "remote function " + (name == null ? "<name>" : name);
            String parameters = params.stream()
                    .map(param -> param.type() + " " + (param.name() == null ? "<name>" : param.name()))
                    .collect(Collectors.joining(", "));
            return declared + "(" + parameters + ")" + (returns == null ? "" : " returns " + returns);
        }
    }

    /**
     * @param name the parameter name, or {@code null} for a slot the user names every instance of
     * @param type its type, spelled as Ballerina source with the module prefix on the package's own types
     * @param required whether the handler must declare it
     * @param repeatable whether the handler may declare it any number of times
     * @param doc what it carries
     * @param deprecated why it should not be used, or {@code null}
     */
    public record Param(String name, String type, boolean required, boolean repeatable, String doc,
            String deprecated) { }

    /**
     * @param requirement the constraint, in words — {@code exactly one of}, {@code at least one of}, …
     * @param handlers the handlers it ranges over, by name, or by {@link Handler#id()} for one with no name; empty
     *     when it ranges over other things
     * @param message the package's own wording, or {@code null}
     */
    public record Rule(String requirement, List<String> handlers, String message) {

        public Rule {
            handlers = List.copyOf(handlers);
        }
    }
}
