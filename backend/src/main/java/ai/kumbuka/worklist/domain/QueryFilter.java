/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Every filter a {@code query} admits, per view, with the form its value takes.
 *
 * <p>This is the one place the filters are declared. The verb surface parses a
 * caller's filter against it before any store is touched, the MCP tool
 * description is written from it, and the REST binding hands every
 * {@code filter.<name>} parameter to the same parse — so a filter added here is
 * reachable from both transports at once, and a name absent here is refused on
 * both (REQ-0156).
 *
 * <p>The parse here is the FORM: whether a value can be read as what the filter
 * takes at all. That is decidable from this declaration alone, without any
 * scope, so a value of the wrong form is {@code ARGUMENT_INVALID} (DEC-0042) —
 * and so is a value outside a fixed set of the platform's own, such as the
 * milestone statuses. Whether a well-formed name, number or token names
 * something the scope declared is the domain's question and is answered by the
 * view's service, because it needs the scope's vocabulary.
 *
 * <p>Only enumerated fields are filters. A free text would need a matching rule
 * the surface would have to publish; it is not offered, and a caller asking for
 * one is refused by name like any other undeclared filter.
 */
public enum QueryFilter {

    /** An item's status, by the display name the scope declared it under. */
    ITEM_STATUS(Selector.ITEM, Names.STATUS, Form.NAME),

    /** An item's milestone, by the milestone's number. */
    ITEM_MILESTONE(Selector.ITEM, "milestone", Form.NUMBER),

    /** An item's workstream, by the token the scope declared it under. */
    ITEM_WORKSTREAM(Selector.ITEM, "workstream", Form.NAME),

    /** Whether an iteration is closed. */
    ITERATION_CLOSED(Selector.ITERATION, "closed", Form.BOOLEAN),

    /** A milestone's status, one of the platform's fixed set. */
    MILESTONE_STATUS(Selector.MILESTONE, Names.STATUS, Form.oneOf(Milestone.STATUSES)),

    /** A milestone's kind, the goal or one of the markers. */
    MILESTONE_KIND(Selector.MILESTONE, "kind", Form.oneOf(Milestone.KINDS)),

    /** A workstream's status, one of the platform's fixed set. */
    WORKSTREAM_STATUS(Selector.WORKSTREAM, Names.STATUS, Form.oneOf(Workstream.STATUSES));

    /** The prefix every filter argument is named under in a refusal and on REST. */
    public static final String FILTER_ARGUMENT = "filter";

    private final String view;
    private final String filterName;
    private final Form form;

    QueryFilter(String view, String filterName, Form form) {
        this.view = view;
        this.filterName = filterName;
        this.form = form;
    }

    /** The view this filter narrows. */
    public String view() {
        return view;
    }

    /** The name a caller writes it under. */
    public String filterName() {
        return filterName;
    }


    /** The argument a refusal names: {@code filter.<name>}. */
    public String offenderName() {
        return FILTER_ARGUMENT + "." + filterName;
    }

    /** Every filter one view declares, in declaration order. */
    public static List<QueryFilter> of(String view) {
        return Arrays.stream(values()).filter(f -> f.view.equals(view)).toList();
    }

    /**
     * A caller's filter, parsed for one view: every name declared, every value in
     * its form.
     *
     * @throws WorklistException {@code PREDICATE_UNKNOWN} for a name the view does
     *         not declare, {@code ARGUMENT_INVALID} for a value its filter cannot
     *         read. Either way nothing is answered, because a filter that is
     *         dropped makes the whole set look like a correct narrow answer.
     */
    static Map<QueryFilter, Object> parse(String view, Map<String, Object> raw) {
        Map<QueryFilter, Object> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            QueryFilter filter = named(view, entry.getKey());
            parsed.put(filter, filter.form.read(filter, entry.getValue()));
        }
        return parsed;
    }

    private static QueryFilter named(String view, String name) {
        return of(view).stream()
            .filter(f -> f.filterName.equals(name))
            .findFirst()
            // The name that arrived is the caller's and never comes back
            // (DEC-0043 R19): the answer names the filter argument and lists
            // the filters this view declares, as their paths.
            .orElseThrow(() -> new WorklistException(
                WorklistException.Reason.PREDICATE_UNKNOWN,
                List.of(FILTER_ARGUMENT),
                of(view).stream().map(QueryFilter::offenderName).toList()));
    }

    /** Whether any view declares a filter of this name: the name is then the platform's. */
    static boolean declaredAnywhere(String name) {
        return Arrays.stream(values()).anyMatch(f -> f.filterName.equals(name));
    }


    /**
     * Literals the constants above share. A nested holder, because an enum's
     * own static fields are not yet initialised when its constants are built.
     */
    private static final class Names {
        static final String STATUS = "status";

        private Names() {
        }
    }

    /** The value forms a filter takes. */
    abstract static class Form {

        private static final Pattern UUID_SHAPE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

        /** A declared name or token: a non-blank string that is not the platform's identity. */
        static final Form NAME = new Form() {
            @Override
            Object read(QueryFilter filter, Object raw) {
                String text = text(filter, raw);
                if (UUID_SHAPE.matcher(text).matches()) {
                    throw refused(filter);
                }
                return text;
            }
        };

        /** A positive whole number, written as a number or as decimal digits. */
        static final Form NUMBER = new Form() {
            @Override
            Object read(QueryFilter filter, Object raw) {
                Long number = QuerySpec.wholeNumber(raw);
                if (number == null || number < 1) {
                    throw refused(filter);
                }
                return number;
            }
        };

        /** {@code true} or {@code false}, as a boolean or as that word. */
        static final Form BOOLEAN = new Form() {
            @Override
            Object read(QueryFilter filter, Object raw) {
                if (raw instanceof Boolean flag) {
                    return flag;
                }
                if ("true".equals(raw) || "false".equals(raw)) {
                    return Boolean.valueOf((String) raw);
                }
                throw refused(filter);
            }
        };

        static Form oneOf(List<String> admitted) {
            return new Form() {
                @Override
                Object read(QueryFilter filter, Object raw) {
                    String text = text(filter, raw);
                    if (!admitted.contains(text)) {
                        throw refused(filter);
                    }
                    return text;
                }
            };
        }

        abstract Object read(QueryFilter filter, Object raw);

        private static String text(QueryFilter filter, Object raw) {
            if (!(raw instanceof String text) || text.isBlank()) {
                throw refused(filter);
            }
            return text.strip();
        }

        /**
         * A value this filter cannot read: the filter named as its path, the
         * value itself left out — the caller holds it, and a refusal is logged.
         */
        private static WorklistException refused(QueryFilter filter) {
            String path = filter.offenderName();
            return new WorklistException(
                WorklistException.Reason.ARGUMENT_INVALID,
                List.of(path));
        }
    }
}
