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

import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.symbols.Surface;
import io.ballerina.tools.discover.views.Containers;
import io.ballerina.tools.discover.views.Readme;
import io.ballerina.tools.discover.views.Types;

import java.util.ArrayList;
import java.util.List;

/**
 * A view asked the way a command asks it — {@code method send --client Producer} — without the CLI around it, so a
 * test of what an answer holds reads as the command a caller types.
 */
final class Asked {

    private Asked() {
    }

    /** {@code words} as one command line after {@code -p}: a noun, its names, and its flags. */
    static Result<DiscoverResult> of(LoadedPackage loaded, String... words) {
        String noun = words[0];
        List<String> names = new ArrayList<>();
        String filter = null;
        int page = 1;
        Surface.Scope owner = null;
        String ownerName = null;
        for (int i = 1; i < words.length; i++) {
            String word = words[i];
            switch (word) {
                case "--filter" -> filter = words[++i];
                case "--page" -> page = Integer.parseInt(words[++i]);
                case "--client", "--class", "--service" -> {
                    owner = Surface.Scope.ofVerb(word.substring(2)).orElseThrow();
                    ownerName = words[++i];
                }
                default -> names.add(word);
            }
        }
        String name = names.isEmpty() ? null : names.get(0);
        return switch (noun) {
            case "type" -> Types.render(loaded, new Types.Options(names, filter, page));
            case "readme" -> Readme.render(loaded, new Readme.Options(name, filter, page));
            case "function" -> Containers.render(loaded, Surface.Scope.MODULE,
                    Containers.Options.function(name, filter, page));
            case "method" -> Containers.render(loaded, owner, Containers.Options.method(ownerName, name, filter,
                    page));
            case "resource" -> Containers.render(loaded, owner, Containers.Options.resource(ownerName, names, filter,
                    page));
            default -> Containers.render(loaded, Surface.Scope.ofVerb(noun).orElseThrow(),
                    Containers.Options.container(name, filter, page));
        };
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
        String flag = "--" + scope.verb();
        return member.length == 1
                ? of(loaded, "method", member[0], flag, container)
                : of(loaded, "resource", member[0], member[1], flag, container);
    }

    /** {@link #of} with the line split on spaces, for a command whose names hold none. */
    static Result<DiscoverResult> line(LoadedPackage loaded, String line) {
        return of(loaded, line.split(" "));
    }
}
