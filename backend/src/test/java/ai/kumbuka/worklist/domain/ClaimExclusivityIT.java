package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.platform.PlatformFixture;
import ai.kumbuka.worklist.repository.ClaimRepository;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The two guarantees of the claim lease, each with the state it would be in if
 * the guarantee were absent, observed in the same run.
 *
 * <h2>The two halves this file exists to defend</h2>
 *
 * <p><strong>Exclusivity.</strong> One live lease per item at any moment. A
 * second claim on an item that already carries a live lease is refused as
 * {@code CLAIM_HELD}, and the row is not overwritten. Without the exclusivity
 * check, the second claim would go through and the second writer's receipt
 * would win silently — the exact race the row exists to prevent.
 *
 * <p><strong>Lazy expiry.</strong> A claim ends at its stored expiry and
 * nothing writes at that moment (ADR-0002). Once the lease has lapsed, a fresh
 * claim on the same item succeeds and overwrites the row in place. A red
 * probe that only observed exclusivity would pass against a service that
 * treated every second claim as CLAIM_HELD forever; the counter-probe here is
 * what makes that failure mode expressible.
 *
 * <h2>Every case carries the state its guarantee would leave behind</h2>
 *
 * The red half is asserted as a TRACE — the row afterwards must still be the
 * first claim's, receipt and actor and all — because that trace is what an
 * absent exclusivity check would replace with the second writer's values. A
 * failed second write and a swallowed one look identical from a caller's side;
 * the row's contents are what tells the two apart.
 *
 * <h2>The red state, and how it was observed</h2>
 *
 * The exclusivity check sits in {@link ClaimService#claim} between the
 * pessimistic lock and the write. Removing the {@code if (held != null &&
 * held.liveAt(now))} block makes {@link #a_second_claim_on_the_same_item_is_refused}
 * fail: the second claim goes through, the row afterwards carries the second
 * claim's receipt and actor, and no refusal is raised. Measured on 2026-09-05
 * against the current build.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ClaimExclusivityIT {

    /**
     * The scope of this probe, fresh per test — the same discipline as
     * {@link PlanningDomainIT}: a shared scope would make each case depend on
     * which ran before it, and a red failure would read as fixture drift
     * rather than as an absent guarantee.
     */
    private UUID scope;
    private UUID openStatus;

    @Inject ItemService items;
    @Inject ClaimService claims;
    @Inject ClaimRepository claimRows;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;

    @BeforeEach
    void aScopeOfItsOwn() {
        scope = UUID.randomUUID();
        selectors.declare(scope, Selector.ITEM);
        openStatus = vocabulary.declareStatus(scope, "open", 1,
            true, false, false, false).id;
    }

    // ==================================================================
    // Probe 1 — exclusivity, and the trace of its absence
    // ==================================================================

    @Test
    void a_second_claim_on_the_same_item_is_refused() {
        UUID item = createItem();
        Map<String, Object> first = claims.claim(scope, item, "first-holder",
            Duration.ofMinutes(30));
        String firstReceipt = String.valueOf(first.get(ClaimService.F_RECEIPT));

        WorklistException refusal = refusalFrom(() ->
            claims.claim(scope, item, "second-holder", Duration.ofMinutes(30)));

        assertThat(refusal.reason())
            .as("a second claim on an item that already carries a live lease is refused "
                + "as CLAIM_HELD, and refused rather than queued: the point of the lease "
                + "is that one holder acts at a time")
            .isEqualTo(WorklistException.Reason.CLAIM_HELD);
        assertThat(refusal.offenders()).contains(item.toString());

        // RED STATE, by its trace: with the exclusivity check removed the
        // second claim would go through, overwriting the row. The receipt in
        // the row afterwards would be the second writer's, and the first
        // caller's would name a lease nothing holds any more.
        Claim stored = claimRows.byItem(item);
        assertThat(stored).isNotNull();
        assertThat(stored.receipt)
            .as("RED STATE, observed by its absence: with the exclusivity check gone the "
                + "second claim would win and the row would carry its receipt — so the "
                + "first receipt would name a lease no row backs. The row still carrying "
                + "the first receipt IS the exclusivity, held by the store rather than by "
                + "a rule the domain has to keep")
            .isEqualTo(firstReceipt);
    }

    // ==================================================================
    // Probe 2 — the counter-probe: a lapsed lease admits a fresh claim
    // ==================================================================

    /**
     * The counter-probe: a claim that has lapsed does not stand forever.
     *
     * <p>Without it, {@link #a_second_claim_on_the_same_item_is_refused} would
     * pass against a service that refused every second claim on an item —
     * which would be exclusivity that never let go, and is exactly the failure
     * mode a check written one predicate too broadly produces.
     *
     * <p>Expiry is lazy (ADR-0002): nothing runs at the moment the lease
     * lapses. So the probe writes an expired row directly and asserts that a
     * fresh claim on the same item overwrites it — the next-claimant path, on
     * a row that is no longer in force.
     */
    @Test
    void a_claim_that_has_lapsed_admits_a_fresh_one() {
        UUID item = createItem();
        Map<String, Object> first = claims.claim(scope, item, "first-holder",
            Duration.ofMinutes(30));
        String firstReceipt = String.valueOf(first.get(ClaimService.F_RECEIPT));

        // Expire the row directly rather than sleeping: a real clock is a
        // fixture the probe would then depend on, and a duration long enough
        // to be robust would be a duration long enough to be slow.
        expireByRow(item);

        Map<String, Object> refresh = claims.claim(scope, item, "later-holder",
            Duration.ofMinutes(30));

        assertThat(String.valueOf(refresh.get(ClaimService.F_RECEIPT)))
            .as("a lapsed lease is over, and the next claimant writes the row in place. "
                + "The receipt of the fresh claim is not the receipt of the lapsed one — "
                + "the row was overwritten rather than resurrected")
            .isNotEqualTo(firstReceipt);

        Claim stored = claimRows.byItem(item);
        assertThat(stored.receipt).isEqualTo(refresh.get(ClaimService.F_RECEIPT));
        assertThat(stored.liveAt(Instant.now()))
            .as("and the fresh row is a live lease again — the counter-probe against "
                + "exclusivity that never let go")
            .isTrue();
    }

    // ==================================================================
    // Probe 3 — release
    // ==================================================================

    @Test
    void a_release_ends_a_lease_and_a_wrong_receipt_does_not() {
        UUID item = createItem();
        Map<String, Object> held = claims.claim(scope, item, "holder",
            Duration.ofMinutes(30));
        String receipt = String.valueOf(held.get(ClaimService.F_RECEIPT));

        WorklistException wrong = refusalFrom(() ->
            claims.release(scope, item, "holder", "not-the-receipt-that-was-minted"));
        assertThat(wrong.reason())
            .as("the check is on the value in the row, not on the actor. A receipt "
                + "nobody minted holds no lease, and a caller passing one is refused "
                + "rather than told which receipt WAS the real one — which would be a "
                + "way to fish for it")
            .isEqualTo(WorklistException.Reason.CLAIM_RECEIPT_UNKNOWN);

        // The row is still held under the same lease — a wrong receipt did
        // not clear it.
        Claim stillHeld = claimRows.byItem(item);
        assertThat(stillHeld.receipt).isEqualTo(receipt);
        assertThat(stillHeld.liveAt(Instant.now())).isTrue();

        // The right receipt does end it, and a fresh claim afterwards succeeds.
        claims.release(scope, item, "holder", receipt);
        Claim released = claimRows.byItem(item);
        assertThat(released.liveAt(Instant.now()))
            .as("release moves the expiry back to the granting instant. The row remains, "
                + "the store learns the lease is over, and the next claim overwrites it")
            .isFalse();

        Map<String, Object> reclaimed = claims.claim(scope, item, "successor",
            Duration.ofMinutes(30));
        assertThat(String.valueOf(reclaimed.get(ClaimService.F_RECEIPT)))
            .as("and the fresh claim after a legitimate release goes through — the same "
                + "counter-probe as after natural expiry, on a different transition")
            .isNotEqualTo(receipt);
    }

    // ==================================================================
    // Fixtures
    // ==================================================================

    private UUID createItem() {
        String openName = vocabulary.requireStatus(scope, openStatus).name;
        Map<String, Object> created = items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "an item to claim",
            Field.STATUS.canonicalName(), openName));
        return UUID.fromString(String.valueOf(created.get(Field.ID.canonicalName())));
    }

    /**
     * Move a claim's expiry back into the past by writing the row directly.
     *
     * <p>Expiry is lazy — nothing runs at the moment a lease lapses — so
     * observing a lapsed claim requires a stored expiry that is already
     * behind the reading clock. A sleeping probe would be observing the
     * clock rather than the guarantee.
     *
     * <p>Both {@code granted_at} and {@code expires_at} move together. The
     * V4 constraint {@code ck_claim_duration} keeps {@code expires_at}
     * strictly greater than {@code granted_at} — moving only the expiry
     * back would violate it, and refusing the row is the same guarantee
     * this probe is not the place to defeat. Written under the superuser
     * because {@code granted_at} is {@code updatable=false} on the entity
     * (V4: "Set at the insert and never moved"), so this write cannot
     * travel through Hibernate at all.
     */
    void expireByRow(UUID itemId) {
        assertThat(claimRows.byItem(itemId))
            .as("the row this probe is about to expire must already exist")
            .isNotNull();
        PlatformFixture.run(
            "UPDATE worklist.claim SET "
                + "granted_at = now() - interval '2 seconds', "
                + "expires_at = now() - interval '1 second' "
                + "WHERE item_id = '" + itemId + "'");
    }

    private static WorklistException refusalFrom(ThrowingRunnable call) {
        Throwable thrown = catchThrowable(() -> {
            try {
                call.run();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(thrown)
            .as("the call must be refused, and refused with this service's typed refusal "
                + "rather than with whatever the substrate raised")
            .isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    // Silence the "unused import" that comes and goes as the file evolves.
    @SuppressWarnings("unused")
    private static void keep(List<UUID> ignore) {
        // Only kept to hold the List<UUID> import in one place if the file
        // grows a case that needs it. Deliberately no body.
    }
}
