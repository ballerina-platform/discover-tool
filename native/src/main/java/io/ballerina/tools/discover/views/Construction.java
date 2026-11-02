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
import io.ballerina.tools.discover.model.TypeDef;
import io.ballerina.tools.discover.model.TypeRef;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.Identifiers;
import io.ballerina.tools.discover.render.Signatures;
import io.ballerina.tools.discover.symbols.Surface;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * How to construct a client or class, derived from its {@code init} alone: a line that compiles once its arguments
 * are in scope, {@code kafka:Producer producer = check new (bootstrapServers, config);}, and {@code init}'s
 * parameters as declared, {@code init(string|string[] bootstrapServers, *kafka:ProducerConfiguration config)}, so
 * each argument's type is on the next line.
 *
 * <p>Every name the package declares is written with the prefix its import binds, as a caller's code spells it.
 * {@code check} is there exactly when {@code init} declares a return type, since an {@code init} can return only an
 * error or nil. The variable is the type's name with its leading capitals lower-cased ({@code HTTPClient} is
 * {@code httpClient}), and the prefix before it when that would be a keyword ({@code github:Client githubClient}).
 * The arguments are the required parameters by name, an included record's included: a parameter with a default and
 * a rest parameter may be left out, and every required parameter after one left out is passed as a named argument
 * ({@code new (host, clientConfig = clientConfig)}), since a positional argument cannot skip a parameter. A class
 * with no {@code init} is constructed with no arguments; an object type is not constructed at all.
 *
 * @since 0.1.0
 */
final class Construction {

    private static final Pattern NOT_IDENTIFIER = Pattern.compile("[^A-Za-z0-9_]");

    private Construction() {
    }

    /** How to construct {@code container}, when it is a client or a class that can be constructed. */
    static Optional<DiscoverResult.Signature.Create> of(LoadedPackage loaded, Surface.Container container,
            String initCommand) {
        boolean constructible = container.scope() == Surface.Scope.CLIENT || container.scope() == Surface.Scope.CLASS;
        if (!constructible) {
            return Optional.empty();
        }
        ModuleRef own = loaded.moduleRef();
        String type = own.prefix() + ":" + container.plainName();
        Optional<Fn.Constructor> init = container.constructor();
        if (init.isEmpty()) {
            return isClass(loaded, container)
                    ? Optional.of(new DiscoverResult.Signature.Create(
                            type + " " + variable(container, own) + " = new ();", null, null))
                    : Optional.empty();
        }
        String arguments = arguments(init.get().params());
        String declared = init.get().params().stream()
                .map(param -> Signatures.renderParam(param, written -> qualified(written, own)))
                .collect(Collectors.joining(", "));
        String check = init.get().returns().hasType() ? "check " : "";
        return Optional.of(new DiscoverResult.Signature.Create(
                type + " " + variable(container, own) + " = " + check + "new (" + arguments + ");",
                Fn.Constructor.NAME + "(" + declared + ")", initCommand));
    }

    // Positional until the first parameter left out; after it, Ballerina accepts the rest only as named arguments.
    private static String arguments(List<Param> params) {
        List<String> arguments = new ArrayList<>();
        boolean named = false;
        for (Param param : params) {
            if (!isRequired(param)) {
                named = true;
                continue;
            }
            String name = Identifiers.write(param.name());
            arguments.add(named ? name + " = " + name : name);
        }
        return String.join(", ", arguments);
    }

    private static boolean isRequired(Param param) {
        return switch (param.form()) {
            case INCLUSION -> true;
            case REST -> false;
            case NORMAL -> !param.hasDefault();
        };
    }

    private static boolean isClass(LoadedPackage loaded, Surface.Container container) {
        return loaded.library().addressable().stream()
                .anyMatch(declared -> declared instanceof TypeDef.ObjectDef object
                        && object.name().equals(container.name())
                        && object.form() == TypeDef.ObjectDef.Form.CLASS);
    }

    private static String variable(Surface.Container container, ModuleRef own) {
        String name = NOT_IDENTIFIER.matcher(container.plainName()).replaceAll("");
        int capitals = 0;
        while (capitals < name.length() && Character.isUpperCase(name.charAt(capitals))) {
            capitals++;
        }
        // Of a run of capitals, the last starts the next word when a lower-case letter follows it: `HTTPClient`.
        int lowered = capitals > 1 && capitals < name.length() ? capitals - 1 : Math.max(capitals, 1);
        String lower = name.substring(0, lowered).toLowerCase(Locale.ROOT) + name.substring(lowered);
        return Identifiers.write(lower).equals(lower) ? lower : own.prefix() + name;
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
