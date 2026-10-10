/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * What a narrowed {@code query} answers with: at most the limit of objects, and
 * the way to the rest when there is any.
 *
 * <p>{@code next} is the cursor that continues after the last object here, and
 * null exactly when nothing follows. A hidden ceiling — an answer that reads
 * complete and is not — is the sprint-169 defect one layer up; this shape cannot
 * express one.
 */
public record QueryAnswer(List<Map<String, Object>> objects, String next) {

    /** Whether the store carried more than this answer holds. */
    public boolean truncated() {
        return next != null;
    }

    /**
     * One page of a view read whole and narrowed in memory.
     *
     * <p>For the three axes, whose whole set is small by construction: a scope
     * has few iterations, milestones and workstreams. The page starts after the
     * object the cursor names in the view's own order, filtered or not — so a
     * walk continues correctly even when that object no longer passes the filter
     * — and admits only what the filter admits.
     *
     * @param ordered every object of the view, in its order
     */
    static <T> QueryAnswer page(Narrowing narrowing, List<T> ordered, Predicate<T> admits,
                                ToLongFunction<T> numberOf,
                                Function<T, Map<String, Object>> project) {
        int start = 0;
        if (narrowing.after() != null) {
            long after = narrowing.after();
            int at = indexOf(ordered, numberOf, after);
            if (at < 0) {
                throw PageCursor.unplaced(narrowing.view(), after);
            }
            start = at + 1;
        }
        List<T> admitted = ordered.subList(start, ordered.size()).stream()
            .filter(admits)
            .limit(narrowing.limit() + 1L)
            .toList();
        return bounded(narrowing, admitted, numberOf, project);
    }

    /**
     * One page from at most {@code limit + 1} admitted objects: the extra one is
     * not answered and says only that more follows.
     */
    static <T> QueryAnswer bounded(Narrowing narrowing, List<T> admitted,
                                   ToLongFunction<T> numberOf,
                                   Function<T, Map<String, Object>> project) {
        boolean more = admitted.size() > narrowing.limit();
        List<T> rows = more ? admitted.subList(0, narrowing.limit()) : admitted;
        String next = more
            ? PageCursor.encode(narrowing.view(), numberOf.applyAsLong(rows.getLast()))
            : null;
        return new QueryAnswer(rows.stream().map(project).toList(), next);
    }

    private static <T> int indexOf(List<T> ordered, ToLongFunction<T> numberOf, long number) {
        for (int i = 0; i < ordered.size(); i++) {
            if (numberOf.applyAsLong(ordered.get(i)) == number) {
                return i;
            }
        }
        return -1;
    }
}
