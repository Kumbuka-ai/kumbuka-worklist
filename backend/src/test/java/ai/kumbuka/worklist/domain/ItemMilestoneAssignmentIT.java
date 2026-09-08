package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.Db;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * An item is assigned to a milestone through {@code item.update} against
 * {@code milestone}, and the assignment carries the milestone's identity.
 *
 * <h2>What is being defended</h2>
 *
 * <p><strong>Existence, in this scope, and not closed.</strong> A milestone
 * that does not exist, one from another scope, or one that has been closed,
 * is a typed refusal — the scope check is written in explicitly so that an id
 * from another tenant cannot slip through as a not-found. The three marker
 * rows are legitimate targets: they are milestones in the table and
 * positions on the axis, and they are rows for exactly that reason.
 *
 * <p><strong>Identity, never a title.</strong> The value travels as a UUID;
 * a non-UUID is a typed refusal that names the field. A title is a scope's
 * to change, and a caller writing one would be writing something that can
 * move under them.
 *
 * <p><strong>null clears the assignment</strong>, and a write that changes
 * no value writes nothing — no timestamp, no rotated token.
 *
 * <h2>The red state</h2>
 *
 * <p>The zusicherung that needs a probe is the EXISTENCE CHECK, not the
 * settability. Settability falls on the first call; a missing existence
 * check does not fall, it quietly writes an assignment into nothing. The
 * red state below is observed by removing the check in
 * {@link ItemService#applyMilestone} — the four refusals turn into silent
 * writes — with the corresponding control run recorded in the return.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ItemMilestoneAssignmentIT {

    @Inject ItemService items;
    @Inject MilestoneService milestones;
    @Inject ScopeSettingService settings;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;

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
    // The legitimate neighbour — a milestone of this scope is assigned
    // ==================================================================

    @Test
    void an_item_carries_a_milestone_of_its_scope_and_a_marker_is_a_valid_target() {
        UUID itemId = itemAt("the assignment case");
        UUID goalId = milestoneAt("a real goal", true);

        Map<String, Object> answer = items.update(scope, itemId, Map.of(
            Field.MILESTONE_ID.canonicalName(), goalId.toString(),
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(itemId)));
        assertThat(answer.get(Field.MILESTONE_ID.canonicalName()))
            .as("the identity stored is what the caller sent")
            .isEqualTo(goalId);

        // A second item, this time assigned to a marker — the three marker
        // rows are milestones in the table and positions on the axis, so
        // they are legitimate targets rather than exceptions.
        UUID secondItem = itemAt("the marker case");
        UUID markerId = markerAt();
        answer = items.update(scope, secondItem, Map.of(
            Field.MILESTONE_ID.canonicalName(), markerId.toString(),
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(secondItem)));
        assertThat(answer.get(Field.MILESTONE_ID.canonicalName())).isEqualTo(markerId);
    }

    @Test
    void a_null_assignment_clears_the_milestone_and_a_no_op_leaves_the_token() {
        UUID itemId = itemAt("the null and no-op case");
        UUID goalId = milestoneAt("a north star", true);

        items.update(scope, itemId, Map.of(
            Field.MILESTONE_ID.canonicalName(), goalId.toString(),
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(itemId)));
        String settled = tokenOf(itemId);

        // The same identity again is not a change. Nothing is written, the
        // token stays where it is, and the honest round trip is admitted
        // rather than refused.
        items.update(scope, itemId, Map.of(
            Field.MILESTONE_ID.canonicalName(), goalId.toString(),
            Field.CONFLICT_TOKEN.canonicalName(), settled));
        assertThat(tokenOf(itemId))
            .as("a no-op assignment writes nothing")
            .isEqualTo(settled);

        // null clears the assignment. An item that serves no milestone is a
        // regular state — the assignment is optional.
        Map<String, Object> answer = items.update(scope, itemId, Map.of(
            Field.MILESTONE_ID.canonicalName(), "",
            Field.CONFLICT_TOKEN.canonicalName(), settled));
        assertThat(answer.get(Field.MILESTONE_ID.canonicalName())).isNull();
    }

    // ==================================================================
    // The red-state cases — existence in this scope, and not closed
    // ==================================================================

    @Test
    void a_milestone_from_another_scope_is_refused_rather_than_reported_as_missing() {
        UUID itemId = itemAt("the foreign-scope case");

        UUID otherScope = UUID.randomUUID();
        vocabulary.declareStatus(otherScope, "open", 1, true, false, false, false);
        settings.create(otherScope, Map.of(
            "max_planned_iterations", 5, "warn_planned_iterations", 4,
            "max_memberships_per_iteration", 5, "warn_memberships_per_iteration", 4));
        UUID foreignMilestone = (UUID) milestones.create(otherScope, Map.of(
            Field.TITLE.canonicalName(), "another scope's goal",
            Field.VISION.canonicalName(), "elsewhere")).get(Field.ID.canonicalName());

        WorklistException refusal = refusalFrom(() -> items.update(scope, itemId, Map.of(
            Field.MILESTONE_ID.canonicalName(), foreignMilestone.toString(),
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(itemId))));

        assertThat(refusal.reason())
            .as("a milestone from another scope must be a typed refusal — an id that "
                + "names something elsewhere is a mistake and not a missing row; "
                + "letting it through as 'not found' would let another tenant's id "
                + "become an existence probe")
            .isEqualTo(WorklistException.Reason.MILESTONE_UNKNOWN);
        assertThat(refusal.offenders()).containsExactly(Field.MILESTONE_ID.canonicalName());
    }

    @Test
    void a_milestone_id_that_names_nothing_is_a_typed_refusal() {
        UUID itemId = itemAt("the absent-milestone case");
        UUID nothing = UUID.randomUUID();

        WorklistException refusal = refusalFrom(() -> items.update(scope, itemId, Map.of(
            Field.MILESTONE_ID.canonicalName(), nothing.toString(),
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(itemId))));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.MILESTONE_UNKNOWN);
        assertThat(refusal.offenders()).containsExactly(Field.MILESTONE_ID.canonicalName());
    }

    @Test
    void a_closed_milestone_is_refused_because_nothing_is_working_towards_it() {
        UUID itemId = itemAt("the closed-milestone case");
        UUID goalId = milestoneAt("a soon-closed goal", true);
        String goalToken = (String) milestones.read(scope, goalId).get(Field.CONFLICT_TOKEN.canonicalName());
        milestones.close(scope, goalId, goalToken);

        WorklistException refusal = refusalFrom(() -> items.update(scope, itemId, Map.of(
            Field.MILESTONE_ID.canonicalName(), goalId.toString(),
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(itemId))));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly(Field.MILESTONE_ID.canonicalName());
    }

    @Test
    void a_milestone_value_that_is_not_a_uuid_is_refused_by_form() {
        UUID itemId = itemAt("the not-a-uuid case");

        WorklistException refusal = refusalFrom(() -> items.update(scope, itemId, Map.of(
            Field.MILESTONE_ID.canonicalName(), "milestone/1",
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(itemId))));

        assertThat(refusal.reason())
            .as("a value that is not a UUID is a form refusal — a name is a property "
                + "the scope may rename at any moment, and only the identity can be "
                + "written without moving under any caller who wrote it")
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly(Field.MILESTONE_ID.canonicalName());
    }

    // ==================================================================
    // Planting.
    // ==================================================================

    private UUID itemAt(String title) {
        selectors.declare(scope, Selector.ITEM);
        return (UUID) items.create(scope, Map.of(
            Field.TITLE.canonicalName(), title,
            Field.STATUS.canonicalName(), openStatus.toString())).get(Field.ID.canonicalName());
    }

    private UUID milestoneAt(String title, boolean asGoal) {
        selectors.declare(scope, Selector.MILESTONE);
        Map<String, Object> arguments = asGoal
            ? Map.of(Field.TITLE.canonicalName(), title,
                Field.VISION.canonicalName(), "the north star of " + title)
            : Map.of(Field.TITLE.canonicalName(), title,
                Field.KIND.canonicalName(), Milestone.NO_VISION);
        return (UUID) milestones.create(scope, arguments).get(Field.ID.canonicalName());
    }

    /**
     * A marker row, planted directly against the schema, because the
     * {@link MilestoneService#create} verb allocates a fresh number and would
     * happily produce another goal with no way to say "and this one is a
     * marker" without inventing an argument the verb catalogue does not carry.
     */
    private UUID markerAt() {
        selectors.declare(scope, Selector.MILESTONE);
        UUID id = UUID.randomUUID();
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, PlanningFixture.boundTenant());
            try (var st = c.prepareStatement(
                    "INSERT INTO worklist.milestone "
                        + "(id, tenant_id, scope_id, number, title, kind, status) "
                        + "VALUES (?, ?, ?, "
                        + "(SELECT coalesce(max(number),0)+1 FROM worklist.milestone "
                        + "  WHERE scope_id = ?), "
                        + "?, ?, ?)")) {
                st.setObject(1, id);
                st.setObject(2, PlanningFixture.boundTenant());
                st.setObject(3, scope);
                st.setObject(4, scope);
                st.setString(5, "on-path marker");
                st.setString(6, Milestone.NO_VISION);
                st.setString(7, Milestone.PLANNED);
                st.executeUpdate();
            }
            c.commit();
        } catch (SQLException notPlantable) {
            throw new IllegalStateException(
                "the marker fixture could not plant a row", notPlantable);
        }
        return id;
    }

    private String tokenOf(UUID itemId) {
        return (String) items.read(scope, itemId).get(Field.CONFLICT_TOKEN.canonicalName());
    }

    private static WorklistException refusalFrom(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown)
            .as("the call must be refused, and refused with this service's typed refusal "
                + "rather than with whatever the database or the ORM raised")
            .isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }

    @SuppressWarnings("unused")
    private static void unused(List<UUID> ignore) {
        // Only kept to hold the List<UUID> import if the file grows a case
        // that needs it. Deliberately no body.
    }
}
