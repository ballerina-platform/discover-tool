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

package io.ballerina.tools.discover.cache;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The cache, on disk.
 *
 * <pre>
 *   &lt;root&gt;/v2/docs/&lt;repository&gt;/&lt;org&gt;/&lt;name&gt;/&lt;version&gt;.json      mode 0600, no TTL
 *   &lt;root&gt;/v2/latest/&lt;repository&gt;/&lt;org&gt;/&lt;name&gt;.json              {"version":"6.0.0","atMs":…}
 *   &lt;root&gt;/v2/modules/&lt;repository&gt;/&lt;org&gt;/&lt;name&gt;/&lt;module&gt;/&lt;version&gt;.json
 *                                                                          a submodule's page, mode 0600, no TTL
 *   &lt;root&gt;/v2/inclusions/&lt;derivation&gt;/&lt;repository&gt;/&lt;org&gt;/&lt;name&gt;/&lt;version&gt;/
 *       &lt;module&gt;.json                                                    a module's object inclusions
 * </pre>
 *
 * <p>{@code v2} is the on-disk format generation, bumped only when the stored bytes change meaning: a {@code v1}
 * path had no repository segment, so every {@code v1} entry is now a harmless miss. Deliberately not a build
 * identity — see {@link DocsCache} for why the raw payload is what gets stored.
 *
 * <p>Entries are stored UNCOMPRESSED, exactly as Central served them: disk is not the constrained resource, and
 * compression would add a corruption mode and a write-path step to save bytes nobody is paying for.
 *
 * <p>Concurrency is real — parallel agents share one {@code $HOME} — so every write goes to a per-process temp
 * file and is then moved atomically. There is deliberately no lock and no single-flight: two processes that miss
 * the same package both fetch and both move, the content is equivalent, and no third process can observe a
 * partial file. A lock could outlive the client's own budget and hang a run; a duplicate download is cheaper.
 *
 * @since 0.1.0
 */
public final class DiskCache implements DocsCache {

    private static final String FORMAT = "v2";

    private static final String DOCS = "docs";
    private static final String MODULES = "modules";
    private static final String INCLUSIONS = "inclusions";
    private static final String LATEST = "latest";

    private static final String VERSION_KEY = "version";
    private static final String AT_MS_KEY = "atMs";

    private static final String POSIX = "posix";

    /** Every path segment has to be one of these before it can reach a join. */
    private static final Pattern SAFE_SEGMENT = Pattern.compile("^[A-Za-z0-9_.-]+$");

    private static final Set<PosixFilePermission> ENTRY_MODE =
            PosixFilePermissions.fromString("rw-------");

    private final Path root;
    private final boolean usable;
    private final long pid;
    private final DoubleSupplier random;

    private DiskCache(Path root, boolean usable, long pid, DoubleSupplier random) {
        this.root = root;
        this.usable = usable;
        this.pid = pid;
        this.random = random;
    }

    /**
     * A disk cache at {@code root}, or a store that silently does nothing if the root is not usable. The
     * caller cannot tell the two apart on purpose, and never has to handle a cache error, because there is
     * no cache error to handle.
     */
    public static DiskCache at(Path root, int mode) {
        return at(root, mode, ProcessHandle.current().pid(), Math::random);
    }

    /** {@code pid} and {@code random} are injectable so a concurrency test can force one temp name. */
    public static DiskCache at(Path root, int mode, long pid, DoubleSupplier random) {
        return new DiskCache(root, isUsableRoot(root, mode), pid, random);
    }

