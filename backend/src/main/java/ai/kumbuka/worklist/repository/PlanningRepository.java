package ai.kumbuka.worklist.repository;

import ai.kumbuka.worklist.domain.Iteration;
import ai.kumbuka.worklist.domain.IterationMembership;
import ai.kumbuka.worklist.domain.Milestone;
import ai.kumbuka.worklist.domain.ScopeSetting;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Every statement issued against the planning tables.
 *
 * <p>One repository for the whole layer rather than one per table. The layer
 * is written as a unit — creating an iteration advances a mark on the
 * settings, planning an item reads its milestone and counts its iteration's
 * memberships — so splitting it four ways would mean four beans injecting
 * each other to answer one question.
 *
 * <h2>What is here and what is deliberately not</h2>
 *
 * Queries and writes are here. <strong>Refusals are not.</strong> A lookup
 * that finds nothing returns null, and the caller decides whether that is an
 * unknown iteration, a scope that has not been opened yet, or an ordinary
 * absence. Those are different things a caller is told, and telling them
 * apart needs why the row was asked for.
 *
 * <p>The methods carry {@code @Transactional} and the class is
 * {@link TenantBound}, matching the callers rather than replacing them: every
 * entry point is already inside a transaction, so the annotation joins that
 * one and starts none. What it buys is that a future caller which forgot its
 * own transaction fails loudly here instead of reading under no tenant at
 * all — a read without the binding comes back silently empty.
 */
@ApplicationScoped
@TenantBound
public class PlanningRepository {

    private static final String P_SCOPE = "scope";
    private static final String P_ITERATION = "iteration";
    private static final String P_STATUS = "status";
    private static final String P_NUMBER = "number";

    @Inject EntityManager em;

    // ------------------------------------------------------------------
    // The goal axis
    // ------------------------------------------------------------------

    /** The milestone of that id, or null. Scope membership is checked by the caller. */
    @Transactional
    public Milestone milestoneById(UUID milestoneId) {
        return milestoneId == null ? null : em.find(Milestone.class, milestoneId);
    }

    /**
     * The milestone at that number in a scope, or null.
     *
     * <p>The surface addresses by number and the store is keyed by a surrogate
     * id; this is where the one becomes the other. Numbers on this axis come
     * from the scope's own high-water mark and are never reused, so the pair
     * scope and number identifies at most one row for the life of the scope.
     */
    @Transactional
    public Milestone milestoneByNumber(UUID scopeId, long number) {
        return first(em.createQuery(
                "SELECT m FROM Milestone m WHERE m.scopeId = :scope AND m.number = :number",
                Milestone.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_NUMBER, number));
    }

    /** Every milestone of a scope, in the axis's own order. */
    @Transactional
    public List<Milestone> milestonesInScope(UUID scopeId) {
        return em.createQuery(
                "SELECT m FROM Milestone m WHERE m.scopeId = :scope "
                    + "ORDER BY m.rank, m.number", Milestone.class)
            .setParameter(P_SCOPE, scopeId)
            .getResultList();
    }

    /**
     * The scope's active milestone, or null.
     *
     * <p>At most one exists, held by a partial unique index rather than by
     * this query. What the query is for is the demotion: setting one active
     * has to move the current one in the same write, or the invariant would
     * have to hold across two.
     */
    @Transactional
    public Milestone activeMilestone(UUID scopeId) {
        return first(em.createQuery(
                "SELECT m FROM Milestone m WHERE m.scopeId = :scope AND m.status = :status",
                Milestone.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_STATUS, Milestone.ACTIVE));
    }

    @Transactional
    public Milestone insert(Milestone milestone) {
        em.persist(milestone);
        em.flush();
        return milestone;
    }

    // ------------------------------------------------------------------
    // The time axis
    // ------------------------------------------------------------------

    /** The iteration of that id, or null. */
    @Transactional
    public Iteration iterationById(UUID iterationId) {
        return iterationId == null ? null : em.find(Iteration.class, iterationId);
    }

    /** The iteration at that number in a scope, or null. See {@link #milestoneByNumber}. */
    @Transactional
    public Iteration iterationByNumber(UUID scopeId, long number) {
        return first(em.createQuery(
                "SELECT i FROM Iteration i WHERE i.scopeId = :scope AND i.number = :number",
                Iteration.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_NUMBER, number));
    }

    /** Every iteration of a scope, in the order they are to be worked. */
    @Transactional
    public List<Iteration> iterationsInScope(UUID scopeId) {
        return em.createQuery(
                "SELECT i FROM Iteration i WHERE i.scopeId = :scope "
                    + "ORDER BY i.rank, i.number", Iteration.class)
            .setParameter(P_SCOPE, scopeId)
            .getResultList();
    }

