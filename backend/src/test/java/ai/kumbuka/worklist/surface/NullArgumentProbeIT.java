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
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * The probe of DEC-0043 R17: JSON {@code null} on an argument is the argument
 * left out, on every path.
 *
 * <p>Every declared argument of every tool is sent once as {@code null} and once
 * not at all, the other arguments of the call being the same, and the two
 * answers are compared whole. A mandatory argument answers
 * {@code ARGUMENT_MISSING} naming it both times; an optional one answers as its
 * default both times. The REST bodies get the same treatment, with the absent
 * body as a third spelling of the same call.
 *
 * <p>The second half reads the declaration: every optional argument names its
 * default and says that {@code null} does the same as leaving it out.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class NullArgumentProbeIT {

    private static final String SCOPE = "null-probe";
    private static final UUID SCOPE_ID = UUID.fromString("6e5d4c3b-2a1f-4e0d-9c8b-7a6f5e4d3c2b");

    private static boolean staged;

    @Inject TestIdentityAssociation identity;
    @Inject ScopeSettingService settings;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject WorkstreamService workstreams;
    @Inject ItemService items;
    @Inject IterationService iterations;

    @BeforeEach
    void stageOnce() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);
        if (!staged) {
            stage();
            staged = true;
        }
    }

    @Test
    void every_tool_answers_a_null_argument_as_the_argument_left_out() {
        String item = "worklist://" + SCOPE + "/item/1";
        String iteration = "worklist://" + SCOPE + "/iteration/1";
        String membership = "worklist://" + SCOPE + "/iteration/1/1";

        // One call per tool whose answer does not change the store, so that the
        // two spellings meet the same state: each is refused, or is a read.
        Map<String, Map<String, Object>> calls = new LinkedHashMap<>();
        calls.put("create", args("scope", SCOPE, "selector", Selector.ITEM, "fields", Map.of()));
        calls.put("read", args("address", item));
        calls.put("update", args("address", item, "conflict_token", "stale",
            "fields", Map.of("title", "x")));
        calls.put("query", args("scope", SCOPE, "selector", Selector.ITEM,
            "filter", Map.of("status", "open"), "page_size", 1, "after", null,
            "sort", "id", "direction", "asc"));
        calls.put("accept", args("address", item, "conflict_token", "stale"));
        calls.put("withdraw", args("address", item, "conflict_token", "stale", "status", "done"));
        calls.put("close", args("address", iteration, "conflict_token", "stale",
            "produced", "a release"));
        calls.put("advance", args("scope", SCOPE, "selector", Selector.ITERATION,
            "conflict_token", "stale"));
        calls.put("plan", args("address", membership, "conflict_token", "stale"));
        calls.put("unplan", args("address", membership, "conflict_token", "stale"));
        calls.put("claim", args("address", item, "duration_seconds", 0));
        calls.put("release", args("address", item, "receipt", "nobody-minted-this"));
        calls.put("claim_next", args("scope", SCOPE, "selector", Selector.ITEM,
            "duration_seconds", 0));
        calls.put("relate", args("address", item, "conflict_token", "stale",
            "to_item", "worklist://" + SCOPE + "/item/2", "type", "blocks"));
        calls.put("unrelate", args("address", item, "conflict_token", "stale",
            "to_item", "worklist://" + SCOPE + "/item/2", "type", "blocks"));
        calls.put("validate", args("scope", SCOPE, "selector", Selector.ITEM));

        SoftAssertions soft = new SoftAssertions();
        List<String> tools = toolsList().getList("result.tools.name", String.class);
        soft.assertThat(calls.keySet()).as("every declared tool is probed")
            .containsExactlyInAnyOrderElementsOf(tools);

        calls.forEach((tool, call) -> {
            for (String argument : call.keySet()) {
                Map<String, Object> asNull = new HashMap<>(call);
                asNull.put(argument, null);
                Map<String, Object> leftOut = new HashMap<>(call);
                leftOut.remove(argument);
                Object nullAnswer = structured(tool, asNull);
                Object absentAnswer = structured(tool, leftOut);
                soft.assertThat(nullAnswer)
                    .as("%s with %s as null answers as %s left out", tool, argument, argument)
                    .isEqualTo(absentAnswer);
                soft.assertThat(String.valueOf(nullAnswer))
                    .as("%s with %s as null is no unexpected failure", tool, argument)
                    .doesNotContain("UNEXPECTED_FAILURE");
            }
        });
        soft.assertAll();
    }

    @Test
    void a_mandatory_argument_sent_as_null_is_refused_as_missing_by_its_name() {
        JsonPath answer = call("read", args("address", null));
        org.assertj.core.api.Assertions.assertThat(
                answer.getString("result.structuredContent.reason"))
            .isEqualTo("ARGUMENT_MISSING");
        org.assertj.core.api.Assertions.assertThat(
                answer.getList("result.structuredContent.data.offenders", String.class))
            .containsExactly("address");
    }

    @Test
    void every_rest_body_answers_a_null_argument_as_the_argument_left_out() {
        String itemPath = "/api/" + SCOPE + "/" + Selector.ITEM;
        Map<String, Map<String, Object>> bodies = new LinkedHashMap<>();
        bodies.put(itemPath + "/1:withdraw", args("status", "done"));
        bodies.put(itemPath + "/1:claim", args("duration_seconds", 0));
        bodies.put(itemPath + "/1:release", args("receipt", "nobody-minted-this"));
        bodies.put(itemPath + "/1:relate", args("to_item", "worklist://" + SCOPE + "/item/2",
            "type", "blocks"));
        bodies.put(itemPath + "/1:unrelate", args("to_item", "worklist://" + SCOPE + "/item/2",
            "type", "blocks"));
        bodies.put(itemPath + ":claim_next", args("duration_seconds", 0));
        bodies.put("/api/" + SCOPE + "/iteration/1:close", args("produced", "a release"));

        SoftAssertions soft = new SoftAssertions();
        bodies.forEach((path, body) -> {
            for (String argument : body.keySet()) {
                Map<String, Object> asNull = new HashMap<>(body);
                asNull.put(argument, null);
                Map<String, Object> leftOut = new HashMap<>(body);
                leftOut.remove(argument);
                soft.assertThat(post(path, asNull))
                    .as("%s with %s as null answers as %s left out", path, argument, argument)
                    .isEqualTo(post(path, leftOut));
            }
            Map<String, Object> allNull = new HashMap<>();
            body.keySet().forEach(name -> allNull.put(name, null));
            soft.assertThat(post(path, allNull))
                .as("%s with every argument null answers as the absent body", path)
                .isEqualTo(post(path, null));
        });
        soft.assertThat(post(itemPath, "null"))
            .as("a create whose body is null answers as one without a body")
            .isEqualTo(post(itemPath, null));
        soft.assertAll();
    }

    @Test
    void every_optional_argument_names_its_default_and_what_null_does() {
        JsonPath tools = toolsList();
        SoftAssertions soft = new SoftAssertions();
        List<Map<String, Object>> declared = tools.getList("result.tools");
        for (Map<String, Object> tool : declared) {
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) tool.get("inputSchema");
            @SuppressWarnings("unchecked")
            Map<String, Map<String, Object>> properties =
                (Map<String, Map<String, Object>>) schema.get("properties");
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) schema.get("required");
            properties.forEach((name, property) -> {
                if (required.contains(name)) {
                    return;
                }
                String description = String.valueOf(property.get("description"));
                soft.assertThat(description)
                    .as("%s.%s names its default", tool.get("name"), name)
                    .contains("Default");
                soft.assertThat(description)
                    .as("%s.%s says that null equals leaving it out", tool.get("name"), name)
                    .contains("null");
            });
        }
        soft.assertAll();
    }

    // ======================================================================
    // Transports
    // ======================================================================

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static JsonPath toolsList() {
        return given().contentType(ContentType.JSON)
            .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
            .when().post("/mcp").then().statusCode(200).extract().jsonPath();
    }

    private static JsonPath call(String tool, Map<String, Object> arguments) {
        Map<String, Object> params = new HashMap<>();
        params.put("name", tool);
        params.put("arguments", arguments);
        return given().contentType(ContentType.JSON)
            .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", params))
            .when().post("/mcp").then().statusCode(200).extract().jsonPath();
    }

    private static Object structured(String tool, Map<String, Object> arguments) {
        return call(tool, arguments).get("result.structuredContent");
    }

    /** Status and body, as one string, so that both are compared at once. */
    private static String post(String path, Object body) {
        var request = given().contentType(ContentType.JSON);
        if (body instanceof String text) {
            request = request.body(text);
        } else if (body != null) {
            request = request.body(body);
        }
        var response = request.when().post(path).then().extract();
        return response.statusCode() + " " + response.asString();
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
            selectors.declare(SCOPE_ID, view);
        }
        vocabulary.declareStatus(SCOPE_ID, "open", 1, true, false, false, false);
        vocabulary.declareStatus(SCOPE_ID, "done", 2, false, false, true, true);
        vocabulary.declareRelationType(SCOPE_ID, "blocks", true, 1);
        workstreams.requireDefault(SCOPE_ID);
        for (int i = 0; i < 2; i++) {
            items.create(SCOPE_ID, Map.of("title", "null probe " + i, "status", "open"));
        }
        iterations.create(SCOPE_ID, Map.of("title", "first", "motto", "m", "description", "d"));
    }
}
