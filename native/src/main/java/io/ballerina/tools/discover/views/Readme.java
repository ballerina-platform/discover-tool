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

import io.ballerina.tools.discover.Command;
import io.ballerina.tools.discover.Failure;
import io.ballerina.tools.discover.LoadedPackage;
import io.ballerina.tools.discover.Result;
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
 * section number, or a title) and {@code --filter} are this tool's own addition, not an RFC shape. A section starts
 * at an ATX ({@code #}) heading; setext headings are not recognised.
 *
 * @since 0.1.0
 */
public final class Readme {

    public static final int WHOLE_LIMIT_BYTES = 10 * 1024;

    private static final String INTRODUCTION = "Introduction";
    private static final String UNTITLED = "Untitled";
    private static final String FENCE_BACKTICKS = "```";
    private static final String FENCE_TILDES = "~~~";
    private static final String LINE_BREAK = "\\r?\\n";
    private static final Pattern INLINE_CODE =
            Pattern.compile("(?<![`\\\\])(`+)(?!`)(.+?)(?<!`)\\1(?!`)", Pattern.DOTALL);
    private static final Pattern HEADING =
            Pattern.compile("^ {0,3}#{1,6}(?:[ \\t]+(.*?))?(?:[ \\t]+#+)?[ \\t]*$");

    private Readme() {
    }

    private static Command command(Command.Target target, String... names) {
        return Command.of(Command.README, names).in(target);
    }

    /**
     * @param selector a section number or a title fragment, or {@code null} to address the whole readme
     * @param filter the {@code --filter} keyword narrowing to matching sections, or {@code null}
     * @param page 1-indexed, meaningful only once a filtered section listing is over the entry ceiling
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
    public record Section(int number, String title, String markdown) {

        int lines() {
            return markdown.split("\n", -1).length;
        }
    }

    public static Result<DiscoverResult> render(LoadedPackage loaded, Options options) {
        String markdown = loaded.readme().orElse("");
        if (markdown.isEmpty()) {
            return Result.err(noReadme(loaded));
        }
        List<Section> sections = sectionsOf(markdown);

        if (options.selector() != null) {
            return oneSection(loaded, sections, options.selector(), options.filtered() ? options.filter() : null);
        }
        if (options.filtered()) {
            return filtered(loaded, sections, options);
        }
        if (markdown.getBytes(StandardCharsets.UTF_8).length > WHOLE_LIMIT_BYTES) {
            return Result.ok(listing(loaded, sections));
        }
        return Result.ok(new DiscoverResult.Readme(markdown, lineCount(markdown)));
    }

    private static DiscoverResult.ReadmeSections listing(LoadedPackage loaded, List<Section> sections) {
        return new DiscoverResult.ReadmeSections(rows(loaded, sections), sections.size(), null, null, false);
    }

    private static List<DiscoverResult.ReadmeSections.Section> rows(LoadedPackage loaded, List<Section> sections) {
        return sections.stream()
                .map(section -> new DiscoverResult.ReadmeSections.Section(section.number(), section.title(),
                        section.lines(), command(loaded.target(), String.valueOf(section.number())).text()))
                .toList();
    }

    private static Failure severalSections(LoadedPackage loaded, String requested, List<Section> found) {
        return loaded.ambiguous(List.of(requested),
                "'" + requested + "' matches " + found.size() + " readme sections of " + loaded.place(),
                "Name the one you mean:", Failure.SECTIONS,
                found.stream().map(section -> new Failure.Match(section.number() + ". " + section.title(),
                        command(loaded.target(), String.valueOf(section.number())).text())).toList());
    }

    private static Failure noReadme(LoadedPackage loaded) {
        return loaded.symbolNotFound(List.of(Command.README),
                loaded.label() + " publishes no readme"
                        + (loaded.module() == null ? "" : " for module " + loaded.module()),
                loaded.module() != null
                        ? "Read the default module's instead. Or find another module's: "
                                + Failure.quoted(Command.overview(loaded.target(null)).text())
                                + " lists what the default module declares and its submodules."
                        : loaded.submodules().isEmpty()
                                ? "This package publishes no other module either."
                                : "Check its other modules.",
                loaded.module() != null
                        ? command(loaded.target(null)).text()
                        : loaded.submodules().isEmpty() ? null : Command.overview(loaded.target(null)).text());
    }

    /** Every addressable section of the resolved module's readme, in the readme's own order. */
    public static List<Section> sectionsOf(LoadedPackage loaded) {
        return sectionsOf(loaded.readme().orElse(""));
    }

    private static List<Section> sectionsOf(String markdown) {
        List<Section> sections = new ArrayList<>();
        String title = INTRODUCTION;
        List<String> body = new ArrayList<>();
        String fence = null;

        for (String line : markdown.split(LINE_BREAK, -1)) {
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
                flush(sections, title, body);
                title = heading.group(1) == null || heading.group(1).isBlank() ? UNTITLED : heading.group(1).trim();
                body = new ArrayList<>();
            }
            body.add(line);
        }
        flush(sections, title, body);
        return List.copyOf(sections);
    }

    /** Every fenced block of {@code markdown}, its lines joined, the fence lines themselves left out. */
    static String fencedCodeOf(String markdown) {
        StringBuilder code = new StringBuilder();
        scan(markdown, code, null);
        return code.toString();
    }

    /** {@link #fencedCodeOf} and every inline code span of the prose, a span never crossing a paragraph. */
    static String codeOf(String markdown) {
        StringBuilder code = new StringBuilder();
        StringBuilder spans = new StringBuilder();
        scan(markdown, code, spans);
        return code.append(spans).toString();
    }

    // Fenced lines go to `fenced`; when `spans` is given, each prose paragraph's inline code spans go to it.
    private static void scan(String markdown, StringBuilder fenced, StringBuilder spans) {
        StringBuilder paragraph = new StringBuilder();
        String fence = null;
        for (String line : markdown.split(LINE_BREAK, -1)) {
            String stripped = line.stripLeading();
            if (fence != null) {
                if (closesFence(stripped, fence)) {
                    fence = null;
                } else {
                    fenced.append(line).append('\n');
                }
                continue;
            }
            fence = openedFence(stripped);
            if (fence != null || stripped.isBlank()) {
                spansOf(paragraph, spans);
                paragraph.setLength(0);
            } else {
                paragraph.append(line).append('\n');
            }
        }
        spansOf(paragraph, spans);
    }

    private static void spansOf(CharSequence paragraph, StringBuilder spans) {
        if (spans == null) {
            return;
        }
        Matcher span = INLINE_CODE.matcher(paragraph);
        while (span.find()) {
            spans.append(span.group(2)).append('\n');
        }
    }

    // The fence's marker run (``` or ~~~, possibly longer) when the line opens one, else null.
    private static String openedFence(String stripped) {
        for (String marker : List.of(FENCE_BACKTICKS, FENCE_TILDES)) {
            if (stripped.startsWith(marker)) {
                int length = 0;
                while (length < stripped.length() && stripped.charAt(length) == marker.charAt(0)) {
                    length++;
                }
                boolean inlineCode = marker.equals(FENCE_BACKTICKS) && stripped.indexOf('`', length) >= 0;
                return inlineCode ? null : stripped.substring(0, length);
            }
        }
        return null;
    }

    private static boolean closesFence(String stripped, String fence) {
        String run = openedFence(stripped);
        return run != null && run.charAt(0) == fence.charAt(0) && run.length() >= fence.length()
                && stripped.substring(run.length()).isBlank();
    }

    private static void flush(List<Section> sections, String title, List<String> body) {
        String text = String.join("\n", body).strip();
        if (text.isEmpty()) {
            return;
        }
        sections.add(new Section(sections.size() + 1, title, text));
    }

    private static int lineCount(String markdown) {
        return markdown.isEmpty() ? 0 : markdown.split("\n", -1).length;
    }

    private static Result<DiscoverResult> oneSection(
            LoadedPackage loaded, List<Section> sections, String requested, String filter) {
        Optional<Section> byNumber = byNumber(sections, requested);
        List<Section> found = byNumber.isPresent() ? List.of(byNumber.get()) : byTitle(sections, requested);
        if (found.isEmpty()) {
            return Result.err(loaded.symbolNotFoundAmong(List.of(requested),
                    "no readme section of " + loaded.place() + " is numbered or titled '" + requested + "'",
                    "Pass the number or title of one of the sections listed, or read the whole readme with "
                            + Failure.quoted(command(loaded.target()).text()) + ".",
                    sections.stream().map(section -> section.number() + ". " + section.title()).toList()));
        }
        if (found.size() > 1) {
            return Result.err(severalSections(loaded, requested, found));
        }
        Section section = found.get(0);
        if (filter != null && !matches(section, filter)) {
            String unfiltered = command(loaded.target(), requested).text();
            return Result.err(new Failure.Validation(
                    "section " + requested + " (\"" + section.title() + "\") does not mention \"" + filter + "\"",
                    "Drop --filter to read it anyway.", unfiltered));
        }
        return Result.ok(toResult(section, sections.size(), loaded));
    }

    private static Optional<Section> byNumber(List<Section> sections, String requested) {
        try {
            int number = Integer.parseInt(requested.trim());
            return sections.stream().filter(section -> section.number() == number).findFirst();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    // Every section the title names: the exact matches when there are any, else those containing it.
    private static List<Section> byTitle(List<Section> sections, String requested) {
        String wanted = normalised(requested);
        if (wanted.isEmpty()) {
            return List.of();
        }
        List<Section> exact = sections.stream().filter(section -> normalised(section.title()).equals(wanted)).toList();
        if (!exact.isEmpty()) {
            return exact;
        }
        return sections.stream().filter(section -> normalised(section.title()).contains(wanted)).toList();
    }

    // A title is typed from prose, so it is matched on its letters and digits alone, in any case.
    private static String normalised(String title) {
        return title.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static boolean matches(Section section, String filter) {
        return Filter.matches(filter, section.markdown()) || Filter.matches(filter, section.title());
    }

    private static DiscoverResult.Readme toResult(Section section, int total, LoadedPackage loaded) {
        return new DiscoverResult.Readme(
                section.markdown(), section.lines(), section.number(), total, section.title());
    }

    private static Result<DiscoverResult> filtered(LoadedPackage loaded, List<Section> sections, Options options) {
        List<Section> matched = sections.stream().filter(section -> matches(section, options.filter())).toList();
        if (matched.size() == 1) {
            return Result.ok(toResult(matched.get(0), sections.size(), loaded));
        }

        List<DiscoverResult.ReadmeSections.Section> items = rows(loaded, matched);
        Command command = command(loaded.target()).filtered(options.filter());
        Result<Containers.Page> page = Containers.Page.of(options.page(), items.size(), command);
        if (!page.isOk()) {
            return page.cast();
        }
        Containers.Page window = page.value();
        List<DiscoverResult.ReadmeSections.Section> shown = items.subList(window.from(), window.to());
        return Result.ok(new DiscoverResult.ReadmeSections(
                shown, items.size(), window.paging(), window.next(command), true));
    }
}
