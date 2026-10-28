/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.repository;

import ai.kumbuka.worklist.domain.NumberSpace;
import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every statement issued against the selector and number-space tables.
 *
 * <p>Separate from {@link ItemRepository} because the registry above it is
 * separate: a selector is declared deliberately, and the number space is
 * locked on a path that runs once per acceptance rather than on every write.
 *
 * <p>As there, refusals stay above. A missing number space and a withdrawn
 * selector are two different things a caller is told, and this layer does not
 * know which question was asked.
 */
@ApplicationScoped
@TenantBound
public class SelectorRepository {

    private static final String P_SCOPE = "scope";

    private static final String P_TOKEN = "token";

    private static final String P_SELECTOR = "selector";

    @Inject EntityManager em;
    @Inject Clock clock;

    /** The selector of that token in a scope, if there is one. */
    @Transactional
    public Optional<Selector> find(UUID scopeId, String token) {
        try {
            return Optional.of(em.createQuery(
                    "SELECT s FROM Selector s WHERE s.scopeId = :scope AND s.token = :token",
                    Selector.class)
                .setParameter(P_SCOPE, scopeId)
                .setParameter(P_TOKEN, token)
                .getSingleResult());
        } catch (NoResultException absent) {
            return Optional.empty();
        }
    }

    /** Every selector of a scope, declared and withdrawn alike, by token. */
    @Transactional
    public List<Selector> inScope(UUID scopeId) {
        return em.createQuery(
                "SELECT s FROM Selector s WHERE s.scopeId = :scope ORDER BY s.token",
                Selector.class)
            .setParameter(P_SCOPE, scopeId)
            .getResultList();
    }

    /**
     * The selector's SCOPE-WIDE number space, locked for the caller's
     * transaction; absent when it has none.
     *
     * <p>The lock is what makes two concurrent acceptances serialise rather
     * than collide, and taking it in the accepting transaction is what makes a
     * rolled-back acceptance give its number back.
     *
     * <p>Looked up by the selector rather than found by key: the counter's
     * own key is a surrogate so the ORM key stays outside the tenancy axis.
     *
     * <p>A selector has exactly one counter row per scope, so the lookup
     * needs nothing but the selector. It named {@code workstream_id IS NULL}
     * as well while the {@code milestone} selector carried one counter per
     * workstream; V12 moved those back to scope-wide and V12's
     * {@code uq_number_space_selector (tenant, scope, selector)} is what
     * makes "exactly one" a property of the table rather than of this
     * predicate. Dropping the predicate is also what stops this image
     * reading a column that is due to be dropped.
     */
    @Transactional
    public Optional<NumberSpace> lockSpace(Long selectorPk) {
        return single(spaceQuery("s.selectorPk = :selector")
                .setParameter(P_SELECTOR, selectorPk)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE))
            .map(this::underLock);
    }

    /** The selector's number space without a lock, for a read that only reports it; absent when it has none. */
    @Transactional
    public Optional<NumberSpace> space(Long selectorPk) {
        return single(spaceQuery("s.selectorPk = :selector")
            .setParameter(P_SELECTOR, selectorPk));
    }

    private TypedQuery<NumberSpace> spaceQuery(String predicate) {
        // `predicate` is a string literal at every call site — the method is
        // private, so that set is closed and checkable, and both callers
        // pass a constant. Every value a caller actually varies goes through
        // setParameter, never through this concatenation.
        //
        // The rule is right to flag the shape, and the safety here rests on the
        // callers staying literal. If a caller ever builds this string from
        // input, this becomes a real injection and the suppression below will
        // hide it. Anyone adding a caller should read that sentence first.
        // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
        return em.createQuery(
            "SELECT s FROM NumberSpace s WHERE " + predicate, NumberSpace.class);
    }

    private static Optional<NumberSpace> single(TypedQuery<NumberSpace> query) {
        try {
            return Optional.of(query.getSingleResult());
        } catch (NoResultException absent) {
            return Optional.empty();
        }
    }

    /** Inserts a selector and flushes, so its unique constraint answers here. */
    @Transactional
    public Selector insert(Selector selector) {
        selector.createdAt = now();
        selector.updatedAt = selector.createdAt;
        em.persist(selector);
        em.flush();
        return selector;
    }

    /** Inserts a number space and flushes, for the same reason. */
    @Transactional
    public NumberSpace insert(NumberSpace space) {
        space.createdAt = now();
        space.updatedAt = space.createdAt;
        em.persist(space);
        em.flush();
        return space;
    }

    /** Flushes a status or mark change so the table's constraints answer here. */
    @Transactional
    public void flush() {
        em.flush();
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
