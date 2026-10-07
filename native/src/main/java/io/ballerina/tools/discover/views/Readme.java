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

package io.ballerina.tools.discover.views;

import io.ballerina.tools.discover.Failure;
import io.ballerina.tools.discover.LoadedPackage;
import io.ballerina.tools.discover.Result;
import io.ballerina.tools.discover.Texts;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.symbols.Names;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code readme} — the resolved module's own guide, verbatim.
 *
 * <p>THE ONE BUCKET NOT DERIVED FROM CALL-SITE GRAMMAR: it answers "what does this module's documentation say"
 * rather than "what can I call", so it shares no code with {@link Containers} and carries no {@link
 * io.ballerina.tools.discover.symbols.Surface.Scope} of its own.
 *
 * <p>Bare, it is the RFC's own base case — the whole readme, verbatim, with no entry ceiling: the RFC's text for
 * this bucket says "returns that module's README, verbatim", and unlike every listing elsewhere in this tool, it
 * states no size limit at all. A trailing selector (a chunk number, or a title) and {@code --filter} are this
 * tool's own addition on top of that base case, not an RFC shape — a readme section WITH its prose, addressed the
 * same way an oversized listing elsewhere narrows, because a package's guide can be tens of kilobytes and
 * "the whole thing, always" is not always the answer an agent needs.
 *
 * @since 0.1.0
 */
public final class Readme {

    /** The bucket a caller types to reach a readme. */
    public static final String BUCKET = "readme";

    private Readme() {
    }

    private static String command(String pkg) {
        return "bal discover " + pkg + " " + BUCKET;
    }

    /**
     * @param selector a chunk number or a title fragment, or {@code null} to address the whole readme
     * @param filter the {@code --filter} keyword narrowing to matching chunks, or {@code null}
     * @param page 1-indexed, meaningful only once a filtered chunk listing is over the entry ceiling
     */
    public record Options(String selector, String filter, int page) {

        public static final Options BARE = new Options(null, null, 1);

        public boolean filtered() {
            return filter != null && !filter.isBlank();
        }
    }

    /**
     * One addressable section of the readme: the heading it sits under, and everything down to the next heading
     * of that depth.
     *
     * @param number the 1-based address a caller types
     * @param title the heading it sits under, or a generated one when the readme opens with prose
     * @param markdown the section verbatim, prose and code together
     */
    public record Chunk(int number, String title, String markdown) {

        int lines() {
            return markdown.split("\n", -1).length;
        }
    }

    public static Result<DiscoverResult> render(LoadedPackage loaded, Options options) {
        String markdown = loaded.readme().orElse("");
        if (markdown.isEmpty()) {
            return Result.err(noReadme(loaded));
        }
        List<Chunk> chunks = chunksOf(markdown);

        if (options.selector() != null) {
            return oneChunk(loaded, chunks, options.selector(), options.filtered() ? options.filter() : null);
        }
        if (options.filtered()) {
            return filtered(loaded, chunks, options);
        }
        return Result.ok(new DiscoverResult.Readme(markdown, lineCount(markdown), loaded.warning()));
    }

    /**
     * A resolved module that simply publishes no readme — a real, addressable module, distinct from a bad
     * {@code --module} value ({@link io.ballerina.tools.discover.model.FromCentral#selectModule} rejects those
     * before this class ever sees them), so this fails loudly rather than the old silent exit-0-empty-body this
     * class used to answer with.
     */
    private static Failure noReadme(LoadedPackage loaded) {
        return new Failure.Validation(
                loaded.label() + " publishes no readme"
                        + (loaded.module() == null ? "." : " for module " + loaded.module() + "."),
                loaded.module() != null
                        ? "Read the default module's instead: `" + command(loaded.pkgArgument(null))
                                + "`. Or find another module's: `bal discover " + loaded.pkgArgument(null)
                                + "` lists the default module's buckets and its submodules."
                        : loaded.submodules().isEmpty()
                                ? "This package publishes no other module either."
                                : "Check its other modules: `bal discover " + loaded.pkgArgument(null) + "`.");
    }

    // -----------------------------------------------------------------------
    // Chunks
    // -----------------------------------------------------------------------

    /** Every addressable chunk of the resolved module's readme, in the readme's own order. */
    public static List<Chunk> chunksOf(LoadedPackage loaded) {
        return chunksOf(loaded.readme().orElse(""));
    }

