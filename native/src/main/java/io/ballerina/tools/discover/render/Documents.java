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
 * {@link Library} → the whole-package Ballerina document the {@code api} verb prints.
 *
 * <p>The output is not a compilable module and is not meant to be: it is the package's whole public
 * surface written in the language the caller is about to write, so a signature can be read straight off it
 * instead of inferred from prose. Function bodies are {@code ;}, and a declaration the reader has no
 * Ballerina form for becomes a comment naming it.
 *
 * <p>Since the addressed verbs landed, this is the fallback rather than the default: {@code overview},
 * {@code ops} and {@code type} answer by name or by path, and {@code api} exists for the question none of
 * them answered, and so that a stale instruction telling an agent to grep one file is recoverable rather
 * than fatal.
 *
 * @since 0.1.0
 */
public final class Documents {

    private Documents() {
    }

    /**
     * Section order is the output's contract with the caller: types, clients, functions, listeners,
     * annotations. Reordering it was proposed and rejected — it moves every declaration in all nine
     * snapshots and does not solve the motivating case, since {@code ballerinax/github}'s client section is
     * 2,715 lines on its own. The addressed verbs are the answer to "the client is at the bottom".
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

    /**
     * The {@code configurable} declarations, as COMMENTS, because they are not declarations a caller may write.
     *
     * <p>A {@code configurable} is module-private: {@code http:maxActiveConnections} from another module is
     * {@code attempt to refer to non-accessible symbol}, measured. So it cannot be printed as source in the code
     * register, where a declaration is something to copy — the same reason an unconfirmed service attachment is a
     * note here rather than a template.
     *
     * <p>It is here at all because {@code overview} stopped carrying it and this is the register that
     * still can. The entry document's section was addressed to a DEPLOYER rather than to someone writing a
     * {@code .bal} file, which is who that document is for; but the fact is real, {@code type} cannot reach it —
     * a configurable is not a declaration to resolve — and a fact reachable from no verb has been deleted rather
     * than moved. Expensive to reach is the intended cost. Unreachable was not.
     */
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

    // -----------------------------------------------------------------------
    // Annotations
    // -----------------------------------------------------------------------

    /**
     * An annotation declaration: its config record, its name, and every point it attaches to.
     *
     * <p>{@code public annotation HttpPayload Payload on parameter, return;} — the config record comes before the
     * name, which is the order the language reads it in and the reason the type can be dropped without the line
     * ceasing to parse. That is how {@code ResourceConfig} and {@code ServiceConfig} came to print as
     * {@code public annotation ResourceConfig on service_function;}: valid-looking, attached to a token the
     * compiler rejects, and giving no way to discover the field set of the record the attachment must carry.
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
        lines.add("public annotation " + config + annotation.name() + " on " + annotation.attachmentPoints() + ";"
                + Signatures.buildSpecialAgentNote(links));
        return String.join("\n", lines);
    }
}
