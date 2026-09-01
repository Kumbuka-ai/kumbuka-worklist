package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.StringUuidConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One item: an entry in what a scope intends to do.
 *
 * <p>The object in the list is an <strong>Item</strong> (target state section
 * 3.1). Not "row", which is a property of the Markdown store being replaced,
 * and not "issue", which two foreign products already occupy.
 *
 * <h2>The address, and why half of it may be absent</h2>
 *
 * An item is addressed by {@code (scope, selector, number)} — {@code FEAT-51}
 * in a scope. Both halves of the selector-and-number pair are null on a raw
 * call-in and are set together when the item is admitted into an address
 * space. That is the intake state rather than a weakened invariant: something
 * gets called in before anybody has decided what kind of thing it is, and
 * demanding a selector at that moment means either guessing one or refusing
 * to record the call-in at all. A HALF address is the state that would be a
 * defect, and the database rejects it.
 *
 * <p>What the predecessor calls {@code Nr} — a corpus-wide running number on
 * every row from its first moment — is not here. It existed because a text
 * file has no other candidate for an address that predates ratification;
 * {@link #id} is present from the insert, immutable, and unique with no
 * high-water mark to maintain.
 *
 * <h2>The status vocabulary has no {@code planned}</h2>
 *
 * {@code planned} means "in an iteration", and it lived in the status column
 * because there was no membership table to derive it from. There is no
 * membership table here either — that is the planning layer, and a separate
 * piece of work — so it is not derivable yet, and the honest form of "not
 * derivable yet" is absent. A sixth value that somebody writes and nothing
 * maintains is worse than the gap it fills.
 *
 * <p>{@code withdrawn} IS present and is not from the predecessor's
 * vocabulary. It is what the predecessor's hard delete becomes, and it is
 * what makes the high-water mark a high-water mark by construction rather
 * than by a rule someone has to keep.
 *
 * <h2>What is still not here</h2>
 *
 * The milestone, the iteration and the sprint. All three belong to the
 * planning layer: the milestone's values are allocated by a milestone table,
 * and a column pointing at a table that does not exist is a column whose
 * values nothing can check.
 *
 * <p>{@code scopeId} is stored and never resolved from here. The platform
 * publishes a read contract for scope access; consuming it is a runtime read
 * through {@link ai.kumbuka.worklist.platform.ScopeDirectory}, not a
 * schema-level reference, which is why there is no foreign key and no join.
 */
@Entity
@Table(name = "item", schema = "worklist")
public class Item {

    /** Raw: called in, not yet characterised, no address. */
    public static final String NEW = "new";
    /** Characterised and live. */
    public static final String OPEN = "open";
    /** Carried out. */
    public static final String DONE = "done";
    /** Deliberately not carried out. */
    public static final String DROPPED = "dropped";
    /** No longer applicable — the termination value that claims no execution. */
    public static final String OBSOLETE = "obsolete";
    /** Taken back. What a delete would have been, without losing the number. */
    public static final String WITHDRAWN = "withdrawn";

    /**
     * The whole vocabulary, in one place, so that a refusal can say what was
     * possible and the check constraint in V4 has exactly one counterpart in
     * Java rather than a list per caller.
     */
    public static final List<String> STATUSES =
        List.of(NEW, OPEN, DONE, DROPPED, OBSOLETE, WITHDRAWN);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    public UUID id;

    /**
     * The tenancy axis — layer 1 of the enforcement model.
     *
     * <p>Typed as String because Quarkus' Hibernate tenant-resolver SPI is
     * String-only, and converted to the {@code uuid} column by
     * {@link StringUuidConverter}.
     */
    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    /** The platform scope this item belongs to. Stored, never resolved from here. */
    @Column(name = "scope_id", nullable = false)
    public UUID scopeId;

    // --- the address ------------------------------------------------------

    /** The declared selector, or null on a raw call-in. Set once, at admission. */
    @Column(name = "selector_id")
    public UUID selectorId;

    /** The number allocated in that selector's space, or null. Set once. */
    @Column(name = "number")
    public Long number;

    // --- what a caller characterises --------------------------------------

    /** One line, human readable (target state section 3.3). */
    @Column(name = "title", nullable = false)
    public String title;

    /** One of {@link #STATUSES}. */
    @Column(name = "status", nullable = false)
    public String status = NEW;

    @Column(name = "cluster_term_id")
    public UUID clusterTermId;

    @Column(name = "type_term_id")
    public UUID typeTermId;

    @Column(name = "priority_term_id")
    public UUID priorityTermId;

    @Column(name = "size_term_id")
    public UUID sizeTermId;

    /**
     * The component tags — {@code e2e}, {@code ee-srv}, {@code none}.
     *
     * <p>An array and not a relation. A tag has no identity, no attributes
     * and no lifecycle of its own, so a relation would buy a join and a
     * DELETE privilege and nothing else, and this schema grants DELETE
     * nowhere.
     *
     * <p>The predecessor holds these in one space-separated cell, and the
     * cell is the part that does not survive: a single string was a fact
     * about Markdown, and the several tags in it were always several.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "component", nullable = false, columnDefinition = "text[]")
    public String[] component = new String[0];

    /**
     * Free text. Null when nothing is on file.
     *
     * <p>The predecessor requires the literal {@code TBD} here, because a
     * Markdown cell cannot tell "empty" from "absent" and the column is
     * required. SQL can, so there is no filler token.
     */
    @Column(name = "reference")
    public String reference;

    // --- technical fields, server-derived ---------------------------------

    /**
     * Written by the database default and read back, never sent.
     *
     * <p>{@code @Generated} is what tells Hibernate to fetch the value rather
     * than to supply one: without it the entity would carry whatever it last
     * saw, and an insert would try to write a null into a not-null column.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    /**
     * Moved by an effective change and by nothing else.
     *
     * <p>The substrate mapped this as insert-only and said why: there was no
     * verb that updated an item, so a maintenance trigger could not have been
     * observed working. There is one now, and the observation it needed is
     * the interesting one — {@link ItemStore#amend} moves this field ONLY
     * when a value actually changed, so a write carrying nothing new leaves
     * it where it is. A database trigger would have been the wrong mechanism
     * for exactly that reason: it cannot tell a statement that changed
     * something from one that did not, and would stamp both.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false)
    public Instant updatedAt;

    /**
     * Opaque, by contract.
     *
     * <p>A caller reads it, sends it back with a write, and is refused if the
     * row moved on in between. That it happens to be a uuid is an
     * implementation detail: a caller that parses it is a caller that breaks
     * when the generator changes.
     *
     * <p>The predecessor's token covers the whole corpus, because every write
     * rewrote the whole file. This one covers the row, so two callers editing
     * two different items no longer conflict with each other.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "conflict_token", nullable = false, insertable = false)
    public String conflictToken;
}
