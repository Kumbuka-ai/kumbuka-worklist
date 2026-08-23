package ai.kumbuka.worklist.platform;

import ai.kumbuka.worklist.domain.WorklistException;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * Resolves a scope name against the platform's published read contract.
 *
 * <p>This service holds no scope table of its own and never reads the
 * platform's base tables. It holds {@code SELECT} on exactly one view and
 * nothing else, and the view answers one question — may this subject enter
 * this scope — without publishing the membership that produces the answer.
 * Existence in the result IS the permission.
 *
 * <h2>The session contract</h2>
 *
 * Two settings, both bound <strong>transaction-locally</strong>:
 * {@code app.tenant_id} and {@code app.subject}. Transaction-local is not a
 * detail. A session-wide {@code SET} survives the connection's return to the
 * pool, so the next caller on that connection inherits the previous caller's
 * subject — a leak that appears under load, on a warm pool, and never in a
 * test.
 *
 * <h2>Why an empty result is an error here</h2>
 *
 * Under row-level security a missing transaction boundary produces zero rows,
 * and zero rows reads exactly like "no such scope". That resemblance is the
 * trap: the plausible repair for "no such scope" is to widen a privilege or
 * to fall back on a local table, and both would be repairs to a symptom whose
 * cause was a forgotten binding. So the binding is checked first and
 * separately, and its absence is a different typed error from an
 * unresolvable scope. Neither is ever an empty return.
 */
@ApplicationScoped
@TenantBound
public class ScopeDirectory {

    /** Bound by the same convention as every logger here: no title, no body, no
     *  metadata text, no token, and no actor. A slug is a scope name and an
     *  address; the subject that asked for it is the audit log's business. */
    private static final Logger LOG = Logger.getLogger(ScopeDirectory.class);

    @Inject EntityManager em;

    /**
     * The scope a caller named, or a typed refusal.
     *
     * @param subject the calling subject, as derived from the token
     * @param slug    the scope name the caller used
     */
    @Transactional
    public ScopeAccess resolve(String subject, String slug) {
        bindSubject(subject);
        requireSessionBound();

        List<Object[]> rows = em.createNativeQuery("""
                SELECT scope_id, tenant_id, slug, archived
                FROM platform.scope_access
                WHERE slug = :slug
                """)
            .setParameter("slug", slug)
            .getResultList();

        if (rows.isEmpty()) {
            // Reached only with both settings bound, so this genuinely means
            // "no such scope for this subject" and not "nothing was bound".
            LOG.warnf("scope '%s' unresolved: %s", slug,
                WorklistException.Reason.SCOPE_UNRESOLVED);
            throw new WorklistException(WorklistException.Reason.SCOPE_UNRESOLVED,
                "no scope '" + slug + "' is open to this subject. The directory answers "
                    + "for the bound subject only, and existence in its answer is the "
                    + "permission — so this is a refusal, not a missing row to be "
                    + "worked around.");
        }

        LOG.debugf("resolved scope '%s'", slug);
        Object[] row = rows.get(0);
        return new ScopeAccess(
            (UUID) row[0],
            (UUID) row[1],
            (String) row[2],
            (Boolean) row[3]);
    }

    /**
     * Binds the calling subject for this transaction.
     *
     * <p>{@code is_local = true} is the whole safety property: the value resets
     * at commit or rollback and cannot ride a pooled connection into the next
     * caller's transaction.
     */
    private void bindSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new WorklistException(WorklistException.Reason.SESSION_NOT_BOUND,
                "there is no subject to bind to app.subject. The directory answers for a "
                    + "subject, so resolving without one would be asking a question with "
                    + "no asker — and the answer would be zero rows, which reads as "
                    + "'no such scope'.");
        }
        em.createNativeQuery("SELECT set_config('app.subject', :v, true)")
            .setParameter("v", subject)
            .getSingleResult();
    }

    /**
     * Fails loudly when either setting is unbound.
     *
     * <p>This runs BEFORE the query rather than interpreting its result,
     * because after the fact the two cases are indistinguishable: both produce
     * zero rows. Checking first is what lets the refusal name the actual cause,
     * and naming the cause is what stops the next person from repairing the
     * wrong thing.
     */
    private void requireSessionBound() {
        Object tenant = em.createNativeQuery(
            "SELECT NULLIF(current_setting('app.tenant_id', true), '')").getSingleResult();
        Object subject = em.createNativeQuery(
            "SELECT NULLIF(current_setting('app.subject', true), '')").getSingleResult();

        if (tenant == null || subject == null) {
            LOG.warnf("directory call with unbound session: %s",
                WorklistException.Reason.SESSION_NOT_BOUND);
            throw new WorklistException(WorklistException.Reason.SESSION_NOT_BOUND,
                ("the session contract is not bound (app.tenant_id=%s, app.subject=%s), so "
                    + "the directory would return zero rows for every scope. That reads as "
                    + "'no such scope' and invites a repair to the privileges — which is "
                    + "why this fails here instead of returning nothing.")
                    .formatted(tenant == null ? "unset" : "set",
                               subject == null ? "unset" : "set"));
        }
    }

    /**
     * One row of the read contract: the access answer, never the membership
     * behind it.
     *
     * <p>{@code archived} is published rather than filtered, deliberately: a
     * write into a retired scope must be refusable with a specific error
     * rather than with "not found", and a directory that hid archived scopes
     * could not tell the two apart.
     */
    public record ScopeAccess(UUID scopeId, UUID tenantId, String slug, boolean archived) {
    }
}
