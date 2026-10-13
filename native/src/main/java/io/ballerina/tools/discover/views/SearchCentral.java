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

import io.ballerina.tools.discover.Coordinate;
import io.ballerina.tools.discover.Failure;
import io.ballerina.tools.discover.Result;
import io.ballerina.tools.discover.Texts;
import io.ballerina.tools.discover.central.CentralSearch;
import io.ballerina.tools.discover.central.HttpOptions;
import io.ballerina.tools.discover.model.Fn;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.Identifiers;
import io.ballerina.tools.discover.symbols.PathTree;
import io.ballerina.tools.discover.symbols.Surface;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * {@code bal discover search <keywords...>} — Ballerina Central's package search and symbol search, one page of each,
 * every row with the {@code bal discover} command that opens it.
 *
 * <p>The ranking is Central's, so the same keywords can rank differently as Central changes; what this tool adds is
 * the command per row, {@code shown}/{@code total}, and {@code --page}, which turns both rankings at once: page
 * {@code n} is Central's window at offset {@code (n - 1) × size} of each. A command names the version Central
 * matched, so it opens what the row describes even when a later version has dropped it.
 *
 * @since 0.1.0
 */
public final class SearchCentral {

    public static final String VERB = "search";

    static final int PACKAGES_PER_PAGE = 10;

    static final int SYMBOLS_PER_PAGE = 25;

    private static final String COMMAND = "bal discover ";
    private static final String CLIENT_METHOD = "client-method";
    private static final String CLASS_METHOD = "class-method";
    private static final String SERVICE_METHOD = "service-type-method";
    private static final String LISTENER_METHOD = "listener-method";
    private static final String FUNCTION = "function";
    private static final String MODULE_SEPARATOR = ".";
    private static final String PARAMETER_REST = "...";
    private static final Pattern WORD_SEPARATOR = Pattern.compile("-");
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern MARKDOWN_MARKS = Pattern.compile("^[>#\\s]+|\\*\\*|__");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SearchCentral() {
    }

    public static Result<DiscoverResult> render(List<String> keywords, int page, HttpOptions http) {
        String query = String.join(" ", keywords);
        Result<CentralSearch.Window<CentralSearch.PackageRow>> packages =
                CentralSearch.packages(query, PACKAGES_PER_PAGE, (page - 1) * PACKAGES_PER_PAGE, http);
        if (!packages.isOk()) {
            return packages.cast();
        }
        Result<CentralSearch.Window<CentralSearch.SymbolRow>> symbols =
                CentralSearch.symbols(query, SYMBOLS_PER_PAGE, (page - 1) * SYMBOLS_PER_PAGE, http);
        if (!symbols.isOk()) {
            return symbols.cast();
        }
        int packageTotal = packages.value().total();
        int symbolTotal = symbols.value().total();
        int pages = Math.max(1, Math.max(pagesOf(packageTotal, PACKAGES_PER_PAGE), pagesOf(symbolTotal,
                SYMBOLS_PER_PAGE)));
        String command = COMMAND + VERB + keywords.stream().map(word -> " " + Texts.shellWord(word))
                .collect(Collectors.joining());
        if (page > pages) {
            String fixed = pages == 1 ? command : command + " --page " + pages;
            return Result.err(new Failure.Validation("Page " + page + " is out of range: Central's answer has "
                    + pages + (pages == 1 ? " page." : " pages."),
                    "Pass a page from 1 to " + pages + ", e.g. " + Failure.quoted(fixed) + ".", fixed));
        }
        List<DiscoverResult.CentralSearch.PackageHit> packageHits = packages.value().items().stream()
                .map(row -> new DiscoverResult.CentralSearch.PackageHit(row.org() + "/" + row.name(), row.version(),
                        firstSentence(row.summary()),
                        COMMAND + Coordinate.argument(row.org() + "/" + row.name(), row.version(), null)))
                .toList();
        List<DiscoverResult.CentralSearch.SymbolHit> symbolHits = symbols.value().items().stream()
                .map(SearchCentral::symbolHit)
                .toList();
        int seen = Math.min(page * PACKAGES_PER_PAGE, packageTotal) + Math.min(page * SYMBOLS_PER_PAGE, symbolTotal);
        DiscoverResult.Paging paging = pages == 1
                ? null
                : new DiscoverResult.Paging(page, pages, packageTotal + symbolTotal - seen);
        return Result.ok(new DiscoverResult.CentralSearch(keywords, packageHits, packageTotal, symbolHits,
                symbolTotal, paging, page < pages ? command + " --page " + (page + 1) : null));
    }

