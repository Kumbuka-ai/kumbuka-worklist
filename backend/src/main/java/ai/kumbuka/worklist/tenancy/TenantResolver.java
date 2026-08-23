package ai.kumbuka.worklist.tenancy;

import java.util.UUID;

/**
 * Resolves the tenant for the current request scope.
 *
 * <p>Application code never reads this resolver directly. Both the Hibernate
 * per-session tenant lookup and the PostgreSQL session-GUC setter go through
 * {@link TenantContext#current()}, which returns a programmatically bound
 * tenant when one is present and delegates here otherwise. That single read
 * point is what prevents a split brain in which the ORM filter and the
 * database policy disagree about who is asking.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>Returns the tenant for the current request scope. <strong>Never
 *       null.</strong> A missing tenant context is a programming error, and
 *       an implementation throws rather than guesses — a guessed tenant is
 *       the one failure mode row-level security cannot catch, because the
 *       query is then genuinely well-formed for the wrong tenant.</li>
 *   <li>Stable for the duration of a transaction, and free of side effects:
 *       the framework may call it from a request filter, from an interceptor
 *       and from the ORM's session lookup within one transaction.</li>
 * </ul>
 */
public interface TenantResolver {

    /** @return the tenant for the current request scope. Never null. */
    UUID currentTenant();
}
