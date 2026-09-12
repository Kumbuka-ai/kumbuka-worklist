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
 * <strong>The full lifecycle of a milestone and an iteration is reachable
 * over the verb surface, with no write outside it.</strong>
 *
 * <p>Every act between the first {@code declare} and the closing of the
 * milestone runs through a service method. The suite injects NO
 * {@code Db}, opens NO JDBC connection, calls NO helper that goes around a
 * verb; the guard {@link #the_test_class_writes_nothing_through_jdbc} makes
 * that non-negotiable rather than a habit.
 *
 * <h2>What the chain covers</h2>
 *
 * <ol>
 *   <li>Declare the three selectors and open the scope's settings row —
 *       the two acts that make a fresh scope carry addresses.</li>
 *   <li>Declare an actionable status and a terminal one, so an item can be
 *       moved through both. A membership needs its item to be actionable
 *       to enter an iteration.</li>
 *   <li>Create a milestone, read it back, and activate it through
 *       {@code milestone.update} — the axis is on the product path.</li>
 *   <li>Create an iteration, read it back, and assert its title,
 *       motto and description survive the round trip.</li>
 *   <li>Create an item, and assign it to the milestone through
 *       <strong>{@code item.update} against the milestone's number</strong>
 *       — the SPRINT_180.5 fixture reached past this verb over JDBC, and
 *       the whole point of this suite is that it does not.</li>
 *   <li>Plan the item into the iteration through {@code membership.plan}.</li>
 *   <li>Advance the iteration to current through
 *       {@code iteration.advance}, so the settings' pointer moves.</li>
 *   <li>Update the membership status to {@code done}, so the iteration can
 *       close over it without carrying a live membership.</li>
 *   <li>Close the iteration with a naming of what was produced.</li>
 *   <li>Close the milestone.</li>
 * </ol>
 *
 * <h2>What this suite deliberately does not do</h2>
 *
 * It does not exhaust every refusal — those are covered in
 * {@link PlanningRefusalIT}, {@link IterationProducedCloseIT} and elsewhere.
 * Its subject is <em>reachability</em>: that the whole lifecycle stands as
 * one working path, without a fixture that reaches around a verb.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class LifecycleChainIT {

    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject ScopeSettingService settings;
    @Inject MilestoneService milestones;
    @Inject IterationService iterations;
    @Inject ItemService items;
    @Inject MembershipService memberships;

    private UUID scope;
    private ItemStatus actionable;

    @BeforeEach
    void aScopeOfItsOwn() {
        scope = UUID.randomUUID();
        // Selectors — the two axes and the item view. Declaring one twice is
        // idempotent, so a fresh scope opens its address spaces here.
        selectors.declare(scope, Selector.ITEM);
        selectors.declare(scope, Selector.MILESTONE);
        selectors.declare(scope, Selector.ITERATION);

        // Cardinality limits are the scope's own — the service admits or
        // refuses against these numbers, so the test writes them.
        settings.create(scope, Map.of(
            "max_planned_iterations", 10,
            "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10,
            "warn_memberships_per_iteration", 9));

        // Two statuses: one an item can enter an iteration under, one it
        // can leave with. Both are declared through the vocabulary verb;
        // membership.plan reads the actionable predicate the declaration
        // carries.
        actionable = vocabulary.declareStatus(scope, "open", 10,
            true, false, false, false);
        vocabulary.declareStatus(scope, "done", 30,
            false, false, true, true);
    }

    @Test
    void the_full_lifecycle_runs_over_the_verb_surface_with_no_write_outside_it() {
        // 1. A milestone, created and read back.
        Map<String, Object> milestoneAnswer = milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), "the beta gate",
            Field.KIND.canonicalName(), Milestone.GOAL,
            Field.VISION.canonicalName(), "the product is usable by a first customer",
            Field.MISSION.canonicalName(),
                "close the last five things a beta reader trips over first"));
        UUID milestoneId = (UUID) milestoneAnswer.get(Field.ID.canonicalName());
        Long milestoneNumber = (Long) milestoneAnswer.get(Field.NUMBER.canonicalName());
        assertThat(milestoneNumber).isGreaterThan(0L);
        assertThat(milestones.read(scope, milestoneId))
            .as("the read answer carries the fields the write set")
            .containsEntry(Field.TITLE.canonicalName(), "the beta gate")
            .containsEntry(Field.KIND.canonicalName(), Milestone.GOAL)
            .containsEntry(Field.STATUS.canonicalName(), Milestone.PLANNED);

        // 2. Activate the milestone.
        String milestoneToken = tokenOf(milestones.read(scope, milestoneId));
        Map<String, Object> activated = milestones.update(scope, milestoneId, Map.of(
            Field.STATUS.canonicalName(), Milestone.ACTIVE,
            Field.CONFLICT_TOKEN.canonicalName(), milestoneToken));
        assertThat(activated.get(Field.STATUS.canonicalName())).isEqualTo(Milestone.ACTIVE);

        // 3. An iteration — with a title beside the motto and the description.
        Map<String, Object> iterationAnswer = iterations.create(scope, Map.of(
            Field.TITLE.canonicalName(), "week 34",
            Field.MOTTO.canonicalName(), "close the beta gate",
            Field.DESCRIPTION.canonicalName(),
                "the five items behind the beta gate; unrelated work stays out"));
        UUID iterationId = (UUID) iterationAnswer.get(Field.ID.canonicalName());
        assertThat(iterationAnswer)
            .as("the iteration read carries the title alongside the motto")
            .containsEntry(Field.TITLE.canonicalName(), "week 34")
            .containsEntry(Field.MOTTO.canonicalName(), "close the beta gate");
        assertThat(iterations.read(scope, iterationId).get(Field.TITLE.canonicalName()))
            .isEqualTo("week 34");

        // 4. An item, created through the verb surface with the actionable
        //    status, then pointed at the milestone THROUGH item.update — the
        //    thing the SPRINT_180.5 fixture reached past the surface for.
        Map<String, Object> itemAnswer = items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "close the last beta reader thread",
            Field.STATUS.canonicalName(), actionable.name));
        UUID itemId = (UUID) itemAnswer.get(Field.ID.canonicalName());
        String itemToken = (String) itemAnswer.get(Field.CONFLICT_TOKEN.canonicalName());

        Map<String, Object> assigned = items.update(scope, itemId, Map.of(
            Field.MILESTONE_ID.canonicalName(), milestoneNumber,
            Field.CONFLICT_TOKEN.canonicalName(), itemToken));
        assertThat(assigned.get(Field.MILESTONE_ID.canonicalName()))
            .as("item.update carries the milestone assignment through the verb "
                + "surface — SPRINT_180.4 fixed the wire form to the number, so this "
                + "call is what the caller writes and no JDBC is needed for it")
            .isEqualTo(milestoneNumber);

        // 5. Plan the item into the iteration.
        Map<String, Object> planned = memberships.plan(scope, iterationId, itemId,
            tokenOf(iterations.read(scope, iterationId)));
        assertThat(planned.get(Field.MEMBERSHIP_STATUS.canonicalName()))
            .isEqualTo(IterationMembership.TODO);

        // 6. Advance the iteration to current.
        Map<String, Object> settingsBefore = settings.read(scope);
        String settingsToken = (String) settingsBefore.get(Field.CONFLICT_TOKEN.canonicalName());
        Map<String, Object> advanced = iterations.advance(scope, settingsToken);
        assertThat(advanced.get(Field.CURRENT_ITERATION.canonicalName()))
            .as("advance points the settings at the iteration through the verb "
                + "surface — no side channel, no manual pointer write")
            .isNotNull();

        // 7. Move the membership through active and on to done.
        String liveToken = tokenOf(iterations.read(scope, iterationId));
        Map<String, Object> madeActive = memberships.update(scope, iterationId, itemId, Map.of(
            Field.MEMBERSHIP_STATUS.canonicalName(), IterationMembership.ACTIVE,
            Field.CONFLICT_TOKEN.canonicalName(), liveToken));
        assertThat(madeActive.get(Field.MEMBERSHIP_STATUS.canonicalName()))
            .isEqualTo(IterationMembership.ACTIVE);

        String activeToken = tokenOf(iterations.read(scope, iterationId));
        Map<String, Object> completed = memberships.update(scope, iterationId, itemId, Map.of(
            Field.MEMBERSHIP_STATUS.canonicalName(), IterationMembership.DONE,
            Field.CONFLICT_TOKEN.canonicalName(), activeToken));
        assertThat(completed.get(Field.MEMBERSHIP_STATUS.canonicalName()))
            .isEqualTo(IterationMembership.DONE);

        // 8. Close the iteration, naming what was produced.
        String beforeClose = tokenOf(iterations.read(scope, iterationId));
        Map<String, Object> closedIteration = iterations.close(scope, iterationId,
            "the beta reader thread is closed", beforeClose);
        assertThat(closedIteration.get(Field.CLOSED_AT.canonicalName()))
            .as("iteration.close writes the timestamp when a produced-name is given")
            .isNotNull();

        // 9. Close the milestone.
        Map<String, Object> milestoneNow = milestones.read(scope, milestoneId);
        Map<String, Object> closedMilestone = milestones.close(scope, milestoneId,
            (String) milestoneNow.get(Field.CONFLICT_TOKEN.canonicalName()));
        assertThat(closedMilestone.get(Field.STATUS.canonicalName())).isEqualTo(Milestone.CLOSED);

        // 10. The closed iteration stays readable — a closed iteration is a
        //     record, not a gap. Its own read succeeds and reports the
        //     timestamp; membership reads through the axis's write path are
        //     the one thing that refuses, and correctly so.
        Map<String, Object> after = iterations.read(scope, iterationId);
        assertThat(after.get(Field.CLOSED_AT.canonicalName())).isNotNull();
        assertThat(after.get(Field.TITLE.canonicalName())).isEqualTo("week 34");
    }

    // ==================================================================
    // The guard against reaching past the verb surface.
    //
    // The point of this suite is that the whole chain closes through
    // verbs. A fixture that plants a milestone assignment over JDBC would
    // satisfy every assertion above while defeating the reason the file
    // exists. Reading the source of this class and asserting no
    // {@code java.sql} import survives is the mechanism that makes it
    // stay that way.
    // ==================================================================

    @Test
    void the_test_class_writes_nothing_through_jdbc() throws Exception {
        java.nio.file.Path source = java.nio.file.Path.of(
            "src/test/java/ai/kumbuka/worklist/domain/LifecycleChainIT.java");
        assertThat(source).exists();

        // Only the imports declared at the top of the file are checked.
        // Scanning the WHOLE source for the strings would flag them in this
        // very block, which is a self-defeating shape rather than a guard.
        java.util.regex.Pattern importLine =
            java.util.regex.Pattern.compile("^import\\s+([^;]+);", java.util.regex.Pattern.MULTILINE);
        String content = java.nio.file.Files.readString(source);
        java.util.regex.Matcher m = importLine.matcher(content);
        List<String> imports = new java.util.ArrayList<>();
        while (m.find()) {
            imports.add(m.group(1).trim());
        }
        assertThat(imports)
            .as("no fixture that reaches past the verb surface — the SPRINT_180.5 shape "
                + "this suite is here to keep out")
            .noneMatch(i -> i.startsWith("java.sql"))
            .noneMatch(i -> i.startsWith("javax.sql"))
            .noneMatch(i -> i.contains("worklist.tenancy.Db"))
            .noneMatch(i -> i.endsWith(".PlanningFixture"));
    }

    // ==================================================================
    // Helpers.
    // ==================================================================

    private static String tokenOf(Map<String, Object> answer) {
        return (String) answer.get(Field.CONFLICT_TOKEN.canonicalName());
    }
}
