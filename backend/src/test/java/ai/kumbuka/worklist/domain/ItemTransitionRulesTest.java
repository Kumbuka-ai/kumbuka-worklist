/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conditions the transitions of an item are refused on, without a
 * database: each is one function that {@code submit}, {@code start} and the
 * open calls of an item read alike (REA-0011 D1, D6; TAR-0002 B18, B20).
 *
 * <p>The expectations are written here from those nodes: submit needs a title
 * and a body of work, the workstream; start needs a status answering
 * {@code actionable} and a type; a transition into a phase sets the first
 * declared status of that phase. The database paths are {@code ItemPhaseIT}'s.
 *
 * <p>The title half of submit's guard is shown here and not against the
 * database: the store refuses an item without a title from V1 on
 * ({@code ck_item_title}), and create refuses one, so a draft without a title
 * cannot be planted for an integration test to submit.
 */
class ItemTransitionRulesTest {

    @Test
    void submit_needs_a_title_and_a_workstream() {
        assertThat(ItemService.submittable(item("a title", 1L)))
            .as("a draft with a title and a workstream is submitted").isTrue();
        assertThat(ItemService.submittable(item(null, 1L)))
            .as("no title (TAR-0002 B20)").isFalse();
        assertThat(ItemService.submittable(item("   ", 1L)))
            .as("a blank title is no title").isFalse();
        assertThat(ItemService.submittable(item("a title", null)))
            .as("no body of work: no workstream").isFalse();
    }

    @Test
    void start_needs_an_actionable_status_and_a_type() {
        ItemStatus actionable = status(true, false, false);
        ItemStatus resting = status(false, false, false);

        assertThat(ItemService.startable(actionable, true)).isTrue();
        assertThat(ItemService.startable(resting, true))
            .as("a status that does not answer actionable (TAR-0002 B18)").isFalse();
        assertThat(ItemService.startable(actionable, false))
            .as("an item that carries no type").isFalse();
    }

    @Test
    void an_item_is_typed_by_a_value_under_its_scope_s_attribute_type() {
        AttributeDefinition type = new AttributeDefinition();
        type.pk = 41L;
        type.key = "type";

        Item item = item("typed", 1L);
        assertThat(ItemService.typed(item, Optional.empty()))
            .as("a scope that declares no attribute type holds no typed item").isFalse();
        assertThat(ItemService.typed(item, Optional.of(type)))
            .as("no value under the declaration").isFalse();

        item.attributes = new java.util.LinkedHashMap<>(Map.of("41", ""));
        assertThat(ItemService.typed(item, Optional.of(type))).as("an empty value").isFalse();

        item.attributes = new java.util.LinkedHashMap<>(Map.of("41", "task"));
        assertThat(ItemService.typed(item, Optional.of(type))).isTrue();

        item.attributes = new java.util.LinkedHashMap<>(Map.of("42", "task"));
        assertThat(ItemService.typed(item, Optional.of(type)))
            .as("a value under another declaration").isFalse();
    }

    @Test
    void a_transition_sets_the_first_declared_status_of_the_phase_it_enters() {
        ItemStatus withdrawnOpen = named("first-open-withdrawn", status(true, false, false));
        withdrawnOpen.status = DeclaredValue.WITHDRAWN;
        ItemStatus open = named("open", status(true, false, false));
        ItemStatus running = named("running", status(false, true, false));
        ItemStatus done = named("done", status(false, false, true));
        List<ItemStatus> declared = List.of(withdrawnOpen, open, running, done);

        assertThat(ItemService.firstOf(declared, Phase.OPEN))
            .as("the first of the phase in the scope's order, a withdrawn one left out")
            .contains(open);
        assertThat(ItemService.firstOf(declared, Phase.IN_PROGRESS)).contains(running);
        assertThat(ItemService.firstOf(declared, Phase.CLOSED)).contains(done);
        assertThat(ItemService.firstOf(declared, Phase.DRAFT))
            .as("no status is bound to the phase draft").isEmpty();
        assertThat(ItemService.firstOf(List.of(open), Phase.CLOSED))
            .as("a scope without a status of the phase").isEmpty();
    }

    @Test
    void a_draft_carries_a_status_of_the_phase_open() {
        assertThat(Phase.DRAFT.statusPhase()).isEqualTo(Phase.OPEN);
        assertThat(Phase.OPEN.statusPhase()).isEqualTo(Phase.OPEN);
        assertThat(Phase.IN_PROGRESS.statusPhase()).isEqualTo(Phase.IN_PROGRESS);
        assertThat(Phase.CLOSED.statusPhase()).isEqualTo(Phase.CLOSED);
    }

    @Test
    void the_four_phases_travel_under_their_tokens() {
        assertThat(Phase.tokens()).containsExactly("draft", "open", "in_progress", "closed");
        assertThat(Phase.byToken("in_progress")).contains(Phase.IN_PROGRESS);
        assertThat(Phase.byToken("planned")).isEmpty();
    }

    private static Item item(String title, Long workstream) {
        Item item = new Item();
        item.title = title;
        item.workstreamNumber = workstream;
        return item;
    }

    private static ItemStatus status(boolean actionable, boolean inProgress, boolean closed) {
        ItemStatus status = new ItemStatus();
        status.actionable = actionable;
        status.inProgress = inProgress;
        status.closed = closed;
        status.status = DeclaredValue.DECLARED;
        return status;
    }

    private static ItemStatus named(String name, ItemStatus status) {
        status.name = name;
        return status;
    }
}
