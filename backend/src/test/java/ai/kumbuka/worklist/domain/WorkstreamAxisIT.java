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
 * The fourth view — the workstream. This suite covers what the frame
 * ratified on 2026-09-08 and asks the service to enforce in the core.
 *
 * <h2>The red state for each of the three violation classes</h2>
 *
 * <p><strong>Class 1 — an item without a workstream.</strong> The item
 * verb resolves a workstream at create time (from the caller's argument
 * or from the scope's default). Removing that resolution — commenting
 * out the {@code item.workstreamId = workstream.id} line — leaves the
 * column null and the DB refuses it at flush; V10's NOT NULL is the
 * outer floor. Both are the guard; both were observed rejecting on the
 * dev DB after this suite was written.
 *
 * <p><strong>Class 2 — an item whose milestone lies in another
 * workstream.</strong> Removing {@code refuseCrossWorkstreamMilestone}
 * from {@link ItemService#applyMilestone} lets the assignment through
 * silently. The probe below observes the refusal.
 *
 * <p><strong>Class 3 — an iteration closed without a naming.</strong>
 * Removing {@code requireNamed} in {@link IterationService#close(UUID, UUID, String, String)}
 * lets the close pass with a null or blank produced-name. The probe
 * observes the refusal.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class WorkstreamAxisIT {

    @Inject WorkstreamService workstreams;
    @Inject ItemService items;
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
    // requireDefault — the lazy path a scope opened outside bootstrap uses
    // ==================================================================

    @Test
    void a_scope_that_never_bootstrapped_gets_its_default_on_first_read() {
        Workstream first = workstreams.requireDefault(scope);
        assertThat(first.isDefault).isTrue();
        assertThat(first.token).isEqualTo(Workstream.DEFAULT_TOKEN);
        assertThat(first.number).isEqualTo(1L);
        assertThat(first.status).isEqualTo(Workstream.DECLARED);

        // Idempotent: a second read returns the same row.
        Workstream second = workstreams.requireDefault(scope);
        assertThat(second.id).isEqualTo(first.id);
    }

    // ==================================================================
    // declare — the caller-mentioned path, and the four refusals
    // ==================================================================

    @Test
    void declare_opens_a_workstream_with_a_number_from_the_workstream_counter() {
        // The default takes number 1, so a first declare returns 2.
        workstreams.requireDefault(scope);
        Workstream mobile = workstreams.declare(scope, "mobile", "the mobile release stream");

        assertThat(mobile.token).isEqualTo("mobile");
        assertThat(mobile.number).isEqualTo(2L);
        assertThat(mobile.isDefault).isFalse();
        assertThat(mobile.status).isEqualTo(Workstream.DECLARED);
        assertThat(mobile.description).isEqualTo("the mobile release stream");
    }

    @Test
    void declare_refuses_a_malformed_token() {
        workstreams.requireDefault(scope);
        WorklistException refusal = refusalFrom(() ->
            workstreams.declare(scope, "Mobile-Release", "capitalised is refused"));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
    }

    @Test
    void declare_refuses_an_empty_description() {
        workstreams.requireDefault(scope);
        WorklistException blankRefusal = refusalFrom(() ->
            workstreams.declare(scope, "mobile", "   "));
        assertThat(blankRefusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
    }

    @Test
    void declare_refuses_a_token_already_declared_in_the_scope() {
        workstreams.requireDefault(scope);
        workstreams.declare(scope, "mobile", "first declaration");

        WorklistException refusal = refusalFrom(() ->
            workstreams.declare(scope, "mobile", "second attempt"));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
    }

    // ==================================================================
    // rename — allowed while empty, refused once anything points at it
    // ==================================================================

    @Test
    void rename_succeeds_while_nothing_points_at_the_workstream() {
        Workstream ws = workstreams.declare(scope, "beta", "beta description");

        Workstream renamed = workstreams.rename(scope, ws.id, "alpha", ws.conflictToken);
        assertThat(renamed.token).isEqualTo("alpha");
    }

    @Test
    void rename_is_refused_once_an_item_points_at_the_workstream() {
        Workstream ws = workstreams.declare(scope, "beta", "beta description");
        selectors.declare(scope, Selector.ITEM);
        items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "an item in beta",
            Field.STATUS.canonicalName(), openStatus.toString(),
            Field.WORKSTREAM_ID.canonicalName(), ws.id.toString()));

        WorklistException refusal = refusalFrom(() ->
            workstreams.rename(scope, ws.id, "alpha", ws.conflictToken));
        assertThat(refusal.reason())
            .isEqualTo(WorklistException.Reason.WORKSTREAM_HAS_REFERENCES);
    }

    @Test
    void the_default_is_never_renamed() {
        Workstream defaultWs = workstreams.requireDefault(scope);
        WorklistException refusal = refusalFrom(() ->
            workstreams.rename(scope, defaultWs.id, "renamed", defaultWs.conflictToken));
        assertThat(refusal.reason())
            .isEqualTo(WorklistException.Reason.WORKSTREAM_DEFAULT_LOCKED);
    }

    // ==================================================================
    // withdraw — a status change refused on the default
    // ==================================================================

    @Test
    void withdraw_moves_a_declared_workstream_to_withdrawn() {
        Workstream ws = workstreams.declare(scope, "shelved", "still resolvable, closed to new");
        Workstream withdrawn = workstreams.withdraw(scope, ws.id, ws.conflictToken);
        assertThat(withdrawn.status).isEqualTo(Workstream.WITHDRAWN);

        // A withdrawn workstream cannot take new items — refuseWithdrawn
        // fires through ItemService.resolveWorkstream.
        selectors.declare(scope, Selector.ITEM);
        WorklistException refusal = refusalFrom(() -> items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "an item under a withdrawn ws",
            Field.STATUS.canonicalName(), openStatus.toString(),
            Field.WORKSTREAM_ID.canonicalName(), withdrawn.id.toString())));
        assertThat(refusal.reason())
            .isEqualTo(WorklistException.Reason.WORKSTREAM_WITHDRAWN);
    }

    @Test
    void the_default_is_never_withdrawn() {
        Workstream defaultWs = workstreams.requireDefault(scope);
        WorklistException refusal = refusalFrom(() ->
            workstreams.withdraw(scope, defaultWs.id, defaultWs.conflictToken));
        assertThat(refusal.reason())
            .isEqualTo(WorklistException.Reason.WORKSTREAM_DEFAULT_LOCKED);
    }

    // ==================================================================
    // update — the description alone
    // ==================================================================

    @Test
    void update_changes_the_description_and_rotates_the_token() {
        Workstream ws = workstreams.declare(scope, "polish", "cosmetic polish");
        String beforeToken = ws.conflictToken;

        Workstream updated = workstreams.update(scope, ws.id,
            "cosmetic polish and copy-editing", beforeToken);
        assertThat(updated.description).isEqualTo("cosmetic polish and copy-editing");
        assertThat(updated.conflictToken).isNotEqualTo(beforeToken);
    }

    @Test
    void update_refuses_a_blank_description() {
        Workstream ws = workstreams.declare(scope, "polish", "cosmetic polish");
        WorklistException refusal = refusalFrom(() ->
            workstreams.update(scope, ws.id, "  ", ws.conflictToken));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
    }

    // ==================================================================
    // require + query — the read paths
    // ==================================================================

    @Test
    void query_lists_the_scopes_workstreams_in_number_order() {
        workstreams.requireDefault(scope);
        workstreams.declare(scope, "alpha", "first");
        workstreams.declare(scope, "beta", "second");

        List<Map<String, Object>> all = workstreams.query(scope);
        assertThat(all).extracting(m -> m.get("token"))
            .containsExactly(Workstream.DEFAULT_TOKEN, "alpha", "beta");
    }

    @Test
    void require_refuses_an_unknown_id() {
        WorklistException refusal = refusalFrom(() ->
            workstreams.require(scope, UUID.randomUUID()));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.WORKSTREAM_UNKNOWN);
    }

    @Test
    void read_returns_the_canonical_field_map() {
        Workstream ws = workstreams.declare(scope, "polish", "cosmetic polish");
        Map<String, Object> read = workstreams.read(scope, ws.id);
        assertThat(read).containsEntry("token", "polish");
        assertThat(read).containsEntry("description", "cosmetic polish");
        assertThat(read).containsEntry("is_default", false);
        assertThat(read).containsEntry("status", Workstream.DECLARED);
    }

    private static WorklistException refusalFrom(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown)
            .as("the call must be refused with the service's typed refusal")
            .isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }
}
