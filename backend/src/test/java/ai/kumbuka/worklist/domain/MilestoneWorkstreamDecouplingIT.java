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
 *
 * <h2>What moved out of here on 2026-09-28</h2>
 *
 * <p>Four tests in this class wrote {@code workstream} on a milestone to show
 * that the write no longer refused. That was the right assertion against V12
 * and the wrong one about the surface: the field was still answered and still
 * taken, and a write on it landed on a dead column without saying so.
 * The field is now off the milestone in both directions, so the four are
 * replaced by {@code MilestoneWorkstreamRetractionIT}, which asserts the
 * refusal instead of the acceptance.
 *
 * <p>What stays here is the half that is about the ITEM: a milestone and an
 * item may sit in unrelated workstreams, and that is the invariant V12
 * retracted. It is not observable through the milestone's own field at all,
 * which is part of why the field had nothing to say.
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

    /**
     * An item in one workstream aims at a milestone, and nothing objects.
     *
     * <p>Pre-rollback this update refused with
     * {@code WORKSTREAM_MILESTONE_MISMATCH}, because the milestone sat in a
     * workstream of its own and the two had to agree. The milestone sits in
     * none now, so there is nothing for the item's workstream to disagree with
     * — which is the retraction stated from the item's side, and the only side
     * it is observable from.
     */
    @Test
    void an_item_in_a_workstream_aims_at_a_milestone_that_is_in_none() {
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");

        UUID itemId = createItem("cross-ws item", mobile.id);
        UUID goal = createMilestone("a goal several streams reach");
        Object goalNumber = milestones.read(scope, goal).get(Field.NUMBER.canonicalName());

        Map<String, Object> updated = items.update(scope, itemId, Map.of(
            Field.MILESTONE_ID.canonicalName(), goalNumber,
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(itemId)));

        assertThat(updated.get(Field.MILESTONE_ID.canonicalName()))
            .as("the milestone attaches — the edge that would have vetoed it is retracted")
            .isEqualTo(goalNumber);
    }

    /**
     * Moving the item between workstreams does not detach its milestone.
     *
     * <p>Pre-rollback the second update refused: the item was leaving the
     * workstream the milestone belonged to. Post-rollback the workstream moves
     * and the milestone stays, because the two axes hang independently on the
     * item.
     */
    @Test
    void moving_the_item_to_another_workstream_keeps_its_milestone() {
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");
        Workstream backend = workstreams.declare(scope, "backend", "backend stream");

        UUID itemId = createItem("consistent then moved", mobile.id);
        UUID goal = createMilestone("a goal that survives the move");
        Object goalNumber = milestones.read(scope, goal).get(Field.NUMBER.canonicalName());
        items.update(scope, itemId, Map.of(
            Field.MILESTONE_ID.canonicalName(), goalNumber,
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(itemId)));

        Map<String, Object> moved = items.update(scope, itemId, Map.of(
            Field.WORKSTREAM_ID.canonicalName(), backend.token,
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(itemId)));

        assertThat(moved.get(Field.WORKSTREAM_ID.canonicalName()))
            .as("the item moved to backend — the milestone-workstream edge is gone")
            .isEqualTo(backend.token);
        assertThat(moved.get(Field.MILESTONE_ID.canonicalName()))
            .as("the milestone still reaches the item across the boundary")
            .isEqualTo(goalNumber);
    }

    // ==================================================================
    // Planting.
    // ==================================================================

    private UUID createItem(String title, UUID workstreamId) {
        return (UUID) items.create(scope, Map.of(
            Field.TITLE.canonicalName(), title,
            Field.STATUS.canonicalName(), vocabulary.requireStatus(scope, openStatus).name,
            Field.WORKSTREAM_ID.canonicalName(),
            workstreams.require(scope, workstreamId).token))
            .get(Field.ID.canonicalName());
    }

    /**
     * A milestone, named by nothing but its title.
     *
     * <p>It takes no workstream because a milestone carries none — which is
     * exactly what the two tests above are about: the item they are attached
     * to sits in one, and the milestone sits in none, and the two are
     * therefore never in conflict.
     */
    private UUID createMilestone(String title) {
        return (UUID) milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), title,
            Field.VISION.canonicalName(), "vision of " + title))
            .get(Field.ID.canonicalName());
    }

    private String tokenOf(UUID itemId) {
        return (String) items.read(scope, itemId).get(Field.CONFLICT_TOKEN.canonicalName());
    }
}
