package ai.kumbuka.worklist.fixture;

import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.persistence.EntityManager;

import java.util.List;

/**
 * A genuine violation, kept so the tripwire that looks for it is observed
 * catching something.
 *
 * <p>{@code @TenantBound} at class level and an {@link EntityManager} to
 * reach the database with — and a public method with no
 * {@code @Transactional}. The binding interceptor sets the database session
 * variable INSIDE a transaction; with no transaction there is nothing to
 * bind, so the policy predicate is NULL and the read comes back empty.
 *
 * <p>The reason that is worth a tripwire rather than a code review is what
 * the emptiness then looks like. It is not an error. It is
 * indistinguishable from "there is nothing there", so a caller that turns an
 * empty result into a typed refusal reports the wrong cause with complete
 * confidence — "this selector is not declared" about a selector that is.
 *
 * <p>Never instantiated and never wired: the check reads the class, it does
 * not run it.
 */
@TenantBound
public class UnboundReadFixture {

    EntityManager em;

    /** Public, reaches the database, and carries no transaction. */
    public List<Object> everythingInScope() {
        return em.createQuery("SELECT i FROM Item i", Object.class).getResultList();
    }
}
