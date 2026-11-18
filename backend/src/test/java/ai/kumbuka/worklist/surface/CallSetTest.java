/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.adapter.mcp.McpTools;
import ai.kumbuka.worklist.adapter.rest.CustomMethod;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The work surface carries exactly the calls of its target cut, nothing more
 * and nothing less: on the MCP tool list, in the declaration, and on the REST
 * routes.
 *
 * <p>The calls, per kind of object, as the target cut fixes them: an item is
 * created, read, updated, queried, related and unrelated, validated, and
 * tagged and untagged, and its status moves through {@code update} alone; an
 * iteration is created, read, updated, queried, closed and advanced; a
 * milestone is created, read, updated, queried and closed; a membership is
 * planned, unplanned, read and updated; a workstream is read and queried.
 * Their union is the thirteen below, written here and never read from the
 * service. {@code accept}, {@code withdraw}, {@code claim}, {@code release}
 * and {@code claim_next} are not among them.
 */
class CallSetTest {

    /** The verbs of the target cut, by their REST names. */
    static final List<String> VERBS = List.of(
        "create", "read", "update", "query", "relate", "unrelate", "validate",
        "tag_attach", "tag_detach", "close", "advance", "plan", "unplan");

    /** The same, as the assistant surface names them: {@code worklist_<verb>}. */
    static final List<String> TOOLS = VERBS.stream().map(verb -> "worklist_" + verb).toList();

    @Test
    void the_tool_list_carries_exactly_the_calls_of_the_target_cut() {
        assertThat(offences(TOOLS, McpTools.names()))
            .as("tools/list names the calls of the target cut, each once, and nothing else")
            .isEmpty();
    }

    @Test
    void the_declaration_carries_exactly_the_calls_of_the_target_cut() {
        List<Map<String, Object>> calls = calls(DeclarationDocument.content(
            bundle(ToolDescriptions.class), bundle(ArgumentDescriptions.class),
            bundle(ValueDescriptions.class)));
        assertThat(offences(TOOLS, calls.stream().map(c -> String.valueOf(c.get("call")))
                .toList()))
            .as("the declaration's calls are the calls of the target cut")
            .isEmpty();
        assertThat(offences(VERBS, calls.stream().map(c -> String.valueOf(c.get("rest_name")))
                .toList()))
            .as("and their REST names the verbs")
            .isEmpty();
    }

    @Test
    void the_rest_routes_carry_exactly_the_verbs_of_the_target_cut() {
        Set<String> carried = new LinkedHashSet<>();
        for (CustomMethod.Depth depth : CustomMethod.Depth.values()) {
            carried.addAll(CustomMethod.accepts(depth));
        }
        assertThat(offences(VERBS, List.copyOf(carried)))
            .as("the REST surface carries the verbs of the target cut at its depths, and no "
                + "other verb")
            .isEmpty();
    }

    /** The red state, observed on every build. */
    @Test
    void the_check_reports_each_offence_it_exists_for() {
        assertThat(offences(List.of("worklist_read", "worklist_update"),
                List.of("worklist_read", "worklist_claim", "worklist_read")))
            .as("RED STATE, observed: a call beyond the cut, a call named twice and a call "
                + "the cut has and the list lacks are each reported")
            .containsExactly("beyond the target cut: worklist_claim",
                "named twice: worklist_read",
                "lacking: worklist_update");
    }

    /** What a list of call names has beyond the expected ones, twice, or lacks. */
    static List<String> offences(Collection<String> expected, List<String> actual) {
        List<String> offences = new ArrayList<>();
        new TreeSet<>(actual).stream().filter(call -> !expected.contains(call))
            .forEach(call -> offences.add("beyond the target cut: " + call));
        Set<String> once = new TreeSet<>();
        actual.stream().filter(call -> !once.add(call)).distinct()
            .forEach(call -> offences.add("named twice: " + call));
        new TreeSet<>(expected).stream().filter(call -> !actual.contains(call))
            .forEach(call -> offences.add("lacking: " + call));
        return offences;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> calls(Map<String, Object> document) {
        return (List<Map<String, Object>>) document.get("calls");
    }

    /** A bundle that answers a text for every method; the words are not the subject. */
    private static <T> T bundle(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, args) -> "a text"));
    }
}
