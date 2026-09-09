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
 * The milestone counter runs scope-wide again after V12 (2026-09-09,
 * TAR-0002 section 4, REQ-0148 obsolete). This suite keeps the checks
 * that survive the rollback:
 *
 * <ul>
 *   <li>Two milestones in one scope get consecutive numbers regardless
 *       of the workstream at either row.
 *   <li>A create without a workstream still resolves to the scope's
 *       default (the column is dead but the create path still populates
 *       it for the image cycle).
 *   <li>Address resolution through the workstream selector still works.
 * </ul>
 *
 * <p>The "different workstreams share a number" and "update is refused"
 * shapes are gone; their reversal is asserted in
 * {@link MilestoneWorkstreamDecouplingIT}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class MilestoneWorkstreamNumberingIT {

    @Inject MilestoneService milestones;
    @Inject WorkstreamService workstreams;
    @Inject SelectorRegistry selectors;
    @Inject AddressRegistry addresses;
    @Inject ScopeSettingService settings;

    private UUID scope;

    @BeforeEach
    void aScopeOfItsOwn() {
        scope = UUID.randomUUID();
        selectors.declare(scope, Selector.MILESTONE);
        settings.create(scope, Map.of(
            "max_planned_iterations", 10, "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10, "warn_memberships_per_iteration", 9));
    }

    @Test
    void milestones_in_one_workstream_number_sequentially() {
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");

        Long first = (Long) milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), "mobile goal 1",
            Field.VISION.canonicalName(), "vision 1",
            Field.WORKSTREAM_ID.canonicalName(), mobile.id.toString()))
            .get(Field.NUMBER.canonicalName());
        Long second = (Long) milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), "mobile goal 2",
            Field.VISION.canonicalName(), "vision 2",
            Field.WORKSTREAM_ID.canonicalName(), mobile.id.toString()))
            .get(Field.NUMBER.canonicalName());

        assertThat(first).isEqualTo(1L);
        assertThat(second).isEqualTo(2L);
    }

    @Test
    void milestone_create_without_workstream_uses_the_default() {
        Workstream defaultWs = workstreams.requireDefault(scope);

        Map<String, Object> created = milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), "default-workstream goal",
            Field.VISION.canonicalName(), "vision"));
        assertThat(created.get(Field.WORKSTREAM_ID.canonicalName())).isEqualTo(defaultWs.id);
    }

    @Test
    void milestone_update_echoing_the_same_workstream_is_a_no_op() {
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");

        Map<String, Object> created = milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), "echoed milestone",
            Field.VISION.canonicalName(), "vision",
            Field.WORKSTREAM_ID.canonicalName(), mobile.id.toString()));
        UUID milestoneId = (UUID) created.get(Field.ID.canonicalName());
        String token = (String) created.get(Field.CONFLICT_TOKEN.canonicalName());

        // Echoing the same value — reading and writing back — must be a
        // no-op, not a refusal.
        Map<String, Object> updated = milestones.update(scope, milestoneId, Map.of(
            Field.WORKSTREAM_ID.canonicalName(), mobile.id.toString(),
            Field.CONFLICT_TOKEN.canonicalName(), token));
        assertThat(updated.get(Field.WORKSTREAM_ID.canonicalName())).isEqualTo(mobile.id);
    }

    @Test
    void workstreamAt_resolves_a_workstreams_address() {
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");

        UUID resolved = addresses.workstreamAt(scope, mobile.number);
        assertThat(resolved).isEqualTo(mobile.id);
    }

    @Test
    void workstreamAt_refuses_an_unknown_number() {
        WorklistException refusal = refusalFrom(() ->
            addresses.workstreamAt(scope, 99_999L));
        assertThat(refusal.reason())
            .isEqualTo(WorklistException.Reason.WORKSTREAM_UNKNOWN);
    }

    private static WorklistException refusalFrom(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown)
            .as("the call must be refused with the service's typed refusal")
            .isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }
}
