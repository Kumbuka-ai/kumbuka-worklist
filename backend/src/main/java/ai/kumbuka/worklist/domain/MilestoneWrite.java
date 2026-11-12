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
 * What a milestone write stored: the milestone, and the one it demoted on the
 * way.
 *
 * <p>Activating a milestone demotes the active one in the same write. The
 * domain logs nothing (DEC-0051, C4), so the surface writes the line of the
 * demotion, and this tells it whether there is one to write.
 *
 * @param stored  the milestone the write stored
 * @param demoted the milestone the write moved from active to planned; absent
 *                where the write demoted none
 */
public record MilestoneWrite(Milestone stored, @Nullable Milestone demoted) {
}
