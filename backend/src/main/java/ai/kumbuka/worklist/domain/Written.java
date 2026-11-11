/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import org.jspecify.annotations.Nullable;

/**
 * The answer of a write, and what it wrote.
 *
 * <p>The domain logs nothing (DEC-0051, C4): the line a completed write
 * leaves (C13.3) is written by the surface that called it, and this is what
 * tells the surface whether there is one to write. The entity is the one the
 * write stored, and absent exactly where the call wrote nothing, so the
 * surface writes its line exactly then and never for a call that changed
 * nothing.
 *
 * <p>The entity's type is a parameter because this domain writes more than
 * one kind of row: the surface builds the technical address from the entity
 * it is handed, and it can only do that for a type it knows.
 *
 * @param answer what the verb answers, whether or not it wrote
 * @param stored the entity the write stored; absent where nothing was written
 */
public record Written<T, E>(T answer, @Nullable E stored) {

    /** The answer of a call that wrote nothing. */
    static <T, E> Written<T, E> unchanged(T answer) {
        return new Written<>(answer, null);
    }
}
