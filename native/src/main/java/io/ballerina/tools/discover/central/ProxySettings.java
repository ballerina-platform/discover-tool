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

import io.ballerina.projects.TomlDocument;
import io.ballerina.projects.internal.SettingsBuilder;
import io.ballerina.projects.internal.model.Proxy;
import io.ballerina.projects.util.ProjectConstants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The HTTP proxy {@code bal pull} would use: the {@code [proxy]} table of the Ballerina home's
 * {@code Settings.toml}, keys {@code host}, {@code port}, {@code username} and {@code password}.
 *
 * <p>Read with {@code bal}'s own {@link SettingsBuilder}, and held to the rule {@code bal pull} applies before
 * using one: a proxy needs a non-empty host and a positive port, and authenticates only when both a username and
 * a password are set. Everything short of that — no file, no table, a value of the wrong type — is no proxy,
 * silently, as it is for {@code bal pull}; a malformed file counts for what the parser salvages from it, since
 * {@code bal pull} ignores its diagnostics too.
 *
 * @param host the proxy's host name or address
 * @param port the proxy's port
 * @param username the proxy user, or empty
 * @param password the proxy user's password, or empty
 * @since 0.1.0
 */
public record ProxySettings(String host, int port, String username, String password) {

    /** The proxy the {@code Settings.toml} under {@code home} configures, if it configures a usable one. */
    public static Optional<ProxySettings> read(Path home) {
        String content;
        try {
            content = Files.readString(home.resolve(ProjectConstants.SETTINGS_FILE_NAME), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
        return parse(content);
    }

    /** The proxy a {@code Settings.toml}'s content configures, if it configures a usable one. */
    public static Optional<ProxySettings> parse(String settingsToml) {
        Proxy proxy;
        try {
            proxy = SettingsBuilder.from(TomlDocument.from(ProjectConstants.SETTINGS_FILE_NAME, settingsToml))
                    .settings().getProxy();
        } catch (RuntimeException unparsable) {
            return Optional.empty();
        }
        if (proxy == null || proxy.host() == null || proxy.host().isEmpty() || proxy.port() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new ProxySettings(proxy.host(), proxy.port(),
                proxy.username() == null ? "" : proxy.username(), proxy.password() == null ? "" : proxy.password()));
    }

    /** Whether the proxy is sent credentials: only with both a username and a password, as for {@code bal pull}. */
    public boolean authenticates() {
        return !username.isEmpty() && !password.isEmpty();
    }

    @Override
    public String toString() {
        return "ProxySettings[host=" + host + ", port=" + port + ", username=" + username + "]";
    }
}
