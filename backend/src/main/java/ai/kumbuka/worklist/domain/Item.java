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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One item: an entry in what a scope intends to do.
 *
 * <p>The object in the list is an <strong>Item</strong>. Not "row", which is a
 * property of the Markdown store being replaced, and not "issue", which two
 * foreign products already occupy.
 *
 * <h2>A small fixed core plus declared attributes</h2>
 *
 * The core is what the SERVICE itself reasons about, and the test for
 * admission is sharp: a field is core only if the service asks a question
 * about it. Title and description are core because every listing and every
 * reader needs them. Status is core because the four predicates hang on it.
 * References are core because their form is validated. Cluster, size,
 * priority, type and the component tags are core in none of these senses —
 * the service never asks a question about them — and they are therefore
 * DECLARED attributes, stored in {@link #attributes} under the identity of
 * their declaration.
 *
 * <p>That is the single most important structural change from the
 * predecessor, whose specification fixes an item at exactly sixteen cells: a
 * seventeenth column was a schema change there, and here it is a row in
 * {@link AttributeDefinition}.
 *
 * <h2>The status is a reference, and the predicates are never stored here</h2>
 *
 * {@link #statusId} points at a declared {@link ItemStatus} carrying
 * {@code actionable}, {@code in_progress}, {@code closed} and
 * {@code successful}. The answer to "is this closed" is a join, and a copy of
 * it on this row would be a second truth that drifts — which is what a check
 * constraint over five literals was.
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
 * {@link ItemRelation}.
 *
 * <p>{@link #scopeId} is stored and never resolved from here. The platform
 * publishes a read contract for scope access; consuming it is a runtime read
 * through {@link ai.kumbuka.worklist.platform.ScopeDirectory}, not a
 * schema-level reference, which is why there is no foreign key and no join.
 */
@Entity
@Table(name = "item", schema = "worklist")
public class Item {

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

    /** The declared selector, or null on a raw call-in. Set once, at acceptance. */
    @Column(name = "selector_id")
    public UUID selectorId;

    /** The number allocated in that selector's space, or null. Set once. */
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
     */
    @Column(name = "description")
    public String description;

    /**
     * The declared status. Mandatory: an item always has one, and which
     * statuses exist is the scope's declaration rather than this service's.
     */
    @Column(name = "status_id", nullable = false)
    public UUID statusId;

    /**
     * The goal axis, including the three marker rows, or null.
     *
     * <p>Read and never written through the item verbs. Setting it is a
     * planning act, and the planning layer's verbs are a separate piece of
     * work; the column is here because a milestone reference that resolves is
     * a property of the schema and not of whoever writes that verb.
     */
    @Column(name = "milestone_id")
    public UUID milestoneId;

    /**
     * Every declared attribute of this item, keyed by the DEFINITION'S
     * IDENTITY.
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
     * <p>A timestamp and not a date. The predecessor carried a date, so two
     * changes on one day were indistinguishable in the store — a defect that
     * costs nothing to avoid at the point where the column is defined.
     *
     * <p>{@link ItemService#update} moves this field ONLY when a value
     * actually changed, so a write carrying nothing new leaves it where it is.
     * A database trigger would have been the wrong mechanism for exactly that
     * reason: it cannot tell a statement that changed something from one that
     * did not, and would stamp both.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "changed_at", nullable = false, insertable = false)
    public Instant changedAt;

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
