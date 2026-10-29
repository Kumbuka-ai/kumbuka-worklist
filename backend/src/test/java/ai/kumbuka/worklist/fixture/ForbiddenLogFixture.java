/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.fixture;

import org.jboss.logging.Logger;

/**
 * Log calls the convention exists to stop, for the guard to find.
 *
 * <p>The first two are the shapes somebody reaches for while debugging a race
 * at two in the morning: print the title to see which item this is, print the
 * whole object to see everything at once. Neither is malicious and neither
 * would be noticed in review — the first ships an item's title out of the
 * container past a boundary built as a missing GRANT, and the second ships the
 * title, the description and the attributes together.
 *
 * <p>The rest carry the other things the convention withholds, and the actor
 * in the four shapes it is written in: the bare identifier, the type, the
 * getter and the accessor. The actor belongs in the audit log, whose
 * collection is governed; a second, aggregatable stream of the same fact is
 * how not-collecting-behavioural-data gets circumvented without anybody
 * deciding to circumvent it. The last hands the logger an exception, the
 * shape DEC-0051 forbids.
 *
 * <p>It lives in the test sources and is wired into nothing. Its only purpose
 * is to be reported: without it, the guard's clean result over the main
 * sources would be a statement about a detection nobody has seen work. What
 * the convention permits is in {@code allowed.AllowedLogFixture}.
 */
public class ForbiddenLogFixture {

    private static final Logger LOG = Logger.getLogger(ForbiddenLogFixture.class);

    public void logATitle(Itemish it) {
        LOG.infof("working on %s", it.title);
    }

    public void logAWholeEntity(Itemish item) {
        LOG.debugf("state now: %s", item);
    }

    public void logABody(Itemish it) {
        LOG.debugf("carrying: %s", it.body);
    }

    public void logMetadataText(Itemish it) {
        LOG.debugf("carrying: %s", it.metadata);
    }

    /** The receipt is a bearer token, in a file the provider operates. */
    public void logAReceipt(String receipt) {
        LOG.debugf("carrying: %s", receipt);
    }

    /** The subject is the actor under the name the token gives it. */
    public void logASubject(String subject) {
        LOG.debugf("carrying: %s", subject);
    }

    /** The actor as a bare identifier — a local, a parameter, a field. */
    public void logTheActorBare(String actor) {
        LOG.infof("changed by %s", actor);
    }

    /** The actor as a type, reached through one of its constants. */
    public void logTheActorType() {
        LOG.infof("changed by %s", Actor.EXECUTOR);
    }

    /** The actor through a getter. */
    public void logTheActorGetter(Itemish it) {
        LOG.infof("changed by %s", it.getActor());
    }

    /** The actor through an accessor, the shape a record gives it. */
    public void logTheActorAccessor(Itemish it) {
        LOG.infof("changed by %s", it.actor());
    }

    /**
     * An exception handed to the logger: a stack trace carries whatever the
     * failing code held, a caller's value included, and it stands before the
     * format string, where a guard reading arguments would not look.
     */
    public void logAnException(RuntimeException failure) {
        LOG.errorf(failure, "failed, reference %s", "a reference");
    }

    /** Stands in for the entity, so the fixture needs no domain import. */
    public static class Itemish {
        public String title = "an item's title";
        public String body = "an item's description";
        public String metadata = "free text belonging to the caller";

        public String getActor() {
            return "who did it";
        }

        public String actor() {
            return "who did it";
        }
    }

    /** Stands in for the actor type, so the fixture needs no domain import. */
    public enum Actor {
        EXECUTOR, STEERING
    }
}
