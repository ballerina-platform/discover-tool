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

import io.ballerina.tools.discover.model.Library;
import io.ballerina.tools.discover.model.ModuleRef;
import io.ballerina.tools.discover.model.ServiceHandlers;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * One package, read once: its coordinates, its API as the IR, and its readme.
 *
 * @param qualified the package's organization and name
 * @param version the resolved version
 * @param library the package's API, as the IR
 * @param readme the resolved module's own readme, verbatim, or empty when it publishes none
 * @param module the {@code --module} value that resolved this module, or {@code null} for the package's own
 *     default module
 * @param submodules the modules listed under the one being read, name and summary only — every other module of
 *     the package for its default module, a submodule's own children otherwise; shown as {@code Submodules:}
 * @param modules every submodule this package publishes, by the name {@code --module} takes — what a command
 *     reaching another module of this package is checked against, whichever module is being read
 * @param warning why this version cannot be trusted, or {@code null} when it was confirmed against the
 *     registry — see {@link Loader#unverifiedWarning}
 * @param pinned the version the caller wrote in the coordinate, or {@code null} when it was resolved — carried into
 *     every command this lookup prints, so drilling further stays on the version being read
 * @param bound {@code library} with what the package source settles — deferred, since only an
 *     answer that shows a service type or an inline record needs it, and reading it can mean a download
 * @param handlers the handler methods the package describes for its service types, by service type name — deferred
 *     like {@code bound}, and empty for a version that describes none
 * @since 0.1.0
 */
public record LoadedPackage(
        QualifiedName qualified,
        Version version,
        Library library,
        Optional<String> readme,
        String module,
        List<Submodule> submodules,
        List<String> modules,
        String warning,
        Version pinned,
        Source bound,
        Supplier<Map<String, ServiceHandlers>> handlers) {

    /** What the package source settles, read on first {@link #get()}. */
    @FunctionalInterface
    public interface Source {

        Library get();

        /** Whether an answer rendered as JSON shows an inline record whose members only the source completes. */
        default boolean settles(Supplier<String> renderedJson) {
            return false;
        }
    }

    /**
     * @param name the bare name {@code --module} itself takes, e.g. {@code dataloader}
     * @param summary the submodule's own one-line summary, or empty when it publishes none
     */
    public record Submodule(String name, String summary) { }

    /** The module being read, as an import names it: the package's default module, or the {@code --module} one. */
    public ModuleRef moduleRef() {
        return new ModuleRef(qualified.org(), module == null ? qualified.name() : qualified.name() + "." + module);
    }

    /** {@code org/name:version} — the label every document and every failure identifies this lookup by. */
    public String label() {
        return qualified.versioned(version);
    }

    /** Where a lookup looked, as a failure names it: {@link #label()}, and the module under {@code --module}. */
    public String place() {
        return module == null ? label() : label() + ", module " + module;
    }

    /**
     * @param message what was not found, naming {@link #place()}
     * @param command the command {@code suggestion} quotes, or {@code null}
     */
    public Failure symbolNotFound(List<String> requested, String message, String suggestion, String command) {
        return new Failure.SymbolNotFound(qualified.qualified(), version.text(), module, requested, message,
                suggestion, command, List.of());
    }

    /** A name several containers declare: the failure carries each one, with the command that opens it there. */
    public Failure declaredBySeveral(List<String> requested, String message, String suggestion,
            List<Failure.Owner> owners) {
        return new Failure.SymbolNotFound(qualified.qualified(), version.text(), module, requested, message,
                suggestion, null, List.of(), owners);
    }

    /** A miss in a listing no command prints, so the failure itself carries every entry of it. */
    public Failure symbolNotFoundAmong(List<String> requested, String message, String suggestion,
            List<String> candidates) {
        return new Failure.SymbolNotFound(qualified.qualified(), version.text(), module, requested, message,
                suggestion, null, candidates);
    }

    /**
     * The package and module a command reaches this SAME lookup by — what every "next command" this tool prints
     * names, so drilling further never silently falls back to the default module.
     */
    public Command.Target target() {
        return target(module);
    }

    /** {@link #target()} for another module of this package, or its default one when {@code other} is null. */
    public Command.Target target(String other) {
        return new Coordinate(qualified, pinned).target(other);
    }

    /** {@code noun} and {@code names} asked of this lookup's package and module. */
    public Command command(String noun, String... names) {
        return Command.of(noun, names).in(target());
    }

    /** This lookup's overview: {@code bal discover package <pkg> [--module <m>]}. */
    public Command overview() {
        return Command.overview(target());
    }

    /**
     * Where another module is reached, or empty when that package is not known.
     * A module of THIS package — its default module, or any submodule it publishes — is reached through
     * {@code --module} or none; a name that merely starts with this package's is not enough, since
     * {@code postgresql.driver} is a package of its own.
     * An undotted module path is its package's default module, so it is the package coordinate as written.
     * Another package's dotted module path names no package boundary, and a command guessing one would not be
     * ready to run.
     */
    public Optional<Command.Target> targetFor(ModuleRef module) {
        boolean sameOrg = module.orgName().equals(qualified.org());
        String sibling = sameOrg && module.moduleName().startsWith(qualified.name() + ".")
                ? module.moduleName().substring(qualified.name().length() + 1)
                : null;
        if (sameOrg && module.moduleName().equals(qualified.name())) {
            return Optional.of(target(null));
        }
        if (sibling != null && modules.contains(sibling)) {
            return Optional.of(target(sibling));
        }
        return module.moduleName().contains(".")
                ? Optional.empty()
                : Optional.of(new Command.Target(module.coordinate(), null));
    }

    public LoadedPackage withLibrary(Library replacement) {
        return new LoadedPackage(qualified, version, replacement, readme, module, submodules, modules, warning,
                pinned, () -> replacement, handlers);
    }

    /**
     * The same package with what only the package source settles — service bindings and what inline records
     * declare — read on first call, and only by an answer that needs it.
     */
    public LoadedPackage withSource() {
        Library settled = bound.get();
        return settled == library ? this : withLibrary(settled);
    }

    /** The handlers the package describes for the service type {@code name}, when it describes any. */
    public Optional<ServiceHandlers> handlersOf(String name) {
        return Optional.ofNullable(handlers.get().get(name));
    }
}
