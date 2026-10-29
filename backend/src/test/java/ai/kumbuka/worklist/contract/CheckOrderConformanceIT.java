/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.contract;

import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.surface.Mcp;
import ai.kumbuka.worklist.surface.SurfaceFixture;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The check order at the entrance of the surface: grammar and argument form,
 * then the scope's visibility, then the vocabulary, then the object (C1.3,
 * DEC-0042), and before all of it the routes the framework does not carry
 * (C3.3, C8.3) and the names no scope may carry (C3.2).
 *
 * <p>A scope the caller cannot see answers {@code NOT_FOUND} whatever else that
 * depends on the scope is wrong with the call: a view the scope does not
 * declare, a stale conflict token, a missing one, a receipt that is not the
 * holder's. A fault of grammar or of form -- an address out of the grammar, an
 * argument the call does not declare -- says nothing about any scope and is
 * answered first, the same whether the scope is there or not.
 *
 * <p>RED STATE, observed 2026-10-29 against the tree before this change
 * ({@code 34e4390}, with this class copied in): the framework's 404 and 405 came
 * without the envelope, a create below {@code /api/declaration} answered 404
 * where {@code ADDRESS_MALFORMED} 400 belongs, and an argument the call does
 * not declare on an invisible scope was answered {@code NOT_FOUND} 404; every
 * case turned red, the two MCP cases also for the tool names that tree did not
 * yet take.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class CheckOrderConformanceIT {

    private static final String INVISIBLE = NotFoundIdentityIT.INVISIBLE_SCOPE;
    private static final String ADDRESS = "worklist://" + INVISIBLE + "/" + Selector.ITEM + "/1";
    private static final UUID SCOPE_ID = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);

    @Inject TestIdentityAssociation identity;
    @Inject SelectorRegistry selectors;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        NotFoundIdentityIT.stageInvisibleScope();
        SurfaceFixture.asMember(identity);
        selectors.open(SCOPE_ID, Selector.ITEM);
    }

    @Test
    void an_invisible_scope_answers_not_found_whatever_else_the_call_gets_wrong() {
        Map<String, Map<String, Object>> calls = new LinkedHashMap<>();
        calls.put("worklist_create", Map.of("scope", INVISIBLE, "selector", Selector.MILESTONE,
            "fields", Map.of("title", "t")));
        calls.put("worklist_update", Map.of("address", ADDRESS, "conflict_token", "stale",
            "fields", Map.of("title", "t")));
        calls.put("worklist_accept", Map.of("address", ADDRESS));
        calls.put("worklist_release", Map.of("address", ADDRESS, "receipt", "not-the-holder"));
        calls.put("worklist_send", Map.of("address", ADDRESS));
        calls.forEach((tool, arguments) -> {
            assertThat(Mcp.reason(Mcp.call(tool, arguments))).as("%s over MCP", tool)
                .isEqualTo("NOT_FOUND");
        });

        Response rest = given().header("If-Match", "stale").contentType(ContentType.JSON)
            .body(Map.of("title", "t")).patch("/api/" + INVISIBLE + "/item/1");
        assertThat(rest.statusCode()).as("a stale token over REST").isEqualTo(404);
        assertThat(rest.jsonPath().getString("reason")).isEqualTo("NOT_FOUND");
    }

    @Test
    void grammar_is_decided_before_the_scope_and_says_nothing_about_it() {
        Response invisible = Mcp.call("worklist_read",
            Map.of("address", "worklist://" + INVISIBLE + "/" + Selector.ITEM + "/0"));
        assertThat(Mcp.reason(invisible)).isEqualTo("ADDRESS_MALFORMED");

        Response visible = Mcp.call("worklist_read", Map.of("address",
            "worklist://" + SurfaceFixture.SCOPE + "/" + Selector.ITEM + "/0"));
        assertThat(Mcp.refusal(invisible))
            .as("the same refusal whether the scope is visible or not")
            .isEqualTo(Mcp.refusal(visible));
    }

    /**
     * The form of a body is checked before any scope is resolved (C1.2, C1.3):
     * an argument the call does not declare, on a scope the caller cannot see,
     * is {@code ARGUMENT_UNKNOWN} 400 with {@code data.accepts}, not {@code
     * NOT_FOUND}. The refusal says nothing about the scope; it is the same
     * whether the scope is there or not.
     */
    @Test
    void a_form_fault_on_an_invisible_scope_is_decided_before_the_scope() {
        Response overRest = given().contentType(ContentType.JSON).body(Map.of("bogus", "x"))
            .post("/api/" + INVISIBLE + "/" + Selector.ITEM + "/1:claim");
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(400);
        assertThat(overRest.jsonPath().getString("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(overRest.jsonPath().getList("data.accepts", String.class))
            .containsExactly("duration_seconds");

        Response visible = given().contentType(ContentType.JSON).body(Map.of("bogus", "x"))
            .post(SurfaceFixture.item(Selector.ITEM, 1) + ":claim");
        assertThat(overRest.asString()).as("the same refusal whether the scope is there or not")
            .isEqualTo(visible.asString());

        Response notJson = given().contentType(ContentType.JSON).body("[not json")
            .post("/api/" + INVISIBLE + "/" + Selector.ITEM + "/1:claim");
        assertThat(notJson.jsonPath().getString("reason")).as("a body that is no JSON object")
            .isEqualTo("PAYLOAD_MALFORMED");

        Response overMcp = Mcp.call("worklist_create", Map.of("scope", INVISIBLE,
            "selector", Selector.ITEM, "bogus", "x", "fields", Map.of("title", "t")));
        assertThat(Mcp.reason(overMcp)).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(Mcp.refusal(overMcp)).containsKey("data");
        assertThat(Mcp.reason(Mcp.call("worklist_claim", Map.of("address", ADDRESS,
                "duration_seconds", "an hour"))))
            .as("a value of the wrong type").isEqualTo("ARGUMENT_INVALID");
    }

    @Test
    void a_scope_named_like_a_literal_path_is_refused_before_the_route() {
        for (String reserved : List.of("declaration", "whoami")) {
            String collection = "/api/" + reserved + "/" + Selector.ITEM;
            Map<String, Response> refused = new LinkedHashMap<>();
            refused.put("create", given().contentType(ContentType.JSON)
                .body(Map.of("title", "t")).post(collection));
            refused.put("read", given().get(collection + "/1"));
            refused.put("a verb not carried", given().post(collection + "/1:invented"));
            refused.put("a method not carried", given().put(collection + "/1"));
            refused.put("a trailing slash", given().get("/api/" + reserved + "/"));
            refused.forEach((what, answer) -> {
                assertThat(answer.statusCode()).as("%s under %s: %s", what, reserved,
                    answer.asString()).isEqualTo(400);
                assertThat(answer.jsonPath().getString("reason")).as("%s under %s", what, reserved)
                    .isEqualTo("ADDRESS_MALFORMED");
                assertThat(answer.jsonPath().getMap("$")).as("%s under %s: no data", what,
                    reserved).doesNotContainKey("data");
            });
            Response literal = given().get("/api/" + reserved);
            assertThat(literal.statusCode()).as("/api/%s itself: %s", reserved, literal.asString())
                .isEqualTo(200);
            assertThat(Mcp.reason(Mcp.call("worklist_read", Map.of("address",
                    "worklist://" + reserved + "/" + Selector.ITEM + "/1"))))
                .as("and the address grammar refuses it on MCP").isEqualTo("ADDRESS_MALFORMED");
        }
    }

    /**
     * A route the service does not carry answers in the envelope (C3.3, C8.3):
     * the framework's 404 as {@code NOT_FOUND} without {@code data}, its 405 as
     * {@code VERB_NOT_CARRIED} with {@code Allow} and {@code data.accepts}. The
     * security layer's 401 is handed back as it is: the authentication
     * exception of DEC-0042.
     */
    @Test
    void a_route_the_framework_does_not_carry_answers_in_the_envelope() {
        Response nothing = given().get("/api/nothing");
        assertThat(nothing.statusCode()).as(nothing.asString()).isEqualTo(404);
        assertThat(nothing.jsonPath().getString("reason")).isEqualTo("NOT_FOUND");
        assertThat(nothing.jsonPath().getMap("$")).as("no data").doesNotContainKey("data");

        Response put = given().contentType(ContentType.JSON).body(Map.of())
            .put(SurfaceFixture.item(Selector.ITEM, 1));
        assertThat(put.statusCode()).as(put.asString()).isEqualTo(405);
        assertThat(put.jsonPath().getString("reason")).isEqualTo("VERB_NOT_CARRIED");
        assertThat(put.header("Allow")).as("the methods the route carries").isNotBlank();
        assertThat(put.jsonPath().getList("data.accepts", String.class))
            .as("the verbs carried at an object").contains("read", "update", "claim");

        Response delete = given().delete(SurfaceFixture.collection(Selector.ITEM));
        assertThat(delete.statusCode()).as(delete.asString()).isEqualTo(405);
        assertThat(delete.jsonPath().getString("reason")).isEqualTo("VERB_NOT_CARRIED");
        assertThat(delete.jsonPath().getList("data.accepts", String.class))
            .as("the verbs carried at a collection").contains("create", "query");

        identity.setTestIdentity(null);
        Response unauthenticated = given().get(SurfaceFixture.collection(Selector.ITEM));
        assertThat(unauthenticated.statusCode()).isEqualTo(401);
        assertThat(unauthenticated.asString()).as("the challenge, not the envelope")
            .doesNotContain("\"reason\"");
    }
}
