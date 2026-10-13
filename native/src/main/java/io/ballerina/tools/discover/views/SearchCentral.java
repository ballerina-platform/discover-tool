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
import java.util.Optional;
import java.util.Set;
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
 * <p><b>SYMBOLS MATCH EVERY KEYWORD.</b> Central's symbol search reads its keywords as alternatives unless they are
 * joined with {@code AND}, which it supports: {@code kafka send} alone matches 1,869 symbols, most of them some other
 * package's {@code send}; {@code kafka AND send} matches 33, kafka's {@code Producer} methods first. So the symbol
 * query joins the keywords with {@code AND}, and {@code total} is what Central matched for that. Its package search
 * has no such operator (it reads {@code AND} as one more keyword), so packages are asked with the keywords as typed.
 *
 * <p><b>A ROW OPENS WHAT IT NAMES, OR THE PACKAGE.</b> Central's {@code symbolType} names the declaration's form,
 * and each form maps to the bucket this tool lists it under: a method to its container's bucket, a function to
 * {@code funcs}, and every declaration to {@code type}, which opens any of them. An object type's method is the
 * one form that does not say its bucket: a remote or resource method makes its object a {@code client}, but a plain
 * one can be a client's or a class's ({@code sql:SchemaClient}'s {@code close} is a client's), so its row opens the
 * object itself with {@code type}, whose answer lists the method with its command. A form this tool does not
 * know, or a row missing the container or path its command needs, opens the package's overview instead of a
 * guess; so does a {@code configurable}, which no bucket lists.
 *
 * @since 0.1.0
 */
public final class SearchCentral {

    public static final String VERB = "search";

    static final int PACKAGES_PER_PAGE = 10;

    static final int SYMBOLS_PER_PAGE = 25;

