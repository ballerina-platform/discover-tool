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

package io.ballerina.tools.discover.render;

import io.ballerina.tools.discover.model.ClientClass;
import io.ballerina.tools.discover.model.Library;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link Library} → the whole-package Ballerina document.
 *
 * <p>The output is not a compilable module and is not meant to be: it is the package's whole public
 * surface written in the language the caller is about to write, so a signature can be read straight off it
 * instead of inferred from prose. Function bodies are {@code ;}, and a declaration the reader has no
 * Ballerina form for becomes a comment naming it.
 *
 * @since 0.1.0
 */
public final class Documents {

    private Documents() {
    }

    /**
     * Section order is the output's contract with the caller: types, clients, functions, listeners,
     * annotations, configurables.
     */
    public static String toSyntaxString(Library library) {
        List<String> output = new ArrayList<>();
        output.add("// ============================================================");
        output.add("// Library: " + library.name());
        if (!library.description().isEmpty()) {
            output.add("// " + library.description().split("\n", -1)[0]);
        }
        output.add("// ============================================================");
        output.add("import " + library.name() + ";");

        section(output, "// --- Types ---", library.typeDefs().stream().map(TypeDefs::renderTypeDef).toList());
        section(output, "// --- Client ---",
                library.clients().stream().map(ClientClass::asObjectDef).map(TypeDefs::renderTypeDef).toList());
        section(output, "// --- Functions ---",
                library.functions().stream().map(Signatures::renderStandaloneFunction).toList());
        section(output, "// --- Listeners ---",
                library.listeners().stream().map(TypeDefs::renderTypeDef).toList());
        section(output, "// --- Annotations ---",
                library.annotations().stream().map(Documents::renderAnnotation).toList());
        section(output, "// --- Configurables ---", configurableSection(library));

        output.add("");
        return String.join("\n", output);
    }

    // As comments: a configurable is module-private, so another module cannot refer to it.
    private static List<String> configurableSection(Library library) {
        if (library.configurables().isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add("// Set in the CALLER's Config.toml, under a [" + library.name().replace('/', '.')
                + "] table. These are\n// module-private, so they cannot be referenced from code — a default "
                + "above that a signature\n// also names is one you set here rather than pass.");
        for (Library.Configurable configurable : library.configurables()) {
            lines.add("// " + configurable.name() + " = " + configurable.defaultValue()
                    + "    # " + configurable.type().name()
                    + (configurable.description().isEmpty()
                            ? ""
                            : " — " + configurable.description().split("\n", -1)[0]));
        }
        return List.copyOf(lines);
    }

    private static void section(List<String> output, String title, List<String> rendered) {
        if (rendered.isEmpty()) {
            return;
        }
        output.add("");
        output.add(title);
        for (String item : rendered) {
            output.add("");
            output.add(item);
        }
    }

    /**
     * An annotation declaration: its config record, its name, and every point it attaches to.
     *
     * <p>The config record comes before the name ({@code public annotation HttpPayload Payload on parameter, return;}),
     * so a dropped type still parses — and hides the record the attachment must carry.
     */
    public static String renderAnnotation(Library.AnnotationDef annotation) {
        List<String> lines = new ArrayList<>();
        if (!annotation.description().isEmpty()) {
            for (String line : annotation.description().split("\n", -1)) {
                lines.add("# " + line);
            }
        }
        List<Signatures.ExternalLink> links = annotation.type()
                .map(Signatures::collectExternalLinks)
                .orElse(List.of());
        String config = annotation.type()
                .map(type -> Signatures.applyPrefixToTypeName(type.name(), links) + " ")
                .filter(name -> !name.isBlank())
                .orElse("");
        lines.add("public annotation " + config + annotation.name() + " on " + annotation.attachmentPoints() + ";");
        return String.join("\n", lines);
    }
}
