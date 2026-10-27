/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.fixture.repository.sub;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.util.List;

/**
 * A locking read without a refresh in a subpackage of a repository package,
 * for {@code LockedReadRefreshTest} to find.
 *
 * <p>The guard selects the repository package with its subpackages; a
 * selection that took the package alone would walk past this class and report
 * nothing. Lives in the test sources and is never wired into anything.
 */
public class SubpackageLockFixture {

    private final EntityManager em;

    public SubpackageLockFixture(EntityManager em) {
        this.em = em;
    }

    /** Locks and answers the context's object. */
    public List<Object> lockedInSubpackage(Long id) {
        return em.createQuery("SELECT s FROM Selector s WHERE s.id = :id", Object.class)
            .setParameter("id", id)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList();
    }
}