    private static final String CLIENT_METHOD = "client-method";
    private static final String CLASS_METHOD = "class-method";
    private static final String OBJECT_METHOD = "object-type-method";
    private static final String SERVICE_METHOD = "service-type-method";
    private static final String LISTENER_METHOD = "listener-method";
    private static final String FUNCTION = "function";
    private static final String CLIENT = "client";
    private static final String CLASS = "class";
    private static final String SERVICE_TYPE = "service-type";
    private static final String TYPE_FORM = "-type";
    private static final Set<String> DECLARATIONS = Set.of("record", "enum", "error", "constant", "annotation",
            "listener", "variable", "type");
    private static final String AND = " AND ";
    private static final String MODULE_SEPARATOR = ".";
    private static final Pattern WORD_SEPARATOR = Pattern.compile("-");
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");
    private static final Pattern QUOTE_MARKS = Pattern.compile("^[>#\\s]+");
    private static final Pattern EMPHASIS = Pattern.compile("(?<!\\w)(\\*\\*|__)(?=\\S)(.+?)(?<=\\S)\\1(?!\\w)");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z])");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private SearchCentral() {
    }

    public static Result<DiscoverResult> render(List<String> keywords, int page, HttpOptions http) {
        List<String> terms = keywords.stream().flatMap(keyword -> Arrays.stream(WHITESPACE.split(keyword.trim())))
                .filter(term -> !term.isEmpty())
                .toList();
        Result<CentralSearch.Window<CentralSearch.PackageRow>> packages = CentralSearch.packages(
                String.join(" ", terms), PACKAGES_PER_PAGE, (page - 1) * PACKAGES_PER_PAGE, http);
        if (!packages.isOk()) {
            return packages.cast();
        }
        Result<CentralSearch.Window<CentralSearch.SymbolRow>> symbols = CentralSearch.symbols(
                String.join(AND, terms), SYMBOLS_PER_PAGE, (page - 1) * SYMBOLS_PER_PAGE, http);
        if (!symbols.isOk()) {
            return symbols.cast();
        }
        int packageTotal = packages.value().total();
        int symbolTotal = symbols.value().total();
        int pages = Math.max(1, Math.max(pagesOf(packageTotal, PACKAGES_PER_PAGE), pagesOf(symbolTotal,
                SYMBOLS_PER_PAGE)));
        String command = command(keywords);
        if (page > pages) {
            String fixed = pages == 1 ? command : command + " --page " + pages;
            return Result.err(new Failure.Validation("Page " + page + " is out of range: Central's answer has "
                    + pages + (pages == 1 ? " page." : " pages."),
                    "Pass a page from 1 to " + pages + ", e.g. " + Failure.quoted(fixed) + ".", fixed));
        }
        List<DiscoverResult.CentralSearch.PackageHit> packageHits = packages.value().items().stream()
                .map(row -> new DiscoverResult.CentralSearch.PackageHit(row.org() + "/" + row.name(), row.version(),
                        firstSentence(row.summary()), Coordinate.argument(row.org() + "/" + row.name(),
                                row.version(), null)))
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

    /** {@code bal discover search} with {@code keywords}, each a shell word. */
    public static String command(List<String> keywords) {
        return Coordinate.COMMAND + VERB + keywords.stream().map(word -> " " + Texts.shellWord(word))
                .collect(Collectors.joining());
    }

    private static int pagesOf(int total, int size) {
        return (total + size - 1) / size;
    }

    private static DiscoverResult.CentralSearch.SymbolHit symbolHit(CentralSearch.SymbolRow row) {
        String qualified = row.org() + "/" + row.name();
        String module = row.module().startsWith(row.name() + MODULE_SEPARATOR)
                ? row.module().substring(row.name().length() + 1)
                : null;
        String pkg = Coordinate.argument(qualified, row.version(), module);
        String parent = row.parent().isEmpty() ? null : Identifiers.plain(row.parent());
        String symbol = Identifiers.plain(row.symbol());
        String kind = WORD_SEPARATOR.matcher(row.type()).replaceAll(" ");
        Row hit = new Row(qualified, row.version(), module, pkg);
        Optional<String> bucket = methodBucket(row);
        if (bucket.isPresent()) {
            if (parent == null) {
                return hit.overview(kind, null, symbol);
            }
            String base = pkg + " " + bucket.get() + " " + Texts.shellWord(parent);
            if (row.resource()) {
                String[] parts = row.symbol().split(" ", 2);
                String path = parts.length < 2 ? "" : plainPath(parts[1]);
                return path.isEmpty()
                        ? hit.overview("resource method", parent, symbol)
                        : new DiscoverResult.CentralSearch.SymbolHit(qualified, row.version(), module,
                                "resource method", parent, null, path, parts[0],
                                base + " " + Texts.shellWord(path) + " " + parts[0]);
            }
            String method = row.remote() ? "remote method"
                    : Fn.Constructor.NAME.equals(symbol) ? "constructor" : "method";
            return hit.of(method, parent, symbol, base + " " + Texts.shellWord(symbol));
        }
        return switch (row.type()) {
            case LISTENER_METHOD, OBJECT_METHOD -> parent == null
                    ? hit.overview(kind, null, symbol)
                    : hit.of(kind, parent, symbol, pkg + " " + Types.BUCKET + " " + Texts.shellWord(parent));
            case FUNCTION -> hit.of(FUNCTION, null, symbol, pkg + " " + Surface.Scope.MODULE.verb() + " "
                    + Texts.shellWord(symbol));
            case CLIENT -> hit.of(kind, null, symbol, pkg + " " + Surface.Scope.CLIENT.verb() + " "
                    + Texts.shellWord(symbol));
            case CLASS -> hit.of(kind, null, symbol, pkg + " " + Surface.Scope.CLASS.verb() + " "
                    + Texts.shellWord(symbol));
            case SERVICE_TYPE -> hit.of(kind, null, symbol, pkg + " " + Surface.Scope.SERVICE.verb() + " "
                    + Texts.shellWord(symbol));
            default -> DECLARATIONS.contains(row.type()) || row.type().endsWith(TYPE_FORM)
                    ? hit.of(kind, null, symbol, pkg + " " + Types.BUCKET + " " + Texts.shellWord(symbol))
                    : hit.overview(kind, null, symbol);
        };
    }

    // The bucket a method row's container is listed under, or empty when the row is not a method's.
    private static Optional<String> methodBucket(CentralSearch.SymbolRow row) {
        return switch (row.type()) {
            case CLIENT_METHOD -> Optional.of(Surface.Scope.CLIENT.verb());
            case CLASS_METHOD -> Optional.of(Surface.Scope.CLASS.verb());
            case SERVICE_METHOD -> Optional.of(Surface.Scope.SERVICE.verb());
            case OBJECT_METHOD -> row.remote() || row.resource()
                    ? Optional.of(Surface.Scope.CLIENT.verb())
                    : Optional.empty();
            default -> Optional.empty();
        };
    }

    // What every row of one package version shares; `pkg` is the package argument, `--module` included.
    private record Row(String qualified, String version, String module, String pkg) {

        DiscoverResult.CentralSearch.SymbolHit of(String kind, String container, String name, String arguments) {
            return new DiscoverResult.CentralSearch.SymbolHit(qualified, version, module, kind, container, name, null,
                    null, arguments);
        }

        DiscoverResult.CentralSearch.SymbolHit overview(String kind, String container, String name) {
            return of(kind, container, name, pkg);
        }
    }

    // `repos/[string owner]/'import` as this tool prints a path: `repos/:owner/import`.
    private static String plainPath(String declared) {
        return Arrays.stream(declared.split(PathTree.SEPARATOR))
                .map(PathTree::displayDeclared)
                .collect(Collectors.joining(PathTree.SEPARATOR));
    }

    // The summary as one line of prose: images dropped, links as their text, emphasis and quote marks gone, cut at
    // the first sentence's end — a full stop followed by a capital, so `e.g. the` and `v2. the` are not one.
    private static String firstSentence(String summary) {
        String text = MARKDOWN_LINK.matcher(MARKDOWN_IMAGE.matcher(summary).replaceAll("")).replaceAll("$1");
        String plain = WHITESPACE.matcher(text).replaceAll(" ").trim();
        plain = EMPHASIS.matcher(QUOTE_MARKS.matcher(plain).replaceAll("")).replaceAll("$2").trim();
        return SENTENCE_END.split(plain, 2)[0];
    }
}
