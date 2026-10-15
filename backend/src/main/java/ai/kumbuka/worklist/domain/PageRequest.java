/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A {@link QuerySpec} checked against one view of one scope: the filters the
 * view declares with their values in form, the size of one page, the sort, and
 * the position of the last object the previous page carried.
 *
 * <p>What it does not yet know is whether a well-formed filter value names
 * something the scope declared — a status name, a milestone number, a
 * workstream token. That needs the scope's vocabulary and is the view's
 * service's to answer.
 *
 * @param scopeId the scope the page is of; a cursor is bound to it
 * @param after   where the previous page ended, or null on the first page
 */
public record PageRequest(UUID scopeId, String view, Map<QueryFilter, Object> filters,
                          int pageSize, SortKey sort, SortKey.Direction direction,
                          PageCursor.Position after) {

    public PageRequest {
        filters = Map.copyOf(filters);
    }

    /** The value of one filter, when the caller named it. */
    public Optional<Object> value(QueryFilter filter) {
        return Optional.ofNullable(filters.get(filter));
    }
}
