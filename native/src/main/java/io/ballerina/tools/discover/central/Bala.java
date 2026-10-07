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
 * <p>Bounded on both axes, since its size is the publisher's choice: an archive past {@link #MAX_ARCHIVE_BYTES} or
 * a source file past {@link #MAX_SOURCE_BYTES} is no answer at all, the same as an archive that could not be fetched.
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

    private Bala() {
    }

    /**
     * The {@code .bal} files directly under {@code modules/<moduleId>/}, by file name, or empty when the archive is
     * unreadable, over a limit or holds none — a submodule's own directory is a sibling, never nested, so this reads
     * exactly one module. Closes {@code archive}.
     */
    public static Optional<Map<String, String>> moduleSources(InputStream archive, String moduleId) {
        return moduleSources(archive, moduleId, MAX_ARCHIVE_BYTES, MAX_SOURCE_BYTES);
    }

    /** {@link #moduleSources(InputStream, String)} under explicit limits. */
    public static Optional<Map<String, String>> moduleSources(
            InputStream archive, String moduleId, long maxArchiveBytes, int maxSourceBytes) {
        String prefix = "modules/" + moduleId + "/";
        Map<String, String> files = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(new Capped(archive, maxArchiveBytes), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                String name = entry.getName();
                if (!entry.isDirectory() && name.startsWith(prefix) && name.endsWith(".bal")
                        && name.indexOf('/', prefix.length()) < 0) {
                    files.put(name.substring(prefix.length()), readSource(zip, maxSourceBytes));
                }
            }
        } catch (IOException | IllegalArgumentException unreadable) {
            return Optional.empty();
        }
        return files.isEmpty() ? Optional.empty() : Optional.of(Collections.unmodifiableMap(files));
    }

    private static String readSource(InputStream entry, int maxBytes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read = entry.read(buffer); read >= 0; read = entry.read(buffer)) {
            if (bytes.size() + read > maxBytes) {
                throw new IOException("a source file is over " + maxBytes + " bytes");
            }
            bytes.write(buffer, 0, read);
        }
        return bytes.toString(StandardCharsets.UTF_8);
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
