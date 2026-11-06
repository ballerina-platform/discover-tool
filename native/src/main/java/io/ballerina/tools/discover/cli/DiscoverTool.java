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
import io.ballerina.tools.discover.central.ProxySettings;
import org.wso2.ballerinalang.util.RepoUtils;
import picocli.CommandLine;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Entry point for the {@code bal discover} CLI tool: the process wrapper.
 *
 * <p>Kept apart from {@link Cli} so the CLI's behaviour is testable without a subprocess. This is ALSO the only
 * class that reads the environment or touches a filesystem the caller did not name, which is what keeps every
 * test in the suite hermetic: they drive {@link Cli#run} with an injected cache and cannot accidentally read or
 * write a developer's real {@code ~/.cache}.
 *
 * <p>{@code bal} hands the whole argument list through unparsed (see {@link #setParentCmdParser}), so the tool owns
 * its own grammar, and {@link System#exit} here is what carries the exit code to the shell.
 *
 * @since 0.1.0
 */
@CommandLine.Command(name = "discover")
public class DiscoverTool implements BLauncherCmd {

    private static final String BASIC_SCHEME = "Basic";

    @CommandLine.Parameters(arity = "0..*")
    private List<String> argList;

    /**
     * Declared only so {@code bal}'s own launcher hands it over: undeclared, the launcher rejects it with
     * {@code ballerina: unknown option: '--help'} at exit 1. It is put back on the argument list, so {@link Cli}
     * alone decides what help means.
     */
    @CommandLine.Option(names = {Commands.HELP_FLAG, Commands.HELP_SHORT}, hidden = true)
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
        boolean basicProxyAuthAllowed = allowBasicProxyAuthInTunnels();
        List<String> argv = arguments();
        HttpOptions.Builder builder = HttpOptions.builder()
                .cache(buildCache())
                .basicProxyAuthDisabled(!basicProxyAuthAllowed)
                .proxy(ProxySettings.configured().orElse(null));
        Path home = ballerinaHome();
        if (home != null) {
            builder.settingsFile(displayPath(home.resolve(ProjectConstants.SETTINGS_FILE_NAME), property("user.home")));
        }
        HttpOptions http = builder.build();
        Cli.Streams streams = new Cli.Streams(outStream::print, errStream::print);

        boolean interactive = Terminal.stdoutIsTerminal();
        int code = guarded(() -> Cli.run(argv, streams, http, discoverProject(), interactive), argv, interactive,
                errStream);

        outStream.flush();
        errStream.flush();
        this.exitCode = code;
        // Only a non-zero code needs `System.exit`: `bal` exits 0 on its own, and short-circuiting the launcher
        // on the success path would skip whatever it does after a tool returns.
        if (exits && code != 0) {
            System.exit(code);
        }
    }

    List<String> arguments() {
        List<String> argv = new ArrayList<>(argList == null ? List.of() : argList);
        if (helpFlag) {
            argv.add(Commands.HELP_FLAG);
        }
        return argv;
    }

    static int guarded(IntSupplier run, List<String> argv, boolean interactive, PrintStream errStream) {
        try {
            return run.getAsInt();
        } catch (RuntimeException | Error cause) {
            // Nothing throws by design; a defect, or an exhausted heap, still reaches the caller as a failure in
            // the run's own mode.
            Failure failure = new Failure.Internal(messageOf(cause), Failure.INTERNAL_SUGGESTION);
            errStream.print(failure.describe(Cli.jsonOutput(argv, interactive)) + "\n");
            return failure.exitCode();
        }
    }

    // Lets Basic answer a tunnel's proxy challenge, as bal pull does; keeps a value the user set. Must run before
    // any HttpClient exists: the JDK reads the property once.
    static boolean allowBasicProxyAuthInTunnels() {
        Object chosen = System.getProperties().putIfAbsent(HttpOptions.TUNNELING_DISABLED_SCHEMES, "");
        return chosen == null || Arrays.stream(chosen.toString().split(","))
                .map(String::trim)
                .noneMatch(BASIC_SCHEME::equalsIgnoreCase);
    }

    static String displayPath(Path file, String userHome) {
        if (userHome != null && !userHome.isEmpty()) {
            Path home = Path.of(userHome);
            if (file.startsWith(home) && !file.equals(home)) {
                return "~" + file.getFileSystem().getSeparator() + home.relativize(file);
            }
        }
        return file.toString();
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

        return DocsCache.NULL;
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

    /**
     * Makes {@code bal}'s launcher pass every argument through. Left to its defaults, the launcher parses a flag
     * before the first word as its own and rejects it ({@code ballerina: unknown option: '-p'}), so
     * {@code bal discover -p ballerinax/kafka method send} would never reach the tool.
     */
    @Override
    public void setParentCmdParser(CommandLine parentCmdParser) {
        CommandLine own = parentCmdParser.getSubcommands().get(getName());
        if (own != null) {
            own.setUnmatchedOptionsArePositionalParams(true);
            own.setStopAtPositional(true);
        }
    }
}
