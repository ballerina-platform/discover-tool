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
import java.util.Optional;

/**
 * What a Ballerina package's public API is, independent of how Central describes it or how it gets
 * rendered.
 *
 * <p>The pipeline is fetch → parse → transform → render and no stage mutates its input, which is why
 * every collection here is an immutable {@code List}.
 *
 * @param name {@code org/module-id} — the string an {@code import} statement takes
 * @param description the module's own documentation, verbatim
 * @param typeDefs every module-level type declaration
 * @param clients every client class
 * @param functions every standalone function
 * @param listeners every listener, held apart from {@code typeDefs} — see {@link #declarations()}
 * @param services every service type, paired with the listener it binds to
 * @param annotations every annotation the module declares
 * @param configurables every {@code configurable} the module declares
 * @since 0.1.0
 */
public record Library(
        String name,
        String description,
        List<TypeDef> typeDefs,
        List<ClientClass> clients,
        List<Fn.Standalone> functions,
        List<TypeDef.ObjectDef> listeners,
        List<Service> services,
        List<AnnotationDef> annotations,
        List<Configurable> configurables) {

    /**
     * A {@code configurable} the package declares, which a DEPLOYER sets and a caller cannot reference.
     *
     * <p>Held apart from {@link #typeDefs()} because a {@code configurable} is module-private: Central publishes
     * it anyway, but {@code http:maxActiveConnections} from another module is
     * {@code attempt to refer to non-accessible symbol}. So it is rendered as "set this in {@code Config.toml}",
     * never as a declaration to copy, and must never gain the blanket {@code public} the other declarations carry.
     *
     * @param name the configurable's name
     * @param description its own documentation, verbatim
     * @param type its declared type
     * @param defaultValue the default Central publishes, as source text
     */
    public record Configurable(String name, String description, TypeRef type, String defaultValue) { }

    /**
     * An annotation the module declares, with the two facts that make one usable.
     *
     * <p>{@code attachmentPoints} is Central's own comma-separated clause, carried through rather than mapped:
     * checked against published sources, the string IS the source's {@code on} clause, down to the order —
     * {@code "record field, parameter, return"} for {@code graphql:ID}, {@code "service, type"} for
     * {@code http:ServiceConfig}.
     *
     * <p>{@code type} is the record an attachment's argument must be, absent for the marker annotations that
     * take none. Without it {@code @http:ResourceConfig { … }} has no discoverable field set, even though the
     * record is declared in the same document.
     *
     * @param name the annotation's name
     * @param description its own documentation, verbatim
     * @param type the record its attachment's argument must be, or empty for a marker annotation
     * @param attachmentPoints Central's own {@code on} clause, verbatim
     */
    public record AnnotationDef(
            String name, String description, Optional<TypeRef> type, String attachmentPoints) { }

    /**
     * Every declaration the document contains, by name.
     *
     * <p>Listeners are held apart from {@link #typeDefs()} only because they print in their own section; a
     * signature's type closure resolves them like any other declaration.
     */
    public List<TypeDef> declarations() {
        List<TypeDef> all = new java.util.ArrayList<>(typeDefs);
        all.addAll(listeners);
        return List.copyOf(all);
    }

    /**
     * Every declaration a caller can address by name, clients included.
     *
     * <p>Separate from {@link #declarations()} because the API document prints clients in their own section, so
     * folding them in would print them twice, while resolving a name has to find a client.
     */
    public List<TypeDef> addressable() {
        List<TypeDef> all = new java.util.ArrayList<>(declarations());
        clients.forEach(client -> all.add(client.asObjectDef()));
        return List.copyOf(all);
    }

    public Library withTypeDefs(List<TypeDef> replacement) {
        return new Library(name, description, replacement, clients, functions, listeners, services,
                annotations, configurables);
    }

    public Library withClients(List<ClientClass> replacement) {
        return new Library(name, description, typeDefs, replacement, functions, listeners, services,
                annotations, configurables);
    }

    public Library withServices(List<Service> replacement) {
        return new Library(name, description, typeDefs, clients, functions, listeners, replacement,
                annotations, configurables);
    }

    public Library withName(String replacement) {
        return new Library(replacement, description, typeDefs, clients, functions, listeners, services,
                annotations, configurables);
    }
}
