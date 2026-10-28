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
import org.jspecify.annotations.Nullable;

/**
 * A JSON-RPC 2.0 response that carries an error. The id stands even where it
 * is null: the protocol answers an envelope it could not read with a null id.
 *
 * @param id absent where the request carried none; written as null, as JSON-RPC asks
 */
public record RpcErrorPayload(
    @JsonProperty("jsonrpc") String jsonrpc,
    @JsonInclude(JsonInclude.Include.ALWAYS) @JsonProperty("id") @Nullable Object id,
    @JsonProperty("error") RpcErrorBodyPayload error) {
}
