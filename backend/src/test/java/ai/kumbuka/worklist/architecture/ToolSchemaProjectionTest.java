/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.architecture;

import ai.kumbuka.worklist.adapter.mcp.McpTools;
import ai.kumbuka.worklist.adapter.payload.SchemaPayload;
import ai.kumbuka.worklist.adapter.payload.ToolPayload;
import ai.kumbuka.worklist.surface.ArgumentDescriptions;
import ai.kumbuka.worklist.surface.SurfaceDeclaration;
import ai.kumbuka.worklist.surface.ToolDescriptions;
import ai.kumbuka.worklist.surface.ValueDescriptions;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tool schema is a projection of the declaration (C4.7): no tool schema
 * names the values of an argument and leaves out one the declaration declares
 * for it, and none names a value the declaration lacks.
 *
 * <p>An argument may stay open in the tool schema, with no list at all: the
 * view is, because the service refuses an unknown view in its check order and
 * a list in the schema would refuse it first, in the client. What the rule
 * forbids is the narrower list, a value the service takes and the assistant is
 * told it may not send.
 *
 * <p>Reads the tool list the MCP adapter builds and the declaration it is
 * built from, worded by stand-in bundles: the words are not what is checked,
 * and the stand-ins spare the container. That the tool list on the wire is
 * this one is {@code McpProjectionIT}'s.
 *
 * <p>RED STATE, observed 2026-10-29 with {@code rank} taken out of the sort's
 * list in the tool schema again: {@code worklist_query: sort closes with [id,
 * created_at] and leaves out [rank]}.
 */
class ToolSchemaProjectionTest {

    @Test
    void every_closed_argument_of_every_tool_carries_every_declared_value() {
        assertThat(offences(declared(), projected()))
            .as("the tool schema is a projection of the declaration (C4.7): an argument "
                + "the schema closes is closed with every value the declaration declares "
                + "for it. Close it with all of them, or leave it open")
            .isEmpty();
        assertThat(declared()).as("the declaration must have been read")
            .containsKey("worklist_query");
    }

    /** The red state, observed on every build: a narrower list and a wider one. */
    @Test
    void the_check_reports_a_list_that_leaves_a_value_out_and_one_that_adds_one() {
        Map<String, Map<String, List<String>>> declared = Map.of("worklist_query",
            Map.of("sort", List.of("id", "created_at", "rank"),
                "selector", List.of("item", "iteration")));
        Map<String, Map<String, @Nullable List<String>>> narrower = new LinkedHashMap<>();
        Map<String, @Nullable List<String>> query = new LinkedHashMap<>();
        query.put("sort", List.of("id", "created_at"));
        query.put("selector", null);
        narrower.put("worklist_query", query);
        assertThat(offences(declared, narrower))
            .as("RED STATE, observed: the sort closed without rank, the shape this service "
                + "had before C4.7; the open view is no offence")
            .containsExactly("worklist_query: sort closes with [id, created_at] and leaves "
                + "out [rank]");

        query.put("sort", List.of("id", "created_at", "rank", "title"));
        assertThat(offences(declared, narrower))
            .as("RED STATE, observed: a value the declaration does not declare")
            .containsExactly("worklist_query: sort closes with [id, created_at, rank, title], "
                + "which names [title] the declaration does not declare");
    }

    /**
     * The offences of a tool list against a declaration.
     *
     * @param declared  per call, the declared values of each argument that has any
     * @param projected per tool, the list each argument is closed with, or
     *                  null where it is open
     */
    static List<String> offences(Map<String, Map<String, List<String>>> declared,
                                 Map<String, Map<String, @Nullable List<String>>> projected) {
        List<String> offences = new ArrayList<>();
        declared.forEach((call, arguments) -> arguments.forEach((argument, values) -> {
            List<String> closed = projected.getOrDefault(call, Map.of()).get(argument);
            if (closed == null) {
                return;
            }
            List<String> left = values.stream().filter(v -> !closed.contains(v)).toList();
            List<String> added = closed.stream().filter(v -> !values.contains(v)).toList();
            if (!left.isEmpty()) {
                offences.add(call + ": " + argument + " closes with " + closed
                    + " and leaves out " + left);
            }
            if (!added.isEmpty()) {
                offences.add(call + ": " + argument + " closes with " + closed
                    + ", which names " + added + " the declaration does not declare");
            }
        }));
        return offences;
    }

    /** Per call, the values the declaration declares for each argument that has any. */
    private static Map<String, Map<String, List<String>>> declared() {
        Map<String, Map<String, List<String>>> declared = new LinkedHashMap<>();
        for (SurfaceDeclaration.Verb verb : SurfaceDeclaration.verbs()) {
            Map<String, List<String>> arguments = new LinkedHashMap<>();
            for (SurfaceDeclaration.Argument argument : verb.arguments()) {
                if (!argument.values().isEmpty()) {
                    arguments.put(argument.name().name(), argument.values().stream()
                        .map(SurfaceDeclaration.Value::name).toList());
                }
            }
            declared.put(SurfaceDeclaration.call(verb), arguments);
        }
        return declared;
    }

    /** Per tool, the list each argument is closed with in its schema, null where open. */
    private static Map<String, Map<String, @Nullable List<String>>> projected() {
        Map<String, Map<String, @Nullable List<String>>> projected = new LinkedHashMap<>();
        for (ToolPayload tool : McpTools.declared(standIn(ToolDescriptions.class),
                standIn(ArgumentDescriptions.class), standIn(ValueDescriptions.class))) {
            Map<String, @Nullable List<String>> arguments = new LinkedHashMap<>();
            Map<String, SchemaPayload> properties = tool.inputSchema().properties();
            if (properties != null) {
                properties.forEach((name, schema) -> arguments.put(name, schema.values()));
            }
            projected.put(tool.name(), arguments);
        }
        return projected;
    }

    /** A bundle whose every text is the name of its method. */
    private static <T> T standIn(Class<T> bundle) {
        return bundle.cast(Proxy.newProxyInstance(bundle.getClassLoader(),
            new Class<?>[] {bundle}, (proxy, method, arguments) -> method.getName()));
    }
}
