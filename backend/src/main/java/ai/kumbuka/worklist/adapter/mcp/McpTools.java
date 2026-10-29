/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.mcp;

import ai.kumbuka.worklist.adapter.payload.SchemaPayload;
import ai.kumbuka.worklist.adapter.payload.SchemaValuePayload;
import ai.kumbuka.worklist.adapter.payload.ToolPayload;
import ai.kumbuka.worklist.surface.ArgumentDescriptions;
import ai.kumbuka.worklist.surface.DeclarationDocument;
import ai.kumbuka.worklist.surface.SurfaceDeclaration;
import ai.kumbuka.worklist.surface.ToolDescriptions;
import ai.kumbuka.worklist.surface.ValueDescriptions;
import io.quarkus.qute.i18n.MessageBundles;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tools the MCP exposition declares: the verbs this service carries, and
 * nothing else.
 *
 * <p><strong>Generated, never written.</strong> Each tool is one verb of
 * {@link SurfaceDeclaration}: its name, its arguments with their JSON types,
 * which are required, the fixed value sets, and the texts the message bundles
 * hold for the verb, each argument and each value. Nothing here says anything
 * of its own, so the tool list and the declaration cannot drift apart (ADR-0039).
 *
 * <p><strong>A tool is named {@code worklist_<verb>}</strong>, the identity the
 * declaration publishes for the call (C4.2, DEC-0043 R1). The bare verb a tool
 * was named by before is still taken on {@code tools/call} and never listed;
 * it goes in the breaking step of the service, with the other old forms.
 *
 * <p><strong>MCP omits and never adds.</strong> The verbs of the scheme this
 * service refuses by name are absent from {@code tools/list} and still answered
 * by name when called, with the same typed refusal the REST surface gives.
 */
public final class McpTools {

    /** What a tool name carries before its verb: the scheme and an underscore. */
    private static final String PREFIX = DeclarationDocument.SCHEME + "_";

    private McpTools() {
    }

    /** The tools, in the order of the declaration. */
    public static List<ToolPayload> declared() {
        return declared(MessageBundles.get(ToolDescriptions.class),
            MessageBundles.get(ArgumentDescriptions.class),
            MessageBundles.get(ValueDescriptions.class));
    }

    /** The tools, worded by the bundles given. */
    public static List<ToolPayload> declared(ToolDescriptions tools,
                                             ArgumentDescriptions arguments,
                                             ValueDescriptions values) {
        return SurfaceDeclaration.verbs().stream()
            .map(verb -> new ToolPayload(SurfaceDeclaration.call(verb),
                verb.description().apply(tools),
                schema(verb, arguments, values)))
            .toList();
    }

    /** The names of the tools this server declares, in the order of tools/list. */
    public static List<String> names() {
        return SurfaceDeclaration.verbs().stream().map(SurfaceDeclaration::call).toList();
    }

    /**
     * The verb a tool name calls: the name without the service prefix, or the
     * bare verb a tool was named by before; absent for a name that calls no
     * verb, carried or refused by name.
     */
    public static Optional<String> verbOf(String tool) {
        String verb = tool.startsWith(PREFIX) ? tool.substring(PREFIX.length()) : tool;
        return SurfaceDeclaration.isNamed(verb) ? Optional.of(verb) : Optional.empty();
    }

    /** The argument names one verb declares; absent for a verb that is not carried. */
    public static Optional<List<String>> argumentsOf(String verb) {
        return SurfaceDeclaration.verb(verb)
            .map(SurfaceDeclaration.Verb::argumentNames);
    }

    /**
     * A JSON Schema object, with {@code additionalProperties} closed.
     *
     * <p>Closed rather than open, deliberately: an argument this surface does not
     * know is one a caller believes in, and it is refused by name rather than
     * accepted and ignored (DEC-0043 R4). A fixed value set is closed with
     * {@code enum}, every declared value in it (C4.7), and {@code oneOf} carries
     * a description per value; an argument the declaration leaves open in the
     * tool list -- the view -- carries none. A bound travels as JSON Schema
     * spells it.
     */
    private static SchemaPayload schema(SurfaceDeclaration.Verb verb,
                                        ArgumentDescriptions arguments,
                                        ValueDescriptions values) {
        Map<String, SchemaPayload> properties = new LinkedHashMap<>();
        List<String> mandatory = new ArrayList<>();
        for (SurfaceDeclaration.Argument argument : verb.arguments()) {
            List<SchemaValuePayload> closed = argument.openInTools() ? List.of()
                : argument.values().stream()
                    .map(v -> new SchemaValuePayload(v.name(), v.description().apply(values)))
                    .toList();
            properties.put(argument.name().name(), SchemaPayload.property(
                argument.type().json(), argument.description().apply(arguments), closed,
                argument.minimum(), argument.maximum()));
            if (argument.required()) {
                mandatory.add(argument.name().name());
            }
        }
        return SchemaPayload.object(properties, List.copyOf(mandatory));
    }
}