    /**
     * The open iterations of a scope, in the order they are to be worked.
     *
     * <p>Open is read from the closing timestamp being absent, which is a
     * fact about the row. There is no status column to read it from, and that
     * is the design: complete is a question about memberships.
     */
    @Transactional
    public List<Iteration> openIterations(UUID scopeId) {
        return em.createQuery(
                "SELECT i FROM Iteration i WHERE i.scopeId = :scope AND i.closedAt IS NULL "
                    + "ORDER BY i.rank, i.number", Iteration.class)
            .setParameter(P_SCOPE, scopeId)
            .getResultList();
    }

    @Transactional
    public Iteration insert(Iteration iteration) {
        em.persist(iteration);
        em.flush();
        return iteration;
    }

    // ------------------------------------------------------------------
    // Membership
    // ------------------------------------------------------------------

    /** One membership, or null. */
    @Transactional
    public IterationMembership membership(UUID iterationId, UUID itemId) {
        return em.find(IterationMembership.class,
            new IterationMembership.Key(iterationId, itemId));
    }

    /** Every membership of an iteration, in its sequence. */
    @Transactional
    public List<IterationMembership> membershipsOf(UUID iterationId) {
        return em.createQuery(
                "SELECT m FROM IterationMembership m WHERE m.iterationId = :iteration "
                    + "ORDER BY m.position, m.itemId", IterationMembership.class)
            .setParameter(P_ITERATION, iterationId)
            .getResultList();
    }

    /**
     * <strong>The derivation of {@code planned}, and the only one there is.</strong>
     *
     * <p>An item is planned when it has a LIVE membership — one that is
     * neither done nor dropped — of an OPEN iteration. Both halves matter and
     * both were defects in the predecessor: a membership alone would report an
     * item planned after the iteration closed over it, and an open iteration
     * alone would report one planned after this iteration had finished with
     * it.
     *
     * <p>It is a query and it is nowhere a column, which is what makes the
     * orphan class — an item reading planned with no membership —
     * inexpressible rather than merely forbidden. Observed twice in the
     * predecessor, where planned was a status value.
     */
    @Transactional
    public List<UUID> plannedItemIds(UUID scopeId) {
        return em.createQuery(
                "SELECT DISTINCT m.itemId FROM IterationMembership m, Iteration i "
                    + "WHERE m.scopeId = :scope "
                    + "AND i.id = m.iterationId "
                    + "AND i.closedAt IS NULL "
                    + "AND m.status NOT IN :terminal", UUID.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter("terminal", IterationMembership.TERMINAL)
            .getResultList();
    }

    @Transactional
    public IterationMembership insert(IterationMembership membership) {
        em.persist(membership);
        em.flush();
        return membership;
    }

    // ------------------------------------------------------------------
    // The scope's settings
    // ------------------------------------------------------------------

    /** The scope's settings row, or null if the scope has not been opened. */
    @Transactional
    public ScopeSetting settingOf(UUID scopeId) {
        return first(settingQuery(scopeId));
    }

    /**
     * The settings row under a write lock.
     *
     * <p>The two high-water marks live on it, and an allocation is a read
     * followed by a write. Without the lock two concurrent creations read the
     * same mark and hand out the same number — which is the race the
     * predecessor lost, and the reason the item allocator takes the same lock
     * on its own counters.
     */
    @Transactional
    public ScopeSetting lockSettingOf(UUID scopeId) {
        return first(settingQuery(scopeId).setLockMode(LockModeType.PESSIMISTIC_WRITE));
    }

    private TypedQuery<ScopeSetting> settingQuery(UUID scopeId) {
        return em.createQuery(
                "SELECT s FROM ScopeSetting s WHERE s.scopeId = :scope", ScopeSetting.class)
            .setParameter(P_SCOPE, scopeId);
    }

    @Transactional
    public ScopeSetting insert(ScopeSetting setting) {
        em.persist(setting);
        em.flush();
        return setting;
    }

    // ------------------------------------------------------------------
    // Shared mechanics
    // ------------------------------------------------------------------

    /** Flushes pending changes, so a constraint is reported at the call site. */
    @Transactional
    public void flush() {
        em.flush();
    }

    /**
     * Flushes, then re-reads the row.
     *
     * <p>Both, and in this order. The columns the database fills are not in
     * the persistence context until the statement has run, so a projection
     * taken before the refresh would report the values the caller sent rather
     * than the ones that were stored.
     */
    @Transactional
    public void flushAndRefresh(Object row) {
        em.flush();
        em.refresh(row);
    }

    private static <T> T first(TypedQuery<T> query) {
        try {
            return query.setMaxResults(1).getSingleResult();
        } catch (NoResultException absent) {
            return null;
        }
    }
}
