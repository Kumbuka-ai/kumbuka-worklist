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
 *   <li>Two milestones in one scope get consecutive numbers, and there is
 *       no second counter shape for them to be distributed over.
 *   <li>Address resolution through the workstream selector still works —
 *       the workstream is a view of its own and keeps its own number line;
 *       that is what the retraction did NOT touch.
 * </ul>
 *
 * <p>The "different workstreams share a number" and "update is refused"
 * shapes are gone; their reversal is asserted in
 * {@link MilestoneWorkstreamDecouplingIT}.
 *
 * <p>Two more went on 2026-09-28, when {@code workstream} came
 * off the milestone in both directions. A create naming one and a create
 * falling back to the scope's default were both assertions about a field
 * that no longer exists on a milestone; the refusal that replaced them is
 * in {@code MilestoneWorkstreamRetractionIT}.
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

    /**
     * Two milestones in a scope get 1 and 2, whatever workstreams exist beside
     * them.
     *
     * <p>The workstreams are declared and then ignored on purpose: what this
     * asserts is that declaring one opens no second milestone counter for it.
     * Under V11 each workstream carried a milestone counter of its own, and
     * the second create here would have taken 1 from a different row.
     */
    @Test
    void milestones_number_sequentially_scope_wide() {
        workstreams.declare(scope, "mobile", "mobile stream");
        workstreams.declare(scope, "backend", "backend stream");

        Long first = (Long) milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), "goal 1",
            Field.VISION.canonicalName(), "vision 1"))
            .get(Field.NUMBER.canonicalName());
        Long second = (Long) milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), "goal 2",
            Field.VISION.canonicalName(), "vision 2"))
            .get(Field.NUMBER.canonicalName());

        assertThat(first).isEqualTo(1L);
        assertThat(second)
            .as("one counter per scope for the milestone selector, and the two "
                + "declared workstreams above added none")
            .isEqualTo(2L);
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
