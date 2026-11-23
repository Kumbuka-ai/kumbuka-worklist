/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One typed edge between two items.
 *
 * <p>The predecessor holds this in a cell as a comma-separated list of row
 * numbers with <strong>no relationship type at all</strong>, and its checks
 * run only inside a validation verb. Two things change here and they are not
 * the same change.
 *
 * <p><strong>The edge is a row, so a dangling reference cannot be
 * written.</strong> A foreign key on both ends makes it impossible rather
 * than findable, which is the stronger outcome: the violation class stops
 * being a thing that can happen.
 *
 * <p><strong>The edge carries a type, so it can be asked whether it
 * blocks.</strong> The previous shape of this domain left the type out
 * deliberately — the moment types exist, something has to interpret them, and
 * the vocabulary needs its own definition and its own guard. {@link
 * RelationType} is that vocabulary and {@link RelationType#blocks} is that
 * interpretation, so the deferral is discharged rather than repeated.
 *
 * <p><strong>The key is the triple</strong> from, to and type. Two items may
 * therefore carry two edges of different types and never two of the same,
 * which is a rule the untyped predecessor edge could not state.
 *
 * <p>Relations are DIRECTED and stored ONCE. The inverse is a query and never
 * a second row — the same conclusion OSLC arrived at after deprecating its own
 * back-links.
 *
 * <p><strong>What does not follow: a cycle is still possible.</strong> No
 * constraint expresses "this graph is acyclic", and the walk that answers it
 * is a domain check with a red probe of its own. It is not built here.
 *
 * <p><strong>Cancellation rather than deletion</strong> (REA-0011 D15). An
 * edge that is cancelled keeps its row: its status moves to
 * {@link #WITHDRAWN}, the stored word of the cancelled state, and it records
 * when it ended, who ended it and, where the call gave one, why (V35). An
 * edge asserted again is the same row, active once more, and keeps the end
 * columns of its last cancellation until the next one overwrites them: the
 * key admits no second row, so only the last cancellation is held. The rows
 * go only with a discarded draft at either end: {@code discard} deletes a
 * draft and what stands beside it, which is what V33 grants DELETE on this
 * table for.
 */
@Entity
@Table(name = "item_relation", schema = "worklist")
@IdClass(ItemRelation.Key.class)
public class ItemRelation extends ScopeKeyed {

    /** The edge is asserted. */
    public static final String ASSERTED = "asserted";
    /** The edge was cancelled: asserted once, and not any more. */
    public static final String WITHDRAWN = "withdrawn";

    @Id
    @Column(name = "from_item_number", nullable = false)
    public Long fromItemNumber;

    @Id
    @Column(name = "to_item_number", nullable = false)
    public Long toItemNumber;

    @Id
    @Column(name = "relation_type_pk", nullable = false)
    public Long relationTypePk;

    @Column(name = "status", nullable = false)
    public String status = ASSERTED;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    /**
     * When the edge was last cancelled. Absent on an edge never cancelled, and
     * on one cancelled before V35 recorded it.
     */
    @Column(name = "ended_at")
    public @Nullable Instant endedAt;

    /**
     * Who last cancelled the edge, as the service derived the subject from the
     * token. Absent where {@link #endedAt} is.
     */
    @Column(name = "ended_by")
    public @Nullable String endedBy;

    /**
     * Why the edge was last cancelled. Absent where the cancelling call took
     * no reason, which is an {@code update} that left the edge out.
     */
    @Column(name = "end_reason")
    public @Nullable String endReason;

    /**
     * Cancels the edge: its status moves and it records when, by whom and why.
     * A further cancellation of an edge asserted again overwrites the three.
     *
     * @param reason why; absent where the call took none
     */
    public void cancel(Instant at, String actor, @Nullable String reason) {
        status = WITHDRAWN;
        endedAt = at;
        endedBy = actor;
        endReason = reason;
        updatedAt = at;
    }

    /**
     * The composite key: from, to, and the type.
     *
     * <p>The tenancy axis is deliberately NOT part of it. An item id is
     * already unique across tenants, and a tenant-qualified key would suggest
     * that two tenants could hold the same edge — which the composite foreign
     * keys in V4 make impossible in the other direction anyway.
     *
     * <p>The type IS part of it, and that is the difference from the untyped
     * edge this replaces: without it, asserting a second kind of relation
     * between the same two items would overwrite the first.
     */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        /*
         * Absent only in the instance the persistence provider builds through
         * the no-argument constructor before it sets the key from the row.
         */
        public @Nullable UUID scopeId;
        public @Nullable Long fromItemNumber;
        public @Nullable Long toItemNumber;
        public @Nullable Long relationTypePk;

        public Key() {
            // Required by the persistence provider.
        }

        public Key(UUID scopeId, Long fromItemNumber, Long toItemNumber, Long relationTypePk) {
            this.scopeId = scopeId;
            this.fromItemNumber = fromItemNumber;
            this.toItemNumber = toItemNumber;
            this.relationTypePk = relationTypePk;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(scopeId, key.scopeId)
                && Objects.equals(fromItemNumber, key.fromItemNumber)
                && Objects.equals(toItemNumber, key.toItemNumber)
                && Objects.equals(relationTypePk, key.relationTypePk);
        }

        @Override
        public int hashCode() {
            return Objects.hash(scopeId, fromItemNumber, toItemNumber, relationTypePk);
        }
    }
}
