/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.payload;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * What a refusal's caller acts on. Each member means one thing on every
 * service (DEC-0042); a member with no value is absent.
 *
 * @param attempted the call as the caller's surface names it (C6.2); absent
 *                  where the refusal fell before the request became a call
 * @param address absent except for a stale token: the object's complete address
 * @param fields absent except for a stale token: the object's fields as they stand
 * @param offenders absent where the refusal concerns no declared argument
 * @param accepts absent except where a call lists what it accepts
 * @param conflictToken absent except for a stale token
 * @param service absent except for a stale token: the service that holds the object
 * @param reference absent except for an unexpected failure
 */
public record RefusalDataPayload(
    @JsonProperty("attempted") @Nullable String attempted,
    @JsonProperty("address") @Nullable String address,
    @JsonProperty("fields") @Nullable Map<String, Object> fields,
    @JsonProperty("offenders") @Nullable List<String> offenders,
    @JsonProperty("accepts") @Nullable List<String> accepts,
    @JsonProperty("conflict_token") @Nullable String conflictToken,
    @JsonProperty("service") @Nullable String service,
    @JsonProperty("reference") @Nullable String reference) {

    /** Whether there is nothing to act on, so that the refusal carries no {@code data}. */
    @JsonIgnore
    boolean isEmpty() {
        return attempted == null && address == null && fields == null && offenders == null
            && accepts == null && conflictToken == null && service == null && reference == null;
    }
}
