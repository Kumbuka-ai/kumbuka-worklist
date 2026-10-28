/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * One page of a {@code query}: at most the page size of objects, and the way
 * to the rest when there is any.
 *
 * <p>{@code next} is the cursor that continues after the last object here, and
 * absent exactly when nothing follows. A hidden ceiling — an answer that reads
 * complete and is not — is the sprint-169 defect one layer up; this shape
 * cannot express one.
 */
public record Page(List<Map<String, Object>> objects, @Nullable String next) {

    /**
     * The page from at most {@code pageSize + 1} rows the store returned in the
     * request's order. The extra row is not answered; it says only that more
     * follows, without a second query.
     *
     * @param positionOf where an object stands in the request's sort
     */
    static <T> Page of(PageRequest request, List<T> fetched,
                       Function<T, PageCursor.Position> positionOf,
                       Function<T, Map<String, Object>> project) {
        boolean more = fetched.size() > request.pageSize();
        List<T> rows = more ? fetched.subList(0, request.pageSize()) : fetched;
        String next = more ? PageCursor.encode(request, positionOf.apply(rows.getLast())) : null;
        return new Page(rows.stream().map(project).toList(), next);
    }
}