    private static int pagesOf(int total, int size) {
        return (total + size - 1) / size;
    }

    private static DiscoverResult.CentralSearch.SymbolHit symbolHit(CentralSearch.SymbolRow row) {
        String qualified = row.org() + "/" + row.name();
        String module = row.module().startsWith(row.name() + MODULE_SEPARATOR)
                ? row.module().substring(row.name().length() + 1)
                : null;
        String pkg = COMMAND + Coordinate.argument(qualified, row.version(), module) + " ";
        String parent = row.parent().isEmpty() ? null : Identifiers.plain(row.parent());
        String symbol = Identifiers.plain(row.symbol());
        return switch (row.type()) {
            case CLIENT_METHOD, CLASS_METHOD, SERVICE_METHOD -> {
                String bucket = switch (row.type()) {
                    case CLIENT_METHOD -> Surface.Scope.CLIENT.verb();
                    case CLASS_METHOD -> Surface.Scope.CLASS.verb();
                    default -> Surface.Scope.SERVICE.verb();
                };
                String base = pkg + bucket + " " + Texts.shellWord(parent == null ? "" : parent) + " ";
                if (row.resource()) {
                    String[] parts = row.symbol().split(" ", 2);
                    String path = parts.length < 2 ? "" : plainPath(parts[1]);
                    yield new DiscoverResult.CentralSearch.SymbolHit(qualified, row.version(), module,
                            "resource method", parent, path + " " + parts[0],
                            base + Texts.shellWord(path) + " " + parts[0]);
                }
                String kind = row.remote() ? "remote method"
                        : Fn.Constructor.NAME.equals(symbol) ? "constructor" : "method";
                yield new DiscoverResult.CentralSearch.SymbolHit(qualified, row.version(), module, kind, parent,
                        symbol, base + Texts.shellWord(symbol));
            }
            case LISTENER_METHOD -> new DiscoverResult.CentralSearch.SymbolHit(qualified, row.version(), module,
                    "listener method", parent, symbol, pkg + Types.BUCKET + " " + Texts.shellWord(String.valueOf(
                            parent)));
            case FUNCTION -> new DiscoverResult.CentralSearch.SymbolHit(qualified, row.version(), module, FUNCTION,
                    null, symbol, pkg + Surface.Scope.MODULE.verb() + " " + Texts.shellWord(symbol));
            default -> new DiscoverResult.CentralSearch.SymbolHit(qualified, row.version(), module,
                    WORD_SEPARATOR.matcher(row.type()).replaceAll(" "), null, symbol,
                    pkg + Types.BUCKET + " " + Texts.shellWord(symbol));
        };
    }

    // `repos/[string owner]/'import` as this tool prints a path: `repos/:owner/import`.
    private static String plainPath(String declared) {
        return Arrays.stream(declared.split(PathTree.SEPARATOR))
                .map(segment -> {
                    if (!segment.startsWith("[") || !segment.endsWith("]")) {
                        return Identifiers.plain(segment);
                    }
                    String[] words = WHITESPACE.split(segment.substring(1, segment.length() - 1).trim());
                    String name = Identifiers.plain(words[words.length - 1]);
                    return segment.contains(PARAMETER_REST) ? ":" + PARAMETER_REST + name : ":" + name;
                })
                .collect(Collectors.joining(PathTree.SEPARATOR));
    }

    // The summary as one line of prose: images dropped, links as their text, emphasis and quote marks gone.
    private static String firstSentence(String summary) {
        String text = MARKDOWN_LINK.matcher(MARKDOWN_IMAGE.matcher(summary).replaceAll("")).replaceAll("$1");
        String plain = MARKDOWN_MARKS.matcher(WHITESPACE.matcher(text).replaceAll(" ").trim()).replaceAll("").trim();
        return SENTENCE_END.split(plain, 2)[0];
    }
}
