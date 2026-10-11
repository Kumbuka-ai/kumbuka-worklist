/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/**
 * Every object of one view, read the way a caller reads it: page by page,
 * following each page's cursor until none is handed out.
 *
 * <p>The services have no whole-set read any more; a test that wants the whole
 * view walks it, and so exercises the paging it relies on rather than a path
 * no caller takes.
 */
public final class Pages {

    private Pages() {
    }

    /** The whole view in the default sort, by number. */
    public static List<Map<String, Object>> all(UUID scope, String view,
                                                BiFunction<UUID, PageRequest, Page> query) {
        return all(scope, view, SortKey.ID, query);
    }

    /** The whole view in the given sort, ascending. */
    public static List<Map<String, Object>> all(UUID scope, String view, SortKey sort,
                                                BiFunction<UUID, PageRequest, Page> query) {
        List<Map<String, Object>> objects = new ArrayList<>();
        String after = null;
        do {
            PageRequest request = new QuerySpec(null, QuerySpec.MAX_PAGE_SIZE, after,
                sort.wireName(), null, List.of(), SortKey.ALL).pageFor(scope, view);
            Page page = query.apply(scope, request);
            objects.addAll(page.objects());
            after = page.next();
        } while (after != null);
        return objects;
    }
}
