package ai.kumbuka.worklist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * One iteration: when an item is being worked.
 *
 * <h2>It carries no status column, and that is the design</h2>
 *
 * Complete is DERIVED from its memberships and current is a POINTER, so
 * neither is a column here. The pointer lives on
 * {@link ScopeSetting#currentIterationId}: a boolean here would allow two
 * current iterations and would then need a partial unique index to forbid
 * what a single nullable pointer cannot express in the first place.
 *
 * <p>{@link #closedAt} is a FACT and not a status. What "complete" means is a
 * question about memberships, and answering it from a timestamp would be the
 * stored copy the concept refuses. What the timestamp does answer is whether
 * the iteration is still open, which is a different question and the one the
 * derivation of {@code planned} asks.
 *
 * <h2>The description and the motto are mandatory</h2>
 *
 * That is not decoration. They are the only machine-readable criterion by
 * which an agent can REFUSE an item as out of scope for the current
 * iteration, which is why the columns are not null rather than merely
 * conventional.
 *
 * <h2>Numbers are never reused</h2>
 *
 * The mechanism is the persisted mark on {@link ScopeSetting} rather than the
 * highest number present. A number burned by a failed write stays burned:
 * non-reuse is the invariant, density is not.
 */
@Entity
@Table(name = "iteration", schema = "worklist")
public class Iteration extends AggregateRoot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    public UUID id;

    /** Allocated from the scope's iteration mark and never reused. */
    @Column(name = "number", nullable = false)
    public long number;

    /** What this iteration is about, in a phrase. Mandatory. */
    @Column(name = "motto", nullable = false)
    public String motto;

    /** What it contains and what it does not. Mandatory, and the refusal criterion. */
    @Column(name = "description", nullable = false)
    public String description;

    /**
     * The order of the planned iterations, and what {@code advance} reads.
     *
     * <p>Never the alphabetical order and never the number: a scope may
     * decide to work its fifth iteration before its fourth, and the number is
     * an identity rather than a sequence.
     */
    @Column(name = "rank", nullable = false)
    public int rank;

    /** When it was closed, or null while it is open. A fact, not a status. */
    @Column(name = "closed_at")
    public Instant closedAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false)
    public Instant updatedAt;

    /** Whether this iteration is still open. Read from the fact, never from a status. */
    public boolean open() {
        return closedAt == null;
    }

    @Override
    protected String subject() {
        return Addressed.ITERATION.description();
    }

    @Override
    protected void touch(Instant now) {
        updatedAt = now;
    }
}
