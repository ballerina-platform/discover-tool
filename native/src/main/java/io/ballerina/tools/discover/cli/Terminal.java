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

import java.io.Console;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

/**
 * Whether stdout is a terminal, the default output mode's only input.
 *
 * <p>{@link System#console()} answers for stdin and stdout together, so a terminal run with stdin redirected would
 * read as not a terminal. The C library's {@code isatty} answers for stdout alone; it is called only when the JVM
 * grants this code native access ({@code bal} starts it with {@code --enable-native-access=ALL-UNNAMED}), since
 * otherwise the JDK prints a warning on stderr.
 *
 * @since 0.1.0
 */
final class Terminal {

    private static final int STDOUT_FD = 1;
    private static final String POSIX_ISATTY = "isatty";
    private static final String WINDOWS_ISATTY = "_isatty";

    private Terminal() {
    }

    static boolean stdoutIsTerminal() {
        if (!Terminal.class.getModule().isNativeAccessEnabled()) {
            return consoleIsTerminal();
        }
        try {
            Linker linker = Linker.nativeLinker();
            SymbolLookup libc = linker.defaultLookup();
            Optional<MemorySegment> isatty = libc.find(POSIX_ISATTY).or(() -> libc.find(WINDOWS_ISATTY));
            if (isatty.isEmpty()) {
                return consoleIsTerminal();
            }
            MethodHandle call = linker.downcallHandle(isatty.get(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            return (int) call.invokeExact(STDOUT_FD) != 0;
        } catch (Throwable unavailable) {
            return consoleIsTerminal();
        }
    }

    private static boolean consoleIsTerminal() {
        Console console = System.console();
        return console != null && console.isTerminal();
    }
}
