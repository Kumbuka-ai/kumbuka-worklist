/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.ItemService;
import ai.kumbuka.worklist.domain.ScopeSettingService;
import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.domain.VocabularyRegistry;
import ai.kumbuka.worklist.domain.WorkstreamService;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;

/**
 * Every refusal {@code query} can produce, on REST and on the service MCP, read
 * for what its message and its {@code data} say (DEC-0042).
 *
 * <ul>
 * <li><strong>No value travels back.</strong> Each call carries a marked value
 *     where the refusal is about a value, and neither the message nor anything
 *     under {@code data} may contain it. A value a caller sends can be content,
 *     and a refusal is written to logs.</li>
 * <li><strong>The remedy is an instruction.</strong> The message carries no
 *     negating word, and its last sentence opens with the act to perform.
 *     A caller, above all a model, carries out "send direction as asc or
 *     desc" more reliably than it inverts "direction is not valid".</li>
 * </ul>
 *
 * <p>Each case also names the reason it expects, so a case that drifts onto
 * another refusal fails as such rather than reading the wrong message.
 * Excluded, as not the caller's to remedy: the server faults (a session the
 * service failed to bind answers 500), and the authentication challenge,
 * whose form the protocol fixes.
 *
 * <p>RED STATE, observed 2026-10-13 on {@code 4012fa1}: of 26 cases on REST
 * and 28 on the service MCP, the value came back in the message of 20 and 21
 * ("arrived as the text 'zqmarkerzq'", "the cursor '…' cannot be read"), and
 * in {@code data.offenders} of the scope-kind and the undeclared-view refusal
 * on both; 23 and 25 messages carried a negating word and did not end on an
 * act; 8 and 9 filter form faults answered {@code INVALID_VALUE}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class QueryRefusalWordingIT {

    /** The marked value: lower-case letters, so it passes every form a value is checked for. */
    private static final String MARKER = "zqmarkerzq";
    private static final String MARKER_UPPER = "ZQ_MARKER";
    private static final String MARKER_NUMBER = "987654321";
    private static final String MARKER_UUID = "0badc0de-0000-4000-8000-00000000beef";

    private static final String SCOPE = "wording-probe";
    private static final UUID SCOPE_ID = UUID.fromString("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d");

    /** A scope that declares the item view only. */
    private static final String SPARSE = "wording-sparse";
    private static final UUID SPARSE_ID = UUID.fromString("0b9c8d7e-6f5a-4b4c-9d3e-2f1a0b9c8d7e");

    /** A private scope, a kind this service does not serve. */
    private static final String PRIVATE = MARKER + "-private";
    private static final UUID PRIVATE_ID = UUID.fromString("1c0d9e8f-7a6b-4c5d-8e4f-3a2b1c0d9e8f");

    /** The words a remedy stated as an act does without. */
    private static final Pattern NEGATION = Pattern.compile(
        "(?i)\\b(not|no|never|without|cannot)\\b|n't\\b");

    /** The acts a remedy opens with. */
    private static final Set<String> ACTS = Set.of(
        "Send", "Call", "Use", "Name", "Write", "Pass", "Check", "Address", "Ask", "Retry",
        "Follow", "Start", "Leave", "Query");

    private static boolean staged;

    @Inject TestIdentityAssociation identity;
    @Inject ScopeSettingService settings;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject WorkstreamService workstreams;
    @Inject ItemService items;

    @BeforeEach
    void stageOnce() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);
        if (!staged) {
            stage();
            staged = true;
        }
    }

    // ======================================================================
    // The probe
    // ======================================================================

    @Test
    void no_refusal_of_query_on_rest_repeats_a_value_or_states_its_remedy_negatively() {
        SoftAssertions soft = new SoftAssertions();
        for (Case c : shared()) {
            check(soft, "REST " + c.label(), rest(c.scope(), c.view(), c.params(), null), c);
        }
        check(soft, "REST surface header", rest(SCOPE, Selector.ITEM, Map.of(), MARKER),
            new Case("header", "ARGUMENT_INVALID", SCOPE, Selector.ITEM, Map.of(), MARKER));
        check(soft, "REST repeated filter", rest(SCOPE, Selector.ITEM,
                Map.of("filter.status", List.of(MARKER, MARKER + "2")), null),
            new Case("repeated", "ARGUMENT_INVALID", SCOPE, Selector.ITEM, Map.of(), MARKER));
        soft.assertAll();
    }

    @Test
    void no_refusal_of_query_on_the_service_mcp_repeats_a_value_or_states_its_remedy_negatively() {
        SoftAssertions soft = new SoftAssertions();
        for (Case c : shared()) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("scope", c.scope());
            args.put("selector", c.view());
            Map<String, Object> filter = new LinkedHashMap<>();
            c.params().forEach((name, value) -> {
                if (name.startsWith("filter.")) {
                    filter.put(name.substring("filter.".length()), value);
                } else {
                    args.put(name, value);
                }
            });
            if (!filter.isEmpty()) {
                args.put("filter", filter);
            }
            check(soft, "MCP " + c.label(), mcp(args), c);
        }
        check(soft, "MCP filter as text", mcp(Map.of("scope", SCOPE, "selector", Selector.ITEM,
                "filter", MARKER)),
            new Case("filter text", "ARGUMENT_INVALID", SCOPE, Selector.ITEM, Map.of(), MARKER));
        check(soft, "MCP filter list", mcp(Map.of("scope", SCOPE, "selector", Selector.ITEM,
                "filter", Map.of("status", List.of(MARKER)))),
            new Case("filter list", "ARGUMENT_INVALID", SCOPE, Selector.ITEM, Map.of(), MARKER));
        check(soft, "MCP no scope", mcp(Map.of("selector", Selector.ITEM)),
            new Case("no scope", "ARGUMENT_MISSING", null, Selector.ITEM, Map.of()));
        check(soft, "MCP no selector", mcp(Map.of("scope", SCOPE)),
            new Case("no selector", "ARGUMENT_MISSING", SCOPE, null, Map.of()));
        soft.assertAll();
    }

    /** The refusals both transports produce, each with the value it must not repeat. */
    private List<Case> shared() {
        String itemCursor = cursorOf(Selector.ITEM);
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("scope not a label", "ADDRESS_MALFORMED", MARKER_UPPER,
            Selector.ITEM, Map.of(), MARKER_UPPER));
        cases.add(new Case("selector not a token", "ADDRESS_MALFORMED", SCOPE,
            "Zq" + MARKER, Map.of(), "Zq" + MARKER));
        cases.add(new Case("selector not a view", "ADDRESS_MALFORMED", SCOPE, MARKER,
            Map.of(), MARKER));
        cases.add(new Case("scope unresolved", "NOT_FOUND", MARKER, Selector.ITEM, Map.of(),
            MARKER));
        cases.add(new Case("scope of an unserved kind", "SCOPE_KIND_UNSUPPORTED", PRIVATE,
            Selector.ITEM, Map.of(), PRIVATE));
        cases.add(new Case("view undeclared", "SELECTOR_UNDECLARED", SPARSE,
            Selector.ITERATION, Map.of(), Selector.ITERATION));
        cases.add(new Case("unknown argument", "ARGUMENT_UNKNOWN", SCOPE, Selector.ITEM,
            Map.of("foo", MARKER), MARKER));
        cases.add(new Case("undeclared filter", "PREDICATE_UNKNOWN", SCOPE, Selector.ITEM,
            Map.of("filter.foo", MARKER), MARKER));
        // DEC-0043 R19: a name the call does not declare never comes back either.
        cases.add(new Case("marked argument name", "ARGUMENT_UNKNOWN", SCOPE, Selector.ITEM,
            Map.of(MARKER, "1"), MARKER));
        cases.add(new Case("marked filter name", "PREDICATE_UNKNOWN", SCOPE, Selector.ITEM,
            Map.of("filter." + MARKER, "1"), MARKER));
        cases.add(new Case("milestone form", "ARGUMENT_INVALID", SCOPE, Selector.ITEM,
            Map.of("filter.milestone", MARKER), MARKER));
        cases.add(new Case("status as an identity", "ARGUMENT_INVALID", SCOPE, Selector.ITEM,
            Map.of("filter.status", MARKER_UUID), MARKER_UUID));
        cases.add(new Case("status empty", "ARGUMENT_INVALID", SCOPE, Selector.ITEM,
            Map.of("filter.status", " ")));
        cases.add(new Case("closed form", "ARGUMENT_INVALID", SCOPE, Selector.ITERATION,
            Map.of("filter.closed", MARKER), MARKER));
        cases.add(new Case("milestone status outside the set", "ARGUMENT_INVALID", SCOPE,
            Selector.MILESTONE, Map.of("filter.status", MARKER), MARKER));
        cases.add(new Case("milestone kind outside the set", "ARGUMENT_INVALID", SCOPE,
            Selector.MILESTONE, Map.of("filter.kind", MARKER), MARKER));
        cases.add(new Case("workstream status outside the set", "ARGUMENT_INVALID", SCOPE,
            Selector.WORKSTREAM, Map.of("filter.status", MARKER), MARKER));
        cases.add(new Case("status undeclared", "VALUE_UNDECLARED", SCOPE, Selector.ITEM,
            Map.of("filter.status", MARKER), MARKER));
        cases.add(new Case("workstream undeclared", "VALUE_UNDECLARED", SCOPE, Selector.ITEM,
            Map.of("filter.workstream", MARKER), MARKER));
        cases.add(new Case("milestone undeclared", "VALUE_UNDECLARED", SCOPE, Selector.ITEM,
            Map.of("filter.milestone", MARKER_NUMBER), MARKER_NUMBER));
        cases.add(new Case("page size a word", "PAGE_SIZE_REJECTED", SCOPE, Selector.ITEM,
            Map.of("page_size", MARKER), MARKER));
        cases.add(new Case("page size too large", "PAGE_SIZE_REJECTED", SCOPE, Selector.ITEM,
            Map.of("page_size", MARKER_NUMBER), MARKER_NUMBER));
        cases.add(new Case("sort unknown", "SORT_UNDECLARED", SCOPE, Selector.ITEM,
            Map.of("sort", MARKER), MARKER));
        cases.add(new Case("direction", "ARGUMENT_INVALID", SCOPE, Selector.ITEM,
            Map.of("direction", MARKER), MARKER));
        cases.add(new Case("cursor malformed", "CURSOR_MALFORMED", SCOPE, Selector.ITEM,
            Map.of("after", MARKER), MARKER));
        cases.add(new Case("cursor foreign", "CURSOR_FOREIGN", SCOPE, Selector.MILESTONE,
            Map.of("after", itemCursor), itemCursor));
        return cases;
    }

    private static void check(SoftAssertions soft, String what, Refusal refusal, Case expected) {
        soft.assertThat(refusal.refused()).as("%s is refused", what).isTrue();
        soft.assertThat(refusal.reason()).as("%s reason", what).isEqualTo(expected.reason());
        for (String value : expected.values()) {
            soft.assertThat(refusal.message()).as("%s: the message repeats no value", what)
                .doesNotContainIgnoringCase(value);
            soft.assertThat(refusal.data()).as("%s: data repeats no value", what)
                .doesNotContainIgnoringCase(value);
        }
        soft.assertThat(NEGATION.matcher(refusal.message()).find())
            .as("%s: the message states what to do, without a negating word: %s",
                what, refusal.message())
            .isFalse();
        soft.assertThat(ACTS).as("%s: the last sentence opens with the act: %s",
                what, refusal.message())
            .contains(firstWordOfLastSentence(refusal.message()));
    }

    private static String firstWordOfLastSentence(String message) {
        String[] sentences = message.strip().split("(?<=[.:])\\s+(?=[A-Z])");
        String last = sentences[sentences.length - 1];
        return last.split("[\\s,]", 2)[0];
    }

    // ======================================================================
    // Transports
    // ======================================================================

    record Case(String label, String reason, String scope, String view,
                Map<String, Object> params, String... values) {
    }

    record Refusal(boolean refused, String reason, String message, String data) {

        static Refusal of(boolean refused, JsonPath body) {
            Object data = body.get("data");
            return new Refusal(refused, body.getString("reason"),
                String.valueOf(body.getString("message")), data == null ? "" : data.toString());
        }
    }

    private static Refusal rest(String scope, String view, Map<String, Object> params,
                                String surface) {
        var request = given().queryParams(params);
        if (surface != null) {
            request = request.header("Kumbuka-Surface", surface);
        }
        var response = request.when().get("/api/" + scope + "/" + view).then().extract();
        return Refusal.of(response.statusCode() >= 400, response.jsonPath());
    }

    private static Refusal mcp(Map<String, Object> arguments) {
        JsonPath rpc = given()
            .contentType(ContentType.JSON)
            .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", "query", "arguments", arguments)))
            .when().post("/mcp")
            .then().statusCode(200)
            .extract().jsonPath();
        String text = rpc.getString("result.content[0].text");
        return Refusal.of(rpc.getBoolean("result.isError"), JsonPath.from(text));
    }

    private static String cursorOf(String view) {
        return given().queryParam("page_size", 1)
            .when().get("/api/" + SCOPE + "/" + view)
            .then().statusCode(200)
            .extract().jsonPath().getString("next_cursor");
    }

    // ======================================================================
    // Staging
    // ======================================================================

    private void stage() {
        SurfaceFixture.publishScope(SCOPE, SCOPE_ID, "project", false);
        settings.create(SCOPE_ID, Map.of(
            "max_planned_iterations", 10,
            "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10,
            "warn_memberships_per_iteration", 9));
        for (String view : Selector.VIEWS) {
            selectors.open(SCOPE_ID, view);
        }
        vocabulary.declareStatus(SCOPE_ID, "open", 1, true, false, false, false);
        workstreams.requireDefault(SCOPE_ID);
        for (int i = 0; i < 2; i++) {
            items.create(SCOPE_ID, Map.of("title", "worded " + i, "status", "open"));
        }

        SurfaceFixture.publishScope(SPARSE, SPARSE_ID, "project", false);
        selectors.open(SPARSE_ID, Selector.ITEM);

        SurfaceFixture.publishScope(PRIVATE, PRIVATE_ID, "private", false);
    }
}
