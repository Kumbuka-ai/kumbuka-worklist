/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.ScopeSettingService;
import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.domain.VocabularyRegistry;
import ai.kumbuka.worklist.domain.WorklistException;
import ai.kumbuka.worklist.domain.WorkstreamService;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every object of the work surface, driven through every state it has, names
 * in {@code next} exactly the calls open to the caller in that state -- and
 * every call it names is carried out and accepted (C6.1, DEC-0043: a named
 * call is never refused because of the state).
 *
 * <p>The open calls per object and state are written here from the target
 * cut, never read from the service:
 * <ul>
 *   <li>an item, in every state: {@code read}, {@code update}, {@code relate},
 *       {@code unrelate}, {@code tag_attach}, {@code tag_detach}, and
 *       {@code plan} into each open iteration it is no member of, where the
 *       item may enter an iteration at all (an actionable status and a
 *       milestone on the product path). A closed item stays writable;</li>
 *   <li>an open iteration: {@code read}, {@code update}, {@code close},
 *       {@code plan} at its own address, and {@code advance} at the iteration
 *       collection while an open iteration stands that is not the current one;
 *       a closed one: {@code read};</li>
 *   <li>a milestone planned or active: {@code read}, {@code update},
 *       {@code close}; closed: {@code read};</li>
 *   <li>a membership of an open iteration: {@code read}, {@code update},
 *       {@code unplan}; of a closed one: {@code read};</li>
 *   <li>a workstream: {@code read}.</li>
 * </ul>
 * No object of this service waits for another party: no answer carries
 * {@code waiting_for}.
 *
 * <p>Each test works in a scope of its own, because which calls are open on
 * an item or an iteration depends on every iteration of the scope.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class OpenCallsIT {

    private static final String OPEN = "oc-open";
    private static final String DONE = "oc-done";
    private static final String RELATION = "oc-relates";
    private static final String TAG = "oc-tag";

    /** Roomy, so that no limit of the scope is what a call meets. */
    private static final int GENEROUS = 1_000;

    @Inject TestIdentityAssociation identity;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject WorkstreamService workstreams;
    @Inject ScopeSettingService settings;

    @BeforeEach
    void asMember() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);
    }

    // =======================================================================
    // The chain: every object through every state
    // =======================================================================

    @Test
    void every_object_names_the_calls_open_in_each_state_and_each_named_call_is_accepted() {
        Scope at = stage("open-calls-chain",
            UUID.fromString("0c0a11e5-0000-4000-8000-000000000001"));
        List<String> offences = new ArrayList<>();

        // ---- a milestone: planned, active, closed --------------------------
        Map<String, Object> goal = at.create(Selector.MILESTONE, Map.of("title", "a goal"));
        String m1 = address(goal);
        check(offences, "milestone planned, as created", goal, at.milestone(m1, true));
        check(offences, "milestone planned, read", at.read(m1), at.milestone(m1, true));
        check(offences, "milestone, updated", at.execute("worklist_update " + m1),
            at.milestone(m1, true));
        Map<String, Object> active = at.mcp("worklist_update", Map.of("address", m1,
            "conflict_token", at.token(m1), "fields", Map.of("status", "active")));
        check(offences, "milestone active", active, at.milestone(m1, true));
        check(offences, "milestone closed, as closed", at.execute("worklist_close " + m1),
            at.milestone(m1, false));
        check(offences, "milestone closed, read", at.execute("worklist_read " + m1),
            at.milestone(m1, false));

        // ---- items: one that may enter an iteration, one that may not ------
        String path = address(at.create(Selector.MILESTONE, Map.of("title", "the path")));
        String a = address(at.create(Selector.ITEM, Map.of("title", "plannable",
            "status", OPEN, "milestone", number(path))));
        String b = address(at.create(Selector.ITEM, Map.of("title", "without a milestone",
            "status", OPEN)));
        at.relateTo(b);

        // ---- two open iterations, no current one ---------------------------
        Map<String, Object> first = at.create(Selector.ITERATION, Map.of("title", "first",
            "motto", "first", "description", "the first of two"));
        String i1 = address(first);
        String i2 = address(at.create(Selector.ITERATION, Map.of("title", "second",
            "motto", "second", "description", "the second of two")));
        check(offences, "iteration open, as created", first, at.iteration(i1, true, true));
        check(offences, "iteration open, read", at.read(i2), at.iteration(i2, true, true));

        check(offences, "item, plannable into both", at.read(a),
            at.item(a, i1 + "/" + number(a), i2 + "/" + number(a)));
        check(offences, "item, not plannable", at.read(b), at.item(b));
        check(offences, "item, as listed", entry(at.query(Selector.ITEM), a),
            at.item(a, i1 + "/" + number(a), i2 + "/" + number(a)));

        // Every call the plannable item names is carried out, in this order:
        // relate before unrelate, so that the edge unrelate takes stands.
        for (String call : at.item(a, i1 + "/" + number(a), i2 + "/" + number(a))) {
            at.execute(call);
        }
        check(offences, "item, planned into both", at.read(a), at.item(a));

        // ---- a membership of an open iteration -----------------------------
        String member = i1 + "/" + number(a);
        check(offences, "membership, open iteration", at.read(member),
            at.membership(member, true));
        for (String call : at.membership(member, true)) {
            check(offences, "membership after " + verbOf(call), at.execute(call),
                at.membership(member, true));
        }

        // ---- the first iteration: each call it names, then closed ----------
        at.execute("worklist_read " + i1);
        check(offences, "iteration, updated", at.execute("worklist_update " + i1),
            at.iteration(i1, true, true));
        Map<String, Object> planned = at.execute("worklist_plan " + i1);
        String appended = address(planned);
        check(offences, "membership, as planned at the iteration", planned,
            at.membership(appended, true));
        at.mcp("worklist_update", Map.of("address", appended, "conflict_token",
            at.token(appended), "fields", Map.of("membership_status", "done")));
        Map<String, Object> advanced = at.execute("worklist_advance " + at.iterations());
        check(offences, "the advance's answer", advanced,
            List.of("worklist_read " + at.iterations() + "/current"));
        at.execute("worklist_read " + at.iterations() + "/current");
        check(offences, "iteration, current, a later one open", at.read(i1),
            at.iteration(i1, true, true));
        check(offences, "iteration closed, as closed", at.execute("worklist_close " + i1),
            at.iteration(i1, false, false));
        check(offences, "iteration closed, read", at.read(i1), at.iteration(i1, false, false));
        check(offences, "membership, closed iteration", at.execute("worklist_read " + member),
            at.membership(member, false));
        check(offences, "membership, closed iteration, planned at the iteration",
            at.read(appended), at.membership(appended, false));

        // ---- the second iteration: advanced to, then the only open one -----
        check(offences, "iteration open, no current", at.read(i2), at.iteration(i2, true, true));
        at.execute("worklist_advance " + at.iterations());
        check(offences, "iteration open, current, none other open", at.read(i2),
            at.iteration(i2, true, false));

        // ---- the item, closed, stays writable ------------------------------
        at.mcp("worklist_update", Map.of("address", a, "conflict_token", at.token(a),
            "fields", Map.of("status", DONE)));
        check(offences, "item closed", at.read(a), at.item(a));
        check(offences, "item closed, updated", at.execute("worklist_update " + a),
            at.item(a));
        check(offences, "item, not plannable, open iteration beside it", at.read(b),
            at.item(b));

        // ---- a workstream --------------------------------------------------
        Map<String, Object> stream = at.query(Selector.WORKSTREAM).get(0);
        String w = address(stream);
        check(offences, "workstream, as listed", stream, List.of("worklist_read " + w));
        check(offences, "workstream, read", at.execute("worklist_read " + w),
            List.of("worklist_read " + w));

        assertThat(offences)
            .as("every answer names exactly the calls open to the caller in the object's "
                + "state, and every named call is accepted")
            .isEmpty();
    }

    // =======================================================================
    // The names of the calls on each surface (C6.2)
    // =======================================================================

    @Test
    void next_names_the_calls_in_the_names_of_the_caller_s_surface() {
        Scope at = stage("open-calls-names",
            UUID.fromString("0c0a11e5-0000-4000-8000-000000000002"));
        String item = address(at.create(Selector.ITEM, Map.of("title", "named",
            "status", OPEN)));
        String rest = "/api/" + item.substring("worklist://".length());
        List<String> offences = new ArrayList<>();

        check(offences, "over MCP", at.read(item), at.item(item));
        check(offences, "over REST", given().get(rest).jsonPath().getMap("$"),
            withoutPrefix(at.item(item)));
        check(offences, "over REST as the assistant",
            given().header("Kumbuka-Surface", "assistant").get(rest).jsonPath().getMap("$"),
            at.item(item));
        Map<String, Object> page = given().get("/api/" + at.slug() + "/" + Selector.ITEM)
            .jsonPath().getMap("$");
        check(offences, "a REST listing entry", entryOf(page, item), withoutPrefix(at.item(item)));

        assertThat(offences)
            .as("MCP names tool names; REST names its verbs, and the tool names under "
                + "Kumbuka-Surface: assistant")
            .isEmpty();
    }

    // =======================================================================
    // Who may only read gets only the reading calls
    // =======================================================================

    @Test
    void a_caller_who_may_only_read_is_named_only_the_reading_calls() {
        Scope at = stage("open-calls-reader",
            UUID.fromString("0c0a11e5-0000-4000-8000-000000000003"));
        String path = address(at.create(Selector.MILESTONE, Map.of("title", "the path")));
        String item = address(at.create(Selector.ITEM, Map.of("title", "read only",
            "status", OPEN, "milestone", number(path))));
        String iteration = address(at.create(Selector.ITERATION, Map.of("title", "read",
            "motto", "read", "description", "read only")));
        String member = iteration + "/" + number(item);
        at.mcp("worklist_plan", Map.of("address", member,
            "conflict_token", at.token(iteration)));
        String stream = address(at.query(Selector.WORKSTREAM).get(0));

        SurfaceFixture.registerMutedMember();
        SurfaceFixture.asMutedMember(identity);
        List<String> offences = new ArrayList<>();
        for (String address : List.of(item, iteration, path, member, stream)) {
            check(offences, "read only, " + address, at.read(address),
                List.of("worklist_read " + address));
        }
        for (Map<String, Object> entry : at.query(Selector.ITEM)) {
            check(offences, "read only, listed " + entry.get("address"), entry,
                List.of("worklist_read " + entry.get("address")));
        }
        String rest = "/api/" + item.substring("worklist://".length());
        check(offences, "read only, over REST", given().get(rest).jsonPath().getMap("$"),
            List.of("read " + item));

        assertThat(offences)
            .as("a caller without the write right in the scope is named the reading calls "
                + "alone, and an object whose open calls only read carries no waiting_for")
            .isEmpty();
    }

    // =======================================================================
    // The scope, the calls, and their expectations
    // =======================================================================

    /** A scope of this class, open to the member, with the vocabulary every chain needs. */
    private Scope stage(String slug, UUID id) {
        SurfaceFixture.publishScope(slug, id, "project", false);
        for (String view : Selector.VIEWS) {
            selectors.open(id, view);
        }
        vocabulary.declareStatus(id, OPEN, 1, true, false, false, false);
        vocabulary.declareStatus(id, DONE, 2, false, false, true, true);
        if (vocabulary.relationTypeByName(id, RELATION).isEmpty()) {
            vocabulary.declareRelationType(id, RELATION, false, 1);
        }
        workstreams.requireDefault(id);
        Map<String, Object> limits = Map.of(
            "max_planned_iterations", GENEROUS, "warn_planned_iterations", GENEROUS,
            "max_memberships_per_iteration", GENEROUS, "warn_memberships_per_iteration",
            GENEROUS);
        try {
            settings.create(id, limits);
        } catch (WorklistException alreadyThere) {
            // A rerun against the same database: the row stands.
            assertThat(alreadyThere.reason()).isEqualTo(WorklistException.Reason.SETTING_PRESENT);
        }
        TaggingIT.declareTag(id, TAG, "declared");
        return new Scope(slug, id);
    }

    /** One scope's addresses, its calls over MCP, and what each object has open. */
    private final class Scope {

        private final String slug;
        private final UUID id;
        private String target;

        Scope(String slug, UUID id) {
            this.slug = slug;
            this.id = id;
        }

        String slug() {
            return slug;
        }

        /** The iteration collection, the one address {@code advance} is named at. */
        String iterations() {
            return "worklist://" + slug + "/" + Selector.ITERATION;
        }

        void relateTo(String item) {
            this.target = item;
        }

        Map<String, Object> create(String view, Map<String, Object> fields) {
            return mcp("worklist_create", Map.of("scope", slug, "selector", view,
                "fields", fields));
        }

        Map<String, Object> read(String address) {
            return mcp("worklist_read", Map.of("address", address));
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> query(String view) {
            return (List<Map<String, Object>>) mcp("worklist_query", Map.of("scope", slug,
                "selector", view, "page_size", 200)).get("objects");
        }

        String token(String address) {
            return String.valueOf(read(address).get("conflict_token"));
        }

        Map<String, Object> mcp(String tool, Map<String, Object> arguments) {
            return Mcp.answer(Mcp.call(tool, arguments));
        }

        /**
         * Carries out one named call, {@code <tool> <address>}, with the
         * arguments it needs, and answers what it answered; a refusal fails
         * the call here.
         */
        Map<String, Object> execute(String call) {
            String tool = call.substring(0, call.indexOf(' '));
            String address = call.substring(call.indexOf(' ') + 1);
            Map<String, Object> arguments = new LinkedHashMap<>();
            switch (tool) {
                case "worklist_read" -> arguments.put("address", address);
                case "worklist_update" -> {
                    arguments.put("address", address);
                    arguments.put("conflict_token", token(address));
                    arguments.put("fields", changeAt(address));
                }
                case "worklist_close" -> {
                    arguments.put("address", address);
                    arguments.put("conflict_token", token(address));
                    if (address.contains("/" + Selector.ITERATION + "/")) {
                        arguments.put("produced", "what the iteration made");
                    }
                }
                case "worklist_plan" -> {
                    String membership = isMembership(address) ? address
                        : address + "/" + number(address(create(Selector.ITEM, Map.of(
                            "title", "appended", "status", OPEN,
                            "milestone", firstMilestoneNumber()))));
                    arguments.put("address", membership);
                    arguments.put("conflict_token", token(iterationOf(membership)));
                }
                case "worklist_unplan" -> {
                    arguments.put("address", address);
                    arguments.put("conflict_token", token(iterationOf(address)));
                }
                case "worklist_relate", "worklist_unrelate" -> {
                    arguments.put("address", address);
                    arguments.put("conflict_token", token(address));
                    arguments.put("to_item", target);
                    arguments.put("type", RELATION);
                }
                case "worklist_tag_attach", "worklist_tag_detach" -> {
                    arguments.put("address", address);
                    arguments.put("tag", TAG);
                }
                case "worklist_advance" -> {
                    arguments.put("scope", slug);
                    arguments.put("selector", Selector.ITERATION);
                    arguments.put("conflict_token",
                        String.valueOf(settings.read(id).get("conflict_token")));
                }
                default -> throw new AssertionError("a call this probe does not carry out: "
                    + call);
            }
            Response answered = Mcp.call(tool, arguments);
            assertThat(answered.jsonPath().getBoolean("result.isError"))
                .as("the named call %s is accepted: %s", call, answered.asString())
                .isFalse();
            return answered.jsonPath().getMap("result.structuredContent");
        }

        /** A change each kind of object takes through update. */
        private Map<String, Object> changeAt(String address) {
            if (isMembership(address)) {
                return Map.of("membership_status", "active");
            }
            if (address.contains("/" + Selector.ITERATION + "/")) {
                return Map.of("motto", "updated");
            }
            return Map.of("title", "updated");
        }

        private long firstMilestoneNumber() {
            return query(Selector.MILESTONE).stream()
                .filter(m -> !"closed".equals(((Map<?, ?>) m.get("fields")).get("status")))
                .map(m -> number(address(m)))
                .findFirst().orElseThrow();
        }

        // ---- what each object has open, written from the target cut --------

        List<String> item(String address, String... plans) {
            List<String> calls = new ArrayList<>(List.of(
                "worklist_read " + address, "worklist_update " + address,
                "worklist_relate " + address, "worklist_unrelate " + address,
                "worklist_tag_attach " + address, "worklist_tag_detach " + address));
            for (String membership : plans) {
                calls.add("worklist_plan " + membership);
            }
            return calls;
        }

        List<String> iteration(String address, boolean open, boolean advanceable) {
            if (!open) {
                return List.of("worklist_read " + address);
            }
            List<String> calls = new ArrayList<>(List.of(
                "worklist_read " + address, "worklist_update " + address,
                "worklist_close " + address, "worklist_plan " + address));
            if (advanceable) {
                calls.add("worklist_advance " + iterations());
            }
            return calls;
        }

        List<String> milestone(String address, boolean open) {
            return open
                ? List.of("worklist_read " + address, "worklist_update " + address,
                    "worklist_close " + address)
                : List.of("worklist_read " + address);
        }

        List<String> membership(String address, boolean open) {
            return open
                ? List.of("worklist_read " + address, "worklist_update " + address,
                    "worklist_unplan " + address)
                : List.of("worklist_read " + address);
        }
    }

    // =======================================================================

    private static void check(List<String> offences, String where, Map<String, Object> answer,
                              List<String> expected) {
        offences.addAll(AnswerChecks.nextOffences(where, answer, expected));
    }

    private static String address(Map<String, Object> answer) {
        return String.valueOf(answer.get("address"));
    }

    private static long number(String address) {
        return Long.parseLong(address.substring(address.lastIndexOf('/') + 1));
    }

    private static boolean isMembership(String address) {
        return address.substring("worklist://".length()).split("/").length == 4;
    }

    private static String iterationOf(String membership) {
        return membership.substring(0, membership.lastIndexOf('/'));
    }

    private static String verbOf(String call) {
        return call.substring(0, call.indexOf(' '));
    }

    private static Map<String, Object> entry(List<Map<String, Object>> page, String address) {
        return page.stream().filter(e -> address.equals(e.get("address"))).findFirst()
            .orElseThrow(() -> new AssertionError(address + " is not listed"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entryOf(Map<String, Object> page, String address) {
        return entry((List<Map<String, Object>>) page.get("objects"), address);
    }

    /** The same calls, as the REST surface names them: the verb without the scheme. */
    private static List<String> withoutPrefix(List<String> calls) {
        Function<String, String> rest = call -> call.substring("worklist_".length());
        return calls.stream().map(rest).toList();
    }
}
