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
 * The transitive type closure a leaf inlines, from a declaration or from a signature.
 *
 * <p>Starting from a CALLABLE as well as from a declaration is what makes the common flow one call rather than two:
 * the real closure for github's cache DELETE includes {@code ActionsDeleteActionsCacheByKeyQueries} — the
 * included-record parameter whose FIELDS are the call's named arguments — which is the fact an agent needs to
 * write the call at all and which no signature line spells out.
 *
 * <p><b>BREADTH-FIRST, AND BOUNDED.</b> {@code ballerina/http}'s {@code ClientConfiguration} is 38 declarations
 * and 24,183 bytes unbounded. Breadth-first because a shallow field is likelier to be needed than a four-levels-deep
 * one, so a budget that truncates should truncate the far end. Every dropped name is NAMED rather than silently
 * missing, which keeps a truncated closure actionable: each one is a legal argument to {@code type}.
 *
 * <p><b>The roots are never dropped.</b> They are what was asked for, so they are charged before the budget is
 * consulted; a budget that could refuse the question is worse than a large answer.
 *
 * <p>Cross-package edges stop at the package boundary: {@code ballerina/http:ConnectionConfig} has a local closure
 * of one and fifteen external edges, so crossing would hide a cold fetch per edge inside an answer the caller
 * expects to be warm. {@link Types} names them instead.
 *
 * @since 0.1.0
 */
public final class Closure {

    /**
     * How many bytes a LEAF's closure may print — the declarations inlined under one fully-resolved result,
     * whether a signature's or a declaration's own. It bounds a closure (how many types a result transitively
     * names), unrelated to the entry ceiling on a listing.
     */
    public static final int LEAF_BYTES = 6_000;

    /** Every level. The depth argument exists for the one-level inlining a single-result view does. */
    public static final int UNBOUNDED = Integer.MAX_VALUE;

    /** Identifiers inside a rendered type expression. Builtins fall out by not being declarations. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private Closure() {
    }

    /**
     * A closure: the declarations to print, in walk order, and the ones the budget left out.
     *
     * @param names every declaration to render, roots first
     * @param omitted names reached but not printed, each a legal {@code type} argument
     */
    public record Result(List<String> names, List<String> omitted) {

        public boolean truncated() {
            return !omitted.isEmpty();
        }

        /** The printed declarations, in walk order. */
        public List<DiscoverResult.Signature.Type> types(Declarations index) {
            return names.stream()
                    .map(name -> new DiscoverResult.Signature.Type(name, TypeDefs.renderTypeDef(index.get(name))))
                    .toList();
        }
    }

    /**
     * The depth-1 closure a leaf inlines. An inclusion ({@code *T}) states that another record's fields ARE this
     * one's, so it does not consume the depth: {@code ClientConfiguration}'s own field types would otherwise
     * be hidden behind the records it includes.
     */
    public static Result leaf(List<String> roots, Declarations index) {
        return of(roots, index, LEAF_BYTES, 1);
    }

    /**
     * The closure of a set of declaration names.
     *
     * <p>The visited set is what makes it terminate on a cycle, and real packages have them — a record whose
     * field is an array of itself is ordinary.
     */
    public static Result of(List<String> roots, Declarations index, int budgetBytes, int maxDepth) {
        Set<String> visited = new LinkedHashSet<>();
        List<String> order = new ArrayList<>();
        Set<String> omitted = new LinkedHashSet<>();
        Deque<Step> queue = new ArrayDeque<>();

        for (String root : roots) {
            if (index.get(root) != null && visited.add(root)) {
                order.add(root);
                queue.add(new Step(root, 0));
            }
        }
        // The roots are charged but not checked: they are the question, and a budget that can refuse the
        // question is not a budget, it is a refusal.
        int spent = order.stream().mapToInt(name -> cost(name, index)).sum();

        while (!queue.isEmpty()) {
            Step step = queue.removeFirst();
            if (step.depth() >= maxDepth) {
                continue;
            }
            Set<String> included = inclusionsOf(index.get(step.name()));
            for (String referenced : localReferences(index.get(step.name()), index)) {
                if (visited.contains(referenced)) {
                    continue;
                }
                int size = cost(referenced, index);
                if (spent + size > budgetBytes) {
                    omitted.add(referenced);
                    continue;
                }
                visited.add(referenced);
                spent += size;
                order.add(referenced);
                queue.add(new Step(referenced, included.contains(referenced) ? step.depth() : step.depth() + 1));
            }
        }
        // A name reached late may also have been printed early on another branch; the omission list is what the
        // caller has to ask for again, so anything printed is removed from it.
        omitted.removeAll(visited);
        return new Result(List.copyOf(order), List.copyOf(omitted));
    }

    /**
     * One entry of the walk, carrying how far from a root it was reached.
     *
     * @param name the declaration reached
     * @param depth how many steps from a root it took to reach it
     */
    private record Step(String name, int depth) { }

    private static int cost(String name, Declarations index) {
        TypeDef typeDef = index.get(name);
        return typeDef == null ? 0 : Texts.byteLength(TypeDefs.renderTypeDef(typeDef)) + 2;
    }

    /**
     * The same-package declarations a signature names — its parameters' types and its return's.
     *
     * <p>This is the entry point that lets a callable have a closure at all. Deliberately the same walk afterwards:
     * a signature's types are just another set of roots, so there is one closure implementation rather than one
     * per entry point that could disagree about cycles or budgets.
     */
    public static List<String> rootsOf(Fn fn, Declarations index) {
        List<String> roots = new ArrayList<>();
        for (Param param : fn.params()) {
            addLocalNames(param.type(), index, roots);
        }
        if (fn.returns().hasType()) {
            addLocalNames(fn.returns().type(), index, roots);
        }
        return List.copyOf(new LinkedHashSet<>(roots));
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

    // -----------------------------------------------------------------------
    // Reading a declaration's type expressions
    // -----------------------------------------------------------------------

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
            // A class's dependencies are its members': the declaration itself has none to walk.
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
