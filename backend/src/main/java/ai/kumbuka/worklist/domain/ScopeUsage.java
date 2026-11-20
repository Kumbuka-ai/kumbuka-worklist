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
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One optional thing a scope declared it uses (DEC-0037 C5).
 *
 * <p>A scope uses the four predicates every scope answers without saying so.
 * What it uses beyond them it declares here, one row per {@link Property}:
 * the predicates {@code reviewed}, {@code approved} and {@code verified}, and
 * the properties {@code priority} and {@code severity}. No row means the scope
 * does not use it, and an item of that scope carries no value for it: the
 * answer names none, and a write that gives one a value is refused.
 *
 * <p>Rows rather than columns of the scope's settings, because the
 * declarations are additive rows (REA-0011 S3) and a scope need not have
 * settings at all. A row is never withdrawn: no verb takes a usage back.
 */
@Entity
@Table(name = "scope_usage", schema = "worklist")
@IdClass(ScopeUsage.Key.class)
public class ScopeUsage extends ScopeKeyed {

    /** What a scope may declare it uses, by the name the row and the answer carry. */
    public enum Property {
        REVIEWED("reviewed"),
        APPROVED("approved"),
        VERIFIED("verified"),
        PRIORITY("priority"),
        SEVERITY("severity");

        private final String token;

        Property(String token) {
            this.token = token;
        }

        /** The name the row stores and the answer carries. */
        public String token() {
            return token;
        }

        /** The property stored under that name, if one is. */
        public static Optional<Property> byToken(String token) {
            return Arrays.stream(values()).filter(p -> p.token.equals(token)).findFirst();
        }
    }

    /** The {@link Property#token() token} of what the scope uses. */
    @Id
    @Column(name = "name", nullable = false, updatable = false)
    public String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    /** The key: the scope and the name of what it uses. */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        /*
         * Absent only in the instance the persistence provider builds through
         * the no-argument constructor before it sets the key from the row.
         */
        public @Nullable UUID scopeId;
        public @Nullable String name;

        public Key() {
            // Required by the persistence provider.
        }

        public Key(UUID scopeId, String name) {
            this.scopeId = scopeId;
            this.name = name;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(scopeId, key.scopeId) && Objects.equals(name, key.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(scopeId, name);
        }
    }
}
