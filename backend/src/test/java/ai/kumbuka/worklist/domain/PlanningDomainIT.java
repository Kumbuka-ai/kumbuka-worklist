package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.Db;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The guarantees of the planning layer, each with the state it would be in if
 * the guarantee were absent, observed in the same run.
 *
 * <h2>Every probe carries its red half AND its legitimate half</h2>
 *
 * A gate that refused everything would satisfy each green assertion below
 * while expressing nothing — which is why the dispatch for this work asks for
 * both halves by name. So each case here does two things: it establishes what
 * an absent mechanism would have left behind and asserts that it did NOT
 * happen, and it performs the neighbouring act that MUST go through.
 *
 * <p>The traces are what is asserted, because a trace is what an absent
 * mechanism actually looks like. A conflict check that was skipped leaves the
 * losing writer's row behind; a cardinality check that was skipped leaves a
 * membership behind; a derivation shortened to "has a membership" answers a
 * closed iteration's item as planned.
 *
 * <h2>A scope per method</h2>
 *
 * The suite shares one database and most of what is asserted below is a count
 * within a scope — open iterations, memberships of an iteration, planned
 * items. A fixed scope would make each case depend on which ran before it,
 * and the failure would read as a broken limit rather than as a shared
 * fixture.
 *
 * <h2>One fixture goes around the service, and that is a finding</h2>
 *
 * {@link #onPath} writes {@code item.milestone_id} over JDBC, because
 * <strong>no verb of this service assigns it</strong>: the field is not
 * settable on an item and no planning verb addresses an item. The
 * precondition it satisfies — an item enters an iteration only when it
 * carries a milestone on the product path — is the concept's and is built.
 * The way to satisfy it through the service is missing, and is reported
 * rather than invented here.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class PlanningDomainIT {

    @Inject ItemService items;
    @Inject VocabularyRegistry vocabulary;
    @Inject MilestoneService milestones;
    @Inject IterationService iterations;
    @Inject MembershipService memberships;
    @Inject ScopeSettingService settings;

    private UUID scope;
    private UUID actionableStatus;
    private UUID restingStatus;

    /** The tenant the ORM binds, read from the same setting the service runs on. */
    private static UUID boundTenant() {
        return UUID.fromString(
            ConfigProvider.getConfig().getValue("worklist.tenant-id", String.class));
    }

    @BeforeEach
    void aScopeOfItsOwn() {
        scope = UUID.randomUUID();
        actionableStatus = vocabulary.declareStatus(scope, "open", 1,
            true, false, false, false).id;
        restingStatus = vocabulary.declareStatus(scope, "on hold", 2,
            false, false, false, false).id;
        settings.create(scope, Map.of(
            "max_planned_iterations", 10,
            "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10,
            "warn_memberships_per_iteration", 9));
    }

    // ==================================================================
    // Probe 1 — the conflict token of the AGGREGATE.
    // ==================================================================

    /**
     * A write on a membership presents the ITERATION's token and rotates it.
     *
     * <p>The membership owns no token: it is addressed at its own address and
     * belongs to the iteration's aggregate. So the second write below is
     * refused not because the membership moved but because the iteration did
     * — which is the whole point of locking per aggregate, and is what makes
     * a reorder of twelve rows present one token instead of twelve.
     */
    @Test
    void a_write_on_a_membership_presents_the_iterations_token_and_rotates_it() {
        UUID iteration = iterationId(created("first", 1));
        UUID one = onPath(item("planned one"));
        UUID two = onPath(item("planned two"));

        String beforeAnyPlan = token(iteration);
        Map<String, Object> planned = memberships.plan(scope, iteration, one, beforeAnyPlan);

        assertThat(planned.get("conflict_token"))
            .as("the answer carries the ITERATION's token, because that is the aggregate "
                + "a caller has to present next time")
            .isEqualTo(token(iteration))
            .isNotEqualTo(beforeAnyPlan);

        WorklistException refusal = refusalFrom(() ->
            memberships.plan(scope, iteration, two, beforeAnyPlan));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.CONFLICT);
        assertThat(refusal.offenders())
            .as("the refusal carries the CURRENT token, so a caller can re-read, "
                + "re-apply and retry without a round trip whose only purpose is to find "
                + "out what it should have sent")
            .containsExactly(token(iteration));

        // What the absent rotation would have left behind: the second item in
        // the iteration, planned under a token that was already spent.
        assertThat(memberships.query(scope))
            .as("RED STATE, by its trace: with the rotation removed from the membership "
                + "write path, the second plan would have gone through on the stale token "
                + "and this would hold two items")
            .containsExactly(one);

        // And the legitimate neighbour: the same write, on the current token,
        // must go through.
        memberships.plan(scope, iteration, two, token(iteration));
        assertThat(memberships.query(scope))
            .as("a plan carrying the token it just read is the normal case and must be "
                + "admitted — a check that refused it would satisfy the assertion above "
                + "by refusing everything")
            .containsExactlyInAnyOrder(one, two);
    }

    /**
     * A write that changes nothing writes nothing: no timestamp, no rotated
     * token, no statement.
     *
     * <p>The same rule the item domain measured, and it holds on the
     * aggregate's token here. Without it a caller who re-sent a read answer
     * would invalidate every other reader's view of the iteration for a call
     * that moved no row.
     */
    @Test
    void a_membership_write_that_changes_nothing_leaves_the_token_where_it_is() {
        UUID iteration = iterationId(created("second", 1));
        UUID item = onPath(item("unchanged"));
        memberships.plan(scope, iteration, item, token(iteration));

        String settled = token(iteration);
        Map<String, Object> answer = memberships.update(scope, iteration, item, Map.of(
            "membership_status", "todo",
            "conflict_token", settled));

        assertThat(answer.get("membership_status")).isEqualTo("todo");
        assertThat(token(iteration))
            .as("RED STATE, by its trace: without the comparison, this write would have "
                + "rotated the aggregate's token and every other reader of the iteration "
                + "would be refused on their next write — for a call that changed nothing")
            .isEqualTo(settled);

        // The legitimate neighbour: a write that DOES change something must
        // rotate it, or the check above would be satisfied by never rotating.
        memberships.update(scope, iteration, item, Map.of(
            "membership_status", "active",
            "conflict_token", settled));
        assertThat(token(iteration))
            .as("an effective change rotates the token, or the next writer cannot tell "
                + "that the aggregate moved")
            .isNotEqualTo(settled);
    }

    // ==================================================================
    // Probe 2 — `planned` is derived, and from BOTH halves.
    // ==================================================================

    /**
     * An item is planned when it has a live membership of an OPEN iteration —
     * and both halves are load-bearing.
     *
     * <p>The membership survives the close of its iteration, which is what
     * makes a closed iteration a record of what was worked rather than a gap.
     * A derivation shortened to "has a membership" would therefore answer this
     * item as planned forever, which is the orphan class the predecessor
     * carried under a different name.
     */
    @Test
    void an_item_in_a_closed_iteration_is_not_planned_and_still_has_its_membership()
            throws SQLException {
        UUID iteration = iterationId(created("third", 1));
        UUID item = onPath(item("worked and finished"));
        memberships.plan(scope, iteration, item, token(iteration));

        assertThat(memberships.query(scope))
            .as("an item in an open iteration on a live membership is planned — the "
                + "legitimate half, without which the derivation could answer nothing "
                + "and pass every case below")
            .containsExactly(item);

        memberships.update(scope, iteration, item, Map.of(
            "membership_status", "done",
            "conflict_token", token(iteration)));
        iterations.close(scope, iteration, token(iteration));

        assertThat(memberships.query(scope))
            .as("closed iteration, terminal membership: not planned")
            .isEmpty();

        assertThat(membershipRowsOf(iteration))
            .as("RED STATE, by its trace: the membership row is STILL THERE. A derivation "
                + "shortened to 'has a membership' would read this row and answer the "
                + "item as planned — which is exactly the state this assertion proves is "
                + "not being read")
            .isEqualTo(1);
    }

    /**
     * A live membership of an OPEN iteration is what counts, and a terminal
     * one in the same open iteration is not.
     *
     * <p>The other half of the derivation, isolated: here the iteration never
     * closes, so anything the case proves is about the membership's own
     * status alone.
     */
    @Test
    void a_terminal_membership_of_an_open_iteration_is_not_planned() {
        UUID iteration = iterationId(created("fourth", 1));
        UUID dropped = onPath(item("dropped"));
        UUID standing = onPath(item("standing"));
        memberships.plan(scope, iteration, dropped, token(iteration));
        memberships.plan(scope, iteration, standing, token(iteration));

        memberships.update(scope, iteration, dropped, Map.of(
            "membership_status", "dropped",
            "conflict_token", token(iteration)));

        assertThat(memberships.query(scope))
            .as("RED STATE, by its trace: both items are members of this open iteration, "
                + "and a derivation that read membership alone would answer both. Only "
                + "the live one is planned")
            .containsExactly(standing);
    }

    // ==================================================================
    // Probe 3 — the cardinality limits, from the row and not from the code.
    // ==================================================================

    /**
     * The hard limit refuses and writes nothing; the warning admits and says
     * so.
     *
     * <p>Both numbers come from the scope's own row. The proof that they do is
     * that this method never mentions a number the code could have held: it
     * writes two limits into the settings and watches the behaviour follow
     * them.
     */
    @Test
    void the_membership_limit_refuses_and_the_threshold_below_it_warns() {
        settings.update(scope, Map.of(
            "max_memberships_per_iteration", 2,
            "warn_memberships_per_iteration", 2,
            "conflict_token", settingToken()));

        UUID iteration = iterationId(created("fifth", 1));
        UUID first = onPath(item("within"));
        UUID second = onPath(item("at the threshold"));
        UUID third = onPath(item("beyond the limit"));

        Map<String, Object> quiet = memberships.plan(scope, iteration, first,
            token(iteration));
        assertThat(warningsOf(quiet))
            .as("below the threshold there is nothing to say, and a warning here would "
                + "make every plan carry noise")
            .isEmpty();

        Map<String, Object> warned = memberships.plan(scope, iteration, second,
            token(iteration));
        assertThat(warningsOf(warned))
            .as("at the threshold the answer warns AND the write goes through: the "
                + "warning exists so the limit is met deliberately rather than "
                + "discovered at the moment it refuses")
            .hasSize(1);
        assertThat(warningsOf(warned).get(0)).contains("2");

        WorklistException refusal = refusalFrom(() ->
            memberships.plan(scope, iteration, third, token(iteration)));
        assertThat(refusal.reason())
            .isEqualTo(WorklistException.Reason.CARDINALITY_EXCEEDED);
        assertThat(refusal.offenders())
            .as("the refusal carries the limit, so a caller can tell a setting they may "
                + "raise from a platform ceiling they may not")
            .containsExactly("2");

        assertThat(memberships.query(scope))
            .as("RED STATE, by its trace: with the check removed the third membership "
                + "would be here, and the limit would have been passed in silence")
            .containsExactlyInAnyOrder(first, second);

        // The limit is the ROW's. Raise it and the same call goes through,
        // with no change to a line of code.
        settings.update(scope, Map.of(
            "max_memberships_per_iteration", 3,
            "conflict_token", settingToken()));
        memberships.plan(scope, iteration, third, token(iteration));
        assertThat(memberships.query(scope))
            .as("raising the scope's own number admits the write that was refused a "
                + "moment ago — which is what makes the number a setting rather than a "
                + "constant in this file")
            .containsExactlyInAnyOrder(first, second, third);
    }

    /** The same pair on the other axis: open iterations per scope. */
    @Test
    void the_open_iteration_limit_refuses_and_the_threshold_below_it_warns() {
        settings.update(scope, Map.of(
            "max_planned_iterations", 2,
            "warn_planned_iterations", 2,
            "conflict_token", settingToken()));

        assertThat(warningsOf(created("first", 1))).isEmpty();
        assertThat(warningsOf(created("second", 2)))
            .as("at the threshold, warned and admitted")
            .hasSize(1);

        WorklistException refusal = refusalFrom(() -> created("third", 3));
        assertThat(refusal.reason())
            .isEqualTo(WorklistException.Reason.CARDINALITY_EXCEEDED);

        assertThat(iterations.query(scope))
            .as("RED STATE, by its trace: with the check removed the third iteration "
                + "would be here — and it would have consumed a number, which is not "
                + "handed back")
            .hasSize(2);
    }

    // ==================================================================
    // Probe 4 — at most one active membership, and the service's demotion.
    // ==================================================================

    /**
     * Activating a second membership demotes the first, in one write.
     *
     * <p>The invariant itself belongs to the partial unique index and is
     * proved against the index, by raw SQL, in {@code SchemaConstraintIT}.
     * What is proved HERE is the other half: that the service expresses the
     * intention in one write rather than refusing it, so that an operator
     * does not have to perform two writes to say one thing.
     *
     * <p>Both halves are needed and neither substitutes for the other. A
     * service that refused would satisfy the index; an index that admitted
     * two would leave the service's demotion doing the work alone.
     */
    @Test
    void activating_a_second_membership_demotes_the_first_in_one_write() {
        UUID iteration = iterationId(created("sixth", 1));
        UUID first = onPath(item("worked first"));
        UUID second = onPath(item("worked next"));
        memberships.plan(scope, iteration, first, token(iteration));
        memberships.plan(scope, iteration, second, token(iteration));

        memberships.update(scope, iteration, first, Map.of(
            "membership_status", "active", "conflict_token", token(iteration)));
        memberships.update(scope, iteration, second, Map.of(
            "membership_status", "active", "conflict_token", token(iteration)));

        assertThat(memberships.read(scope, iteration, second).get("membership_status"))
            .isEqualTo("active");
        assertThat(memberships.read(scope, iteration, first).get("membership_status"))
            .as("the first was demoted in the same write, and NOT refused: a refusal "
                + "would make the operator perform two writes to express one intention")
            .isEqualTo("todo");
    }

    // ==================================================================
    // Probe 5 — what may enter an iteration at all.
    // ==================================================================

    /**
     * An item enters an iteration only when it is actionable and carries a
     * milestone on the product path, and each refusal names what it found.
     */
    @Test
    void an_item_enters_an_iteration_only_when_it_is_actionable_and_on_the_path() {
        UUID iteration = iterationId(created("seventh", 1));

        UUID resting = onPath(item("on hold", restingStatus));
        WorklistException notActionable = refusalFrom(() ->
            memberships.plan(scope, iteration, resting, token(iteration)));
        assertThat(notActionable.reason())
            .isEqualTo(WorklistException.Reason.ITEM_UNPLANNABLE);
        assertThat(notActionable.offenders())
            .as("the refusal names the STATUS it found, because the three causes read "
                + "the same from outside and the remedy for each is different")
            .containsExactly("on hold");

        UUID unassessed = item("not assessed yet");
        WorklistException noMilestone = refusalFrom(() ->
            memberships.plan(scope, iteration, unassessed, token(iteration)));
        assertThat(noMilestone.reason())
            .isEqualTo(WorklistException.Reason.ITEM_UNPLANNABLE);

        UUID offPath = withMilestone(item("off the path"), marker(Milestone.OFF_PATH));
        WorklistException off = refusalFrom(() ->
            memberships.plan(scope, iteration, offPath, token(iteration)));
        assertThat(off.offenders())
            .as("and here it names the KIND it found")
            .containsExactly(Milestone.OFF_PATH);

        // The legitimate neighbour, without which all three assertions would
        // be satisfied by a precondition that refuses everything.
        UUID admissible = onPath(item("actionable, on the path"));
        memberships.plan(scope, iteration, admissible, token(iteration));
        assertThat(memberships.query(scope)).containsExactly(admissible);
    }

    // ==================================================================
    // Probe 6 — the reorder: many rows, one token.
    // ==================================================================

    /**
     * <strong>A reorder writes twelve rows and presents ONE token.</strong>
     *
     * <p>This is the ratification of this session made observable. The
     * membership rows carry no token of their own precisely so that this is
     * expressible; had they one, the caller below would have had to present
     * twelve, and the aggregate would be the row again.
     *
     * <p>Twelve rather than two, and the number is not decoration: the point
     * is that the count of tokens does not follow the count of rows, and two
     * of each would leave that indistinguishable from one token per row.
     */
    @Test
    void a_reorder_writes_every_membership_and_presents_one_token() {
        // Twelve is above this fixture's default limit, so the scope's own
        // number is raised first — which is what a scope that works in
        // twelves would do, and is not a way around the check.
        settings.update(scope, Map.of(
            "max_memberships_per_iteration", 20,
            "warn_memberships_per_iteration", 20,
            "conflict_token", settingToken()));

        UUID iteration = iterationId(created("the reorder", 1));
        List<UUID> planned = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID item = onPath(item("row " + i));
            memberships.plan(scope, iteration, item, token(iteration));
            planned.add(item);
        }

        List<UUID> reversed = new java.util.ArrayList<>(planned);
        java.util.Collections.reverse(reversed);

        // ONE token, for twelve rows.
        Map<String, Object> answer = iterations.update(scope, iteration, Map.of(
            "order", reversed.stream().map(String::valueOf).toList(),
            "conflict_token", token(iteration)));

        assertThat(answer.get("order"))
            .as("the sequence is now the one that was given, and it took one token to "
                + "say so — twelve rows moved under the iteration's single aggregate "
                + "token")
            .isEqualTo(reversed);
        assertThat(memberships.read(scope, iteration, reversed.get(0)).get("position"))
            .as("position is derived from the order and never given")
            .isEqualTo(0);

        // The no-op half: sending the sequence back as it now stands moves
        // nothing, so no reader of this iteration is invalidated for free.
        String settled = token(iteration);
        iterations.update(scope, iteration, Map.of(
            "order", reversed.stream().map(String::valueOf).toList(),
            "conflict_token", settled));
        assertThat(token(iteration))
            .as("RED STATE, by its trace: without the comparison a re-sent sequence would "
                + "rotate the token and refuse every other reader's next write")
            .isEqualTo(settled);
    }

    /**
     * A reorder names exactly the members, and neither plans nor unplans.
     *
     * <p>An item left out would otherwise leave a membership at a position
     * the sequence no longer mentions, and an item added would be a plan
     * performed by a verb that says it is reordering.
     */
    @Test
    void a_reorder_that_names_a_stranger_is_refused_and_names_it() {
        UUID iteration = iterationId(created("strict order", 1));
        UUID member = onPath(item("a member"));
        UUID stranger = onPath(item("not a member"));
        memberships.plan(scope, iteration, member, token(iteration));

        WorklistException refusal = refusalFrom(() ->
            iterations.update(scope, iteration, Map.of(
                "order", List.of(String.valueOf(member), String.valueOf(stranger)),
                "conflict_token", token(iteration))));

        assertThat(refusal.reason())
            .isEqualTo(WorklistException.Reason.MEMBERSHIP_UNKNOWN);
        assertThat(refusal.offenders()).contains(String.valueOf(stranger));

        // The legitimate neighbour: the sequence that names exactly the
        // members must go through, or the check would refuse every reorder.
        iterations.update(scope, iteration, Map.of(
            "order", List.of(String.valueOf(member)),
            "conflict_token", token(iteration)));
    }

    // ==================================================================
    // Probe 7 — leaving, promoting, and the goal axis.
    // ==================================================================

    /**
     * Unplanning takes an item out of the pool of planned ones and leaves the
     * record of it having been there.
     *
     * <p>The row is not deleted — this schema grants DELETE nowhere — so what
     * has to be observed is both halves: the derivation stops counting it,
     * and the row is still in the store.
     */
    @Test
    void unplanning_removes_the_item_from_planned_and_keeps_its_row()
            throws SQLException {
        UUID iteration = iterationId(created("leaving", 1));
        UUID leaving = onPath(item("taken out"));
        UUID staying = onPath(item("still here"));
        memberships.plan(scope, iteration, leaving, token(iteration));
        memberships.plan(scope, iteration, staying, token(iteration));

        memberships.unplan(scope, iteration, leaving, token(iteration));

        assertThat(memberships.query(scope))
            .as("the item returned to the pool it came from")
            .containsExactly(staying);
        assertThat(membershipRowsOf(iteration))
            .as("RED STATE, by its trace: both rows are still in the store. A verb that "
                + "deleted would need a privilege this schema grants nowhere, and the "
                + "record of the item having been in this iteration would be gone")
            .isEqualTo(2);
        assertThat(memberships.read(scope, iteration, staying).get("position"))
            .as("the sequence closed up behind the row that left, so position stays dense")
            .isEqualTo(0);
    }

    /**
     * {@code advance} promotes the first open iteration and writes the
     * pointer on the SETTINGS, presenting their token.
     *
     * <p>The pointer lives there rather than as a flag on the iteration,
     * which would allow two current ones. So the aggregate being written is
     * the settings row, and that is the token the verb asks for — an
     * iteration is not modified by being pointed at.
     */
    @Test
    void advance_promotes_the_first_open_iteration_and_writes_the_pointer() {
        UUID first = iterationId(created("first", 1));
        UUID second = iterationId(created("second", 2));

        Map<String, Object> afterFirst = iterations.advance(scope, settingToken());
        assertThat(afterFirst.get("current_iteration")).isEqualTo(first);

        Map<String, Object> afterSecond = iterations.advance(scope, settingToken());
        assertThat(afterSecond.get("current_iteration"))
            .as("advancing again takes the next one in the axis's order, by rank")
            .isEqualTo(second);

        WorklistException stale = refusalFrom(() ->
            iterations.advance(scope, (String) afterFirst.get("conflict_token")));
        assertThat(stale.reason())
            .as("RED STATE, by its trace: the pointer is on the settings, so a stale "
                + "settings token is what has to be refused here — a verb that presented "
                + "an iteration's token would be writing one aggregate under another's "
                + "guard")
            .isEqualTo(WorklistException.Reason.CONFLICT);
    }

    /**
     * A scope holds one active milestone, and activating a second demotes the
     * first in one write.
     *
     * <p>The invariant is the partial unique index, proved against the index
     * in {@code SchemaConstraintIT}. What is proved here is that the service
     * expresses the intention in one write rather than refusing it.
     */
    @Test
    void activating_a_milestone_demotes_the_one_that_was_active() {
        UUID first = (UUID) milestones.create(scope, Map.of("title", "first goal")).get("id");
        UUID second = (UUID) milestones.create(scope, Map.of("title", "second goal")).get("id");

        milestones.update(scope, first, Map.of("status", Milestone.ACTIVE,
            "conflict_token", milestoneToken(first)));
        milestones.update(scope, second, Map.of("status", Milestone.ACTIVE,
            "conflict_token", milestoneToken(second)));

        assertThat(milestones.read(scope, second).get("status")).isEqualTo(Milestone.ACTIVE);
        assertThat(milestones.read(scope, first).get("status"))
            .as("demoted in the same write, and not refused — a refusal would make the "
                + "operator perform two writes to express one intention")
            .isEqualTo(Milestone.PLANNED);
    }

    /**
     * A marker carries neither a vision nor a mission, and the refusal is
     * this service's rather than the database's.
     *
     * <p>The check constraint stays and is the mechanism. What is asserted
     * here is that the caller meets a typed refusal first — a constraint
     * violation arrives at flush, under JTA, outside the typed refusal model,
     * which is how a refusal becomes a 500.
     */
    @Test
    void a_marker_carrying_a_goal_is_refused_before_the_constraint_sees_it() {
        WorklistException refusal = refusalFrom(() -> milestones.create(scope, Map.of(
            "title", "off the path", "kind", Milestone.OFF_PATH,
            "vision", "a north star a marker may not have")));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly("kind");

        // Both legitimate neighbours: a marker without a goal, and a real
        // milestone with one.
        milestones.create(scope, Map.of("title", "off the path", "kind", Milestone.OFF_PATH));
        milestones.create(scope, Map.of("title", "a real goal", "vision", "the north star"));
    }

    // ==================================================================
    // Probe 8 — the shape of the store the aggregate rule depends on.
    // ==================================================================

    /**
     * There is no conflict token on {@code iteration_membership}, and there
     * must not be.
     *
     * <p>Asserted against the catalog rather than against the entity: a
     * column the entity does not map is still a column, and the invariant is
     * about the STORE. A token here would invert the ratification — the
     * aggregate would be the row again, and a reorder would present twelve
     * tokens instead of the iteration's one.
     *
     * <p>The three that DO carry one are asserted in the same breath, so the
     * check cannot pass by finding no columns at all.
     */
    @Test
    void the_token_is_on_the_roots_and_never_on_the_membership() throws SQLException {
        try (Connection c = Db.asService()) {
            assertThat(hasConflictToken(c, "iteration_membership"))
                .as("RED STATE, observed: a token on the membership is the most tempting "
                    + "column in this migration and the one that would undo the "
                    + "aggregate rule. It must not be there")
                .isFalse();

            assertThat(List.of(
                    hasConflictToken(c, "item"),
                    hasConflictToken(c, "iteration"),
                    hasConflictToken(c, "milestone"),
                    hasConflictToken(c, "scope_setting")))
                .as("and the four roots must all carry one, or the assertion above would "
                    + "be passing on a catalog read that finds nothing anywhere")
                .containsExactly(true, true, true, true);
        }
    }

    /**
     * A number handed out on either planning axis is never handed out again.
     *
     * <p>The mark records what was ISSUED, which is not the same set as what
     * exists: a closed iteration stays in the table and the next number
     * counts past it. An allocator reading {@code max(number) + 1} would pass
     * this only because nothing here deletes — so the case closes an
     * iteration first, which is the state where a derived allocator and a
     * persisted mark still agree, and then checks that the mark kept
     * counting.
     */
    @Test
    void a_planning_number_is_never_handed_out_twice() {
        Map<String, Object> first = created("first", 1);
        UUID firstId = iterationId(first);
        memberships.plan(scope, firstId, onPath(item("in the first")), token(firstId));
        memberships.update(scope, firstId, memberships.query(scope).get(0), Map.of(
            "membership_status", "done", "conflict_token", token(firstId)));
        iterations.close(scope, firstId, token(firstId));

        Map<String, Object> second = created("second", 2);

        assertThat((Long) second.get("number"))
            .as("RED STATE, by its trace: the first iteration is CLOSED and its number is "
                + "still spent. An allocator that counted the live rows would hand this "
                + "number out again")
            .isEqualTo(2L);
        assertThat((Long) first.get("number")).isEqualTo(1L);
    }

    // ==================================================================
    // Fixtures.
    // ==================================================================

    /** An iteration of this scope, with a rank so the order is deterministic. */
    private Map<String, Object> created(String motto, int rank) {
        return iterations.create(scope, Map.of(
            "motto", motto, "description", "what " + motto + " contains", "rank", rank));
    }

    private static UUID iterationId(Map<String, Object> iteration) {
        return (UUID) iteration.get("id");
    }

    /** The iteration's current token, read the way a caller would. */
    private String token(UUID iterationId) {
        return (String) iterations.read(scope, iterationId).get("conflict_token");
    }

    private String settingToken() {
        return (String) settings.read(scope).get("conflict_token");
    }

    private String milestoneToken(UUID milestoneId) {
        return (String) milestones.read(scope, milestoneId).get("conflict_token");
    }

    @SuppressWarnings("unchecked")
    private static List<String> warningsOf(Map<String, Object> answer) {
        return (List<String>) answer.get("warnings");
    }

    private UUID item(String title) {
        return item(title, actionableStatus);
    }

    private UUID item(String title, UUID statusId) {
        return (UUID) items.create(scope, Map.of(
            "title", title, "status", String.valueOf(statusId))).get("id");
    }

    /** A milestone of the given kind. Markers carry neither vision nor mission. */
    private UUID marker(String kind) {
        return (UUID) milestones.create(scope, Map.of(
            "title", kind + " position", "kind", kind)).get("id");
    }

    /** An item carrying a real goal, which is what makes it plannable. */
    private UUID onPath(UUID itemId) {
        return withMilestone(itemId, (UUID) milestones.create(scope, Map.of(
            "title", "a goal", "vision", "the north star in a sentence")).get("id"));
    }

    /**
     * Point an item at a milestone, over JDBC.
     *
     * <p>The one fixture here that goes around the service, and it does so
     * because <strong>no verb assigns this field</strong>. See the class
     * comment: the precondition is built, the way to satisfy it through a
     * verb is not, and that gap is reported rather than papered over with a
     * seventh verb nobody asked for.
     */
    private UUID withMilestone(UUID itemId, UUID milestoneId) {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, boundTenant());
            try (var st = c.prepareStatement(
                    "UPDATE worklist.item SET milestone_id = ? WHERE id = ?")) {
                st.setObject(1, milestoneId);
                st.setObject(2, itemId);
                st.executeUpdate();
            }
            c.commit();
        } catch (SQLException notWritable) {
            throw new IllegalStateException(
                "the milestone fixture could not write item " + itemId, notWritable);
        }
        return itemId;
    }

    private static int membershipRowsOf(UUID iterationId) throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, boundTenant());
            try (var st = c.prepareStatement(
                    "SELECT count(*) FROM worklist.iteration_membership "
                        + "WHERE iteration_id = ?")) {
                st.setObject(1, iterationId);
                try (ResultSet rows = st.executeQuery()) {
                    rows.next();
                    return rows.getInt(1);
                }
            }
        }
    }

    private static boolean hasConflictToken(Connection c, String table) throws SQLException {
        try (var st = c.prepareStatement(
                "SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_schema = 'worklist' AND table_name = ? "
                    + "AND column_name = 'conflict_token'")) {
            st.setString(1, table);
            try (ResultSet rows = st.executeQuery()) {
                rows.next();
                return rows.getInt(1) == 1;
            }
        }
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
