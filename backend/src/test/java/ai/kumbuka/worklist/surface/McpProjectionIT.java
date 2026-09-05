package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.domain.VocabularyRegistry;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * The MCP exposition: a projection that omits and adds nothing.
 *
 * <p>What the static probe already establishes is that the tool list IS the
 * carried verb set — read from the same specification the REST half is read
 * from, and not from the routes. What only a call can establish is the two
 * halves below.
 *
 * <p><strong>The projection reaches the same acts.</strong> An object created
 * over MCP is readable over MCP at the address the answer named, which is what
 * "calls the surface rather than reimplementing it" looks like from outside.
 *
 * <p><strong>An omitted verb is answered by name.</strong> A verb absent from
 * {@code tools/list} still answers the typed refusal the REST surface gives,
 * and the two classes stay apart. An unknown-tool reply would send a caller
 * looking for a spelling; a category error tells them the act does not exist
 * here, and the unbuilt one tells them it is coming.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class McpProjectionIT {

    private static final UUID SCOPE_ID = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);

    @Inject TestIdentityAssociation identity;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);
        selectors.declare(SCOPE_ID, Selector.ITEM);
    }

    @Test
    void the_server_announces_itself_and_lists_the_carried_verbs() {
        rpc("initialize", Map.of())
            .body("result.protocolVersion", is("2025-06-18"))
            .body("result.serverInfo.name", is("kumbuka-worklist"));

        List<String> tools = rpc("tools/list", Map.of())
            .extract().path("result.tools.name");

        assertThat(tools)
            .as("MCP omits and never adds: the tool list is the carried verb set exactly, "
                + "and the verbs this scheme does not carry are absent from it")
            .containsExactlyInAnyOrderElementsOf(
                VerbSurfaceSpecification.verbsOf(VerbSurfaceSpecification.CARRIED));
    }

    @Test
    void an_object_created_over_mcp_is_readable_over_mcp_at_the_address_it_answered() {
        String status = String.valueOf(vocabulary
            .declareStatus(SCOPE_ID, "mcp-open", 1, true, false, false, false).id);

        String address = call("create", Map.of(
            "scope", SurfaceFixture.SCOPE,
            "selector", Selector.ITEM,
            "fields", Map.of("title", "an mcp probe", "status", status)))
            .body("result.isError", is(false))
            .extract().path("result.structuredContent.address");

        assertThat(address)
            .as("the answer carries the complete address, scheme included: over MCP there "
                + "is no request line for one to travel in, so it travels in the body")
            .startsWith(AddressParser.SCHEME + "://" + SurfaceFixture.SCOPE + "/"
                + Selector.ITEM + "/");

        call("read", Map.of("address", address))
            .body("result.isError", is(false))
            .body("result.structuredContent.fields.title", is("an mcp probe"));
    }

    @Test
    void a_verb_the_scheme_does_not_carry_is_answered_by_name_and_not_as_an_unknown_tool() {
        call("send", Map.of("address", SurfaceFixture.address(Selector.ITEM, 1)))
            .body("result.isError", is(true))
            .body("result.structuredContent.reason", is("VERB_UNCARRIED"));
    }

    @Test
    void a_verb_it_carries_unbuilt_is_answered_apart_from_one_it_does_not_carry() {
        call("claim", Map.of("address", SurfaceFixture.address(Selector.ITEM, 1)))
            .body("result.isError", is(true))
            .body("result.structuredContent.reason", is("VERB_UNBUILT"));
    }

    @Test
    void a_tool_this_server_does_not_have_is_a_malformed_call_and_says_so() {
        call("frobnicate", Map.of("address", SurfaceFixture.address(Selector.ITEM, 1)))
            .body("result.isError", is(true))
            .body("result.structuredContent.reason", is("PAYLOAD_MALFORMED"));
    }

    /**
     * The check order reaches this exposition too.
     *
     * <p>Both adapters call one surface, so the order cannot drift between them
     * — but "cannot" is a claim about the construction, and this is the call
     * that shows it holding. A stranger asking about a scope they may not see
     * gets the scope refusal, not the vocabulary one, over MCP as over HTTP.
     */
    @Test
    void the_check_order_is_the_same_one_the_rest_surface_runs() {
        SurfaceFixture.asStranger(identity);

        call("send", Map.of("address", SurfaceFixture.address(Selector.ITEM, 1)))
            .body("result.isError", is(true))
            .body("result.structuredContent.reason", is("SCOPE_UNRESOLVED"));
    }

    private static ValidatableResponse call(String tool, Map<String, Object> arguments) {
        return rpc("tools/call", Map.of("name", tool, "arguments", arguments));
    }

    private static ValidatableResponse rpc(String method, Map<String, Object> params) {
        return given()
            .contentType(ContentType.JSON)
            .body(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params))
            .when().post("/mcp")
            .then()
            .statusCode(200);
    }
}
