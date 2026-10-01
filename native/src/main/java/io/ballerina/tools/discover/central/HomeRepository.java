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
import io.ballerina.tools.discover.Failure;
import io.ballerina.tools.discover.QualifiedName;
import io.ballerina.tools.discover.Result;
import io.ballerina.tools.discover.Version;
import io.ballerina.tools.discover.central.schema.CentralDocs;
import org.wso2.ballerinalang.util.RepoUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The Central packages already on this machine: the unpacked balas {@code bal pull} and {@code bal build} leave in
 * the user's Ballerina home, and the standard library a distribution ships there —
 * {@code <home>/repositories/central.ballerina.io/bala/<org>/<name>/<version>/<platform>/}.
 *
 * <p>Module sources only. It serves no version and no docs payload: those stay Central's, so a stale or partial
 * local copy can never change what a lookup reports, only spare the archive download.
 *
 * <p>The home is {@code bal}'s own — {@link RepoUtils#createAndGetHomeReposPath()}, which honours
 * {@code BALLERINA_HOME_DIR} and otherwise uses {@code ~/.ballerina} and creates nothing — and the directory names
 * are {@link ProjectConstants}'. {@code BALLERINA_DEV_CENTRAL} and {@code BALLERINA_STAGE_CENTRAL} only change the
 * remote URL there: {@code BallerinaUserHome} caches every Central variant under the same
 * {@code central.ballerina.io} directory, so there is no variant directory to choose.
 *
 * @since 0.1.0
 */
public final class HomeRepository implements PackageRepository {

    private final Optional<Path> home;

    private HomeRepository(Optional<Path> home) {
        this.home = home;
    }

    /** The home {@code bal} itself uses, or none when it cannot be determined. */
    public static HomeRepository fromEnvironment() {
        try {
            return new HomeRepository(Optional.of(RepoUtils.createAndGetHomeReposPath()));
        } catch (RuntimeException | LinkageError unavailable) {
            return new HomeRepository(Optional.empty());
        }
    }

    /** @param home a Ballerina user home, laid out as {@code ~/.ballerina} is */
    public static HomeRepository at(Path home) {
        return new HomeRepository(Optional.of(home));
    }

    @Override
    public Result<CentralClient.ResolvedVersion> resolveVersion(QualifiedName qualified, HttpOptions options) {
        return Result.err(sourcesOnly(qualified.qualified()));
    }

    @Override
    public Result<CentralDocs> fetchDocs(
            QualifiedName qualified, CentralClient.ResolvedVersion resolved, HttpOptions options) {
        return Result.err(sourcesOnly(qualified.versioned(resolved.version())));
    }

    private static Failure sourcesOnly(String coordinate) {
        return new Failure.PackageNotFound(coordinate,
                "The Ballerina home repository serves package source only; Central answers this.");
    }

    /**
     * The exact version's module, from whichever platform directory ({@code java21}, {@code any}, …) holds it.
     * Anything short of that — no such version, no such module, an unreadable file — is empty.
     */
    @Override
    public Optional<Map<String, String>> fetchModuleSources(
            QualifiedName qualified, Version version, String moduleId, HttpOptions options) {
        if (home.isEmpty()) {
            return Optional.empty();
        }
        Path versionDir = home.get().resolve(ProjectConstants.REPOSITORIES_DIR)
                .resolve(ProjectConstants.CENTRAL_REPOSITORY_CACHE_NAME)
                .resolve(ProjectConstants.BALA_DIR_NAME)
                .resolve(qualified.org())
                .resolve(qualified.name())
                .resolve(version.text());
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
        Map<String, String> files = new TreeMap<>();
        List<Path> sources;
        try (Stream<Path> listing = Files.list(moduleDir)) {
            sources = listing.filter(path -> String.valueOf(path.getFileName()).endsWith(".bal"))
                    .filter(Files::isRegularFile)
                    .toList();
        }
        for (Path source : sources) {
            files.put(String.valueOf(source.getFileName()), Files.readString(source, StandardCharsets.UTF_8));
        }
        return files.isEmpty() ? Optional.empty() : Optional.of(files);
    }

    @Override
    public String id() {
        return "home";
    }

    @Override
    public String describe() {
        return "Ballerina home repository (" + home.map(Path::toString).orElse("unavailable") + ")";
    }
}
