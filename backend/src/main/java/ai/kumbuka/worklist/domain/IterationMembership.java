package ai.kumbuka.worklist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One item's membership of one iteration.
 *
 * <h2>Membership is an entity, not a column on the item</h2>
 *
 * That is what makes {@code planned} a QUERY rather than a status value, and
 * the orphan class — an item reading planned with no membership —
 * structurally impossible rather than merely forbidden. It was observed twice
 * in the predecessor, whose pair of columns exists only because a markdown
 * table cannot join.
 *
 * <h2>It is not an aggregate root, and it owns no conflict token</h2>
 *
 * A membership is ADDRESSED at its own address — the verb catalogue's mapping
 * table says {@code update} carries membership status addressed at the
 * membership — and it nevertheless presents the token of its
 * {@link Iteration}. Addressing and token ownership are two different things,
 * and this is the row where they come apart.
 *
 * <p>A token of its own would invert the ratification: the aggregate would be
 * the row again, and a reorder of twelve memberships would have to present
 * twelve tokens instead of the iteration's one.
 *
 * <h2>Its status is not the item's status, and it is not spelled the same</h2>
 *
 * {@code done} on a membership means completed IN THIS ITERATION; done on an
 * item means finished. The two carry different field names on the wire —
 * {@code membership_status} against {@code status} — and different
 * vocabularies, so that the two cannot be confused in a call.
 *
 * <p>The four states here are a small fixed set rather than a declared
 * vocabulary, and that asymmetry is deliberate: they are the platform's own
 * planning mechanism, not a scope's way of describing its work.
 *
 * <h2>Position is dense and rewritten as a whole on reorder</h2>
 *
 * A membership is addressed by its item and never by its position, so a
 * reorder moves nothing a caller is holding.
 */
@Entity
@Table(name = "iteration_membership", schema = "worklist")
@IdClass(IterationMembership.Key.class)
public class IterationMembership extends TenantScoped {

    /** In this iteration and not yet started. */
    public static final String TODO = "todo";
    /** Being worked. At most one per iteration, held by a partial unique index. */
    public static final String ACTIVE = "active";
    /** Completed in this iteration. Terminal. */
    public static final String DONE = "done";
    /** Taken out of this iteration's work without being completed. Terminal. */
    public static final String DROPPED = "dropped";

    /** Every value {@link #status} admits, in the order V4's check constraint lists them. */
    public static final List<String> STATUSES = List.of(TODO, ACTIVE, DONE, DROPPED);

    /**
     * The two that end this membership's part in the iteration.
     *
     * <p>What terminal means here is "this iteration is done with the item",
     * which is a different assertion from the item being finished — the item
     * may well be planned into the next iteration.
     */
    public static final Set<String> TERMINAL = Set.of(DONE, DROPPED);

    /** The iteration. Half of the identity, and the owner of the conflict token. */
    @Id
    @Column(name = "iteration_id", nullable = false)
    public UUID iterationId;

    /** The item. The other half, and the address a caller holds. */
    @Id
    @Column(name = "item_id", nullable = false)
    public UUID itemId;

    /** Dense within the iteration, rewritten as a whole on reorder. */
    @Column(name = "position", nullable = false)
    public int position;

    /** One of the four states above, and never one of the item's. */
    @Column(name = "status", nullable = false)
    public String status = TODO;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false)
    public Instant updatedAt;

    /** Whether this iteration is still expected to do something with the item. */
    public boolean live() {
        return !TERMINAL.contains(status);
    }

    /**
     * The composite key, without the tenancy axis.
     *
     * <p>The table's primary key includes {@code tenant_id}, and this class
     * deliberately does not. The tenancy column is the aspect's: it is set by
     * the tenant resolver and appended to every lookup by Hibernate, so
     * naming it in the Java key would put it into signatures no caller
     * supplies it through. Within one tenant the pair below is unique, which
     * is the property a key needs.
     */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        public UUID iterationId;
        public UUID itemId;

        public Key() {
        }

        public Key(UUID iterationId, UUID itemId) {
            this.iterationId = iterationId;
            this.itemId = itemId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                && Objects.equals(iterationId, key.iterationId)
                && Objects.equals(itemId, key.itemId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(iterationId, itemId);
        }
    }
}
