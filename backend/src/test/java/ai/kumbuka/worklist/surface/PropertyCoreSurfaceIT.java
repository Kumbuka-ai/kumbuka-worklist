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
 * The property core and the duplicate relation at the surface, over REST and
 * MCP (REA-0011 S2, D18).
 *
 * <p>What the domain cases in {@code PropertyCoreIT} cannot show: that the
 * creator is the subject the token carries and nothing the call says, that a
 * create naming a creator is refused at the surface, and that a
 * {@code duplicate_of} refused by state names the item's state and the open
 * calls: on a closed item its phase and {@code read} alone, on an open one
 * {@code relate} among them.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class PropertyCoreSurfaceIT {

    private static final String SCOPE = "property-core";
    private static final UUID SCOPE_ID = UUID.fromString("00000000-0000-0000-0000-0000000002b1");

    private static final String OPEN = "core-open";
    private static final String DONE = "core-done";
    private static final String OBSOLETE = "core-obsolete";

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
            vocabulary.declareStatus(SCOPE_ID, DONE, 2, false, false, true, true);
            vocabulary.declareStatus(SCOPE_ID, OBSOLETE, 3, false, false, true, false);
            vocabulary.declareRelationType(SCOPE_ID, "duplicate_of", false, 1, OBSOLETE);
        }
    }

    @Test
    void k1_the_creator_is_the_subject_of_the_token_on_both_surfaces() {
        Response overRest = given().contentType(ContentType.JSON)
            .body(Map.of("title", "created over REST", "status", OPEN))
            .when().post("/api/" + SCOPE + "/" + Selector.ITEM);
        overRest.then().statusCode(201);
        assertThat(overRest.jsonPath().getString("fields.creator"))
            .as("the creator is the member the token names")
            .isEqualTo(SurfaceFixture.MEMBER);

        Map<String, Object> overMcp = Mcp.answer(Mcp.call("worklist_create", Map.of(
            "scope", SCOPE, "selector", Selector.ITEM,
            "fields", Map.of("title", "created over MCP", "status", OPEN))));
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) overMcp.get("fields");
        assertThat(fields.get("creator")).isEqualTo(SurfaceFixture.MEMBER);
        assertThat(fields.get("contributor")).isEqualTo(List.of());
        assertThat(fields)
            .as("a property without a value is absent from the answer")
            .doesNotContainKeys("parent", "priority", "severity", "close_date");
    }

    /**
     * The predicates on the wire (REA-0011 S3): an object of the item's
     * fields on both surfaces, with {@code successful} only where
     * {@code closed} holds, and no member at all for {@code reviewed},
     * {@code approved} and {@code verified} in a scope that does not use them.
     */
    @Test
    void s3_the_predicates_travel_on_both_surfaces_and_an_unused_one_is_absent() {
        long item = createItem("carries its predicates");

        Map<String, Object> overRest = read(item).jsonPath().getMap("fields.predicates");
        assertThat(overRest)
            .as("an open item: closed, actionable and in_progress, and nothing more")
            .isEqualTo(Map.of("closed", false, "actionable", true, "in_progress", false));

        Map<String, Object> overMcp = Mcp.answer(Mcp.call("worklist_read", Map.of(
            "address", address(item))));
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) overMcp.get("fields");
        assertThat(fields.get("predicates"))
            .as("the same object over MCP")
            .isEqualTo(overRest);

        long duplicate = createItem("closed as a duplicate");
        relate(duplicate, item).then().statusCode(200);
        assertThat(read(duplicate).jsonPath().getMap("fields.predicates"))
            .as("a closed item answers successful too")
            .isEqualTo(Map.of("closed", true, "successful", false, "actionable", false,
                "in_progress", false));
    }

    @Test
    void k1_a_create_naming_its_creator_is_refused_at_the_surface() {
        Response refused = given().contentType(ContentType.JSON)
            .body(Map.of("title", "claims a creator", "status", OPEN,
                "creator", "someone-else"))
            .when().post("/api/" + SCOPE + "/" + Selector.ITEM);

        assertThat(refused.jsonPath().getString("reason")).isEqualTo("FIELD_NOT_SETTABLE");
        assertThat(refused.jsonPath().getList("data.offenders", String.class))
            .containsExactly("fields.creator");
    }

    @Test
    void k3_duplicate_of_closes_over_rest_and_is_refused_by_state_once_closed() {
        long duplicate = createItem("the duplicate");
        long original = createItem("the original");

        Response closed = relate(duplicate, original);
        closed.then().statusCode(200);
        assertThat(closed.jsonPath().getString("fields.status")).isEqualTo(OBSOLETE);
        assertThat(closed.jsonPath().getString("fields.close_date")).isNotNull();

        long another = createItem("a second original");
        Response refused = relate(duplicate, another);
        refused.then().statusCode(409);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("TRANSITION_REFUSED");
        assertThat(refused.jsonPath().getString("data.state"))
            .as("the refusal names the item's state: its phase, closed (REA-0011 D11)")
            .isEqualTo("closed");
        assertThat(refused.jsonPath().getList("data.next.call", String.class))
            .as("and the calls open on it: a closed item opens read alone (REA-0011 D6)")
            .containsExactly("read " + address(duplicate));
    }

    @Test
    void d18_an_update_adding_duplicate_of_over_mcp_is_refused_and_names_relate() {
        long duplicate = createItem("added by update");
        long original = createItem("the original");
        String token = read(duplicate).jsonPath().getString("fields.conflict_token");

        Response refused = Mcp.call("worklist_update", Map.of(
            "address", address(duplicate), "conflict_token", token,
            "fields", Map.of("relations", List.of(Map.of(
                "type", "duplicate_of", "item", address(original))))));

        assertThat(Mcp.reason(refused)).isEqualTo("TRANSITION_REFUSED");
        assertThat(refused.jsonPath().getList(
                "result.structuredContent.data.next.call", String.class))
            .as("the open calls name relate, which sets duplicate_of")
            .anySatisfy(call -> assertThat(call).startsWith("worklist_relate"));
        assertThat(read(duplicate).jsonPath().getString("fields.conflict_token"))
            .as("nothing was written")
            .isEqualTo(token);
    }

    // =======================================================================
    // Fixtures
    // =======================================================================

    /** An open item: created over REST and submitted in the same act (REA-0011 D12). */
    private static long createItem(String title) {
        Response created = given().contentType(ContentType.JSON)
            .body(Map.of("title", title, "status", OPEN))
            .when().post(Submitted.restCreate("/api/" + SCOPE + "/" + Selector.ITEM));
        created.then().statusCode(201);
        return created.jsonPath().getLong("fields.number");
    }

    private static Response read(long item) {
        return given().when().get("/api/" + SCOPE + "/" + Selector.ITEM + "/" + item);
    }

    private static Response relate(long from, long to) {
        String token = read(from).jsonPath().getString("fields.conflict_token");
        return given().contentType(ContentType.JSON).header("If-Match", "\"" + token + "\"")
            .body(Map.of("to_item", address(to), "type", "duplicate_of"))
            .when().post("/api/" + SCOPE + "/" + Selector.ITEM + "/" + from + ":relate");
    }

    private static String address(long item) {
        return "worklist://" + SCOPE + "/" + Selector.ITEM + "/" + item;
    }
}
