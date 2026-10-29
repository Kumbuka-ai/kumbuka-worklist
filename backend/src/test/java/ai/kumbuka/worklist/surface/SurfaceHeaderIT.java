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
import io.restassured.http.Header;
import io.restassured.http.Headers;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The REST surface with {@code Kumbuka-Surface: assistant}: it answers as the
 * service's own MCP adapter answers, and that is all the header changes
 * (C14.4).
 *
 * <p>Each comparison is a JSON value against a JSON value -- the body of the
 * REST answer against {@code structuredContent} of the service's own MCP
 * adapter, for the same call by the same identity at the same object.
 *
 * <p>A value the header cannot take is {@code HEADER_INVALID} 400 with no
 * {@code data}, decided after the caller is authenticated and before anything
 * else: before the route and its method, the address, the scope, the body and
 * the object (C14.5). The MCP adapter does not read the header at all.
 *
 * <p>RED STATE, observed 2026-10-29 with {@code SurfaceHeaderFilter} taken out
 * of the chain for the run ({@code @Provider} removed): a create with {@code
 * Kumbuka-Surface: mcp}, and one with the header sent twice, came through and
 * wrote an item (201), and a colon verb not carried answered {@code
 * WRITE_ON_TRUNCATED_ADDRESS} 405 where the header is decided first: {@link
 * #an_unknown_value_is_header_invalid_and_writes_nothing}, {@link
 * #the_header_twice_is_header_invalid_and_writes_nothing} and {@link
 * #the_header_is_decided_before_anything_else} turned red.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class SurfaceHeaderIT {

    private static final String HEADER = "Kumbuka-Surface";
    private static final String ASSISTANT = "assistant";
    private static final UUID SCOPE_ID = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);

    @Inject TestIdentityAssociation identity;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;

    private String status;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);
        selectors.open(SCOPE_ID, Selector.ITEM);
        status = vocabulary.declareStatus(SCOPE_ID, "header-open", 1, true, false, false, false)
            .name;
    }

    @Test
    void with_the_header_rest_answers_what_the_assistant_surface_answers() {
        String number = create("the header's item");
        String address = SurfaceFixture.address(Selector.ITEM, number);

        Response read = assistant().get(SurfaceFixture.item(Selector.ITEM, number));
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(body(read)).as("read")
            .isEqualTo(Mcp.answer(Mcp.call("worklist_read", Map.of("address", address))));

        Response listed = assistant().queryParam("page_size", 2)
            .get(SurfaceFixture.collection(Selector.ITEM));
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(body(listed)).as("query")
            .isEqualTo(Mcp.answer(Mcp.call("worklist_query", Map.of(
                "scope", SurfaceFixture.SCOPE, "selector", Selector.ITEM, "page_size", 2))));

        Response rank = assistant().queryParam("sort", "rank")
            .get(SurfaceFixture.collection(Selector.ITEM));
        assertThat(rank.statusCode()).isEqualTo(400);
        assertThat(body(rank)).as("a sort the item view does not declare")
            .isEqualTo(Mcp.refusal(Mcp.call("worklist_query", Map.of(
                "scope", SurfaceFixture.SCOPE, "selector", Selector.ITEM, "sort", "rank"))));

        Response missing = assistant().get(SurfaceFixture.item(Selector.ITEM, 999_999));
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(body(missing)).as("NOT_FOUND")
            .isEqualTo(Mcp.refusal(Mcp.call("worklist_read", Map.of(
                "address", SurfaceFixture.address(Selector.ITEM, 999_999)))));
    }

    @Test
    void an_unknown_value_is_header_invalid_and_writes_nothing() {
        for (String value : List.of("mcp", "Assistant", "rest", "")) {
            String before = newest();
            Response refused = given().header(HEADER, value).contentType(ContentType.JSON)
                .body(fields("refused for '" + value + "'"))
                .post(SurfaceFixture.collection(Selector.ITEM));
            headerInvalid(refused, "'" + value + "'");
            assertThat(newest()).as("'%s' wrote nothing", value).isEqualTo(before);
        }
    }

    @Test
    void the_header_twice_is_header_invalid_and_writes_nothing() {
        String before = newest();
        Response twice = given().header(HEADER, ASSISTANT, ASSISTANT)
            .contentType(ContentType.JSON).body(fields("refused for twice"))
            .post(SurfaceFixture.collection(Selector.ITEM));
        headerInvalid(twice, "the header twice");
        assertThat(newest()).as("twice wrote nothing").isEqualTo(before);
    }

    @Test
    void the_header_is_decided_before_anything_else() {
        String number = create("the header's first item");
        String item = SurfaceFixture.item(Selector.ITEM, number);

        headerInvalid(wrong().post(item + ":invented"), "before the route: a verb not carried");
        headerInvalid(wrong().delete(SurfaceFixture.collection(Selector.ITEM)),
            "before the route: a method not carried");
        headerInvalid(wrong().get("/api/nothing"), "before the route: a route not carried");
        headerInvalid(wrong().get("/api/declaration/item"), "before a reserved name");
        headerInvalid(wrong().get(SurfaceFixture.item("FEAT", 1)), "before the grammar");
        headerInvalid(wrong().get(SurfaceFixture.item(Selector.ITEM, 999_999)),
            "before the address: an object that is not there");
        headerInvalid(wrong().get("/api/probe-scope-nowhere/" + Selector.ITEM + "/1"),
            "before the scope: a scope the caller does not see");
        headerInvalid(wrong().contentType(ContentType.JSON).body("[not json")
                .post(item + ":claim"),
            "before the body: no JSON object");
        headerInvalid(wrong().contentType(ContentType.JSON).body(Map.of("invented", 1))
                .post(item + ":claim"),
            "before the body: an argument the call does not declare");

        SurfaceFixture.asStranger(identity);
        headerInvalid(wrong().get(item), "before the caller's visibility");
    }

    @Test
    void the_mcp_adapter_does_not_read_the_header() {
        String address = SurfaceFixture.address(Selector.ITEM, create("the adapter's item"));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", "tools/call");
        envelope.put("params", Map.of("name", "worklist_read",
            "arguments", Map.of("address", address)));

        for (Headers headers : List.of(new Headers(new Header(HEADER, "mcp")),
                new Headers(new Header(HEADER, ASSISTANT), new Header(HEADER, ASSISTANT)))) {
            Response read = given().headers(headers).contentType(ContentType.JSON)
                .body(envelope).post("/mcp");
            assertThat(read.statusCode()).as("%s", headers).isEqualTo(200);
            assertThat(Mcp.answer(read)).as("%s", headers)
                .isEqualTo(Mcp.answer(Mcp.call("worklist_read", Map.of("address", address))));
        }
    }

    @Test
    void the_header_decides_nothing_about_who_sees_what() {
        String item = SurfaceFixture.item(Selector.ITEM, create("the stranger's item"));
        SurfaceFixture.asStranger(identity);
        Response without = given().get(item);
        Response with = assistant().get(item);
        assertThat(without.statusCode()).isEqualTo(404);
        assertThat(with.statusCode()).isEqualTo(404);
        assertThat(with.asString()).as("the stranger sees nothing, with the header or without")
            .isEqualTo(without.asString());
    }

    // -----------------------------------------------------------------------

    /** {@code HEADER_INVALID} 400 with no data. */
    private static void headerInvalid(Response refused, String what) {
        assertThat(refused.statusCode()).as("%s: %s", what, refused.asString()).isEqualTo(400);
        assertThat(refused.jsonPath().getString("reason")).as(what).isEqualTo("HEADER_INVALID");
        assertThat(refused.jsonPath().getMap("$")).as("%s: no data", what)
            .doesNotContainKey("data");
    }

    private static RequestSpecification assistant() {
        return given().header(HEADER, ASSISTANT);
    }

    private static RequestSpecification wrong() {
        return given().header(HEADER, "mcp");
    }

    private static Map<String, Object> body(Response response) {
        return response.jsonPath().getMap("$");
    }

    private Map<String, Object> fields(String title) {
        return Map.of("title", title, "status", status);
    }

    /** Creates an item over REST and answers its number. */
    private String create(String title) {
        Response created = given().contentType(ContentType.JSON).body(fields(title))
            .post(SurfaceFixture.collection(Selector.ITEM));
        assertThat(created.statusCode()).as(created.asString()).isEqualTo(201);
        String address = created.jsonPath().getString("address");
        return address.substring(address.lastIndexOf('/') + 1);
    }

    /** The address of the newest item of the scope, or none: what a write would change. */
    private static String newest() {
        List<String> addresses = given().queryParam("sort", "created_at")
            .queryParam("direction", "desc").queryParam("page_size", 1)
            .get(SurfaceFixture.collection(Selector.ITEM))
            .jsonPath().getList("objects.address", String.class);
        return addresses.isEmpty() ? "none" : addresses.get(0);
    }
}
