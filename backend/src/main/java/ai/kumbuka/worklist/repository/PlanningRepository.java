/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.repository;

import ai.kumbuka.worklist.domain.Iteration;
import ai.kumbuka.worklist.domain.IterationMembership;
import ai.kumbuka.worklist.domain.Milestone;
import ai.kumbuka.worklist.domain.PageRequest;
import ai.kumbuka.worklist.domain.QueryFilter;
import ai.kumbuka.worklist.domain.ScopeSetting;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    @Inject Clock clock;

    // ------------------------------------------------------------------
    // The goal axis
    // ------------------------------------------------------------------

    /**
     * The milestone of that id, if there is one. Scope membership is checked by
     * the caller.
     *
     * @param milestoneId the id, or absent where the caller holds none; an absent id
     *            names no milestone
     */
    @Transactional
    public Optional<Milestone> milestoneById(@Nullable UUID milestoneId) {
        return milestoneId == null ? Optional.empty()
            : Optional.ofNullable(em.find(Milestone.class, milestoneId));
    }

    /**
     * The milestone of that id, locked and read as it stands under the lock,
     * if there is one (C10.2, C11.1). Every write that checks its token loads
     * it here.
     *
     * @param milestoneId the id, or absent where the caller holds none; an absent id
     *            names no milestone
     */
    @Transactional
    public Optional<Milestone> lockMilestoneById(@Nullable UUID milestoneId) {
        if (milestoneId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                em.find(Milestone.class, milestoneId, LockModeType.PESSIMISTIC_WRITE))
            .map(this::underLock);
    }

    /**
     * The milestone at that number in a scope, if there is one.
     *
     * <p>The surface addresses by number and the store is keyed by a surrogate
     * id; this is where the one becomes the other. Numbers on this axis come
     * from the scope's own high-water mark and are never reused, so the pair
     * scope and number identifies at most one row for the life of the scope.
     */
    @Transactional
    public Optional<Milestone> milestoneByNumber(UUID scopeId, long number) {
        return first(em.createQuery(
                "SELECT m FROM Milestone m WHERE m.scopeId = :scope AND m.number = :number",
                Milestone.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_NUMBER, number));
    }

    /**
     * One page of a scope's milestones, narrowed by the status and kind the
     * request names, in its sort and continuing after its position — in the
     * database, by {@link KeysetClause}, and never by reading the whole axis.
     */
    @Transactional
    public List<Milestone> milestonePage(UUID scopeId, PageRequest request) {
        StringBuilder jpql = new StringBuilder("SELECT m FROM Milestone m WHERE m.scopeId = :scope");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(P_SCOPE, scopeId);
        request.value(QueryFilter.MILESTONE_STATUS).ifPresent(status -> {
            jpql.append(" AND m.status = :").append(P_STATUS);
            params.put(P_STATUS, status);
        });
        request.value(QueryFilter.MILESTONE_KIND).ifPresent(kind -> {
            jpql.append(" AND m.kind = :kind");
            params.put("kind", kind);
        });
        KeysetClause.continueAndOrder(jpql, params, "m", request);
        var query = em.createQuery(jpql.toString(), Milestone.class);
        params.forEach(query::setParameter);
        return query.setMaxResults(KeysetClause.bound(request)).getResultList();
    }

    /**
     * The scope's active milestone, if there is one.
     *
     * <p>At most one exists, held by a partial unique index rather than by
     * this query. What the query is for is the demotion: setting one active
     * has to move the current one in the same write, or the invariant would
     * have to hold across two.
     */
    @Transactional
    public Optional<Milestone> activeMilestone(UUID scopeId) {
        return first(em.createQuery(
                "SELECT m FROM Milestone m WHERE m.scopeId = :scope AND m.status = :status",
                Milestone.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_STATUS, Milestone.ACTIVE));
    }

    @Transactional
    public Milestone insert(Milestone milestone) {
        milestone.born(now());
        em.persist(milestone);
        em.flush();
        return milestone;
    }

    // ------------------------------------------------------------------
    // The time axis
    // ------------------------------------------------------------------

    /**
     * The iteration of that id, if there is one.
     *
     * @param iterationId the id, or absent where the caller holds none; an absent id
     *            names no iteration
     */
    @Transactional
    public Optional<Iteration> iterationById(@Nullable UUID iterationId) {
        return iterationId == null ? Optional.empty()
            : Optional.ofNullable(em.find(Iteration.class, iterationId));
    }

    /**
     * The iteration of that id, locked and read as it stands under the lock,
     * if there is one (C10.2, C11.1). Every write that checks its token, its
     * own or one presented for a membership, loads it here.
     *
     * @param iterationId the id, or absent where the caller holds none; an absent id
     *            names no iteration
     */
    @Transactional
    public Optional<Iteration> lockIterationById(@Nullable UUID iterationId) {
        if (iterationId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                em.find(Iteration.class, iterationId, LockModeType.PESSIMISTIC_WRITE))
            .map(this::underLock);
    }

    /** The iteration at that number in a scope, if there is one. See {@link #milestoneByNumber}. */
    @Transactional
    public Optional<Iteration> iterationByNumber(UUID scopeId, long number) {
        return first(em.createQuery(
                "SELECT i FROM Iteration i WHERE i.scopeId = :scope AND i.number = :number",
                Iteration.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_NUMBER, number));
    }

    /**
     * One page of a scope's iterations, narrowed to the open or the closed ones
     * when the request names which, in its sort and continuing after its
     * position — in the database, by {@link KeysetClause}.
     *
     * <p>Open is read from the closing timestamp being absent, as in
     * {@link #openIterations}.
     */
    @Transactional
    public List<Iteration> iterationPage(UUID scopeId, PageRequest request) {
        StringBuilder jpql = new StringBuilder("SELECT i FROM Iteration i WHERE i.scopeId = :scope");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(P_SCOPE, scopeId);
        request.value(QueryFilter.ITERATION_CLOSED).ifPresent(closed ->
            jpql.append(Boolean.TRUE.equals(closed)
                ? " AND i.closedAt IS NOT NULL" : " AND i.closedAt IS NULL"));
        KeysetClause.continueAndOrder(jpql, params, "i", request);
        var query = em.createQuery(jpql.toString(), Iteration.class);
        params.forEach(query::setParameter);
        return query.setMaxResults(KeysetClause.bound(request)).getResultList();
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
        iteration.born(now());
        em.persist(iteration);
        em.flush();
        return iteration;
    }

    // ------------------------------------------------------------------
    // Membership
    // ------------------------------------------------------------------

    /** One membership, if there is one. */
    @Transactional
    public Optional<IterationMembership> membership(UUID scopeId, long iterationNumber, long itemNumber) {
        return Optional.ofNullable(em.find(IterationMembership.class,
            new IterationMembership.Key(scopeId, iterationNumber, itemNumber)));
    }

    /** Every membership of an iteration, in its sequence. */
    @Transactional
    public List<IterationMembership> membershipsOf(UUID scopeId, long iterationNumber) {
        return em.createQuery(
                "SELECT m FROM IterationMembership m WHERE m.scopeId = :scope "
                    + "AND m.iterationNumber = :iteration "
                    + "ORDER BY m.position, m.itemNumber", IterationMembership.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_ITERATION, iterationNumber)
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
    public List<Long> plannedItemNumbers(UUID scopeId) {
        return em.createQuery(
                "SELECT DISTINCT m.itemNumber FROM IterationMembership m, Iteration i "
                    + "WHERE m.scopeId = :scope "
                    + "AND i.scopeId = m.scopeId AND i.number = m.iterationNumber "
                    + "AND i.closedAt IS NULL "
                    + "AND m.status NOT IN :terminal", Long.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter("terminal", IterationMembership.TERMINAL)
            .getResultList();
    }

    @Transactional
    public IterationMembership insert(IterationMembership membership) {
        membership.createdAt = now();
        membership.updatedAt = membership.createdAt;
        em.persist(membership);
        em.flush();
        return membership;
    }

    // ------------------------------------------------------------------
    // The scope's settings
    // ------------------------------------------------------------------

    /** The scope's settings row; absent if the scope has not been opened. */
    @Transactional
    public Optional<ScopeSetting> settingOf(UUID scopeId) {
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
    public Optional<ScopeSetting> lockSettingOf(UUID scopeId) {
        return first(settingQuery(scopeId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE))
            .map(this::underLock);
    }

    private TypedQuery<ScopeSetting> settingQuery(UUID scopeId) {
        return em.createQuery(
                "SELECT s FROM ScopeSetting s WHERE s.scopeId = :scope", ScopeSetting.class)
            .setParameter(P_SCOPE, scopeId);
    }

    @Transactional
    public ScopeSetting insert(ScopeSetting setting) {
        setting.born(now());
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

    private static <T> Optional<T> first(TypedQuery<T> query) {
        try {
            return Optional.of(query.setMaxResults(1).getSingleResult());
        } catch (NoResultException absent) {
            return Optional.empty();
        }
    }

    /**
     * The entity as its row stands under the lock this transaction holds.
     *
     * <p>A locking read takes the lock on the current row, but where this
     * transaction already holds the entity, the persistence context answers
     * the object it read earlier and not what the database found. A token
     * compared against that object is compared against a row that may have
     * moved since, and the write that follows overwrites the move (C11.1).
     * The refresh reads the locked row again; it would drop a change not yet
     * flushed, and every write of this service flushes in the call that
     * makes it.
     */
    private <T> T underLock(T entity) {
        em.refresh(entity);
        return entity;
    }

    /** The clock's instant at the microsecond the timestamp columns store (C20.1). */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
