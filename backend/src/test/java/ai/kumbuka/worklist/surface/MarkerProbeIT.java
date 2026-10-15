/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.ItemService;
import ai.kumbuka.worklist.domain.IterationService;
import ai.kumbuka.worklist.domain.MilestoneService;
import ai.kumbuka.worklist.domain.ScopeSettingService;
import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.domain.VocabularyRegistry;
import ai.kumbuka.worklist.domain.WorkstreamService;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * The marker probe of DEC-0043 R18 and R19, over every verb on both paths.
 *
 * <p>A marked value is put in every place a caller can put one -- the protocol
 * method, the tool name, an argument's name and its value, every part of an
 * address, a field's name and its value, an entry of a list, a header, the raw
 * body -- and each refusal the place can provoke is provoked. The marker must
 * appear nowhere in the answer: not in {@code message}, not in any member of
 * {@code data}, not in the protocol envelope around it. The probe reads the
 * whole answer as text, so a member nobody thought to check is checked too.
 *
 * <p>The tenant's vocabulary is the second marker. The scope is staged with a
 * status, a workstream, an attribute, an option and a relation type whose names
 * carry it, and the refusals that concern those values must not name them
 * either: R18 binds the tenant's vocabulary as it binds the caller's input.
 *
 * <p>{@code QueryRefusalWordingIT} holds the same probe for {@code query} alone,
 * with its wording checks; this class carries it to every other verb and to the
 * protocol layer. What a call the caller made to read answers -- an object and
 * its fields -- is outside R18 and outside this probe; the {@code warnings} of a
 * planning answer are notices and inside it.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class MarkerProbeIT {

    /** The caller's marker. Lower case so it is a valid scope label and token. */
    private static final String MARKER = "zqmarkerzq";

    /** The tenant's marker, carried by every name the scope declares. */
    private static final String TENANT = "zqtenantzq";

    private static final String MARKER_UUID = "0badc0de-0000-4000-8000-00000000beef";

    private static final String SCOPE = "marker-probe";
    private static final UUID SCOPE_ID = UUID.fromString("5d4c3b2a-1f0e-4d9c-8b7a-6f5e4d3c2b1a");

    private static final String OPEN = TENANT + "-open";
    private static final String DONE = TENANT + "-done";
    private static final String WORKSTREAM = TENANT + "-ws";
    private static final String ATTRIBUTE = TENANT + "_attr";
    private static final String OPTION = TENANT + "-option";
    private static final String RELATION = TENANT + "-rel";

    private static boolean staged;

    @Inject TestIdentityAssociation identity;
    @Inject ScopeSettingService settings;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject WorkstreamService workstreams;
    @Inject ItemService items;
    @Inject IterationService iterations;
    @Inject MilestoneService milestones;

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
    // The protocol layer
    // ======================================================================

    @Test
    void the_protocol_layer_answers_an_invented_name_with_the_declared_ones() {
        SoftAssertions soft = new SoftAssertions();

        String method = rpc(Map.of("jsonrpc", "2.0", "id", 1, "method", MARKER));
        absent(soft, "unknown method", method);
        soft.assertThat(method).as("the declared methods are named").contains("tools/call");

        String tool = mcpRaw(MARKER, Map.of());
        absent(soft, "unknown tool", tool);
        soft.assertThat(tool).as("the declared tools are named").contains("query");

        soft.assertAll();
    }

    // ======================================================================
    // Every tool, with a marker in every argument place
    // ======================================================================

    @Test
    void no_tool_repeats_a_marked_argument_name_or_value() {
        SoftAssertions soft = new SoftAssertions();
        String item = address(Selector.ITEM, 1);
        String other = address(Selector.ITEM, 2);
        String iteration = address(Selector.ITERATION, 1);
        String milestone = address(Selector.MILESTONE, 1);
        String membership = "worklist://" + SCOPE + "/iteration/1/1";

        // An invented argument name, with a marked value, on every declared tool.
        Map<String, Map<String, Object>> minimal = new LinkedHashMap<>();
        minimal.put("create", Map.of("scope", SCOPE, "selector", Selector.ITEM));
        minimal.put("read", Map.of("address", item));
        minimal.put("update", Map.of("address", item, "conflict_token", "t"));
        minimal.put("query", Map.of("scope", SCOPE, "selector", Selector.ITEM));
        minimal.put("accept", Map.of("address", item, "conflict_token", "t"));
        minimal.put("withdraw", Map.of("address", item, "conflict_token", "t"));
        minimal.put("close", Map.of("address", milestone, "conflict_token", "t"));
        minimal.put("advance", Map.of("scope", SCOPE, "selector", Selector.ITERATION));
        minimal.put("plan", Map.of("address", membership, "conflict_token", "t"));
        minimal.put("unplan", Map.of("address", membership, "conflict_token", "t"));
        minimal.put("claim", Map.of("address", item));
        minimal.put("release", Map.of("address", item));
        minimal.put("claim_next", Map.of("scope", SCOPE, "selector", Selector.ITEM));
        minimal.put("relate", Map.of("address", item, "conflict_token", "t"));
        minimal.put("unrelate", Map.of("address", item, "conflict_token", "t"));
        minimal.put("validate", Map.of("scope", SCOPE, "selector", Selector.ITEM));
        minimal.forEach((tool, args) -> {
            Map<String, Object> with = new LinkedHashMap<>(args);
            with.put(MARKER, MARKER);
            String answer = mcpRaw(tool, with);
            absent(soft, tool + " with an invented argument", answer);
            soft.assertThat(answer).as("%s refuses it as ARGUMENT_UNKNOWN", tool)
                .contains("ARGUMENT_UNKNOWN");
        });

        // Every address part.
        for (String address : List.of(MARKER, "worklist://" + MARKER + "/item/1",
                "worklist://" + SCOPE + "/" + MARKER + "/1",
                "worklist://" + SCOPE + "/item/" + MARKER,
                "worklist://" + SCOPE + "/iteration/1/" + MARKER,
                "worklist://" + SCOPE + "/item/1/2/" + MARKER,
                "zq" + MARKER + "://" + SCOPE + "/item/1")) {
            absent(soft, "read at " + address, mcpRaw("read", Map.of("address", address)));
        }
        for (String view : List.of(MARKER, "Zq" + MARKER)) {
            absent(soft, "create in view " + view, mcpRaw("create",
                Map.of("scope", SCOPE, "selector", view, "fields", Map.of("title", "x"))));
        }
        absent(soft, "create in scope", mcpRaw("create",
            Map.of("scope", MARKER, "selector", Selector.ITEM, "fields", Map.of("title", "x"))));

        // The fields of a write: a name, and every value that names something.
        Map<String, Object> base = Map.of("title", "marked", "status", OPEN);
        List<Map<String, Object>> fieldCases = List.of(
            with(base, MARKER, MARKER),
            with(base, "status", MARKER),
            with(base, "status", MARKER_UUID),
            with(base, "workstream", MARKER),
            with(base, "milestone", MARKER),
            with(base, "attributes", Map.of(MARKER, MARKER)),
            with(base, "attributes", Map.of(ATTRIBUTE, MARKER)),
            with(base, "attributes", MARKER),
            with(base, "relations", List.of(Map.of("type", MARKER, "item", other))),
            with(base, "relations", List.of(Map.of("type", RELATION, "item", MARKER))),
            with(base, "relations", List.of(Map.of("type", RELATION,
                "item", "worklist://" + MARKER + "/item/1"))),
            with(base, "relations", List.of(Map.of("type", RELATION,
                "item", "worklist://" + SCOPE + "/item/" + MARKER))),
            with(base, "relations", MARKER),
            with(base, "references", List.of(Map.of(MARKER, MARKER))),
            with(base, "references", List.of(MARKER)));
        for (Map<String, Object> fields : fieldCases) {
            absent(soft, "create item " + fields.keySet(), mcpRaw("create",
                Map.of("scope", SCOPE, "selector", Selector.ITEM, "fields", fields)));
        }
        absent(soft, "create with fields as text", mcpRaw("create",
            Map.of("scope", SCOPE, "selector", Selector.ITEM, "fields", MARKER)));
        absent(soft, "create milestone with a marked status", mcpRaw("create",
            Map.of("scope", SCOPE, "selector", Selector.MILESTONE,
                "fields", Map.of("title", "x", "status", MARKER))));
        absent(soft, "create milestone with a marked kind", mcpRaw("create",
            Map.of("scope", SCOPE, "selector", Selector.MILESTONE,
                "fields", Map.of("title", "x", "kind", MARKER))));
        absent(soft, "create iteration with a marked rank", mcpRaw("create",
            Map.of("scope", SCOPE, "selector", Selector.ITERATION,
                "fields", Map.of("title", "x", "motto", "m", "description", "d",
                    "rank", MARKER))));

        // The arguments of the transitions and the graph verbs.
        absent(soft, "update with a marked token", mcpRaw("update",
            Map.of("address", item, "conflict_token", MARKER, "fields", Map.of("title", "y"))));
        absent(soft, "withdraw into a marked status", mcpRaw("withdraw",
            Map.of("address", item, "conflict_token", tokenOf(item), "status", MARKER)));
        absent(soft, "withdraw into an open status", mcpRaw("withdraw",
            Map.of("address", item, "conflict_token", tokenOf(item), "status", OPEN)));
        absent(soft, "claim with a marked duration", mcpRaw("claim",
            Map.of("address", item, "duration_seconds", MARKER)));
        absent(soft, "release with a marked receipt", mcpRaw("release",
            Map.of("address", item, "receipt", MARKER)));
        absent(soft, "relate to a marked item", mcpRaw("relate",
            Map.of("address", item, "conflict_token", tokenOf(item),
                "to_item", MARKER, "type", RELATION)));
        absent(soft, "relate with a marked type", mcpRaw("relate",
            Map.of("address", item, "conflict_token", tokenOf(item),
                "to_item", other, "type", MARKER)));
        absent(soft, "unrelate an absent edge", mcpRaw("unrelate",
            Map.of("address", item, "conflict_token", tokenOf(item),
                "to_item", other, "type", RELATION)));
        absent(soft, "close an iteration with live memberships", mcpRaw("close",
            Map.of("address", iteration, "conflict_token", tokenOf(iteration),
                "produced", MARKER)));
        absent(soft, "plan an item twice", mcpRaw("plan",
            Map.of("address", membership, "conflict_token", tokenOf(iteration))));
        absent(soft, "an uncarried verb on a marked scope", mcpRaw("send",
            Map.of("scope", MARKER, "selector", Selector.ITEM)));
        absent(soft, "advance with a marked token", mcpRaw("advance",
            Map.of("scope", SCOPE, "selector", Selector.ITERATION, "conflict_token", MARKER)));

        soft.assertAll();
    }

    // ======================================================================
    // The REST surface
    // ======================================================================

    @Test
    void no_rest_call_repeats_a_marked_value() {
        SoftAssertions soft = new SoftAssertions();
        String itemPath = "/api/" + SCOPE + "/" + Selector.ITEM;

        absent(soft, "a body that is no JSON", post(itemPath, "{\"" + MARKER + "\": " + MARKER));
        absent(soft, "a field name", post(itemPath, Map.of("title", "x", MARKER, MARKER)));
        absent(soft, "a status", post(itemPath, Map.of("title", "x", "status", MARKER)));
        absent(soft, "a collection verb", post(itemPath + ":" + MARKER, Map.of()));
        absent(soft, "an item verb", post(itemPath + "/1:" + MARKER, Map.of()));
        absent(soft, "a body argument", post(itemPath + "/1:withdraw", Map.of(MARKER, MARKER)));
        absent(soft, "a lease", post(itemPath + "/1:claim", Map.of("duration_seconds", MARKER)));
        absent(soft, "a receipt", post(itemPath + "/1:release", Map.of("receipt", MARKER)));
        absent(soft, "an edge", post(itemPath + "/1:relate", Map.of("to_item", MARKER,
            "type", MARKER)));
        absent(soft, "a stale token", given().contentType(ContentType.JSON)
            .header("If-Match", "\"" + MARKER + "\"").body(Map.of("title", "y"))
            .when().patch(itemPath + "/1").then().extract().asString());
        absent(soft, "a query parameter name", given().queryParam(MARKER, MARKER)
            .when().get(itemPath).then().extract().asString());
        absent(soft, "a filter name", given().queryParam("filter." + MARKER, MARKER)
            .when().get(itemPath).then().extract().asString());
        absent(soft, "a surface header", given().header("Kumbuka-Surface", MARKER)
            .when().get(itemPath).then().extract().asString());
        absent(soft, "a scope", given().when().get("/api/" + MARKER + "/item/1")
            .then().extract().asString());
        absent(soft, "a view", given().when().get("/api/" + SCOPE + "/" + MARKER + "/1")
            .then().extract().asString());
        absent(soft, "a number", given().when().get(itemPath + "/" + MARKER)
            .then().extract().asString());

        soft.assertAll();
    }

    // ======================================================================
    // Notices
    // ======================================================================

    @Test
    void a_planning_notice_repeats_nothing_the_caller_or_the_tenant_wrote() {
        // The scope warns at one membership: planning the second item provokes
        // the notice. The item's own title carries the marker, and the
        // notice must not.
        Map<String, Object> created = items.create(SCOPE_ID,
            Map.of("title", MARKER, "status", OPEN, "milestone", 1));
        Object number = created.get("number");
        String iteration = address(Selector.ITERATION, 1);
        ExtractableResponse<Response> planned = given().contentType(ContentType.JSON)
            .body(rpcCall("plan", Map.of(
                "address", "worklist://" + SCOPE + "/iteration/1/" + number,
                "conflict_token", tokenOf(iteration))))
            .when().post("/mcp").then().statusCode(200).extract();

        List<String> warnings = planned.jsonPath()
            .getList("result.structuredContent.fields.warnings", String.class);
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(warnings).as("the notice is provoked").isNotEmpty();
        for (String warning : warnings) {
            soft.assertThat(warning).doesNotContainIgnoringCase(MARKER)
                .doesNotContainIgnoringCase(TENANT);
        }
        soft.assertAll();
    }

    // ======================================================================
    // Checks and transports
    // ======================================================================

    private static void absent(SoftAssertions soft, String what, String answer) {
        soft.assertThat(answer).as("%s: the caller's marker is absent from the answer", what)
            .doesNotContainIgnoringCase(MARKER);
        soft.assertThat(answer).as("%s: the tenant's vocabulary is absent from the answer", what)
            .doesNotContainIgnoringCase(TENANT);
        soft.assertThat(answer).as("%s: the marked identity is absent from the answer", what)
            .doesNotContainIgnoringCase(MARKER_UUID);
    }

    private static Map<String, Object> with(Map<String, Object> base, String name, Object value) {
        Map<String, Object> fields = new LinkedHashMap<>(base);
        fields.put(name, value);
        return fields;
    }

    private static String address(String view, Object number) {
        return "worklist://" + SCOPE + "/" + view + "/" + number;
    }

    private static Map<String, Object> rpcCall(String tool, Map<String, Object> arguments) {
        return Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
            "params", Map.of("name", tool, "arguments", arguments));
    }

    private static String rpc(Map<String, Object> envelope) {
        return given().contentType(ContentType.JSON).body(envelope)
            .when().post("/mcp").then().extract().asString();
    }

    private static String mcpRaw(String tool, Map<String, Object> arguments) {
        return rpc(rpcCall(tool, arguments));
    }

    private static String tokenOf(String address) {
        return given().contentType(ContentType.JSON)
            .body(rpcCall("read", Map.of("address", address)))
            .when().post("/mcp").then().statusCode(200)
            .extract().jsonPath().getString("result.structuredContent.fields.conflict_token");
    }

    private static String post(String path, Object body) {
        Object sent = body instanceof String text ? text : body;
        return given().contentType(ContentType.JSON).body(sent)
            .when().post(path).then().extract().asString();
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
            "warn_memberships_per_iteration", 1));
        for (String view : Selector.VIEWS) {
            selectors.declare(SCOPE_ID, view);
        }
        vocabulary.declareStatus(SCOPE_ID, OPEN, 1, true, false, false, false);
        vocabulary.declareStatus(SCOPE_ID, DONE, 2, false, false, true, true);
        workstreams.requireDefault(SCOPE_ID);
        workstreams.declare(SCOPE_ID, WORKSTREAM, "a workstream named by the tenant");
        vocabulary.declareAttribute(SCOPE_ID, ATTRIBUTE, TENANT + " attribute", "choice", 1,
            false);
        vocabulary.declareOption(SCOPE_ID, ATTRIBUTE, OPTION, 1);
        vocabulary.declareRelationType(SCOPE_ID, RELATION, true, 1);

        Map<String, Object> milestone = milestones.create(SCOPE_ID,
            Map.of("title", "goal", "kind", "milestone"));
        List<Object> numbers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            numbers.add(items.create(SCOPE_ID, Map.of("title", "marked " + i, "status", OPEN,
                "milestone", milestone.get("number"))).get("number"));
        }
        iterations.create(SCOPE_ID, Map.of("title", "first", "motto", "m", "description", "d"));
        given().contentType(ContentType.JSON)
            .body(rpcCall("plan", Map.of(
                "address", "worklist://" + SCOPE + "/iteration/1/" + numbers.get(0),
                "conflict_token", tokenOf(address(Selector.ITERATION, 1)))))
            .when().post("/mcp").then().statusCode(200);
    }
}
