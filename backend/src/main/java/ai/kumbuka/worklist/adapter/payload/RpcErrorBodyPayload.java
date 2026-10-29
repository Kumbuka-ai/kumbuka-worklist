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

/**
 * A JSON-RPC error: its code, its message, and the detail of an unknown tool.
 *
 * @param data the declared tool names, for an unknown tool (C4.3); absent for
 *             every other protocol error
 */
public record RpcErrorBodyPayload(
    @JsonProperty("code") int code,
    @JsonProperty("message") String message,
    @JsonProperty("data") @Nullable RpcErrorDataPayload data) {
}
