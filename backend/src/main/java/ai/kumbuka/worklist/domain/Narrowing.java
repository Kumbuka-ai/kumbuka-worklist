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

/**
 * A {@link QuerySpec} checked against one view: the filters it declares with
 * their values in form, the bound of one answer, and the number of the object
 * the previous answer ended on.
 *
 * <p>What it does not yet know is whether a well-formed value names something
 * the scope declared — a status name, a milestone number, a workstream token.
 * That needs the scope's vocabulary and is the view's service's to answer.
 *
 * @param after the number of the last object of the previous page, or null on
 *              the first
 */
public record Narrowing(String view, Map<QueryFilter, Object> filters, int limit, Long after) {

    public Narrowing {
        filters = Map.copyOf(filters);
    }

    /** The value of one filter, when the caller named it. */
    public Optional<Object> value(QueryFilter filter) {
        return Optional.ofNullable(filters.get(filter));
    }
}
