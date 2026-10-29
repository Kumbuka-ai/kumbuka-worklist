/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * One object as it goes out: its address, and what the domain projected.
 *
 * <p>The field map is passed through unaltered. An adapter that reshaped it
 * would be a second naming of the same values -- which is the defect the
 * canonical field catalogue was built to remove, reintroduced one layer out.
 * It is the one map an answer carries (C7.1); a key whose value is absent is
 * left out of it on the wire like any other member.
 *
 * <p>The conflict token stands beside the field map (C6.1) and, until the
 * breaking step, inside it as well: a caller that reads it from {@code fields}
 * today keeps reading it there. Both are the same value, read off the one
 * projection ({@code VerbSurface.Result#conflictToken}).
 *
 * @param conflictToken the object's token; absent for an answer about no
 *                      single object that carries one, and then left out
 */
public record ObjectPayload(
    @JsonProperty("address") String address,
    @JsonProperty("fields") Map<String, Object> fields,
    @JsonProperty("conflict_token") @Nullable String conflictToken) {
}
