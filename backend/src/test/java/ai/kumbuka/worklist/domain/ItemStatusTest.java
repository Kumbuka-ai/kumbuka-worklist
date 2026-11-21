/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The consistency rules over one status, the phase it is bound to, and the
 * predicates an item answers from its phase and its status, without a
 * database (TAR-0002 B2, B3 and Annex A1, DEC-0037 C5).
 *
 * <p>The expectations are written here from those nodes: which combination
 * contradicts, and which predicate is answered where. The database paths that
 * use both are {@code StatusPredicatesIT}'s.
 */
class ItemStatusTest {

    @Test
    void closed_and_in_progress_contradict_each_other() {
        assertThat(status("closed", "in_progress").contradicts(false)).isTrue();
        assertThat(status("closed").contradicts(false)).isFalse();
        assertThat(status("in_progress").contradicts(false)).isFalse();
    }

    @Test
    void verified_requires_closed_and_successful() {
        assertThat(status("verified").contradicts(false))
            .as("verified alone").isTrue();
        assertThat(status("verified", "closed").contradicts(false))
            .as("verified and closed, not successful").isTrue();
        assertThat(status("verified", "successful").contradicts(false))
            .as("verified and successful, not closed").isTrue();
        assertThat(status("verified", "closed", "successful").contradicts(false))
            .as("verified, closed and successful").isFalse();
    }

    @Test
    void successful_without_closed_is_no_contradiction() {
        assertThat(status("successful").contradicts(true))
            .as("a rule of the answer, not of the declaration (TAR-0002 B3)")
            .isFalse();
    }

    @Test
    void where_review_or_approval_is_used_an_actionable_status_is_reviewed_and_approved() {
        assertThat(status("actionable").contradicts(false))
            .as("in a scope that uses neither, actionable alone stands").isFalse();
        assertThat(status("actionable").contradicts(true)).isTrue();
        assertThat(status("actionable", "reviewed").contradicts(true)).isTrue();
        assertThat(status("actionable", "approved").contradicts(true)).isTrue();
        assertThat(status("actionable", "reviewed", "approved").contradicts(true)).isFalse();
        assertThat(status().contradicts(true))
            .as("a status that is not actionable needs neither").isFalse();
    }

    @Test
    void an_item_answers_closed_actionable_and_in_progress_always_and_successful_where_closed() {
        Map<String, Object> open = ItemService.predicatesOf(Phase.OPEN,
            status("actionable", "successful"), EnumSet.noneOf(ScopeUsage.Property.class));
        assertThat(open)
            .as("an open item answers successful by no value, even where its status declares it")
            .isEqualTo(ordered("closed", false, "actionable", true, "in_progress", false));

        Map<String, Object> done = ItemService.predicatesOf(Phase.CLOSED,
            status("closed", "successful"), EnumSet.noneOf(ScopeUsage.Property.class));
        assertThat(done).isEqualTo(
            ordered("closed", true, "successful", true, "actionable", false, "in_progress", false));
    }

    /**
     * TAR-0002 B3: {@code closed} holds exactly in the phase closed and
     * {@code in_progress} exactly in the phase in_progress, whatever the
     * status declares; the others stay on the status word.
     */
    @Test
    void closed_and_in_progress_are_a_projection_of_the_phase() {
        ItemStatus inProgress = status("in_progress", "actionable");
        assertThat(ItemService.predicatesOf(Phase.OPEN, inProgress,
                EnumSet.noneOf(ScopeUsage.Property.class)))
            .as("an item placed in open by S9 whose status answers in_progress answers by its phase")
            .isEqualTo(ordered("closed", false, "actionable", true, "in_progress", false));
        assertThat(ItemService.predicatesOf(Phase.IN_PROGRESS, inProgress,
                EnumSet.noneOf(ScopeUsage.Property.class)))
            .isEqualTo(ordered("closed", false, "actionable", true, "in_progress", true));
        assertThat(ItemService.predicatesOf(Phase.DRAFT, status("actionable"),
                EnumSet.noneOf(ScopeUsage.Property.class)))
            .as("a draft is neither closed nor in progress")
            .isEqualTo(ordered("closed", false, "actionable", true, "in_progress", false));
    }

    /** TAR-0002 B2, B3: each status is bound to one phase, and none to draft. */
    @Test
    void every_status_is_bound_to_one_phase_and_none_to_draft() {
        assertThat(status("closed", "successful").phase()).isEqualTo(Phase.CLOSED);
        assertThat(status("closed").phase()).isEqualTo(Phase.CLOSED);
        assertThat(status("in_progress").phase()).isEqualTo(Phase.IN_PROGRESS);
        assertThat(status("actionable").phase()).isEqualTo(Phase.OPEN);
        assertThat(status().phase()).isEqualTo(Phase.OPEN);
    }

    @Test
    void the_optional_predicates_are_answered_only_where_the_scope_uses_them() {
        ItemStatus status = status("closed", "successful", "reviewed", "verified");

        assertThat(ItemService.predicatesOf(Phase.CLOSED, status,
                EnumSet.noneOf(ScopeUsage.Property.class)))
            .as("a scope that uses none: no value, not false")
            .doesNotContainKeys("reviewed", "approved", "verified");

        Map<String, Object> used = ItemService.predicatesOf(Phase.CLOSED, status,
            EnumSet.of(ScopeUsage.Property.REVIEWED, ScopeUsage.Property.APPROVED,
                ScopeUsage.Property.VERIFIED));
        assertThat(used)
            .containsEntry("reviewed", true)
            .containsEntry("approved", false)
            .containsEntry("verified", true);

        assertThat(ItemService.predicatesOf(Phase.CLOSED, status,
                EnumSet.of(ScopeUsage.Property.APPROVED)))
            .as("each one only where it is used")
            .containsEntry("approved", false)
            .doesNotContainKeys("reviewed", "verified");
    }

    @Test
    void priority_and_severity_are_no_predicates() {
        assertThat(ItemService.predicatesOf(Phase.OPEN, status("actionable"),
                EnumSet.of(ScopeUsage.Property.PRIORITY, ScopeUsage.Property.SEVERITY)))
            .isEqualTo(ordered("closed", false, "actionable", true, "in_progress", false));
    }

    /** A status declaring exactly the predicates named. */
    private static ItemStatus status(String... holding) {
        List<String> named = List.of(holding);
        ItemStatus status = new ItemStatus();
        status.actionable = named.contains("actionable");
        status.inProgress = named.contains("in_progress");
        status.closed = named.contains("closed");
        status.successful = named.contains("successful");
        status.reviewed = named.contains("reviewed");
        status.approved = named.contains("approved");
        status.verified = named.contains("verified");
        return status;
    }

    private static Map<String, Object> ordered(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
