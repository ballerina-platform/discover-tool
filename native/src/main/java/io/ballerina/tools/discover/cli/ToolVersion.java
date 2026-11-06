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

package io.ballerina.tools.discover.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * The tool's own version, as the build stamped it into {@value #RESOURCE}: what {@code bal discover --version}
 * prints.
 *
 * @since 0.1.0
 */
final class ToolVersion {

    private static final String RESOURCE = "tool.properties";

    private static final String KEY = "version";

    private ToolVersion() {
    }

    static String text() {
        Properties properties = new Properties();
        try (InputStream stamped = ToolVersion.class.getResourceAsStream(RESOURCE)) {
            if (stamped == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the tool's jar");
            }
            properties.load(stamped);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
        String version = properties.getProperty(KEY);
        if (version == null || version.isBlank()) {
            throw new IllegalStateException(RESOURCE + " in the tool's jar has no '" + KEY + "'");
        }
        return version.strip();
    }
}
