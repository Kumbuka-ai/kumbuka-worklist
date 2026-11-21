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
 * The fulfilment ledger at the surface, over REST and MCP.
 *
 * <p>What the domain cases in {@code FulfilmentLedgerIT} cannot show: that
 * {@code book} travels on both surfaces and back, that the actor is the
 * subject of the token and nothing the call says, that the option of
 * {@code read} answers the whole ledger on both surfaces, that a refusal
 * arrives on the wire as a typed refusal naming the argument, and that
 * {@code book} is among the calls open to a caller who may write and absent
 * for one who may only read. The addresses expected here are written from the
 * order against the scope's published name.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class FulfilmentSurfaceIT {

    private static final String SCOPE = "fulfilment-surface";
    private static final UUID SCOPE_ID = UUID.fromString("00000000-0000-0000-0000-0000000005f1");

    private static final String OPEN = "fulfilment-open";
    private static final String DONE = "fulfilment-done";

    private static final String INSTANCE = "https://instances.example/run/5";

    @Inject TestIdentityAssociation identity;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;

    @BeforeAll
    static void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.publishEmptyScope(SCOPE, SCOPE_ID);
        SurfaceFixture.registerMutedMember();
    }

    @BeforeEach
    void openTheScope() {
        SurfaceFixture.asMember(identity);
        selectors.open(SCOPE_ID, Selector.ITEM);
        if (vocabulary.statusByName(SCOPE_ID, OPEN).isEmpty()) {
            vocabulary.declareStatus(SCOPE_ID, OPEN, 1, true, false, false, false);
            vocabulary.declareStatus(SCOPE_ID, DONE, 2, false, false, true, true);
        }
    }

    // =======================================================================
    // K7: book over REST and MCP, there and back
    // =======================================================================

    @Test
    void book_over_rest_reads_back_over_mcp_with_the_actor_of_the_token() {
        long item = createItem(2);

        Response booked = bookOverRest(item, Map.of("instance", INSTANCE, "entries", List.of(
            Map.of("criterion", 1, "status", "met", "evidence", "the report"),
            Map.of("criterion", address(item) + "/criterion/2", "status", "not_applicable",
                "evidence", "out of scope"))));
        booked.then().statusCode(200);
        assertThat(booked.jsonPath().getString("address"))
            .as("the answer is the item").isEqualTo(address(item));

        Map<String, Object> overMcp = Mcp.answer(Mcp.call("worklist_read", Map.of(
            "address", address(item))));
        List<Map<String, Object>> ledger = ledgerOf(overMcp);
        assertThat(ledger).hasSize(2);
        assertThat(ledger.get(0))
            .containsEntry("criterion", address(item) + "/criterion/1")
            .containsEntry("instance", INSTANCE)
            .containsEntry("status", "met")
            .containsEntry("evidence", "the report")
            .containsEntry("actor", SurfaceFixture.MEMBER)
            .containsEntry("after_close", false)
            .containsKey("booked_at");
        assertThat(ledger.get(1))
            .containsEntry("criterion", address(item) + "/criterion/2")
            .containsEntry("status", "not_applicable")
            .containsEntry("actor", SurfaceFixture.MEMBER);
    }

    @Test
    void book_over_mcp_reads_back_over_rest_and_the_option_answers_the_whole_ledger() {
        long item = createItem(1);
        String token = read(item).jsonPath().getString("fields.conflict_token");

        Map<String, Object> first = Mcp.answer(Mcp.call("worklist_book", Map.of(
            "address", address(item), "instance", INSTANCE,
            "entries", List.of(Map.of("criterion", 1, "status", "not_met", "evidence", "1st")))));
        assertThat(first.get("conflict_token"))
            .as("book leaves the item's token as it was, over MCP").isEqualTo(token);
        Mcp.answer(Mcp.call("worklist_book", Map.of(
            "address", address(item), "instance", INSTANCE,
            "entries", List.of(Map.of("criterion", 1, "status", "met", "evidence", "2nd")))));

        Response latest = read(item);
        assertThat(latest.jsonPath().getList("fields.fulfilment.evidence", String.class))
            .as("read answers the latest entry per criterion and instance")
            .containsExactly("2nd");
        assertThat(latest.jsonPath().getString("fields.conflict_token")).isEqualTo(token);

        Response whole = given().queryParam("fulfilment", "all")
            .when().get("/api/" + SCOPE + "/" + Selector.ITEM + "/" + item);
        whole.then().statusCode(200);
        assertThat(whole.jsonPath().getList("fields.fulfilment.evidence", String.class))
            .as("the option answers every entry, in booking order, over REST")
            .containsExactly("1st", "2nd");

        Map<String, Object> wholeOverMcp = Mcp.answer(Mcp.call("worklist_read", Map.of(
            "address", address(item), "fulfilment", "all")));
        assertThat(ledgerOf(wholeOverMcp)).extracting(e -> e.get("evidence"))
            .as("and over MCP")
            .containsExactly("1st", "2nd");
    }

    @Test
    void a_closed_item_takes_a_booking_over_the_surface_marked_after_close() {
        long item = createItem(1);
        String token = read(item).jsonPath().getString("fields.conflict_token");
        given().contentType(ContentType.JSON).header("If-Match", "\"" + token + "\"")
            .body(Map.of("status", DONE))
            .when().post("/api/" + SCOPE + "/" + Selector.ITEM + "/" + item + ":close")
            .then().statusCode(200);

        Response booked = bookOverRest(item, Map.of("instance", INSTANCE, "entries",
            List.of(Map.of("criterion", 1, "status", "met", "evidence", "late"))));
        booked.then().statusCode(200);
        assertThat(booked.jsonPath().getBoolean("fields.fulfilment[0].after_close"))
            .as("booked after the item closed, and answered as such")
            .isTrue();
    }

    // =======================================================================
    // K7: a refusal arrives on the wire as a typed refusal
    // =======================================================================

    @Test
    void an_identifier_the_item_never_issued_is_refused_typed_on_both_surfaces() {
        long item = createItem(1);
        Map<String, Object> neverIssued = Map.of("instance", INSTANCE, "entries", List.of(
            Map.of("criterion", 1, "status", "met", "evidence", "valid"),
            Map.of("criterion", 7, "status", "met", "evidence", "never issued")));

        Response overRest = bookOverRest(item, neverIssued);
        overRest.then().statusCode(422);
        assertThat(overRest.jsonPath().getString("reason")).isEqualTo("INVALID_VALUE");
        assertThat(overRest.jsonPath().getList("data.offenders", String.class))
            .containsExactly("entries");

        Response overMcp = Mcp.call("worklist_book", Map.of("address", address(item),
            "instance", INSTANCE, "entries", neverIssued.get("entries")));
        assertThat(Mcp.reason(overMcp)).isEqualTo("INVALID_VALUE");
        assertThat(Mcp.refusal(overMcp).get("data"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("offenders", List.of("entries"));

        assertThat(read(item).jsonPath().getList("fields.fulfilment"))
            .as("nothing of either booking was written, the valid entry included")
            .isEmpty();
    }

    @Test
    void a_status_outside_the_three_and_an_empty_instance_are_refused_naming_their_argument() {
        long item = createItem(1);

        Response status = bookOverRest(item, Map.of("instance", INSTANCE, "entries",
            List.of(Map.of("criterion", 1, "status", "done", "evidence", "x"))));
        status.then().statusCode(422);
        assertThat(status.jsonPath().getList("data.offenders", String.class))
            .containsExactly("entries");

        Response empty = Mcp.call("worklist_book", Map.of("address", address(item),
            "instance", "", "entries", List.of(Map.of("criterion", 1, "status", "met",
                "evidence", "x"))));
        assertThat(Mcp.reason(empty)).isEqualTo("INVALID_VALUE");
        assertThat(Mcp.refusal(empty).get("data"))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("offenders", List.of("instance"));
    }

    @Test
    void a_missing_argument_and_a_list_of_another_type_are_refused_by_their_names() {
        long item = createItem(1);

        Response missing = bookOverRest(item, Map.of("entries", List.of(
            Map.of("criterion", 1, "status", "met", "evidence", "x"))));
        missing.then().statusCode(400);
        assertThat(missing.jsonPath().getString("reason")).isEqualTo("ARGUMENT_MISSING");
        assertThat(missing.jsonPath().getList("data.offenders", String.class))
            .containsExactly("instance");

        Response notAList = Mcp.call("worklist_book", Map.of("address", address(item),
            "instance", INSTANCE, "entries", "met"));
        assertThat(Mcp.reason(notAList)).isEqualTo("ARGUMENT_INVALID");
    }

    /**
     * Only an item carries a ledger. The view is decided from the address
     * before anything is resolved, so no milestone has to stand for it.
     */
    @Test
    void book_at_a_milestone_address_is_refused_as_a_verb_the_view_does_not_carry() {
        Response overRest = given().contentType(ContentType.JSON)
            .body(Map.of("instance", INSTANCE, "entries", List.of()))
            .when().post("/api/" + SCOPE + "/" + Selector.MILESTONE + "/1:book");
        assertThat(overRest.jsonPath().getString("reason")).isEqualTo("VERB_UNCARRIED");

        Response overMcp = Mcp.call("worklist_book", Map.of(
            "address", "worklist://" + SCOPE + "/" + Selector.MILESTONE + "/1",
            "instance", INSTANCE, "entries", List.of()));
        assertThat(Mcp.reason(overMcp)).isEqualTo("VERB_UNCARRIED");
    }

    // =======================================================================
    // The open calls: a writer is named book, a reader is not
    // =======================================================================

    @Test
    void book_is_open_to_a_writer_and_neither_named_to_nor_taken_from_a_reader() {
        long item = createItem(1);
        assertThat(read(item).jsonPath().getList("next.call", String.class))
            .as("a caller with the write right is named book on the item")
            .contains("book " + address(item));

        SurfaceFixture.asMutedMember(identity);
        Response asReader = read(item);
        Response refused = bookOverRest(item, Map.of("instance", INSTANCE, "entries",
            List.of(Map.of("criterion", 1, "status", "met", "evidence", "x"))));
        SurfaceFixture.asMember(identity);

        assertThat(asReader.jsonPath().getList("next.call", String.class))
            .as("a caller who may only read is named the reading calls alone")
            .containsExactly("read " + address(item));
        refused.then().statusCode(403);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("SCOPE_READ_ONLY");
        assertThat(read(item).jsonPath().getList("fields.fulfilment")).isEmpty();
    }

    /**
     * A draft takes no booking: no instance works on what was never
     * submitted (REA-0011 Annex A1). It is named no book, and a booking is
     * refused by its state.
     */
    @Test
    void a_draft_is_named_no_book_and_refuses_one_by_its_state() {
        Response created = given().contentType(ContentType.JSON)
            .body(Map.of("title", "still a draft", "status", OPEN,
                "criteria", List.of(Map.of("text", "criterion 1"))))
            .when().post("/api/" + SCOPE + "/" + Selector.ITEM);
        created.then().statusCode(201);
        long draft = created.jsonPath().getLong("fields.number");
        assertThat(created.jsonPath().getList("next.call", String.class))
            .as("a draft is named no book")
            .doesNotContain("book " + address(draft));

        Response refused = bookOverRest(draft, Map.of("instance", INSTANCE, "entries",
            List.of(Map.of("criterion", 1, "status", "met", "evidence", "x"))));
        refused.then().statusCode(409);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("TRANSITION_REFUSED");
        assertThat(refused.jsonPath().getString("data.state")).isEqualTo("draft");
        assertThat(read(draft).jsonPath().getList("fields.fulfilment")).isEmpty();
    }

    // =======================================================================
    // Fixtures
    // =======================================================================

    /**
     * An open item with criteria 1 to {@code count}: created over REST and
     * submitted in the same act (REA-0011 D12).
     */
    private static long createItem(int count) {
        List<Map<String, Object>> criteria = new java.util.ArrayList<>();
        for (int k = 1; k <= count; k++) {
            criteria.add(Map.of("text", "criterion " + k));
        }
        Response created = given().contentType(ContentType.JSON)
            .body(Map.of("title", "booked against", "status", OPEN, "criteria", criteria))
            .when().post(Submitted.restCreate("/api/" + SCOPE + "/" + Selector.ITEM));
        created.then().statusCode(201);
        return created.jsonPath().getLong("fields.number");
    }

    private static Response bookOverRest(long item, Map<String, Object> body) {
        return given().contentType(ContentType.JSON).body(body)
            .when().post("/api/" + SCOPE + "/" + Selector.ITEM + "/" + item + ":book");
    }

    private static Response read(long item) {
        return given().when().get("/api/" + SCOPE + "/" + Selector.ITEM + "/" + item);
    }

    private static String address(long item) {
        return "worklist://" + SCOPE + "/" + Selector.ITEM + "/" + item;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> ledgerOf(Map<String, Object> answer) {
        return (List<Map<String, Object>>) ((Map<String, Object>) answer.get("fields"))
            .get("fulfilment");
    }
}
