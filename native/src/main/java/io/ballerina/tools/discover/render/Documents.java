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
import io.ballerina.tools.discover.model.ModuleRef;
import io.ballerina.tools.discover.model.Param;
import io.ballerina.tools.discover.model.Service;
import io.ballerina.tools.discover.model.TypeDef;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
     * The opening comment of a code-register document: what was resolved, then what is wrong with it.
     *
     * <p>A version nobody verified says so in the document itself, where a comment is the only thing a
     * Ballerina file can carry.
     *
     * @param warning {@code null} when there is nothing to warn about, and then no second line is written
     */
    public static String headerComment(String identity, String warning) {
        return warning == null ? "// " + identity : "// " + identity + "\n// Warning: " + warning;
    }

    /**
     * Section order is the output's contract with the caller: types, clients, functions, services,
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
        // Listeners immediately before the services written against them: a listener declaration and the
        // `service … on new Listener(…)` template are one story, and the template names the arguments whose
        // types only the declaration gives.
        section(output, "// --- Listeners ---",
                library.listeners().stream().map(TypeDefs::renderTypeDef).toList());
        section(output, "// --- Service ---", serviceSection(library));
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
    // Services and annotations
    // -----------------------------------------------------------------------

    /**
     * The listener's constructor as ARGUMENTS, which is what a {@code new} call takes.
     *
     * <p>The parameter NAMES, because that is the only part of a declaration that belongs in a call. Emitting
     * the whole declaration produced six compiler errors on one line for kafka, the first of which was
     * {@code too many arguments in call to 'new()'} and the rest of which came from
     * {@code string|string[] bootstrapServers} being parsed as member access on {@code string}. A template a
     * caller is meant to copy has to parse.
     */
    private static String listenerArguments(Service.Listener listener) {
        return listener.initParams().stream()
                .map(Param::name)
                .collect(Collectors.joining(", "));
    }

    /** {@code kafka:Listener} → {@code kafka}, the alias the service type needs too. */
    private static String deriveListenerAlias(String listenerName) {
        int index = listenerName.indexOf(':');
        return index > 0 ? listenerName.substring(0, index) : null;
    }

    /**
     * How a service is written against this package: a template per service type a listener accepts, then a note
     * naming the types whose attachment could not be settled, then one naming the types no listener accepts.
     *
     * <p>HTTP-14. Every service object type used to get {@code service X on new Listener(…)}, and 5 of the 10
     * the corpus produced do not compile: {@code ballerina/http}'s four interceptor types and
     * {@code ballerina/graphql}'s {@code Interceptor} are service objects a listener does not accept — an
     * interceptor reaches the runtime through {@code createInterceptors()}, not through an attachment. Which
     * types a listener accepts is {@code Bindings}' call; a pairing it could not confirm gets no template,
     * because printing a declaration that does not compile is the failure this document exists to prevent, and
     * the note is what keeps the gap from being a silent one. Each of these types is declared in full in the
     * Types section either way, so no contract is lost.
     */
    private static List<String> serviceSection(Library library) {
        List<String> rendered = new ArrayList<>();
        List<Service> unconfirmed = new ArrayList<>();
        Set<String> paired = new HashSet<>();
        for (Service service : library.services()) {
            if (service.declaredIn().isEmpty()) {
                paired.add(service.name());
            }
            if (service.isAttachable()) {
                rendered.add(renderService(service));
            } else {
                unconfirmed.add(service);
            }
        }
        if (!unconfirmed.isEmpty()) {
            rendered.add(unconfirmedAttachments(unconfirmed));
        }
        List<String> unattachable = library.typeDefs().stream()
                .filter(TypeDef.ObjectDef.class::isInstance)
                .map(TypeDef.ObjectDef.class::cast)
                .filter(object -> object.role() == TypeDef.ObjectDef.Role.SERVICE && !paired.contains(object.name()))
                .map(TypeDef::name)
                .distinct()
                .toList();
        if (!library.listeners().isEmpty() && !unattachable.isEmpty()) {
            rendered.add(unattachableTypes(library, unattachable));
        }
        return List.copyOf(rendered);
    }

    private static String unconfirmedAttachments(List<Service> unconfirmed) {
        String listener = unconfirmed.get(0).listener().name();
        String alias = deriveListenerAlias(listener);
        List<String> lines = new ArrayList<>();
        lines.add("// These service object types are declared above; this reader cannot confirm that "
                + listener);
        lines.add("// accepts them, so it writes no attachment template for them:");
        for (Service service : unconfirmed) {
            lines.add("//   " + (alias == null ? "" : alias + ":") + service.name());
        }
        lines.add("// A listener accepts the type its `attach` takes and any `distinct service object` type that");
        lines.add("// INCLUDES it; for these, that could not be read — the listener publishes no `attach`, or the");
        lines.add("// package source that shows inclusions was unavailable. The package's own guide is where the");
        lines.add("// usage of each is written; `bal discover <org>/<name> readme` reproduces it.");
        return String.join("\n", lines);
    }

    /** The service types no listener of the package accepts — named, so their absence above is not a gap. */
    private static String unattachableTypes(Library library, List<String> names) {
        String moduleId = library.name().substring(library.name().indexOf('/') + 1);
        String alias = moduleId.substring(moduleId.lastIndexOf('.') + 1);
        List<String> lines = new ArrayList<>();
        lines.add("// Declared above, but no listener of this package accepts them, so no template is written:");
        for (String name : names) {
            lines.add("//   " + alias + ":" + name);
        }
        return String.join("\n", lines);
    }

    private static String renderService(Service service) {
        List<String> lines = new ArrayList<>();
        if (service.isDeprecated()) {
            lines.add("@deprecated");
        }
        String alias = service.declaredIn().map(ModuleRef::prefix)
                .orElseGet(() -> deriveListenerAlias(service.listener().name()));
        String prefix = !service.name().isEmpty() && alias != null ? alias + ":" + service.name() + " " : "";
        lines.add("service " + prefix + "on new " + service.listener().name()
                + "(" + listenerArguments(service.listener()) + ") {");
        if (service.declaredIn().isPresent()) {
            String coordinate = service.declaredIn().get().coordinate();
            lines.add("    // " + coordinate + " declares this service type's contract: `bal discover " + coordinate
                    + " service " + service.name() + "`.");
        } else if (service.methods().isEmpty()) {
            // A SKELETON with a named hole, rather than a block that silently does not compile. Central
            // publishes no methods for `graphql:Service` or `kafka:Service`, and both listeners require one —
            // measured: `a GraphQL service must include at least one resource method with the accessor 'get'`
            // and `Service must have remote method onConsumerRecord`. `http:Service` is the case where an empty
            // body genuinely compiles, so the difference is real and only the package's guide states which
            // applies.
            lines.add("    // Central publishes no method contract for this service type. The listener may "
                    + "still require");
            lines.add("    // one — add the resource or remote methods the package's guide shows; "
                    + "`bal discover <org>/<name>");
            lines.add("    // readme` reproduces it.");
        }
        // The same renderer every other callable uses, so a service method keeps its parameter defaults, its
        // optionality, its doc comment and its import note. The hand-rolled copy this replaces kept none of
        // them.
        lines.addAll(TypeDefs.renderMembers(List.of(), service.methods()));
        lines.add("}");
        return String.join("\n", lines);
    }

    /**
     * An annotation declaration: its config record, its name, and every point it attaches to.
     *
     * <p>{@code public annotation HttpPayload Payload on parameter, return;} — the config record comes before the
     * name, which is the order the language reads it in and the reason the type can be dropped without the line
     * ceasing to parse. That is how {@code ResourceConfig} and {@code ServiceConfig} came to print as
     * {@code public annotation ResourceConfig on service_function;}: valid-looking, attached to a token the
     * compiler rejects, and giving no way to discover the field set of the record the attachment must carry.
     */
    private static String renderAnnotation(Library.AnnotationDef annotation) {
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
