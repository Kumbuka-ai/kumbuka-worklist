/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.Db;
import jakarta.persistence.EntityManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Asks the database whether one transaction waits on a lock another holds.
 *
 * <p>A concurrency case that only starts two threads shows nothing about
 * their overlapping: the second may simply run after the first. This helper
 * makes the overlap observable, so a case can assert that it happened before
 * it asserts what came of it.
 */
final class LockWaits {

    /** How long the second transaction is given to end between two questions to the database. */
    private static final long POLL_MILLIS = 10;

    /** A guard against a hung run, never the measure of a wait. */
    private static final long GIVE_UP_SECONDS = 60;

    private LockWaits() {
    }

    /** The database session of the connection the caller's transaction runs on. */
    static int sessionOf(EntityManager em) {
        return ((Number) em.createNativeQuery("SELECT pg_backend_pid()").getSingleResult())
            .intValue();
    }

    /**
     * Watches {@code second} until it waits on a lock the session {@code
     * holder} holds, or ends.
     *
     * <p>Sound only while {@code holder} keeps its locks: a wait that started
     * then lasts until the holder ends, so no poll can miss it.
     *
     * @return true when {@code second} was seen waiting on {@code holder},
     *     false when it ended without having been
     */
    static boolean waitsOn(int holder, Future<?> second) throws Exception {
        long giveUp = System.nanoTime() + TimeUnit.SECONDS.toNanos(GIVE_UP_SECONDS);
        try (Connection c = Db.asAdmin();
             PreparedStatement blocked = c.prepareStatement(
                 "SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))")) {
            // Each question in a transaction of its own: within one, the
            // activity view answers the snapshot its first read took.
            c.setAutoCommit(true);
            blocked.setInt(1, holder);
            while (System.nanoTime() < giveUp) {
                try (ResultSet rs = blocked.executeQuery()) {
                    rs.next();
                    if (rs.getInt(1) > 0) {
                        return true;
                    }
                }
                if (ended(second)) {
                    return false;
                }
            }
        }
        throw new AssertionError("the second transaction neither waited on session " + holder
            + " nor ended within " + GIVE_UP_SECONDS + " s");
    }

    /** Whether {@code second} ended, given one poll interval to do so. */
    private static boolean ended(Future<?> second) throws InterruptedException {
        try {
            second.get(POLL_MILLIS, TimeUnit.MILLISECONDS);
            return true;
        } catch (ExecutionException refusedOrFailed) {
            return true;
        } catch (TimeoutException stillRunning) {
            return false;
        }
    }
}
