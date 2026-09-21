package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * The refusal envelope of DEC-0042, on this service's own adapter.
 *
 * <p>This is the case TST-9004 describes: the per-service protocol surface is
 * the community-edition entry, and a probe that only ever reaches the service
 * through the router leaves it as an assertion. It runs against the service
 * standalone, which is how a community-edition deployment is reached.
 *
 * <h2>Two obligations, and the second is why the first is testable</h2>
 *
 * The not-found class carries one code, one message, and no {@code data} —
 * "no code distinguishes these cases, on any service, at any time". And every
 * other refusal's machine-readable detail travels UNDER {@code data}, never
 * beside {@code reason}. The second is what makes the first assertable as an
 * absence: with {@code offenders} at the top level, "no data" would have been
 * true of every refusal in this service and would have measured nothing.
 *
 * <h2>Expectations come from the node, never from a recorded answer</h2>
 *
 * Every literal is read from {@link RefusalSpecification}, which transcribes
 * DEC-0042 by hand. The comparisons that matter are between LIVE answers —
 * three refusals of the not-found class, compared with each other byte for
 * byte, which is the node's own clause and holds whatever the message says.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class RefusalEnvelopeIT {

    /** A scope of this tenant, visible and empty, for the address probes. */
    private static final String SCOPE = "envelope-scope";
    private static final UUID SCOPE_ID =
        UUID.fromString("00000000-0000-0000-0000-0000000002a1");

    /** A scope slug that is well formed and belongs to nobody. */
    private static final String NO_SUCH_SCOPE = "envelope-no-such-scope";

    @Inject TestIdentityAssociation identity;
    @Inject SelectorRegistry selectors;

    @BeforeAll
    static void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.publishEmptyScope(SCOPE, SCOPE_ID);
    }

    @BeforeEach
    void declareTheItemView() {
        selectors.declare(SCOPE_ID, Selector.ITEM);
    }

    // =======================================================================
    // Obligation 5 — one not-found, on both address forms and both surfaces
    // =======================================================================

    /**
     * The three ways this service reaches the not-found class answer the same
     * bytes.
     *
     * <p>An object that was never allocated; a scope the caller cannot see; a
     * scope that does not exist. The router adds a fourth — an unroutable
     * scheme — which no service can produce and which TST-9001 covers at the
     * seam.
     *
     * <p>RED PROBE (dispatch 187.4, probe 4): take one of the seven reasons
     * out of {@code RefusalPayload.NOT_FOUND_REASONS} — {@code ITEM_UNKNOWN}
     * is the one the first call below raises — and this case goes red on the
     * first comparison: the answer then carries its own code, its own message
     * naming the number and the scope id, and a {@code data} member naming the
     * item. Observed on 2026-09-21.
     */
    @Test
    void the_not_found_class_is_one_answer_however_it_was_reached() {
        SurfaceFixture.asMember(identity);
        String absentObject = body(given()
            .when().get("/api/" + SCOPE + "/" + Selector.ITEM + "/999999")
            .then().statusCode(404));

        String absentScope = body(given()
            .when().get("/api/" + NO_SUCH_SCOPE + "/" + Selector.ITEM + "/1")
            .then().statusCode(404));

        SurfaceFixture.asStranger(identity);
        String invisibleScope = body(given()
            .when().get("/api/" + SCOPE + "/" + Selector.ITEM + "/1")
            .then().statusCode(404));

        assertThat(absentScope)
            .as("an object that is not there and a scope that is not there: DEC-0042 "
                + "gives them one code and one message, and a difference of any kind "
                + "is the enumeration oracle ADR-0011 exists against")
            .isEqualTo(absentObject);
        assertThat(invisibleScope)
            .as("and a scope the caller may not see is the third of the three. This is "
                + "the one an implementation is most likely to get right by accident "
                + "and wrong on purpose, which is why it is compared and not assumed")
            .isEqualTo(absentObject);
    }

    /** The same class, reached through the collection form. */
    @Test
    void the_collection_form_answers_the_not_found_class_the_same_way() {
        SurfaceFixture.asMember(identity);
        String collection = body(given()
            .when().get("/api/" + NO_SUCH_SCOPE + "/" + Selector.ITEM)
            .then().statusCode(404));

        String item = body(given()
            .when().get("/api/" + NO_SUCH_SCOPE + "/" + Selector.ITEM + "/1")
            .then().statusCode(404));

        assertThat(collection)
            .as("the two address forms are different resource methods, and the answer "
                + "must not say which one the caller used")
            .isEqualTo(item);
    }

    /** The envelope itself: the code from the node, and no {@code data}. */
    @Test
    void the_not_found_envelope_carries_the_node_s_code_and_no_data() {
        SurfaceFixture.asMember(identity);

        Map<String, Object> envelope = given()
            .when().get("/api/" + SCOPE + "/" + Selector.ITEM + "/999999")
            .then().statusCode(404)
            .body(RefusalSpecification.REASON, is(RefusalSpecification.NOT_FOUND))
            .body(RefusalSpecification.MESSAGE, is(RefusalSpecification.NOT_FOUND_MESSAGE))
            .extract().body().jsonPath().getMap("$");

        assertThat(envelope)
            .as("'with no data' is the node's wording, and a 'data': null member is a "
                + "member — a caller reading the key set would see a difference the "
                + "node says must not exist")
            .doesNotContainKey(RefusalSpecification.DATA)
            .containsOnlyKeys(RefusalSpecification.REASON, RefusalSpecification.MESSAGE);
    }

    /** The protocol surface answers the same envelope, for the same case. */
    @Test
    void the_protocol_surface_answers_the_same_not_found_envelope() {
        SurfaceFixture.asMember(identity);

        Map<String, Object> envelope = given()
            .contentType(ContentType.JSON)
            .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", "read", "arguments", Map.of(
                    "address", "worklist://" + SCOPE + "/" + Selector.ITEM + "/999999"))))
            .when().post("/mcp")
            .then().statusCode(200)
            .body("result." + RefusalSpecification.MCP_IS_ERROR, is(true))
            .extract().body().jsonPath()
            .getMap("result." + RefusalSpecification.MCP_STRUCTURED_CONTENT);

        assertThat(envelope)
            .as("one envelope for both expositions is the node's wording — 'on the "
                + "protocol tool error and on the REST error body'. The transport "
                + "around it differs and is not compared")
            .containsEntry(RefusalSpecification.REASON, RefusalSpecification.NOT_FOUND)
            .containsEntry(RefusalSpecification.MESSAGE,
                RefusalSpecification.NOT_FOUND_MESSAGE)
            .doesNotContainKey(RefusalSpecification.DATA);
    }

    // =======================================================================
    // Obligation 6 — offenders under data, never beside reason
    // =======================================================================

    /**
     * A refusal that names what offended carries it under {@code data}.
     *
     * <p>RED PROBE (dispatch 187.4, probe 5): put {@code offenders} back at
     * the top level of the envelope — the shape this service answered with
     * until this pass — and this case goes red on the forbidden member.
     * Observed on 2026-09-21.
     *
     * <p>{@code UNKNOWN_FIELD} is used because its offenders are the argument
     * names the caller sent, so the assertion can name the exact value it
     * expects to find rather than reading one back out of the answer.
     */
    @Test
    void machine_readable_detail_travels_under_data() {
        SurfaceFixture.asMember(identity);

        Map<String, Object> envelope = given()
            .contentType(ContentType.JSON)
            .body(Map.of("title", "a probe", "no_such_field", "a value"))
            .when().post("/api/" + SCOPE + "/" + Selector.ITEM)
            .then().statusCode(422)
            .body(RefusalSpecification.REASON, is("UNKNOWN_FIELD"))
            .extract().body().jsonPath().getMap("$");

        assertThat(envelope)
            .as("DEC-0042 puts machine-readable detail 'under data', and a top-level "
                + "member of the envelope's own is a second shape a caller has to learn")
            .doesNotContainKey(RefusalSpecification.FORBIDDEN_TOP_LEVEL_MEMBER)
            .containsOnlyKeys(RefusalSpecification.PERMITTED_MEMBERS);

        assertThat(envelope.get(RefusalSpecification.DATA))
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .as("and the detail is there rather than merely relocated out of sight — "
                + "the whole value of naming the offender is that the caller does not "
                + "have to diff two vocabularies by eye")
            .containsEntry(RefusalSpecification.FORBIDDEN_TOP_LEVEL_MEMBER,
                List.of("no_such_field"));
    }

    /** The same on the protocol surface, which builds the envelope too. */
    @Test
    void machine_readable_detail_travels_under_data_on_the_protocol_surface() {
        SurfaceFixture.asMember(identity);

        Map<String, Object> envelope = given()
            .contentType(ContentType.JSON)
            .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", "create", "arguments", Map.of(
                    "scope", SCOPE, "selector", Selector.ITEM,
                    "fields", Map.of("title", "a probe", "no_such_field", "a value")))))
            .when().post("/mcp")
            .then().statusCode(200)
            .body("result." + RefusalSpecification.MCP_IS_ERROR, is(true))
            .extract().body().jsonPath()
            .getMap("result." + RefusalSpecification.MCP_STRUCTURED_CONTENT);

        assertThat(envelope)
            .containsEntry(RefusalSpecification.REASON, "UNKNOWN_FIELD")
            .doesNotContainKey(RefusalSpecification.FORBIDDEN_TOP_LEVEL_MEMBER)
            .containsOnlyKeys(RefusalSpecification.PERMITTED_MEMBERS);
    }

    /**
     * A refusal with nothing to name carries no {@code data} at all.
     *
     * <p>"Where a refusal has any" is the node's wording, so an empty
     * {@code data} would be a member present with nothing in it — which the
     * not-found assertions above would then have to tolerate, and could no
     * longer measure.
     */
    @Test
    void a_refusal_with_nothing_to_name_carries_no_data() {
        SurfaceFixture.asMember(identity);

        Map<String, Object> envelope = given()
            .when().post("/api/" + SCOPE + "/" + Selector.ITEM + "/1:send")
            .then().statusCode(422)
            .body(RefusalSpecification.REASON, is("VERB_UNCARRIED"))
            .extract().body().jsonPath().getMap("$");

        assertThat(envelope)
            .containsOnlyKeys(RefusalSpecification.REASON, RefusalSpecification.MESSAGE);
    }

    /** The response body, as the bytes a caller receives. */
    private static String body(ValidatableResponse response) {
        return response.extract().asString();
    }
}
