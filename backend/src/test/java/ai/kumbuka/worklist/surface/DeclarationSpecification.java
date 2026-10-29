/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.CounterProbe;
import java.util.List;
import java.util.Map;

/**
 * The counter-probe's own transcription of the worklist declaration (ADR-0039).
 *
 * <p>Written by hand from the verb surface specification, the router's verb
 * catalogue and the refusal model, and importing nothing from the production
 * packages: a check that derived its expectation from the declaration it
 * checks would be consistent with itself and prove nothing. It is the
 * counter-probe, never the source. {@code DeclarationConformanceIT} compares the
 * running service's {@code GET /api/declaration} against it field by field.
 *
 * <p>The effect classes are the router's names, verbatim, so that the router
 * reads them without a mapping.
 */
@CounterProbe
public final class DeclarationSpecification {

    private DeclarationSpecification() {
    }

    /**
     * One argument as a caller sends it.
     *
     * @param defaultValue the fallback in its own JSON type, as the parsed
     *                     answer holds it; null where the argument declares none
     */
    public record Arg(String name, String type, boolean required, Object defaultValue,
                      List<String> values) {

        static Arg req(String name, String type) {
            return new Arg(name, type, true, null, List.of());
        }

        static Arg opt(String name, String type, Object defaultValue) {
            return new Arg(name, type, false, defaultValue, List.of());
        }

        static Arg view(String... views) {
            return new Arg("selector", "string", true, null, List.of(views));
        }
    }

    /**
     * One verb as the declaration carries it.
     *
     * @param answer the shape of its answer; the members of each shape are
     *               {@link #ANSWER_MEMBERS}
     */
    public record Verb(String identity, String effectClass, String answer, List<Arg> args,
                       List<String> reasons) {

        /** The name the verb is called under on REST: the identity without the scheme. */
        public String restName() {
            return identity.substring(SCHEME.length() + 1);
        }
    }

    public static final String SCHEME = "worklist";

    /** The shape of the declaration, the same for every service (C5.5). */
    public static final String SHAPE_VERSION = "3";

    /** The form of a worklist address; the service has no technical one. */
    public static final String ADDRESS_FORM = "worklist://<scope>/<selector>/<number>";

    /** Where every argument of this service travels: the top level. */
    public static final String PLACEMENT = "TOP";

    /** The members each answer shape carries at its top level. */
    public static final Map<String, List<String>> ANSWER_MEMBERS = Map.of(
        "OBJECT", List.of("address", "fields"),
        "OBJECT_CREATED", List.of("address", "fields"),
        "OBJECT_LISTING", List.of("objects", "[next_cursor]"));

    /** The effect classes the worklist verbs take, in the order the declaration lists them. */
    public static final List<String> DECLARED_EFFECT_CLASSES = List.of(
        "ITEM_READ", "COLLECTION_READ", "CREATE", "FIELD_WRITE", "GRAPH_ADD_OR_REMOVE",
        "ITEM_TRANSITION", "COLLECTION_COMPOUND");

    private static final Arg SCOPE = Arg.req("scope", "string");
    private static final Arg ITEM_VIEW = Arg.view("item");
    private static final Arg ITERATION_VIEW = Arg.view("iteration");
    private static final Arg ADDRESS = Arg.req("address", "string");
    private static final Arg TOKEN = Arg.req("conflict_token", "string");
    private static final Arg FIELDS = Arg.req("fields", "object");
    private static final Arg DURATION = Arg.req("duration_seconds", "integer");
    private static final Arg TO_ITEM = Arg.req("to_item", "string");
    private static final Arg TYPE = Arg.req("type", "string");

    /** Every refusal a call can meet before its own work begins. */
    private static final List<String> COMMON = List.of(
        "ADDRESS_MALFORMED", "NOT_FOUND", "SESSION_NOT_BOUND", "SCOPE_KIND_UNSUPPORTED",
        "ARGUMENT_MISSING", "ARGUMENT_UNKNOWN", "ARGUMENT_INVALID", "PAYLOAD_MALFORMED",
        "UNEXPECTED_FAILURE");

