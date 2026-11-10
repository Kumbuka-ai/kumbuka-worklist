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
import java.util.Objects;
import java.util.UUID;

/**
 * One tag an item carries.
 *
 * <p>The tag is a token of the scope's vocabulary, and the vocabulary is the
 * platform's: this row holds the platform's identity of the tag and nothing
 * of what it says. Its token, its description and whether it is withdrawn
 * are read from {@code platform.tag_access} when they are needed, so a tag
 * the platform renames or withdraws reads that way here without this row
 * moving.
 *
 * <p><strong>A relation, not a field of the item.</strong> Attaching and
 * detaching write this table only; the item's {@code changed_at} and its
 * conflict token stay where they are.
 *
 * <p><strong>Removed, not withdrawn.</strong> Every other link of this schema
 * keeps its row and moves a status. An assignment has nothing to fall back to
 * and no address that has to keep resolving, so {@code tag_detach} deletes it;
 * V26 grants DELETE on this table and on no other.
 *
 * <p>{@code attachedBy} is who attached the tag. It is a fact about one act
 * and is never counted per subject.
 */
@Entity
@Table(name = "item_tag", schema = "worklist")
@IdClass(ItemTag.Key.class)
public class ItemTag extends ScopeKeyed {

    @Id
    @Column(name = "item_number", nullable = false)
    public Long itemNumber;

    /** The platform's identity of the tag; no key across the schema boundary. */
    @Id
    @Column(name = "tag_id", nullable = false)
    public UUID tagId;

    @Column(name = "attached_by", nullable = false, updatable = false)
    public String attachedBy;

    @Column(name = "attached_at", nullable = false, updatable = false)
    public Instant attachedAt;

    /** The key: the item, by its number in its scope, and the tag. */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        /*
         * Absent only in the instance the persistence provider builds through
         * the no-argument constructor before it sets the key from the row.
         */
        public @Nullable UUID scopeId;
        public @Nullable Long itemNumber;
        public @Nullable UUID tagId;

        public Key() {
            // Required by the persistence provider.
        }

        public Key(UUID scopeId, Long itemNumber, UUID tagId) {
            this.scopeId = scopeId;
            this.itemNumber = itemNumber;
            this.tagId = tagId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(scopeId, key.scopeId)
                && Objects.equals(itemNumber, key.itemNumber)
                && Objects.equals(tagId, key.tagId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(scopeId, itemNumber, tagId);
        }
    }
}
