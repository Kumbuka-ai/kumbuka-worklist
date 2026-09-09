package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reversal probe for V12 (2026-09-09).
 *
 * <h2>What is defended</h2>
 *
 * <p>V12 retracts the milestone-workstream edge. An item's milestone
 * is unconstrained by the item's workstream, and a milestone's
 * workstream is unconstrained by anything. Several workstreams reach
 * one milestone together, and that is the normal case
 * (TAR-0002 section 4, REQ-0148 obsolete).
 *
 * <h2>The probe runs the other way</h2>
 *
 * <p>Removing a check is not visible as a red state — after removal
 * nothing refuses. The nachweis therefore runs the other way: a test
 * that asserts an assignment that USED TO BE REFUSED now passes. Under
 * V11 and the pre-V12 Java, each test in this class would refuse with
 * {@link WorklistException.Reason#WORKSTREAM_MILESTONE_MISMATCH};
 * after the rollback, each passes.
 *
 * <p>The paired observation: run this suite in both states.
 * <ul>
 *   <li>Pre-rollback (Java invariants still in place): every test in this
 *       class throws {@code WorklistException} and the suite reports red.
 *   <li>Post-rollback (V12 + Java changes applied): every test in this
 *       class passes.
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class MilestoneWorkstreamDecouplingIT {

    @Inject ItemService items;
    @Inject MilestoneService milestones;
    @Inject WorkstreamService workstreams;
    @Inject VocabularyRegistry vocabulary;
    @Inject SelectorRegistry selectors;
    @Inject ScopeSettingService settings;

    private UUID scope;
    private UUID openStatus;

    @BeforeEach
    void aScopeOfItsOwn() {
        scope = UUID.randomUUID();
        openStatus = vocabulary.declareStatus(scope, "open", 1, true, false, false, false).id;
        settings.create(scope, Map.of(
            "max_planned_iterations", 10, "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10, "warn_memberships_per_iteration", 9));
        selectors.declare(scope, Selector.ITEM);
        selectors.declare(scope, Selector.MILESTONE);
    }

    @Test
    void assigning_a_milestone_from_another_workstream_now_passes() {
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");
        Workstream backend = workstreams.declare(scope, "backend", "backend stream");

        UUID itemId = createItem("cross-ws item", mobile.id);
        UUID milestoneInBackend = createMilestone("backend goal", backend.id);

        // Pre-rollback: this update refused with WORKSTREAM_MILESTONE_MISMATCH.
        // Post-rollback (V12 + Java changes): the assignment lands.
        Map<String, Object> updated = items.update(scope, itemId, Map.of(
            Field.MILESTONE_ID.canonicalName(), milestoneInBackend.toString(),
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(itemId)));

        assertThat(updated.get(Field.MILESTONE_ID.canonicalName()))
            .as("cross-workstream milestone now attaches — the edge is retracted")
            .isEqualTo(milestoneInBackend);
    }

    @Test
    void moving_the_item_to_a_workstream_the_milestone_is_not_in_now_passes() {
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");
        Workstream backend = workstreams.declare(scope, "backend", "backend stream");

        UUID itemId = createItem("consistent then moved", mobile.id);
        UUID milestoneInMobile = createMilestone("mobile goal", mobile.id);
        items.update(scope, itemId, Map.of(
            Field.MILESTONE_ID.canonicalName(), milestoneInMobile.toString(),
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(itemId)));

        // Pre-rollback: the second update refused with WORKSTREAM_MILESTONE_MISMATCH.
        // Post-rollback: the workstream moves; the milestone stays where it is
        // and continues to be reached by the item.
        Map<String, Object> moved = items.update(scope, itemId, Map.of(
            Field.WORKSTREAM_ID.canonicalName(), backend.id.toString(),
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(itemId)));

        assertThat(moved.get(Field.WORKSTREAM_ID.canonicalName()))
            .as("the item moved to backend — the milestone-workstream edge is gone")
            .isEqualTo(backend.id);
        assertThat(moved.get(Field.MILESTONE_ID.canonicalName()))
            .as("the milestone still reaches the item across the boundary")
            .isEqualTo(milestoneInMobile);
    }

    @Test
    void milestone_update_workstream_is_a_noop_and_does_not_refuse() {
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");
        Workstream backend = workstreams.declare(scope, "backend", "backend stream");

        Map<String, Object> created = milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), "settled milestone",
            Field.VISION.canonicalName(), "vision",
            Field.WORKSTREAM_ID.canonicalName(), mobile.id.toString()));
        UUID milestoneId = (UUID) created.get(Field.ID.canonicalName());
        String token = (String) created.get(Field.CONFLICT_TOKEN.canonicalName());

        // Pre-rollback: this update refused with WORKSTREAM_MILESTONE_MISMATCH
        // ("a milestone's workstream is set at create and is not moved after").
        // Post-rollback: the column is dead, and the write is accepted or
        // echoed. The row's number is scope-wide now, so moving the workstream
        // does not detach any number from its axis.
        Map<String, Object> updated = milestones.update(scope, milestoneId, Map.of(
            Field.WORKSTREAM_ID.canonicalName(), backend.id.toString(),
            Field.CONFLICT_TOKEN.canonicalName(), token));

        assertThat(updated)
            .as("no refusal — a milestone's workstream is no longer an invariant")
            .isNotEmpty();
    }

    @Test
    void milestones_in_different_workstreams_share_the_scope_wide_number_line() {
        // Pre-rollback: the counter ran per-workstream and both got 1.
        // Post-rollback: the counter is scope-wide, so the two are 1 and 2.
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");
        Workstream backend = workstreams.declare(scope, "backend", "backend stream");

        Long firstInMobile = (Long) milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), "mobile goal 1",
            Field.VISION.canonicalName(), "first mobile star",
            Field.WORKSTREAM_ID.canonicalName(), mobile.id.toString()))
            .get(Field.NUMBER.canonicalName());

        Long firstInBackend = (Long) milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), "backend goal 1",
            Field.VISION.canonicalName(), "first backend star",
            Field.WORKSTREAM_ID.canonicalName(), backend.id.toString()))
            .get(Field.NUMBER.canonicalName());

        assertThat(firstInMobile).isEqualTo(1L);
        assertThat(firstInBackend)
            .as("scope-wide counter: the second milestone gets 2 regardless of workstream")
            .isEqualTo(2L);
    }

    // ==================================================================
    // Planting.
    // ==================================================================

    private UUID createItem(String title, UUID workstreamId) {
        return (UUID) items.create(scope, Map.of(
            Field.TITLE.canonicalName(), title,
            Field.STATUS.canonicalName(), openStatus.toString(),
            Field.WORKSTREAM_ID.canonicalName(), workstreamId.toString()))
            .get(Field.ID.canonicalName());
    }

    private UUID createMilestone(String title, UUID workstreamId) {
        return (UUID) milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), title,
            Field.VISION.canonicalName(), "vision of " + title,
            Field.WORKSTREAM_ID.canonicalName(), workstreamId.toString()))
            .get(Field.ID.canonicalName());
    }

    private String tokenOf(UUID itemId) {
        return (String) items.read(scope, itemId).get(Field.CONFLICT_TOKEN.canonicalName());
    }
}
