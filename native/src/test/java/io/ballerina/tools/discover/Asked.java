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

import io.ballerina.tools.discover.cli.Cli;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.symbols.Surface;

import java.util.List;

/**
 * A view asked the way a command asks it — {@code method send --client Producer} — through the CLI's own parse and
 * dispatch, but of a package already loaded, so a test of what an answer holds reads as the command a caller types.
 */
final class Asked {

    private Asked() {
    }

    /** {@code words} as one command line after {@code -p}: a noun, its names, and its flags. */
    static Result<DiscoverResult> of(LoadedPackage loaded, String... words) {
        return Cli.answerOf(loaded, List.of(words));
    }

    /**
     * One member of {@code container}: a method by its name, or a resource method by its path and accessor; the
     * module's function by its name when {@code scope} is the module's.
     */
    static Result<DiscoverResult> member(LoadedPackage loaded, Surface.Scope scope, String container,
            String... member) {
        if (scope == Surface.Scope.MODULE) {
            return of(loaded, "function", container);
        }
        String flag = scope.commandFlag();
        return member.length == 1
                ? of(loaded, "method", member[0], flag, container)
                : of(loaded, "resource", member[0], member[1], flag, container);
    }

    /** {@link #of} with the line split on spaces, for a command whose names hold none. */
    static Result<DiscoverResult> line(LoadedPackage loaded, String line) {
        return of(loaded, line.split(" "));
    }
}
