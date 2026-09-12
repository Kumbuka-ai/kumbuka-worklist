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

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A single guard that stands over every verb answer of this service and asserts
 * one thing: no uuid leaks onto the wire under an identifier of an ordering
 * construct or piece of declared vocabulary.
 *
 * <p><strong>Why this class exists.</strong> The rule Sprint 180.4 fixes — that
 * status, milestone, workstream and relation-type present as name, number and
 * token, never as a raw uuid — has to be built in code, not carried by
 * convention. A per-field assertion in each verb's own probe would let one
 * added response quietly escape by never being checked. This probe drives every
 * verb once and pattern-matches the answer against the uuid shape: any hit is
 * a leak, and the failure points at the specific answer.
 *
 * <p><strong>What is exempt, and why.</strong> Two fields are the platform's
 * own identity carriers and must remain uuid: {@code fields.id} (the row's
 * technical id, still exposed for callers that hold uuids as receipts) and the
 * {@code target} of a reference entry (a free-text external pointer a caller
 * writes and the reader hands back verbatim). The guard strips those two
 * before matching, and everything else is subject to the check.
 *
 * <p><strong>What it does not police.</strong> Free-text fields a caller
 * supplies — {@code title}, {@code description}, {@code motto} — could carry
 * any string, and this probe uses uuid-free values in every write so their
 * content is not the subject. The subject is the fields the service itself
 * fills.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class,
    restrictToAnnotatedClass = true)
class NoUuidLabelsAtSurfaceIT {

    /** The RFC 4122 shape, case-insensitive. Nothing else looks like this. */
    private static final Pattern UUID_SHAPE = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * The fields the guard is allowed to see uuids in.
     *
     * <p>Three by design and one by construction:
     * <ul>
     *   <li>{@code id} — the platform's own row identity, still surfaced on
     *       every projection so a caller can hold it beside the address.
     *   <li>{@code conflict_token} — an opaque rotating value the round trip
     *       depends on, minted as a uuid because uuids are what the store
     *       generates cheaply. Its shape is not a wire commitment; treating
     *       it as an ordering identifier would defeat the aggregate rule.
     *   <li>{@code receipt} — the opaque proof a claim mints, checked against
     *       the row and never rendered.
     * </ul>
     * The strip removes every quoted "field": "&lt;uuid&gt;" of the exempt
     * names from the body before the shape match runs.
     */
    private static final Pattern EXEMPT_FIELD = Pattern.compile(
        "\"(?:id|conflict_token|receipt|structuredContent)\"\\s*:\\s*"
            + "\"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
            + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\"");

