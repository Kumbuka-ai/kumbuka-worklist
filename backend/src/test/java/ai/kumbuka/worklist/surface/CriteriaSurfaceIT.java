/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.domain.VocabularyRegistry;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The acceptance criteria at the surface, over REST and MCP (REA-0011 S12,
 * D14).
 *
 * <p>What the domain cases in {@code AcceptanceCriteriaIT} cannot show: that
 * the entries travel as JSON in the form a read answers and come back in it,
 * so the criteria of a read answer sent back over either surface change nothing, and that a
 * refusal names the field on the wire. The addresses expected here are written
 * from D14 against the scope's published name.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class CriteriaSurfaceIT {

    private static final String SCOPE = "criteria-surface";
    private static final UUID SCOPE_ID = UUID.fromString("00000000-0000-0000-0000-0000000005c1");

    private static final String OPEN = "criteria-open";

    @Inject TestIdentityAssociation identity;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;

    @BeforeAll
    static void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.publishEmptyScope(SCOPE, SCOPE_ID);
    }

    @BeforeEach
    void openTheScope() {
        SurfaceFixture.asMember(identity);
        selectors.open(SCOPE_ID, Selector.ITEM);
        if (vocabulary.statusByName(SCOPE_ID, OPEN).isEmpty()) {
            vocabulary.declareStatus(SCOPE_ID, OPEN, 1, true, false, false, false);
        }
    }

    @Test
    void k1_criteria_created_over_rest_read_back_over_mcp_under_their_addresses() {
        long item = createItem(List.of(Map.of("text", "first"), Map.of("text", "second")));

        Map<String, Object> overMcp = Mcp.answer(Mcp.call("worklist_read", Map.of(
            "address", address(item))));
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) overMcp.get("fields");
        assertThat(fields.get("criteria"))
            .as("each criterion under worklist://<scope>/item/<n>/criterion/<k>, with its text")
            .isEqualTo(List.of(
                Map.of("address", address(item) + "/criterion/1", "text", "first"),
                Map.of("address", address(item) + "/criterion/2", "text", "second")));
    }

    @Test
    void k5_the_read_answer_sent_back_over_mcp_and_over_rest_changes_nothing() {
        long item = createItem(List.of(Map.of("text", "echoed")));
        Response read = read(item);
        String token = read.jsonPath().getString("fields.conflict_token");
        Map<String, Object> fields = read.jsonPath().getMap("fields");

        Map<String, Object> overMcp = Mcp.answer(Mcp.call("worklist_update", Map.of(
            "address", address(item), "conflict_token", token,
            "fields", Map.of("criteria", fields.get("criteria")))));
        assertThat(overMcp.get("conflict_token"))
            .as("the criteria of the read answer sent back as they came over MCP")
            .isEqualTo(token);

        Response overRest = given().contentType(ContentType.JSON)
            .header("If-Match", "\"" + token + "\"")
            .body(Map.of("criteria", fields.get("criteria")))
            .when().patch("/api/" + SCOPE + "/" + Selector.ITEM + "/" + item);
        overRest.then().statusCode(200);
        assertThat(overRest.jsonPath().getString("fields.conflict_token"))
            .as("and the criteria alone sent back over REST")
            .isEqualTo(token);
    }

    @Test
    void k4_an_identifier_the_item_never_issued_is_refused_over_rest_naming_the_field() {
        long item = createItem(List.of(Map.of("text", "the only one")));
        String token = read(item).jsonPath().getString("fields.conflict_token");

        Response refused = given().contentType(ContentType.JSON)
            .header("If-Match", "\"" + token + "\"")
            .body(Map.of("criteria", List.of(Map.of(
                "address", address(item) + "/criterion/7", "text", "never issued"))))
            .when().patch("/api/" + SCOPE + "/" + Selector.ITEM + "/" + item);

        refused.then().statusCode(422);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("INVALID_VALUE");
        assertThat(refused.jsonPath().getList("data.offenders", String.class))
            .containsExactly("fields.criteria");
        assertThat(read(item).jsonPath().getString("fields.conflict_token"))
            .as("nothing was written")
            .isEqualTo(token);
    }

    // =======================================================================
    // Fixtures
    // =======================================================================

    private static long createItem(List<Map<String, Object>> criteria) {
        Response created = given().contentType(ContentType.JSON)
            .body(Map.of("title", "carries criteria", "status", OPEN, "criteria", criteria))
            .when().post("/api/" + SCOPE + "/" + Selector.ITEM);
        created.then().statusCode(201);
        return created.jsonPath().getLong("fields.number");
    }

    private static Response read(long item) {
        return given().when().get("/api/" + SCOPE + "/" + Selector.ITEM + "/" + item);
    }

    private static String address(long item) {
        return "worklist://" + SCOPE + "/" + Selector.ITEM + "/" + item;
    }
}
