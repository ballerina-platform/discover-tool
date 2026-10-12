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

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A package archive ({@code .bala}) — a zip whose {@code modules/<module-id>/} directories hold each module's
 * {@code .bal} files, beside the docs, resources and native jars this reader never needs.
 *
 * <p>Bounded on every axis, since its size is the publisher's choice: an archive past {@link #MAX_ARCHIVE_BYTES}, a
 * source file past {@link #MAX_SOURCE_BYTES}, or a module past {@link #MAX_MODULE_BYTES} or {@link #MAX_MODULE_FILES}
 * is no answer at all, the same as an archive that could not be fetched. The optional {@link #TRIGGER_METADATA} is
 * the exception: one over {@link #MAX_SOURCE_BYTES} is skipped, and the module's sources are still read.
 *
 * @since 0.1.0
 */
public final class Bala {

    /**
     * The most of an archive read off the wire. {@code ballerina/http} 2.16.6 is 24 MB, nearly all of it bundled
     * native jars; this leaves it more than twice that headroom while still refusing an archive that is not a
     * Ballerina package's.
     */
    public static final long MAX_ARCHIVE_BYTES = 64L * 1024 * 1024;

    /**
     * The most of one {@code .bal} file kept. The largest in {@code ballerina/http} is under 50 KB; a file a
     * hundred times that is no hand-written source worth parsing for its type declarations.
     */
    public static final int MAX_SOURCE_BYTES = 4 * 1024 * 1024;

    /** The most source kept for one module, all its files together — a hundred times the whole of {@code http}'s. */
    public static final long MAX_MODULE_BYTES = 16L * 1024 * 1024;

    /** The most {@code .bal} files kept for one module; {@code ballerina/http}'s default module has under fifty. */
    public static final int MAX_MODULE_FILES = 1000;

    /**
     * What a read may take.
     *
     * @param archiveBytes the most read off the wire
     * @param sourceBytes the most kept of one file
     * @param moduleBytes the most kept of all the module's files together
     * @param moduleFiles the most files kept
     */
    public record Limits(long archiveBytes, int sourceBytes, long moduleBytes, int moduleFiles) {

        public static final Limits DEFAULT =
                new Limits(MAX_ARCHIVE_BYTES, MAX_SOURCE_BYTES, MAX_MODULE_BYTES, MAX_MODULE_FILES);
    }

    /**
     * Where a package describes the service handlers its listeners dispatch to. The one entry {@link #moduleSources}
     * keeps that is not a module's {@code .bal} file; the slash keeps it from ever colliding with one.
     */
    public static final String TRIGGER_METADATA = "metadata/trigger-metadata.json";

    static final String MODULES_DIR = "modules";

    private static final String MODULES_PREFIX = MODULES_DIR + "/";

    private Bala() {
    }

    /**
     * The {@code .bal} files directly under {@code modules/<moduleId>/}, by file name, with the package's
     * {@link #TRIGGER_METADATA} when it publishes one — or empty when the archive is unreadable, over a limit or
     * holds neither. A submodule's own directory is a sibling, never nested, so this reads exactly one module.
     * Closes {@code archive}.
     */
    public static Optional<Map<String, String>> moduleSources(InputStream archive, String moduleId) {
        return moduleSources(archive, moduleId, Limits.DEFAULT);
    }

    /** {@link #moduleSources(InputStream, String)} under explicit limits. */
    public static Optional<Map<String, String>> moduleSources(InputStream archive, String moduleId, Limits limits) {
        String prefix = MODULES_PREFIX + moduleId + "/";
        Map<String, String> files = new TreeMap<>();
        long kept = 0;
        int sourceFiles = 0;
        try (ZipInputStream zip = new ZipInputStream(new Capped(archive, limits.archiveBytes()),
                StandardCharsets.UTF_8)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                if (TRIGGER_METADATA.equals(name)) {
                    readSource(zip, limits.sourceBytes())
                            .ifPresent(metadata -> files.put(TRIGGER_METADATA, utf8(metadata)));
                } else if (!entry.isDirectory() && name.startsWith(prefix) && name.endsWith(".bal")
                        && name.indexOf('/', prefix.length()) < 0) {
                    if (sourceFiles >= limits.moduleFiles()) {
                        return Optional.empty();
                    }
                    Optional<byte[]> source = readSource(zip, limits.sourceBytes());
                    if (source.isEmpty()) {
                        return Optional.empty();
                    }
                    kept += source.get().length;
                    if (kept > limits.moduleBytes()) {
                        return Optional.empty();
                    }
                    sourceFiles++;
                    files.put(name.substring(prefix.length()), utf8(source.get()));
                }
            }
        } catch (IOException | IllegalArgumentException unreadable) {
            return Optional.empty();
        }
        return files.isEmpty() ? Optional.empty() : Optional.of(Collections.unmodifiableMap(files));
    }

    private static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // Empty when the entry is over the limit; the rest of it is skipped by the next getNextEntry.
    private static Optional<byte[]> readSource(InputStream entry, int maxBytes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read = entry.read(buffer); read >= 0; read = entry.read(buffer)) {
            if (bytes.size() + read > maxBytes) {
                return Optional.empty();
            }
            bytes.write(buffer, 0, read);
        }
        return Optional.of(bytes.toByteArray());
    }

    private static final class Capped extends FilterInputStream {

        private final long limit;
        private long count;

        Capped(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        private int counted(int read) throws IOException {
            if (read > 0) {
                count += read;
                if (count > limit) {
                    throw new IOException("the archive is over " + limit + " bytes");
                }
            }
            return read;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                counted(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return counted(super.read(buffer, offset, length));
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            counted((int) Math.min(skipped, Integer.MAX_VALUE));
            return skipped;
        }
    }
}
