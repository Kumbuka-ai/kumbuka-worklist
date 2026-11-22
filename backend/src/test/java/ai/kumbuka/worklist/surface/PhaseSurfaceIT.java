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
import ai.kumbuka.worklist.domain.WorkstreamService;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The phase of an item on both surfaces (REA-0011 S9; D1, D8, D11, D12;
 * TAR-0002 B12): create makes a draft on every surface, the REST create takes
 * a field that submits in the same act and the assistant surface does not, and
 * a status write that names another phase is refused by state with the state
 * and the open calls.
 *
 * <p>Each case names the acceptance criterion it covers. The expectations are
 * written here from those nodes and none is read from the service.
 *
 * <h2>The red probe</h2>
 *
 * With {@code create} storing the item in the phase open --
 * {@code item.enter(Phase.DRAFT)} in {@code ItemService.create} replaced by
 * {@code Phase.OPEN} -- {@link #k1_an_item_created_over_mcp_reads_back_as_a_draft_and_is_open_after_submit}
 * fails at its first assertion, reading {@code open} where {@code draft} is
 * expected. With {@code update} taking a status of another phase,
 * {@link #k7_an_update_naming_a_status_of_another_phase_is_refused_with_the_state_and_the_open_calls}
 * fails where {@code isError} is expected and the call was carried out.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class PhaseSurfaceIT {

    private static final String SLUG = "phase-surface";
    private static final UUID SCOPE = UUID.fromString("0c0a11e5-0000-4000-8000-00000000a501");

    @Inject TestIdentityAssociation identity;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject WorkstreamService workstreams;

    @BeforeEach
    void asMemberOfAStagedScope() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);
        SurfaceFixture.publishScope(SLUG, SCOPE, "project", false);
        for (String view : Selector.VIEWS) {
            selectors.open(SCOPE, view);
        }
        // name, rank, actionable, in_progress, closed, successful
        vocabulary.declareStatus(SCOPE, "intake", 1, false, false, false, false);
        vocabulary.declareStatus(SCOPE, "planned", 2, true, false, false, false);
        vocabulary.declareStatus(SCOPE, "in_progress", 3, true, true, false, false);
        vocabulary.declareStatus(SCOPE, "done", 4, false, false, true, true);
        workstreams.requireDefault(SCOPE);
    }

    // ====================================================================
    // K1: over MCP an item is created as a draft and opened by submit
    // ====================================================================

    @Test
    void k1_an_item_created_over_mcp_reads_back_as_a_draft_and_is_open_after_submit() {
        Map<String, Object> created = Mcp.answer(Mcp.call("worklist_create", Map.of(
            "scope", SLUG, "selector", Selector.ITEM, "fields", Map.of("title", "over mcp"))));
        String address = String.valueOf(created.get("address"));

        Map<String, Object> read = Mcp.answer(Mcp.call("worklist_read", Map.of("address", address)));
        assertThat(fields(read).get("phase")).as("create makes a draft (D12)").isEqualTo("draft");
        assertThat(calls(read))
            .as("a draft opens read, update, relate, unrelate, the tag verbs, submit and "
                + "discard (Annex A1)")
            .containsExactly("worklist_read " + address, "worklist_update " + address,
                "worklist_relate " + address, "worklist_unrelate " + address,
                "worklist_tag_attach " + address, "worklist_tag_detach " + address,
                "worklist_submit " + address, "worklist_discard " + address);

        Map<String, Object> submitted = Submitted.overMcp(read);
        assertThat(fields(submitted).get("phase")).isEqualTo("open");
        assertThat(fields(Mcp.answer(Mcp.call("worklist_read", Map.of("address", address))))
            .get("phase")).as("and reads back open").isEqualTo("open");
    }

    @Test
    void k1_the_assistant_surface_carries_no_field_that_submits_on_create() {
        Response refused = Mcp.call("worklist_create", Map.of("scope", SLUG,
            "selector", Selector.ITEM, "fields", Map.of("title", "submitted at once?"),
            "submit", true));
        assertThat(Mcp.reason(refused))
            .as("creating and submitting are two acts over the assistant surface (B12)")
            .isEqualTo("ARGUMENT_UNKNOWN");

        Response tools = Mcp.rpc("tools/list", Map.of());
        List<Map<String, Object>> listed = tools.jsonPath().getList("result.tools");
        Map<String, Object> create = listed.stream()
            .filter(tool -> "worklist_create".equals(tool.get("name")))
            .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>)
            ((Map<String, Object>) create.get("inputSchema")).get("properties");
        assertThat(properties).as("the tool declares no submit").doesNotContainKey("submit");
    }

    // ====================================================================
    // K2: a REST create with the submit field reads back open
    // ====================================================================

    @Test
    void k2_a_rest_create_with_submit_reads_back_open_and_without_it_as_a_draft() {
        Response submitted = given().contentType(ContentType.JSON)
            .body(Map.of("title", "submitted at once"))
            .post(Submitted.restCreate("/api/" + SLUG + "/" + Selector.ITEM));
        assertThat(submitted.statusCode()).as(submitted.asString()).isEqualTo(201);
        String location = "/api/" + SLUG + "/" + Selector.ITEM + "/"
            + numberOf(submitted.jsonPath().getString("address"));
        assertThat(given().get(location).jsonPath().getString("fields.phase"))
            .as("a REST create with the submit field reads back open (K2)")
            .isEqualTo("open");

        Response drafted = given().contentType(ContentType.JSON)
            .body(Map.of("title", "a draft over rest"))
            .post("/api/" + SLUG + "/" + Selector.ITEM);
        assertThat(drafted.statusCode()).isEqualTo(201);
        assertThat(drafted.jsonPath().getString("fields.phase"))
            .as("without it, REST creates a draft as well (D12)")
            .isEqualTo("draft");
    }

    @Test
    void a_submit_field_that_is_neither_true_nor_false_is_refused_as_a_form_fault() {
        Response refused = given().contentType(ContentType.JSON)
            .body(Map.of("title", "submitted how?"))
            .post("/api/" + SLUG + "/" + Selector.ITEM + "?submit=maybe");
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("ARGUMENT_INVALID");
        assertThat(refused.jsonPath().getList("data.offenders", String.class))
            .containsExactly("submit");
    }

    // ====================================================================
    // K3: start is refused by state, with the state and the open calls
    // ====================================================================

    @Test
    void k3_start_without_actionable_or_type_is_refused_by_state_on_rest() {
        Map<String, Object> open = Submitted.createOverMcp(SLUG,
            Map.of("title", "not yet worked out", "status", "intake"));
        String path = "/api/" + SLUG + "/" + Selector.ITEM + "/"
            + numberOf(String.valueOf(open.get("address")));

        Response refused = given().contentType(ContentType.JSON)
            .header("If-Match", "\"" + open.get("conflict_token") + "\"")
            .post(path + ":start");
        assertThat(refused.statusCode()).as(refused.asString()).isEqualTo(409);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("TRANSITION_REFUSED");
        assertThat(refused.jsonPath().getString("data.state")).isEqualTo("open");
        assertThat(refused.jsonPath().getList("data.next.call", String.class))
            .as("start is refused, and so it is not among the open calls")
            .isNotEmpty()
            .noneMatch(call -> call.startsWith("start "));
    }

    // ====================================================================
    // K7: a status of another phase is refused by state
    // ====================================================================

    @Test
    void k7_an_update_naming_a_status_of_another_phase_is_refused_with_the_state_and_the_open_calls() {
        Map<String, Object> open = Submitted.createOverMcp(SLUG,
            Map.of("title", "open", "status", "intake"));
        String address = String.valueOf(open.get("address"));

        Response refused = Mcp.call("worklist_update", Map.of("address", address,
            "conflict_token", open.get("conflict_token"), "fields", Map.of("status", "done")));
        Map<String, Object> refusal = Mcp.refusal(refused);
        assertThat(refusal.get("reason"))
            .as("the phase moves through a transition and never through update (D11, D8)")
            .isEqualTo("TRANSITION_REFUSED");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) refusal.get("data");
        assertThat(data.get("state")).as("the refusal names the state").isEqualTo("open");
        assertThat(callsOf(data))
            .as("and the calls open on the item, close among them")
            .contains("worklist_close " + address, "worklist_update " + address);

        Map<String, Object> within = Mcp.answer(Mcp.call("worklist_update", Map.of(
            "address", address, "conflict_token", open.get("conflict_token"),
            "fields", Map.of("status", "planned"))));
        assertThat(fields(within).get("status"))
            .as("a status of the same phase is taken")
            .isEqualTo("planned");
    }

    // ====================================================================
    // The field classes (REA-0011 S10, D13): content frozen at submit, the
    // refusal naming the addendum, and the addendum on both surfaces
    // ====================================================================

    @Test
    void s10_k1_a_title_write_on_an_open_item_is_refused_by_state_and_names_the_addendum() {
        Map<String, Object> open = Submitted.createOverMcp(SLUG,
            Map.of("title", "frozen at submit", "status", "planned"));
        String address = String.valueOf(open.get("address"));

        Map<String, Object> refusal = Mcp.refusal(Mcp.call("worklist_update", Map.of(
            "address", address, "conflict_token", open.get("conflict_token"),
            "fields", Map.of("title", "renamed after submit"))));
        assertThat(refusal.get("reason")).as("content is frozen at submit (D13, Annex A2)")
            .isEqualTo("TRANSITION_REFUSED");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) refusal.get("data");
        assertThat(data.get("state")).as("the refusal names the state (D8)").isEqualTo("open");
        assertThat(callsOf(data))
            .as("and the open calls, the addendum among them")
            .contains("worklist_addendum " + address);

        Response rest = given().contentType(ContentType.JSON)
            .header("If-Match", "\"" + open.get("conflict_token") + "\"")
            .body(Map.of("description", "described after submit"))
            .patch(path(address));
        assertThat(rest.statusCode()).as(rest.asString()).isEqualTo(409);
        assertThat(rest.jsonPath().getString("reason")).isEqualTo("TRANSITION_REFUSED");
        assertThat(rest.jsonPath().getString("data.state")).isEqualTo("open");
        assertThat(rest.jsonPath().getList("data.next.call", String.class))
            .as("REST names the calls in its own verb names")
            .contains("addendum " + address);
    }

    @Test
    void s10_k5_an_addendum_is_written_over_rest_and_mcp_and_refused_on_a_draft() {
        Map<String, Object> open = Submitted.createOverMcp(SLUG,
            Map.of("title", "corrected by addenda", "status", "planned"));
        String address = String.valueOf(open.get("address"));

        Response overRest = given().contentType(ContentType.JSON)
            .body(Map.of("text", "the first, over rest"))
            .post(path(address) + ":addendum");
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(200);
        assertThat(overRest.jsonPath().getString("fields.conflict_token"))
            .as("an addendum moves the item's token neither (D1)")
            .isEqualTo(String.valueOf(open.get("conflict_token")));

        Map<String, Object> overMcp = Mcp.answer(Mcp.call("worklist_addendum", Map.of(
            "address", address, "text", "the second, over mcp")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> addenda =
            (List<Map<String, Object>>) fields(overMcp).get("addenda");
        assertThat(addenda).extracting(entry -> entry.get("text"))
            .as("in the order written")
            .containsExactly("the first, over rest", "the second, over mcp");
        assertThat(addenda).allSatisfy(entry -> {
            assertThat(entry.get("author"))
                .as("the author is the caller, as the service derives the creator")
                .isEqualTo(fields(overMcp).get("creator"));
            assertThat(entry.get("written_at")).isNotNull();
        });

        Response drafted = given().contentType(ContentType.JSON)
            .body(Map.of("title", "still a draft"))
            .post("/api/" + SLUG + "/" + Selector.ITEM);
        Response refused = given().contentType(ContentType.JSON)
            .body(Map.of("text", "too early"))
            .post(path(drafted.jsonPath().getString("address")) + ":addendum");
        assertThat(refused.statusCode()).as(refused.asString()).isEqualTo(409);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("TRANSITION_REFUSED");
        assertThat(refused.jsonPath().getString("data.state")).isEqualTo("draft");
        assertThat(refused.jsonPath().getList("data.next.call", String.class))
            .as("a draft is edited: the addendum is no call of it")
            .noneMatch(call -> call.startsWith("addendum "));

        Response elsewhere = given().contentType(ContentType.JSON)
            .body(Map.of("text", "on an iteration"))
            .post("/api/" + SLUG + "/" + Selector.ITERATION + "/1:addendum");
        assertThat(elsewhere.jsonPath().getString("reason"))
            .as("only an item takes an addendum")
            .isEqualTo("VERB_UNCARRIED");
    }

    /** The REST path of an item's address. */
    private static String path(String address) {
        return "/api/" + SLUG + "/" + Selector.ITEM + "/" + numberOf(address);
    }

    // ====================================================================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fields(Map<String, Object> answer) {
        return (Map<String, Object>) answer.get("fields");
    }

    private static List<String> calls(Map<String, Object> answer) {
        return callsOf(answer);
    }

    @SuppressWarnings("unchecked")
    private static List<String> callsOf(Map<String, Object> holder) {
        return ((List<Map<String, Object>>) holder.get("next")).stream()
            .map(step -> String.valueOf(step.get("call")))
            .toList();
    }

    private static String numberOf(String address) {
        return address.substring(address.lastIndexOf('/') + 1);
    }
}
