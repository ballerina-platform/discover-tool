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

import io.ballerina.tools.discover.central.schema.CentralDocs;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which service types bind to which listener.
 *
 * <p>The compiler's rule: {@code service T on L} compiles only when {@code T} is a subtype of the first parameter
 * of {@code L.attach}. Every service type a package publishes is a {@code distinct service object}, and a distinct
 * type is a subtype of another only by INCLUDING it — so {@code T} binds to {@code L} when it is one of
 * {@code attach}'s targets, or includes one, directly or transitively. Verified with {@code bal build}:
 * {@code http:InterceptableService} and {@code http:ServiceContract} (both write {@code *Service;}) attach to
 * {@code http:Listener}; the four http interceptors, {@code graphql:Interceptor}, {@code tcp:ConnectionService}
 * and {@code websocket:Service} do not.
 *
 * <p>The targets come from the docs payload. Inclusions do not — Central publishes none for a service type — so
 * they come from the package's source when it can be read ({@link ObjectInclusions}); without it a service type
 * that is no listener's target cannot be placed, and is paired {@link Binding#SOURCE_UNAVAILABLE} rather than dropped.
 *
 * @since 0.1.0
 */
public final class Bindings {

    private Bindings() {
    }

    /** What one attach target is: a service type of this module, or a type another module declares. */
    public sealed interface Target {

        /**
         * @param name the module's own service type
         */
        record Local(String name) implements Target { }

        /**
         * @param module the module that declares it — {@code ballerinax/cdc} for {@code postgresql:CdcListener}
         * @param name the type's name in that module
         */
        record Foreign(ModuleRef module, String name) implements Target {

            /**
             * {@code ballerinax/cdc:Service} — what an inclusion of it is recorded as, whatever prefix the
             * including file imported the module under.
             */
            public String qualified() {
                return module.coordinate() + ":" + name;
            }
        }
    }

    /**
     * Whether a service type binds to a listener, and how sure that answer is. An unsettled binding carries why,
     * as a {@link #reason()} for prose and a {@link #label()} for a roster to group under; a settled one carries
     * {@code null} for both.
     */
    public enum Binding {
        /** It is the listener's attach target, or includes one. */
        CONFIRMED(null, null),
        /** Unsettled: the listener publishes no {@code attach()} to read the target from. */
        NO_ATTACH_EVIDENCE("the listener publishes no attach() signature to read its service type from",
                "listener publishes no attach()"),
        /** Unsettled: {@code attach()}'s parameter resolves to no named service type ({@code service object {}}). */
        UNNAMED_ATTACH_TARGET("the listener's attach() takes no named service type to pair with",
                "attach() names no service type"),
        /** Unsettled: the package source, which shows the type's inclusions, was unavailable. */
        SOURCE_UNAVAILABLE("the package source, which shows which service types include the listener's attach() "
                + "type, was unavailable", "package source unavailable"),
        /** It does not bind. */
        NONE(null, null);

        private final String reason;
        private final String label;

        Binding(String reason, String label) {
            this.reason = reason;
            this.label = label;
        }

        public boolean isConfirmed() {
            return this == CONFIRMED;
        }

        public boolean isUnsettled() {
            return reason != null;
        }

