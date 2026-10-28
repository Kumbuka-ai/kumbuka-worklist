/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.payload;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A JSON-RPC 2.0 response that carries a result. The member names are the
 * protocol's, not this service's: {@code jsonrpc}, {@code id}, {@code result}.
 */
public record RpcResultPayload(
    @JsonProperty("jsonrpc") String jsonrpc,
    @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("id") Object id,
    @JsonProperty("result") Object result) {
}
