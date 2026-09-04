package ai.kumbuka.worklist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * A declared value: an identity, a display name, a rank and an optional
 * description.
 *
 * <p>The concept describes ONE kind of object here, and the schema
 * nevertheless carries three tables for it — {@link ItemStatus},
 * {@link AttributeOption} and {@link RelationType} — plus
 * {@link AttributeDefinition} for the declaration an option belongs to. The
 * reason is the platform properties: a status carries the four predicates, a
 * relation type carries whether it blocks, an option carries neither. In one
 * table those become two blocks of columns that are null for two of the three
 * kinds and mandatory for the third, which no check constraint expresses
 * without enumerating the kinds — a discriminator by another name.
 *
 * <p><strong>This class is what survives of "they are one kind of
 * object".</strong> The study says so in as many words: what remains is a
 * naming and behaviour convention — the same column names, the same rank
 * semantics, the same withdrawal rule — rather than one table. Here that
 * convention is a shared parent instead of four copies, so a fifth kind of
 * declared value inherits the shape rather than reproducing it.
 *
 * <h2>The identity is separate from the name, and that is the whole point</h2>
 *
 * What an item stores is {@link #id}; what a reader sees is {@link #name}.
 * Renaming a value is therefore not a data migration and breaks nothing. The
 * predecessor cannot do this — there the value IS the identifier, so renaming
 * a cluster would rewrite several hundred items and invalidate every reference
 * to it in prose.
 *
 * <h2>The rank, and the description</h2>
 *
 * {@link #rank} is the order of the vocabulary and never the alphabetical one.
 * Without it a size axis sorts L before M before S, and the defect surfaces in
 * the console rather than at declaration.
 *
 * <p>{@link #description} is a place, not an obligation. It exists so that the
 * meaning of a value can stand in the system instead of in somebody's head —
 * which is exactly where the estate's own size vocabulary stood until it was
 * asked about. Whether a scope fills it is the scope's business.
 *
 * <h2>Withdrawal, never deletion</h2>
 *
 * {@link #status} is the declaration's own lifecycle and not the value the row
 * defines. A value that was written onto items has to stay resolvable, or
 * those items become unreadable in their own history — and nothing in this
 * schema holds a DELETE privilege in any case.
 */
@MappedSuperclass
public abstract class DeclaredValue extends TenantScoped {

    /** A value that may still be set on something. */
    public static final String DECLARED = "declared";
    /** Withdrawn: resolvable for what carries it, closed to anything new. */
    public static final String WITHDRAWN = "withdrawn";

    /** What an item stores. Stable across every rename of the name. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    public UUID id;

    /** What a reader sees. Changeable at will. */
    @Column(name = "name", nullable = false)
    public String name;

    /** Optional, and a place rather than an obligation. */
    @Column(name = "description")
    public String description;

    /** The order of the vocabulary, and never the alphabetical one. */
    @Column(name = "rank", nullable = false)
    public int rank;

    /** {@link #DECLARED} or {@link #WITHDRAWN}. */
    @Column(name = "status", nullable = false)
    public String status = DECLARED;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false)
    public Instant updatedAt;
}
