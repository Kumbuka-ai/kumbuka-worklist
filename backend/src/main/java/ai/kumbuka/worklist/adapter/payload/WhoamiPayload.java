/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.payload;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Who the caller is, and which tenant the request was bound to. */
public record WhoamiPayload(
    @JsonProperty("subject") String subject,
    @JsonProperty("tenant") String tenant) {
}
