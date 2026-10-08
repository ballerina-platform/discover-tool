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

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The {@code [proxy]} table is read as {@code bal pull} reads it: a host and a positive port make a proxy,
 * credentials count only in pairs, and anything less is no proxy rather than a failure.
 *
 * @since 0.1.0
 */
public class ProxySettingsTest {

    @Test
    public void aHostAndPortMakeAProxyWithoutCredentials() {
        Optional<ProxySettings> proxy = ProxySettings.parse("""
                [proxy]
                host = "proxy.example.com"
                port = 3128
                """);
        Assert.assertEquals(proxy, Optional.of(new ProxySettings("proxy.example.com", 3128, "", "")));
        Assert.assertFalse(proxy.get().authenticates());
    }

    @Test
    public void aUsernameAndPasswordTogetherAuthenticate() {
        ProxySettings proxy = ProxySettings.parse("""
                [central]
                accesstoken = "token"

                [proxy]
                host = "10.0.0.1"
                port = 8080
                username = "alice"
                password = "secret"
                """).orElseThrow();
        Assert.assertEquals(proxy, new ProxySettings("10.0.0.1", 8080, "alice", "secret"));
        Assert.assertTrue(proxy.authenticates());
        Assert.assertFalse(proxy.toString().contains("secret"), proxy.toString());
    }

    @Test
    public void aUsernameWithoutAPasswordDoesNotAuthenticate() {
        ProxySettings proxy = ProxySettings.parse("""
                [proxy]
                host = "proxy.example.com"
                port = 3128
                username = "alice"
                """).orElseThrow();
        Assert.assertFalse(proxy.authenticates());
    }

    @Test
    public void anythingShortOfAHostAndAPositivePortIsNoProxy() {
        for (String settings : new String[] {
                "",
                "[central]\naccesstoken = \"token\"\n",
                "[proxy]\n",
                "[proxy]\nhost = \"\"\nport = 3128\n",
                "[proxy]\nhost = \"proxy.example.com\"\n",
                "[proxy]\nhost = \"proxy.example.com\"\nport = 0\n",
                "[proxy]\nhost = \"proxy.example.com\"\nport = -1\n",
                "[proxy]\nhost = \"proxy.example.com\"\nport = \"3128\"\n",
                "[proxy]\nhost = 42\nport = 3128\n",
                "proxy = \"proxy.example.com:3128\"\n",
                "[proxy\nhost = \"proxy.example.com\"\n"}) {
            Assert.assertEquals(ProxySettings.parse(settings), Optional.empty(), settings);
        }
    }

    @Test
    public void theSettingsFileIsReadFromTheBallerinaHomeAndAMissingOneIsNoProxy() throws IOException {
        Path home = Files.createTempDirectory("bal-discover-home-");
        Assert.assertEquals(ProxySettings.read(home), Optional.empty());
        Files.writeString(home.resolve("Settings.toml"), "[proxy]\nhost = \"127.0.0.1\"\nport = 3128\n",
                StandardCharsets.UTF_8);
        Assert.assertEquals(ProxySettings.read(home), Optional.of(new ProxySettings("127.0.0.1", 3128, "", "")));
    }
}
