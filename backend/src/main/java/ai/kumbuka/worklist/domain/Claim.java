package ai.kumbuka.worklist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * One lease on one item, held by a receipt for a duration.
 *
 * <h2>One row per item, and why the key is not the item's</h2>
 *
 * The primary key is the item id and nothing beside it. That is the exclusivity
 * of a claim, held by the store rather than by a rule the domain has to keep: a
 * second live lease on one item is the state {@code claim} exists to prevent,
 * and a key that admitted it would leave the prevention to whoever writes the
 * verb.
 *
 * <p>The row is not deleted when the lease ends. This schema grants DELETE on
 * nothing, and the next claimant overwrites the row instead — which is why a
 * {@code claim} that finds an expired row through the surface reads as a fresh
 * lease rather than as a resurrection of what stood there before.
 *
 * <h2>Expiry is lazy, and the row does not describe itself</h2>
 *
 * A claim lapses at {@link #expiresAt}, and no writer moves at that moment
 * (ADR-0002). Every consumer of claim state derives the answer from the stored
 * expiry time against its own notion of now; a support query reading this table
 * directly sees a claim that is no longer in force unless it applies the same
 * comparison. The obligation belongs to this decision rather than to the
 * consumers who will be surprised by it.
 *
 * <p><strong>{@link #expiresAt} must be strictly greater than
 * {@link #grantedAt}.</strong> A non-positive duration is a lease that is inert
 * the moment it is granted, reported as a success — the predecessor's exact
 * defect, and the reason {@code ck_claim_duration} in V4 refuses it at the
 * table.
 *
 * <h2>The receipt is opaque and minted here</h2>
 *
 * A caller does not supply it. A caller-chosen holder would be an identity
 * assertion the service cannot check, and a caller that could name their own
 * receipt could name somebody else's — which is exactly the check
 * {@code release} runs against this column.
 *
 * <p>{@link #actor} is what the service derived from the write channel, beside
 * the receipt rather than instead of it: two different questions. The receipt
 * says who holds the lease; the actor says who wrote the row, for the audit
 * entry the platform reads back.
 *
 * <h2>The tenancy pair and no aggregate token</h2>
 *
 * {@link TenantScoped} carries the tenant and scope columns row-level security
 * filters on. There is no conflict token: a claim is not something two callers
 * read and edit against a moving version — the exclusivity is the read of the
 * row, and the writes are the transitions {@code claim} and {@code release}
 * carry themselves.
 */
@Entity
@Table(name = "claim", schema = "worklist")
public class Claim extends TenantScoped {

    /** One row per item. Not per (item, holder) — that would admit a second lease. */
    @Id
    @Column(name = "item_id", nullable = false)
    public UUID itemId;

    /**
     * Opaque, by contract. A caller reads it, presents it back with
     * {@code release}, and is refused if it is not the one the row carries.
     *
     * <p>A uuid is what it happens to be; nothing may read structure into it,
     * and a caller that parses it is a caller that breaks when the generator
     * changes.
     */
    @Column(name = "receipt", nullable = false)
    public String receipt;

    /**
     * The subject the service derived from the write channel, at the moment the
     * lease was granted.
     *
     * <p>Recorded so that the audit trail names an actor for the transition,
     * never accepted from a body. Authorship is server-derived on this scheme
     * without exception.
     */
    @Column(name = "actor", nullable = false)
    public String actor;

    /** When the lease started. Set at the insert and never moved. */
    @Generated(event = EventType.INSERT)
    @Column(name = "granted_at", nullable = false, insertable = false, updatable = false)
    public Instant grantedAt;

    /** When the lease ends, evaluated lazily against the reading side's notion of now. */
    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false)
    public Instant updatedAt;

    /**
     * Whether the lease still holds at the given instant.
     *
     * <p>{@code >=} rather than {@code >}: a lease that ends exactly now is over
     * — the boundary belongs to the next claimant, not to the holder walking
     * off the far side of it.
     */
    public boolean liveAt(Instant now) {
        return now.isBefore(expiresAt);
    }
}