    /**
     * Is this root usable, and is it ours?
     *
     * <p>Public because the process wrapper walks the candidate locations and needs to ask before committing
     * to one; creating a store per candidate just to inspect it would create every rung it tried.
     *
     * <p>Symlinks are not followed: a root that is a symlink is refused outright, because following one is
     * how a writable-looking path becomes somebody else's directory. A root owned by another user is refused
     * for the same reason.
     */
    public static boolean isUsableRoot(Path root, int mode) {
        try {
            createDirectories(root, mode);
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
        try {
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            return isOurs(root);
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    // Checked on POSIX only: Windows owner names vary in domain-qualification and case by runner, and the
    // inherited profile ACL already restricts the directory to its user.
    private static boolean isOurs(Path root) throws IOException {
        if (!root.getFileSystem().supportedFileAttributeViews().contains(POSIX)) {
            return true;
        }
        String expected = System.getProperty("user.name");
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        UserPrincipal owner;
        try {
            owner = Files.getOwner(root, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException ignored) {
            return true;
        }
        return owner == null || owner.getName().equals(expected);
    }

    private static void createDirectories(Path directory, int mode) throws IOException {
        if (Files.isDirectory(directory)) {
            return;
        }
        if (directory.getFileSystem().supportedFileAttributeViews().contains(POSIX)) {
            Files.createDirectories(
                    directory, PosixFilePermissions.asFileAttribute(permissions(mode)));
            return;
        }
        Files.createDirectories(directory);
    }

    private static Set<PosixFilePermission> permissions(int mode) {
        StringBuilder bits = new StringBuilder(9);
        int[] masks = {0400, 0200, 0100, 0040, 0020, 0010, 0004, 0002, 0001};
        String letters = "rwxrwxrwx";
        for (int index = 0; index < masks.length; index++) {
            bits.append((mode & masks[index]) != 0 ? letters.charAt(index) : '-');
        }
        return PosixFilePermissions.fromString(bits.toString());
    }

    private static boolean isSafeSegment(String segment) {
        return SAFE_SEGMENT.matcher(segment).matches() && !".".equals(segment) && !"..".equals(segment);
    }

    // The raw coordinate check is not redundant: `..` plus `.json` is `...json`, which passes the segment check.
    private Path entryPath(List<String> coordinates, List<String> segments) {
        for (String coordinate : coordinates) {
            if (!isSafeSegment(coordinate)) {
                return null;
            }
        }
        for (String segment : segments) {
            if (!isSafeSegment(segment)) {
                return null;
            }
        }
        Path base = root.toAbsolutePath().normalize();
        Path candidate = base;
        for (String segment : segments) {
            candidate = candidate.resolve(segment);
        }
        candidate = candidate.normalize();
        return candidate.startsWith(base) && !candidate.equals(base) ? candidate : null;
    }

    private Path docsPath(DocsKey key) {
        return entryPath(
                List.of(key.repository(), key.org(), key.name(), key.version()),
                List.of(FORMAT, DOCS, key.repository(), key.org(), key.name(), key.version() + ".json"));
    }

    private Path moduleDocsPath(ModuleKey key) {
        return entryPath(
                List.of(key.repository(), key.org(), key.name(), key.module(), key.version()),
                List.of(FORMAT, MODULES, key.repository(), key.org(), key.name(), key.module(),
                        key.version() + ".json"));
    }

    private Path docsDir(PackageKey key) {
        return entryPath(
                List.of(key.repository(), key.org(), key.name()),
                List.of(FORMAT, DOCS, key.repository(), key.org(), key.name()));
    }

    private Path inclusionsPath(DocsKey key, String module, String derivation) {
        return entryPath(
                List.of(key.repository(), key.org(), key.name(), key.version(), module, derivation),
                List.of(FORMAT, INCLUSIONS, derivation, key.repository(), key.org(), key.name(), key.version(),
                        module + ".json"));
    }

    private Path latestPath(PackageKey key) {
        return entryPath(
                List.of(key.repository(), key.org(), key.name()),
                List.of(FORMAT, LATEST, key.repository(), key.org(), key.name() + ".json"));
    }

    private static JsonElement readJson(Path path) {
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            JsonElement parsed = JsonParser.parseString(text);
            return parsed == null || parsed.isJsonNull() ? null : parsed;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    // Same directory as the target, so the move stays on one filesystem.
    private Path tempPathFor(Path target) {
        String suffix = Long.toHexString((long) (random.getAsDouble() * 0xffffffffL));
        return target.resolveSibling(target.getFileName() + "." + pid + "-" + suffix + ".tmp");
    }

    private void writeAtomically(Path target, String contents, int mode) {
        if (!usable) {
            return;
        }
        Path temp = tempPathFor(target);
        try {
            Path parent = target.getParent();
            if (parent == null) {
                return;
            }
            createDirectories(parent, mode);
            Files.writeString(temp, contents, StandardCharsets.UTF_8);
            restrict(temp);
            move(temp, target);
        } catch (IOException | RuntimeException ignored) {
            // No space, no permission, a vanished parent: leave nothing behind and say nothing.
            try {
                Files.deleteIfExists(temp);
            } catch (IOException | RuntimeException alsoIgnored) {
                // The temp file is already gone, or was never created.
            }
        }
    }

    private static void move(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException retry) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void restrict(Path file) {
        if (!file.getFileSystem().supportedFileAttributeViews().contains(POSIX)) {
            return;
        }
        try {
            Files.getFileAttributeView(file, PosixFileAttributeView.class).setPermissions(ENTRY_MODE);
        } catch (IOException | RuntimeException ignored) {
            // A filesystem that will not narrow the mode is still a usable cache.
        }
    }

    @Override
    public JsonElement readDocs(DocsKey key) {
        return readPayload(docsPath(key));
    }

    @Override
    public void writeDocs(DocsKey key, JsonElement payload) {
        writePayload(docsPath(key), payload);
    }

    @Override
    public void removeDocs(DocsKey key) {
        removePayload(docsPath(key));
    }

    @Override
    public JsonElement readModuleDocs(ModuleKey key) {
        return readPayload(moduleDocsPath(key));
    }

    @Override
    public void writeModuleDocs(ModuleKey key, JsonElement payload) {
        writePayload(moduleDocsPath(key), payload);
    }

    @Override
    public void removeModuleDocs(ModuleKey key) {
        removePayload(moduleDocsPath(key));
    }

    private JsonElement readPayload(Path path) {
        return !usable || path == null ? null : readJson(path);
    }

    private void writePayload(Path path, JsonElement payload) {
        if (path == null) {
            return;
        }
        String contents;
        try {
            contents = payload.toString();
        } catch (RuntimeException ignored) {
            return;
        }
        writeAtomically(path, contents, 0700);
    }

    private void removePayload(Path path) {
        if (!usable || path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ignored) {
            // Best effort; the next successful fetch overwrites it anyway.
        }
    }

    @Override
    public JsonElement readInclusions(DocsKey key, String module, String derivation) {
        if (!usable) {
            return null;
        }
        Path path = inclusionsPath(key, module, derivation);
        return path == null ? null : readJson(path);
    }

    @Override
    public void writeInclusions(DocsKey key, String module, String derivation, JsonElement inclusions) {
        Path path = inclusionsPath(key, module, derivation);
        if (path == null) {
            return;
        }
        writeAtomically(path, inclusions.toString(), 0700);
    }

    @Override
    public LatestEntry readLatest(PackageKey key) {
        if (!usable) {
            return null;
        }
        Path path = latestPath(key);
        if (path == null) {
            return null;
        }
        JsonElement raw = readJson(path);
        if (raw == null || !raw.isJsonObject()) {
            return null;
        }
        JsonObject entry = raw.getAsJsonObject();
        JsonElement version = entry.get(VERSION_KEY);
        JsonElement atMs = entry.get(AT_MS_KEY);
        boolean valid = version != null && version.isJsonPrimitive() && version.getAsJsonPrimitive().isString()
                && !version.getAsString().isEmpty()
                && atMs != null && atMs.isJsonPrimitive() && atMs.getAsJsonPrimitive().isNumber();
        return valid ? new LatestEntry(version.getAsString(), atMs.getAsLong()) : null;
    }

    @Override
    public void writeLatest(PackageKey key, LatestEntry entry) {
        Path path = latestPath(key);
        if (path == null) {
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty(VERSION_KEY, entry.version());
        json.addProperty(AT_MS_KEY, entry.atMs());
        writeAtomically(path, json.toString(), 0700);
    }

    @Override
    public List<String> listVersions(PackageKey key) {
        if (!usable) {
            return List.of();
        }
        Path directory = docsDir(key);
        if (directory == null) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            List<String> versions = new ArrayList<>();
            entries.map(Path::getFileName)
                    .filter(Objects::nonNull)
                    .map(Path::toString)
                    .filter(file -> file.endsWith(".json"))
                    .map(file -> file.substring(0, file.length() - ".json".length()))
                    .forEach(versions::add);
            versions.sort(Comparator.comparing(version -> version, (a, b) -> Versions.compare(b, a)));
            return List.copyOf(versions);
        } catch (IOException | RuntimeException ignored) {
            // Includes the UncheckedIOException a lazily-evaluated directory stream can throw mid-walk.
            return List.of();
        }
    }

    @Override
    public String describe() {
        if (!usable) {
            return root + " (unusable; caching disabled)";
        }
        return Files.isDirectory(root) ? root + " (writable)" : root + " (unusable; caching disabled)";
    }
}
