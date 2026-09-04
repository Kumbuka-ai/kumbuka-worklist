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
import org.hibernate.annotations.TenantId;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * One status value a scope declared, and the four predicates it maps onto.
 *
 * <p>The predecessor fixes its status vocabulary in a check constraint over
 * five literals, so a scope that works differently has to be released for.
 * Here a status is a declared value like any other: it has an identity, a
 * display name, a rank and an optional description, and what an item stores
 * is the identity.
 *
 * <h2>The four predicates are the whole of the platform's guarantee</h2>
 *
 * <ul>
 *   <li>{@link #closed} — is the item finished, is there nothing further to
 *       do?</li>
 *   <li>{@link #successful} — was it achieved, as opposed to abandoned?</li>
 *   <li>{@link #inProgress} — is someone working on it now?</li>
 *   <li>{@link #actionable} — is it worked out well enough to be taken
 *       up?</li>
 * </ul>
 *
 * <p>The set is deliberately small: a predicate is an obligation every scope
 * must be able to answer. {@code successful} is the one that is not obvious
 * and it is load-bearing — finished is not achieved, and without the
 * distinction a blocking relation pointing at an ABANDONED item would
 * unblock.
 *
 * <p><strong>The predicates are never stored on the item.</strong> The item
 * stores its status; the answer to "is this closed" is a join. A stored copy
 * would be a second truth that drifts, which is what a literal set in a check
 * constraint was.
 *
 * <p>Withdrawal is a status here for the reason it is one everywhere in this
 * schema: a value that was written onto items has to stay resolvable, or
 * those items become unreadable in their own history.
 */
@Entity
@Table(name = "item_status", schema = "worklist")
public class ItemStatus {

    /** A value that may still be set on an item. */
    public static final String DECLARED = "declared";
    /** Withdrawn: resolvable for what carries it, closed to anything new. */
    public static final String WITHDRAWN = "withdrawn";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    public UUID id;

    /** The tenancy axis. String-typed for the resolver SPI; see {@link Item}. */
    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "scope_id", nullable = false)
    public UUID scopeId;

    /**
     * What a reader sees. Changeable at will — an item stores {@link #id}, so
     * a rename is not a data migration and breaks no reference.
     */
    @Column(name = "name", nullable = false)
    public String name;

    /**
     * A place, not an obligation. It exists so that the meaning of a value can
     * stand in the system instead of in somebody's head.
     */
    @Column(name = "description")
    public String description;

    /**
     * The order of the vocabulary, and never the alphabetical one. Without it
     * a size axis sorts L before M before S, and the defect surfaces in the
     * console rather than at declaration.
     */
    @Column(name = "rank", nullable = false)
    public int rank;

    /** Worked out well enough to be taken up. No standard carries this one. */
    @Column(name = "actionable", nullable = false)
    public boolean actionable;

    /** Somebody is working on it now. Mutually exclusive with {@link #closed}. */
    @Column(name = "in_progress", nullable = false)
    public boolean inProgress;

    /** Finished: there is nothing further to do. */
    @Column(name = "closed", nullable = false)
    public boolean closed;

    /**
     * Achieved rather than abandoned.
     *
     * <p>Meaningful only where {@link #closed} holds, and deliberately not
     * constrained to be false elsewhere: a scope that records the eventual
     * outcome on a non-terminal value is not wrong, the platform simply does
     * not read the field there.
     */
    @Column(name = "successful", nullable = false)
    public boolean successful;

    /** The declaration's own lifecycle: {@link #DECLARED} or {@link #WITHDRAWN}. */
    @Column(name = "status", nullable = false)
    public String status = DECLARED;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false)
    public Instant updatedAt;
}
