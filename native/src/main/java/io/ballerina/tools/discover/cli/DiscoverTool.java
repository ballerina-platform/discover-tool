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

import io.ballerina.cli.BLauncherCmd;
import io.ballerina.projects.util.ProjectConstants;
import io.ballerina.tools.discover.Failure;
import io.ballerina.tools.discover.cache.CacheLocation;
import io.ballerina.tools.discover.cache.DiskCache;
import io.ballerina.tools.discover.cache.DocsCache;
import io.ballerina.tools.discover.central.DependenciesToml;
import io.ballerina.tools.discover.central.HttpOptions;
import io.ballerina.tools.discover.central.JdkHttpTransport;
import io.ballerina.tools.discover.central.ProxySettings;
import org.wso2.ballerinalang.util.RepoUtils;
import picocli.CommandLine;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point for the {@code bal discover} CLI tool: the process wrapper.
 *
 * <p>Kept apart from {@link Cli} so the CLI's behaviour is testable without a subprocess. This is ALSO the only
 * class that reads the environment or touches a filesystem the caller did not name, which is what keeps every
 * test in the suite hermetic: they drive {@link Cli#run} with an injected cache and cannot accidentally read or
 * write a developer's real {@code ~/.cache}.
 *
 * <p>{@code bal} hands the whole argument list through unparsed, so the tool owns its own grammar, and
 * {@link System#exit} here is what carries the exit code to the shell.
 *
 * @since 0.1.0
 */
@CommandLine.Command(name = "discover")
public class DiscoverTool implements BLauncherCmd {

    @CommandLine.Parameters(arity = "0..*")
    private List<String> argList;

    /**
     * Declared only so {@code bal}'s own launcher hands it over: undeclared, the launcher rejects it with
     * {@code ballerina: unknown option: '--help'} at exit 1. It is put back on the argument list, so {@link Cli}
     * alone decides what help means.
     */
    @CommandLine.Option(names = {"--help", "-h"}, hidden = true)
    private boolean helpFlag;

    private final PrintStream outStream;
    private final PrintStream errStream;

    /** Whether {@link #execute()} ends the process. Off in tests, which assert the code instead. */
    private final boolean exits;

    private int exitCode;

    public DiscoverTool() {
        this(System.out, System.err, true);
    }

    /** For tests: both streams captured, and no {@link System#exit}. */
    public DiscoverTool(PrintStream outStream, PrintStream errStream) {
        this(outStream, errStream, false);
    }

    private DiscoverTool(PrintStream outStream, PrintStream errStream, boolean exits) {
        this.outStream = outStream;
        this.errStream = errStream;
        this.exits = exits;
    }

    /** The code the last {@link #execute()} produced. Only meaningful for the non-exiting constructor. */
    public int exitCode() {
        return exitCode;
    }

    @Override
    public String getName() {
        return "discover";
    }

    @Override
    public void execute() {
        List<String> argv = new ArrayList<>(argList == null ? List.of() : argList);
        if (helpFlag) {
            argv.add("--help");
        }
        Path home = ballerinaHome();
        HttpOptions.Builder builder = HttpOptions.builder().cache(buildCache());
        if (home != null) {
            builder.settingsFile(ProxySettings.displayPath(home.resolve(ProjectConstants.SETTINGS_FILE_NAME),
                    property("user.home")));
            ProxySettings.read(home).ifPresent(proxy -> builder.transport(new JdkHttpTransport(proxy)).proxy(proxy));
        }
        HttpOptions http = builder.build();
        Cli.Streams streams = new Cli.Streams(outStream::print, errStream::print);

        // Pre-JDK 22, System.console() is null if EITHER stream is redirected, so a terminal with redirected
        // stdin is under-detected. That is the safe direction: a human gets JSON (recoverable with
        // --output text) rather than an agent's parser getting prose.
        boolean interactive = System.console() != null;
        int code;
        try {
            code = Cli.run(argv, streams, http, discoverProject(), interactive);
        } catch (RuntimeException cause) {
            // Nothing in the pipeline throws by design; if something does, it is a defect in this tool and the
            // caller still needs a failure in the run's own mode rather than a Java stack trace on stdout.
            Failure failure = new Failure.Internal(messageOf(cause), Failure.INTERNAL_SUGGESTION);
            errStream.print((Cli.jsonOutput(argv, interactive) ? failure.describe() : failure.describeText()) + "\n");
            code = 1;
        }

        outStream.flush();
        errStream.flush();
        this.exitCode = code;
        // Only a non-zero code needs `System.exit`: `bal` exits 0 on its own, and short-circuiting the launcher
        // on the success path would skip whatever it does after a tool returns.
        if (exits && code != 0) {
            System.exit(code);
        }
    }

    private static String messageOf(Throwable cause) {
        return cause.getMessage() == null || cause.getMessage().isEmpty()
                ? cause.getClass().getName()
                : cause.getMessage();
    }

    private static Path ballerinaHome() {
        try {
            return RepoUtils.createAndGetHomeReposPath();
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private static DocsCache buildCache() {
        CacheLocation.Environment environment = new CacheLocation.Environment(
                System.getenv(),
                property("user.home"),
                property("java.io.tmpdir"),
                property("user.name"));

        List<CacheLocation.Candidate> candidates = CacheLocation.candidates(environment);
        for (CacheLocation.Candidate candidate : candidates) {
            if (candidate instanceof CacheLocation.Candidate.Disabled) {
                return DocsCache.NULL;
            }
            CacheLocation.Candidate.Directory directory = (CacheLocation.Candidate.Directory) candidate;
            Path root = Path.of(directory.root());
            if (!DiskCache.isUsableRoot(root, directory.mode())) {
                continue;
            }
            return DiskCache.at(root, directory.mode());
        }

        // Nothing worked. `describe()` still has to name something for a diagnostic, so the last candidate is
        // reported as the one that was tried.
        CacheLocation.Candidate last = candidates.get(candidates.size() - 1);
        return last instanceof CacheLocation.Candidate.Directory directory
                ? DiskCache.at(Path.of(directory.root()), directory.mode())
                : DocsCache.NULL;
    }

    private static String property(String name) {
        String value = System.getProperty(name);
        return value == null ? "" : value;
    }

    private static String discoverProject() {
        String cwd = property("user.dir");
        if (cwd.isEmpty()) {
            return null;
        }
        Path project = DependenciesToml.discoverProject(Path.of(cwd));
        return project == null ? null : project.toString();
    }

    @Override
    public void printLongDesc(StringBuilder sb) {
        sb.append(Usage.root());
    }

    /** Deprecated on {@link BLauncherCmd} itself, but still abstract, so it has to be implemented. */
    @Override
    @Deprecated
    public void printUsage(StringBuilder sb) {
        sb.append("  ").append(UsageRenderer.synopsisLine(Commands.Grammar.create())).append("\n");
    }

    @Override
    public void setParentCmdParser(CommandLine parentCmdParser) {
    }
}
