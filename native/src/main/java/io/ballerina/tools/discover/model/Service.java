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
 * How a package expects a service to be written against it: the exact {@code service X on new Y(...)} block,
 * plus the remote contract the block must implement.
 *
 * <p>Derived from the package's own listeners and service types, and only from those. There used to be a
 * second shape here — a comment block carrying free-text instructions — attached by a patch for the few
 * packages whose service story Central was believed not to describe. It described them all along: http
 * publishes one listener and seven {@code distinct service object} types, graphql one and two. The patch
 * replaced them with three comment lines, so the sealed pair went when the patch did.
 *
 * <p>It carries the ordinary IR types — {@link Param} and {@link Fn} — rather than a reduced pair of its own.
 * The reduced pair is what dropped a service method's parameter defaults and optionality while every other
 * callable in the document kept them, and what made a listener's parameter DECLARATIONS render inside a
 * {@code new} call, where the language expects arguments.
 *
 * <p>{@code isAttachable} is whether the pairing is CONFIRMED — the service type is one of the listener's
 * {@code attach} targets or includes one ({@link Bindings}). False is a pairing that could not be settled either
 * way: the listener publishes no {@code attach} to read, or the package source that would show the type's
 * inclusions could not be read. A type known NOT to bind is not paired at all.
 *
 * <p>{@code declaredIn} is set when the attach target is another module's type — {@code postgresql:CdcListener}
 * takes a {@code cdc:Service} — and then {@code name} is that type's name in that module, and {@code methods}
 * is empty: the contract is that module's to state.
 *
 * @param name the service type's name
 * @param isDeprecated whether Central flagged the type deprecated
 * @param listener the listener it binds to, and what its constructor takes
 * @param methods the remote contract the service block must implement
 * @param isAttachable whether the pairing is confirmed rather than merely possible
 * @param declaredIn the module that declares the service type, when it is not this one
 * @since 0.1.0
 */
public record Service(String name, boolean isDeprecated, Listener listener, List<Fn> methods,
        boolean isAttachable, Optional<ModuleRef> declaredIn) {

    public Service(String name, boolean isDeprecated, Listener listener, List<Fn> methods) {
        this(name, isDeprecated, listener, methods, true, Optional.empty());
    }

    public Service(String name, boolean isDeprecated, Listener listener, List<Fn> methods, boolean isAttachable) {
        this(name, isDeprecated, listener, methods, isAttachable, Optional.empty());
    }

    /**
     * The listener a service attaches to, and what its constructor takes.
     *
     * <p>{@code name} is qualified ({@code kafka:Listener}) because a service is written in the caller's
     * module, where the listener is a foreign name.
     *
     * @param name the listener's qualified name
     * @param initParams what its constructor takes
     */
    public record Listener(String name, List<Param> initParams) { }
}
