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

import java.io.InputStream;
import java.util.Optional;

/**
 * One HTTP GET, as a value.
 *
 * <p>This is the seam every retry test drives. Transport trouble is returned rather than thrown, because the
 * retry policy above it has to distinguish three outcomes that an exception hierarchy blurs: an answer with a
 * status worth retrying, a request that ran out of time, and a socket that never connected. A test that wants
 * "503 then 200" scripts two {@link Reply.Answered} values and never opens a socket.
 *
 * @since 0.1.0
 */
public interface HttpTransport {

    /** One attempt. Never throws; every outcome is a {@link Reply}. */
    Reply get(String url, long timeoutMs);

    /**
     * One binary GET — a package archive rather than a JSON document — as a stream the caller closes, with no
     * retries: every caller treats a missing answer as "fall back to what the docs payload says", so the reason is
     * not worth carrying. {@code timeoutMs} bounds the whole transfer, body included: a body not complete by then
     * is no answer.
     */
    default Optional<InputStream> openStream(String url, long timeoutMs) {
        return Optional.empty();
    }

    /** What one attempt produced. */
    sealed interface Reply {

        /**
         * Central answered.
         *
         * @param status the HTTP status Central answered with
         * @param body the response body
         * @param retryAfter the raw {@code Retry-After} header, or {@code null}
         */
        record Answered(int status, String body, String retryAfter) implements Reply {

            public boolean isOk() {
                return status >= 200 && status < 300;
            }
        }

        /** The attempt ran out of time. */
        record TimedOut() implements Reply { }

        /**
         * The request never produced a response from Central.
         *
         * @param problem what went wrong
         * @param message what the transport said
         * @param status the proxy's answer to the {@code CONNECT} for {@link Problem#TUNNEL}, else {@code null}
         */
        record Failed(Problem problem, String message, Integer status) implements Reply {

            public Failed(Problem problem, String message) {
                this(problem, message, null);
            }
        }

        /** Why a request produced no response from Central. */
        enum Problem {
            /** The connection to the proxy, or to Central when there is none, could not be made. */
            UNCONNECTED,
            /** The proxy's host name, or Central's when there is no proxy, did not resolve. */
            UNRESOLVED,
            TLS,
            /** The proxy answered the {@code CONNECT} with an error status of its own, other than 407. */
            TUNNEL,
            PROXY_REJECTED,
            /** The URL could not be made into a request: a defect, not the network. */
            BAD_URL,
            OTHER
        }
    }
}
