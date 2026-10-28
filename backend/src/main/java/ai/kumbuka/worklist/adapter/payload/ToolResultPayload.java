/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.payload;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The result of a tool call, as MCP shapes it: the answer as JSON text for
 * clients that read only text, the same answer as structured content, and
 * whether it is a refusal.
 */
public record ToolResultPayload(
    @JsonProperty("content") List<TextContentPayload> content,
    @JsonProperty("structuredContent") Object structuredContent,
    @JsonProperty("isError") boolean isError) {
}
