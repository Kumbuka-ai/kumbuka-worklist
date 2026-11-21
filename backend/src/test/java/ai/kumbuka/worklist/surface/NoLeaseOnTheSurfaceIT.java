/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.ScopeSettingService;
import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.domain.VocabularyRegistry;
import ai.kumbuka.worklist.domain.WorklistException;
import ai.kumbuka.worklist.domain.WorkstreamService;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lease is gone from the surface: no answer carries a holder, a receipt
 * or a member derived from a lease, no tool or declared call takes or answers
 * one, and no refusal code of a lease is declared.
 *
 * <p>The lease's members are {@link AnswerChecks#LEASE_MEMBERS}; the five
 * calls that left the surface and the lease's four codes are written here.
 * The store no longer holds a lease either: V31 dropped its table, and
 * {@code SchemaConstraintIT} and {@code ServiceRolePrivilegeIT} assert its
 * absence from the catalog. What is probed here is the surface: that nothing
 * of a lease reaches a caller.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class NoLeaseOnTheSurfaceIT {

    private static final String SCOPE = "no-lease";
    private static final UUID SCOPE_ID = UUID.fromString("10a5e000-0000-4000-8000-000000000001");
    private static final String OPEN = "nl-open";
    private static final String DONE = "nl-done";
    private static final int GENEROUS = 1_000;

    /** The calls that left the surface with the target cut. */
    private static final List<String> RETIRED = List.of("accept", "withdraw", "claim",
        "release", "claim_next");

    /** The refusal codes of the lease and the draw. */
    private static final List<String> LEASE_CODES = List.of("CLAIM_HELD", "CLAIM_ABSENT",
        "CLAIM_RECEIPT_UNKNOWN", "DRAW_EMPTY");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static boolean staged;

    @Inject TestIdentityAssociation identity;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject WorkstreamService workstreams;
    @Inject ScopeSettingService settings;

    @BeforeEach
    void stageOnce() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);
        if (!staged) {
            SurfaceFixture.publishScope(SCOPE, SCOPE_ID, "project", false);
            for (String view : Selector.VIEWS) {
                selectors.open(SCOPE_ID, view);
            }
            vocabulary.declareStatus(SCOPE_ID, OPEN, 1, true, false, false, false);
            vocabulary.declareStatus(SCOPE_ID, DONE, 2, false, false, true, true);
            workstreams.requireDefault(SCOPE_ID);
            try {
                settings.create(SCOPE_ID, Map.of(
                    "max_planned_iterations", GENEROUS, "warn_planned_iterations", GENEROUS,
                    "max_memberships_per_iteration", GENEROUS,
                    "warn_memberships_per_iteration", GENEROUS));
            } catch (WorklistException alreadyThere) {
                assertThat(alreadyThere.reason())
                    .isEqualTo(WorklistException.Reason.SETTING_PRESENT);
            }
            staged = true;
        }
    }

    @Test
    void no_tool_and_no_declared_call_takes_or_answers_a_lease() {
        JsonPath tools = Mcp.rpc("tools/list", Map.of()).jsonPath();
        List<String> names = tools.getList("result.tools.name", String.class);
        assertThat(names).as("tools/list names none of the calls that left the surface")
            .doesNotContainAnyElementsOf(RETIRED.stream().map(v -> "worklist_" + v).toList());
        List<String> properties = new ArrayList<>();
        for (Map<String, Object> tool : tools.<Map<String, Object>>getList("result.tools")) {
            Object schema = tool.get("inputSchema");
            if (schema instanceof Map<?, ?> map && map.get("properties") instanceof Map<?, ?> p) {
                p.keySet().forEach(name -> properties.add(tool.get("name") + "." + name));
            }
        }
        assertThat(properties).as("no tool takes a receipt or a lease's length")
            .noneMatch(p -> p.endsWith(".receipt") || p.endsWith(".duration_seconds"));

        JsonPath declaration = given().get("/api/declaration").jsonPath();
        assertThat(declaration.getList("calls.call", String.class))
            .as("the declaration names none of them")
            .doesNotContainAnyElementsOf(RETIRED.stream().map(v -> "worklist_" + v).toList());
        assertThat(declaration.getList("calls.arguments.name").toString())
            .as("and no declared argument is a receipt or a lease's length")
            .doesNotContain("receipt", "duration_seconds");
        assertThat(declaration.getList("reasons.reason", String.class))
            .as("and no refusal code of the lease is declared")
            .doesNotContainAnyElementsOf(LEASE_CODES);
    }

    @Test
    void no_answer_carries_a_holder_or_a_receipt() throws Exception {
        Map<String, String> answers = new LinkedHashMap<>();
        String path = "/api/" + SCOPE + "/";

        String created = given().contentType(ContentType.JSON)
            .body(Map.of("title", "held by nobody", "status", OPEN))
            .post(path + Selector.ITEM).asString();
        answers.put("create over REST", created);
        long number = JSON.readTree(created).path("fields").path("number").asLong();
        String item = "worklist://" + SCOPE + "/" + Selector.ITEM + "/" + number;
        answers.put("read over REST", given().get(path + Selector.ITEM + "/" + number)
            .asString());
        answers.put("query over REST", given().get(path + Selector.ITEM).asString());
        answers.put("read over MCP", Mcp.call("worklist_read", Map.of("address", item))
            .asString());
        answers.put("query over MCP", Mcp.call("worklist_query", Map.of("scope", SCOPE,
            "selector", Selector.ITEM)).asString());
        answers.put("update over MCP", Mcp.call("worklist_update", Map.of("address", item,
            "conflict_token", token(item), "fields", Map.of("status", DONE))).asString());
        answers.put("a stale token over MCP", Mcp.call("worklist_update", Map.of(
            "address", item, "conflict_token", "stale", "fields", Map.of("title", "x")))
            .asString());
        answers.put("tag_detach over MCP", Mcp.call("worklist_tag_detach", Map.of(
            "address", item, "tag", "none-attached")).asString());
        answers.put("validate over MCP", Mcp.call("worklist_validate", Map.of("scope", SCOPE,
            "selector", Selector.ITEM)).asString());
        for (String retired : RETIRED) {
            answers.put(retired + " over MCP", Mcp.rpc("tools/call", Map.of(
                "name", "worklist_" + retired, "arguments", Map.of("address", item)))
                .asString());
        }

        List<String> offences = new ArrayList<>();
        for (Map.Entry<String, String> answer : answers.entrySet()) {
            offences.addAll(AnswerChecks.leaseOffences(answer.getKey(),
                JSON.readValue(answer.getValue(), Object.class)));
        }
        assertThat(offences)
            .as("no answer, refusal or listing carries a member of a lease")
            .isEmpty();
    }

    private static String token(String address) {
        return String.valueOf(Mcp.answer(Mcp.call("worklist_read",
            Map.of("address", address))).get("conflict_token"));
    }
}
