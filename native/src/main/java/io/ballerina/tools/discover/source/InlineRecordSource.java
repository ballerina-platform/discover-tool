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

package io.ballerina.tools.discover.source;

import io.ballerina.compiler.syntax.tree.FunctionBodyNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NonTerminalNode;
import io.ballerina.compiler.syntax.tree.RecordFieldNode;
import io.ballerina.compiler.syntax.tree.RecordFieldWithDefaultValueNode;
import io.ballerina.compiler.syntax.tree.RecordTypeDescriptorNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.compiler.syntax.tree.TypeDefinitionNode;
import io.ballerina.tools.discover.model.Defaults;
import io.ballerina.tools.discover.model.InlineRecords;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The inline record types of a module's source — every {@code record {...}} that is not the body of a type
 * definition — with the optionality and default each field declares.
 *
 * <p>Only what a caller's types can come from is read: function bodies and private type definitions declare
 * records no package API mentions. A default that names a declaration of the package, or runs long, is dropped.
 *
 * <p>Two declarations with the same field names and different answers are ambiguous, so neither is recovered.
 *
 * @since 0.1.0
 */
final class InlineRecordSource {

    private static final Pattern LINE_BREAK = Pattern.compile("\\s*\\R\\s*");
    private static final String LINE_COMMENT = "//";
    private static final int MAX_DEFAULT_LENGTH = 80;

    private InlineRecordSource() {
    }

    static InlineRecords.Declared collect(Iterable<? extends Node> roots) {
        Map<List<String>, Map<String, InlineRecords.Member>> found = new LinkedHashMap<>();
        Set<List<String>> ambiguous = new HashSet<>();
        roots.forEach(root -> visit(root, found, ambiguous));
        ambiguous.forEach(found::remove);
        return new InlineRecords.Declared(found);
    }

    private static void visit(Node node, Map<List<String>, Map<String, InlineRecords.Member>> found,
            Set<List<String>> ambiguous) {
        if (node instanceof FunctionBodyNode
                || node instanceof TypeDefinitionNode definition && definition.visibilityQualifier().isEmpty()) {
            return;
        }
        if (node instanceof RecordTypeDescriptorNode record
                && record.parent().kind() != SyntaxKind.TYPE_DEFINITION) {
            record(record, found, ambiguous);
        }
        if (node instanceof NonTerminalNode parent) {
            parent.children().forEach(child -> visit(child, found, ambiguous));
        }
    }

    private static void record(RecordTypeDescriptorNode record,
            Map<List<String>, Map<String, InlineRecords.Member>> found, Set<List<String>> ambiguous) {
        Map<String, InlineRecords.Member> members = new LinkedHashMap<>();
        for (Node field : record.fields()) {
            if (field instanceof RecordFieldNode plain) {
                members.put(unquoted(plain.fieldName().text()),
                        new InlineRecords.Member(plain.questionMarkToken().isPresent(), null));
            } else if (field instanceof RecordFieldWithDefaultValueNode withDefault) {
                members.put(unquoted(withDefault.fieldName().text()),
                        new InlineRecords.Member(false, writable(withDefault.expression().toSourceCode())));
            }
        }
        if (members.isEmpty()) {
            return;
        }
        List<String> key = InlineRecords.Declared.key(members.keySet());
        Map<String, InlineRecords.Member> earlier = found.putIfAbsent(key, members);
        if (earlier != null && !earlier.equals(members)) {
            ambiguous.add(key);
        }
    }

    // A default spread over lines is joined into one; one carrying a comment cannot be, so none is recovered.
    private static String writable(String expression) {
        String trimmed = expression.trim();
        if (trimmed.contains(LINE_COMMENT) && LINE_BREAK.matcher(trimmed).find()) {
            return null;
        }
        String joined = LINE_BREAK.matcher(trimmed).replaceAll(" ");
        return joined.length() <= MAX_DEFAULT_LENGTH && Defaults.isWritable(joined, Set.of()) ? joined : null;
    }

    private static String unquoted(String identifier) {
        return identifier.startsWith("'") ? identifier.substring(1) : identifier;
    }
}
