/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.contract;

import ai.kumbuka.worklist.domain.ItemService;
import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.domain.VocabularyRegistry;
import ai.kumbuka.worklist.domain.WorkstreamService;
import ai.kumbuka.worklist.surface.Mcp;
import ai.kumbuka.worklist.surface.SurfaceFixture;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every answer about an object, and every page, has the shape the contract
 * fixes, for the members this service carries today (C6.1, C6.2, C6.4, C9.2),
 * on both surfaces.
 *
 * <ul>
 *   <li>An answer about one object carries {@code address}, {@code fields}
 *       and {@code conflict_token} at the top. Until the breaking step the
 *       token stays inside {@code fields} as well, the same value.</li>
 *   <li>Every listing entry carries the same.</li>
 *   <li>The address an answer carries is complete, and {@code read} takes it
 *       unchanged.</li>
 *   <li>A page is exactly {@code {objects, next_cursor}}.</li>
 *   <li>Every refusal that falls once the route stands carries
 *       {@code attempted}, in the names of the surface the caller came
 *       through: the tool name on MCP, the verb on REST, and the tool name on
 *       REST under {@code Kumbuka-Surface: assistant}. {@code NOT_FOUND}
 *       carries no {@code data} at all, and a refusal before the route none.</li>
 * </ul>
 *
 * <p>{@code next} and {@code waiting_for} are not asserted: this service does
 * not answer them yet, and the clause that does belongs to the step that
 * builds them.
 *
 * <p>The member names are written here from the contract; the answers are
 * read off the wire.
 *
 * <p>RED STATE, observed 2026-10-29 against {@code 0fb4862} with this class
 * copied in: {@link #an_answer_about_one_object_carries_its_token_at_the_top_on_both_surfaces},
 * {@link #every_listing_entry_carries_the_same_members} and
 * {@link #a_refusal_after_the_route_names_the_call_in_the_caller_s_surface}
 * turned red, the first two for the absent top-level {@code conflict_token},
 * the third for the absent {@code data.attempted}; the page shape and the
 * not-found case stayed green, as they were already met.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class AnswerShapeConformanceIT {

    private static final String SCOPE = "shape-probe";
    private static final UUID SCOPE_ID = UUID.fromString("5a1d3c7e-0b2f-4c6e-9a8d-1f2e3d4c5b6a");

    /** The members of an answer about one object that this service carries (C6.1). */
    private static final List<String> OBJECT = List.of("address", "fields", "conflict_token");

    private static boolean staged;

    @Inject TestIdentityAssociation identity;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject WorkstreamService workstreams;
    @Inject ItemService items;

    @BeforeEach
    void stageOnce() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);
        if (!staged) {
            SurfaceFixture.publishScope(SCOPE, SCOPE_ID, "project", false);
            selectors.open(SCOPE_ID, Selector.ITEM);
            vocabulary.declareStatus(SCOPE_ID, "open", 1, true, false, false, false);
            workstreams.requireDefault(SCOPE_ID);
            for (int i = 0; i < 3; i++) {
                items.create(SCOPE_ID, Map.of("title", "shaped " + i, "status", "open"));
            }
            staged = true;
        }
    }

    @Test
    void an_answer_about_one_object_carries_its_token_at_the_top_on_both_surfaces() {
        Map<String, Object> created = Mcp.answer(Mcp.call("worklist_create", Map.of(
            "scope", SCOPE, "selector", Selector.ITEM,
            "fields", Map.of("title", "an object", "status", "open"))));
        String address = String.valueOf(created.get("address"));
        Map<String, Object> overMcp = Mcp.answer(Mcp.call("worklist_read",
            Map.of("address", address)));
        Response overRest = given().get(rest(address));
        Map<String, Object> rested = overRest.jsonPath().getMap("$");

        SoftAssertions soft = new SoftAssertions();
        for (Map<String, Object> answer : List.of(created, overMcp, rested)) {
            assertObject(soft, answer);
            soft.assertThat(answer.get("address")).as("one address on both surfaces")
                .isEqualTo(address);
        }
        soft.assertThat(overRest.header("ETag")).as("the ETag is the token at the top (C10.3)")
            .isEqualTo("\"" + rested.get("conflict_token") + "\"");
        soft.assertAll();
    }

    @Test
    void every_listing_entry_carries_the_same_members() {
        Map<String, Object> first = Mcp.answer(Mcp.call("worklist_query", Map.of(
            "scope", SCOPE, "selector", Selector.ITEM, "page_size", 2)));
        Map<String, Object> overRest = given().queryParam("page_size", 2)
            .get("/api/" + SCOPE + "/" + Selector.ITEM).jsonPath().getMap("$");

        SoftAssertions soft = new SoftAssertions();
        for (Map<String, Object> page : List.of(first, overRest)) {
            soft.assertThat(page).as("C9.2").containsOnlyKeys("objects", "next_cursor");
            objects(page).forEach(entry -> assertObject(soft, entry));
        }
        soft.assertAll();
    }

    @Test
    void a_page_without_a_rest_carries_no_cursor() {
        Map<String, Object> all = given().queryParam("page_size", 200)
            .get("/api/" + SCOPE + "/" + Selector.ITEM).jsonPath().getMap("$");
        assertThat(all).as("the last page carries objects alone (C9.2)")
            .containsOnlyKeys("objects");
    }

    @Test
    void every_address_an_answer_carries_is_taken_by_read_unchanged() {
        Map<String, Object> page = Mcp.answer(Mcp.call("worklist_query", Map.of(
            "scope", SCOPE, "selector", Selector.ITEM)));
        SoftAssertions soft = new SoftAssertions();
        for (Map<String, Object> entry : objects(page)) {
            String address = String.valueOf(entry.get("address"));
            soft.assertThat(address).as("complete (C6.4)").startsWith("worklist://" + SCOPE + "/");
            soft.assertThat(Mcp.call("worklist_read", Map.of("address", address))
                    .jsonPath().getBoolean("result.isError"))
                .as("read takes %s unchanged", address).isFalse();
        }
        soft.assertAll();
    }

    @Test
    void a_refusal_after_the_route_names_the_call_in_the_caller_s_surface() {
        String address = String.valueOf(objects(Mcp.answer(Mcp.call("worklist_query", Map.of(
            "scope", SCOPE, "selector", Selector.ITEM)))).get(0).get("address"));
        Map<String, Object> change = Map.of("title", "changed");
        SoftAssertions soft = new SoftAssertions();

        // A domain refusal (a stale token) and a surface refusal (no token).
        Map<String, Object> staleOverMcp = Mcp.refusal(Mcp.call("worklist_update", Map.of(
            "address", address, "conflict_token", "stale", "fields", change)));
        Map<String, Object> missingOverMcp = Mcp.refusal(Mcp.call("worklist_update", Map.of(
            "address", address, "fields", change)));
        soft.assertThat(attempted(staleOverMcp)).as("a domain refusal over MCP")
            .isEqualTo("worklist_update");
        soft.assertThat(attempted(missingOverMcp)).as("a surface refusal over MCP")
            .isEqualTo("worklist_update");
        soft.assertThat(attempted(Mcp.refusal(Mcp.call("update", Map.of(
                "address", address, "fields", change)))))
            .as("the bare name the tool was called by still answers the declared tool name")
            .isEqualTo("worklist_update");

        Response staleOverRest = given().contentType(ContentType.JSON)
            .header("If-Match", "\"stale\"").body(change).patch(rest(address));
        Response missingOverRest = given().contentType(ContentType.JSON).body(change)
            .patch(rest(address));
        soft.assertThat(staleOverRest.jsonPath().getString("data.attempted"))
            .as("a domain refusal over REST: %s", staleOverRest.asString()).isEqualTo("update");
        soft.assertThat(missingOverRest.jsonPath().getString("data.attempted"))
            .as("a surface refusal over REST: %s", missingOverRest.asString()).isEqualTo("update");

        Response asAssistant = given().contentType(ContentType.JSON)
            .header("Kumbuka-Surface", "assistant").body(change).patch(rest(address));
        soft.assertThat(asAssistant.jsonPath().getString("data.attempted"))
            .as("REST under the assistant surface names the tool (C6.2)")
            .isEqualTo("worklist_update");

        Response query = given().queryParam("page_size", 0)
            .get("/api/" + SCOPE + "/" + Selector.ITEM);
        soft.assertThat(query.jsonPath().getString("data.attempted"))
            .as("a refusal of the paging: %s", query.asString()).isEqualTo("query");
        soft.assertAll();
    }

    @Test
    void not_found_and_a_refusal_before_the_route_carry_no_attempted() {
        SoftAssertions soft = new SoftAssertions();
        Map<String, Object> absent = Mcp.refusal(Mcp.call("worklist_read", Map.of(
            "address", "worklist://" + SCOPE + "/item/999999")));
        soft.assertThat(absent).as("NOT_FOUND carries no data (C8.4)").doesNotContainKey("data");

        Response header = given().header("Kumbuka-Surface", "console")
            .get("/api/" + SCOPE + "/" + Selector.ITEM + "/1");
        soft.assertThat(header.jsonPath().getString("reason")).isEqualTo("HEADER_INVALID");
        soft.assertThat(header.jsonPath().getMap("$"))
            .as("HEADER_INVALID falls before the route and carries no data")
            .doesNotContainKey("data");

        Response noVerb = given().contentType(ContentType.JSON)
            .post("/api/" + SCOPE + "/" + Selector.ITEM + "/1:nothing");
        soft.assertThat(noVerb.jsonPath().getString("data.attempted"))
            .as("a colon that names no verb never became a call: %s", noVerb.asString())
            .isNull();
        soft.assertAll();
    }

    // ======================================================================

    /** The members of an answer about one object (C6.1), and the token in both places. */
    @SuppressWarnings("unchecked")
    private static void assertObject(SoftAssertions soft, Map<String, Object> answer) {
        soft.assertThat(answer).as("C6.1: %s", answer).containsKeys(OBJECT.toArray(String[]::new));
        soft.assertThat(answer.get("fields")).isInstanceOf(Map.class);
        Object inFields = answer.get("fields") instanceof Map<?, ?> fields
            ? ((Map<String, Object>) fields).get("conflict_token") : null;
        soft.assertThat(answer.get("conflict_token"))
            .as("the token at the top is the one still in fields").isNotNull()
            .isEqualTo(inFields);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("objects");
    }

    @SuppressWarnings("unchecked")
    private static Object attempted(Map<String, Object> refusal) {
        Object data = refusal.get("data");
        return data instanceof Map<?, ?> map ? ((Map<String, Object>) map).get("attempted") : null;
    }

    /** The REST path of a complete address. */
    private static String rest(String address) {
        return "/api/" + address.substring("worklist://".length());
    }
}
