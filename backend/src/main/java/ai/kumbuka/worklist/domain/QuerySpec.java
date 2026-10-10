/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The narrowing of one {@code query} as a caller sent it: which filters, how
 * many objects, and where to continue — raw, as the transport delivered it.
 *
 * <h2>One reading for both transports</h2>
 *
 * Both adapters hand the raw arguments to {@link #of} and neither reads them
 * itself. That is what makes "refused on REST and on MCP alike" a property of
 * the construction: an MCP client that sends the filter as a JSON string, or a
 * limit as {@code "10"}, reaches the same reading a REST query string does,
 * rather than an {@code instanceof} that falls through to "no filter" and
 * answers the whole set (REQ-0156).
 *
 * <h2>Read late, on purpose</h2>
 *
 * Nothing is checked when the spec is built. The verb surface calls
 * {@link #narrowFor} once the scope is visible and the view declared, so a call
 * against a scope the caller cannot see answers 404 however wrong its narrowing
 * is — the ratified check order, which a refusal raised in an adapter would
 * break. {@code narrowFor} then reads every argument, checks the filter names
 * and value forms against {@link QueryFilter} for that view, and decodes the
 * cursor; the values the scope must have declared are resolved by the view's
 * service.
 *
 * <h2>Paging</h2>
 *
 * A limit bounds one answer, and a cursor continues after the last object of
 * the previous one. The cursor is handed out by the answer ({@link QueryAnswer})
 * and is opaque to a caller; see {@link PageCursor}. Without a limit the default
 * of {@value #DEFAULT_LIMIT} applies.
 *
 * @param untaken the arguments that arrived and are none of filter, limit and
 *                cursor — refused by name rather than ignored, so that a
 *                narrowing written beside the filter instead of in it does not
 *                answer the whole set
 */
public record QuerySpec(Object filter, Object limit, Object cursor, List<String> untaken) {

    /** The default upper bound on one answer, when a caller does not name one. */
    public static final int DEFAULT_LIMIT = 100;

    /**
     * The absolute upper bound. A caller asking for more is refused rather
     * than served the ceiling silently: the whole point of the parameter is
     * that the caller says how much they want, and truncating without saying
     * is what the sprint-169 defect looks like one layer out.
     */
    public static final int MAX_LIMIT = 1000;

    /** The argument names a query takes besides its address. */
    public static final String LIMIT_ARGUMENT = "limit";
    public static final String CURSOR_ARGUMENT = "cursor";

    public QuerySpec {
        untaken = untaken == null ? List.of() : List.copyOf(untaken);
    }

    /** A first page with a filter, as an in-process caller writes one. */
    public QuerySpec(Map<String, Object> filter, int limit) {
        this(filter, limit, null, List.of());
    }

    /** The all-of-it spec: no filter, default limit, first page. */
    public static QuerySpec all() {
        return new QuerySpec(null, null, null, List.of());
    }

    /**
     * The narrowing as a transport delivered it, read nowhere yet.
     *
     * @param filter  a map from filter name to a scalar value, or null for none
     * @param limit   a whole number, decimal digits, or null for the default
     * @param cursor  the cursor of a previous answer, or null for the first page
     * @param untaken the arguments that are none of the three
     */
    public static QuerySpec of(Object filter, Object limit, Object cursor,
                               List<String> untaken) {
        return new QuerySpec(filter, limit, cursor, untaken);
    }

    /**
     * The narrowing read and checked against one view: no untaken argument,
     * every filter name declared, every value in its form, the limit in range,
     * the cursor one this view handed out.
     *
     * @throws WorklistException {@code UNKNOWN_FIELD} or {@code INVALID_VALUE},
     *         naming the argument under its offenders — never a silent fallback
     *         to "no filter", to the default limit or to the first page
     */
    public Narrowing narrowFor(String view) {
        refuseUntaken(untaken);
        return new Narrowing(view, QueryFilter.parse(view, filterOf(filter)), limitOf(limit),
            PageCursor.decode(view, cursorOf(cursor)));
    }

    private static void refuseUntaken(List<String> untaken) {
        if (untaken.isEmpty()) {
            return;
        }
        throw new WorklistException(
            WorklistException.Reason.UNKNOWN_FIELD,
            "'query' takes a filter, a limit and a cursor besides its address, and "
                + untaken + " is none of them. A filter is written inside the filter "
                + "argument. Nothing was answered: an argument this read ignores would "
                + "make the whole set look like a correct narrow answer",
            untaken);
    }

    private static Map<String, Object> filterOf(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "the filter is an object from filter name to value, and arrived as "
                    + kindOf(raw) + ". Nothing was answered: a filter this read cannot "
                    + "read would otherwise be dropped",
                List.of(QueryFilter.FILTER_ARGUMENT));
        }
        Map<String, Object> filter = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String name = String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value == null || value instanceof Map || value instanceof Collection) {
                throw new WorklistException(
                    WorklistException.Reason.INVALID_VALUE,
                    "the filter '" + name + "' takes one value, and arrived as "
                        + kindOf(value) + ". Each filter narrows on one value; a set of "
                        + "them is a shape this read does not apply",
                    List.of(QueryFilter.FILTER_ARGUMENT + "." + name));
            }
            filter.put(name, value);
        }
        return filter;
    }

    private static int limitOf(Object raw) {
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        Long number = wholeNumber(raw);
        if (number == null || number < 1 || number > MAX_LIMIT) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "the limit on one answer is a whole number from 1 to " + MAX_LIMIT
                    + ", and arrived as " + kindOf(raw) + ". Nothing was answered: a "
                    + "caller wanting more follows the cursor of the answer, and a limit "
                    + "read as a ceiling or a default without saying so would give an "
                    + "answer that looks complete and is not",
                List.of(LIMIT_ARGUMENT));
        }
        return number.intValue();
    }

    private static String cursorOf(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof String text) || text.isBlank()) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "the cursor is the 'next_cursor' of a previous answer, passed back as it "
                    + "came, and arrived as " + kindOf(raw),
                List.of(CURSOR_ARGUMENT));
        }
        return text;
    }

    /**
     * A whole number from a JSON number or from decimal digits, or null.
     *
     * <p>Both forms, because REST carries every argument as text and an MCP
     * client may too; a fraction, a word or an empty string is not one.
     */
    static Long wholeNumber(Object raw) {
        try {
            BigDecimal value = switch (raw) {
                case Integer i -> BigDecimal.valueOf(i);
                case Long l -> BigDecimal.valueOf(l);
                case Number n -> new BigDecimal(n.toString());
                case String s when s.strip().matches("-?\\d{1,18}") -> new BigDecimal(s.strip());
                case null, default -> null;
            };
            return value == null ? null : value.longValueExact();
        } catch (ArithmeticException | NumberFormatException notWhole) {
            return null;
        }
    }

    private static String kindOf(Object raw) {
        return switch (raw) {
            case null -> "nothing";
            case String s -> "the text '" + s + "'";
            case Map<?, ?> m -> "an object";
            case Collection<?> c -> "a list";
            default -> String.valueOf(raw);
        };
    }
}
