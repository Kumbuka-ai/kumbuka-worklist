package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The invariant that binds the fourth axis to the goal axis, and the
 * item's own workstream field around it.
 *
 * <h2>What is defended</h2>
 *
 * <p><strong>An item carries a workstream — obligatorily.</strong> Create
 * without a workstream falls to the scope's default; create with a
 * caller-named workstream uses it. Withdrawn is refused.
 *
 * <p><strong>An item with a milestone shares its workstream.</strong>
 * Assigning a milestone whose workstream is not the item's is refused
 * with {@code WORKSTREAM_MILESTONE_MISMATCH}. Moving the item to a
 * different workstream while it carries a milestone in the current one
 * is refused for the same reason.
 *
 * <p>The red state for each check is the removal of the corresponding
 * refuse-* method from {@link ItemService}, with the probe here observed
 * turning from a typed refusal into a silent write.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ItemWorkstreamInvariantIT {

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
    }

    // ==================================================================
    // Class 1 — item carries a workstream (default fallback + explicit)
    // ==================================================================

    @Test
    void create_without_a_workstream_falls_to_the_scopes_default() {
        selectors.declare(scope, Selector.ITEM);
        Workstream defaultWs = workstreams.requireDefault(scope);

        Map<String, Object> item = items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "no-workstream item",
            Field.STATUS.canonicalName(), openStatus.toString()));

        assertThat(item.get(Field.WORKSTREAM_ID.canonicalName())).isEqualTo(defaultWs.id);
    }

    @Test
    void create_with_a_named_workstream_uses_it() {
        selectors.declare(scope, Selector.ITEM);
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");

        Map<String, Object> item = items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "a mobile item",
            Field.STATUS.canonicalName(), openStatus.toString(),
            Field.WORKSTREAM_ID.canonicalName(), mobile.id.toString()));

        assertThat(item.get(Field.WORKSTREAM_ID.canonicalName())).isEqualTo(mobile.id);
    }

    @Test
    void create_refuses_an_unknown_workstream() {
        selectors.declare(scope, Selector.ITEM);
        WorklistException refusal = refusalFrom(() -> items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "orphan item",
            Field.STATUS.canonicalName(), openStatus.toString(),
            Field.WORKSTREAM_ID.canonicalName(), UUID.randomUUID().toString())));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.WORKSTREAM_UNKNOWN);
    }

    @Test
    void create_refuses_a_workstream_value_that_is_not_a_uuid() {
        selectors.declare(scope, Selector.ITEM);
        WorklistException refusal = refusalFrom(() -> items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "malformed ws",
            Field.STATUS.canonicalName(), openStatus.toString(),
            Field.WORKSTREAM_ID.canonicalName(), "not-a-uuid")));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
    }

    // ==================================================================
    // Class 2 — item with milestone shares its workstream
    // ==================================================================

    @Test
    void assigning_a_milestone_from_another_workstream_is_refused() {
        selectors.declare(scope, Selector.ITEM);
        selectors.declare(scope, Selector.MILESTONE);
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");
        Workstream backend = workstreams.declare(scope, "backend", "backend stream");

        // Item lands in `mobile`; milestone lives in `backend`.
        UUID itemId = createItem("cross-ws item", mobile.id);
        UUID milestoneInBackend = createMilestone("backend goal", backend.id);

        WorklistException refusal = refusalFrom(() ->
            items.update(scope, itemId, Map.of(
                Field.MILESTONE_ID.canonicalName(), milestoneInBackend.toString(),
                Field.CONFLICT_TOKEN.canonicalName(), tokenOf(itemId))));
        assertThat(refusal.reason())
            .isEqualTo(WorklistException.Reason.WORKSTREAM_MILESTONE_MISMATCH);
    }

    @Test
    void assigning_a_milestone_from_the_same_workstream_passes() {
        selectors.declare(scope, Selector.ITEM);
        selectors.declare(scope, Selector.MILESTONE);
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");

        UUID itemId = createItem("same-ws item", mobile.id);
        UUID milestoneInMobile = createMilestone("mobile goal", mobile.id);

        Map<String, Object> updated = items.update(scope, itemId, Map.of(
            Field.MILESTONE_ID.canonicalName(), milestoneInMobile.toString(),
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(itemId)));
        assertThat(updated.get(Field.MILESTONE_ID.canonicalName())).isEqualTo(milestoneInMobile);
    }

    @Test
    void moving_the_item_to_a_workstream_the_milestone_is_not_in_is_refused() {
        selectors.declare(scope, Selector.ITEM);
        selectors.declare(scope, Selector.MILESTONE);
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");
        Workstream backend = workstreams.declare(scope, "backend", "backend stream");

        // Item in `mobile`, with a milestone in `mobile` — the pair is
        // consistent — then a rename to `backend` would break the invariant.
        UUID itemId = createItem("consistent then moved", mobile.id);
        UUID milestoneInMobile = createMilestone("mobile goal", mobile.id);
        items.update(scope, itemId, Map.of(
            Field.MILESTONE_ID.canonicalName(), milestoneInMobile.toString(),
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(itemId)));

        WorklistException refusal = refusalFrom(() ->
            items.update(scope, itemId, Map.of(
                Field.WORKSTREAM_ID.canonicalName(), backend.id.toString(),
                Field.CONFLICT_TOKEN.canonicalName(), tokenOf(itemId))));
        assertThat(refusal.reason())
            .isEqualTo(WorklistException.Reason.WORKSTREAM_MILESTONE_MISMATCH);
    }

    @Test
    void moving_the_item_to_another_workstream_without_a_milestone_passes() {
        selectors.declare(scope, Selector.ITEM);
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");
        Workstream backend = workstreams.declare(scope, "backend", "backend stream");

        UUID itemId = createItem("moveable item", mobile.id);

        Map<String, Object> updated = items.update(scope, itemId, Map.of(
            Field.WORKSTREAM_ID.canonicalName(), backend.id.toString(),
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(itemId)));
        assertThat(updated.get(Field.WORKSTREAM_ID.canonicalName())).isEqualTo(backend.id);
    }

    @Test
    void clearing_the_workstream_is_refused() {
        selectors.declare(scope, Selector.ITEM);
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");
        UUID itemId = createItem("no-clear item", mobile.id);

        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put(Field.WORKSTREAM_ID.canonicalName(), null);
        args.put(Field.CONFLICT_TOKEN.canonicalName(), tokenOf(itemId));
        WorklistException refusal = refusalFrom(() -> items.update(scope, itemId, args));
        assertThat(refusal.reason())
            .isEqualTo(WorklistException.Reason.ITEM_WORKSTREAM_MISSING);
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

    private static WorklistException refusalFrom(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown)
            .as("the call must be refused with the service's typed refusal")
            .isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }
}
