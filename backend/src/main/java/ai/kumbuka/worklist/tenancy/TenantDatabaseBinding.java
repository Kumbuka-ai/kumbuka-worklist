package ai.kumbuka.worklist.tenancy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * Sets the PostgreSQL session GUC {@code app.tenant_id} once per transaction
 * — layer 2 of the enforcement model, the half the database itself applies.
 *
 * <p>The policies read {@code current_setting('app.tenant_id', true)}.
 * Without this binding every query would fail closed, because an unset
 * setting yields NULL and the policy predicate then matches nothing. Failing
 * closed is the correct behaviour and it is also an unhelpful symptom: an
 * unbound transaction looks exactly like a correctly isolated one that has
 * no rows.
 *
 * <p>{@code set_config(…, is_local = true)} is {@code SET LOCAL} expressed as
 * SQL: the value is scoped to the current transaction and resets on commit or
 * rollback, so a connection handed back to the pool never carries one
 * caller's tenant into the next caller's transaction.
 */
@ApplicationScoped
public class TenantDatabaseBinding {

    private static final Logger LOG = Logger.getLogger(TenantDatabaseBinding.class);
    private static final String BOUND_KEY = "ai.kumbuka.worklist.tenancy.bound";

    @Inject TenantContext context;
    @Inject EntityManager em;
    @Inject TransactionSynchronizationRegistry txReg;

    /**
     * Bind the current tenant onto the active transaction. Idempotent per
     * transaction; a second call is a no-op, and a second call with a
     * DIFFERENT tenant is logged because it can only be a programming error —
     * one transaction cannot honestly belong to two tenants.
     *
     * <p>Called from inside an open transaction boundary. Outside one there
     * is no envelope for {@code SET LOCAL} and the call returns quietly; the
     * ORM filter still applies, and a tenant-scoped read that reaches the
     * database outside a transaction would be caught by the architecture
     * probe over raw SQL instead.
     */
    public void bindCurrentTransaction() {
        Set<String> alreadyBound = ensureRegistry();
        if (alreadyBound == null) {
            return;
        }
        String tenant = context.current().toString();
        if (alreadyBound.contains(tenant)) {
            return;
        }
        if (!alreadyBound.isEmpty()) {
            LOG.warnf("tenant rebinding inside one transaction: %s -> %s", alreadyBound, tenant);
        }
        em.createNativeQuery("SELECT set_config('app.tenant_id', :v, true)")
            .setParameter("v", tenant)
            .getSingleResult();
        alreadyBound.add(tenant);
    }

    /** Whether the current transaction already carries a binding. Does not bind. */
    public boolean isBoundOnCurrentTransaction() {
        try {
            return txReg.getResource(BOUND_KEY) instanceof Set<?> s && !s.isEmpty();
        } catch (RuntimeException noTx) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> ensureRegistry() {
        Object existing;
        try {
            existing = txReg.getResource(BOUND_KEY);
        } catch (RuntimeException noTx) {
            return null;
        }
        if (existing instanceof Set<?>) {
            return (Set<String>) existing;
        }
        Set<String> bound = new HashSet<>();
        try {
            txReg.putResource(BOUND_KEY, bound);
            txReg.registerInterposedSynchronization(new ResetSync(bound));
        } catch (RuntimeException refused) {
            // The registry refused (transaction in an unexpected state). The
            // synchronization is housekeeping only — SET LOCAL resets itself
            // at commit or rollback either way — so skipping it is safe.
            return null;
        }
        return bound;
    }

    /** Clears the per-transaction record at commit or rollback. */
    private static final class ResetSync implements Synchronization {
        private final Set<String> bound;

        ResetSync(Set<String> bound) {
            this.bound = bound;
        }

        @Override public void beforeCompletion() { /* no-op */ }

        @Override public void afterCompletion(int status) { bound.clear(); }
    }
}
