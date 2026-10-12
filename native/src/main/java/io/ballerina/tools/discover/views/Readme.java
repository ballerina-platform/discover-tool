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
import io.ballerina.tools.discover.symbols.Filter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code readme} — the resolved module's own guide, verbatim.
 *
 * <p>THE ONE BUCKET NOT DERIVED FROM CALL-SITE GRAMMAR: it answers "what does this module's documentation say"
 * rather than "what can I call", so it shares no code with {@link Containers} and carries no {@link
 * io.ballerina.tools.discover.symbols.Surface.Scope} of its own.
 *
 * <p>Bare, a readme of at most {@link #WHOLE_LIMIT_BYTES} is the RFC's base case — the whole readme, verbatim. A
 * larger one lists every one of its sections with the command that opens it, never cut off. A trailing selector (a
 * section number, or a title) and {@code --filter} are this tool's own addition, not an RFC shape.
 *
 * @since 0.1.0
 */
public final class Readme {

    public static final String BUCKET = "readme";

    public static final int WHOLE_LIMIT_BYTES = 10 * 1024;

    private static final String INTRODUCTION = "Introduction";
    private static final String UNTITLED = "Untitled";
    private static final String FENCE_BACKTICKS = "```";
    private static final String FENCE_TILDES = "~~~";
    private static final Pattern HEADING =
            Pattern.compile("^ {0,3}#{1,6}(?:[ \\t]+(.*?))?(?:[ \\t]+#+)?[ \\t]*$");

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
     * One addressable section of the readme: a heading and everything down to the next heading of any depth, prose
     * only or not.
     *
     * @param number the 1-based address a caller types
     * @param title the heading it sits under, or {@code Introduction} for prose before the first heading
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
        if (markdown.getBytes(StandardCharsets.UTF_8).length > WHOLE_LIMIT_BYTES) {
            return Result.ok(listing(loaded, chunks, chunks, false));
        }
        return Result.ok(new DiscoverResult.Readme(markdown, lineCount(markdown), loaded.warning()));
    }

    private static DiscoverResult.ReadmeChunks listing(
            LoadedPackage loaded, List<Chunk> chunks, List<Chunk> shown, boolean matching) {
        return new DiscoverResult.ReadmeChunks(rows(loaded, shown), shown.size(), null, null, loaded.warning(),
                matching);
    }

    private static List<DiscoverResult.ReadmeChunks.Chunk> rows(LoadedPackage loaded, List<Chunk> chunks) {
        String pkg = loaded.pkgArgument();
        return chunks.stream()
                .map(chunk -> new DiscoverResult.ReadmeChunks.Chunk(
                        chunk.number(), chunk.title(), chunk.lines(), command(pkg) + " " + chunk.number()))
                .toList();
    }

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

    /** Every addressable chunk of the resolved module's readme, in the readme's own order. */
    public static List<Chunk> chunksOf(LoadedPackage loaded) {
        return chunksOf(loaded.readme().orElse(""));
    }

    private static List<Chunk> chunksOf(String markdown) {
        List<Chunk> chunks = new ArrayList<>();
        String title = INTRODUCTION;
        List<String> body = new ArrayList<>();
        String fence = null;

        for (String line : markdown.split("\n", -1)) {
            String stripped = line.stripLeading();
            if (fence != null) {
                body.add(line);
                if (closesFence(stripped, fence)) {
                    fence = null;
                }
                continue;
            }
            String opened = openedFence(stripped);
            if (opened != null) {
                fence = opened;
                body.add(line);
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                flush(chunks, title, body);
                title = heading.group(1) == null || heading.group(1).isBlank() ? UNTITLED : heading.group(1).trim();
                body = new ArrayList<>();
            }
            body.add(line);
        }
        flush(chunks, title, body);
        return List.copyOf(chunks);
    }

    // The fence's marker run (``` or ~~~, possibly longer) when the line opens one, else null.
    private static String openedFence(String stripped) {
        for (String marker : List.of(FENCE_BACKTICKS, FENCE_TILDES)) {
            if (stripped.startsWith(marker)) {
                int length = 0;
                while (length < stripped.length() && stripped.charAt(length) == marker.charAt(0)) {
                    length++;
                }
                return stripped.substring(0, length);
            }
        }
        return null;
    }

    private static boolean closesFence(String stripped, String fence) {
        String run = openedFence(stripped);
        return run != null && run.charAt(0) == fence.charAt(0) && run.length() >= fence.length()
                && stripped.substring(run.length()).isBlank();
    }

    private static void flush(List<Chunk> chunks, String title, List<String> body) {
        String text = String.join("\n", body).strip();
        if (text.isEmpty()) {
            return;
        }
        chunks.add(new Chunk(chunks.size() + 1, title, text));
    }

    private static int lineCount(String markdown) {
        return markdown.isEmpty() ? 0 : markdown.split("\n", -1).length;
    }

    private static Result<DiscoverResult> oneChunk(
            LoadedPackage loaded, List<Chunk> chunks, String requested, String filter) {
        Optional<Chunk> byNumber = byNumber(chunks, requested);
        List<Chunk> found = byNumber.isPresent() ? List.of(byNumber.get()) : byTitle(chunks, requested);
        if (found.isEmpty()) {
            return Result.err(loaded.symbolNotFoundAmong(List.of(requested),
                    "No readme section of " + loaded.place() + " is numbered or titled '" + requested + "'.",
                    "Pass the number or title of one of the sections listed, or read the whole readme: `"
                            + command(loaded.pkgArgument()) + "`.",
                    chunks.stream().map(chunk -> chunk.number() + ". " + chunk.title()).toList()));
        }
        if (found.size() > 1) {
            return Result.ok(listing(loaded, chunks, found, true));
        }
        Chunk chunk = found.get(0);
        if (filter != null && !matches(chunk, filter)) {
            return Result.err(new Failure.Validation(
                    "Section " + requested + " (\"" + chunk.title() + "\") does not mention \""
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

    // Every chunk the title names: the exact matches when there are any, else those containing it.
    private static List<Chunk> byTitle(List<Chunk> chunks, String requested) {
        String wanted = normalised(requested);
        if (wanted.isEmpty()) {
            return List.of();
        }
        List<Chunk> exact = chunks.stream().filter(chunk -> normalised(chunk.title()).equals(wanted)).toList();
        if (!exact.isEmpty()) {
            return exact;
        }
        return chunks.stream().filter(chunk -> normalised(chunk.title()).contains(wanted)).toList();
    }

    // A title is typed from prose, so it is matched on its letters and digits alone, in any case.
    private static String normalised(String title) {
        return title.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static boolean matches(Chunk chunk, String filter) {
        return Filter.matches(filter, chunk.markdown()) || Filter.matches(filter, chunk.title());
    }

    private static DiscoverResult.Readme toResult(Chunk chunk, int total, LoadedPackage loaded) {
        return new DiscoverResult.Readme(
                chunk.markdown(), chunk.lines(), chunk.number(), total, chunk.title(), loaded.warning());
    }

    private static Result<DiscoverResult> filtered(LoadedPackage loaded, List<Chunk> chunks, Options options) {
        List<Chunk> matched = chunks.stream().filter(chunk -> matches(chunk, options.filter())).toList();
        String pkg = loaded.pkgArgument();

        if (matched.size() == 1) {
            return Result.ok(toResult(matched.get(0), chunks.size(), loaded));
        }

        List<DiscoverResult.ReadmeChunks.Chunk> items = rows(loaded, matched);
        String command = command(pkg) + " --filter " + Texts.shellWord(options.filter());
        Result<Containers.Page> page = Containers.Page.of(options.page(), items.size(), command);
        if (!page.isOk()) {
            return page.cast();
        }
        Containers.Page window = page.value();
        List<DiscoverResult.ReadmeChunks.Chunk> shown = items.subList(window.from(), window.to());
        return Result.ok(new DiscoverResult.ReadmeChunks(
                shown, items.size(), window.paging(), window.next(command), loaded.warning(), true));
    }
}
