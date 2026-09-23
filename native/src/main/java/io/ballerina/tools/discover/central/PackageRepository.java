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

package io.ballerina.tools.discover.central;

import io.ballerina.tools.discover.QualifiedName;
import io.ballerina.tools.discover.Result;
import io.ballerina.tools.discover.central.schema.CentralDocs;

/**
 * Where a package's version and its docs payload come from — the seam a source other than Ballerina Central
 * implements identically, so {@link io.ballerina.tools.discover.Loader} never special-cases one.
 *
 * <p>{@link io.ballerina.tools.discover.Loader.LoadOptions} holds an ORDERED LIST of these, not one: the RFC's
 * multi-source repository interface (Central, a local "Local Central" cache, Artifactory) means several sources
 * are candidates for the same lookup, tried in order until one answers. Ballerina Central is the only
 * implementation this phase ships ({@link CentralRepository}), and the only element that list ever holds yet — a
 * local cache and Artifactory-backed repositories are additive later work behind this same interface, not
 * designed here.
 *
 * @since 0.1.0
 */
public interface PackageRepository {

    /** Which version of {@code qualified} to read, resolved however this repository resolves "latest". */
    Result<CentralClient.ResolvedVersion> resolveVersion(QualifiedName qualified, HttpOptions options);

    /** The API docs for one already-resolved version. */
    Result<CentralDocs> fetchDocs(QualifiedName qualified, CentralClient.ResolvedVersion resolved, HttpOptions options);

    /**
     * This repository's own stable, filesystem-safe identity — the cache key's repository dimension (see
     * {@code io.ballerina.tools.discover.cache.DocsCache}), so two repositories answering the same
     * {@code org/name/version} differently never collide in one entry. Never free-form prose and never derived
     * from anything a repository's own configuration could change between runs (a URL, a mirror's hostname) —
     * unlike {@link #describe()}, this is parsed, as a path segment.
     */
    String id();

    /** A short, human-readable name for this repository — for diagnostics, never parsed. */
    String describe();
}
