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

import java.time.Instant;

/**
 * The high-water mark of one address space.
 *
 * <h2>One counter per view</h2>
 *
 * One row per view and scope. Each view — item, iteration, milestone,
 * workstream — has its own counter, and that row is the one position. There is no scope-wide counter
 * beside them: the address form carries the view, so {@code .../item/1},
 * {@code .../iteration/1} and {@code .../milestone/1} are three different
 * addresses already, and a bare number that had to disambiguate itself
 * across them would solve a problem the address form does not have.
 *
 * <p>The key is a surrogate rather than the view so the ORM key stays
 * outside the tenancy axis, which the shared superclass owns and no caller
 * names.
 *
 * <h2>Why the mark is stored rather than computed</h2>
 *
 * {@code max(number) + 1} over the existing items is the obvious
 * implementation and it answers a different question. It reports the highest
 * number IN USE; the mark records the highest number HANDED OUT, and those
 * two sets differ by every number that was allocated and then lost —
 * allocated in a transaction that rolled back, or skipped by a mark that was
 * carried forward during an import.
 *
 * <p>Nothing in this schema deletes, so a row cannot vanish and take its
 * number back into circulation. That closes one way of losing the difference
 * and not the other, and the other is enough: a burnt number handed out twice
 * means two items answer to one address, and every reference to that address
 * silently becomes ambiguous. So the mark is a row, moved forward in the same
 * transaction that allocates.
 *
 * <h2>Settable, and only forward</h2>
 *
 * The predecessor's corpus will be imported one day, carrying numbers
 * allocated long before this table existed. An import that could not tell the
 * mark where the corpus had got to would start allocating from 1 into an
 * address space that is already occupied. So the mark can be carried
 * FORWARD — never back, because moving it back is precisely the act of
 * handing out numbers that are already in use.
 */
@Entity
@Table(name = "number_space", schema = "worklist")
public class NumberSpace extends TenantScoped {

    /**
     * The row's identity inside this service: the surrogate every key onto it
     * targets (ADR-0042), allocated by the store, never part of an answer.
     *
     * <p>The uuid column {@code id} the store still carries is not mapped. The
     * image before this one reads it, and the store fills it by default; the
     * next release drops it, and an image that mapped it could not start
     * against that schema.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pk", nullable = false, updatable = false)
    public Long pk;

    /**
     * The view whose address space this is: one of {@link Selector#VIEWS}.
     *
     * <p>Immutable and never null: a counter belongs to one view and does
     * not migrate between address spaces. Together with the scope it is the
     * counter's key (V24); there is one counter per view and scope.
     *
     * <p>The store still carries {@code selector_pk} beside it until the
     * contraction release. It is not mapped: the repository names it nowhere,
     * and the trigger of V24 fills it from this column on every insert, so the
     * previous image still finds the selector its counters hang on.
     */
    @Column(name = "view", updatable = false, nullable = false)
    public String view;

    // No workstream. There was one counter shape per (scope, workstream) for
    // the `milestone` selector between 2026-09-08 and V12; V12 moved every
    // milestone counter back to scope-wide, replaced the two partial unique
    // indexes with the single `uq_number_space_selector (tenant, scope,
    // selector)` and marked `number_space.workstream_id` DEAD. Every counter
    // now takes one shape, so there is nothing for a discriminator to
    // discriminate.
    //
    // The column was unmapped first, for the reason the milestone's was:
    // while a deployed image selected it, its DROP was not available. V17
    // dropped both columns and their foreign keys.

    /**
     * The highest number ever handed out in this space. Zero means none has
     * been, so the first allocation is 1.
     */
    @Column(name = "high_water_mark", nullable = false)
    public long highWaterMark;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
