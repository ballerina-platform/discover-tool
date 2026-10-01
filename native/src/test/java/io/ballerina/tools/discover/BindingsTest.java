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

import io.ballerina.tools.discover.central.schema.CentralDocs;
import io.ballerina.tools.discover.constructs.Decl;
import io.ballerina.tools.discover.constructs.Node;
import io.ballerina.tools.discover.constructs.Payload;
import io.ballerina.tools.discover.model.Bindings;
import io.ballerina.tools.discover.model.Library;
import io.ballerina.tools.discover.model.ModuleRef;
import io.ballerina.tools.discover.model.ObjectInclusions;
import io.ballerina.tools.discover.model.Pipeline;
import io.ballerina.tools.discover.model.Service;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which service types bind to which listener: the listener's {@code attach} targets, resolved through every form
 * a real payload writes them in, plus the inclusions the package's source shows.
 *
 * <p>Each payload is assembled around one form. The forms are the ones measured across 43 listener-bearing
 * packages — a plain name (34 of them), an anonymous union ({@code ballerina/mcp}), a union alias
 * ({@code ballerinax/salesforce}, every {@code trigger.*}), a name-reference alias
 * ({@code trigger.google.sheets}) and another module's type ({@code postgresql:CdcListener}'s
 * {@code cdc:Service}) — plus the two no real payload has, an intersection and an alias cycle, which the
 * resolver must still survive.
 *
 * @since 0.1.0
 */
public class BindingsTest {

    private static final String OBJECT_TYPES = "objectTypes";

    private static Node local(String name) {
        return Node.named(name, OBJECT_TYPES).with("orgName", "test").with("moduleName", "pkg");
    }

    private static Payload withServices(Decl listener, String... serviceTypes) {
        Payload payload = Payload.pkg().with("listeners", listener);
        for (String serviceType : serviceTypes) {
            payload.with("serviceTypes", Decl.serviceType(serviceType));
        }
        return payload;
    }

    private static Optional<Set<Bindings.Target>> targetsOf(CentralDocs.Module module) {
        return Bindings.attachTargets(module.listeners().get(0), module);
    }

