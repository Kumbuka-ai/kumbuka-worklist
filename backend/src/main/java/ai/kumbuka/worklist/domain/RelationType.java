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
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/**
 * One declared type of edge, and whether it blocks.
 *
 * <p>{@link #blocks} is the property of a type the platform reasons about,
 * and one type more carries a meaning of the platform: the type named
 * {@link #DUPLICATE_OF}, which closes the item that carries it (TAR-0002 B5,
 * REA-0011 D18). Everything else a type means belongs to the scope, and the
 * platform never asks.
 *
 * <p>That single boolean is what makes readiness answerable at all. The
 * predecessor's dependency column carries no type, so every machine reader
 * has to guess whether an edge blocks — and a reader that guesses "blocking"
 * resolves at least one real edge wrongly. The previous shape of this domain
 * deferred the type deliberately, on the ground that the moment types exist
 * something has to interpret them. This is that interpretation, and for
 * readiness it is exactly one column wide.
 *
 * <p><strong>Whether a type may be declared blocking after items already
 * carry it is an open point</strong>: flipping the property retroactively
 * changes the readiness of items nobody touched. The field is ordinary and
 * updatable here, and the answer belongs where the verb lives rather than in
 * a mapping.
 */
@Entity
@Table(name = "relation_type", schema = "worklist")
public class RelationType extends DeclaredValue {

    /**
     * The name under which the platform recognises the duplicate relation
     * (DEC-0037 C6, REA-0011 D18): setting it closes the source item into the
     * type's {@link #targetStatusPk}, as closed and not successful.
     */
    public static final String DUPLICATE_OF = "duplicate_of";

    /** Whether an edge of this type blocks. */
    @Column(name = "blocks", nullable = false)
    public boolean blocks;

    /**
     * The status {@code relate} closes the source item into, on the type
     * named {@link #DUPLICATE_OF} alone (the store refuses it on any other
     * name). Absent where the scope named none, and then the relation is
     * refused as closing is.
     */
    @Column(name = "target_status_pk")
    public @Nullable Long targetStatusPk;

    /** Whether this is the type the platform reads as the duplicate relation. */
    public boolean isDuplicateOf() {
        return DUPLICATE_OF.equals(name);
    }
}
