/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.domain.VocabularyRegistry;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP adapter speaks the protocol the contract fixes (C4.1 to C4.5).
 *
 * <p>JSON-RPC 2.0 at {@code POST /mcp}, protocol {@code 2025-06-18}; the methods
 * {@code initialize}, {@code ping}, {@code tools/list} and {@code tools/call};
 * a notification acknowledged with 202. {@code tools/list} is the declaration:
 * its names are the identities {@code GET /api/declaration} publishes, in its
 * order. An unknown tool is the protocol's -32602 with the declared tool names
 * under {@code error.data.accepts} and no part of the name that arrived; an
 * unknown method is -32601. A refused call is a tool result with {@code
 * isError}. {@code serverInfo.version} is the version the POM builds. The bare
 * verb a tool was named by before is still taken and answers what the tool
 * name answers.
 *
 * <p>The expectations are read from outside the service: the POM's version
 * from the POM, the identities from the served declaration, the codes from the
 * MCP specification.
 *
 * <p>RED STATE, observed 2026-10-29 against the adapter before this change
 * (the tree of {@code 34e4390}, with this class copied in): {@code ping}
 * answered no result, the tool list named bare verbs where the declaration
 * names {@code worklist_<verb>}, an unknown tool answered a tool result where
 * the protocol error belongs, and {@code serverInfo.version} was {@code 0.4.2}
 * under a POM of {@code 0.4.2-SNAPSHOT}; six of nine cases turned red.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class McpProtocolIT {

    private static final String MARKER = "zzecho5521";
    private static final UUID SCOPE_ID = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);

    @Inject TestIdentityAssociation identity;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);
        selectors.open(SCOPE_ID, Selector.ITEM);
    }

    @Test
    void initialize_names_the_protocol_and_the_version_the_build_carries() throws IOException {
        Response answer = Mcp.rpc("initialize", Map.of());
        assertThat(answer.jsonPath().getString("jsonrpc")).isEqualTo("2.0");
        assertThat(answer.jsonPath().getString("result.protocolVersion")).isEqualTo("2025-06-18");
        assertThat(answer.jsonPath().getString("result.serverInfo.version"))
            .as("serverInfo.version is the POM's version, read from the build (C4.5)")
            .isEqualTo(pomVersion());
        assertThat(answer.jsonPath().getMap("result.capabilities")).containsKey("tools");
    }

    @Test
    void ping_answers_an_empty_result() {
        Response answer = Mcp.rpc("ping", Map.of());
        assertThat(answer.jsonPath().getMap("result")).as("C4.1: ping answers {}").isEmpty();
        assertThat(answer.jsonPath().getMap("$")).doesNotContainKey("error");
    }

    @Test
    void the_tool_list_is_the_declaration() {
        List<String> tools = Mcp.rpc("tools/list", Map.of())
            .jsonPath().getList("result.tools.name", String.class);
        List<String> identities = given().get("/api/declaration")
            .jsonPath().getList("calls.identity", String.class);
        assertThat(tools)
            .as("tools/list is generated from the declaration, one tool per call under its "
                + "identity, in its order (C4.2, ADR-0039)")
            .isEqualTo(identities)
            .allMatch(tool -> tool.startsWith("worklist_"));
    }

    @Test
    void an_unknown_tool_is_invalid_params_naming_the_declared_tools_and_not_the_one_sent() {
        for (String unknown : List.of("worklist_" + MARKER, MARKER, "")) {
            Response answer = Mcp.call(unknown, Map.of());
            assertThat(answer.jsonPath().getInt("error.code")).as("'%s'", unknown)
                .isEqualTo(-32602);
            assertThat(answer.jsonPath().getList("error.data.accepts", String.class))
                .as("the declared tool names (C4.3)")
                .isEqualTo(Mcp.rpc("tools/list", Map.of()).jsonPath()
                    .getList("result.tools.name", String.class));
            assertThat(answer.asString()).as("no part of the name that arrived")
                .doesNotContain(MARKER);
            assertThat(answer.jsonPath().getMap("$")).doesNotContainKey("result");
        }
    }

    @Test
    void an_unknown_method_is_method_not_found_and_names_nothing_sent() {
        Response answer = Mcp.rpc("tools/" + MARKER, Map.of());
        assertThat(answer.jsonPath().getInt("error.code")).isEqualTo(-32601);
        assertThat(answer.asString()).doesNotContain(MARKER);
    }

    @Test
    void a_notification_is_acknowledged_and_answered_with_nothing() {
        Map<String, Object> notification = new LinkedHashMap<>();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        Response answer = given().contentType(ContentType.JSON).body(notification).post("/mcp");
        assertThat(answer.statusCode()).isEqualTo(202);
        assertThat(answer.asString()).isEmpty();
    }

    @Test
    void a_refused_call_is_a_tool_result_marked_as_an_error_with_the_envelope() {
        Response answer = Mcp.call("worklist_read",
            Map.of("address", SurfaceFixture.address(Selector.ITEM, 999_999)));
        Map<String, Object> envelope = Mcp.refusal(answer);
        assertThat(envelope).containsOnlyKeys("reason", "message")
            .containsEntry("reason", "NOT_FOUND");
        assertThat(answer.jsonPath().getString("result.content[0].text"))
            .as("the same envelope as text, for a client that reads only text")
            .contains("\"reason\":\"NOT_FOUND\"");
    }

    @Test
    void an_envelope_that_is_no_json_rpc_is_refused_with_a_null_id() {
        Response answer = given().contentType(ContentType.JSON)
            .body(Map.of("method", "tools/list", "id", 7)).post("/mcp");
        assertThat(answer.jsonPath().getMap("$")).containsKey("id");
        assertThat(answer.jsonPath().getString("id")).isNull();
        assertThat(answer.jsonPath().getInt("error.code")).isEqualTo(-32602);
    }

    /**
     * The bare verb is still taken on {@code tools/call} until the breaking
     * step, and it answers what the tool name answers: the same object read
     * under both names, and the same refusal.
     */
    @Test
    void the_bare_verb_answers_what_the_tool_name_answers() {
        String status = vocabulary
            .declareStatus(SCOPE_ID, "protocol-open", 1, true, false, false, false).name;
        Map<String, Object> created = Mcp.answer(Mcp.call("worklist_create", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", Selector.ITEM,
            "fields", Map.of("title", "named twice", "status", status))));
        String address = String.valueOf(created.get("address"));

        assertThat(Mcp.answer(Mcp.call("read", Map.of("address", address))))
            .as("a read under the bare verb")
            .isEqualTo(Mcp.answer(Mcp.call("worklist_read", Map.of("address", address))));

        String absent = SurfaceFixture.address(Selector.ITEM, 999_999);
        assertThat(Mcp.refusal(Mcp.call("read", Map.of("address", absent))))
            .as("a refusal under the bare verb")
            .isEqualTo(Mcp.refusal(Mcp.call("worklist_read", Map.of("address", absent))));

        assertThat(Mcp.refusal(Mcp.call("send", Map.of("address", address))))
            .as("a verb refused by name, under both names")
            .isEqualTo(Mcp.refusal(Mcp.call("worklist_send", Map.of("address", address))));
    }

    /** The version the POM of this module builds, read from the POM. */
    private static String pomVersion() throws IOException {
        Path pom = Files.isRegularFile(Path.of("pom.xml")) ? Path.of("pom.xml")
            : Path.of("backend", "pom.xml");
        String text = Files.readString(pom);
        Matcher m = Pattern.compile(
            "<artifactId>kumbuka-worklist</artifactId>\\s*<version>([^<]+)</version>")
            .matcher(text);
        assertThat(m.find()).as("the module's own version stands in its POM").isTrue();
        return m.group(1);
    }
}
