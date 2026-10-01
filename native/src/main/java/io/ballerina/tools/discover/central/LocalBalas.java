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

import io.ballerina.projects.util.ProjectConstants;
import io.ballerina.tools.discover.QualifiedName;
import io.ballerina.tools.discover.Version;
import org.wso2.ballerinalang.util.RepoUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Packages already unpacked on this machine, laid out {@code <root>/<org>/<name>/<version>/<platform>/modules/}:
 *
 * <ul>
 *   <li>the user's Ballerina home repository, where {@code bal pull} and {@code bal build} put what they fetch from
 *       Central — {@code <home>/repositories/central.ballerina.io/bala};</li>
 *   <li>the running distribution's own repository, which holds the standard library it ships —
 *       {@code <ballerina.home>/repo/bala}.</li>
 * </ul>
 *
 * <p>The locations are {@code bal}'s own: the home from {@link RepoUtils#createAndGetHomeReposPath()}, which honours
 * {@code BALLERINA_HOME_DIR} and otherwise uses {@code ~/.ballerina} and creates nothing; the distribution from the
 * {@code ballerina.home} system property the {@code bal} launcher sets; the directory names from
 * {@link ProjectConstants}. {@code BALLERINA_DEV_CENTRAL} and {@code BALLERINA_STAGE_CENTRAL} change only the remote
 * URL there — {@code BallerinaUserHome} caches every Central variant under the same {@code central.ballerina.io}
 * directory — so there is no variant directory to choose.
 *
 * @since 0.1.0
 */
public final class LocalBalas implements ModuleSources {

    private final Path root;

    private LocalBalas(Path root) {
        this.root = root;
    }

    /** @param home a Ballerina user home, laid out as {@code ~/.ballerina} is */
    public static LocalBalas homeRepository(Path home) {
        return new LocalBalas(home.resolve(ProjectConstants.REPOSITORIES_DIR)
                .resolve(ProjectConstants.CENTRAL_REPOSITORY_CACHE_NAME)
                .resolve(ProjectConstants.BALA_DIR_NAME));
    }

    /** @param ballerinaHome a distribution's install directory, the {@code ballerina.home} property's value */
    public static LocalBalas distributionRepository(Path ballerinaHome) {
        return new LocalBalas(ballerinaHome.resolve(ProjectConstants.DIST_CACHE_DIRECTORY)
                .resolve(ProjectConstants.BALA_DIR_NAME));
    }

    /** The home repository, then the distribution's, as this process's {@code bal} would find them. */
    public static List<ModuleSources> fromEnvironment() {
        List<ModuleSources> found = new ArrayList<>();
        try {
            found.add(homeRepository(RepoUtils.createAndGetHomeReposPath()));
        } catch (RuntimeException | LinkageError unavailable) {
            // No home to look in: the distribution and the download remain.
        }
        String ballerinaHome = System.getProperty(ProjectConstants.BALLERINA_HOME);
        if (ballerinaHome != null && !ballerinaHome.isBlank()) {
            found.add(distributionRepository(Path.of(ballerinaHome)));
        }
        return List.copyOf(found);
    }

    /**
     * The exact version's module, from whichever platform directory ({@code java21}, {@code any}, …) holds it.
     * Anything short of that — no such version, no such module, an unreadable file — is empty.
     */
    @Override
    public Optional<Map<String, String>> moduleSources(
            QualifiedName qualified, Version version, String moduleId, HttpOptions options) {
        Path versionDir = root.resolve(qualified.org()).resolve(qualified.name()).resolve(version.text());
        try (Stream<Path> platforms = Files.list(versionDir)) {
            for (Path platform : platforms.sorted().toList()) {
                Optional<Map<String, String>> sources = readModule(platform.resolve("modules").resolve(moduleId));
                if (sources.isPresent()) {
                    return sources;
                }
            }
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<Map<String, String>> readModule(Path moduleDir) throws IOException {
        if (!Files.isDirectory(moduleDir)) {
            return Optional.empty();
        }
        List<Path> sources;
        try (Stream<Path> listing = Files.list(moduleDir)) {
            sources = listing.filter(path -> String.valueOf(path.getFileName()).endsWith(".bal"))
                    .filter(Files::isRegularFile)
                    .toList();
        }
        Map<String, String> files = new TreeMap<>();
        for (Path source : sources) {
            files.put(String.valueOf(source.getFileName()), Files.readString(source, StandardCharsets.UTF_8));
        }
        return files.isEmpty() ? Optional.empty() : Optional.of(files);
    }
}
