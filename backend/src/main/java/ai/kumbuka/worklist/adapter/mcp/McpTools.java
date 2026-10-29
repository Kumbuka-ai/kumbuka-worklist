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
 * <p><strong>MCP omits and never adds.</strong> The verbs of the scheme this
 * service refuses by name are absent from {@code tools/list} and still answered
 * by name when called, with the same typed refusal the REST surface gives.
 */
public final class McpTools {

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
            .map(verb -> new ToolPayload(verb.name().name(), verb.description().apply(tools),
                schema(verb, arguments, values)))
            .toList();
    }

    /** The names of the tools this server declares, in the order of tools/list. */
    public static List<String> names() {
        return SurfaceDeclaration.verbs().stream().map(v -> v.name().name()).toList();
    }

    /** The argument names one tool declares; absent for a name that is no declared tool. */
    public static Optional<List<String>> argumentsOf(String tool) {
        return SurfaceDeclaration.verb(tool)
            .map(SurfaceDeclaration.Verb::argumentNames);
    }

    /**
     * A JSON Schema object, with {@code additionalProperties} closed.
     *
     * <p>Closed rather than open, deliberately: an argument this surface does not
     * know is one a caller believes in, and it is refused by name rather than
     * accepted and ignored (DEC-0043 R4). A fixed value set is closed with
     * {@code enum}, and {@code oneOf} carries a description per value.
     */
    private static SchemaPayload schema(SurfaceDeclaration.Verb verb,
                                        ArgumentDescriptions arguments,
                                        ValueDescriptions values) {
        Map<String, SchemaPayload> properties = new LinkedHashMap<>();
        List<String> mandatory = new ArrayList<>();
        for (SurfaceDeclaration.Argument argument : verb.arguments()) {
            properties.put(argument.name().name(), SchemaPayload.property(
                argument.type().json(), argument.description().apply(arguments),
                argument.values().stream()
                    .filter(SurfaceDeclaration.Value::inToolSchema)
                    .map(v -> new SchemaValuePayload(v.name(), v.description().apply(values)))
                    .toList()));
            if (argument.required()) {
                mandatory.add(argument.name().name());
            }
        }
        return SchemaPayload.object(properties, List.copyOf(mandatory));
    }
}
