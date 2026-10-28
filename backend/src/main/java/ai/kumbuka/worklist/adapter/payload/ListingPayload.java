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

import java.util.List;

/**
 * A listing.
 *
 * <p>An object around the list rather than the bare array, so that anything a
 * listing later needs to say about itself is an added key rather than a
 * changed shape.
 *
 * <p>{@code next_cursor} is the way to the rest: passed back as the
 * {@code after} argument of the same query, it answers the objects after
 * the last one here. It is absent exactly when nothing follows -- the one
 * field that says whether more follows, so that no flag beside it can say
 * something else.
 *
 * @param nextCursor the cursor to the rest; absent exactly when nothing
 *                   follows, and then left out of the answer
 */
public record ListingPayload(
    @JsonProperty("objects") List<ObjectPayload> objects,
    @JsonProperty("next_cursor") @Nullable String nextCursor) {
}
