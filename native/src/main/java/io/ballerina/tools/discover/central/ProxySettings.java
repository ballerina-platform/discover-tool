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

import io.ballerina.projects.internal.model.Proxy;
import org.wso2.ballerinalang.util.RepoUtils;

import java.util.Optional;

/**
 * The HTTP proxy {@code bal pull} would use, from the {@code [proxy]} table of the Ballerina home's
 * {@code Settings.toml}, read and held to the same rule {@code bal pull} applies before using one.
 *
 * @param host the proxy's host name or address
 * @param port the proxy's port
 * @param username the proxy user, or empty
 * @param password the proxy user's password, or empty
 * @since 0.1.0
 */
public record ProxySettings(String host, int port, String username, String password) {

    /**
     * The proxy the Ballerina home's {@code Settings.toml} configures, read by {@code bal pull}'s own reader. A
     * file it cannot read is no proxy, as it is for {@code bal pull}.
     */
    public static Optional<ProxySettings> configured() {
        try {
            return of(RepoUtils.readSettings().getProxy());
        } catch (RuntimeException | LinkageError unreadable) {
            return Optional.empty();
        }
    }

    // bal pull's rule (ProjectUtils.initializeProxy) is kept rather than called: it resolves the host on the spot
    // and drops the credentials, and an unresolvable proxy host has a failure of its own here.
    static Optional<ProxySettings> of(Proxy proxy) {
        if (proxy == null || proxy.host() == null || proxy.host().isEmpty() || proxy.port() <= 0) {
            return Optional.empty();
        }
        return Optional.of(new ProxySettings(proxy.host(), proxy.port(), orEmpty(proxy.username()),
                orEmpty(proxy.password())));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
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
