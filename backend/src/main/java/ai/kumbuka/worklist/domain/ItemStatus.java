package ai.kumbuka.worklist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * One status value a scope declared, and the four predicates it maps onto.
 *
 * <p>The predecessor fixes its status vocabulary in a check constraint over
 * five literals, so a scope that works differently has to be released for.
 * Here a status is a {@link DeclaredValue} like any other: it has an identity,
 * a display name, a rank and an optional description, and what an item stores
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
 * unblock, and an agent would build on sand.
 *
 * <p>{@code actionable} separates a raw call-in from a worked-out item. No
 * standard carries it and it is needed daily: it is the criterion that keeps
 * an uncharacterised item out of an iteration.
 *
 * <p><strong>The predicates are never stored on the item.</strong> The item
 * stores its status; the answer to "is this closed" is a join. A stored copy
 * would be a second truth that drifts, which is what a literal set in a check
 * constraint was.
 *
 * <p>Two coherence rules travel with these four, and only one of them is
 * expressible here. That {@code closed} and {@code inProgress} exclude each
 * other is a statement about a row and is a check constraint. That a scope
 * declares at least one actionable and at least one closed status is a
 * statement about a SET of rows; it is enforced at declaration time in the
 * domain, and that is a piece of work of its own.
 */
@Entity
@Table(name = "item_status", schema = "worklist")
public class ItemStatus extends DeclaredValue {

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
}
