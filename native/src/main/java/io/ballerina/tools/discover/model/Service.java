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

import java.util.Optional;

/**
 * One service type paired with one listener it binds to.
 *
 * <p>{@code binding} says how sure the pairing is ({@link Bindings}): CONFIRMED when the type is one of the
 * listener's {@code attach} targets or includes one; otherwise a reason it could not be settled either way. A type
 * known NOT to bind is not paired at all.
 *
 * <p>{@code declaredIn} is set when the attach target is another module's type — {@code postgresql:CdcListener}
 * takes a {@code cdc:Service} — and then {@code name} is that type's name in that module.
 *
 * @param name the service type's name
 * @param listener the listener's name as a caller writes it, {@code http:Listener}
 * @param binding how sure the pairing is
 * @param declaredIn the module that declares the service type, when it is not this one
 * @since 0.1.0
 */
public record Service(String name, String listener, Bindings.Binding binding, Optional<ModuleRef> declaredIn) {

    public boolean isConfirmed() {
        return binding.isConfirmed();
    }
}
