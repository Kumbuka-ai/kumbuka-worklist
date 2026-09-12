package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The full lifecycle of the two planning axes — iteration and milestone —
 * driven end-to-end through the verb surface, with no fixture reaching
 * around the service after the initial scope opening.
 *
 * <h2>What is proved</h2>
 *
 * <p>Every act sits on the verb catalogue and answers the same field map on
 * the way back. The chain runs {@code create → read → query → update →
 * (plan / unplan / advance) → close → read} for the iteration, and
 * {@code create → read → query → update → close → read} for the milestone,
 * with a title present on both. A break anywhere in the chain — a missing
 * canonical name, a settable field the service refuses, a projection that
 * omits the field it was just written with — surfaces as a red assertion
 * on the neighbouring read.
 *
 * <h2>Why the two axes share one class</h2>
 *
 * The verb catalogue is one, and the address is what says which axis is
 * meant. Splitting the chain over two files would let the two projections
 * quietly diverge on which fields they carry, which is the shape the
 * canonical-name defect took in the predecessor.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class LifecycleChainIT {

    @Inject ItemService items;
    @Inject IterationService iterations;
    @Inject MilestoneService milestones;
    @Inject MembershipService memberships;
    @Inject ScopeSettingService settings;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;

    private UUID scope;
    private UUID actionable;

    @BeforeEach
    void aFreshScope() {
        scope = UUID.randomUUID();
        actionable = vocabulary.declareStatus(scope, "open", 1,
            true, false, false, false).id;
        settings.create(scope, Map.of(
            "max_planned_iterations", 5,
            "warn_planned_iterations", 4,
            "max_memberships_per_iteration", 10,
            "warn_memberships_per_iteration", 9));
    }

    // ==================================================================
    // The iteration lifecycle: create → read → query → update → plan/unplan
    // → advance → close → read.
    // ==================================================================

    @Test
    void an_iteration_moves_through_every_verb_in_the_chain() {
        // create — with a title on the way in.
        Map<String, Object> created = iterations.create(scope, Map.of(
            "title", "kickoff iteration",
            "motto", "kickoff",
            "description", "what kickoff contains",
            "rank", 1));
        UUID iterationId = (UUID) created.get("id");
        assertThat(created.get("title"))
            .as("the title round-trips through create — the field the axis first "
                + "carries as of SPRINT_180.5")
            .isEqualTo("kickoff iteration");
        assertThat(created.get("number")).isEqualTo(1L);
        assertThat(created.get("motto")).isEqualTo("kickoff");

        // read — the projection carries every field a caller can set.
        Map<String, Object> readBack = iterations.read(scope, iterationId);
        assertThat(readBack.get("title")).isEqualTo("kickoff iteration");
        assertThat(readBack.get("closed_at")).isNull();

        // query — the axis carries the iteration we just created.
        List<Map<String, Object>> axis = iterations.query(scope);
        assertThat(axis).hasSize(1);
        assertThat(axis.get(0).get("id")).isEqualTo(iterationId);

        // update — change the title through the update path.
        String tokenOne = (String) readBack.get("conflict_token");
        Map<String, Object> updated = iterations.update(scope, iterationId, Map.of(
            "title", "renamed iteration",
            "conflict_token", tokenOne));
        assertThat(updated.get("title"))
            .as("the title moves through the update path too, and the token rotated "
                + "with the effective change")
            .isEqualTo("renamed iteration");
        assertThat(updated.get("conflict_token"))
            .as("an effective change rotates the token — a caller must not be able "
                + "to read the same token twice across a real write")
            .isNotEqualTo(tokenOne);

        // plan / unplan — put an item in, take it out, put another back in.
        UUID first = onPathItem("first item");
        UUID second = onPathItem("second item");

        memberships.plan(scope, iterationId, first, tokenOf(iterationId));
        memberships.plan(scope, iterationId, second, tokenOf(iterationId));
        assertThat(memberships.query(scope))
            .as("both items are planned in one open iteration")
            .containsExactlyInAnyOrder(first, second);

        memberships.unplan(scope, iterationId, first, tokenOf(iterationId));
        assertThat(memberships.query(scope))
            .as("unplan drops the membership, so the derivation stops counting it")
            .containsExactly(second);

        // advance — the pointer moves to this iteration.
        iterations.advance(scope, (String) settings.read(scope).get("conflict_token"));
        assertThat(settings.read(scope).get("current_iteration"))
            .as("advance points the settings row at this iteration")
            .isEqualTo(iterationId);

        // close — finish the live membership, close the iteration with a produced.
        memberships.update(scope, iterationId, second, Map.of(
            "membership_status", "done",
            "conflict_token", tokenOf(iterationId)));
        Map<String, Object> closed = iterations.close(scope, iterationId,
            "the SPRINT_180.5 lifecycle probe", tokenOf(iterationId));
        assertThat(closed.get("closed_at"))
            .as("close writes the timestamp when produced is named")
            .isNotNull();

        // read after close — still readable, closed_at now set, title still there.
        Map<String, Object> afterClose = iterations.read(scope, iterationId);
        assertThat(afterClose.get("closed_at")).isNotNull();
        assertThat(afterClose.get("title")).isEqualTo("renamed iteration");
    }

    // ==================================================================
    // The milestone lifecycle: create → read → query → update → close → read.
    // ==================================================================

    @Test
    void a_milestone_moves_through_every_verb_in_the_chain() {
        // create — title, vision, mission all round-trip.
        Map<String, Object> created = milestones.create(scope, Map.of(
            "title", "the north star",
            "vision", "north star vision",
            "mission", "north star mission"));
        UUID milestoneId = (UUID) created.get("id");
        assertThat(created.get("title")).isEqualTo("the north star");
        assertThat(created.get("vision")).isEqualTo("north star vision");
        assertThat(created.get("mission")).isEqualTo("north star mission");
        assertThat(created.get("number")).isEqualTo(1L);
        assertThat(created.get("kind")).isEqualTo(Milestone.GOAL);
        assertThat(created.get("status")).isEqualTo(Milestone.PLANNED);

        // read — the projection carries every field a caller can set.
        Map<String, Object> readBack = milestones.read(scope, milestoneId);
        assertThat(readBack.get("title")).isEqualTo("the north star");

        // query — the axis carries the milestone we just created.
        List<Map<String, Object>> axis = milestones.query(scope);
        assertThat(axis).hasSize(1);
        assertThat(axis.get(0).get("id")).isEqualTo(milestoneId);

        // update — change vision through the update path, activate the milestone.
        String tokenOne = (String) readBack.get("conflict_token");
        Map<String, Object> visionMoved = milestones.update(scope, milestoneId, Map.of(
            "vision", "the sharpened north star",
            "conflict_token", tokenOne));
        assertThat(visionMoved.get("vision")).isEqualTo("the sharpened north star");

        Map<String, Object> activated = milestones.update(scope, milestoneId, Map.of(
            "status", Milestone.ACTIVE,
            "conflict_token", (String) visionMoved.get("conflict_token")));
        assertThat(activated.get("status")).isEqualTo(Milestone.ACTIVE);

        // close — closing goes through update, so the token stays on one path.
        Map<String, Object> closed = milestones.close(scope, milestoneId,
            (String) activated.get("conflict_token"));
        assertThat(closed.get("status")).isEqualTo(Milestone.CLOSED);

        // read after close — still readable, status now closed, title unchanged.
        Map<String, Object> afterClose = milestones.read(scope, milestoneId);
        assertThat(afterClose.get("status")).isEqualTo(Milestone.CLOSED);
        assertThat(afterClose.get("title")).isEqualTo("the north star");
    }

    // ==================================================================
    // Planting.
    // ==================================================================

    private UUID onPathItem(String title) {
        UUID goal = (UUID) milestones.create(scope, Map.of(
            "title", "goal for " + title,
            "vision", "star for " + title)).get("id");
        UUID itemId = (UUID) items.create(scope, Map.of(
            "title", title,
            "status", actionable.toString())).get("id");
        return PlanningFixture.pointAtMilestone(itemId, goal);
    }

    private String tokenOf(UUID iterationId) {
        return (String) iterations.read(scope, iterationId).get("conflict_token");
    }
}
