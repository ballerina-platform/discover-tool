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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A package archive ({@code .bala}) — a zip whose {@code modules/<module-id>/} directories hold each module's
 * {@code .bal} files, beside the docs, resources and native jars this reader never needs.
 *
 * @since 0.1.0
 */
public final class Bala {

    private Bala() {
    }

    /**
     * The {@code .bal} files directly under {@code modules/<moduleId>/}, by file name, or empty when the archive
     * is unreadable or holds none — a submodule's own directory is a sibling, never nested, so this reads exactly
     * one module.
     */
    public static Optional<Map<String, String>> moduleSources(byte[] archive, String moduleId) {
        String prefix = "modules/" + moduleId + "/";
        Map<String, String> files = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                if (!entry.isDirectory() && name.startsWith(prefix) && name.endsWith(".bal")
                        && name.indexOf('/', prefix.length()) < 0) {
                    files.put(name.substring(prefix.length()), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException | IllegalArgumentException unreadable) {
            return Optional.empty();
        }
        return files.isEmpty() ? Optional.empty() : Optional.of(Collections.unmodifiableMap(files));
    }
}
