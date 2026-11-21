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
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A refusal by state names the state and the calls open on the refused
 * object (service contract C8.7, DEC-0043): {@code data.state} as the
 * object's own read would answer it, and {@code data.next} as an answer
 * names it, in the names of the caller's surface.
 *
 * <p>Where the way out is a call on the refused object, the code is
 * {@code TRANSITION_REFUSED} 409: a write to a closed iteration, to a
 * membership of one, a plan into one, and -- the commissioner's decision for
 * this cut -- an update or a second close of a closed milestone, whose one
 * open call is {@code read}. Closing an iteration that still holds memberships
 * neither done nor dropped keeps its own code, {@code ITERATION_INCOMPLETE},
 * and names those memberships under {@code data.children}.
 *
 * <p>The state words are written here: an iteration is {@code open} or
 * {@code closed}, a membership is in the state of its iteration, a milestone
 * in its status.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class StateRefusalIT {

    private static final String SCOPE = "state-refusals";
    private static final UUID SCOPE_ID = UUID.fromString("5ea7e000-0000-4000-8000-000000000001");
    private static final String OPEN = "sr-open";

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
            workstreams.requireDefault(SCOPE_ID);
            try {
                settings.create(SCOPE_ID, Map.of());
            } catch (WorklistException alreadyThere) {
                assertThat(alreadyThere.reason())
                    .isEqualTo(WorklistException.Reason.SETTING_PRESENT);
            }
            staged = true;
        }
    }

    @Test
    void a_write_to_a_closed_iteration_names_its_state_and_its_one_open_call() {
        String path = address(create(Selector.MILESTONE, Map.of("title", "the path")));
        String item = address(create(Selector.ITEM, Map.of("title", "worked",
            "status", OPEN, "milestone", number(path))));
        String iteration = address(create(Selector.ITERATION, Map.of("title", "done with",
            "motto", "done", "description", "closed before the probe")));
        String member = iteration + "/" + number(item);
        Mcp.answer(Mcp.call("worklist_plan", Map.of("address", member,
            "conflict_token", token(iteration))));
        Mcp.answer(Mcp.call("worklist_update", Map.of("address", member,
            "conflict_token", token(iteration), "fields", Map.of("membership_status", "done"))));
        Mcp.answer(Mcp.call("worklist_close", Map.of("address", iteration,
            "conflict_token", token(iteration), "produced", "a record")));
        String other = address(create(Selector.ITEM, Map.of("title", "too late",
            "status", OPEN, "milestone", number(path))));

        List<String> offences = new ArrayList<>();
        offences.addAll(AnswerChecks.stateRefusalOffences("update of a closed iteration",
            Mcp.refusal(Mcp.call("worklist_update", Map.of("address", iteration,
                "conflict_token", token(iteration), "fields", Map.of("motto", "again")))),
            "TRANSITION_REFUSED", "closed", List.of("worklist_read " + iteration), List.of()));
        offences.addAll(AnswerChecks.stateRefusalOffences("a second close",
            Mcp.refusal(Mcp.call("worklist_close", Map.of("address", iteration,
                "conflict_token", token(iteration), "produced", "again"))),
            "TRANSITION_REFUSED", "closed", List.of("worklist_read " + iteration), List.of()));
        offences.addAll(AnswerChecks.stateRefusalOffences("update of a membership",
            Mcp.refusal(Mcp.call("worklist_update", Map.of("address", member,
                "conflict_token", token(iteration),
                "fields", Map.of("membership_status", "dropped")))),
            "TRANSITION_REFUSED", "closed", List.of("worklist_read " + member), List.of()));
        offences.addAll(AnswerChecks.stateRefusalOffences("a plan into it",
            Mcp.refusal(Mcp.call("worklist_plan", Map.of(
                "address", iteration + "/" + number(other),
                "conflict_token", token(iteration)))),
            "TRANSITION_REFUSED", "closed", List.of("worklist_read " + iteration), List.of()));

        String rest = "/api/" + iteration.substring("worklist://".length());
        Response overRest = given().contentType(ContentType.JSON)
            .header("If-Match", "\"" + token(iteration) + "\"")
            .body(Map.of("motto", "again")).patch(rest);
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(409);
        offences.addAll(AnswerChecks.stateRefusalOffences("over REST",
            overRest.jsonPath().getMap("$"), "TRANSITION_REFUSED", "closed",
            List.of("read " + iteration), List.of()));

        assertThat(offences)
            .as("a refusal by state names the state and the calls still open on the object")
            .isEmpty();
    }

    @Test
    void a_write_to_a_closed_milestone_is_refused_by_its_state() {
        String milestone = address(create(Selector.MILESTONE, Map.of("title", "reached")));
        Mcp.answer(Mcp.call("worklist_close", Map.of("address", milestone,
            "conflict_token", token(milestone))));

        List<String> offences = new ArrayList<>();
        offences.addAll(AnswerChecks.stateRefusalOffences("update of a closed milestone",
            Mcp.refusal(Mcp.call("worklist_update", Map.of("address", milestone,
                "conflict_token", token(milestone), "fields", Map.of("title", "again")))),
            "TRANSITION_REFUSED", "closed", List.of("worklist_read " + milestone),
            List.of()));
        offences.addAll(AnswerChecks.stateRefusalOffences("reopening a closed milestone",
            Mcp.refusal(Mcp.call("worklist_update", Map.of("address", milestone,
                "conflict_token", token(milestone), "fields", Map.of("status", "active")))),
            "TRANSITION_REFUSED", "closed", List.of("worklist_read " + milestone),
            List.of()));
        offences.addAll(AnswerChecks.stateRefusalOffences("a second close of a milestone",
            Mcp.refusal(Mcp.call("worklist_close", Map.of("address", milestone,
                "conflict_token", token(milestone)))),
            "TRANSITION_REFUSED", "closed", List.of("worklist_read " + milestone),
            List.of()));

        Response overRest = given().contentType(ContentType.JSON)
            .header("If-Match", "\"" + token(milestone) + "\"")
            .body(Map.of("title", "again"))
            .patch("/api/" + milestone.substring("worklist://".length()));
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(409);
        offences.addAll(AnswerChecks.stateRefusalOffences("over REST",
            overRest.jsonPath().getMap("$"), "TRANSITION_REFUSED", "closed",
            List.of("read " + milestone), List.of()));

        assertThat(offences)
            .as("a closed milestone takes no write, and the refusal names its state and read")
            .isEmpty();
        assertThat(Mcp.answer(Mcp.call("worklist_read", Map.of("address", milestone))))
            .as("and read stays open").containsKey("fields");
    }

    @Test
    void closing_an_iteration_with_live_memberships_names_them_as_children() {
        String path = address(create(Selector.MILESTONE, Map.of("title", "the path")));
        String item = address(create(Selector.ITEM, Map.of("title", "still open",
            "status", OPEN, "milestone", number(path))));
        String iteration = address(create(Selector.ITERATION, Map.of("title", "busy",
            "motto", "busy", "description", "holds a live membership")));
        String member = iteration + "/" + number(item);
        Mcp.answer(Mcp.call("worklist_plan", Map.of("address", member,
            "conflict_token", token(iteration))));

        Map<String, Object> refusal = Mcp.refusal(Mcp.call("worklist_close", Map.of(
            "address", iteration, "conflict_token", token(iteration), "produced", "early")));
        List<String> open = AnswerChecks.calls(Mcp.answer(Mcp.call("worklist_read",
            Map.of("address", iteration))).get("next"));

        assertThat(open).as("the iteration's own read names what is open on it")
            .contains("worklist_read " + iteration, "worklist_update " + iteration,
                "worklist_close " + iteration, "worklist_plan " + iteration);
        assertThat(AnswerChecks.stateRefusalOffences("close with a live membership", refusal,
                "ITERATION_INCOMPLETE", "open", open, List.of(member)))
            .as("the refusal keeps its own code, names the open state and the open calls, "
                + "and the live memberships under children")
            .isEmpty();
    }

    // =======================================================================

    private static Map<String, Object> create(String view, Map<String, Object> fields) {
        return Mcp.answer(Mcp.call("worklist_create", Map.of("scope", SCOPE,
            "selector", view, "fields", fields)));
    }

    private static String token(String address) {
        return String.valueOf(Mcp.answer(Mcp.call("worklist_read",
            Map.of("address", address))).get("conflict_token"));
    }

    private static String address(Map<String, Object> answer) {
        return String.valueOf(answer.get("address"));
    }

    private static long number(String address) {
        return Long.parseLong(address.substring(address.lastIndexOf('/') + 1));
    }
}
