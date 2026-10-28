/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.contract;

import ai.kumbuka.worklist.domain.IterationService;
import ai.kumbuka.worklist.domain.MilestoneService;
import ai.kumbuka.worklist.domain.ScopeSettingService;
import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.domain.VocabularyRegistry;
import ai.kumbuka.worklist.domain.WorkstreamService;
import ai.kumbuka.worklist.surface.SurfaceFixture;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * How an answer is written (C7.2, C7.3): a member with no value is absent, and
 * an instant is ISO-8601 in UTC, to the microsecond at most.
 *
 * <p>A set of answers is collected over both surfaces -- the three MCP
 * methods, a JSON-RPC error, an item created without a description, read and
 * listed, a claim with its lease, a planned membership, a closed iteration, a
 * {@code whoami}, and refusals with and without {@code data} -- and every node
 * of every answer is walked: no member is {@code null}, and every member that
 * names an instant has the one form.
 *
 * <p>RED STATE, observed 2026-10-28 against the service before its
 * serialisation customizer: every item answer -- create, read and query, on
 * both surfaces -- carried {@code "description":null} inside {@code fields},
 * and one created without a milestone {@code "milestone":null} beside it. No
 * instant was of another form: the domain truncates to the microsecond where
 * it reads the clock, and the customizer is the second line behind it.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class SerialisationIT {

    private static final String SCOPE = "serialisation-probe";
    private static final UUID SCOPE_ID = UUID.fromString("5a4b3c2d-1e0f-4a9b-8c7d-6e5f4a3b2c1d");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern INSTANT = Pattern.compile(
        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?Z");
    private static final List<String> INSTANT_MEMBERS = List.of("created_at", "changed_at",
        "updated_at", "closed_at", "granted_at", "expires_at");

    private static boolean staged;

    @Inject TestIdentityAssociation identity;
    @Inject ScopeSettingService settings;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject WorkstreamService workstreams;
    @Inject IterationService iterations;
    @Inject MilestoneService milestones;

    /** The milestone an item needs to be planned at all. */
    private static Object milestone;

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
    void no_member_is_null_and_every_instant_has_the_one_form() throws Exception {
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put("initialize", rpc("initialize", Map.of()).asString());
        answers.put("tools/list", rpc("tools/list", Map.of()).asString());
        answers.put("unknown method", rpc("no/such/method", Map.of()).asString());

        Response created = call("create", Map.of("scope", SCOPE, "selector", Selector.ITEM,
            "fields", Map.of("title", "written without a description", "status", "open",
                "milestone", milestone)));
        answers.put("create over MCP", created.asString());
        JsonPath item = created.jsonPath();
        String address = item.getString("result.structuredContent.address");
        String number = item.getString("result.structuredContent.fields.number");
        answers.put("read over MCP", call("read", Map.of("address", address)).asString());
        answers.put("query over MCP", call("query", Map.of("scope", SCOPE,
            "selector", Selector.ITEM, "page_size", 1)).asString());
        answers.put("claim over MCP", call("claim", Map.of("address", address,
            "duration_seconds", 600)).asString());

        String first = "worklist://" + SCOPE + "/iteration/1";
        answers.put("plan over MCP", call("plan", Map.of("address", first + "/" + number,
            "conflict_token", tokenOf(first))).asString());
        answers.put("membership over MCP", call("read", Map.of("address",
            first + "/" + number)).asString());
        String second = "worklist://" + SCOPE + "/iteration/2";
        answers.put("close over MCP", call("close", Map.of("address", second,
            "conflict_token", tokenOf(second), "produced", "a release")).asString());

        answers.put("stale token over MCP", call("update", Map.of("address", address,
            "conflict_token", "stale", "fields", Map.of("title", "x"))).asString());
        answers.put("not found over MCP", call("read", Map.of("address",
            "worklist://" + SCOPE + "/item/999999")).asString());
        answers.put("unknown tool", call("no_such_tool", Map.of()).asString());

        String items = "/api/" + SCOPE + "/" + Selector.ITEM;
        answers.put("create over REST", given().contentType(ContentType.JSON)
            .body(Map.of("title", "written over REST", "status", "open"))
            .when().post(items).asString());
        answers.put("read over REST", given().get(items + "/" + number).asString());
        answers.put("query over REST", given().queryParam("page_size", 1).get(items).asString());
        answers.put("membership over REST", given()
            .get("/api/" + SCOPE + "/iteration/1/" + number).asString());
        answers.put("stale token over REST", given().contentType(ContentType.JSON)
            .header("If-Match", "\"stale\"").body(Map.of("title", "x"))
            .when().patch(items + "/" + number).asString());
        answers.put("not found over REST", given().get(items + "/999999").asString());
        answers.put("whoami", given().get("/api/whoami").asString());

        List<String> offences = new ArrayList<>();
        for (Map.Entry<String, String> answer : answers.entrySet()) {
            walk(JSON.readTree(answer.getValue()), answer.getKey() + " $", offences);
        }
        assertThat(offences).as("members with no value, and instants of another form").isEmpty();
        assertThat(answers.values()).as("the instants were written at all")
            .anySatisfy(a -> assertThat(a).contains("\"expires_at\""))
            .anySatisfy(a -> assertThat(a).contains("\"closed_at\""))
            .anySatisfy(a -> assertThat(a).contains("\"updated_at\""))
            .anySatisfy(a -> assertThat(a).contains("\"changed_at\""));
    }

    @Test
    void not_found_carries_no_data_member_at_all() {
        Response answer = given().get("/api/" + SCOPE + "/" + Selector.ITEM + "/999999");
        assertThat(answer.jsonPath().getMap("$")).containsOnlyKeys("reason", "message");
        assertThat(answer.asString()).doesNotContain("null");
    }

    private static void walk(JsonNode node, String path, List<String> offences) {
        if (node.isNull()) {
            offences.add(path + " is null");
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> members = node.fields();
            while (members.hasNext()) {
                Map.Entry<String, JsonNode> member = members.next();
                String at = path + "." + member.getKey();
                if (INSTANT_MEMBERS.contains(member.getKey()) && member.getValue().isTextual()
                        && !INSTANT.matcher(member.getValue().asText()).matches()) {
                    offences.add(at + " is " + member.getValue().asText());
                }
                walk(member.getValue(), at, offences);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), path + "[" + i + "]", offences);
            }
        }
    }

    private static String tokenOf(String address) {
        return call("read", Map.of("address", address)).jsonPath()
            .getString("result.structuredContent.fields.conflict_token");
    }

    private static Response rpc(String method, Map<String, Object> params) {
        return given().contentType(ContentType.JSON)
            .body(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params))
            .when().post("/mcp");
    }

    private static Response call(String tool, Map<String, Object> arguments) {
        Map<String, Object> params = new HashMap<>();
        params.put("name", tool);
        params.put("arguments", arguments);
        return rpc("tools/call", params);
    }

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
        vocabulary.declareStatus(SCOPE_ID, "done", 2, false, false, true, true);
        workstreams.requireDefault(SCOPE_ID);
        milestone = milestones.create(SCOPE_ID, Map.of("title", "goal", "kind", "milestone"))
            .get("number");
        iterations.create(SCOPE_ID, Map.of("title", "first", "motto", "m", "description", "d"));
        iterations.create(SCOPE_ID, Map.of("title", "second", "motto", "m", "description", "d"));
    }
}
