/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.payload;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One call open to the caller, as an answer names it under {@code next}
 * (C6.1): the call, the verb in the names of the caller's surface followed by
 * the address it is made at, and a sentence of what it does.
 */
public record NextPayload(
    @JsonProperty("call") String call,
    @JsonProperty("does") String does) {
}
