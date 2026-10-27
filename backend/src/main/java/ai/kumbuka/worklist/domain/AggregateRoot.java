/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Transient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * A row a caller may hold a view of, and the two acts that keep that view
 * honest.
 *
 * <h2>The aggregate is the unit, not the row</h2>
 *
 * Optimistic locking is per AGGREGATE. Five classes extend this one:
 * {@link Item}, {@link Iteration}, {@link Milestone} and {@link Workstream}
 * are aggregate roots; {@link ScopeSetting} is a configuration object with a
 * token of its own. A write presents the token of
 * its ROOT — so a reorder writes the iteration whose membership list happens
 * to be twelve rows and presents that iteration's one token, never twelve.
 *
 * <p>{@link IterationMembership} is therefore NOT one of these. It is
 * addressed at its own address and it owns no token: it presents its
 * iteration's. Addressing and token ownership are two different things and
 * here they come apart — which is why this class is what a root extends
 * rather than what every table extends.
 *
 * <h2>Why the two acts are here and not copied into each service</h2>
 *
 * {@link #stamp(Instant)} and {@link #requireCurrentToken(Object)} were private
 * static methods on {@code ItemService}, typed to {@code Item}. Four
 * aggregates would have meant four copies, which is the shape a duplication
 * gate has already caught once in this repository — and the shape where the
 * four slowly stop agreeing about what a conflict is.
 *
 * <p>They sit on the root rather than in a helper because the pair is a
 * property of a versioned row: the token means nothing without the write that
 * rotates it, and the rotation means nothing without the check that reads it.
 * Splitting them across two classes would let one of them be called alone,
 * which is precisely the defect they exist against.
 *
 * <h2>The modification timestamp is NOT here</h2>
 *
 * {@link Item} carries {@code changed_at} and the planning tables carry
 * {@code updated_at}, as V4 names them. A shared column here would mean
 * overriding the one that differs, which is a longer way to say the same
 * thing and hides which table is the exception — the same reasoning that
 * keeps the timestamps out of {@link TenantScoped}. So the root declares
 * {@link #touch(Instant)} and {@link #changeTime()} instead, and each subclass
 * moves and reads its own column.
 *
 * <h2>The token is that timestamp</h2>
 *
 * The conflict token is the modification column at the microsecond the
 * database stores, rendered as ISO-8601 text (C10.1). Every effective write
 * sets it to {@code max(now, previous + 1 µs)}, so two writes in one
 * microsecond yield two tokens and a clock set back never yields an earlier
 * one. The time comes from the injected clock, never from a database default
 * read back (C20.1).
 *
 * <p>The comparison is only half of C10.2. It is sound only on a row the
 * caller's transaction has locked and refreshed under that lock (C11.1), so
 * a caller loads a root it is about to check through the locking read of its
 * repository ({@code lockById} and its siblings), and a second writer with the
 * same token waits on the first and then compares against what the first
 * wrote. {@code LockedReadRefreshTest} holds the repository half, that every
 * locking read refreshes; {@code ConcurrentWriteIT} shows the whole for the
 * item and the iteration. That a caller chose the locking read is not guarded
 * by a test: a call of {@link #requireCurrentToken} on a root read without the
 * lock compiles and passes every check here.
 *
 * <h2>A write behind the stamp is refused at the flush</h2>
 *
 * That every effective write calls {@link #stamp(Instant)} would otherwise be
 * a convention: a write path that forgot it would flush a changed row under
 * the token it had, and a second writer holding that token would get through.
 * So the lifecycle callback {@link #refuseAnUnstampedWrite()} turns the
 * convention into a refusal: the persistence context calls it for
 * every root it found changed, and a change that did not pass the stamp is
 * refused with an {@link IllegalStateException}, which rolls the transaction
 * back. A create is a persist and never reaches it; a write that changes
 * nothing flushes nothing and never reaches it either.
 *
 * <p>The flag and the callback sit here, once, rather than on each of the
 * five classes as on the single entities of the dispatch and memory services
 * they follow. Both are inherited by every entity that extends a mapped
 * superclass, and the stamp they guard is itself declared here: one class
 * owns the rule and the check of it, and a sixth root cannot extend the one
 * without the other. {@code UnstampedWriteIT} shows the refusal on each of
 * the five.
 */
@MappedSuperclass
public abstract class AggregateRoot extends TenantScoped {

    /**
     * The column the previous image compared tokens against, written and
     * never read.
     *
     * <p>An image rolled back meets the schema of this one and reads its token
     * from here (N-1). So every write that moves the token writes its text here
     * too, and the comparison and the answer read {@link #conflictToken()}
     * alone. The column falls with the contraction, one release later.
     */
    @Column(name = "conflict_token", nullable = false)
    public String storedToken;

    /**
     * Whether the change about to be flushed passed {@link #stamp}. Not a
     * column: it lives for one write of one loaded root.
     */
    @Transient
    private boolean stamped;

    /**
     * The word this root is called by in a refusal.
     *
     * <p>A caller told "the token given is not the one this row carries" has
     * to go and work out which row was meant. The whole value of a typed
     * refusal is that the answer is in it.
     */
    protected abstract String subject();

    /** Move this root's own modification timestamp, under whatever name it has. */
    protected abstract void touch(Instant at);

    /** This root's own modification timestamp, under whatever name it has. */
    protected abstract Instant changeTime();

    /** Set this root's creation timestamp. */
    protected abstract void created(Instant at);

    /**
     * The creation and modification timestamps and the token of a new root,
     * all one instant: the clock's, at the microsecond the column stores.
     */
    public void born(Instant now) {
        Instant at = now.truncatedTo(ChronoUnit.MICROS);
        created(at);
        touch(at);
        storedToken = render(at);
    }

    /**
     * The modification timestamp and the conflict token, moved together.
     *
     * <p>One method, called only where an effective change was established,
     * so that "the token rotated" and "something changed" cannot come apart.
     * A write that changes nothing does not reach this line, and the change
     * trail keeps meaning what it says.
     */
    public void stamp(Instant now) {
        Instant at = following(now);
        touch(at);
        storedToken = render(at);
        stamped = true;
    }

    /**
     * Refuses a changed root whose write did not pass {@link #stamp}: a write
     * the token would not fence, refused here rather than stored (C10.1).
     *
     * <p>Called by the persistence context only for an update it found the
     * row changed in. A create is a persist and never arrives here, and a
     * write that changes nothing flushes nothing and keeps its token.
     *
     * <p>It does NOT clear the flag, as the dispatch and memory services do in
     * theirs. Hibernate also calls it when it weighs flushing before a query
     * and then finds the query reads no table the change touches: no
     * statement runs, the row stays changed, and the real flush calls it a
     * second time. A flag cleared on the first call refuses the second, which
     * would refuse a stamped write for the order of its queries. The flag is
     * cleared by {@link #clearTheStamp()} instead, once the row is written.
     */
    @PreUpdate
    void refuseAnUnstampedWrite() {
        if (!stamped) {
            throw new IllegalStateException(subject() + " is written without stamp(), "
                + "and its conflict token would not move");
        }
    }

    /**
     * Clears the flag once the update has run, so the stamp of one write never
     * stands in for a change made after it was written.
     */
    @PostUpdate
    void clearTheStamp() {
        stamped = false;
    }

    /**
     * The conflict token: the modification timestamp at the microsecond the
     * column stores, as ISO-8601 text. Opaque to the caller by contract.
     * Absent on a root not yet inserted, which has no timestamp to render.
     */
    public String conflictToken() {
        Instant at = changeTime();
        return at == null ? null : render(at);
    }

    /**
     * The conflict token presented must be the one this root currently
     * carries.
     *
     * <p>A stale one and an absent one are the same refusal, and it carries
     * the CURRENT token: a caller that has to make a second call just to
     * learn what it should have sent will end up reading before every write,
     * and a caller that reads in order to overwrite has stopped detecting
     * conflicts.
     */
    public void requireCurrentToken(Object presented) {
        String current = conflictToken();
        if (presented != null && current != null && current.equals(String.valueOf(presented))) {
            return;
        }
        throw WorklistException.conflict(current);
    }

    /** {@code max(now, previous + 1 µs)}, at the microsecond the column stores. */
    private Instant following(Instant now) {
        Instant at = now.truncatedTo(ChronoUnit.MICROS);
        Instant previous = changeTime();
        if (previous == null) {
            return at;
        }
        Instant floor = previous.truncatedTo(ChronoUnit.MICROS).plus(1, ChronoUnit.MICROS);
        return at.isBefore(floor) ? floor : at;
    }

    private static String render(Instant at) {
        return at.truncatedTo(ChronoUnit.MICROS).toString();
    }
}
