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
 * The selector is the view, and a token outside the three is refused rather
 * than declared.
 *
 * <h2>What is being defended</h2>
 *
 * The selector used to be the item's family — {@code FEAT}, {@code CHORE},
 * {@code BUG} — and a scope could declare as many as it liked. It is the view
 * now, and there are three of them: they are the platform's object model rather
 * than a scope's vocabulary. A fourth declared token would open an address
 * space for a kind of thing this service does not hold, and every address
 * issued under it would resolve to nothing while looking perfectly well formed.
 *
 * <p>This is the one place that can refuse it. The database constraint checks
 * the FORM — lower case, a leading letter, interior hyphens — because form is
 * decidable without knowing anything about a deployment. Which tokens name
 * views is not form, and a constraint carrying the three literals would answer
 * a caller with a constraint violation where a typed refusal belongs.
 *
 * <h2>The red state, and how it was observed</h2>
 *
 * The refusal is one block in {@code SelectorRegistry.declare}. Removing it —
 * which is exactly what this file's absence would have looked like — makes
 * {@link #a_token_outside_the_three_views_is_refused_and_not_declared} fail:
 * the foreign token passes the form check, is inserted, and comes back in the
 * scope's list of address spaces. Measured on 2026-09-05, with {@code sprint}
 * as the token, which is well formed and is the name of something this platform
 * genuinely has — in another service.
 *
 * <h2>The counter-probe</h2>
 *
 * Every case below has one, because a registry that refused every declaration
 * would satisfy the first half of each. The counter-probe is the same call with
 * a view: it has to be accepted, and the token has to come back.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class SelectorViewProbeIT {

    /**
     * Well formed, and the name of something this platform has elsewhere.
     *
     * <p>Chosen rather than invented: a nonsense string would be refused by any
     * check at all, including one that only looked plausible. This one is a real
     * word from the sibling service's address space, which is exactly the token
     * somebody would reach for.
     */
    private static final String FOREIGN_TOKEN = "sprint";

    @Inject SelectorRegistry selectors;

    @Test
    void a_token_outside_the_three_views_is_refused_and_not_declared() {
        UUID scope = UUID.randomUUID();

        WorklistException refusal = refusalFrom(() -> selectors.declare(scope, FOREIGN_TOKEN));

        assertThat(refusal.reason())
            .as("a typed refusal naming the class of the problem, so that a caller can "
                + "tell it from an undeclared view — which is fixed by declaring, and this "
                + "one never is")
            .isEqualTo(WorklistException.Reason.VIEW_UNKNOWN);
        assertThat(refusal.offenders()).containsExactly(FOREIGN_TOKEN);
        assertThat(refusal.getMessage())
            .as("and the message names the three, because the remedy is to pick one of "
                + "them and a refusal that withheld the list would send the caller reading "
                + "source")
            .contains(Selector.ITEM).contains(Selector.ITERATION).contains(Selector.MILESTONE);

        // RED STATE, by its trace: with the refusal removed the token passes the
        // form check and is inserted, and this is where it would be.
        assertThat(selectors.inScope(scope).stream().map(s -> s.token).toList())
            .as("RED STATE, observed by its absence: without the view check the token is "
                + "well formed, so it would be declared — and the scope would carry a "
                + "fourth address space for a kind of thing this service does not hold")
            .doesNotContain(FOREIGN_TOKEN);
    }

    /**
     * The counter-probe: a view is declared, and comes back.
     *
     * <p>Without this the assertion above would hold just as well against a
     * registry that had stopped declaring anything at all — and that failure
     * mode is not hypothetical: it is what a check written one line too early
     * produces.
     */
    @Test
    void a_view_is_declared_and_comes_back() {
        UUID scope = UUID.randomUUID();

        for (String view : Selector.VIEWS) {
            Selector declared = selectors.declare(scope, view);
            assertThat(declared.token).isEqualTo(view);
            assertThat(declared.status).isEqualTo(Selector.DECLARED);
        }

        assertThat(selectors.inScope(scope).stream().map(s -> s.token).toList())
            .as("all three, and only three: the address spaces of a scope are the views it "
                + "can hold, and there is no fourth")
            .containsExactlyInAnyOrderElementsOf(Selector.VIEWS);
    }

    /**
     * Form is checked before the vocabulary, and the two refusals are apart.
     *
     * <p>{@code Item} is what a caller types by habit. It is refused as a form
     * error and never folded to {@code item}: folding would make two strings
     * resolve to one selector, which is an identity statement arrived at by
     * leniency. That it is a FORM error and not a view error is the part worth
     * asserting — the two stages of the check order are two stages here too, and
     * a token that is not a token is decidable without knowing what this
     * platform holds.
     */
    @Test
    void upper_case_is_a_form_error_and_not_a_vocabulary_one() {
        UUID scope = UUID.randomUUID();

        assertThat(refusalFrom(() -> selectors.declare(scope, "Item")).reason())
            .as("upper case is rejected rather than folded, and it is rejected by the form "
                + "check — which runs first and needs to know nothing about views")
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);

        assertThat(refusalFrom(() -> selectors.declare(scope, "ITEM")).reason())
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);

        assertThat(selectors.inScope(scope))
            .as("and neither spelling left anything behind")
            .isEmpty();
    }

    /**
     * The refusal is the domain's, and the database agrees about the form.
     *
     * <p>The Java pattern and {@code ck_selector_token} are the same language,
     * and they must not drift: a Java check that accepted what the database
     * rejects would turn a refusal into a constraint violation reaching the
     * caller as a 500, and the other way round would let a token through that
     * nothing can store. The form refusal above is what keeps the constraint
     * from ever being reached; this asserts that the constraint would have
     * caught it too.
     */
    @Test
    void the_database_carries_the_same_form_rule() {
        Throwable refused = catchThrowable(() -> ai.kumbuka.worklist.platform.PlatformFixture
            .run("SELECT set_config('app.tenant_id', '"
                    + SubstrateDatabaseResource.TENANT_ID + "', false)",
                "INSERT INTO worklist.selector (tenant_id, scope_id, token) VALUES ('"
                    + SubstrateDatabaseResource.TENANT_ID + "', '"
                    + UUID.randomUUID() + "', 'FEAT')"));

        assertThat(refused)
            .as("the constraint has to refuse upper case as well, or the two expressions "
                + "have drifted and the one that is wider decides")
            .isNotNull();
        assertThat(refused.getMessage()).contains("ck_selector_token");
    }

    private static WorklistException refusalFrom(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown)
            .as("the call must be refused, and refused with this service's typed refusal "
                + "rather than with whatever the database raised")
            .isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }
}
