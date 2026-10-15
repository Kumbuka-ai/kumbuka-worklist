/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.repository;

import ai.kumbuka.worklist.domain.PageCursor;
import ai.kumbuka.worklist.domain.PageRequest;
import ai.kumbuka.worklist.domain.SortKey;

import java.util.Map;

/**
 * The continuation, the order and the bound of one page, appended to a JPQL
 * query that has already narrowed by scope and filter.
 *
 * <p>One construction for the four views, because the four differ only in
 * what they filter on: each sorts by the same keys over columns of the same
 * names, and each ends on its {@code number}, unique within its scope, so the
 * order is total. The continuation is a keyset on exactly that order — strictly
 * after the position the cursor carries — and never an offset, so an object
 * inserted ahead of the position shifts nothing that was already answered.
 *
 * <p>At most {@link #bound} rows are read, {@code pageSize + 1}. The extra row
 * is not answered; it is what the domain reads to know that more follows,
 * without a second query. The query itself is created by the repository that
 * built it, so that its text never leaves the method it is written in.
 */
final class KeysetClause {

    private static final String AFTER_LEAD = "afterLead";
    private static final String AFTER_NUMBER = "afterNumber";

    private KeysetClause() {
    }

    /**
     * Continue the query so far after the request's position and order it by
     * the request's sort. Everything appended is this class's own text; every
     * value travels as a parameter.
     *
     * @param jpql   a query over {@code alias} whose WHERE clause is open for
     *               {@code AND}
     * @param params the parameters of the query so far
     */
    static void continueAndOrder(StringBuilder jpql, Map<String, Object> params, String alias,
                                 PageRequest request) {
        String number = alias + ".number";
        String lead = switch (request.sort()) {
            case ID -> number;
            case CREATED_AT -> alias + ".createdAt";
            case RANK -> alias + ".rank";
        };
        boolean ascending = request.direction() == SortKey.Direction.ASC;
        String beyond = ascending ? " > " : " < ";
        String direction = ascending ? " ASC" : " DESC";

        PageCursor.Position after = request.after();
        if (after != null && request.sort() == SortKey.ID) {
            jpql.append(" AND ").append(number).append(beyond).append(':').append(AFTER_NUMBER);
            params.put(AFTER_NUMBER, after.number());
        } else if (after != null) {
            // A row comparison and not "lead > a OR (lead = a AND number > n)":
            // the two say the same, and only the first is an index condition.
            // The second walks the index from the start of the scope and
            // removes every row before the position by filter.
            jpql.append(" AND (").append(lead).append(", ").append(number).append(')')
                .append(beyond).append("(:").append(AFTER_LEAD).append(", :")
                .append(AFTER_NUMBER).append(')');
            params.put(AFTER_LEAD, after.lead());
            params.put(AFTER_NUMBER, after.number());
        }

        jpql.append(" ORDER BY ");
        if (request.sort() != SortKey.ID) {
            jpql.append(lead).append(direction).append(", ");
        }
        jpql.append(number).append(direction);
    }

    /** The bound of one page: its size and the one row that says more follows. */
    static int bound(PageRequest request) {
        return request.pageSize() + 1;
    }
}
