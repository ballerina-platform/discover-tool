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

import io.ballerina.tools.discover.LoadedPackage;
import io.ballerina.tools.discover.model.Fn;
import io.ballerina.tools.discover.render.Identifiers;
import io.ballerina.tools.discover.symbols.PathTree;
import io.ballerina.tools.discover.symbols.Surface;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The readme sections whose code calls one method — what a method's answer lists under "Readme sections that call
 * it". Read off each section's fenced code alone, deterministically, in two steps.
 *
 * <p><b>THE CALL.</b> A remote method is called as {@code receiver->name(}, its name in either spelling
 * ({@code 'commit} or {@code commit}). A resource method is called as {@code receiver->/seg/[expr]/seg.accessor},
 * or {@code receiver->/seg} for {@code get}, with or without an argument list: the accessor and every literal
 * segment must match, a path parameter matches any one segment and a rest parameter the remaining ones, and a call
 * that a more literal resource of the same container also matches is that resource's, as Ballerina resolves it.
 * A plain method ({@code .name(}) is never matched: the same spelling calls every other object's methods.
 *
 * <p><b>THE RECEIVER.</b> Several containers often declare the same method ({@code http:Client} and
 * {@code http:FailoverClient} both {@code post}), so the variable the call is made on decides whose call it is. Its
 * type is read from where the readme declares it ({@code http:Client clientEp = ...}, in the same section first,
 * then anywhere in the readme): naming this container, the call is its; naming another container of this module,
 * it is not; naming neither or not found, the call counts only when no other container of this module declares a
 * method the call would also match.
 *
 * @since 0.1.0
 */
final class ReadmeCalls {

    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)");
    private static final String SEGMENT = "(?:\\[[^\\]]*]|(?:\\\\.|[\\w'])*)";
    private static final Pattern RESOURCE_CALL = Pattern.compile("([\\w.']+)\\s*->\\s*/(" + SEGMENT + "(?:/" + SEGMENT
            + ")*)(?:\\s*\\.\\s*([A-Za-z_]\\w*))?");
    private static final Pattern DECLARATION = Pattern.compile(
            "((?:[A-Za-z_][\\w.]*:[A-Za-z_]\\w*\\??\\s*\\|?\\s*)+)\\s+'?([A-Za-z_]\\w*)\\s*[=;,)]");
    private static final Pattern SERVICE_ON = Pattern.compile(
            "\\bservice\\b[^{;]*?\\bon\\s+(new\\s+)?([A-Za-z_][\\w.]*(?::[A-Za-z_]\\w*)?)");
    private static final String DEFAULT_ACCESSOR = "get";
    private static final String ATTACH = "attach";
    private static final String ROOT = ".";

    private ReadmeCalls() {
    }

    /** The sections of the module's readme whose code calls {@code entry} of {@code container}. */
    static List<Readme.Section> calling(LoadedPackage loaded, Surface.Container container, Containers.Entry entry) {
        if (!(entry.fn() instanceof Fn.Remote || entry.fn() instanceof Fn.Resource)) {
            return List.of();
        }
        List<Readme.Section> sections = Readme.sectionsOf(loaded);
        StringBuilder readme = new StringBuilder();
        sections.forEach(section -> readme.append(codeOf(section.markdown())));
        Owners owners = new Owners(loaded, container);
        List<Readme.Section> found = new ArrayList<>();
        for (Readme.Section section : sections) {
            String code = codeOf(section.markdown());
            boolean calls = callsIn(code, container, entry).stream()
                    .anyMatch(receiver -> owners.owns(receiver, code, readme.toString(), entry));
            if (calls) {
                found.add(section);
            }
        }
        return List.copyOf(found);
    }

    // The receiver of every call of `entry` in `code`, in order.
    static List<String> callsIn(String code, Surface.Container container, Containers.Entry entry) {
        List<String> receivers = new ArrayList<>();
        switch (entry.fn()) {
            case Fn.Remote remote -> {
                Matcher matcher = Pattern.compile("([\\w.']+)\\s*->\\s*'?"
                        + Pattern.quote(Identifiers.plain(remote.name())) + "\\s*\\(").matcher(code);
                while (matcher.find()) {
                    receivers.add(matcher.group(1));
                }
            }
            case Fn.Resource resource -> resourceCalls(code).stream()
                    .filter(call -> resolvesTo(call, container, entry.path(), resource.accessor()))
                    .forEach(call -> receivers.add(call.receiver()));
            case Fn.Normal ignored -> {
                // Not matched: `.name(` reads the same on every object.
            }
            case Fn.Constructor ignored -> {
                // Not a call.
            }
        }
        return receivers;
    }

    /**
     * One resource call in code.
     *
     * @param receiver what it is called on, as written
     * @param segments the path as written, one entry per segment, a computed one ({@code [owner]}) kept bracketed
     * @param accessor the accessor it names, {@code get} when it names none
     */
    record Call(String receiver, List<String> segments, String accessor) { }

    static List<Call> resourceCalls(String code) {
        List<Call> calls = new ArrayList<>();
        Matcher matcher = RESOURCE_CALL.matcher(code);
        while (matcher.find()) {
            String path = matcher.group(2);
            List<String> segments = path.isEmpty() ? List.of() : List.of(path.split(PathTree.SEPARATOR, -1));
            calls.add(new Call(matcher.group(1), segments,
                    matcher.group(3) == null ? DEFAULT_ACCESSOR : matcher.group(3)));
        }
        return calls;
    }

    private static boolean resolvesTo(Call call, Surface.Container container, List<String> path, String accessor) {
        int score = score(call, path, accessor);
        if (score < 0) {
            return false;
        }
        return container.operations().stream()
                .noneMatch(operation -> score(call, operation.segments(), operation.fn().accessor()) > score);
    }

    // How many literal segments the call matches on this resource, or -1 when it does not call it at all.
    private static int score(Call call, List<String> path, String accessor) {
        if (!call.accessor().equals(accessor)) {
            return -1;
        }
        List<String> declared = path.size() == 1 && ROOT.equals(path.get(0)) ? List.of() : path;
        int literals = 0;
        for (int i = 0; i < declared.size(); i++) {
            String segment = declared.get(i);
            if (segment.startsWith(PathTree.REST)) {
                return literals;
            }
            if (i >= call.segments().size()) {
                return -1;
            }
            String written = call.segments().get(i);
            if (segment.startsWith(PathTree.PARAMETER)) {
                continue;
            }
            if (written.startsWith("[") || !Identifiers.plain(written).equals(segment)) {
                return -1;
            }
            literals++;
        }
        return declared.size() == call.segments().size() ? literals : -1;
    }

    /**
     * Who a receiver's call belongs to, read off the readme's declarations.
     *
     * @param prefix the import prefix the module's own types are written with
     * @param container the container asked about
     * @param others every other container of the module
     */
    private record Owners(String prefix, Surface.Container container, List<Surface.Container> others) {

        Owners(LoadedPackage loaded, Surface.Container container) {
            this(prefixOf(loaded), container, Arrays.stream(Surface.Scope.values())
                    .filter(scope -> scope != Surface.Scope.MODULE)
                    .flatMap(scope -> Surface.of(loaded.library(), scope).stream())
                    .filter(other -> !other.name().equals(container.name()))
                    .toList());
        }

        boolean owns(String receiver, String section, String readme, Containers.Entry entry) {
            String variable = Identifiers.plain(receiver.substring(receiver.lastIndexOf('.') + 1));
            List<String> types = declaredType(variable, section).or(() -> declaredType(variable, readme))
                    .orElse(List.of());
            if (types.contains(typeOf(container))) {
                return true;
            }
            if (others.stream().anyMatch(other -> types.contains(typeOf(other)))) {
                return false;
            }
            return others.stream().noneMatch(other -> declaresSame(other, entry));
        }

        private String typeOf(Surface.Container each) {
            return prefix + ":" + each.plainName();
        }

        private boolean declaresSame(Surface.Container other, Containers.Entry entry) {
            return switch (entry.fn()) {
                case Fn.Remote remote -> other.standalone().stream().anyMatch(fn -> fn instanceof Fn.Remote
                        && Identifiers.plain(fn.name()).equals(Identifiers.plain(remote.name())));
                case Fn.Resource resource -> other.operations().stream().anyMatch(operation ->
                        operation.fn().accessor().equals(resource.accessor())
                                && operation.segments().equals(entry.path()));
                default -> false;
            };
        }
    }

    private static String prefixOf(LoadedPackage loaded) {
        return Identifiers.plain(loaded.moduleRef().prefix());
    }

    // The types `variable` is declared with in `code` — the last declaration wins — each as `prefix:Name`.
    private static Optional<List<String>> declaredType(String variable, String code) {
        Matcher matcher = DECLARATION.matcher(code);
        List<String> found = null;
        while (matcher.find()) {
            if (matcher.group(2).equals(variable)) {
                found = Arrays.stream(matcher.group(1).split("\\|"))
                        .map(type -> type.replace("?", "").trim())
                        .toList();
            }
        }
        return Optional.ofNullable(found);
    }

    /**
     * The sections of the module's readme whose code declares a service of {@code container}: typed as it
     * ({@code service cdc:Service on ...}), or untyped on a listener it is the service of, or, for the only service
     * type of a module that declares no listener, any service at all.
     *
     * <p>An untyped service ({@code service /x on new http:Listener(9090)}, or on a variable declared as one) takes
     * the type of the listener's {@code attach} parameter, so it is {@code container}'s only when that parameter is
     * {@code container} itself, or when {@code container} is the one service type that listener accepts:
     * {@code http:Listener} also accepts {@code ServiceContract}, but an untyped service on it is an
     * {@code http:Service}.
     *
     * @param listeners the listener types the service type binds to, as written: {@code http:Listener}
     */
    static List<Readme.Section> declaringService(LoadedPackage loaded, Surface.Container container,
            List<String> listeners) {
        String type = container.plainName().contains(":")
                ? container.plainName()
                : prefixOf(loaded) + ":" + container.plainName();
        Pattern typed = Pattern.compile("\\bservice\\s+" + Pattern.quote(type) + "\\b");
        List<String> untyped = listeners.stream().filter(listener -> takesUntyped(loaded, container, listener))
                .toList();
        boolean anyService = loaded.library().listeners().isEmpty()
                && Surface.of(loaded.library(), Surface.Scope.SERVICE).size() == 1;
        List<Readme.Section> sections = Readme.sectionsOf(loaded);
        StringBuilder readme = new StringBuilder();
        sections.forEach(section -> readme.append(codeOf(section.markdown())));
        List<Readme.Section> found = new ArrayList<>();
        for (Readme.Section section : sections) {
            String code = codeOf(section.markdown());
            Matcher declared = SERVICE_ON.matcher(code);
            boolean declares = typed.matcher(code).find();
            while (!declares && declared.find()) {
                String target = declared.group(2);
                String listener = declared.group(1) != null
                        ? target
                        : declaredType(target, code).or(() -> declaredType(target, readme.toString()))
                                .orElse(List.of()).stream().findFirst().orElse("");
                declares = anyService || untyped.contains(listener);
            }
            if (declares) {
                found.add(section);
            }
        }
        return List.copyOf(found);
    }

    private static boolean takesUntyped(LoadedPackage loaded, Surface.Container container, String listener) {
        long accepted = Surface.of(loaded.library(), Surface.Scope.SERVICE).stream()
                .filter(each -> each.pairings().stream().anyMatch(pairing -> pairing.listener().equals(listener)))
                .count();
        if (accepted == 1) {
            return true;
        }
        String name = listener.substring(listener.indexOf(':') + 1);
        return loaded.library().listeners().stream()
                .filter(declared -> Identifiers.plain(declared.name()).equals(name))
                .flatMap(declared -> declared.methods().stream())
                .filter(method -> method instanceof Fn.Normal normal && ATTACH.equals(normal.name())
                        && !normal.params().isEmpty())
                .anyMatch(attach -> Identifiers.plain(attach.params().get(0).type().name().trim())
                        .equals(container.plainName()));
    }

    /** Every fenced block of {@code markdown}, joined; prose is never read. */
    static String codeOf(String markdown) {
        StringBuilder code = new StringBuilder();
        boolean inside = false;
        for (String line : markdown.split("\\R", -1)) {
            if (FENCE.matcher(line).find()) {
                inside = !inside;
                continue;
            }
            if (inside) {
                code.append(line).append('\n');
            }
        }
        return code.toString();
    }
}