    /**
     * Split the readme on its headings, keeping only the sections that hold code.
     *
     * <p>A section with no fenced block is prose about the project — a badge row, a licence, a "report an issue"
     * paragraph — and addressing it would spend a number on something no caller will ask for. A section WITH one
     * keeps all of its prose: the setup narration around a call is what makes it reproducible, not decoration
     * around it — "you must also enable the Google Drive API in the same project" is not inferable from any
     * signature, and is the difference between a connector that works and one that 403s on a single operation.
     *
     * <p>Fences are tracked so a {@code #} inside a code block cannot start a section: it is a shell comment or a
     * Ballerina doc comment, not a heading, and treating it as one would cut a snippet in half.
     */
    private static List<Chunk> chunksOf(String markdown) {
        List<Chunk> chunks = new ArrayList<>();
        String title = null;
        List<String> body = new ArrayList<>();
        boolean inFence = false;

        for (String line : markdown.split("\n", -1)) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("```") || stripped.startsWith("~~~")) {
                inFence = !inFence;
                body.add(line);
                continue;
            }
            if (!inFence && stripped.startsWith("#")) {
                flush(chunks, title, body);
                title = stripped.replaceAll("^#+\\s*", "").trim();
                body = new ArrayList<>();
                body.add(line);
                continue;
            }
            body.add(line);
        }
        flush(chunks, title, body);
        return List.copyOf(chunks);
    }

    private static void flush(List<Chunk> chunks, String title, List<String> body) {
        String text = String.join("\n", body).strip();
        if (text.isEmpty() || !text.contains("```")) {
            return;
        }
        chunks.add(new Chunk(chunks.size() + 1, title == null ? "Introduction" : title, text));
    }

    private static int lineCount(String markdown) {
        return markdown.isEmpty() ? 0 : markdown.split("\n", -1).length;
    }

    // -----------------------------------------------------------------------
    // One chunk, by number or by title
    // -----------------------------------------------------------------------

    private static Result<DiscoverResult> oneChunk(
            LoadedPackage loaded, List<Chunk> chunks, String requested, String filter) {
        if (chunks.isEmpty()) {
            return Result.err(new Failure.Validation(
                    loaded.label() + " publishes no readme chunk — no section carries code.",
                    "Read the readme whole: `" + command(loaded.pkgArgument()) + "`."));
        }
        Optional<Chunk> found = byNumber(chunks, requested).or(() -> byTitle(chunks, requested));
        if (found.isEmpty()) {
            return Result.err(new Failure.SymbolNotFound(
                    loaded.label(),
                    List.of(requested),
                    chunks.stream().map(chunk -> chunk.number() + ". " + chunk.title()).toList(),
                    "No chunk answers to that. The candidates are every chunk this readme publishes; pass one "
                            + "of the numbers, or read the readme whole: `" + command(loaded.pkgArgument()) + "`."));
        }
        Chunk chunk = found.get();
        if (filter != null && !matches(chunk, filter)) {
            return Result.err(new Failure.Validation(
                    "Chunk " + requested + " (\"" + chunk.title() + "\") does not match --filter \""
                            + filter + "\".",
                    "Drop --filter to read it anyway: `" + command(loaded.pkgArgument()) + " "
                            + Texts.shellWord(requested) + "`."));
        }
        return Result.ok(toResult(chunk, chunks.size(), loaded));
    }

    private static Optional<Chunk> byNumber(List<Chunk> chunks, String requested) {
        try {
            int number = Integer.parseInt(requested.trim());
            return chunks.stream().filter(chunk -> chunk.number() == number).findFirst();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    /**
     * A number because that is what a chunk listing prints, and a title because that is what an agent reading
     * one will type when it means one thing rather than "the third one". Title matching goes through {@link
     * Names}' normalisation, so punctuation and casing in a heading are not a trap.
     */
    private static Optional<Chunk> byTitle(List<Chunk> chunks, String requested) {
        String wanted = Names.normalise(requested);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        List<Chunk> exact = chunks.stream().filter(chunk -> Names.normalise(chunk.title()).equals(wanted)).toList();
        if (exact.size() == 1) {
            return Optional.of(exact.get(0));
        }
        List<Chunk> partial = chunks.stream()
                .filter(chunk -> Names.normalise(chunk.title()).contains(wanted))
                .toList();
        return partial.size() == 1 ? Optional.of(partial.get(0)) : Optional.empty();
    }

    private static boolean matches(Chunk chunk, String filter) {
        String needle = filter.toLowerCase(Locale.ROOT);
        return chunk.markdown().toLowerCase(Locale.ROOT).contains(needle)
                || chunk.title().toLowerCase(Locale.ROOT).contains(needle);
    }

    private static DiscoverResult.Readme toResult(Chunk chunk, int total, LoadedPackage loaded) {
        return new DiscoverResult.Readme(
                chunk.markdown(), chunk.lines(), chunk.number(), total, chunk.title(), loaded.warning());
    }

    // -----------------------------------------------------------------------
    // --filter
    // -----------------------------------------------------------------------

    /**
     * Narrows to the chunks whose title or body mentions the keyword — exactly one is answered in full, the
     * same rule every other bucket applies to an exact one-of-many match; more than one is a roster to choose
     * from, paginated past {@value Containers#MAX_ENTRIES} like any other listing this tool ceilings; none is the
     * same no-match answer every other bucket gives, pointing back at the whole readme.
     */
    private static Result<DiscoverResult> filtered(LoadedPackage loaded, List<Chunk> chunks, Options options) {
        List<Chunk> matched = chunks.stream().filter(chunk -> matches(chunk, options.filter())).toList();
        String pkg = loaded.pkgArgument();

        if (matched.isEmpty()) {
            return Result.ok(new DiscoverResult.NoMatch(options.filter(), null,
                    Names.nearMisses(options.filter(), chunks.stream().map(Chunk::title).toList()), List.of(), null,
                    command(pkg), DiscoverResult.Documented.NONE, null, loaded.warning(),
                    null));
        }
        if (matched.size() == 1) {
            return Result.ok(toResult(matched.get(0), chunks.size(), loaded));
        }

        List<DiscoverResult.ReadmeChunks.Chunk> items = matched.stream()
                .map(chunk -> new DiscoverResult.ReadmeChunks.Chunk(
                        chunk.number(), chunk.title(), chunk.lines(),
                        command(pkg) + " " + chunk.number()))
                .toList();
        String command = command(pkg) + " --filter " + Texts.shellWord(options.filter());
        Result<Containers.Page> page = Containers.Page.of(options.page(), items.size(), command);
        if (!page.isOk()) {
            return page.cast();
        }
        Containers.Page window = page.value();
        List<DiscoverResult.ReadmeChunks.Chunk> shown = items.subList(window.from(), window.to());
        return Result.ok(new DiscoverResult.ReadmeChunks(
                shown, items.size(), window.paging(), window.next(command), loaded.warning()));
    }
}
