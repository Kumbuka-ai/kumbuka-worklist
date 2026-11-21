/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The decisions that bound planning, without a database: the two platform
 * constants of REA-0011 D9, the count a membership bound is held against, and
 * whether an item may enter an iteration at all (TAR-0002 B9).
 *
 * <p>Every expectation is written here from those nodes: one hundred open
 * iterations, one thousand memberships, a dropped membership not counted, a
 * closed item refused and nothing else asked of an item. The database paths
 * that use these decisions are {@code PlanningDomainIT}'s and
 * {@code PlanningRefusalIT}'s.
 */
class PlanningBoundsTest {

    @Test
    void the_platform_constants_are_those_of_d9() {
        assertThat(PlanningService.OPEN_ITERATIONS_PER_SCOPE)
            .as("at most one hundred open iterations per scope (D9)")
            .isEqualTo(100);
        assertThat(PlanningService.MEMBERSHIPS_PER_ITERATION)
            .as("at most one thousand memberships per iteration (D9)")
            .isEqualTo(1000);
    }

    @Test
    void reaching_a_bound_is_admitted_and_passing_it_is_refused_with_the_cardinality_code() {
        PlanningService.refuseBeyond(100, 100);
        assertThat(PlanningService.within(100, 100)).isTrue();

        WorklistException refusal = catchThrowableOfType(WorklistException.class,
            () -> PlanningService.refuseBeyond(101, 100));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.CARDINALITY_EXCEEDED);
        assertThat(PlanningService.within(101, 100)).isFalse();
    }

    @Test
    void a_dropped_membership_does_not_count_against_the_bound() {
        List<IterationMembership> held = new ArrayList<>();
        held.add(membership("todo"));
        held.add(membership("active"));
        held.add(membership("done"));
        held.add(membership("dropped"));
        held.add(membership("dropped"));

        assertThat(PlanningService.countedMemberships(held))
            .as("todo, active and done count; the two dropped ones are unplanned items")
            .isEqualTo(3);
        assertThat(PlanningService.countedMemberships(List.of())).isZero();
    }

    @Test
    void an_item_is_plannable_unless_its_status_answers_closed() {
        assertThat(MembershipService.plannable(status(false, false, false)))
            .as("a status that is neither actionable nor closed: a raw intake is planned")
            .isTrue();
        assertThat(MembershipService.plannable(status(true, false, false)))
            .as("an actionable status").isTrue();
        assertThat(MembershipService.plannable(status(false, true, false)))
            .as("a status in progress").isTrue();
        assertThat(MembershipService.plannable(status(false, false, true)))
            .as("a closed status is refused").isFalse();
        assertThat(MembershipService.plannable(status(true, false, true)))
            .as("closed decides, whatever else the status answers").isFalse();
    }

    private static IterationMembership membership(String status) {
        IterationMembership membership = new IterationMembership();
        membership.status = status;
        return membership;
    }

    private static ItemStatus status(boolean actionable, boolean inProgress, boolean closed) {
        ItemStatus status = new ItemStatus();
        status.actionable = actionable;
        status.inProgress = inProgress;
        status.closed = closed;
        return status;
    }
}