        public String reason() {
            return reason;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Every type {@code listener.attach} accepts: empty when the listener publishes no {@code attach}, and an empty
     * set when its parameter resolves to no named service type ({@code service object {}}) — no evidence either
     * way, for two different reasons.
     *
     * <p>The parameter is resolved through anonymous unions and through the module's own union, name-reference and
     * intersection aliases, recursively: salesforce's {@code Service} is {@code CdcService|PlatformEventsService},
     * every {@code trigger.*} package's {@code GenericServiceType} is a union of its services, and an intersection
     * member such as {@code readonly} names no service type and simply drops out.
     */
    public static Optional<Set<Target>> attachTargets(CentralDocs.Listener listener, CentralDocs.Module module) {
        Optional<CentralDocs.TypeNode> parameter = listener.lifeCycleMethodList().stream()
                .filter(method -> "attach".equals(method.name()))
                .flatMap(method -> method.params().stream().limit(1))
                .map(CentralDocs.Parameter::type)
                .findFirst();
        if (parameter.isEmpty()) {
            return Optional.empty();
        }
        Set<Target> targets = new LinkedHashSet<>();
        collect(parameter.get(), module, aliases(module), new HashSet<>(), targets);
        return Optional.of(Collections.unmodifiableSet(targets));
    }

    private static Map<String, CentralDocs.AliasDecl> aliases(CentralDocs.Module module) {
        Map<String, CentralDocs.AliasDecl> aliases = new LinkedHashMap<>();
        for (List<CentralDocs.AliasDecl> category : List.of(
                module.unionTypes(), module.simpleNameReferenceTypes(), module.intersectionTypes())) {
            category.forEach(alias -> aliases.putIfAbsent(alias.name(), alias));
        }
        return aliases;
    }

    private static void collect(CentralDocs.TypeNode node, CentralDocs.Module module,
            Map<String, CentralDocs.AliasDecl> aliases, Set<String> seen, Set<Target> targets) {
        if (node.isAnonymousUnionType() || node.isIntersectionType()) {
            node.members().forEach(member -> collect(member, module, aliases, seen, targets));
            return;
        }
        if (node.isParenthesisedType() && node.elementType().isPresent()) {
            collect(node.elementType().get(), module, aliases, seen, targets);
            return;
        }
        String name = node.name().orElse("").trim();
        if (name.isEmpty()) {
            node.members().forEach(member -> collect(member, module, aliases, seen, targets));
            return;
        }
        Optional<ModuleRef> foreign = foreignModule(node, module);
        if (foreign.isPresent()) {
            targets.add(new Target.Foreign(foreign.get(), name));
        } else if (module.serviceTypes().stream().anyMatch(serviceType -> serviceType.name().equals(name))) {
            targets.add(new Target.Local(name));
        } else if (aliases.containsKey(name) && seen.add(name)) {
            CentralDocs.TypeNode alias = aliases.get(name).type();
            alias.members().forEach(member -> collect(member, module, aliases, seen, targets));
            alias.elementType().ifPresent(element -> collect(element, module, aliases, seen, targets));
        }
    }

    private static Optional<ModuleRef> foreignModule(CentralDocs.TypeNode node, CentralDocs.Module module) {
        String moduleName = node.moduleName().orElse("");
        String org = node.orgName().orElse("");
        boolean foreign = !moduleName.isEmpty() && !org.isEmpty() && !FromCentral.NO_ORG.equals(org)
                && (!moduleName.equals(module.id()) || !org.equals(module.orgName()));
        return foreign ? Optional.of(new ModuleRef(org, moduleName, node.version().orElse(""))) : Optional.empty();
    }

    /**
     * Whether the package's source could change the answer: some listener names its targets, and some service
     * type is no listener's target — the only types an inclusion could still bind. Never true for a package
     * without listeners, so the source is fetched only where the payload alone cannot settle a pairing.
     */
    public static boolean needsSource(CentralDocs.Module module) {
        Set<String> local = new HashSet<>();
        boolean evidence = false;
        for (CentralDocs.Listener listener : module.listeners()) {
            Optional<Set<Target>> targets = attachTargets(listener, module);
            if (targets.isPresent() && !targets.get().isEmpty()) {
                evidence = true;
                targets.get().forEach(target -> {
                    if (target instanceof Target.Local named) {
                        local.add(named.name());
                    }
                });
            }
        }
        return evidence && module.serviceTypes().stream().anyMatch(serviceType -> !local.contains(serviceType.name()));
    }

    /**
     * How {@code serviceType} binds to one listener.
     *
     * @param targets that listener's {@link #attachTargets}
     * @param targetedElsewhere every local service type SOME listener of the module names as a target
     * @param inclusions the module's object inclusions, or empty when its source could not be read
     */
    public static Binding bind(String serviceType, Optional<Set<Target>> targets, Set<String> targetedElsewhere,
            Optional<ObjectInclusions> inclusions) {
        if (targets.isEmpty()) {
            return Binding.NO_ATTACH_EVIDENCE;
        }
        if (targets.get().isEmpty()) {
            return Binding.UNNAMED_ATTACH_TARGET;
        }
        if (targets.get().contains(new Target.Local(serviceType))) {
            return Binding.CONFIRMED;
        }
        if (inclusions.isPresent()) {
            Set<String> included = inclusions.get().closure(serviceType);
            boolean reaches = targets.get().stream().anyMatch(target -> switch (target) {
                case Target.Local local -> included.contains(local.name());
                case Target.Foreign foreign -> included.contains(foreign.qualified());
            });
            return reaches ? Binding.CONFIRMED : Binding.NONE;
        }
        return targetedElsewhere.contains(serviceType) ? Binding.NONE : Binding.SOURCE_UNAVAILABLE;
    }
}
