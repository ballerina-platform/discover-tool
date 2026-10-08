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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * A proxy that reads one {@code CONNECT} per connection, answers {@code refusal} (a 407 by default) unless it
 * carries {@code expected}, and otherwise opens a tunnel it closes at once: enough to see what the client sent,
 * with no origin behind it. Each request is recorded as its request line, a space, and its credential.
 *
 * @since 0.1.0
 */
public final class ConnectProxy implements AutoCloseable {

    public static final String CHALLENGE = "HTTP/1.1 407 Proxy Authentication Required\r\n"
            + "Proxy-Authenticate: Basic realm=\"test\"\r\nContent-Length: 0\r\n\r\n";

    private static final String ESTABLISHED = "HTTP/1.1 200 Connection Established\r\n\r\n";
    private static final String CREDENTIAL_HEADER = "proxy-authorization:";
    private static final int BACKLOG = 16;

    private final ServerSocket socket = new ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress());
    private final List<String> requests = new ArrayList<>();

    public ConnectProxy(String expected) throws IOException {
        this(expected, CHALLENGE);
    }

    public ConnectProxy(String expected, String refusal) throws IOException {
        Thread acceptor = new Thread(() -> {
            while (!socket.isClosed()) {
                try (Socket connection = socket.accept()) {
                    String head = readHead(connection.getInputStream());
                    String request = head.lines().findFirst().orElse("");
                    String credential = head.lines()
                            .filter(line -> line.toLowerCase(Locale.ROOT).startsWith(CREDENTIAL_HEADER))
                            .map(line -> line.substring(line.indexOf(':') + 1).trim())
                            .findFirst().orElse("");
                    synchronized (requests) {
                        requests.add(request + " " + credential);
                    }
                    String reply = expected.equals(credential) ? ESTABLISHED : refusal;
                    connection.getOutputStream().write(reply.getBytes(StandardCharsets.ISO_8859_1));
                    connection.getOutputStream().flush();
                } catch (IOException closed) {
                    // The test is over, or the client gave up on this connection.
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        byte[] end = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        for (int next = in.read(); next >= 0; next = in.read()) {
            head.write(next);
            matched = next == end[matched] ? matched + 1 : (next == end[0] ? 1 : 0);
            if (matched == end.length) {
                break;
            }
        }
        return head.toString(StandardCharsets.ISO_8859_1);
    }

    public int port() {
        return socket.getLocalPort();
    }

    public List<String> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
