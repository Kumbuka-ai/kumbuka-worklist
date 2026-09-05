package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Turning an address into the row it names, and the four ways that fails.
 *
 * <p>Every one of them is a refusal a caller acts on differently, which is why
 * they are four and not one. "The view is not declared here" is fixed by
 * declaring it; "there is no object at that number" is fixed by looking
 * elsewhere; "the scope is working no iteration" is fixed by waiting or
 * advancing. A store that answered all three the same way would send every
 * caller down the same wrong path.
 *
 * <p>The order matters as much as the answers. The vocabulary question — is
 * this view declared in this scope — is asked BEFORE the row is looked for,
 * which is stage 3 before stage 4 of the ratified check order. Asked the other
 * way round, a scope that had not declared a view would answer "no such object"
 * for every number in it, and the missing declaration would never surface.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class AddressResolutionIT {

    @Inject AddressRegistry addresses;
    @Inject SelectorRegistry selectors;

    @Test
    void an_undeclared_view_is_refused_before_the_row_is_looked_for() {
        UUID scope = UUID.randomUUID();

        for (Runnable resolution : new Runnable[] {
            () -> addresses.itemAt(scope, 1),
            () -> addresses.iterationAt(scope, 1),
            () -> addresses.milestoneAt(scope, 1),
            () -> addresses.currentIteration(scope)}) {

            assertThat(refusalFrom(resolution).reason())
                .as("the vocabulary stage runs before the resolution stage. The other way "
                    + "round, a scope that had not declared a view would answer 'no such "
                    + "object' for every number in it")
                .isEqualTo(WorklistException.Reason.SELECTOR_UNDECLARED);
        }
    }

    @Test
    void each_view_names_its_own_absence() {
        UUID scope = UUID.randomUUID();
        for (String view : Selector.VIEWS) {
            selectors.declare(scope, view);
        }

        assertThat(refusalFrom(() -> addresses.itemAt(scope, 999)).reason())
            .isEqualTo(WorklistException.Reason.ITEM_UNKNOWN);
        assertThat(refusalFrom(() -> addresses.iterationAt(scope, 999)).reason())
            .isEqualTo(WorklistException.Reason.ITERATION_UNKNOWN);
        assertThat(refusalFrom(() -> addresses.milestoneAt(scope, 999)).reason())
            .as("three reasons rather than one not-found, because a caller holding an "
                + "address that does not resolve needs to know which axis it was on")
            .isEqualTo(WorklistException.Reason.MILESTONE_UNKNOWN);

        assertThat(refusalFrom(() -> addresses.itemAt(scope, 999)).offenders())
            .as("and the refusal names the address it could not resolve, so the reader "
                + "does not have to reconstruct it from the call")
            .containsExactly(Selector.ITEM + "/999");
    }

    /**
     * The pointer addresses nothing while a scope is working no iteration.
     *
     * <p>A state every scope passes through: between a close and the next
     * advance there is no current iteration, and {@code iteration/current}
     * resolves to nothing. It is answered apart from an unknown iteration
     * because nothing was addressed wrongly — the caller asked which one is
     * current and the answer is "none right now".
     */
    @Test
    void the_pointer_addresses_nothing_while_a_scope_works_no_iteration() {
        UUID scope = UUID.randomUUID();
        selectors.declare(scope, Selector.ITERATION);

        WorklistException refusal = refusalFrom(() -> addresses.currentIteration(scope));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.ITERATION_ABSENT);
        assertThat(refusal.getMessage())
            .as("and it says what the state is rather than what is missing: a scope with "
                + "no settings row has no pointer either, and the caller asked about the "
                + "iteration, not about the shape of the record holding the answer")
            .contains("working no iteration");
    }

    private static WorklistException refusalFrom(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown)
            .as("the call must be refused with this service's typed refusal rather than "
                + "with an empty result: under row-level security a missing binding and a "
                + "missing row look identical from outside")
            .isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }
}
