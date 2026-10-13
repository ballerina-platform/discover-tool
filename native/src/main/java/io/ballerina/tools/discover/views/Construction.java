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

package io.ballerina.tools.discover.views;

import io.ballerina.tools.discover.LoadedPackage;
import io.ballerina.tools.discover.model.Fn;
import io.ballerina.tools.discover.model.ModuleRef;
import io.ballerina.tools.discover.model.Param;
import io.ballerina.tools.discover.model.TypeRef;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.Identifiers;
import io.ballerina.tools.discover.render.Signatures;
import io.ballerina.tools.discover.symbols.Surface;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The one line that constructs a client or class, derived from its {@code init} alone:
 * {@code kafka:Producer producer = check new (string|string[] bootstrapServers, *kafka:ProducerConfiguration config);}.
 *
 * <p>Every name the package declares is written with the prefix its import binds, as a caller's code spells it.
 * {@code check} is there exactly when {@code init} declares a return type, since an {@code init} can return only an
 * error or nil. The variable is the type's name with a lower-case first letter, and the prefix before it when that
 * would be a keyword ({@code github:Client githubClient}). Parameters are listed as {@code init} declares them,
 * defaults included, so the line is a template to fill in rather than code that compiles as it stands.
 *
 * @since 0.1.0
 */
final class Construction {

    private static final String MODULE_SEPARATOR = ".";

    private Construction() {
    }

    /** How to construct {@code container}, when it is a client or a class with a constructor. */
    static Optional<DiscoverResult.Signature.Create> of(LoadedPackage loaded, Surface.Container container,
            String initCommand) {
        boolean constructible = container.scope() == Surface.Scope.CLIENT || container.scope() == Surface.Scope.CLASS;
        Optional<Fn.Constructor> init = constructible ? container.constructor() : Optional.empty();
        if (init.isEmpty()) {
            return Optional.empty();
        }
        ModuleRef own = new ModuleRef(loaded.qualified().org(), loaded.module() == null
                ? loaded.qualified().name()
                : loaded.qualified().name() + MODULE_SEPARATOR + loaded.module());
        String type = own.prefix() + ":" + container.name();
        String params = init.get().params().stream()
                .map(param -> written(param, own))
                .collect(Collectors.joining(", "));
        String check = init.get().returns().hasType() ? "check " : "";
        return Optional.of(new DiscoverResult.Signature.Create(
                type + " " + variable(container, own) + " = " + check + "new (" + params + ");", initCommand));
    }

    private static String variable(Surface.Container container, ModuleRef own) {
        String name = container.plainName();
        String lower = Character.toLowerCase(name.charAt(0)) + name.substring(1);
        return Identifiers.write(lower).equals(lower) ? lower : own.prefix() + name;
    }

    private static String written(Param param, ModuleRef own) {
        String type = qualified(param.type(), own);
        String name = Identifiers.write(param.name());
        return switch (param.form()) {
            case INCLUSION -> "*" + type + " " + name;
            case REST -> type + "... " + name;
            case NORMAL -> type + " " + name + (param.hasDefault() ? " = " + param.defaultValue() : "");
        };
    }

    // The type as a caller writes it: names of other modules with their prefixes, and this module's with its own.
    private static String qualified(TypeRef type, ModuleRef own) {
        List<Signatures.ExternalLink> links = new ArrayList<>(Signatures.collectExternalLinks(type));
        type.links().stream()
                .filter(TypeRef.Link.Internal.class::isInstance)
                .forEach(link -> links.add(new Signatures.ExternalLink(link.recordName(), own)));
        return Signatures.applyPrefixToTypeName(type.name(), links);
    }
}
