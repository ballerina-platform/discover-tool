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

import java.util.List;
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
 * @param pinned the {@code --version} the caller supplied, or {@code null} when it was resolved — carried into
 *     every command this lookup prints, so drilling further stays on the version being read
 * @param bound {@code library} with its service bindings read from the package source — deferred, since only an
 *     answer that shows a service type needs it, and reading it can mean a download
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
        String pinned,
        Supplier<Library> bound) {

    /**
     * @param name the bare name {@code --module} itself takes, e.g. {@code dataloader}
     * @param summary the submodule's own one-line summary, or empty when it publishes none
     */
    public record Submodule(String name, String summary) { }

    /** {@code org/name:version} — the label every document and every failure identifies this lookup by. */
    public String label() {
        return qualified.versioned(version);
    }

    /**
     * The argument a caller types to reach this SAME (package, module) pair again — what every "next command"
     * this tool prints builds on, so drilling further never silently falls back to the default module.
     */
    public String pkgArgument() {
        return pkgArgument(module);
    }

    /** {@link #pkgArgument()} for another module of this package, or its default one when {@code other} is null. */
    public String pkgArgument(String other) {
        return qualified.qualified() + (other == null ? "" : " --module " + Texts.shellWord(other)) + pin();
    }

    private String pin() {
        return pinned == null ? "" : " --version " + Texts.shellWord(pinned);
    }

    /**
     * The argument that reaches another module in its own package, or empty when that package is not known.
     * A module of THIS package — its default module, or any submodule it publishes — is reached through
     * {@code --module} or none; a name that merely starts with this package's is not enough, since
     * {@code postgresql.driver} is a package of its own.
     * An undotted module path is its package's default module, so it is the package coordinate as written.
     * Another package's dotted module path names no package boundary, and a command guessing one would not be
     * ready to run.
     */
    public Optional<String> argumentFor(ModuleRef module) {
        boolean sameOrg = module.orgName().equals(qualified.org());
        String sibling = sameOrg && module.moduleName().startsWith(qualified.name() + ".")
                ? module.moduleName().substring(qualified.name().length() + 1)
                : null;
        if (sameOrg && module.moduleName().equals(qualified.name())) {
            return Optional.of(pkgArgument(null));
        }
        if (sibling != null && modules.contains(sibling)) {
            return Optional.of(pkgArgument(sibling));
        }
        return module.moduleName().contains(".") ? Optional.empty() : Optional.of(module.coordinate());
    }

    public LoadedPackage withLibrary(Library replacement) {
        return new LoadedPackage(qualified, version, replacement, readme, module, submodules, modules, warning,
                pinned, () -> replacement);
    }

    /**
     * The same package with its service bindings settled from the package source where the docs payload cannot
     * settle them — read on first call, and only by an answer that shows a service type.
     */
    public LoadedPackage withBindings() {
        Library settled = bound.get();
        return settled == library ? this : withLibrary(settled);
    }
}
