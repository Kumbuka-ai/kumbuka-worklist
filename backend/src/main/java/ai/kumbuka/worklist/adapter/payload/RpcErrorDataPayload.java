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
 * The detail of an unknown tool: the tool names this server declares (C4.3).
 * The name that arrived is the caller's and is not among it (DEC-0043 R19).
 */
public record RpcErrorDataPayload(@JsonProperty("accepts") List<String> accepts) {
}
