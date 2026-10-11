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
import jakarta.persistence.EntityManager;

import java.util.List;
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
 * <p>At most {@code pageSize + 1} rows are read. The extra row is not answered;
 * it is what the domain reads to know that more follows, without a second
 * query.
 */
final class KeysetClause {

    private static final String AFTER_LEAD = "afterLead";
    private static final String AFTER_NUMBER = "afterNumber";

    private KeysetClause() {
    }

    /**
     * The page of the query so far: continued after the request's position,
     * ordered by its sort, bounded by its size.
     *
     * @param jpql   a query over {@code alias} whose WHERE clause is open for
     *               {@code AND}
     * @param params the parameters of the query so far
     */
    static <T> List<T> page(EntityManager em, StringBuilder jpql, Map<String, Object> params,
                            String alias, PageRequest request, Class<T> type) {
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
            jpql.append(" AND (").append(lead).append(beyond).append(':').append(AFTER_LEAD)
                .append(" OR (").append(lead).append(" = :").append(AFTER_LEAD)
                .append(" AND ").append(number).append(beyond).append(':').append(AFTER_NUMBER)
                .append("))");
            params.put(AFTER_LEAD, after.lead());
            params.put(AFTER_NUMBER, after.number());
        }

        jpql.append(" ORDER BY ");
        if (request.sort() != SortKey.ID) {
            jpql.append(lead).append(direction).append(", ");
        }
        jpql.append(number).append(direction);

        var query = em.createQuery(jpql.toString(), type);
        params.forEach(query::setParameter);
        return query.setMaxResults(request.pageSize() + 1).getResultList();
    }
}
