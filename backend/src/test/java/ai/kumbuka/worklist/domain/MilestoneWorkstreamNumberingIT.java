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
 * The milestone counter runs per workstream — two workstreams
 * legitimately share a milestone number, and the address form
 * disambiguates through the scope alone because the workstream is a
 * field at the milestone and never an address component.
 *
 * <p>Also probes that a milestone's workstream is write-once (from
 * create) and refuses a later move — the milestone's number was
 * allocated from THIS workstream's counter, and moving the row would
 * detach the number from its axis.
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
    void milestones_in_different_workstreams_share_the_same_number_line() {
        workstreams.requireDefault(scope);
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

        assertThat(firstInMobile)
            .as("mobile's milestone counter opens at 1")
            .isEqualTo(1L);
        assertThat(firstInBackend)
            .as("backend's milestone counter opens at 1 too — the two axes are independent")
            .isEqualTo(1L);
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
    void milestone_update_with_a_different_workstream_is_refused() {
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");
        Workstream backend = workstreams.declare(scope, "backend", "backend stream");

        Map<String, Object> created = milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), "settled milestone",
            Field.VISION.canonicalName(), "vision",
            Field.WORKSTREAM_ID.canonicalName(), mobile.id.toString()));
        UUID milestoneId = (UUID) created.get(Field.ID.canonicalName());
        String token = (String) created.get(Field.CONFLICT_TOKEN.canonicalName());

        WorklistException refusal = refusalFrom(() -> milestones.update(scope, milestoneId,
            Map.of(
                Field.WORKSTREAM_ID.canonicalName(), backend.id.toString(),
                Field.CONFLICT_TOKEN.canonicalName(), token)));
        assertThat(refusal.reason())
            .isEqualTo(WorklistException.Reason.WORKSTREAM_MILESTONE_MISMATCH);
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
