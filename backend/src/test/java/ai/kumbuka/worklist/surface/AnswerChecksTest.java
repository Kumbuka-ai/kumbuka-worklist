/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The checks of {@link AnswerChecks}, each observed reporting the offence it
 * exists for, on every build: a planted answer for each offence, and a sound
 * answer each check lets pass. The gates that run the checks against a
 * running service -- {@code OpenCallsIT}, {@code StateRefusalIT} and
 * {@code NoLeaseOnTheSurfaceIT} -- need a database and run in the pull
 * request's checks; what they decide with is held red here.
 */
class AnswerChecksTest {

    private static final String ITEM = "worklist://planted/item/7";

    private static final Map<String, Object> READ = step("worklist_read " + ITEM, "Reads it.");
    private static final Map<String, Object> UPDATE =
        step("worklist_update " + ITEM, "Changes it.");

    @Test
    void the_checks_report_each_offence_they_exist_for() {
        assertThat(AnswerChecks.leaseOffences("planted", Map.of("address", ITEM,
                "fields", Map.of("receipt", "r", "items", List.of(Map.of("actor", "self"))))))
            .as("RED STATE, observed: a receipt in fields and a holder deep in a list are "
                + "each reported by path")
            .containsExactlyInAnyOrder("planted $.fields.receipt is a member of a lease",
                "planted $.fields.items[0].actor is a member of a lease");

        assertThat(AnswerChecks.nextOffences("planted", Map.of("address", ITEM,
                "waiting_for", "somebody",
                "next", List.of(READ, step("worklist_claim " + ITEM, " "), READ,
                    Map.of("call", "worklist_relate " + ITEM))),
                List.of("worklist_read " + ITEM, "worklist_update " + ITEM)))
            .as("RED STATE, observed: a waiting_for, a step without a sentence, a call named "
                + "twice, a step that is no {call, does}, a call lacking and a call beyond "
                + "the open ones are each reported")
            .containsExactly(
                "planted: carries waiting_for",
                "planted: worklist_claim " + ITEM + " says nothing it does",
                "planted: a step is no {call, does}: {call=worklist_relate " + ITEM + "}",
                "planted: names worklist_read " + ITEM + " twice",
                "planted: lacks worklist_update " + ITEM,
                "planted: names worklist_claim " + ITEM + " beyond the open calls");

        assertThat(AnswerChecks.nextOffences("planted", Map.of("address", ITEM), List.of()))
            .as("RED STATE, observed: an answer without next is reported")
            .containsExactly("planted: next is no list");

        assertThat(AnswerChecks.stateRefusalOffences("planted", Map.of(
                "reason", "ITERATION_CLOSED",
                "data", Map.of("state", "open", "next", List.of(READ, UPDATE),
                    "children", List.of(ITEM))),
                "TRANSITION_REFUSED", "closed", List.of("worklist_read " + ITEM), List.of()))
            .as("RED STATE, observed: another code, another state, a write named on a "
                + "refused object and children nobody expected are each reported")
            .containsExactly(
                "planted: the code is ITERATION_CLOSED, not TRANSITION_REFUSED",
                "planted: data.state is open, not closed",
                "planted data: names worklist_update " + ITEM + " beyond the open calls",
                "planted: data carries children");

        assertThat(AnswerChecks.stateRefusalOffences("planted", Map.of(
                "reason", "TRANSITION_REFUSED"),
                "TRANSITION_REFUSED", "closed", List.of("worklist_read " + ITEM), List.of()))
            .as("RED STATE, observed: a refusal by state without data is reported")
            .containsExactly("planted: the refusal carries no data");
    }

    @Test
    void a_sound_answer_passes_every_check() {
        Map<String, Object> answer = Map.of("address", ITEM, "fields", Map.of("title", "t"),
            "next", List.of(READ, UPDATE));
        assertThat(AnswerChecks.leaseOffences("sound", answer)).isEmpty();
        assertThat(AnswerChecks.nextOffences("sound", answer,
            List.of("worklist_update " + ITEM, "worklist_read " + ITEM))).isEmpty();
        assertThat(AnswerChecks.stateRefusalOffences("sound", Map.of(
                "reason", "ITERATION_INCOMPLETE",
                "data", Map.of("state", "open", "next", List.of(READ),
                    "children", List.of(ITEM))),
                "ITERATION_INCOMPLETE", "open", List.of("worklist_read " + ITEM),
                List.of(ITEM)))
            .isEmpty();
        assertThat(AnswerChecks.calls(answer.get("next")))
            .containsExactly("worklist_read " + ITEM, "worklist_update " + ITEM);
    }

    private static Map<String, Object> step(String call, String does) {
        return Map.of("call", call, "does", does);
    }
}
