/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.repository;

import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SpentKey;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The ledger of spent idempotency keys, and the one question about the object
 * a key produced that the ledger needs answered: whether it still stands.
 */
@ApplicationScoped
@TenantBound
public class SpentKeyRepository {

    @Inject EntityManager em;

    /**
     * Takes the lock of one key in a scope for the caller's transaction.
     *
     * <p>A call under a key takes it before it reads the ledger, so a second
     * call under the same key that overlaps the first waits here until the
     * first has committed, and then reads the first's row as a repeat. Without
     * it both would find no row, both would create, and the second would meet
     * the unique constraint after its object was already written.
     *
     * <p>An advisory lock rather than a row lock: before the first use there
     * is no row to lock. Not a locking read either, so there is nothing to
     * refresh. Two keys whose texts hash alike wait on each other and decide
     * nothing for each other.
     */
    @Transactional
    public void lockKeyUse(UUID scopeId, String key) {
        em.createNativeQuery("""
                SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(
                    CAST(:lock AS text), 0))) AS held
                """)
            .setParameter("lock", lockOf(scopeId, key))
            .getSingleResult();
    }

    /**
     * The text the lock of a key is taken over: the scope, then the key as its
     * length, a colon and the key, so that no two pairs write the same text
     * whatever characters a key carries.
     */
    static String lockOf(UUID scopeId, String key) {
        return "worklist.idempotency_key:" + scopeId + ":" + key.length() + ":" + key;
    }

    /** The row a key was spent under in a scope, if it was. */
    @Transactional
    public Optional<SpentKey> byKey(UUID scopeId, String key) {
        return em.createQuery(
                "SELECT k FROM SpentKey k WHERE k.scopeId = :scope AND k.key = :key",
                SpentKey.class)
            .setParameter("scope", scopeId)
            .setParameter("key", key)
            .getResultStream()
            .findFirst();
    }

    /** Spends a key: inserts its row and flushes, so the table's constraints answer here. */
    @Transactional
    public void insert(SpentKey row) {
        em.persist(row);
        em.flush();
    }

    /** Flushes a row written over, so the table's constraints answer here. */
    @Transactional
    public void flush() {
        em.flush();
    }

    /**
     * Whether the object a key produced still stands in its scope.
     *
     * <p>A withdrawn item stands: it keeps its address and its row. What does
     * not is a row that is no longer there, however it went.
     */
    @Transactional
    public boolean stands(UUID scopeId, String view, long number) {
        String query = switch (view) {
            case Selector.ITEM ->
                "SELECT count(o) FROM Item o WHERE o.scopeId = :scope AND o.number = :number";
            case Selector.ITERATION ->
                "SELECT count(o) FROM Iteration o WHERE o.scopeId = :scope AND o.number = :number";
            case Selector.MILESTONE ->
                "SELECT count(o) FROM Milestone o WHERE o.scopeId = :scope AND o.number = :number";
            default -> throw new IllegalArgumentException("no view a create produces: " + view);
        };
        return em.createQuery(query, Long.class)
            .setParameter("scope", scopeId)
            .setParameter("number", number)
            .getSingleResult() > 0;
    }
}
