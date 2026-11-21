/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The four phases of an item's lifecycle (TAR-0002 B20, REA-0011 D11): kept
 * by the platform in code and the same for every tenant.
 *
 * <p>An item holds its phase in a column of its own, {@code item.phase}, and
 * only the transitions write it: {@code create} makes a draft, {@code submit}
 * moves it to open, {@code start} to in progress, {@code return} back to
 * open, {@code close} to closed, and {@code discard} deletes a draft. Nothing
 * leaves {@link #CLOSED}. A status word changes within a phase through
 * {@code update}; a phase is never moved by a status write (REA-0011 D1).
 *
 * <p>Every declared status is bound to one phase ({@link ItemStatus#phase()}),
 * and no status is bound to {@link #DRAFT}: a draft carries a status of the
 * phase {@link #OPEN}, which it keeps when it is submitted.
 *
 * <p>The predicates {@code closed} and {@code in_progress} of an item are a
 * projection of its phase (TAR-0002 B3); the other predicates stay on the
 * status word.
 */
public enum Phase {

    /** Being written; everything is free, and nothing is booked or planned. */
    DRAFT("draft"),

    /** Submitted: the item stands in the list and may be planned and started. */
    OPEN("open"),

    /** Somebody works on it now. */
    IN_PROGRESS("in_progress"),

    /** Final: the item takes no write any more, and nothing reopens it. */
    CLOSED("closed");

    private final String token;

    Phase(String token) {
        this.token = token;
    }

    /** The tokens of the four phases, in lifecycle order. */
    public static List<String> tokens() {
        return Arrays.stream(values()).map(Phase::token).toList();
    }

    /** The phase as it travels: in a read answer, a filter and the column. */
    public String token() {
        return token;
    }

    /** The phase of this token, if it names one. */
    public static Optional<Phase> byToken(String token) {
        return Arrays.stream(values()).filter(phase -> phase.token.equals(token)).findFirst();
    }

    /**
     * The phase the status of an item in this phase belongs to: a draft
     * carries a status of the phase {@link #OPEN}, every other phase a status
     * of its own.
     */
    public Phase statusPhase() {
        return this == DRAFT ? OPEN : this;
    }
}
