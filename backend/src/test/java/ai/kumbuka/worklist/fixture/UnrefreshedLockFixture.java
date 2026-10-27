/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.fixture;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.util.List;

/**
 * Locking reads, two of them deliberate violations, for {@code
 * LockedReadRefreshTest} to tell apart.
 *
 * <p>{@code lockedJpql} and {@code lockedNative} each take a row lock and
 * answer what the persistence context holds, which may be an object this
 * transaction read before the lock and another transaction has changed since.
 * The javadoc of {@code lockedJpql} names {@code underLock}, on purpose: the
 * guard reads code, and a mention in a comment does not refresh anything.
 *
 * <p>{@code locked} hands its lock mode to {@code at}, which refreshes, the
 * way {@code ItemRepository.lockById} does; it is not a violation, and the guard
 * is required not to report it.
 *
 * <p>Lives in the test sources and is never wired into anything.
 */
public class UnrefreshedLockFixture {

    private final EntityManager em;

    public UnrefreshedLockFixture(EntityManager em) {
        this.em = em;
    }

    /** Locks and answers the context's object; it should go through underLock. */
    public List<Object> lockedJpql(Long id) {
        return em.createQuery("SELECT s FROM Selector s WHERE s.id = :id", Object.class)
            .setParameter("id", id)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList();
    }

    public List<?> lockedNative() {
        return em.createNativeQuery("SELECT id FROM worklist.item LIMIT 1 FOR UPDATE")
            .getResultList();
    }

    public Object locked(Long id) {
        return at(id, LockModeType.PESSIMISTIC_WRITE);
    }

    private Object at(Long id, LockModeType mode) {
        Object found = em.createQuery("SELECT s FROM Selector s WHERE s.id = :id", Object.class)
            .setParameter("id", id)
            .setLockMode(mode)
            .getSingleResult();
        return underLock(found);
    }

    private Object underLock(Object entity) {
        em.refresh(entity);
        return entity;
    }
}
