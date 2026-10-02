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
 * The invariant that keeps the item on its workstream — an obligatory
 * carrier, resolved at create.
 *
 * <h2>What is defended</h2>
 *
 * <p><strong>An item carries a workstream — obligatorily.</strong> Create
 * without a workstream falls to the scope's default; create with a
 * caller-named workstream uses it. Withdrawn is refused. Clearing on
 * update is refused.
 *
 * <p><strong>An item's milestone is no longer bound to its workstream.</strong>
 * V12 (2026-09-09) retracts the edge — several workstreams reach one
 * milestone together (TAR-0002 section 4, REQ-0148 obsolete). The
 * decoupling probe is in {@link MilestoneWorkstreamDecouplingIT}, and the
 * field's removal from the milestone surface in
 * {@code MilestoneWorkstreamRetractionIT}. Nothing about a milestone is
 * asserted here any more: what this class is about is the item's own
 * obligation, which the retraction does not touch.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ItemWorkstreamInvariantIT {

    @Inject ItemService items;
    @Inject WorkstreamService workstreams;
    @Inject VocabularyRegistry vocabulary;
    @Inject SelectorRegistry selectors;
    @Inject ScopeSettingService settings;

    private UUID scope;
    private Long openStatus;

    @BeforeEach
    void aScopeOfItsOwn() {
        scope = UUID.randomUUID();
        openStatus = vocabulary.declareStatus(scope, "open", 1, true, false, false, false).pk;
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
            Field.STATUS.canonicalName(), vocabulary.requireStatus(scope, openStatus).name));

        assertThat(item.get(Field.WORKSTREAM_ID.canonicalName())).isEqualTo(defaultWs.token);
    }

    @Test
    void create_with_a_named_workstream_uses_it() {
        selectors.declare(scope, Selector.ITEM);
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");

        Map<String, Object> item = items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "a mobile item",
            Field.STATUS.canonicalName(), vocabulary.requireStatus(scope, openStatus).name,
            Field.WORKSTREAM_ID.canonicalName(), mobile.token));

        assertThat(item.get(Field.WORKSTREAM_ID.canonicalName())).isEqualTo(mobile.token);
    }

    @Test
    void create_refuses_an_unknown_workstream() {
        selectors.declare(scope, Selector.ITEM);
        WorklistException refusal = refusalFrom(() -> items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "orphan item",
            Field.STATUS.canonicalName(), vocabulary.requireStatus(scope, openStatus).name,
            Field.WORKSTREAM_ID.canonicalName(), "no-such-workstream")));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.WORKSTREAM_UNKNOWN);
    }

    @Test
    void create_refuses_a_uuid_where_a_workstream_token_is_expected() {
        selectors.declare(scope, Selector.ITEM);
        WorklistException refusal = refusalFrom(() -> items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "malformed ws",
            Field.STATUS.canonicalName(), vocabulary.requireStatus(scope, openStatus).name,
            Field.WORKSTREAM_ID.canonicalName(), UUID.randomUUID().toString())));
        assertThat(refusal.reason())
            .as("the wire form of a workstream is the token the scope declared, and "
                + "a uuid is a form refusal — the platform's identity is not what "
                + "the reader sees back any more")
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);
    }

    // ==================================================================
    // Class 2 — the item's workstream moves, and nothing about a milestone
    // has a say in it
    //
    // Three tests about the item-milestone-workstream triangle used to live
    // here. Two went with V12, which retracted the edge the "refused" shapes
    // asserted. The third — "assigning a milestone from the SAME workstream
    // passes" — went on 2026-09-28 with the surface retraction: a milestone
    // carries no workstream at all now, so there is no same one for it to
    // come from, and the claim that survives (an item in a workstream
    // reaches a milestone) is asserted in MilestoneWorkstreamDecouplingIT
    // rather than twice.
    // ==================================================================

    @Test
    void moving_the_item_to_another_workstream_without_a_milestone_passes() {
        selectors.declare(scope, Selector.ITEM);
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");
        Workstream backend = workstreams.declare(scope, "backend", "backend stream");

        UUID itemId = createItem("moveable item", mobile.id);

        Map<String, Object> updated = items.update(scope, itemId, Map.of(
            Field.WORKSTREAM_ID.canonicalName(), backend.token,
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(itemId)));
        assertThat(updated.get(Field.WORKSTREAM_ID.canonicalName())).isEqualTo(backend.token);
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
            Field.STATUS.canonicalName(), vocabulary.requireStatus(scope, openStatus).name,
            Field.WORKSTREAM_ID.canonicalName(),
            workstreams.require(scope, workstreamId).token))
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
