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
 * @param submodules every OTHER module this package publishes, name and summary only — the bare-package fact
 *     shown as {@code Submodules:}, regardless of which module {@code module} itself addresses
 * @param warning why this version cannot be trusted, or {@code null} when it was confirmed against the
 *     registry — see {@link Loader#unverifiedWarning}
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
        String warning,
        Supplier<Library> bound) {

    /** A package whose {@code library} already shows every service binding it can. */
    public LoadedPackage(QualifiedName qualified, Version version, Library library, Optional<String> readme,
            String module, List<Submodule> submodules, String warning) {
        this(qualified, version, library, readme, module, submodules, warning, () -> library);
    }

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
        return module == null ? qualified.qualified() : qualified.qualified() + " --module " + module;
    }

    /** The same package with a different IR, which is what a test that removes every client needs. */
    public LoadedPackage withLibrary(Library replacement) {
        return new LoadedPackage(qualified, version, replacement, readme, module, submodules, warning);
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
