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
 * Class 3 of the ratified violations: an iteration is closed only when
 * what was produced is NAMED. Presence is checked, form is not.
 *
 * <p>The red state is the removal of {@code requireNamed(produced)} from
 * {@link IterationService#close(UUID, UUID, String, String)} — with it
 * gone, a null or blank produced-name passes silently and the iteration
 * closes on nothing. The probes below observe the refusal.
 *
 * <p>Also probes that the compatibility shim (the 3-arg
 * {@code close(scope, id, token)}) forwards with null and fails loud —
 * so any REST or MCP caller wired against the old signature breaks
 * rather than closing without a naming.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class IterationProducedCloseIT {

    @Inject IterationService iterations;
    @Inject ScopeSettingService settings;
    @Inject SelectorRegistry selectors;

    private UUID scope;

    @BeforeEach
    void aScopeOfItsOwn() {
        scope = UUID.randomUUID();
        selectors.declare(scope, Selector.ITERATION);
        settings.create(scope, Map.of(
            "max_planned_iterations", 10, "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10, "warn_memberships_per_iteration", 9));
    }

    @Test
    void close_with_a_named_produced_succeeds() {
        UUID iterationId = createIteration();
        String token = tokenOf(iterationId);

        Map<String, Object> closed = iterations.close(scope, iterationId,
            "a specification and a set of corpus nodes", token);

        assertThat(closed.get(Field.CLOSED_AT.canonicalName()))
            .as("close writes the timestamp when produced is named")
            .isNotNull();
    }

    @Test
    void close_without_a_produced_is_refused() {
        UUID iterationId = createIteration();
        String token = tokenOf(iterationId);

        WorklistException refusal = refusalFrom(() ->
            iterations.close(scope, iterationId, null, token));
        assertThat(refusal.reason())
            .isEqualTo(WorklistException.Reason.ITERATION_PRODUCED_MISSING);
    }

    @Test
    void close_with_a_blank_produced_is_refused() {
        UUID iterationId = createIteration();
        String token = tokenOf(iterationId);

        WorklistException refusal = refusalFrom(() ->
            iterations.close(scope, iterationId, "   ", token));
        assertThat(refusal.reason())
            .isEqualTo(WorklistException.Reason.ITERATION_PRODUCED_MISSING);
    }

    @Test
    void close_with_an_empty_produced_is_refused() {
        UUID iterationId = createIteration();
        String token = tokenOf(iterationId);

        WorklistException refusal = refusalFrom(() ->
            iterations.close(scope, iterationId, "", token));
        assertThat(refusal.reason())
            .isEqualTo(WorklistException.Reason.ITERATION_PRODUCED_MISSING);
    }

    @Test
    void the_three_arg_shim_fails_loud_and_names_the_missing_produced() {
        UUID iterationId = createIteration();
        String token = tokenOf(iterationId);

        WorklistException refusal = refusalFrom(() ->
            iterations.close(scope, iterationId, token));
        assertThat(refusal.reason())
            .isEqualTo(WorklistException.Reason.ITERATION_PRODUCED_MISSING);
    }

    // ==================================================================
    // Planting.
    // ==================================================================

    private UUID createIteration() {
        return (UUID) iterations.create(scope, Map.of(
            Field.MOTTO.canonicalName(), "a motto",
            Field.DESCRIPTION.canonicalName(), "a description"))
            .get(Field.ID.canonicalName());
    }

    private String tokenOf(UUID iterationId) {
        return (String) iterations.read(scope, iterationId)
            .get(Field.CONFLICT_TOKEN.canonicalName());
    }

    private static WorklistException refusalFrom(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown)
            .as("the call must be refused with the service's typed refusal")
            .isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }
}
