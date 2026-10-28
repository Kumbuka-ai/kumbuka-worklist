/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.payload;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One value of a closed set, with what it means: an entry of a schema's {@code oneOf}. */
public record SchemaValuePayload(
    @JsonProperty("const") String value,
    @JsonProperty("description") String description) {
}
