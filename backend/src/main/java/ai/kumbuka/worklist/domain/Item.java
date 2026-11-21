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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * One item: an entry in what a scope intends to do.
 *
 * <p>The object in the list is an <strong>Item</strong>. Not "row", which is a
 * property of the Markdown store being replaced, and not "issue", which two
 * foreign products already occupy.
 *
 * <h2>A fixed core plus declared attributes</h2>
 *
 * The core is what the SERVICE itself reasons about or what the platform's
 * model fixes for every item. Title and description are core because every
 * listing and every reader needs them. Status is core because the
 * predicates hang on it. References are core because their form is
 * validated. The property core of DEC-0037 — {@link #creator},
 * {@link #contributor}, {@link #parentNumber}, {@link #priority},
 * {@link #severity} and {@link #closeDate} — is core because the model
 * carries it on every item, under one name and with the value sets of the
 * standard: {@code priority} and {@code severity} are columns with a fixed
 * value set, not declarations of a scope. Cluster, size, type and the
 * component tags are core in none of these senses, and they are therefore
 * DECLARED attributes, stored in {@link #attributes} under the identity of
 * their declaration; a scope that declared an attribute of its own with the
 * key {@code priority} keeps it there, beside the core field.
 *
 * <p>That is the single most important structural change from the
 * predecessor, whose specification fixes an item at exactly sixteen cells: a
 * seventeenth column was a schema change there, and here it is a row in
 * {@link AttributeDefinition}.
 *
 * <h2>The phase is a column, the status a reference</h2>
 *
 * {@link #phase} is the item's place in its lifecycle ({@link Phase}), held
 * here and written by the transitions alone (REA-0011 D11): a status write
 * never moves it, so a change to a scope's vocabulary moves no item between
 * phases. The predicates {@code closed} and {@code in_progress} are a
 * projection of it (TAR-0002 B3).
 *
 * <p>{@link #statusPk} points at a declared {@link ItemStatus} of the phase
 * the item's phase binds it to ({@link Phase#statusPhase()}), carrying
 * {@code actionable}, {@code successful} and the predicates a scope may use.
 * Those are a join and never a copy on this row.
 *
 * <h2>The address, and why half of it may be absent</h2>
 *
 * An item is addressed by {@code (scope, selector, number)} — {@code FEAT-51}
 * in a scope. Both halves of the selector-and-number pair are null on a raw
 * call-in and are set together when the item is accepted into an address
 * space. That is the intake state rather than a weakened invariant:
 * something gets called in before anybody has decided what kind of thing it
 * is. A HALF address is the state that would be a defect, and the database
 * rejects it.
 *
 * <p><strong>The uniqueness is the triple scope, selector and number</strong>
 * and never the pair without the selector — under both allocation modes. A
 * store constraining the pair could not later admit per-selector numbering,
 * and once two selectors had shared a number the constraint could never be
 * switched on again.
 *
 * <h2>What is deliberately not a field here</h2>
 *
 * <p><strong>No {@code planned}.</strong> It is a view over iteration
 * membership, so the orphan class — an item reading planned with no
 * membership — is not merely forbidden but inexpressible. It was observed
 * twice in the predecessor.
 *
 * <p><strong>No iteration and no sprint column.</strong> Membership is an
 * entity; a column would be a second copy of it, and the predecessor's pair
 * of columns exists only because a markdown table cannot join.
 *
 * <p><strong>No relation column.</strong> Relations are rows with a type, in
 * {@link ItemRelation}. {@link #parentNumber} is not one of them: an item has
 * at most one parent, so the model holds it as a field of the item.
 *
 * <p>{@link #scopeId} is stored and never resolved from here. The platform
 * publishes a read contract for scope access; consuming it is a runtime read
 * through {@link ai.kumbuka.worklist.platform.ScopeDirectory}, not a
 * schema-level reference, which is why there is no foreign key and no join.
 */
@Entity
@Table(name = "item", schema = "worklist")
public class Item extends AggregateRoot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    public UUID id;



    // --- the address ------------------------------------------------------

    /**
     * The number allocated in the scope's item space. Set once, never moved:
     * every key onto an item is (tenant, scope, number), and the store refuses
     * a change below the domain.
     *
     * <p>The selector this number was drawn under is not mapped. Every item
     * hangs on the view selector {@code item}; the store keeps the column for
     * the image before this one and fills it itself until it is dropped.
     */
    @Column(name = "number")
    public Long number;

    // --- the core ---------------------------------------------------------

    /** One line, the item's handle in every listing. */
    @Column(name = "title", nullable = false)
    public String title;

    /**
     * What the item is and <strong>why</strong> it matters — never how it will
     * be done.
     *
     * <p>The design lives in the document a reference points at. Without the
     * boundary the description grows into a specification held in the wrong
     * service.
     *
     * <p>Absent where the item carries no description.
     */
    @Column(name = "description")
    public @Nullable String description;

    /**
     * The phase, as its token ({@link Phase#token()}). Written by the
     * transitions and by nothing else; read through {@link #phase()}.
     *
     * <p>The column defaults to {@code open} for the image before V33, which
     * inserts without it (N-1); this image writes it on every insert.
     */
    @Column(name = "phase", nullable = false)
    public String phase = Phase.OPEN.token();

    /**
     * The declared status. Mandatory: an item always has one, a draft
     * included, and which statuses exist is the scope's declaration rather
     * than this service's. It belongs to the phase {@link #phase()} binds it
     * to: a status of another phase is refused by {@code update}.
     */
    @Column(name = "status_pk", nullable = false)
    public Long statusPk;

    /**
     * The goal axis, including the three marker rows; absent where the item is
     * on no milestone.
     *
     * <p>Read and never written through the item verbs. Setting it is a
     * planning act, and the planning layer's verbs are a separate piece of
     * work; the column is here because a milestone reference that resolves is
     * a property of the schema and not of whoever writes that verb.
     */
    @Column(name = "milestone_number")
    public @Nullable Long milestoneNumber;

    /**
     * The workstream this item belongs to. Mandatory, once V10 lands.
     *
     * <p>Ratified 2026-09-08: an item carries a workstream as an
     * obligation, and a milestone reference on this item — if it carries
     * one — has to lie in this same workstream. The invariant is enforced
     * by {@code ItemService}, not by a database constraint, because the
     * check spans two tables and a check constraint cannot join.
     *
     * <p>The column is NOT NULL since V19, which narrows it after V18 added
     * it nullable and backfilled it. The mapping does not repeat that with
     * {@code nullable = false}; the NOT NULL is the database's, and
     * {@code ItemService} sees to it that a create always names a
     * workstream, falling back to the scope's default.
     */
    @Column(name = "workstream_number")
    public Long workstreamNumber;

    /**
     * Every declared attribute of this item, keyed by the DEFINITION'S
     * SURROGATE, with an option stored as the option's surrogate (ADR-0042).
     *
     * <p>Not by its key: a rename of a declaration would otherwise be a data
     * migration, which the separation of identity from name exists to
     * prevent.
     *
     * <p><strong>One document column and not an entity-attribute-value
     * table</strong>, and the reason is the read path rather than elegance.
     * Under EAV a single item read fans out to one row per attribute,
     * filtering on two attributes needs two joins, and the item's own row
     * stops being the item.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attributes", nullable = false, columnDefinition = "jsonb")
    public Map<String, Object> attributes = new LinkedHashMap<>();

    // --- the property core (DEC-0037 C3) ----------------------------------

    /**
     * The identity that created the item: the calling subject, written by the
     * service at the create and never again. Absent on an item created before
     * the property core arrived, which nothing fills retroactively.
     */
    @Column(name = "creator", updatable = false)
    public @Nullable String creator;

    /**
     * The identities of the platform responsible for the work the item needs,
     * in the caller's order and distinct. Absent where none was ever set; an
     * item reads an absent list and an empty one alike, as no contributor.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "contributor", columnDefinition = "jsonb")
    public @Nullable List<String> contributor;

    /**
     * The number of the item this item is part of, in the same scope; absent
     * where it is part of none. The chain is free of cycles, which
     * {@link ItemService} holds when it is written.
     */
    @Column(name = "parent_number")
    public @Nullable Long parentNumber;

    /** One of {@link ItemFields#PRIORITIES}; absent where none was set. */
    @Column(name = "priority")
    public @Nullable String priority;

    /** One of {@link ItemFields#SEVERITIES}; absent where none was set. */
    @Column(name = "severity")
    public @Nullable String severity;

    /**
     * When the item closed: set by the service when the item enters the
     * phase closed, through {@code close} or through {@code relate} with
     * {@code duplicate_of}. Nothing reopens a closed item, so nothing clears
     * it. Absent on an item that is not closed, and on an item closed before
     * the property core arrived.
     */
    @Column(name = "close_date")
    public @Nullable Instant closeDate;

    // --- technical fields, server-derived ---------------------------------

    /**
     * Written by the service from its clock at the insert, never sent
     * (C20.1). The column keeps its database default for the previous image,
     * which inserts without it (N-1).
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    /**
     * Moved by an effective change and by nothing else.
     *
     * <p>A timestamp and not a date. The predecessor carried a date, so two
     * changes on one day were indistinguishable in the store — a defect that
     * costs nothing to avoid at the point where the column is defined.
     *
     * <p>{@link ItemService#update} moves this field ONLY when a value
     * actually changed, so a write carrying nothing new leaves it where it is.
     * A database trigger would have been the wrong mechanism for exactly that
     * reason: it cannot tell a statement that changed something from one that
     * did not, and would stamp both.
     *
     * <p>It is also the conflict token ({@link AggregateRoot#conflictToken()}).
     */
    @Column(name = "changed_at", nullable = false)
    public Instant changedAt;

    /**
     * The phase this item is in.
     *
     * @throws IllegalStateException for a token the column's CHECK of V33
     *         refuses, which no row can carry
     */
    public Phase phase() {
        return Phase.byToken(phase).orElseThrow(() -> new IllegalStateException(
            "an item carries a phase the store admits no row with"));
    }

    /** Moves the item into a phase: the act of a transition, and of nothing else. */
    void enter(Phase next) {
        phase = next.token();
    }

    /**
     * The item, as a refusal names it.
     *
     * <p>The conflict token itself and the two acts around it are on
     * {@link AggregateRoot}, which is where they moved when the planning
     * layer gained three more roots that need the same pair. The
     * predecessor's token covers the whole corpus, because every write
     * rewrote the whole file; this one covers the aggregate, so two callers
     * editing two different items no longer conflict with each other.
     */
    @Override
    protected String subject() {
        return Addressed.ITEM.description();
    }

    /** The item's modification column is {@code changed_at}, as the target schema names it. */
    @Override
    protected void touch(Instant at) {
        changedAt = at;
    }

    @Override
    protected Optional<Instant> changeTime() {
        return Optional.ofNullable(changedAt);
    }

    @Override
    protected void created(Instant at) {
        createdAt = at;
    }
}
