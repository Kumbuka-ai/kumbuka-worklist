/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The fields a {@code query} sorts by.
 *
 * <p>Every sort ends on the object's number, which is unique within its scope
 * and view, so every sort is total: two objects never compare equal and a
 * keyset continuation never skips or repeats one of a tie. {@link #ID} is the
 * number alone; every other key is the named field with the number after it.
 * Never the platform's identity: it orders nothing a caller can see.
 *
 * <p>Two sets are offered. {@link #STANDARD} is what every transport sorts by
 * and what the assistant surface offers at all. The full set adds the fields a
 * view declares beyond it — today {@link #RANK}, on the two planning axes
 * whose objects carry one — and is offered on the REST surface.
 */
public enum SortKey {

    /** The object's number. The default. */
    ID("id"),

    /** When the object was created, then its number. */
    CREATED_AT("created_at"),

    /**
     * The axis's own working order, then the number. Declared on iteration
     * and milestone only. A rank is changed by re-ranking, so a walk over
     * pages of this sort meets the order as it stands when each page is read.
     */
    RANK("rank");

    /** The sorts every transport offers. */
    public static final Set<SortKey> STANDARD = EnumSet.of(ID, CREATED_AT);

    /** Every sort, for the transport that offers declared fields too. */
    public static final Set<SortKey> ALL = EnumSet.allOf(SortKey.class);

    private final String wireName;

    SortKey(String wireName) {
        this.wireName = wireName;
    }

    /** The name a caller writes it under. */
    public String wireName() {
        return wireName;
    }

    /** Whether this view's objects carry the field. */
    public boolean declaredOn(String view) {
        return switch (this) {
            case ID, CREATED_AT -> true;
            case RANK -> Selector.ITERATION.equals(view) || Selector.MILESTONE.equals(view);
        };
    }

    /** The key a caller wrote, if it is one. */
    static Optional<SortKey> named(String wireName) {
        return Arrays.stream(values()).filter(k -> k.wireName.equals(wireName)).findFirst();
    }

    /** Which way a sort runs. */
    public enum Direction {
        ASC("asc"),
        DESC("desc");

        private final String wireName;

        Direction(String wireName) {
            this.wireName = wireName;
        }

        /** The name a caller writes it under. */
        public String wireName() {
            return wireName;
        }

        static Optional<Direction> named(String wireName) {
            return Arrays.stream(values()).filter(d -> d.wireName.equals(wireName)).findFirst();
        }
    }
}
