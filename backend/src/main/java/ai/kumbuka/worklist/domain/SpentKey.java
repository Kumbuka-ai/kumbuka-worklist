/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One idempotency key spent in a scope (C10.4).
 *
 * <p>The key, the call it was spent on, a digest of that call's arguments and
 * the object it produced, by view and identity. Nothing the caller wrote is
 * held, and no caller: a key is spent within its scope (V25 gives the reason).
 * The row speaks for its call for {@link #REMEMBERED_FOR}; after that a use of
 * the key writes over it.
 */
@Entity
@Table(name = "idempotency_key", schema = "worklist")
public class SpentKey extends TenantScoped {

    /** How long a spent key speaks for its call: twenty-four hours. */
    public static final Duration REMEMBERED_FOR = Duration.ofHours(24);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    public Long id;

    @Column(name = "idempotency_key", nullable = false)
    public String key;

    @Column(name = "call_name", nullable = false)
    public String callName;

    @Column(name = "argument_digest", nullable = false)
    public String argumentDigest;

    /** The view of what the call produced: {@code item}, {@code iteration} or {@code milestone}. */
    @Column(name = "produced_view", nullable = false)
    public String producedView;

    @Column(name = "produced_id", nullable = false)
    public UUID producedId;

    @Column(name = "first_seen_at", nullable = false)
    public Instant firstSeenAt;

    /** Whether this row still speaks for its key at {@code now}. */
    public boolean standsAt(Instant now) {
        return firstSeenAt.isAfter(now.minus(REMEMBERED_FOR));
    }

    /** Whether this row records the call the caller is making now. */
    public boolean records(String call, String digest) {
        return callName.equals(call) && argumentDigest.equals(digest);
    }
}
