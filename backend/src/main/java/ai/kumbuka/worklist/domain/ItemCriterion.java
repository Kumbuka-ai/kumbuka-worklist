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
 * One acceptance criterion of an item (REA-0011 S12, D14; TAR-0002 B17).
 *
 * <p><strong>A criterion carries its text and nothing else.</strong> No
 * status of fulfilment, no evidence, no author: what is booked against a
 * criterion is a record of its own and books against the identifier below.
 *
 * <h2>The identifier is the service's, and it is issued once</h2>
 *
 * {@link #number} is the {@code k} of the address
 * {@code worklist://<scope>/item/<n>/criterion/<k>}. The service allocates it
 * beneath the item as the next number after the highest the item ever
 * issued, withdrawn criteria counted; a caller never chooses one, because
 * identifiers a caller chooses collide and get reused (D14, rejected
 * alternative). The store holds the rule as a unique index over every row of
 * the item, withdrawn ones included, so a number stays spent once issued; the
 * item's conflict token orders two writers on one item before that index is
 * ever reached.
 *
 * <p>This is where the criterion departs from {@link ItemReference}, the
 * pattern it otherwise follows: a reference's ordinal is the reader's order
 * and dense over the living entries, so it is reissued as the list grows
 * back. A criterion's number is an identity and is never reissued.
 *
 * <p><strong>Withdrawal rather than deletion</strong>, as everywhere in this
 * schema: nothing holds a DELETE privilege, so a criterion that leaves the
 * list is marked withdrawn, keeps its number and its text, and is no longer
 * answered.
 *
 * <p>Criteria are as writable as the description until the field classes per
 * phase arrive with a later step (REA-0011 S10, Annex A2); freezing them at
 * submit belongs to that step and is not built here.
 */
@Entity
@Table(name = "item_criterion", schema = "worklist")
public class ItemCriterion extends TenantScoped {

    /** The criterion is part of the item. */
    public static final String ASSERTED = "asserted";
    /** It was part of the item once; its number stays spent. */
    public static final String WITHDRAWN = "withdrawn";

    /**
     * The row's identity inside this service: the surrogate (ADR-0042),
     * allocated by the store, never part of an answer.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "pk", nullable = false, updatable = false)
    public Long pk;

    /** The item this criterion belongs to. Immutable: a criterion does not migrate. */
    @Column(name = "item_number", nullable = false, updatable = false)
    public Long itemNumber;

    /**
     * The identifier beneath the item, from 1, allocated by the service and
     * never reissued. Immutable.
     */
    @Column(name = "number", nullable = false, updatable = false)
    public Long number;

    /** What the criterion says. Never blank. */
    @Column(name = "text", nullable = false)
    public String text;

    @Column(name = "status", nullable = false)
    public String status = ASSERTED;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;
}