    private static Set<Bindings.Target> locals(String... names) {
        return Set.of(names).stream().map(Bindings.Target.Local::new).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    public void aPlainNameIsItsOwnTarget() {
        CentralDocs.Module module = withServices(Decl.listenerAttaching(local("Service"), "Listener"),
                "Service", "Interceptor").module();
        Assert.assertEquals(targetsOf(module), Optional.of(locals("Service")));
    }

    @Test
    public void anAnonymousUnionTargetsEachMember() {
        CentralDocs.Module module = withServices(Decl.listenerAttaching(
                Node.structural().on("isAnonymousUnionType").members(local("Service"), local("AdvancedService")),
                "Listener"), "Service", "AdvancedService").module();
        Assert.assertEquals(targetsOf(module), Optional.of(locals("Service", "AdvancedService")));
    }

    @Test
    public void aUnionAliasIsResolvedToItsMembers() {
        CentralDocs.Module module = withServices(Decl.listenerAttaching(local("Service"), "Listener"),
                "CdcService", "PlatformEventsService")
                .with("unionTypes", Decl.alias("Service", local("CdcService"), local("PlatformEventsService")))
                .module();
        Assert.assertEquals(targetsOf(module), Optional.of(locals("CdcService", "PlatformEventsService")));
        Assert.assertFalse(Bindings.needsSource(module), "every service type is a target");
    }

    @Test
    public void aNameReferenceAliasIsResolvedToWhatItNames() {
        CentralDocs.Module module = withServices(Decl.listenerAttaching(local("GenericServiceType"), "Listener"),
                "SheetRowService")
                .with("simpleNameReferenceTypes", Decl.alias("GenericServiceType", local("SheetRowService")))
                .module();
        Assert.assertEquals(targetsOf(module), Optional.of(locals("SheetRowService")));
    }

    @Test
    public void anIntersectionAliasDropsItsReadonlyMember() {
        CentralDocs.Module module = withServices(Decl.listenerAttaching(local("Frozen"), "Listener"), "Service")
                .with("intersectionTypes", Decl.intersectionAlias("Frozen", Node.builtin("readonly"),
                        local("Service")))
                .module();
        Assert.assertEquals(targetsOf(module), Optional.of(locals("Service")));
    }

    @Test
    public void anotherModulesTypeIsAForeignTarget() {
        CentralDocs.Module module = Payload.pkg()
                .with("listeners", Decl.listenerAttaching(
                        Node.external("ballerinax", "cdc", "Service").with("version", "1.4.0"), "CdcListener"))
                .module();
        Assert.assertEquals(targetsOf(module), Optional.of(Set.of(
                new Bindings.Target.Foreign(new ModuleRef("ballerinax", "cdc", "1.4.0"), "Service"))));

        Library library = Pipeline.build(module);
        Assert.assertEquals(library.services().size(), 1);
        Service foreign = library.services().get(0);
        Assert.assertEquals(foreign.name(), "Service");
        Assert.assertEquals(foreign.listener(), "pkg:CdcListener");
        Assert.assertTrue(foreign.isConfirmed());
        Assert.assertEquals(foreign.declaredIn().map(ModuleRef::coordinate), Optional.of("ballerinax/cdc"));
    }

    @Test
    public void anAliasCycleEndsWithoutATarget() {
        CentralDocs.Module module = withServices(Decl.listenerAttaching(local("A"), "Listener"), "Service")
                .with("unionTypes", Decl.alias("A", local("B")), Decl.alias("B", local("A")))
                .module();
        Assert.assertEquals(targetsOf(module), Optional.empty());
    }

    @Test
    public void noEvidenceIsTheCrossProductUnconfirmed() {
        CentralDocs.Module module = withServices(Decl.named("Listener"), "Service", "Interceptor").module();
        Assert.assertEquals(targetsOf(module), Optional.empty());
        Assert.assertFalse(Bindings.needsSource(module), "nothing to settle against");
        List<Service> services = Pipeline.build(module).services();
        Assert.assertEquals(services.stream().map(Service::name).toList(), List.of("Service", "Interceptor"));
        Assert.assertTrue(services.stream().allMatch(service -> service.binding()
                == Bindings.Binding.NO_ATTACH_EVIDENCE));
    }

    @Test
    public void anAnonymousServiceObjectIsNoEvidence() {
        CentralDocs.Module module = withServices(Decl.listenerAttaching(
                Node.structural().with("category", "other"), "Listener"), "Service").module();
        Assert.assertEquals(targetsOf(module), Optional.empty());
    }

    @Test
    public void aTypeThatIsNoTargetNeedsTheSource() {
        CentralDocs.Module module = withServices(Decl.listenerAttaching(local("Service"), "Listener"),
                "Service", "InterceptableService").module();
        Assert.assertTrue(Bindings.needsSource(module));
        Assert.assertFalse(Bindings.needsSource(Payload.pkg().with("serviceTypes", Decl.serviceType("Service"))
                .module()), "a package without listeners never fetches source");
    }

    @Test
    public void anInclusionBindsTransitively() {
        Set<Bindings.Target> targets = locals("Service");
        ObjectInclusions inclusions = new ObjectInclusions(Map.of(
                "Contract", List.of("Service"),
                "Interceptable", List.of("Contract"),
                "Interceptor", List.of()));
        Assert.assertEquals(Bindings.bind("Contract", Optional.of(targets), Set.of("Service"),
                Optional.of(inclusions)), Bindings.Binding.CONFIRMED);
        Assert.assertEquals(Bindings.bind("Interceptable", Optional.of(targets), Set.of("Service"),
                Optional.of(inclusions)), Bindings.Binding.CONFIRMED);
        Assert.assertEquals(Bindings.bind("Interceptor", Optional.of(targets), Set.of("Service"),
                Optional.of(inclusions)), Bindings.Binding.NONE);
    }

    @Test
    public void anInclusionOfAForeignTargetBindsToo() {
        Set<Bindings.Target> targets =
                Set.of(new Bindings.Target.Foreign(new ModuleRef("ballerinax", "cdc"), "Service"));
        ObjectInclusions inclusions = new ObjectInclusions(Map.of("Audit", List.of("ballerinax/cdc:Service")));
        Assert.assertEquals(Bindings.bind("Audit", Optional.of(targets), Set.of(), Optional.of(inclusions)),
                Bindings.Binding.CONFIRMED);
    }

    @Test
    public void withoutTheSourceANonTargetIsUnconfirmedUnlessAnotherListenerTakesIt() {
        Optional<Set<Bindings.Target>> jetStream = Optional.of(locals("JetStreamService"));
        Assert.assertEquals(Bindings.bind("Interceptor", jetStream, Set.of("JetStreamService", "Service"),
                Optional.empty()), Bindings.Binding.SOURCE_UNAVAILABLE);
        Assert.assertEquals(Bindings.bind("Service", jetStream, Set.of("JetStreamService", "Service"),
                Optional.empty()), Bindings.Binding.NONE);
    }

    @Test
    public void inclusionClosureSurvivesACycle() {
        ObjectInclusions inclusions = new ObjectInclusions(Map.of("A", List.of("B"), "B", List.of("A", "C")));
        Assert.assertEquals(inclusions.closure("A"), Set.of("B", "C"));
    }

    @Test
    public void eachListenerGetsOnlyTheTypesItTakes() {
        CentralDocs.Module module = Payload.pkg()
                .with("listeners",
                        Decl.listenerAttaching(local("JetStreamService"), "JetStreamListener"),
                        Decl.listenerAttaching(local("Service"), "Listener"))
                .with("serviceTypes", Decl.serviceType("Service"), Decl.serviceType("JetStreamService"))
                .module();
        List<Service> services = Pipeline.build(module).services();
        Assert.assertEquals(services.stream().map(service -> service.name() + "->" + service.listener())
                .toList(), List.of("Service->pkg:Listener", "JetStreamService->pkg:JetStreamListener"));
        Assert.assertTrue(services.stream().allMatch(Service::isConfirmed));
    }

    /** Targets keep the order the attach parameter lists them in, so a roster is the same on every run. */
    @Test
    public void foreignTargetsKeepTheirDeclaredOrder() {
        List<String> names = List.of("Zeta", "Alpha", "Mid", "Beta", "Omega");
        Node[] members = names.stream().map(name -> Node.external("ballerinax", "cdc", name)).toArray(Node[]::new);
        CentralDocs.Module module = Payload.pkg().with("listeners", Decl.listenerAttaching(
                Node.structural().on("isAnonymousUnionType").members(members), "Listener")).module();
        Assert.assertEquals(targetsOf(module).orElseThrow().stream()
                .map(target -> ((Bindings.Target.Foreign) target).name()).toList(), names);
        Assert.assertEquals(Pipeline.build(module).services().stream().map(Service::name).toList(), names);
    }
}
