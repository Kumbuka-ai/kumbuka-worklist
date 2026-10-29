/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.repository.SpentKeyRepository;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/**
 * The idempotency of a call that creates (C10.4).
 *
 * <p>A call under a key asks {@link #firstAnswer} before it does anything, and
 * {@link #remember}s what it produced after. A repeat with the same call and
 * the same arguments is answered with the object the first call produced,
 * while that object stands; a repeat that cannot be answered so -- another
 * call, other arguments, or the object is gone -- is refused with
 * {@code IDEMPOTENCY_KEY_REUSED}. In no case is the call carried out a second
 * time: an object that is gone is not made again (decision 204.29 on O-10).
 *
 * <p>The arguments arrive as a digest the surface computed from the call, so
 * that this class knows nothing of how a call is spelled.
 */
@ApplicationScoped
@TenantBound
public class IdempotencyService {

    @Inject SpentKeyRepository keys;
    @Inject Clock clock;

    /**
     * The number of the object a repeat of this call answers with, where the key was spent
     * on it within the last 24 hours; empty where the key is new here or its
     * row has expired.
     *
     * <p>Takes the key's lock first and holds it to the end of the caller's
     * transaction, so that two overlapping calls under one key are decided one
     * after the other.
     *
     * @throws WorklistException {@code IDEMPOTENCY_KEY_REUSED} where the key
     *                           was spent on another call or other arguments,
     *                           or the object it produced no longer stands
     */
    @Transactional
    public Optional<Long> firstAnswer(UUID scopeId, String key, String call, String digest) {
        keys.lockKeyUse(scopeId, key);
        Optional<SpentKey> held = keys.byKey(scopeId, key).filter(row -> row.standsAt(now()));
        if (held.isEmpty()) {
            return Optional.empty();
        }
        SpentKey spent = held.get();
        if (!spent.records(call, digest)
                || !keys.stands(scopeId, spent.producedView, spent.producedNumber)) {
            throw new WorklistException(WorklistException.Reason.IDEMPOTENCY_KEY_REUSED);
        }
        return Optional.of(spent.producedNumber);
    }

    /**
     * Spends the key on this call: what it produced, in which view. An expired
     * row of the same key is written over.
     */
    @Transactional
    public void remember(UUID scopeId, String key, Spent call) {
        Optional<SpentKey> held = keys.byKey(scopeId, key);
        SpentKey row = held.orElseGet(SpentKey::new);
        row.scopeId = scopeId;
        row.key = key;
        row.callName = call.name();
        row.argumentDigest = call.digest();
        row.producedView = call.view();
        row.producedNumber = call.produced();
        row.firstSeenAt = now();
        if (held.isPresent()) {
            keys.flush();
        } else {
            keys.insert(row);
        }
    }

    /**
     * One call a key was spent on.
     *
     * @param name     the call, as the declaration names it
     * @param digest   the digest of its arguments
     * @param view     the view of what it produced
     * @param produced the number of what it produced, its address in the view
     */
    public record Spent(String name, String digest, String view, long produced) {
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
