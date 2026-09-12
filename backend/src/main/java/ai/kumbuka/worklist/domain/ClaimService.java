package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.repository.ClaimRepository;
import ai.kumbuka.worklist.repository.ItemRepository;
import ai.kumbuka.worklist.repository.ScopeAccessRepository;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The lease on an item.
 *
 * <h2>The verbs</h2>
 *
 * {@code claim} takes a lease on a named item. {@code release} gives it up.
 * {@code claim_next} draws the next unclaimed item in a scope and takes the
 * lease atomically. Three verbs of the platform vocabulary, spelled identically
 * to the sibling services — {@code activate}, {@code next_open_dispatch},
 * {@code revert} are the previous names, and the identity is the mechanism.
 *
 * <h2>Why the duration is a caller argument and not a service constant</h2>
 *
 * The concept says a claim is a lease for a duration and that a non-positive
 * duration is a typed rejection; it does not fix the duration itself. So the
 * caller says how long the lease is for, and this class refuses a non-positive
 * value with a message that says why. A service constant here would be a
 * platform decision wearing an implementation's clothes, and the two callers
 * this scheme is built for — an executing apparatus and an operator — want
 * different leases for the same reason.
 *
 * <h2>The receipt is opaque and minted here, never accepted from a body</h2>
 *
 * A caller-chosen holder is an identity assertion the service cannot check, and
 * a caller that could name their own receipt could name somebody else's — which
 * is exactly the check {@code release} runs against the stored value.
 *
 * <h2>Expiry is lazy and writes nothing</h2>
 *
 * A claim ends at its {@link Claim#expiresAt}, and this class does not schedule
 * anything to notice — the next claimant reads the row and overwrites it (ADR-0002).
 * The comparison happens at read time against {@code Instant.now()}, and every
 * consumer of claim state derives the answer from the stored expiry rather than
 * reading a status column that does not exist.
 *
 * <h2>Exclusivity is held by the store and observed by a probe</h2>
 *
 * The primary key of {@link Claim} is the item id, so a second live lease on
 * one item cannot be written by construction. A lease that has lapsed is
 * overwritten in place: the row remains, its receipt and its expiry move, and
 * the actor is recorded so that the audit trail names who took the row over.
 *
 * <p>The check runs under a {@link ClaimRepository#lockByItem pessimistic write
 * lock}, because "the row is expired" and "the row is being taken by somebody
 * else at this moment" have to give the same serialised answer — two callers
 * both reading an expired row and both writing over it is the exact race the
 * lock refuses.
 */
@ApplicationScoped
@TenantBound
public class ClaimService {

    /** An address, a scope id, a transition. Never a receipt, never the actor. */
    private static final Logger LOG = Logger.getLogger(ClaimService.class);

    @Inject ClaimRepository claims;
    @Inject ItemRepository items;
    @Inject ScopeAccessRepository scopeAccess;

    // ------------------------------------------------------------------
    // Writing — claim, release, claim_next
    // ------------------------------------------------------------------
    //
    // No READ verb of its own. The catalogue has one `read` verb, addressed
    // at an object, and it answers the item. A second `read` addressed at a
    // lease would be a verb the vocabulary does not carry, and asking "who
    // holds this?" is a question for the item's read once the projection
    // exposes it — which is a separate piece of work.

    /**
     * Take a lease on a named item.
     *
     * <p>The exclusivity check runs UNDER the pessimistic lock the repository
     * takes: reading first and then writing without the lock is what makes a
     * race — two callers each see an expired row, both write over it, and the
     * receipt of the later writer wins silently. The lock serialises the two,
     * and the second one sees the row the first wrote and refuses.
     *
     * <p>A raw call-in — the item has no address yet — is not claimable.
     * {@code claim_next} filters those out in its draw; the addressed
     * {@code claim} refuses them by name here, because addressing an item that
     * is not yet in an address space is not the mistake this verb is telling
     * the caller they made.
     */
    @Transactional
    public Map<String, Object> claim(UUID scopeId, UUID itemId, String actor,
            Duration duration) {
        Item item = requireItem(scopeId, itemId);
        requireAddressed(item);
        String subject = requireActor(actor);
        Duration lease = requirePositive(duration);

        Instant now = Instant.now();
        Claim held = claims.lockByItem(itemId);
        if (held != null && held.liveAt(now)) {
            throw new WorklistException(
                WorklistException.Reason.CLAIM_HELD,
                "a live lease stands on item " + itemId + " in scope " + scopeId + " and "
                    + "expires at " + held.expiresAt + ". A second claimant is refused "
                    + "rather than queued: the point of the lease is that one holder acts "
                    + "at a time, and a lease that ends is a lease that is over — a "
                    + "waiter would be a promise to hand the item over without a call, "
                    + "which the platform does not carry",
                List.of(String.valueOf(itemId)));
        }

        Claim claim = held == null ? new Claim() : held;
        claim.itemId = itemId;
        claim.scopeId = item.scopeId;
        claim.receipt = UUID.randomUUID().toString();
        claim.actor = subject;
        claim.expiresAt = now.plus(lease);
        if (held == null) {
            claims.insert(claim);
        } else {
            claims.flush();
        }
        claims.flushAndRefresh(claim);

        LOG.infof("claim taken on item %s in scope %s until %s",
            itemId, scopeId, claim.expiresAt);
        return project(item, claim, subject);
    }

    /**
     * Give up a lease, by presenting the receipt that was minted for it.
     *
     * <p>The receipt is the whole of the check: an actor beside it would say
     * that the caller who released must be the caller who claimed, and that is
     * a different rule from the one the concept fixes. A holder that hands the
     * receipt to another agent has passed the lease, and the release from the
     * second agent is legitimate.
     *
     * <p>The row is not deleted — this schema grants DELETE on nothing.
     * Expiry is moved back to the granting instant, so the next {@code claim}
     * on the row sees a lapsed lease and overwrites it. That is the same
     * mechanism a natural expiry uses; the release is the caller admitting the
     * lease is over sooner than it would have been.
     */
    @Transactional
    public Map<String, Object> release(UUID scopeId, UUID itemId, String subject,
            String receipt) {
        Item item = requireItem(scopeId, itemId);
        Claim claim = claims.lockByItem(itemId);
        if (claim == null || !claim.liveAt(Instant.now())) {
            throw new WorklistException(
                WorklistException.Reason.CLAIM_ABSENT,
                "no live lease stands on item " + itemId + " in scope " + scopeId + " to "
                    + "release. A lease that lapsed on its own is over already, and a "
                    + "release verb over it would be a write that reports what the store "
                    + "already knows",
                List.of(String.valueOf(itemId)));
        }

        String presented = requireReceipt(receipt);
        if (!claim.receipt.equals(presented)) {
            throw new WorklistException(
                WorklistException.Reason.CLAIM_RECEIPT_UNKNOWN,
                "the receipt given does not name the lease this item holds. A receipt "
                    + "is minted per claim and is opaque; the check is on the value in "
                    + "the row, not on the actor, so passing the receipt to another agent "
                    + "hands the lease over — but a receipt nobody minted holds nothing",
                List.of(String.valueOf(itemId)));
        }

        // Move the expiry to the smallest instant strictly greater than the
        // grant — the same lapsed state a natural expiry produces, expressed
        // in a way ck_claim_duration accepts. The check refuses expires_at
        // <= granted_at at the table (a non-positive-duration insert is the
        // predecessor's defect this row was rebuilt to refuse), so a release
        // that assigned granted_at to expires_at directly would be a write
        // caught by the same constraint the whole grant path travels. One
        // microsecond is the smallest step the substrate carries — postgres
        // timestamptz is microsecond-precision — so this is the least the
        // row can move and still say "the lease ended". Nothing is deleted;
        // the next claim overwrites the row in place, in keeping with the
        // whole of this schema's rule about what happens to a row that is
        // no longer asserted.
        claim.expiresAt = claim.grantedAt.plusNanos(1_000);
        claims.flushAndRefresh(claim);

        LOG.infof("claim released on item %s in scope %s", itemId, scopeId);
        return project(item, claim, subject);
    }

    /**
     * Draw the next unclaimed item in a scope and take a lease on it, atomically.
     *
     * <p>The draw and the claim are one transaction: two agents pulling at once
     * both read the same next row, and only the one that reaches the pessimistic
     * lock first writes — the other retries and picks up whatever is next.
     * Splitting the two into "which is next" and "claim that one" would be the
     * race the sibling service's earlier design carried, where the two calls
     * were separate and the second could learn the first had lost.
     *
     * <p>A scope with no items and a scope whose items are all leased are two
     * different refusals a caller is told: the first is a call to add work, the
     * second is a call to wait. Collapsing them would make a caller fall back
     * silently to the wrong remedy — the same reasoning as the
     * ITERATION_ABSENT / plan-vs-close split.
     */
    @Transactional
    public Map<String, Object> claimNext(UUID scopeId, String actor, Duration duration) {
        String subject = requireActor(actor);
        Duration lease = requirePositive(duration);

        Instant now = Instant.now();
        Item item = claims.nextClaimable(scopeId, now);
        if (item == null) {
            // Read `hasAnyItems` once: the answer is stable inside this
            // transaction, and calling it twice is a second query where a
            // local variable is the whole of it.
            boolean anyItems = claims.hasAnyItems(scopeId);
            throw new WorklistException(
                anyItems
                    ? WorklistException.Reason.DRAW_EMPTY
                    : WorklistException.Reason.ITEM_UNKNOWN,
                anyItems
                    ? "every addressable item in scope " + scopeId + " is currently held. "
                        + "That is a call to wait for a lease to lapse or for a holder to "
                        + "release, and it is deliberately a different answer from an empty "
                        + "scope — which is a call to add work"
                    : "scope " + scopeId + " holds no addressable item. Nothing to draw, and "
                        + "a call to add an item first",
                List.of(String.valueOf(scopeId)));
        }

        Claim held = claims.lockByItem(item.id);
        if (held != null && held.liveAt(Instant.now())) {
            // A race we lost between reading and locking: another agent has
            // taken this exact row in the meantime. Reported as a routine
            // refusal so the caller retries — which will read the NEXT
            // unclaimed row, because this one now stands claimed.
            throw new WorklistException(
                WorklistException.Reason.CLAIM_HELD,
                "the item drawn was taken by another caller between the draw and the "
                    + "lock. Retry: the next draw sees a scope in which this item is "
                    + "held, and picks the one after it",
                List.of(String.valueOf(item.id)));
        }

        Claim claim = held == null ? new Claim() : held;
        claim.itemId = item.id;
        claim.scopeId = item.scopeId;
        claim.receipt = UUID.randomUUID().toString();
        claim.actor = subject;
        claim.expiresAt = Instant.now().plus(lease);
        if (held == null) {
            claims.insert(claim);
        } else {
            claims.flush();
        }
        claims.flushAndRefresh(claim);

        LOG.infof("claim drawn and taken on item %s in scope %s until %s",
            item.id, scopeId, claim.expiresAt);
        return project(item, claim, subject);
    }

    // ------------------------------------------------------------------
    // The mechanisms
    // ------------------------------------------------------------------

    /**
     * The projection: item address plus the lease's own fields.
     *
     * <p>Addresses and receipts, and never a title or a status — the operator
     * boundary is a missing GRANT and a log line carrying content walks around
     * it by another road. The item id and its number are part of the answer
     * because the answer IS about that item; the title is not.
     *
     * <p>The lease's fields are plain string keys and not entries in
     * {@link Field}. That enum resolves a caller's INPUT map: nothing here takes
     * a receipt or an expiry through {@code Field.resolve}, and a Field entry
     * with no input path would be a canonical name whose settability check
     * nothing consults.
     */
    private Map<String, Object> project(Item item, Claim claim, String callerSubject) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put(Field.ID.canonicalName(), item.id);
        answer.put(Field.SCOPE.canonicalName(), slugOf(item.scopeId));
        answer.put(Field.NUMBER.canonicalName(), item.number);
        answer.put(F_RECEIPT, claim.receipt);
        answer.put(F_ACTOR, holderStateOf(claim, callerSubject));
        answer.put(F_GRANTED_AT, claim.grantedAt);
        answer.put(F_EXPIRES_AT, claim.expiresAt);
        return answer;
    }

    /**
     * The holder state a caller reads out of {@code actor}. Three states —
     * {@code nobody}/{@code self}/{@code other} — the platform's standing
     * refusal to name a person behind a claim. A lapsed lease reads as
     * {@code nobody}, matching the derived meaning of "not held".
     */
    private static HolderState holderStateOf(Claim claim, String callerSubject) {
        if (claim == null || !claim.liveAt(Instant.now())) {
            return HolderState.NOBODY;
        }
        return HolderState.of(claim.actor, callerSubject);
    }

    /** The slug of a scope, or the scope's id when the access row is absent. */
    private String slugOf(UUID scopeId) {
        try {
            return scopeAccess.findByScopeId(scopeId)
                .map(ScopeAccessRepository.ScopeAccessRow::slug)
                .orElse(String.valueOf(scopeId));
        } catch (RuntimeException notReadable) {
            return String.valueOf(scopeId);
        }
    }

    /** The lease's own projection keys. See {@link #project} on why they are here. */
    public static final String F_RECEIPT = "receipt";
    public static final String F_ACTOR = "actor";
    public static final String F_GRANTED_AT = "granted_at";
    public static final String F_EXPIRES_AT = "expires_at";
    public static final String F_DURATION_SECONDS = "duration_seconds";

    private Item requireItem(UUID scopeId, UUID itemId) {
        Item item = items.byId(itemId);
        if (item == null || !item.scopeId.equals(scopeId)) {
            throw new WorklistException(
                WorklistException.Reason.ITEM_UNKNOWN,
                "no item " + itemId + " in scope " + scopeId,
                List.of(String.valueOf(itemId)));
        }
        return item;
    }

    private static void requireAddressed(Item item) {
        if (item.number == null || item.selectorId == null) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "item " + item.id + " has no address yet — it is a raw call-in. A claim "
                    + "is a lease on an addressed item; something without an address in "
                    + "its own scope has no place to be worked and cannot be leased",
                List.of(String.valueOf(item.id)));
        }
    }

    private static Duration requirePositive(Duration presented) {
        if (presented == null || presented.isZero() || presented.isNegative()) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "the duration of a lease is a positive amount of time. A zero duration "
                    + "is a lease that is inert the moment it is granted; a negative one "
                    + "is not a duration at all. The predecessor's exact defect",
                List.of("duration"));
        }
        return presented;
    }

    private static String requireReceipt(String presented) {
        if (presented == null || presented.isBlank()) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "release names the lease it ends by the receipt that claim minted for it. "
                    + "No receipt arrived",
                List.of(F_RECEIPT));
        }
        return presented;
    }

    /**
     * The actor a claim records, refused where it did not arrive.
     *
     * <p>Passed in by the surface rather than injected here, so that the
     * domain does not import the adapter's caller class — the layer model
     * says the domain does not read the surface, and the injection would be
     * that read wearing a CDI type's clothes. The surface derives the actor
     * from the token and hands it to the domain; the domain checks it is not
     * empty and stores it.
     */
    private static String requireActor(String presented) {
        if (presented == null || presented.isBlank()) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "a claim records the actor the service derived from the write channel, "
                    + "and this call arrived without one. Authorship is server-derived on "
                    + "this scheme without exception",
                List.of(F_ACTOR));
        }
        return presented;
    }
}
