/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.AttributeDefinition;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cancelled references and relations on both surfaces (REA-0011 S11, D15,
 * D8, Annex A1): {@code unrelate} takes a mandatory reason, cancels the edge
 * with its end, and is refused by state in progress; {@code read} answers the
 * active edges with a count of the cancelled ones, and its option answers the
 * cancelled entries.
 *
 * <p>Each case names the acceptance criterion it covers. The expectations are
 * written here from those nodes and none is read from the service.
 *
 * <h2>The red probe</h2>
 *
 * With {@code unrelate} taking an item in progress -- the refusal by state in
 * {@code ItemService.unrelate} switched off -- the case
 * {@link #k2_unrelate_on_an_item_in_progress_is_refused_by_state_over_mcp_and_rest} fails at
 * its first assertion: the call is answered without {@code isError}, so
 * {@code Mcp.refusal} finds no refusal.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class CancelledEdgesSurfaceIT {

    private static final String SLUG = "cancelled-edges";
    private static final UUID SCOPE = UUID.fromString("0c0a11e5-0000-4000-8000-00000000a511");
    private static final String RELATES = "relates";
    private static final String WHY = "the dependency was a misreading";

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
        vocabulary.declareStatus(SCOPE, "planned", 1, true, false, false, false);
        vocabulary.declareStatus(SCOPE, "doing", 2, true, true, false, false);
        vocabulary.declareStatus(SCOPE, "done", 3, false, false, true, true);
        vocabulary.declareRelationType(SCOPE, RELATES, false, 1);
        vocabulary.declareAttribute(SCOPE, "type", "Type", AttributeDefinition.TEXT, 1, false);
        workstreams.requireDefault(SCOPE);
    }

    // ====================================================================
    // K9: unrelate without a reason, or with an empty one, is refused
    // ====================================================================

    @Test
    void k9_unrelate_without_a_reason_or_with_an_empty_one_is_refused_over_rest() {
        Map<String, Object> source = open("the source");
        Map<String, Object> target = open("the target");
        Map<String, Object> related = relateOverMcp(source, target);
        String token = String.valueOf(fields(related).get("conflict_token"));

        Response missing = given().contentType(ContentType.JSON)
            .header("If-Match", "\"" + token + "\"")
            .body(Map.of("to_item", address(target), "type", RELATES))
            .post(path(source) + ":unrelate");
        assertThat(missing.statusCode()).as(missing.asString()).isEqualTo(400);
        assertThat(missing.jsonPath().getString("reason")).isEqualTo("ARGUMENT_MISSING");
        assertThat(missing.jsonPath().getList("data.offenders", String.class))
            .containsExactly("reason");

        Response empty = given().contentType(ContentType.JSON)
            .header("If-Match", "\"" + token + "\"")
            .body(Map.of("to_item", address(target), "type", RELATES, "reason", "   "))
            .post(path(source) + ":unrelate");
        assertThat(empty.statusCode()).as(empty.asString()).isEqualTo(400);
        assertThat(empty.jsonPath().getString("reason")).isEqualTo("ARGUMENT_INVALID");
        assertThat(empty.jsonPath().getList("data.offenders", String.class))
            .containsExactly("reason");

        assertThat(relationsOf(readOverMcp(source, null))).as("the edge stays active").hasSize(1);
    }

    @Test
    void k9_unrelate_without_a_reason_or_with_an_empty_one_is_refused_over_mcp() {
        Map<String, Object> source = open("the source");
        Map<String, Object> target = open("the target");
        Map<String, Object> related = relateOverMcp(source, target);
        Object token = fields(related).get("conflict_token");

        Map<String, Object> missing = Mcp.refusal(Mcp.call("worklist_unrelate", Map.of(
            "address", address(source), "conflict_token", token,
            "to_item", address(target), "type", RELATES)));
        assertThat(missing.get("reason")).isEqualTo("ARGUMENT_MISSING");
        assertThat(offenders(missing)).containsExactly("reason");

        Map<String, Object> empty = Mcp.refusal(Mcp.call("worklist_unrelate", Map.of(
            "address", address(source), "conflict_token", token,
            "to_item", address(target), "type", RELATES, "reason", "")));
        assertThat(empty.get("reason")).isEqualTo("ARGUMENT_INVALID");
        assertThat(offenders(empty)).containsExactly("reason");

        assertThat(relationsOf(readOverMcp(source, null))).as("the edge stays active").hasSize(1);
    }

    // ====================================================================
    // K1: unrelate cancels; read answers a count, and the option the entries
    // ====================================================================

    @Test
    void k1_unrelate_over_rest_cancels_and_read_answers_a_count_and_with_the_option_the_entry() {
        Map<String, Object> source = open("the source");
        Map<String, Object> target = open("the target");
        Map<String, Object> related = relateOverMcp(source, target);

        Response cancelled = given().contentType(ContentType.JSON)
            .header("If-Match", "\"" + fields(related).get("conflict_token") + "\"")
            .body(Map.of("to_item", address(target), "type", RELATES, "reason", WHY))
            .post(path(source) + ":unrelate");
        assertThat(cancelled.statusCode()).as(cancelled.asString()).isEqualTo(200);
        assertThat(cancelled.jsonPath().getList("fields.relations")).isEmpty();
        assertThat(cancelled.jsonPath().getInt("fields.cancelled_relations_count")).isEqualTo(1);

        Response plain = given().get(path(source));
        assertThat(plain.jsonPath().getList("fields.relations")).isEmpty();
        assertThat(plain.jsonPath().getInt("fields.cancelled_relations_count")).isEqualTo(1);
        assertThat(plain.jsonPath().getMap("fields"))
            .as("the entries only with the option").doesNotContainKey("cancelled_relations");

        Response all = given().get(path(source) + "?cancelled=all");
        assertThat(all.statusCode()).as(all.asString()).isEqualTo(200);
        assertThat(all.jsonPath().getList("fields.relations")).isEmpty();
        Map<String, Object> entry = all.jsonPath().getMap("fields.cancelled_relations[0]");
        assertThat(entry)
            .containsEntry("type", RELATES)
            .containsEntry("item", address(target))
            .containsEntry("end_reason", WHY)
            .containsEntry("ended_by", fields(source).get("creator"))
            .containsKey("ended_at");
    }

    @Test
    void k1_the_option_of_read_answers_the_cancelled_entries_over_mcp_and_refuses_another_value() {
        Map<String, Object> source = open("the source");
        Map<String, Object> target = open("the target");
        Map<String, Object> related = relateOverMcp(source, target);
        Mcp.answer(Mcp.call("worklist_unrelate", Map.of("address", address(source),
            "conflict_token", fields(related).get("conflict_token"),
            "to_item", address(target), "type", RELATES, "reason", WHY)));

        Map<String, Object> counted = fields(readOverMcp(source, null));
        assertThat(counted.get("cancelled_relations_count")).isEqualTo(1);
        assertThat(counted).doesNotContainKey("cancelled_relations");

        Map<String, Object> all = fields(readOverMcp(source, "all"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) all.get("cancelled_relations");
        assertThat(entries).singleElement()
            .satisfies(entry -> assertThat(entry).containsEntry("end_reason", WHY));
        assertThat((List<?>) all.get("cancelled_references")).isEmpty();

        assertThat(Mcp.reason(Mcp.call("worklist_read", Map.of("address", address(source),
                "cancelled", "some"))))
            .as("a value outside the two declared is a form fault").isEqualTo("ARGUMENT_INVALID");
    }

    // ====================================================================
    // K2: unrelate in progress is refused by state, and not named there
    // ====================================================================

    @Test
    void k2_unrelate_on_an_item_in_progress_is_refused_by_state_over_mcp_and_rest() {
        Map<String, Object> target = open("the target");
        Map<String, Object> source = Submitted.createOverMcp(SLUG, Map.of("title", "in work",
            "status", "planned", "attributes", Map.of("type", "task")));
        Map<String, Object> related = relateOverMcp(source, target);
        Map<String, Object> started = Mcp.answer(Mcp.call("worklist_start", Map.of(
            "address", address(source),
            "conflict_token", fields(related).get("conflict_token"))));
        assertThat(fields(started).get("phase")).isEqualTo("in_progress");
        assertThat(callsOf(started))
            .as("the open calls in progress name no unrelate (Annex A1)")
            .doesNotContain("worklist_unrelate " + address(source))
            .contains("worklist_relate " + address(source));

        Map<String, Object> refusal = Mcp.refusal(Mcp.call("worklist_unrelate", Map.of(
            "address", address(source), "conflict_token", fields(started).get("conflict_token"),
            "to_item", address(target), "type", RELATES, "reason", WHY)));
        assertThat(refusal.get("reason")).isEqualTo("TRANSITION_REFUSED");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) refusal.get("data");
        assertThat(data.get("state")).as("the refusal names the state (D8)")
            .isEqualTo("in_progress");
        assertThat(callsOf(data))
            .as("and the open calls: return is the way out, unrelate is none of them")
            .contains("worklist_return " + address(source))
            .doesNotContain("worklist_unrelate " + address(source));

        Response rest = given().contentType(ContentType.JSON)
            .header("If-Match", "\"" + fields(started).get("conflict_token") + "\"")
            .body(Map.of("to_item", address(target), "type", RELATES, "reason", WHY))
            .post(path(source) + ":unrelate");
        assertThat(rest.statusCode()).as(rest.asString()).isEqualTo(409);
        assertThat(rest.jsonPath().getString("reason")).isEqualTo("TRANSITION_REFUSED");
        assertThat(rest.jsonPath().getString("data.state")).isEqualTo("in_progress");

        assertThat(relationsOf(readOverMcp(source, null))).as("the edge stays active").hasSize(1);
    }

    // ====================================================================

    private Map<String, Object> open(String title) {
        return Submitted.createOverMcp(SLUG, Map.of("title", title, "status", "planned"));
    }

    private static Map<String, Object> relateOverMcp(Map<String, Object> source,
                                                     Map<String, Object> target) {
        Map<String, Object> now = readOverMcp(source, null);
        return Mcp.answer(Mcp.call("worklist_relate", Map.of("address", address(source),
            "conflict_token", fields(now).get("conflict_token"),
            "to_item", address(target), "type", RELATES)));
    }

    /** A read over MCP, with the option for cancelled edges where one is named. */
    private static Map<String, Object> readOverMcp(Map<String, Object> answer,
                                                   @Nullable String cancelled) {
        return Mcp.answer(Mcp.call("worklist_read", cancelled == null
            ? Map.of("address", address(answer))
            : Map.of("address", address(answer), "cancelled", cancelled)));
    }

    private static String address(Map<String, Object> answer) {
        return String.valueOf(answer.get("address"));
    }

    private static String path(Map<String, Object> answer) {
        String address = address(answer);
        return "/api/" + SLUG + "/" + Selector.ITEM + "/"
            + address.substring(address.lastIndexOf('/') + 1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fields(Map<String, Object> answer) {
        return (Map<String, Object>) answer.get("fields");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> relationsOf(Map<String, Object> answer) {
        return (List<Object>) fields(answer).get("relations");
    }

    @SuppressWarnings("unchecked")
    private static List<String> offenders(Map<String, Object> refusal) {
        return (List<String>) ((Map<String, Object>) refusal.get("data")).get("offenders");
    }

    @SuppressWarnings("unchecked")
    private static List<String> callsOf(Map<String, Object> holder) {
        return ((List<Map<String, Object>>) holder.get("next")).stream()
            .map(step -> String.valueOf(step.get("call")))
            .toList();
    }
}
