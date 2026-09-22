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
 * <p>Ballerina Central is the only implementation this phase ships ({@link CentralRepository}). A local
 * "Local Central" cache and Artifactory-backed repositories are additive later work behind this same interface —
 * see the RFC's Multi-Source Repository Interface section — not designed here.
 *
 * @since 0.1.0
 */
public interface PackageRepository {

    /** Which version of {@code qualified} to read, resolved however this repository resolves "latest". */
    Result<CentralClient.ResolvedVersion> resolveVersion(QualifiedName qualified, HttpOptions options);

    /** The API docs for one already-resolved version. */
    Result<CentralDocs> fetchDocs(QualifiedName qualified, CentralClient.ResolvedVersion resolved, HttpOptions options);

    /** A short, human-readable name for this repository — for diagnostics, never parsed. */
    String describe();
}
