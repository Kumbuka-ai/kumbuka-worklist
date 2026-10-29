/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The query of one {@code query} call as a caller sent it: which filters, how
 * large a page, which sort, and where to continue — raw, as the transport
 * delivered it.
 *
 * <h2>One reading for both transports</h2>
 *
 * Both adapters hand the raw arguments to {@link #QuerySpec} and neither reads
 * them itself. That is what makes "refused on REST and on MCP alike" a property
 * of the construction: an MCP client that sends the filter as a JSON string, or
 * a page size as {@code "10"}, reaches the same reading a REST query string
 * does, rather than an {@code instanceof} that falls through to "no filter" and
 * answers the whole set (REQ-0156).
 *
 * <p>Nothing differs between the transports: both offer every sort a view
 * declares, because the tool schema is a projection of the declaration and
 * declares no narrower set (C4.7).
 *
 * <h2>What a refusal says</h2>
 *
 * Every refusal raised here names the argument under its offenders and in its
 * message, says which form the argument takes, and ends on the act that
 * corrects the call, stated as what to do (DEC-0042). None repeats the value
 * the caller sent: a refusal is logged, a value can be content, and the caller
 * still holds what it sent. Each message is built in one place per refusal, so
 * it can move to a catalogue of the service's messages as it stands.
 *
 * <h2>Read late, on purpose</h2>
 *
 * Nothing is checked when the spec is built. The verb surface calls
 * {@link #pageFor} once the scope is visible and the view declared, so a call
 * against a scope the caller cannot see answers 404 however wrong its paging
 * is — the ratified check order, which a refusal raised in an adapter would
 * break. {@code pageFor} then reads every argument, checks the filter names and
 * value forms against {@link QueryFilter} for that view, the page size, the
 * sort against the view, and decodes the cursor against
 * all of them; the values the scope must have declared are resolved by the
 * view's service.
 *
 * @param untaken the arguments that arrived and are none of the five — refused
 *                by name rather than ignored, so that a narrowing written
 *                beside the filter instead of in it does not answer the whole
 *                set, and a {@code limit} or {@code cursor} of the earlier
 *                contract is not silently dropped
 */
public record QuerySpec(@Nullable Object filter, @Nullable Object pageSize, @Nullable Object after,
                        @Nullable Object sort, @Nullable Object direction, List<String> untaken) {

    /** The page size when a caller does not name one. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * The largest page. A caller asking for more is refused rather than served
     * the ceiling silently: the whole point of the parameter is that the caller
     * says how much they want, and truncating without saying so is what the
     * sprint-169 defect looks like one layer out.
     */
    public static final int MAX_PAGE_SIZE = 200;

    /** The smallest page: a page of none answers nothing and moves no cursor. */
    public static final int MIN_PAGE_SIZE = 1;

    /** The argument names a query takes besides its address and filter. */
    public static final String PAGE_SIZE_ARGUMENT = "page_size";
    public static final String AFTER_ARGUMENT = "after";
    public static final String SORT_ARGUMENT = "sort";
    public static final String DIRECTION_ARGUMENT = "direction";

    /** The sort and direction when a caller names none. */
    public static final SortKey DEFAULT_SORT = SortKey.ID;
    public static final SortKey.Direction DEFAULT_DIRECTION = SortKey.Direction.ASC;

    public QuerySpec {
        untaken = untaken == null ? List.of() : List.copyOf(untaken);
    }

    /** A first page with a filter, as an in-process caller writes one. */
    public QuerySpec(Map<String, Object> filter, int pageSize) {
        this(filter, pageSize, null, null, null, List.of());
    }

    /** The first page in the default sort: no filter, the default page size. */
    public static QuerySpec all() {
        return new QuerySpec(null, null, null, null, null, List.of());
    }

    /**
     * The query read and checked against one view of one scope: no untaken
     * argument, every filter name declared, every value in its form, the page
     * size in range, the sort declared, the cursor one this query
     * handed out.
     *
     * @throws WorklistException {@code ARGUMENT_UNKNOWN}, {@code PREDICATE_UNKNOWN},
     *         {@code ARGUMENT_INVALID}, {@code PAGE_SIZE_REJECTED}, {@code SORT_UNDECLARED},
     *         {@code CURSOR_MALFORMED} or {@code CURSOR_FOREIGN}, naming the
     *         argument under its offenders — never a silent fallback to "no
     *         filter", to the default page size or to the first page
     */
    public PageRequest pageFor(UUID scopeId, String view) {
        refuseUntaken(untaken);
        Map<QueryFilter, Object> filters = QueryFilter.parse(view, filterOf(filter));
        int size = pageSizeOf(pageSize);
        SortKey key = sortOf(view, sort);
        SortKey.Direction way = directionOf(direction);
        return new PageRequest(scopeId, view, filters, size, key, way,
            cursorOf(after)
                .map(cursor -> PageCursor.decode(cursor, scopeId, view, filters, key, way))
                .orElse(null));
    }

    /** The arguments {@code query} declares, which a refusal of any other name answers with. */
    public static final List<String> ARGUMENTS = List.of("scope", "selector",
        QueryFilter.FILTER_ARGUMENT, PAGE_SIZE_ARGUMENT, AFTER_ARGUMENT, SORT_ARGUMENT,
        DIRECTION_ARGUMENT);

    /**
     * Refuses a name that is no argument of {@code query}. The names that
     * arrived are the caller's and never come back (DEC-0043 R19); the answer
     * lists the arguments {@code query} declares.
     */
    private static void refuseUntaken(List<String> untaken) {
        if (untaken.isEmpty()) {
            return;
        }
        throw new WorklistException(
            WorklistException.Reason.ARGUMENT_UNKNOWN,
            List.of(), ARGUMENTS);
    }

    private static Map<String, Object> filterOf(@Nullable Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new WorklistException(
                WorklistException.Reason.ARGUMENT_INVALID,
                List.of(QueryFilter.FILTER_ARGUMENT));
        }
        Map<String, Object> filter = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value == null) {
                // DEC-0043 R17: a filter sent as null is a filter left out.
                continue;
            }
            if (value instanceof Map || value instanceof Collection) {
                // A name no view declares is the caller's, and the refusal
                // names the argument it arrived in rather than the name.
                String path = QueryFilter.declaredAnywhere(name)
                    ? QueryFilter.FILTER_ARGUMENT + "." + name
                    : QueryFilter.FILTER_ARGUMENT;
                throw new WorklistException(
                    WorklistException.Reason.ARGUMENT_INVALID,
                    List.of(path));
            }
            filter.put(name, value);
        }
        return filter;
    }

    private static int pageSizeOf(@Nullable Object raw) {
        if (raw == null) {
            return DEFAULT_PAGE_SIZE;
        }
        return wholeNumber(raw)
            .filter(number -> number >= MIN_PAGE_SIZE && number <= MAX_PAGE_SIZE)
            .orElseThrow(() -> new WorklistException(
                WorklistException.Reason.PAGE_SIZE_REJECTED,
                List.of(PAGE_SIZE_ARGUMENT)))
            .intValue();
    }

    private static SortKey sortOf(String view, @Nullable Object raw) {
        if (raw == null) {
            return DEFAULT_SORT;
        }
        return (raw instanceof String text ? SortKey.named(text) : Optional.<SortKey>empty())
            .filter(key -> key.declaredOn(view))
            .orElseThrow(() -> new WorklistException(
                WorklistException.Reason.SORT_UNDECLARED,
                List.of(SORT_ARGUMENT)));
    }


    private static SortKey.Direction directionOf(@Nullable Object raw) {
        if (raw == null) {
            return DEFAULT_DIRECTION;
        }
        return (raw instanceof String text ? SortKey.Direction.named(text)
                : Optional.<SortKey.Direction>empty())
            .orElseThrow(() -> new WorklistException(
                WorklistException.Reason.ARGUMENT_INVALID,
                List.of(DIRECTION_ARGUMENT)));
    }

    private static Optional<String> cursorOf(@Nullable Object raw) {
        if (raw == null) {
            return Optional.empty();
        }
        if (!(raw instanceof String text) || text.isBlank()) {
            throw PageCursor.malformed();
        }
        return Optional.of(text);
    }

    /**
     * A whole number from a JSON number or from decimal digits, if the value
     * is one.
     *
     * <p>Both forms, because REST carries every argument as text and an MCP
     * client may too; a fraction, a word or an empty string is not one.
     */
    static Optional<Long> wholeNumber(@Nullable Object raw) {
        try {
            BigDecimal value = switch (raw) {
                case Integer i -> BigDecimal.valueOf(i);
                case Long l -> BigDecimal.valueOf(l);
                case Number n -> new BigDecimal(n.toString());
                case String s when s.strip().matches("-?\\d{1,18}") -> new BigDecimal(s.strip());
                case null, default -> null;
            };
            return value == null ? Optional.empty() : Optional.of(value.longValueExact());
        } catch (ArithmeticException | NumberFormatException notWhole) {
            return Optional.empty();
        }
    }
}
