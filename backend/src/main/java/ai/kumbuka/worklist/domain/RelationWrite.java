/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

/**
 * What a write of one edge stored: the source item, whose token it rotated,
 * and the target item the edge points at.
 *
 * <p>The domain logs nothing (DEC-0051, C4), so the surface writes the line
 * of the edge, and names both ends of it by their numbers.
 *
 * @param stored the source item, the aggregate the write stored
 * @param target the item the edge points at
 */
public record RelationWrite(Item stored, Item target) {
}
