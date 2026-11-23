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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * One external pointer of an item, at its position in the list.
 *
 * <p><strong>References are an ordered list and not one field.</strong> The
 * single free-text column this replaces was the predecessor's shape and it is
 * the wrong one for a measured reason: in the estate being migrated that one
 * field came to hold, simultaneously, the item's rationale, a withdrawn
 * decision, a build source path and a warning that the path was wrong. A list
 * separates the pointers, and the item's description takes the prose that was
 * never a reference in the first place.
 *
 * <p><strong>An entry has no type and no resolution.</strong> A pointer to a
 * dispatch object, a pointer to a document, a URL and a citation are all the
 * same kind of thing to this service: a string it positions and does not
 * follow. Typing them would make the service responsible for something on the
 * other side of a boundary it deliberately does not cross.
 *
 * <h2>The entry has an identity, and the ordinal is not it</h2>
 *
 * {@link #id} is the key. {@link #ordinal} is the reader's order and carries
 * no address, so a reorder moves nothing a caller is holding.
 *
 * <p>The two are separate because <strong>a positional key and a withdrawal
 * status exclude each other.</strong> Withdraw two entries from a list of five
 * and the ordinals 3 and 4 carry tombstones; let the list grow back to five
 * and exactly those ordinals have to be reissued. Under a positional key the
 * write either collides with the tombstone or overwrites it — and an
 * overwritten tombstone is not preservation, it is a free slot that READS like
 * preservation, which is worse than a delete because it is invisible.
 *
 * <p>So density is a property of the LIVING entries: one living entry per
 * ordinal within an item, as a partial unique index, with withdrawn rows
 * exempt. A withdrawn row keeps whatever ordinal it had and is never consulted
 * for order.
 *
 * <p><strong>Cancellation rather than deletion</strong> (REA-0011 D15): an
 * entry a list leaves out is cancelled and left standing, its status moved to
 * {@link #WITHDRAWN}, the stored word of the cancelled state, with when it
 * ended and who ended it (V35). Its label and its target are never
 * overwritten: an entry is matched by its content, and a different content is
 * a new row. The rows go only with the draft they belong to, when
 * {@code discard} deletes it (V33).
 */
@Entity
@Table(name = "item_reference", schema = "worklist")
public class ItemReference extends TenantScoped {

    /** The entry is part of the list. */
    public static final String ASSERTED = "asserted";
    /** The entry was cancelled: part of the list once, and not any more. */
    public static final String WITHDRAWN = "withdrawn";

    /**
     * The row's identity inside this service: the surrogate every key onto it
     * targets (ADR-0042), allocated by the store, never part of an answer.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pk", nullable = false, updatable = false)
    public Long pk;

    /** The item this pointer hangs off. Immutable: an entry does not migrate. */
    @Column(name = "item_number", nullable = false, updatable = false)
    public Long itemNumber;

    /**
     * The reader's order. Dense across the LIVING entries and meaningless on a
     * withdrawn one, which keeps whatever it had.
     */
    @Column(name = "ordinal", nullable = false)
    public int ordinal;

    /** What a reader sees instead of the raw target. Optional: absent where none was given. */
    @Column(name = "label")
    public @Nullable String label;

    /** The pointer itself. Positioned, never followed. */
    @Column(name = "target", nullable = false)
    public String target;

    @Column(name = "status", nullable = false)
    public String status = ASSERTED;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    /**
     * When the entry was cancelled. Absent on a living entry, and on one
     * cancelled before V35 recorded it.
     */
    @Column(name = "ended_at")
    public @Nullable Instant endedAt;

    /**
     * Who cancelled the entry, as the service derived the subject from the
     * token. Absent where {@link #endedAt} is.
     */
    @Column(name = "ended_by")
    public @Nullable String endedBy;

    /**
     * Why the entry was cancelled. Absent where the cancelling call took no
     * reason, which is every {@code update}: no call cancels a single entry
     * with a reason.
     */
    @Column(name = "end_reason")
    public @Nullable String endReason;

    /**
     * Cancels the entry: its status moves and it records when and by whom. The
     * ordinal is left where it was; nothing reads it on a cancelled entry.
     */
    public void cancel(Instant at, String actor) {
        status = WITHDRAWN;
        endedAt = at;
        endedBy = actor;
        updatedAt = at;
    }
}