    private static final UUID SCOPE_ID = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);

    @Inject TestIdentityAssociation identity;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject ai.kumbuka.worklist.domain.ScopeSettingService settings;

    private String openStatusName;
    private String closedStatusName;
    private String blocksTypeName;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);

        for (String view : Selector.VIEWS) {
            selectors.declare(SCOPE_ID, view);
        }
        openStatusName = vocabulary
            .declareStatus(SCOPE_ID, "guard-open", 1, true, false, false, false).name;
        closedStatusName = vocabulary
            .declareStatus(SCOPE_ID, "guard-done", 2, false, false, true, true).name;
        blocksTypeName = vocabulary
            .declareRelationType(SCOPE_ID, "guard-blocks", true, 1).name;

        openTheScope();
    }

    /**
     * Every verb answer the surface hands out, driven once in order, and every
     * answer checked in place.
     *
     * <p>One method rather than one per verb, because the point is the walk:
     * the object moves through its states, each verb answers, and no answer
     * carries a uuid under an identifier that Sprint 180.4 fixes as name,
     * number or token. Splitting the walk would lose the chain and add
     * fixture boilerplate for something the chain already builds.
     */
    @Test
    void no_verb_answer_carries_a_uuid_under_an_identifier() {
        // create — the item is born, its address is allocated and its
        // status name comes back.
        Response created = restCall("POST", SurfaceFixture.collection(Selector.ITEM), null,
            Map.of("title", "the walk of every verb",
                "status", openStatusName));
        created.then().statusCode(201);
        assertNoUuid("create", created);
        long itemNumber = ((Number) created.jsonPath().get("fields.number")).longValue();
        String itemAddress = created.jsonPath().getString("address");
        String itemToken = created.jsonPath().getString("fields.conflict_token");

        // read — the projection carries the name, the number, the token,
        // and no identity for the ordering constructs.
        Response read = restCall("GET", SurfaceFixture.item(Selector.ITEM, itemNumber),
            null, null);
        read.then().statusCode(200);
        assertNoUuid("read (item)", read);

        // query on the collection — every row of the answer runs through
        // the same projection.
        Response listed = restCall("GET", SurfaceFixture.collection(Selector.ITEM), null, null);
        listed.then().statusCode(200);
        assertNoUuid("query (item)", listed);

        // update — the token rides in the request line, the projection
        // comes back the same shape as the read.
        Response updated = restCall("PATCH", SurfaceFixture.item(Selector.ITEM, itemNumber),
            itemToken, Map.of("title", "the walk, renamed"));
        updated.then().statusCode(200);
        assertNoUuid("update (item)", updated);
        itemToken = updated.jsonPath().getString("fields.conflict_token");

        // milestone create — the goal axis writes and reads with the same
        // rule; no workstream identity leaks under `workstream`.
        Response milestone = restCall("POST", SurfaceFixture.collection(Selector.MILESTONE),
            null, Map.of("title", "a guard goal", "vision", "the north star"));
        milestone.then().statusCode(201);
        assertNoUuid("create (milestone)", milestone);

        // iteration create — same rule on the time axis.
        Response iteration = restCall("POST", SurfaceFixture.collection(Selector.ITERATION),
            null, Map.of("motto", "guard", "description", "what this iteration holds"));
        iteration.then().statusCode(201);
        assertNoUuid("create (iteration)", iteration);

        // workstream query — the axis answer runs through workstream projections.
        Response workstreams = restCall("GET",
            SurfaceFixture.collection(Selector.WORKSTREAM), null, null);
        workstreams.then().statusCode(200);
        assertNoUuid("query (workstream)", workstreams);

        // relate + unrelate — the graph verbs take the type by name and
        // the target by address, and the projection they answer with
        // holds the same shape.
        String secondItemAddress = restCall("POST",
            SurfaceFixture.collection(Selector.ITEM), null,
            Map.of("title", "a second item", "status", openStatusName))
            .then().statusCode(201).extract().path("address");
        Response related = restCall("POST",
            SurfaceFixture.item(Selector.ITEM, itemNumber) + ":relate", itemToken,
            Map.of("toItem", secondItemAddress, "type", blocksTypeName));
        related.then().statusCode(200);
        assertNoUuid("relate", related);
        itemToken = related.jsonPath().getString("fields.conflict_token");

        Response unrelated = restCall("POST",
            SurfaceFixture.item(Selector.ITEM, itemNumber) + ":unrelate", itemToken,
            Map.of("toItem", secondItemAddress, "type", blocksTypeName));
        unrelated.then().statusCode(200);
        assertNoUuid("unrelate", unrelated);
        itemToken = unrelated.jsonPath().getString("fields.conflict_token");

        // validate — the scope walk at collection depth.
        Response validated = restCall("POST",
            SurfaceFixture.collection(Selector.ITEM) + ":validate", null, null);
        validated.then().statusCode(200);
        assertNoUuid("validate", validated);

        // withdraw — the terminal write on the item, the status name at
        // both ends of the round trip.
        Response withdrawn = restCall("POST",
            SurfaceFixture.item(Selector.ITEM, itemNumber) + ":withdraw", itemToken,
            Map.of("status", closedStatusName));
        withdrawn.then().statusCode(200);
        assertNoUuid("withdraw", withdrawn);
    }

    /**
     * The refusal shape also travels — it carries {@code offenders}, and a
     * caller-visible refusal on a wrong scope-shape should not smuggle a uuid
     * back either.
     */
    @Test
    void refusals_do_not_carry_a_uuid_under_an_identifier() {
        Response refused = given().accept(ContentType.JSON)
            .get("/api/no-such-scope/item/1");
        refused.then().statusCode(404);
        assertNoUuid("refusal (unknown scope)", refused);
    }

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
        }
    }

    /**
     * The body of a response with the two id fields stripped, so the shape
     * match below is about the fields the rule fixes and not the identity
     * carriers the projection deliberately still exposes.
     */
    private static String stripped(Response response) {
        return EXEMPT_FIELD.matcher(response.asString())
            .replaceAll("\"stripped\":\"[stripped]\"");
    }

    private static void assertNoUuid(String label, Response response) {
        String body = stripped(response);
        Matcher matcher = UUID_SHAPE.matcher(body);
        assertThat(matcher.find())
            .as("%s: no verb answer of this service may carry a uuid under an "
                + "identifier of an ordering construct or declared vocabulary "
                + "(status, milestone, workstream, relation type, membership "
                + "order). Sprint 180.4 fixes the wire form as name, number "
                + "and token; a raw uuid is a leak. First hit was at index %d "
                + "of the stripped body:%n%s",
                label,
                matcher.find(0) ? matcher.start() : -1,
                body)
            .isFalse();
    }

    private static Response restCall(String method, String path, String token,
            Map<String, ?> body) {
        var request = given().accept(ContentType.JSON);
        if (token != null) {
            request = request.header("If-Match", token);
        }
        if (body != null) {
            request = request.contentType(ContentType.JSON).body(body);
        }
        return switch (method) {
            case "GET" -> request.get(path);
            case "POST" -> request.post(path);
            case "PATCH" -> request.patch(path);
            case "DELETE" -> request.delete(path);
            default -> throw new IllegalArgumentException("no method " + method);
        };
    }
}
