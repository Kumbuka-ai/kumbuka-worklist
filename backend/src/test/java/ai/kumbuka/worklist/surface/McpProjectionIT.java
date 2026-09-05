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
    @Inject ai.kumbuka.worklist.domain.ScopeSettingService settings;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);
        for (String view : Selector.VIEWS) {
            selectors.declare(SCOPE_ID, view);
        }
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

    /**
     * The whole chain over MCP, which is the only way "it reaches the same acts"
     * is a measurement rather than a claim.
     *
     * <p>One method for the same reason the REST coverage probe uses one: the
     * acts form a chain, and splitting it would mean staging the middle of it
     * behind the surface being probed.
     */
    @Test
    void every_declared_tool_reaches_its_act() {
        String status = String.valueOf(vocabulary
            .declareStatus(SCOPE_ID, "mcp-chain-open", 3, true, false, false, false).id);
        String closed = String.valueOf(vocabulary
            .declareStatus(SCOPE_ID, "mcp-chain-done", 4, false, false, true, true).id);
        openTheScope();

        String item = created(Selector.ITEM, Map.of("title", "an mcp chain", "status", status));
        String itemToken = tokenOf(item);

        itemToken = call("update", Map.of("address", item, "conflict_token", itemToken,
            "fields", Map.of("title", "an mcp chain, renamed")))
            .body("result.structuredContent.fields.title", is("an mcp chain, renamed"))
            .extract().path("result.structuredContent.fields.conflict_token");

        call("query", Map.of("scope", SurfaceFixture.SCOPE, "selector", Selector.ITEM))
            .body("result.isError", is(false))
            .body("result.structuredContent.objects.size()",
                org.hamcrest.Matchers.greaterThan(0));

        String milestone = created(Selector.MILESTONE,
            Map.of("title", "an mcp goal", "vision", "the north star"));
        String iteration = created(Selector.ITERATION,
            Map.of("motto", "mcp", "description", "what this iteration contains"));

        pointAtMilestone(item, milestone);

        String membership = iteration + "/" + numberOf(item);
        String iterationToken = call("plan",
            Map.of("address", membership, "conflict_token", tokenOf(iteration)))
            .body("result.isError", is(false))
            .extract().path("result.structuredContent.fields.conflict_token");

        call("read", Map.of("address", membership))
            .body("result.structuredContent.fields.membership_status",
                org.hamcrest.Matchers.notNullValue());

        iterationToken = call("update", Map.of("address", membership,
            "conflict_token", iterationToken,
            "fields", Map.of("membership_status", "active")))
            .body("result.isError", is(false))
            .extract().path("result.structuredContent.fields.conflict_token");

        call("advance", Map.of("scope", SurfaceFixture.SCOPE,
            "selector", Selector.ITERATION, "conflict_token", settingToken()))
            .body("result.isError", is(false));

        iterationToken = call("unplan",
            Map.of("address", membership, "conflict_token", iterationToken))
            .body("result.isError", is(false))
            .extract().path("result.structuredContent.fields.conflict_token");

        call("close", Map.of("address", iteration, "conflict_token", iterationToken))
            .body("result.structuredContent.fields.closed_at",
                org.hamcrest.Matchers.notNullValue());
        call("close", Map.of("address", milestone, "conflict_token", tokenOf(milestone)))
            .body("result.structuredContent.fields.status", is("closed"));

        call("accept", Map.of("address", item, "conflict_token", itemToken))
            .body("result.isError", is(true))
            .body("result.structuredContent.reason", is("IDENTIFIER_UNDECIDED"));

        call("withdraw", Map.of("address", item, "conflict_token", itemToken,
            "status", closed))
            .body("result.isError", is(false))
            .body("result.structuredContent.fields.status", is(closed));
    }

    /**
     * The protocol's own faults, which are not refusals of a verb.
     *
     * <p>A JSON-RPC error says the call could not be made; every refusal in this
     * service is a call that was made and answered. Answering a refused verb as
     * a protocol error would tell a client to fix its transport when what it has
     * to fix is its expectation.
     */
    @Test
    void a_protocol_fault_is_a_json_rpc_error_and_a_refused_verb_is_not() {
        given().contentType(ContentType.JSON)
            .body(Map.of("jsonrpc", "1.0", "id", 1, "method", "tools/list"))
            .when().post("/mcp").then()
            .statusCode(200)
            .body("error.code", is(-32602));

        rpc("tools/frobnicate", Map.of())
            .body("error.code", is(-32601))
            .body("error.message", org.hamcrest.Matchers.containsString("tools/call"));

        // A notification carries no id and takes no answer. Answering one is a
        // protocol error on our side, not a courtesy.
        given().contentType(ContentType.JSON)
            .body(Map.of("jsonrpc", "2.0", "method", "tools/list"))
            .when().post("/mcp").then()
            .statusCode(202);
    }

    @Test
    void an_argument_the_schema_declares_required_is_refused_when_it_is_absent() {
        call("read", Map.of())
            .body("result.isError", is(true))
            .body("result.structuredContent.reason", is("PAYLOAD_MALFORMED"));

        call("create", Map.of("scope", SurfaceFixture.SCOPE))
            .body("result.isError", is(true))
            .body("result.structuredContent.reason", is("PAYLOAD_MALFORMED"));
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

    /** One object, created over MCP, as its complete address. */
    private String created(String view, Map<String, Object> fields) {
        return call("create", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", view, "fields", fields))
            .body("result.isError", is(false))
            .extract().path("result.structuredContent.address");
    }

    private String tokenOf(String address) {
        return call("read", Map.of("address", address))
            .extract().path("result.structuredContent.fields.conflict_token");
    }

    /** The number part of an address, which is its last segment. */
    private static String numberOf(String address) {
        return address.substring(address.lastIndexOf('/') + 1);
    }

    /**
     * The scope's settings row, without which no iteration can be created.
     *
     * <p>Written through the service because the surface does not carry it:
     * {@code scope_setting} has no view and is not addressed. A scope therefore
     * cannot be opened over the machine surface at all, which is reported rather
     * than worked around here.
     */
    private void openTheScope() {
        Map<String, Object> limits = Map.of(
            "max_planned_iterations", 1_000, "warn_planned_iterations", 1_000,
            "max_memberships_per_iteration", 1_000, "warn_memberships_per_iteration", 1_000);
        try {
            settings.create(SCOPE_ID, limits);
        } catch (ai.kumbuka.worklist.domain.WorklistException alreadyOpen) {
            if (alreadyOpen.reason()
                    != ai.kumbuka.worklist.domain.WorklistException.Reason.SETTING_PRESENT) {
                throw alreadyOpen;
            }
            Map<String, Object> raise = new java.util.HashMap<>(limits);
            raise.put("conflict_token", settingToken());
            settings.update(SCOPE_ID, raise);
        }
    }

    private String settingToken() {
        return (String) settings.read(SCOPE_ID).get("conflict_token");
    }

    /**
     * Points an item at a milestone, over JDBC and under the runtime role.
     *
     * <p>No verb of this service assigns {@code item.milestone_id}, so the
     * precondition {@code plan} enforces is satisfiable only by a write like
     * this one. Named for what it is: a fixture that goes around the surface is
     * a finding about the surface.
     */
    private static void pointAtMilestone(String itemAddress, String milestoneAddress) {
        ai.kumbuka.worklist.platform.PlatformFixture.run(
            "SELECT set_config('app.tenant_id', '"
                + SubstrateDatabaseResource.TENANT_ID + "', false)",
            "UPDATE worklist.item SET milestone_id = ("
                + "  SELECT id FROM worklist.milestone WHERE scope_id = '" + SCOPE_ID
                + "' AND number = " + numberOf(milestoneAddress) + ")"
                + " WHERE scope_id = '" + SCOPE_ID + "' AND number = "
                + numberOf(itemAddress));
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
