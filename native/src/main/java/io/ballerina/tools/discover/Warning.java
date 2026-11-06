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

package io.ballerina.tools.discover;

import com.google.gson.JsonObject;

/**
 * A report on stderr that does not fail the run: the answer is still on stdout, and the exit code is unchanged.
 *
 * @param kind the discriminator an agent branches on
 * @param message what the caller should know, lowercase-first, with no closing period
 * @since 0.1.0
 */
public record Warning(String kind, String message) {

    /** The version was read from the cache because Central could not be reached to confirm it. */
    public static final String STALE_CACHE = "stale-cache";

    /** The warning as one line of stderr in the run's output mode, without a final newline. */
    public String describe(boolean json) {
        if (!json) {
            return Command.PROGRAM + ": warning: " + message;
        }
        JsonObject report = new JsonObject();
        report.addProperty("severity", "warning");
        report.addProperty("kind", kind);
        report.addProperty("message", message);
        return report.toString();
    }
}
