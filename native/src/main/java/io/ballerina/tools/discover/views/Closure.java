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

import io.ballerina.tools.discover.Texts;
import io.ballerina.tools.discover.model.Fn;
import io.ballerina.tools.discover.model.Param;
import io.ballerina.tools.discover.model.RecordField;
import io.ballerina.tools.discover.model.TypeDef;
import io.ballerina.tools.discover.model.TypeRef;
import io.ballerina.tools.discover.render.DiscoverResult;
import io.ballerina.tools.discover.render.TypeDefs;
import io.ballerina.tools.discover.symbols.Declarations;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The types a leaf inlines under its declaration or signature, one level deep.
 *
 * <p>Starting from a CALLABLE as well as from a declaration is what makes the common flow one call rather than two:
 * the real closure for github's cache DELETE includes {@code ActionsDeleteActionsCacheByKeyQueries} — the
 * included-record parameter whose FIELDS are the call's named arguments — which is the fact an agent needs to
 * write the call at all and which no signature line spells out.
 *
 * <p>What is inlined is decided per type, never by cutting a leaf short. The type asked for and every parameter's
 * type are always printed, whatever their size, because a call cannot be written without them. Any other type —
 * a return type, or one a printed declaration refers to — is printed only while it is no larger than
 * {@link #INLINE_LIMIT_BYTES}. A class, client or service type is never inlined, since it is a surface of its own
 * rather than data. Every type a printed declaration refers to and the leaf does not print is NAMED, each one a
 * legal argument to {@code type}.
 *
 * <p>Cross-package edges stop at the package boundary: {@code ballerina/http:ConnectionConfig} has a local closure
 * of one and fifteen external edges, so crossing would hide a cold fetch per edge inside an answer the caller
 * expects to be warm. {@link Types} names them instead.
 *
 * @since 0.1.0
 */
public final class Closure {

    /** The largest declaration, rendered, that is inlined when it is not a parameter's type or the one asked for. */
    public static final int INLINE_LIMIT_BYTES = 6_000;

    private static final int LEAF_DEPTH = 1;

    /** Identifiers inside a rendered type expression. Builtins fall out by not being declarations. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private Closure() {
    }

    /**
     * The declarations to print, in walk order, and the ones left out.
     *
     * @param names every declaration to render, roots first
     * @param omitted names reached but not printed, each a legal {@code type} argument
     */
    public record Result(List<String> names, List<String> omitted) {

        public boolean truncated() {
            return !omitted.isEmpty();
        }

        public List<DiscoverResult.Signature.Type> types(Declarations index) {
            return names.stream()
                    .map(name -> new DiscoverResult.Signature.Type(name, TypeDefs.renderTypeDef(index.get(name))))
                    .toList();
        }
    }

    /**
     * Where a root comes from, which decides whether it can be left out.
     *
     * @param asked the declarations the caller named: always printed, even a class
     * @param parameters the types of a signature's parameters: always printed unless a class
     * @param others the remaining roots, such as a return type: printed within {@link #INLINE_LIMIT_BYTES}
     */
    public record Roots(List<String> asked, List<String> parameters, List<String> others) {

        public static Roots askedFor(List<String> names) {
            return new Roots(names, List.of(), List.of());
        }
    }

    /**
     * The depth-1 closure a leaf inlines. An inclusion ({@code *T}) states that another record's fields ARE this
     * one's, so it does not consume the depth and is printed whenever the record including it is:
     * {@code ClientConfiguration}'s own field types would otherwise be hidden behind the records it includes.
     *
     * <p>The visited set is what makes it terminate on a cycle, and real packages have them — a record whose
     * field is an array of itself is ordinary.
     */
    public static Result leaf(Roots roots, Declarations index) {
        Set<String> visited = new LinkedHashSet<>();
        List<String> order = new ArrayList<>();
        Set<String> omitted = new LinkedHashSet<>();
        Deque<Step> queue = new ArrayDeque<>();

        for (String root : roots.asked()) {
            if (index.get(root) != null && visited.add(root)) {
                order.add(root);
                queue.add(new Step(root, 0, true));
            }
        }
        admit(roots.parameters(), true, index, visited, order, omitted, queue);
        admit(roots.others(), false, index, visited, order, omitted, queue);

        while (!queue.isEmpty()) {
            Step step = queue.removeFirst();
            boolean beyondLeaf = step.depth() >= LEAF_DEPTH;
            Set<String> included = inclusionsOf(index.get(step.name()));
            for (String referenced : localReferences(index.get(step.name()), index)) {
                if (visited.contains(referenced)) {
                    continue;
                }
                if (beyondLeaf) {
                    omitted.add(referenced);
                    continue;
                }
                boolean inclusion = included.contains(referenced);
                if (!admits(referenced, step.unlimited() && inclusion, index)) {
                    omitted.add(referenced);
                    continue;
                }
                visited.add(referenced);
                order.add(referenced);
                queue.add(new Step(referenced, inclusion ? step.depth() : step.depth() + 1,
                        step.unlimited() && inclusion));
            }
        }
        // A name reached late may also have been printed early on another branch; the omission list is what the
        // caller has to ask for again, so anything printed is removed from it.
        omitted.removeAll(visited);
        return new Result(List.copyOf(order), List.copyOf(omitted));
    }

    private static void admit(
            List<String> names, boolean unlimited, Declarations index, Set<String> visited, List<String> order,
            Set<String> omitted, Deque<Step> queue) {
        for (String name : names) {
            if (index.get(name) == null || visited.contains(name)) {
                continue;
            }
            if (!admits(name, unlimited, index)) {
                omitted.add(name);
                continue;
            }
            visited.add(name);
            order.add(name);
            queue.add(new Step(name, 0, unlimited));
        }
    }

    private static boolean admits(String name, boolean unlimited, Declarations index) {
        TypeDef typeDef = index.get(name);
        if (typeDef instanceof TypeDef.ObjectDef) {
            return false;
        }
        return unlimited || Texts.byteLength(TypeDefs.renderTypeDef(typeDef)) <= INLINE_LIMIT_BYTES;
    }

    private record Step(String name, int depth, boolean unlimited) { }

    /** The same-package declarations a signature names, its parameters' types apart from its return's. */
    public static Roots rootsOf(Fn fn, Declarations index) {
        List<String> parameters = new ArrayList<>();
        for (Param param : fn.params()) {
            addLocalNames(param.type(), index, parameters);
        }
        List<String> others = new ArrayList<>();
        if (fn.returns().hasType()) {
            addLocalNames(fn.returns().type(), index, others);
        }
        others.removeAll(parameters);
        return new Roots(List.of(), List.copyOf(new LinkedHashSet<>(parameters)),
                List.copyOf(new LinkedHashSet<>(others)));
    }

    /** The same-package declarations one type expression names, for a declaration that has no signature. */
    public static List<String> rootsOf(TypeRef type, Declarations index) {
        List<String> roots = new ArrayList<>();
        addLocalNames(type, index, roots);
        return List.copyOf(roots);
    }

    private static void addLocalNames(TypeRef type, Declarations index, List<String> into) {
        for (String token : localTokens(type.name())) {
            if (index.get(token) != null && !into.contains(token)) {
                into.add(token);
            }
        }
    }

    private static Set<String> inclusionsOf(TypeDef typeDef) {
        Set<String> names = new LinkedHashSet<>();
        if (typeDef instanceof TypeDef.Rec record) {
            record.fields().stream()
                    .filter(field -> field.form() == RecordField.Form.INCLUSION)
                    .forEach(field -> names.add(field.type().name()));
        }
        return names;
    }

    private static List<String> localReferences(TypeDef typeDef, Declarations index) {
        if (typeDef == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (TypeRef expression : expressionsOf(typeDef)) {
            for (String token : localTokens(expression.name())) {
                if (index.get(token) != null && !names.contains(token)) {
                    names.add(token);
                }
            }
        }
        return List.copyOf(names);
    }

    /**
     * Every type expression a declaration mentions.
     *
     * <p>Read off the rendered EXPRESSION rather than off Central's links, so the local walk follows exactly the
     * spellings a reader can see. Each expression's own links answer the other half — WHICH module a foreign name
     * belongs to — which {@link Types} reads for its foreign rows.
     */
    public static List<TypeRef> expressionsOf(TypeDef typeDef) {
        return switch (typeDef) {
            case TypeDef.Rec record -> record.fields().stream().map(RecordField::type).toList();
            case TypeDef.Alias alias -> List.of(alias.type());
            case TypeDef.Constant constant -> List.of(constant.varType());
            case TypeDef.Variable variable -> List.of(variable.varType());
            case TypeDef.ErrorDef error -> error.base().map(List::of).orElse(List.of());
            case TypeDef.Enumeration ignored -> List.of();
            case TypeDef.ObjectDef object -> objectExpressions(object);
        };
    }

    private static List<TypeRef> objectExpressions(TypeDef.ObjectDef object) {
        List<TypeRef> types = new ArrayList<>(object.fields().stream().map(RecordField::type).toList());
        for (Fn fn : object.methods()) {
            fn.params().forEach(param -> types.add(param.type()));
            if (fn.returns().hasType()) {
                types.add(fn.returns().type());
            }
        }
        return List.copyOf(types);
    }

    private static List<String> localTokens(String expression) {
        List<String> local = new ArrayList<>();
        Matcher matcher = IDENTIFIER.matcher(expression);
        while (matcher.find()) {
            String token = matcher.group();
            int start = matcher.start();
            char before = start > 0 ? expression.charAt(start - 1) : '\0';
            char after = start + token.length() < expression.length()
                    ? expression.charAt(start + token.length())
                    : '\0';
            if (after != ':' && before != ':') {
                local.add(token);
            }
        }
        return List.copyOf(local);
    }
}
