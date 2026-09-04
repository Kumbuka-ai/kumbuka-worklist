package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * What the planning verbs refuse, and what they answer when nothing is wrong.
 *
 * <h2>Why this is a class of its own</h2>
 *
 * {@code PlanningDomainIT} carries the guarantees — the aggregate token, the
 * derivation, the cardinality pair, the reorder — each with the trace an
 * absent mechanism would have left. This class carries the surface: every
 * typed refusal the layer can raise, and the ordinary reads beside them.
 *
 * <p>Kept apart because the two answer different questions. A refusal here
 * that stopped working would be a caller getting an internal error where a
 * typed one belongs; a guarantee there that stopped working would be a
 * silently wrong store. Mixing them would make the second harder to read for
 * the sake of the first.
 *
 * <h2>Every refusal has its admitted neighbour</h2>
 *
 * A verb that refused everything would satisfy each assertion below on its
 * own. So each case pairs the refusal with the call that must go through —
 * usually the same call with the one thing corrected.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class PlanningRefusalIT {

    @Inject ItemService items;
    @Inject VocabularyRegistry vocabulary;
    @Inject MilestoneService milestones;
    @Inject IterationService iterations;
    @Inject MembershipService memberships;
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
    }

    // ==================================================================
    // Opening a scope.
    // ==================================================================

    /**
     * A scope plans against a settings row, and planning without one is a
     * refusal that says so rather than a limit this service invented.
     */
    @Test
    void a_scope_with_no_settings_row_cannot_plan() {
        UUID unopened = UUID.randomUUID();

        WorklistException refusal = refusalFrom(() -> iterations.create(unopened, Map.of(
            "motto", "nowhere", "description", "a scope that was never opened")));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.SETTING_ABSENT);
        assertThat(refusal.offenders()).containsExactly(String.valueOf(unopened));

        assertThat(refusalFrom(() -> settings.read(unopened)).reason())
            .as("reading them is the same absence and the same refusal")
            .isEqualTo(WorklistException.Reason.SETTING_ABSENT);
    }

    /** One settings row per scope, and the second call says so. */
    @Test
    void a_scope_is_opened_once() {
        WorklistException refusal = refusalFrom(() -> settings.create(scope, Map.of(
            "max_planned_iterations", 1, "warn_planned_iterations", 1,
            "max_memberships_per_iteration", 1, "warn_memberships_per_iteration", 1)));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.SETTING_PRESENT);
        assertThat(settings.read(scope).get("max_planned_iterations"))
            .as("and the limits the scope is already working under are untouched")
            .isEqualTo(10);
    }

    /**
     * The four cardinality numbers are mandatory and positive.
     *
     * <p>V4 gives them no defaults on purpose, so this verb has none to fall
     * back on. A limit of zero forbids what the setting exists to bound.
     */
    @Test
    void the_four_cardinality_numbers_are_mandatory_and_positive() {
        assertThat(refusalFrom(() -> settings.create(UUID.randomUUID(), Map.of(
                "max_planned_iterations", 5, "warn_planned_iterations", 4,
                "max_memberships_per_iteration", 5))).reason())
            .as("a number left out is a refusal, not a default")
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);

        assertThat(refusalFrom(() -> settings.create(UUID.randomUUID(), Map.of(
                "max_planned_iterations", 0, "warn_planned_iterations", 1,
                "max_memberships_per_iteration", 1,
                "warn_memberships_per_iteration", 1))).offenders())
            .containsExactly("max_planned_iterations");

        assertThat(refusalFrom(() -> settings.update(scope, Map.of(
                "max_planned_iterations", "not a number",
                "conflict_token", settingToken()))).reason())
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);

        // The admitted neighbour, and the mode and column set beside it.
        UUID opened = UUID.randomUUID();
        Map<String, Object> answer = settings.create(opened, Map.of(
            "max_planned_iterations", 5, "warn_planned_iterations", 4,
            "max_memberships_per_iteration", 5, "warn_memberships_per_iteration", 4,
            "allocation_mode", ScopeSetting.SCOPE_WIDE,
            "default_columns", List.of("title", "status")));
        assertThat(answer.get("allocation_mode")).isEqualTo(ScopeSetting.SCOPE_WIDE);
        assertThat(answer.get("default_columns")).isEqualTo(List.of("title", "status"));
        assertThat(answer.get("current_iteration")).isNull();
    }

    /**
     * A settings write that changes nothing writes nothing, and a read answer
     * sent straight back is accepted rather than refused.
     */
    @Test
    void a_settings_write_round_trips_and_a_no_op_leaves_the_token() {
        Map<String, Object> read = settings.read(scope);
        String settled = (String) read.get("conflict_token");

        settings.update(scope, read);

        assertThat(settingToken())
            .as("RED STATE, by its trace: sending a whole read answer back must not "
                + "rotate the token, or every honest round trip would invalidate every "
                + "other reader")
            .isEqualTo(settled);

        settings.update(scope, Map.of("allocation_mode", ScopeSetting.SCOPE_WIDE,
            "conflict_token", settled));
        assertThat(settingToken())
            .as("and an effective change must rotate it, or the check above would be "
                + "satisfied by never rotating at all")
            .isNotEqualTo(settled);
    }

    /** An unknown allocation mode is refused against the platform's own two. */
    @Test
    void the_allocation_mode_is_one_of_the_platforms_two() {
        WorklistException refusal = refusalFrom(() -> settings.update(scope, Map.of(
            "allocation_mode", "by_the_phase_of_the_moon",
            "conflict_token", settingToken())));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly("allocation_mode");

        settings.update(scope, Map.of("allocation_mode", ScopeSetting.PER_SELECTOR,
            "conflict_token", settingToken()));
    }

    // ==================================================================
    // Addressing something that is not there.
    // ==================================================================

    @Test
    void an_unknown_address_is_a_typed_refusal_naming_the_id() {
        UUID nothing = UUID.randomUUID();

        assertThat(refusalFrom(() -> milestones.read(scope, nothing)).reason())
            .isEqualTo(WorklistException.Reason.MILESTONE_UNKNOWN);
        assertThat(refusalFrom(() -> iterations.read(scope, nothing)).reason())
            .isEqualTo(WorklistException.Reason.ITERATION_UNKNOWN);

        UUID iteration = iteration("addressing");
        assertThat(refusalFrom(() -> memberships.read(scope, iteration, nothing)).reason())
            .isEqualTo(WorklistException.Reason.MEMBERSHIP_UNKNOWN);

        // A milestone of ANOTHER scope is unknown here too, and that is the
        // half worth having: the row exists, and it is not this scope's.
        UUID elsewhere = UUID.randomUUID();
        settings.create(elsewhere, Map.of(
            "max_planned_iterations", 1, "warn_planned_iterations", 1,
            "max_memberships_per_iteration", 1, "warn_memberships_per_iteration", 1));
        UUID foreign = (UUID) milestones.create(elsewhere,
            Map.of("title", "another scope's goal")).get("id");
        assertThat(refusalFrom(() -> milestones.read(scope, foreign)).reason())
            .isEqualTo(WorklistException.Reason.MILESTONE_UNKNOWN);
    }

    /** An item may be in an iteration once. */
    @Test
    void an_item_is_planned_into_an_iteration_once() {
        UUID iteration = iteration("once");
        UUID item = onPath("planned twice");
        memberships.plan(scope, iteration, item, token(iteration));

        WorklistException refusal = refusalFrom(() ->
            memberships.plan(scope, iteration, item, token(iteration)));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.MEMBERSHIP_PRESENT);
        assertThat(refusal.offenders()).containsExactly(String.valueOf(item));

        // The admitted neighbour: the SAME item in a second iteration is
        // ordinary — an item worked across two iterations is the normal case.
        UUID next = iteration("and again");
        memberships.plan(scope, next, item, token(next));
    }

    // ==================================================================
    // The close, and what a closed iteration still admits.
    // ==================================================================

    /**
     * The close refuses while live memberships stand, and it NAMES them.
     *
     * <p>A refusal that only stated the rule would send the reader back to
     * the store to work out which rows it meant.
     */
    @Test
    void the_close_refuses_over_live_memberships_and_names_them() {
        UUID iteration = iteration("unfinished");
        UUID standing = onPath("still open");
        UUID finished = onPath("finished");
        memberships.plan(scope, iteration, standing, token(iteration));
        memberships.plan(scope, iteration, finished, token(iteration));
        memberships.update(scope, iteration, finished, Map.of(
            "membership_status", IterationMembership.DONE,
            "conflict_token", token(iteration)));

        WorklistException refusal = refusalFrom(() ->
            iterations.close(scope, iteration, token(iteration)));
        assertThat(refusal.reason())
            .isEqualTo(WorklistException.Reason.ITERATION_INCOMPLETE);
        assertThat(refusal.offenders())
            .as("exactly the live one, and not the one that is done")
            .containsExactly(String.valueOf(standing));

        // The admitted neighbour: every membership terminal, and the close
        // goes through.
        memberships.update(scope, iteration, standing, Map.of(
            "membership_status", IterationMembership.DROPPED,
            "conflict_token", token(iteration)));
        assertThat(iterations.close(scope, iteration, token(iteration)).get("closed_at"))
            .isNotNull();
    }

    /** A closed iteration takes no further writes, on itself or its memberships. */
    @Test
    void a_closed_iteration_takes_no_further_writes() {
        UUID iteration = iteration("finished");
        UUID item = onPath("was worked");
        memberships.plan(scope, iteration, item, token(iteration));
        memberships.update(scope, iteration, item, Map.of(
            "membership_status", IterationMembership.DONE,
            "conflict_token", token(iteration)));
        String last = token(iteration);
        iterations.close(scope, iteration, last);

        assertThat(refusalFrom(() -> iterations.update(scope, iteration, Map.of(
                "motto", "reopened", "conflict_token", token(iteration)))).reason())
            .isEqualTo(WorklistException.Reason.ITERATION_CLOSED);
        assertThat(refusalFrom(() ->
                iterations.close(scope, iteration, token(iteration))).reason())
            .as("and closing it twice is the same refusal")
            .isEqualTo(WorklistException.Reason.ITERATION_CLOSED);
        assertThat(refusalFrom(() -> memberships.plan(scope, iteration,
                onPath("too late"), token(iteration))).reason())
            .isEqualTo(WorklistException.Reason.ITERATION_CLOSED);

        assertThat(iterations.read(scope, iteration).get("order"))
            .as("what it held stays READABLE — that is what makes a closed iteration a "
                + "record of what was worked rather than a gap")
            .isEqualTo(List.of(item));
    }

    /** {@code advance} with nothing left to promote is a call to plan, and says so. */
    @Test
    void advance_with_no_further_open_iteration_says_so() {
        WorklistException empty = refusalFrom(() -> iterations.advance(scope, settingToken()));
        assertThat(empty.reason()).isEqualTo(WorklistException.Reason.ITERATION_ABSENT);

        // The admitted neighbour: one exists, and it is promoted.
        UUID only = iteration("the only one");
        assertThat(iterations.advance(scope, settingToken()).get("current_iteration"))
            .isEqualTo(only);

        assertThat(refusalFrom(() -> iterations.advance(scope, settingToken())).reason())
            .as("and once it is current there is nothing further to promote")
            .isEqualTo(WorklistException.Reason.ITERATION_ABSENT);
    }

    /**
     * Closing the current iteration clears the pointer at it.
     *
     * <p>A pointer that outlived what it points at would leave the draw with
     * somewhere to look and nothing to find — the empty answer the concept
     * insists must mean "plan" rather than "close".
     */
    @Test
    void closing_the_current_iteration_clears_the_pointer() {
        UUID iteration = iteration("current");
        iterations.advance(scope, settingToken());
        assertThat(settings.read(scope).get("current_iteration")).isEqualTo(iteration);

        iterations.close(scope, iteration, token(iteration));

        assertThat(settings.read(scope).get("current_iteration"))
            .as("RED STATE, by its trace: a pointer left behind would name a closed "
                + "iteration, and nothing downstream could tell that from a running one")
            .isNull();
    }

    // ==================================================================
    // The field catalogue, addressed.
    // ==================================================================

    /**
     * A field of another object is unknown here, and the refusal says which
     * object was addressed.
     *
     * <p>That is the case the addressed catalogue exists for: {@code motto} is
     * a real field of this service, and sending it to an item has to read as
     * "not on an item" rather than as a typo.
     */
    @Test
    void a_field_of_another_object_is_unknown_on_this_one() {
        WorklistException refusal = refusalFrom(() -> items.create(scope, Map.of(
            "title", "an item", "status", String.valueOf(openStatus),
            "motto", "which an item does not have")));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.UNKNOWN_FIELD);
        assertThat(refusal.offenders()).containsExactly("motto");
        assertThat(refusal.getMessage())
            .as("and the message names the object that was addressed, because the field "
                + "exists — just not here")
            .contains("no field of a item is named");

        assertThat(refusalFrom(() -> iterations.create(scope, Map.of(
                "motto", "fine", "description", "fine", "vision", "a milestone's"))).reason())
            .as("the same in the other direction: a milestone's field on an iteration")
            .isEqualTo(WorklistException.Reason.UNKNOWN_FIELD);
    }

    /**
     * A read-only field carrying a DIFFERENT value is refused; carrying the
     * one it already has, it is accepted.
     */
    @Test
    void a_read_only_field_may_be_echoed_and_not_changed() {
        UUID milestone = (UUID) milestones.create(scope, Map.of("title", "a goal")).get("id");
        Map<String, Object> read = milestones.read(scope, milestone);

        WorklistException refusal = refusalFrom(() -> {
            var tampered = new java.util.HashMap<>(read);
            tampered.put("number", 99L);
            milestones.update(scope, milestone, tampered);
        });
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.FIELD_NOT_SETTABLE);
        assertThat(refusal.offenders()).containsExactly("number");

        String settled = (String) read.get("conflict_token");
        milestones.update(scope, milestone, read);
        assertThat(milestoneToken(milestone))
            .as("the whole read answer sent back changes nothing and rotates nothing")
            .isEqualTo(settled);
    }

    // ==================================================================
    // The goal axis, read and closed.
    // ==================================================================

    /** The axis answers in its own order, markers and goals alike. */
    @Test
    void the_axis_answers_in_its_own_order_and_a_milestone_closes() {
        UUID second = (UUID) milestones.create(scope, Map.of(
            "title", "later", "rank", 2, "mission", "what it contains")).get("id");
        UUID first = (UUID) milestones.create(scope, Map.of(
            "title", "sooner", "rank", 1)).get("id");
        milestones.create(scope, Map.of(
            "title", "not assessed", "kind", Milestone.NOT_ASSESSED, "rank", 3));

        assertThat(milestones.query(scope).stream().map(m -> m.get("id")).toList())
            .as("rank orders the axis, and never the number or the alphabet")
            .containsExactly(first, second, milestones.query(scope).get(2).get("id"));

        Map<String, Object> closed = milestones.close(scope, first, milestoneToken(first));
        assertThat(closed.get("status")).isEqualTo(Milestone.CLOSED);
        assertThat(milestones.query(scope))
            .as("a closed milestone stays in the table, so every reference still resolves "
                + "and the allocator counts past it")
            .hasSize(3);
    }

    /** An unknown milestone status is refused against the platform's own three. */
    @Test
    void a_milestone_status_is_one_of_the_platforms_three() {
        UUID milestone = (UUID) milestones.create(scope, Map.of("title", "a goal")).get("id");

        assertThat(refusalFrom(() -> milestones.update(scope, milestone, Map.of(
                "status", "half done", "conflict_token", milestoneToken(milestone))))
                .offenders())
            .containsExactly("status");
        assertThat(refusalFrom(() -> milestones.update(scope, milestone, Map.of(
                "kind", "a kind nobody declared",
                "conflict_token", milestoneToken(milestone)))).offenders())
            .containsExactly("kind");

        milestones.update(scope, milestone, Map.of(
            "status", Milestone.ACTIVE, "vision", "the north star",
            "conflict_token", milestoneToken(milestone)));
        assertThat(milestones.read(scope, milestone).get("vision"))
            .isEqualTo("the north star");
    }

    /** A milestone and an iteration both need what their columns require. */
    @Test
    void the_mandatory_text_of_each_object_cannot_be_left_out_or_cleared() {
        assertThat(refusalFrom(() -> milestones.create(scope, Map.of("rank", 1))).offenders())
            .containsExactly("title");
        assertThat(refusalFrom(() -> iterations.create(scope,
                Map.of("description", "no motto"))).offenders())
            .containsExactly("motto");
        assertThat(refusalFrom(() -> iterations.create(scope,
                Map.of("motto", "no description"))).offenders())
            .containsExactly("description");

        UUID iteration = iteration("complete");
        assertThat(refusalFrom(() -> iterations.update(scope, iteration, Map.of(
                "motto", "  ", "conflict_token", token(iteration)))).offenders())
            .as("and what is mandatory cannot be cleared through an update either")
            .containsExactly("motto");

        Map<String, Object> moved = iterations.update(scope, iteration, Map.of(
            "motto", "renamed", "rank", 7, "conflict_token", token(iteration)));
        assertThat(moved.get("motto")).isEqualTo("renamed");
        assertThat(moved.get("rank")).isEqualTo(7);
    }

    // ==================================================================
    // Fixtures.
    // ==================================================================

    private UUID iteration(String motto) {
        return (UUID) iterations.create(scope, Map.of(
            "motto", motto, "description", "what " + motto + " contains")).get("id");
    }

    private String token(UUID iterationId) {
        return (String) iterations.read(scope, iterationId).get("conflict_token");
    }

    private String settingToken() {
        return (String) settings.read(scope).get("conflict_token");
    }

    private String milestoneToken(UUID milestoneId) {
        return (String) milestones.read(scope, milestoneId).get("conflict_token");
    }

    /** An actionable item carrying a real goal, which is what makes it plannable. */
    private UUID onPath(String title) {
        UUID item = (UUID) items.create(scope, Map.of(
            "title", title, "status", String.valueOf(openStatus))).get("id");
        UUID milestone = (UUID) milestones.create(scope, Map.of(
            "title", "a goal for " + title, "vision", "the north star")).get("id");
        return PlanningFixture.pointAtMilestone(item, milestone);
    }

    private static WorklistException refusalFrom(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown)
            .as("the call must be refused, and refused with this service's typed refusal "
                + "rather than with whatever the database or the ORM raised")
            .isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }
}
