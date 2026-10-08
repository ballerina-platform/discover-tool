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

import io.ballerina.tools.discover.central.JdkHttpTransport;
import io.ballerina.tools.discover.central.ProxySettings;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.Arrays;

/**
 * The entry a forked JVM runs: a fresh process, where no HTTP client exists before the tool's own.
 *
 * <p>{@code tool <argv...>} runs {@link DiscoverTool} as {@code bal} does and exits with its code;
 * {@code transport <port> <username> <password> <requests>} prepares the process as {@link DiscoverTool} does,
 * then sends that many requests through one transport and prints each reply on a line.
 *
 * @since 0.1.0
 */
public final class ForkedRun {

    static final String TOOL = "tool";
    static final String TRANSPORT = "transport";
    static final String TUNNELLED_URL = "https://central.invalid/2.0/docs/ballerina/http";

    private static final String LOOPBACK = "127.0.0.1";
    private static final long TIMEOUT_MS = 5_000;

    private ForkedRun() {
    }

    public static void main(String[] args) {
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        if (TRANSPORT.equals(args[0])) {
            transport(rest);
            return;
        }
        DiscoverTool tool = new DiscoverTool(System.out, System.err);
        new CommandLine(tool).parseArgs(rest);
        tool.execute();
        System.exit(tool.exitCode());
    }

    private static void transport(String[] args) {
        DiscoverTool.allowBasicProxyAuthInTunnels();
        JdkHttpTransport transport = new JdkHttpTransport(
                new ProxySettings(LOOPBACK, Integer.parseInt(args[0]), args[1], args[2]));
        int requests = Integer.parseInt(args[3]);
        PrintStream out = System.out;
        for (int i = 0; i < requests; i++) {
            out.println(transport.get(TUNNELLED_URL, TIMEOUT_MS));
        }
    }
}
