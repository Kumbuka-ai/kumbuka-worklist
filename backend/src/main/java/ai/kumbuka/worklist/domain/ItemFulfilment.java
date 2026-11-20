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
import java.util.List;

/**
 * One entry of an item's fulfilment ledger: what an instance reported against
 * one acceptance criterion of the item.
 *
 * <h2>Beside the item, never part of it</h2>
 *
 * An entry is a row of its own and is written by {@code book} alone. Booking
 * writes no row of the item, so the item's conflict token and change time
 * stay where they are, and the ledger takes entries on an item in any
 * status, a closed one included.
 *
 * <h2>Append only</h2>
 *
 * Every column is insert-only here, and the store backs that: V30 grants the
 * runtime role SELECT and INSERT on the table and neither UPDATE nor DELETE.
 * A correction is a new booking; the latest entry per criterion and instance
 * is the one that holds.
 *
 * <p>{@link #criterionNumber} is the {@code k} of the criterion's address. It
 * names a criterion the item issued, possibly one withdrawn since: the ledger
 * holds the contract an instance entered with its copy of the criteria, and
 * the contract outlives the criterion. {@link #actor} is the calling subject
 * as the service derived it from the token, never a value a caller chose.
 */
@Entity
@Table(name = "item_fulfilment", schema = "worklist")
public class ItemFulfilment extends TenantScoped {

    /** The criterion is fulfilled by the instance. */
    public static final String MET = "met";
    /** The instance reports the criterion as not fulfilled. */
    public static final String NOT_MET = "not_met";
    /** The criterion does not apply to what the instance did. */
    public static final String NOT_APPLICABLE = "not_applicable";

    /** The three statuses the platform fixes, and no other. */
    public static final List<String> STATUSES = List.of(MET, NOT_MET, NOT_APPLICABLE);

    /**
     * The row's identity inside this service, allocated by the store, never
     * part of an answer. Orders two entries booked at the same instant.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pk", nullable = false, updatable = false)
    public Long pk;

    /** The item the entry is booked against. */
    @Column(name = "item_number", nullable = false, updatable = false)
    public Long itemNumber;

    /** The identifier of the criterion beneath the item, from 1. */
    @Column(name = "criterion_number", nullable = false, updatable = false)
    public Long criterionNumber;

    /** The address of the instance that reported, positioned and never followed. */
    @Column(name = "instance", nullable = false, updatable = false)
    public String instance;

    /** One of {@link #STATUSES}. */
    @Column(name = "status", nullable = false, updatable = false)
    public String status;

    /** The reference to the evidence, positioned and never followed. */
    @Column(name = "evidence", nullable = false, updatable = false)
    public String evidence;

    /** The subject that booked the entry, as the service derived it. */
    @Column(name = "actor", nullable = false, updatable = false)
    public String actor;

    /** When the entry was booked, at the microsecond. */
    @Column(name = "booked_at", nullable = false, updatable = false)
    public Instant bookedAt;
}