    /** What a writing call can add: the scope's state and the caller's right. */
    private static final List<String> WRITE = List.of(
        "SCOPE_LOCKED", "SCOPE_READ_ONLY", "SELECTOR_UNDECLARED", "SELECTOR_WITHDRAWN");

    /** What a write carrying a conflict token can add. */
    private static final List<String> TOKENED = List.of(
        "CONFLICT_TOKEN_MISSING", "CONFLICT", "ADDRESS_READ_ONLY");

    public static final List<Verb> VERBS = List.of(
        new Verb("worklist_create", "CREATE", "OBJECT_CREATED",
            List.of(SCOPE, Arg.view("item", "iteration", "milestone"), FIELDS),
            reasons(WRITE, List.of("SELECTOR_DECLARED_NOT_WRITTEN", "FIELD_RETRACTED",
                "FIELD_NOT_SETTABLE", "INVALID_VALUE", "VALUE_UNDECLARED",
                "WORKSTREAM_WITHDRAWN", "ITEM_WORKSTREAM_MISSING", "SETTING_ABSENT",
                "CARDINALITY_EXCEEDED"))),
        new Verb("worklist_read", "ITEM_READ", "OBJECT", List.of(ADDRESS),
            reasons(List.of("SELECTOR_UNDECLARED", "ITERATION_ABSENT"))),
        new Verb("worklist_update", "FIELD_WRITE", "OBJECT",
            List.of(ADDRESS, TOKEN, FIELDS),
            reasons(WRITE, TOKENED, List.of("SELECTOR_DECLARED_NOT_WRITTEN",
                "FIELD_RETRACTED", "FIELD_NOT_SETTABLE", "INVALID_VALUE", "VALUE_UNDECLARED",
                "WORKSTREAM_WITHDRAWN", "ITEM_WORKSTREAM_MISSING", "ITERATION_CLOSED"))),
        new Verb("worklist_query", "COLLECTION_READ", "OBJECT_LISTING",
            List.of(SCOPE, Arg.view("item", "iteration", "milestone", "workstream"),
                Arg.opt("filter", "object", null),
                Arg.opt("page_size", "integer", 50), Arg.opt("after", "string", null),
                new Arg("sort", "string", false, "id", List.of("id", "created_at", "rank")),
                new Arg("direction", "string", false, "asc", List.of("asc", "desc"))),
            reasons(List.of("SELECTOR_UNDECLARED", "SELECTOR_WITHDRAWN", "PREDICATE_UNKNOWN",
                "VALUE_UNDECLARED", "PAGE_SIZE_REJECTED", "CURSOR_MALFORMED", "CURSOR_FOREIGN",
                "SORT_UNDECLARED"))),
        new Verb("worklist_accept", "ITEM_TRANSITION", "OBJECT", List.of(ADDRESS, TOKEN),
            reasons(WRITE, TOKENED, List.of("VERB_UNCARRIED", "IDENTIFIER_UNDECIDED"))),
        new Verb("worklist_withdraw", "ITEM_TRANSITION", "OBJECT",
            List.of(ADDRESS, TOKEN, Arg.req("status", "string")),
            reasons(WRITE, TOKENED, List.of("VERB_UNCARRIED", "INVALID_VALUE",
                "VALUE_UNDECLARED"))),
        new Verb("worklist_close", "ITEM_TRANSITION", "OBJECT",
            List.of(ADDRESS, TOKEN, Arg.opt("produced", "string", null)),
            reasons(WRITE, TOKENED, List.of("VERB_UNCARRIED", "ITERATION_CLOSED",
                "ITERATION_INCOMPLETE", "ITERATION_PRODUCED_MISSING"))),
        new Verb("worklist_advance", "COLLECTION_COMPOUND", "OBJECT",
            List.of(SCOPE, ITERATION_VIEW, TOKEN),
            reasons(WRITE, List.of("CONFLICT_TOKEN_MISSING", "CONFLICT", "VERB_UNCARRIED",
                "ITERATION_ABSENT", "SETTING_ABSENT"))),
        new Verb("worklist_plan", "GRAPH_ADD_OR_REMOVE", "OBJECT_CREATED",
            List.of(ADDRESS, TOKEN),
            reasons(WRITE, TOKENED, List.of("MEMBERSHIP_PRESENT", "ITERATION_CLOSED",
                "ITEM_UNPLANNABLE", "SETTING_ABSENT", "CARDINALITY_EXCEEDED"))),
        new Verb("worklist_unplan", "GRAPH_ADD_OR_REMOVE", "OBJECT", List.of(ADDRESS, TOKEN),
            reasons(WRITE, TOKENED, List.of("ITERATION_CLOSED"))),
        new Verb("worklist_claim", "ITEM_TRANSITION", "OBJECT", List.of(ADDRESS, DURATION),
            reasons(WRITE, List.of("VERB_UNCARRIED", "ADDRESS_READ_ONLY", "INVALID_VALUE",
                "CLAIM_HELD"))),
        new Verb("worklist_release", "ITEM_TRANSITION", "OBJECT",
            List.of(ADDRESS, Arg.req("receipt", "string")),
            reasons(WRITE, List.of("VERB_UNCARRIED", "ADDRESS_READ_ONLY", "CLAIM_ABSENT",
                "CLAIM_RECEIPT_UNKNOWN"))),
        new Verb("worklist_claim_next", "COLLECTION_COMPOUND", "OBJECT",
            List.of(SCOPE, ITEM_VIEW, DURATION),
            reasons(WRITE, List.of("VERB_UNCARRIED", "DRAW_EMPTY", "CLAIM_HELD"))),
        new Verb("worklist_relate", "ITEM_TRANSITION", "OBJECT",
            List.of(ADDRESS, TOKEN, TO_ITEM, TYPE),
            reasons(WRITE, TOKENED, List.of("VERB_UNCARRIED", "INVALID_VALUE",
                "VALUE_UNDECLARED"))),
        new Verb("worklist_unrelate", "ITEM_TRANSITION", "OBJECT",
            List.of(ADDRESS, TOKEN, TO_ITEM, TYPE),
            reasons(WRITE, TOKENED, List.of("VERB_UNCARRIED", "INVALID_VALUE",
                "VALUE_UNDECLARED"))),
        new Verb("worklist_validate", "COLLECTION_COMPOUND", "OBJECT",
            List.of(SCOPE, ITEM_VIEW),
            reasons(List.of("SELECTOR_UNDECLARED", "SELECTOR_WITHDRAWN", "VERB_UNCARRIED"))));

    @SafeVarargs
    private static List<String> reasons(List<String>... groups) {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(COMMON);
        for (List<String> group : groups) {
            all.addAll(group);
        }
        return List.copyOf(all);
    }

    /** The effect classes the router knows, verbatim from its catalogue. */
    public static final List<String> EFFECT_CLASSES = List.of(
        "ITEM_READ", "COLLECTION_READ", "SCHEME_READ", "SCOPE_READ", "SCOPE_COMPOUND",
        "CREATE", "FIELD_WRITE", "GRAPH_ADD_OR_REMOVE", "ITEM_TRANSITION", "ITEM_READ_CALL",
        "ITEM_DELETE", "COLLECTION_COMPOUND");

    /** Kept for reading convenience in failure messages. */
    public static Map<String, Verb> byIdentity() {
        Map<String, Verb> map = new java.util.LinkedHashMap<>();
        VERBS.forEach(v -> map.put(v.identity(), v));
        return map;
    }
}
