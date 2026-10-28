/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.payload;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One piece of MCP content: text. */
public record TextContentPayload(
    @JsonProperty("type") String type,
    @JsonProperty("text") String text) {

    /** A piece of text content. */
    public static TextContentPayload text(String text) {
        return new TextContentPayload("text", text);
    }
}
