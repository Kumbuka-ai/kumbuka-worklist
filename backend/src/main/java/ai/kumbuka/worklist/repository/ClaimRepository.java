package ai.kumbuka.worklist.repository;

import ai.kumbuka.worklist.domain.Claim;
import ai.kumbuka.worklist.domain.Item;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Every statement issued against the claim table, plus one draw over items.
 *
 * <p>Separate from {@link ItemRepository} because the callers are separate: the
 * item domain owns the row that CARRIES a claim and knows nothing about the
 * lease that hangs off it. Putting the two behind one repository would drag
 * every claim's read into every item's read on the day somebody added a join
 * for one call.
 *
 * <p>Refusals stay above, as everywhere in this package. A missing row and an
 * expired lease are two different things a caller is told, and this layer does
 * not know which question was asked.
 */
@ApplicationScoped
@TenantBound
public class ClaimRepository {

    private static final String P_ITEM = "item";
    private static final String P_SCOPE = "scope";

    @Inject EntityManager em;

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * The claim on that item, or null.
     *
     * <p>Whether it is live is a decision the caller makes against
     * {@link Claim#liveAt(Instant)} — this repository does not filter by
     * expiry, because a caller asking whether the item is claimed and a caller
     * asking who last held it are two different callers, and both read the same
     * row.
     */
    @Transactional
    public Claim byItem(UUID itemId) {
        return itemId == null ? null : em.find(Claim.class, itemId);
    }

    /**
     * The claim on that item, locked for the caller's transaction, or null.
     *
     * <p>Taken by {@code claim} and {@code release} in one place, so that the
     * two calls that must not interleave cannot. Without it, two concurrent
     * claims would both read a row they consider expired and both write over it
     * — the exact race the row exists to prevent.
     *
     * <p>{@code PESSIMISTIC_WRITE} rather than optimistic retries because the
     * contended case here is two agents working the same scope, which is normal
     * rather than exceptional.
     */
    @Transactional
    public Claim lockByItem(UUID itemId) {
        return itemId == null ? null : em.find(Claim.class, itemId, LockModeType.PESSIMISTIC_WRITE);
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /** Inserts a claim and flushes so the table's constraints answer here. */
    @Transactional
    public Claim insert(Claim claim) {
        em.persist(claim);
        em.flush();
        return claim;
    }

    /** Flushes a change so the table's constraints answer here. */
    @Transactional
    public void flush() {
        em.flush();
    }

    /** Flushes, then re-reads the row — see {@link ItemRepository#flushAndRefresh}. */
    @Transactional
    public void flushAndRefresh(Claim claim) {
        em.flush();
        em.refresh(claim);
    }

    // ------------------------------------------------------------------
    // The draw
    // ------------------------------------------------------------------

    /**
     * The next unclaimed item in a scope, at the given moment, or null.
     *
     * <p>Unclaimed means: no claim row exists, or the row that exists has
     * lapsed at {@code now}. Both are one draw, and it is one query rather than
     * two: "load every item, filter in Java" walks the whole scope every time
     * and answers the same question worse the more the scope holds.
     *
     * <p>Ordered by creation and then by id, matching {@link ItemRepository#inScope}:
     * "next" means oldest first, and the tiebreak is deterministic so that two
     * concurrent draws are not answered by the ordering. The exclusivity is
     * held by the write below, not by the order.
     *
     * <p>A raw call-in — the {@code selector} half of the address is not yet
     * set — is not drawn: what {@code claim_next} activates is an item somebody
     * declared an address for. The exclusion happens in the JPQL and not in the
     * caller, so a caller cannot claim a raw row by asking twice.
     */
    @Transactional
    public Item nextClaimable(UUID scopeId, Instant now) {
        List<Item> candidates = em.createQuery(
                "SELECT i FROM Item i "
                    + "WHERE i.scopeId = :scope AND i.number IS NOT NULL "
                    + "AND NOT EXISTS ("
                    + "  SELECT 1 FROM Claim c "
                    + "  WHERE c.itemId = i.id AND c.expiresAt > :now"
                    + ") "
                    + "ORDER BY i.createdAt, i.id", Item.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter("now", now)
            .setMaxResults(1)
            .getResultList();
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * Whether any claimable item exists at all in a scope, at the given moment.
     *
     * <p>Used by refusals that want to say "nothing to claim" rather than "no
     * item at all here": a scope with items whose leases all stand is a
     * different state from an empty scope, and a caller is told which they are
     * in.
     */
    @Transactional
    public boolean hasAnyItems(UUID scopeId) {
        try {
            em.createQuery(
                    "SELECT i.id FROM Item i WHERE i.scopeId = :scope", UUID.class)
                .setParameter(P_SCOPE, scopeId)
                .setMaxResults(1)
                .getSingleResult();
            return true;
        } catch (NoResultException absent) {
            return false;
        }
    }
}
