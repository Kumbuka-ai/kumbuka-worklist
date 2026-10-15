/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

/**
 * A fixed notice an answer carries: a warning of a planning write, or a
 * finding of {@code validate}.
 *
 * <p>The domain names the notice and words nothing. The text is the surface's,
 * one per constant in the {@code notices} message bundle under the constant's
 * name in lower camel case, rendered where the answer leaves the service, so
 * that a notice is the platform's statement and repeats nothing a call sent or
 * a tenant declared (DEC-0043 R18).
 */
public enum Notice {

    /** The scope's planned iterations reached the warning threshold it set. */
    PLANNED_ITERATIONS_AT_THRESHOLD,

    /** An iteration's memberships reached the warning threshold the scope set. */
    MEMBERSHIPS_AT_THRESHOLD,

    /** A set of items forms a cycle over relation types that block. */
    BLOCKING_CYCLE
}
