/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.repository;

import ai.kumbuka.worklist.domain.NumberSpace;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every statement issued against the number-space table, and the one that
 * still writes a selector row beside it.
 *
 * <p>Separate from {@link ItemRepository} because the registry above it is
 * separate: a view is opened deliberately, and its counter is locked on a path
 * that runs once per acceptance rather than on every write.
 *
 * <p>As there, refusals stay above. A missing counter is a thing a caller is
 * told, and this layer does not know which question was asked.
 *
 * <h2>The counter is found by its view</h2>
 *
 * Every read here names the scope and the view, which is the counter's key
 * since V24. The selector row the store still carries is not read by anything
 * in this image: whether a view is open in a scope is whether its counter is
 * there, and the four views are constants of the service.
 */
@ApplicationScoped
@TenantBound
public class SelectorRepository {

    private static final String P_SCOPE = "scope";

    private static final String P_VIEW = "view";

    @Inject EntityManager em;
    @Inject Clock clock;

    /**
     * The view's counter in a scope, locked for the caller's transaction;
     * absent when the view is not open there.
     *
     * <p>The lock is what makes two concurrent acceptances serialise rather
     * than collide, and taking it in the accepting transaction is what makes a
     * rolled-back acceptance give its number back.
     *
     * <p>{@code uq_number_space_view (tenant, scope, view)} of V24 is what
     * makes "exactly one" a property of the table rather than of this query.
     */
    @Transactional
    public Optional<NumberSpace> lockSpace(UUID scopeId, String view) {
        return single(spaceQuery(scopeId, view)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE))
            .map(this::underLock);
    }

    /** The view's counter without a lock, for a read that only reports it; absent when it has none. */
    @Transactional
    public Optional<NumberSpace> space(UUID scopeId, String view) {
        return single(spaceQuery(scopeId, view));
    }

    /** The views open in a scope, by token. */
    @Transactional
    public List<String> views(UUID scopeId) {
        return em.createQuery(
                "SELECT s.view FROM NumberSpace s WHERE s.scopeId = :scope ORDER BY s.view",
                String.class)
            .setParameter(P_SCOPE, scopeId)
            .getResultList();
    }

    /**
     * Opens a view in a scope: its counter at zero, and — until the
     * contraction release — the selector row the previous image resolves it
     * through. Opening an open view changes nothing.
     *
     * <p><strong>This is the one statement in the service that touches
     * {@code worklist.selector}, and it only writes.</strong> The row is
     * written so that the image before this one, after a rollback, finds the
     * selectors it reads; nothing in this image reads it back. The counter's
     * {@code selector_pk} is filled by the V24 trigger from the view, so the
     * write needs no read of the selector to name it. The contraction release
     * removes the first statement, the column and the table together;
     * {@code SelectorTableAccessTest} holds every other class and method away
     * from the table until then.
     *
     * <p>Native rather than JPQL: both inserts must leave an existing row
     * alone rather than collide with it, which is {@code ON CONFLICT DO
     * NOTHING} and has no JPQL form. That also makes two concurrent first
     * openings agree rather than have the loser see a constraint violation.
     * The tenant is the one this transaction is bound to; unbound, it is
     * null and the insert is refused.
     */
    @Transactional
    public void open(UUID scopeId, String view) {
        Timestamp now = Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS));
        em.createNativeQuery("""
                INSERT INTO worklist.selector (tenant_id, scope_id, token, created_at, updated_at)
                VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid,
                        :scope, :view, :now, :now)
                ON CONFLICT (tenant_id, scope_id, token) DO NOTHING
                """)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_VIEW, view)
            .setParameter("now", now)
            .executeUpdate();
        em.createNativeQuery("""
                INSERT INTO worklist.number_space
                    (tenant_id, scope_id, view, high_water_mark, created_at, updated_at)
                VALUES (NULLIF(current_setting('app.tenant_id', true), '')::uuid,
                        :scope, :view, 0, :now, :now)
                ON CONFLICT (tenant_id, scope_id, view) DO NOTHING
                """)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_VIEW, view)
            .setParameter("now", now)
            .executeUpdate();
    }

    /** Flushes a mark change so the table's constraints answer here. */
    @Transactional
    public void flush() {
        em.flush();
    }

    private TypedQuery<NumberSpace> spaceQuery(UUID scopeId, String view) {
        return em.createQuery(
                "SELECT s FROM NumberSpace s WHERE s.scopeId = :scope AND s.view = :view",
                NumberSpace.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_VIEW, view);
    }

    private static Optional<NumberSpace> single(TypedQuery<NumberSpace> query) {
        try {
            return Optional.of(query.getSingleResult());
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
}
