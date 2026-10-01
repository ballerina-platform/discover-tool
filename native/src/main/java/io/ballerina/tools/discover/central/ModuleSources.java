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
import io.ballerina.tools.discover.Version;

import java.util.Map;
import java.util.Optional;

/**
 * Somewhere one module's published {@code .bal} files can be read from, for one exact version: a bala directory
 * already on this machine ({@link LocalBalas}), or the archive Central serves ({@link CentralClient}).
 *
 * <p>Empty is an ordinary answer — not there, not readable, too large — never a failure: the only reader falls
 * back to what the docs payload says.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface ModuleSources {

    /**
     * @param moduleId the module's full id as Central names it — {@code http}, or {@code http.httpscerr}
     * @return the module's {@code .bal} files by file name
     */
    Optional<Map<String, String>> moduleSources(
            QualifiedName qualified, Version version, String moduleId, HttpOptions options);
}
