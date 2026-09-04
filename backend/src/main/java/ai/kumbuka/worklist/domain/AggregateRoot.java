package ai.kumbuka.worklist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A row a caller may hold a view of, and the two acts that keep that view
 * honest.
 *
 * <h2>The aggregate is the unit, not the row</h2>
 *
 * Optimistic locking is per AGGREGATE. {@link Item}, {@link Iteration} and
 * {@link Milestone} are aggregate roots; {@link ScopeSetting} is a
 * configuration object with a token of its own. A write presents the token of
 * its ROOT — so a reorder writes the iteration whose membership list happens
 * to be twelve rows and presents that iteration's one token, never twelve.
 *
 * <p>{@link IterationMembership} is therefore NOT one of these. It is
 * addressed at its own address and it owns no token: it presents its
 * iteration's. Addressing and token ownership are two different things and
 * here they come apart — which is why this class is what a root extends
 * rather than what every table extends.
 *
 * <h2>Why the two acts are here and not copied into each service</h2>
 *
 * {@link #stamp()} and {@link #requireCurrentToken(Object)} were private
 * static methods on {@code ItemService}, typed to {@code Item}. Four
 * aggregates would have meant four copies, which is the shape a duplication
 * gate has already caught once in this repository — and the shape where the
 * four slowly stop agreeing about what a conflict is.
 *
 * <p>They sit on the root rather than in a helper because the pair is a
 * property of a versioned row: the token means nothing without the write that
 * rotates it, and the rotation means nothing without the check that reads it.
 * Splitting them across two classes would let one of them be called alone,
 * which is precisely the defect they exist against.
 *
 * <h2>The modification timestamp is NOT here</h2>
 *
 * {@link Item} carries {@code changed_at} and the planning tables carry
 * {@code updated_at}, as V4 names them. A shared column here would mean
 * overriding the one that differs, which is a longer way to say the same
 * thing and hides which table is the exception — the same reasoning that
 * keeps the timestamps out of {@link TenantScoped}. So the root declares
 * {@link #touch(Instant)} instead and each subclass moves its own column.
 */
@MappedSuperclass
public abstract class AggregateRoot extends TenantScoped {

    /**
     * Opaque, by contract.
     *
     * <p>A caller reads it, sends it back with a write, and is refused if the
     * row moved on in between. That it happens to be a uuid is an
     * implementation detail: a caller that parses it is a caller that breaks
     * when the generator changes.
     *
     * <p>{@code @Generated} is what tells Hibernate to fetch the value the
     * database default wrote rather than to supply one, and
     * {@code insertable = false} is what keeps the entity from trying to
     * write a null into a not-null column on the insert.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "conflict_token", nullable = false, insertable = false)
    public String conflictToken;

    /**
     * The word this root is called by in a refusal.
     *
     * <p>A caller told "the token given is not the one this row carries" has
     * to go and work out which row was meant. The whole value of a typed
     * refusal is that the answer is in it.
     */
    protected abstract String subject();

    /** Move this root's own modification timestamp, under whatever name it has. */
    protected abstract void touch(Instant now);

    /**
     * The modification timestamp and the conflict token, moved together.
     *
     * <p>One method, called only where an effective change was established,
     * so that "the token rotated" and "something changed" cannot come apart.
     * A write that changes nothing does not reach this line, and the change
     * trail keeps meaning what it says.
     */
    public void stamp() {
        touch(Instant.now());
        conflictToken = UUID.randomUUID().toString();
    }

    /**
     * The conflict token presented must be the one this root currently
     * carries.
     *
     * <p>A stale one and an absent one are the same refusal, and it carries
     * the CURRENT token: a caller that has to make a second call just to
     * learn what it should have sent will end up reading before every write,
     * and a caller that reads in order to overwrite has stopped detecting
     * conflicts.
     */
    public void requireCurrentToken(Object presented) {
        if (presented != null && conflictToken.equals(String.valueOf(presented))) {
            return;
        }
        throw new WorklistException(
            WorklistException.Reason.CONFLICT,
            (presented == null
                ? "a write carries the conflict token it read; none was given. "
                : "the conflict token given is not the one this " + subject()
                    + " carries. ")
                + "The " + subject() + " has changed since it was read, or was never "
                + "read. Its current token is " + conflictToken
                + " — re-read, re-apply the change and retry",
            List.of(conflictToken));
    }
}
