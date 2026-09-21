package ai.kumbuka.worklist.platform;

import ai.kumbuka.worklist.domain.WorklistException;
import ai.kumbuka.worklist.repository.ScopeAccessRepository;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
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

    /**
     * The one scope kind this service refuses outright.
     *
     * <p>Spelled as the core spells it in {@code platform.scope.kind}; the
     * other two values, {@code project} and {@code global}, are both served
     * and neither needs a constant to be served.
     */
    private static final String PRIVATE_KIND = "private";

    @Inject ScopeAccessRepository scopes;

    /**
     * The scope a caller named, or a typed refusal.
     *
     * <p>Four questions, in this order, and the order is the contract:
     *
     * <ol>
     *   <li>is there a row for this subject — no, and the answer is the
     *       not-found class, whether the scope is absent or merely invisible;
     *   <li>is it a kind this service serves — no, and the answer says so;
     *   <li>is the act a write into a locked scope;
     *   <li>is the act a write this subject may not make.
     * </ol>
     *
     * <p>Two and three cannot be swapped with one: everything after the first
     * question is said only about a scope the read contract has already
     * published to this subject, which is what keeps a distinguishable answer
     * from becoming an enumeration oracle (ADR-0011). Three cannot be swapped
     * with four — see {@link WorklistException.Reason#SCOPE_LOCKED}.
     *
     * @param subject the calling subject, as derived from the token
     * @param slug    the scope name the caller used
     * @param access  whether the act about to run reads or writes. Passed in
     *                rather than inferred: the directory knows what the
     *                contract says about the scope, and only the surface knows
     *                what the verb is about to do with it.
     */
    @Transactional
    public ScopeAccess resolve(String subject, String slug, Access access) {
        bindSubject(subject);
        requireSessionBound();

        Optional<ScopeAccessRepository.ScopeAccessRow> row = scopes.findBySlug(slug);

        if (row.isEmpty()) {
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

        ScopeAccessRepository.ScopeAccessRow found = row.get();
        refuseUnservedKind(found);
        if (access == Access.WRITE) {
            refuseWriteIntoLockedScope(found);
            refuseWriteWithoutTheRight(found);
        }

        LOG.debugf("resolved scope '%s'", slug);
        return new ScopeAccess(
            found.scopeId(),
            found.tenantId(),
            found.slug(),
            found.archived(),
            found.kind(),
            found.locked(),
            found.canWrite());
    }

    /**
     * Whether the act about to run reads the scope or writes into it.
     *
     * <p>Two values and not a set of verbs: the directory has no business
     * knowing what {@code advance} is, and a list of verb names here would be
     * a second copy of the surface's own register — the copy that goes stale
     * the first time a verb is added.
     */
    public enum Access {
        /** The act reads. A locked scope and a read-only one both admit it. */
        READ,
        /** The act writes. Both of those refuse it, for different reasons. */
        WRITE
    }

    /**
     * A scope of a kind this service does not serve.
     *
     * <p>Checked for reads as well as writes, and deliberately: a private
     * scope holds no items to read either, so answering a read and refusing a
     * write would publish an address space that is empty by construction.
     */
    private static void refuseUnservedKind(ScopeAccessRepository.ScopeAccessRow scope) {
        if (!PRIVATE_KIND.equals(scope.kind())) {
            return;
        }
        throw new WorklistException(WorklistException.Reason.SCOPE_KIND_UNSUPPORTED,
            "scope '" + scope.slug() + "' is a private scope, and the worklist does not "
                + "serve one. A private scope is a per-tenant container for memory "
                + "content; items, iterations, milestones and workstreams are not kept "
                + "in it. Address a project or a global scope instead.",
            List.of(scope.slug()));
    }

    /** A write into a scope whose content is frozen. */
    private static void refuseWriteIntoLockedScope(ScopeAccessRepository.ScopeAccessRow scope) {
        if (!scope.locked()) {
            return;
        }
        throw new WorklistException(WorklistException.Reason.SCOPE_LOCKED,
            "scope '" + scope.slug() + "' is locked, so it takes no writes over a "
                + "service channel. Reading it is unaffected — freezing a scope keeps "
                + "the record, it does not withdraw it. The lock is lifted where it was "
                + "set, which is not here.",
            List.of(scope.slug()));
    }

    /** A write this subject may not make in a scope it may read. */
    private static void refuseWriteWithoutTheRight(ScopeAccessRepository.ScopeAccessRow scope) {
        if (scope.canWrite()) {
            return;
        }
        throw new WorklistException(WorklistException.Reason.SCOPE_READ_ONLY,
            "this subject may read scope '" + scope.slug() + "' and may not write into "
                + "it over a service channel. The write right is the platform's answer, "
                + "not this service's, so it is changed where membership is "
                + "administered.",
            List.of(scope.slug()));
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
        scopes.bindSubject(subject);
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
        Object tenant = scopes.boundTenant();
        Object subject = scopes.boundSubject();

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
     * could not tell the two apart. {@code kind}, {@code locked} and
     * {@code canWrite} arrived with V24 of the core and are carried for the
     * same reason: a refusal that cannot name which property refused it is a
     * refusal the caller cannot act on.
     *
     * <p>Every value here has already been judged by {@link #resolve} for the
     * access it was resolved under, so a holder of this record is past the
     * refusals rather than expected to repeat them. It carries them so that a
     * caller downstream can say what it is holding — not so that a second
     * check can be written somewhere else and drift from this one.
     */
    public record ScopeAccess(UUID scopeId, UUID tenantId, String slug, boolean archived,
                              String kind, boolean locked, boolean canWrite) {
    }
}
