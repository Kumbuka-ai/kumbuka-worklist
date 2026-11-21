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
 * cut and REA-0011 Annex A1, never read from the service:
 * <ul>
 *   <li>an item, by its phase (REA-0011 D6, acceptance K8): a draft
 *       {@code read}, {@code update}, {@code relate}, {@code unrelate},
 *       {@code tag_attach}, {@code tag_detach}, {@code submit},
 *       {@code discard}; an open item {@code read}, {@code update},
 *       {@code relate}, {@code unrelate}, {@code tag_attach},
 *       {@code tag_detach}, {@code book}, {@code start} where its status
 *       answers {@code actionable} and it carries a type, {@code close}, and
 *       {@code plan} into each open iteration it is no member of, whatever its
 *       milestone (TAR-0002 B9); an item in progress the same with
 *       {@code return} in place of {@code start}; a closed item {@code read}
 *       alone. The addendum and the narrower calls of the phase in progress
 *       are later steps (S10, S11) and are not expected here. A closed item
 *       still takes {@code book}, which it does not name (Annex A1). A draft
 *       and a closed item are named no {@code plan}, which they are refused;</li>
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
    private static final String RESTING = "oc-resting";
    private static final String RUNNING = "oc-running";
    private static final String RELATION = "oc-relates";
    private static final String TAG = "oc-tag";

    /** The attribute an item's type is stored under (TAR-0002 B18). */
    private static final String TYPE = "type";

    /** The calls that move an item's phase, carried out last in a state. */
    private static final List<String> TRANSITIONS = List.of(
        "worklist_submit", "worklist_discard", "worklist_start", "worklist_return",
        "worklist_close");

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

        // ---- items: a draft, each of its calls, then submitted -------------
        String path = address(at.create(Selector.MILESTONE, Map.of("title", "the path")));
        String b = address(at.open(Map.of("title", "without a milestone", "status", OPEN)));
        at.relateTo(b);
        Map<String, Object> drafted = at.create(Selector.ITEM, Map.of("title", "plannable",
            "status", OPEN, "milestone", number(path),
            "criteria", List.of(Map.of("text", "the criterion book reports on"))));
        String a = address(drafted);
        check(offences, "item draft, as created", drafted, at.draft(a));
        check(offences, "item draft, read", at.read(a), at.draft(a));
        for (String call : at.draft(a)) {
            if (!isTransition(call)) {
                check(offences, "item draft after " + verbOf(call), at.execute(call),
                    at.draft(a));
            }
        }
        assertThat(Mcp.reason(Mcp.call("worklist_book", at.booking(a))))
            .as("a draft is named no book, and a booking of it is refused by its state")
            .isEqualTo("TRANSITION_REFUSED");
        check(offences, "item open, as submitted", at.execute("worklist_submit " + a),
            at.item(a, false));

        // A second draft is discarded: the named call is accepted, its answer
        // names no call, and nothing stands at the address any more.
        String gone = address(at.create(Selector.ITEM, Map.of("title", "discarded")));
        check(offences, "item discarded, as discarded", at.execute("worklist_discard " + gone),
            List.of());
        assertThat(Mcp.reason(Mcp.call("worklist_read", Map.of("address", gone))))
            .as("a discarded draft is gone")
            .isEqualTo("NOT_FOUND");

        // ---- items: two more that may enter an iteration, one that may not -
        String c = address(at.open(Map.of(
            "title", "resting: not actionable, without a milestone", "status", RESTING)));
        String d = address(at.closed(Map.of("title", "closed from the start",
            "status", OPEN)));
        String e = address(at.create(Selector.ITEM, Map.of("title", "a draft, never planned")));

        // ---- two open iterations, no current one ---------------------------
        Map<String, Object> first = at.create(Selector.ITERATION, Map.of("title", "first",
            "motto", "first", "description", "the first of two"));
        String i1 = address(first);
        String i2 = address(at.create(Selector.ITERATION, Map.of("title", "second",
            "motto", "second", "description", "the second of two")));
        check(offences, "iteration open, as created", first, at.iteration(i1, true, true));
        check(offences, "iteration open, read", at.read(i2), at.iteration(i2, true, true));

        check(offences, "item, plannable into both", at.read(a),
            at.item(a, false, i1 + "/" + number(a), i2 + "/" + number(a)));
        check(offences, "item without a milestone, plannable into both", at.read(b),
            at.item(b, false, i1 + "/" + number(b), i2 + "/" + number(b)));
        check(offences, "item, as listed", entry(at.query(Selector.ITEM), a),
            at.item(a, false, i1 + "/" + number(a), i2 + "/" + number(a)));

        // The item that is neither actionable nor on a milestone is named plan
        // into both open iterations, and each named plan is accepted.
        check(offences, "item not actionable, without a milestone, plannable into both",
            at.read(c), at.item(c, false, i1 + "/" + number(c), i2 + "/" + number(c)));
        for (String call : List.of("worklist_plan " + i1 + "/" + number(c),
                "worklist_plan " + i2 + "/" + number(c))) {
            at.execute(call);
        }
        check(offences, "item not actionable, planned into both", at.read(c),
            at.item(c, false));
        // Taken out of the first again, so that its close below meets no live
        // membership; the dropped row keeps it a member, and no plan is named.
        at.execute("worklist_unplan " + i1 + "/" + number(c));
        check(offences, "item not actionable, unplanned from the first", at.read(c),
            at.item(c, false));

        // The closed item and the draft are named no plan, and a plan of either
        // is refused: plan is named exactly where plan is accepted.
        check(offences, "item closed, never planned", at.read(d), at.closedItem(d));
        check(offences, "item draft, never planned", at.read(e), at.draft(e));
        for (String unplannable : List.of(d, e)) {
            Response refused = Mcp.call("worklist_plan", Map.of(
                "address", i1 + "/" + number(unplannable), "conflict_token", at.token(i1)));
            assertThat(refused.jsonPath().getBoolean("result.isError"))
                .as("a plan of %s is refused: %s", unplannable, refused.asString())
                .isTrue();
            assertThat(refused.jsonPath().getString("result.structuredContent.reason"))
                .as("a draft and a closed item enter no iteration (TAR-0002 B9)")
                .isEqualTo("ITEM_UNPLANNABLE");
        }

        // Every call the plannable item names is carried out, in this order:
        // relate before unrelate, so that the edge unrelate takes stands. The
        // transition is carried out last, further below.
        for (String call : at.item(a, false, i1 + "/" + number(a), i2 + "/" + number(a))) {
            if (!isTransition(call)) {
                at.execute(call);
            }
        }
        check(offences, "item, planned into both", at.read(a), at.item(a, false));

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

        // ---- the item through its phases: started, returned, closed --------
        at.typed(a);
        check(offences, "item open, typed: start is named", at.read(a), at.item(a, true));
        check(offences, "item in progress, as started", at.execute("worklist_start " + a),
            at.inProgress(a));
        for (String call : at.inProgress(a)) {
            if (!isTransition(call)) {
                check(offences, "item in progress after " + verbOf(call), at.execute(call),
                    at.inProgress(a));
            }
        }
        check(offences, "item open, as returned", at.execute("worklist_return " + a),
            at.item(a, true));
        at.execute("worklist_start " + a);
        check(offences, "item closed, as closed from in progress",
            at.execute("worklist_close " + a), at.closedItem(a));
        check(offences, "item closed, read", at.read(a), at.closedItem(a));

        // A closed item opens read alone and refuses every write by its state,
        // and still takes a booking, which it does not name (Annex A1, S13).
        Response update = Mcp.call("worklist_update", Map.of("address", a,
            "conflict_token", at.token(a), "fields", Map.of("title", "after the close")));
        assertThat(Mcp.reason(update)).isEqualTo("TRANSITION_REFUSED");
        Mcp.answer(Mcp.call("worklist_book", at.booking(a)));
        check(offences, "item closed, booked", at.read(a), at.closedItem(a));

        check(offences, "item without a milestone, the one open iteration beside it",
            at.read(b), at.item(b, false, i2 + "/" + number(b)));
        check(offences, "item closed, the one open iteration beside it", at.read(d),
            at.closedItem(d));

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
        String item = address(at.open(Map.of("title", "named", "status", OPEN)));
        String rest = "/api/" + item.substring("worklist://".length());
        List<String> offences = new ArrayList<>();

        check(offences, "over MCP", at.read(item), at.item(item, false));
        check(offences, "over REST", given().get(rest).jsonPath().getMap("$"),
            withoutPrefix(at.item(item, false)));
        check(offences, "over REST as the assistant",
            given().header("Kumbuka-Surface", "assistant").get(rest).jsonPath().getMap("$"),
            at.item(item, false));
        Map<String, Object> page = given().get("/api/" + at.slug() + "/" + Selector.ITEM)
            .jsonPath().getMap("$");
        check(offences, "a REST listing entry", entryOf(page, item),
            withoutPrefix(at.item(item, false)));

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
        String item = address(at.open(Map.of("title", "read only",
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
        vocabulary.declareStatus(id, RESTING, 3, false, false, false, false);
        vocabulary.declareStatus(id, RUNNING, 4, true, true, false, false);
        vocabulary.declareAttribute(id, TYPE, "Type", "text", 1, false);
        if (vocabulary.relationTypeByName(id, RELATION).isEmpty()) {
            vocabulary.declareRelationType(id, RELATION, false, 1);
        }
        workstreams.requireDefault(id);
        try {
            settings.create(id, Map.of());
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

        /** An item created and submitted: open. */
        Map<String, Object> open(Map<String, Object> fields) {
            return Submitted.overMcp(create(Selector.ITEM, fields));
        }

        /** An item created, submitted and closed into the closed status. */
        Map<String, Object> closed(Map<String, Object> fields) {
            String item = address(open(fields));
            return mcp("worklist_close", Map.of("address", item, "conflict_token", token(item),
                "status", DONE));
        }

        /** Gives an item a type, the value of its attribute {@code type}. */
        void typed(String item) {
            mcp("worklist_update", Map.of("address", item, "conflict_token", token(item),
                "fields", Map.of("attributes", Map.of(TYPE, "task"))));
        }

        /** The arguments of a booking on an item's first criterion. */
        Map<String, Object> booking(String item) {
            return Map.of("address", item, "instance", "https://instances.example/run/1",
                "entries", List.of(Map.of("criterion", 1, "status", "met",
                    "evidence", "a report of the instance")));
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
                    if (address.contains("/" + Selector.ITEM + "/")) {
                        // Mandatory where an item leaves the phase in progress.
                        arguments.put("reason", "the work is finished");
                    }
                }
                case "worklist_submit", "worklist_discard", "worklist_start" -> {
                    arguments.put("address", address);
                    arguments.put("conflict_token", token(address));
                }
                case "worklist_return" -> {
                    arguments.put("address", address);
                    arguments.put("conflict_token", token(address));
                    arguments.put("reason", "back to the pool");
                }
                case "worklist_plan" -> {
                    String membership = isMembership(address) ? address
                        : address + "/" + number(address(open(Map.of(
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
                case "worklist_book" -> arguments.putAll(booking(address));
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

        /** A draft (Annex A1): its writes, submit and discard. */
        List<String> draft(String address) {
            return List.of(
                "worklist_read " + address, "worklist_update " + address,
                "worklist_relate " + address, "worklist_unrelate " + address,
                "worklist_tag_attach " + address, "worklist_tag_detach " + address,
                "worklist_submit " + address, "worklist_discard " + address);
        }

        /**
         * An open item (Annex A1): its writes, book, start where it may be
         * started, close, and plan into each open iteration with room it is
         * no member of.
         */
        List<String> item(String address, boolean startable, String... plans) {
            List<String> calls = new ArrayList<>(List.of(
                "worklist_read " + address, "worklist_update " + address,
                "worklist_relate " + address, "worklist_unrelate " + address,
                "worklist_tag_attach " + address, "worklist_tag_detach " + address,
                "worklist_book " + address));
            if (startable) {
                calls.add("worklist_start " + address);
            }
            calls.add("worklist_close " + address);
            for (String membership : plans) {
                calls.add("worklist_plan " + membership);
            }
            return calls;
        }

        /**
         * An item in progress (Annex A1, without the narrowing of S11): its
         * writes, book, return, close, and plan as an open item has it.
         */
        List<String> inProgress(String address, String... plans) {
            List<String> calls = new ArrayList<>(List.of(
                "worklist_read " + address, "worklist_update " + address,
                "worklist_relate " + address, "worklist_unrelate " + address,
                "worklist_tag_attach " + address, "worklist_tag_detach " + address,
                "worklist_book " + address, "worklist_return " + address,
                "worklist_close " + address));
            for (String membership : plans) {
                calls.add("worklist_plan " + membership);
            }
            return calls;
        }

        /** A closed item (D6): read alone. */
        List<String> closedItem(String address) {
            return List.of("worklist_read " + address);
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

    /** Whether a named call moves the item's phase, and so is carried out on its own. */
    private static boolean isTransition(String call) {
        return TRANSITIONS.contains(verbOf(call));
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
