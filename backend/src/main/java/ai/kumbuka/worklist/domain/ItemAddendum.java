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
 * One addendum of an item: a text written on it while it lives (REA-0011 D13).
 *
 * <h2>Beside the item, never part of it</h2>
 *
 * An addendum is a row of its own and is written by {@code addendum} alone.
 * Writing one writes no row of the item, so the item's conflict token and
 * change time stay where they are. It is how a submitted item, whose content
 * is frozen, is corrected or commented.
 *
 * <h2>Append only</h2>
 *
 * Every column is insert-only here, and the store backs that: V34 grants the
 * runtime role SELECT and INSERT on the table and neither UPDATE nor DELETE.
 * A second addendum is a new row and leaves the first as it is.
 *
 * <p>{@link #author} is the calling subject as the service derived it from
 * the token, never a value a caller chose.
 */
@Entity
@Table(name = "item_addendum", schema = "worklist")
public class ItemAddendum extends TenantScoped {

    /**
     * The row's identity inside this service, allocated by the store, never
     * part of an answer. Orders two addenda written at the same instant.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pk", nullable = false, updatable = false)
    public Long pk;

    /** The item the addendum is written on. */
    @Column(name = "item_number", nullable = false, updatable = false)
    public Long itemNumber;

    /** What the addendum says, trimmed, never blank. */
    @Column(name = "text", nullable = false, updatable = false)
    public String text;

    /** The subject that wrote the addendum, as the service derived it. */
    @Column(name = "author", nullable = false, updatable = false)
    public String author;

    /**
     * The delegated identity the author acted through. Absent where the
     * service's call context carries none, which is every call today.
     */
    @Column(name = "delegated_identity", updatable = false)
    public @Nullable String delegatedIdentity;

    /** When the addendum was written, at the microsecond. */
    @Column(name = "written_at", nullable = false, updatable = false)
    public Instant writtenAt;
}
