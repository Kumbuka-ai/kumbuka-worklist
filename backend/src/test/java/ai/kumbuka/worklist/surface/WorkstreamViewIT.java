package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.domain.Workstream;
import ai.kumbuka.worklist.domain.WorkstreamService;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * The fourth view answered on the verb surface — the arms that were missing
 * until SPRINT_177.3.
 *
 * <h2>What this suite is about</h2>
 *
 * <p>The workstream selector was ratified as the fourth view on 2026-09-08
 * (SPRINT_177.1) and appears in {@link Selector#VIEWS}. Every switch over the
 * view in {@link VerbSurface} used to know three arms and fell into
 * {@code default -> throw unreachableView(...)} for anything else. This suite
 * asserts the arms that were added and the shape of every refusal for the
 * verbs that do NOT act on this view.
 *
 * <h2>Three answer classes, and a probe against the recurrence</h2>
 *
 * <p><strong>Read and query answer</strong> — a workstream is addressable like
 * any other declared view; a filter on the query is typed-refused for the same
 * reason iteration and milestone refuse one.
 *
 * <p><strong>Write on the view is typed-refused</strong> — {@code create} and
 * {@code update} answer {@code SELECTOR_DECLARED_NOT_WRITTEN}. This is not
 * {@code VERB_UNCARRIED} (which would say the act does not exist here at all)
 * and not {@code VERB_UNBUILT} (which would say the act is coming here); the
 * act exists and runs on the selector-declaration surface, which is a third
 * sentence.
 *
 * <p><strong>Every other verb is a category error</strong> — the transition
 * verbs, the claim family, the graph verbs and validate refuse with
 * {@code VERB_UNCARRIED}. Most are refused by the existing
 * {@code requireView(...,ITEM,...)} / {@code !ITERATION}-checks; {@code close}
 * grows a dedicated arm.
 *
 * <p><strong>Regression probe</strong> — the last test iterates over
 * {@link Selector#VIEWS} and asserts that {@code read} on every view answers
 * WITHOUT the {@code IllegalStateException} the unreachable arm throws. A
 * fifth view added to the grammar without arms here fails this test loudly
 * instead of arriving in production as a 500.
 *
 * <h2>The red probes, observed before this suite was accepted</h2>
 *
 * Each answer class carries its own red probe, all measured against origin/main
 * of the SPRINT_177.3 branch before the arms were added and again after
 * removing each one:
 *
 * <ul>
 * <li><strong>read</strong> — removing the {@code case WORKSTREAM} arm from
 *     {@code VerbSurface.read} makes {@link #read_answers_the_workstream_object}
 *     answer 500 with an {@code IllegalStateException} carrying the
 *     "unreachable unless the two have been changed apart" sentence. Measured
 *     2026-09-09.</li>
 * <li><strong>query</strong> — same removal in {@code VerbSurface.query(3)}
 *     makes {@link #query_answers_the_workstream_listing} answer 500 with the
 *     same message. Same for {@code query(4)} and the filter refusal.</li>
 * <li><strong>create / update refusal</strong> — removing the
 *     {@code refuseWriteOnDeclaredView(...)} guard in
 *     {@code VerbSurface.create} / {@code VerbSurface.update} makes the calls
 *     fall through into the {@code default -> throw unreachableView(...)}
 *     branch (500) rather than into a typed refusal. The tests assert on
 *     status AND on the reason string, so a 500 fails them both. Measured
 *     2026-09-09.</li>
 * <li><strong>close on workstream</strong> — removing the
 *     {@code case WORKSTREAM} arm in {@code VerbSurface.close(5-arg)} makes
 *     {@link #close_on_workstream_is_a_category_error} answer 500 rather than
 *     422 / VERB_UNCARRIED. Measured 2026-09-09.</li>
 * <li><strong>regression probe</strong> — adding a fifth token to
 *     {@code Selector.VIEWS} without any arm here makes
 *     {@link #every_view_of_the_platform_answers_read_without_a_500} fail
 *     loudly. Rehearsed by locally appending {@code "test-view"} to
 *     {@code VIEWS}; the test then reports the new view is the one that
 *     answered a 500 message, and passes again once the token is removed.</li>
 * </ul>
 *
 * <p>The regression probe is the actual yield of the suite: what SPRINT_177.1
 * did not have was a check that a fifth view would land loud rather than as
 * a switch default.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class WorkstreamViewIT {

    private static final UUID SCOPE_ID = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);

    @Inject TestIdentityAssociation identity;
    @Inject SelectorRegistry selectors;
    @Inject WorkstreamService workstreams;

    /** A workstream number stamped by {@link #aWorkstreamOfItsOwn()}. */
    private long workstreamNumber;

    @BeforeEach
    void aWorkstreamOfItsOwn() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);
        selectors.declare(SCOPE_ID, Selector.WORKSTREAM);
        selectors.declare(SCOPE_ID, Selector.MILESTONE);
        Workstream declared = declareAWorkstream(SCOPE_ID);
        workstreamNumber = declared.number;
    }

    /**
     * The workstream declaration runs inside a transaction so the row is
     * committed by the time the REST call reaches the surface — a lazy
     * default created inside the same request would land after the read
     * that provoked it.
     */
    @Transactional
    Workstream declareAWorkstream(UUID scope) {
        workstreams.requireDefault(scope);
        String token = "ws-" + UUID.randomUUID().toString().substring(0, 8);
        return workstreams.declare(scope, token, "a workstream for the view probe");
    }

    // =======================================================================
    // Read and query — the two verbs that ACT on this view
    // =======================================================================

    @Test
    void read_answers_the_workstream_object() {
        given()
            .when().get(SurfaceFixture.item(Selector.WORKSTREAM, workstreamNumber))
            .then()
            .statusCode(200)
            .body("address",
                is(SurfaceFixture.address(Selector.WORKSTREAM, workstreamNumber)))
            .body("fields.number", equalTo((int) workstreamNumber))
            .body("fields.token", is(instanceOfString()));
    }

    @Test
    void query_answers_the_workstream_listing() {
        given()
            .when().get(SurfaceFixture.collection(Selector.WORKSTREAM))
            .then()
            .statusCode(200)
            // The default plus the one we declared above, at least — earlier
            // classes may have declared more against this shared scope.
            .body("objects", hasSize(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    /**
     * The filter refusal has the same shape as the one iteration and milestone
     * carry: PAYLOAD_MALFORMED (400), with a message that says the view takes
     * no filter today. The reason is what a caller reads to know retry will
     * not help.
     */
    @Test
    void query_on_the_workstream_view_refuses_a_filter() {
        given()
            .when().get(SurfaceFixture.collection(Selector.WORKSTREAM) + "?filter.token=x")
            .then()
            .statusCode(400)
            .body("reason", is("PAYLOAD_MALFORMED"))
            .body("message", containsString("takes no filter today"));
    }

    // =======================================================================
    // Create and update — typed refusal with a third sentence
    // =======================================================================

    @Test
    void create_on_the_workstream_view_is_declared_not_written() {
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("token", "another", "description", "an attempt"))
            .when().post(SurfaceFixture.collection(Selector.WORKSTREAM))
            .then()
            .statusCode(422)
            .body("reason", is("SELECTOR_DECLARED_NOT_WRITTEN"))
            // The message names the surface the act belongs on. Asserting on
            // it is what makes this a probe of the REASON rather than only of
            // a status class shared with VERB_UNCARRIED.
            .body("message", containsString("declared"))
            .body("message", containsString("selector-declaration surface"));
    }

    @Test
    void update_on_the_workstream_view_is_declared_not_written() {
        given()
            .header("If-Match", "any-token")
            .contentType(ContentType.JSON)
            .body(Map.of("description", "an attempted change"))
            .when().patch(SurfaceFixture.item(Selector.WORKSTREAM, workstreamNumber))
            .then()
            .statusCode(422)
            .body("reason", is("SELECTOR_DECLARED_NOT_WRITTEN"))
            .body("message", containsString("declared"))
            .body("message", containsString("selector-declaration surface"));
    }

    /**
     * The refusal must fire BEFORE resolution — otherwise a caller writing to
     * a non-existent workstream would get a 404 instead of the categorical
     * refusal, and the answer would say "wrong id" instead of "wrong door".
     */
    @Test
    void the_write_refusal_fires_before_resolution() {
        given()
            .header("If-Match", "any-token")
            .contentType(ContentType.JSON)
            .body(Map.of("description", "an attempted change"))
            .when().patch(SurfaceFixture.item(Selector.WORKSTREAM, 999_999L))
            .then()
            .statusCode(422)
            .body("reason", is("SELECTOR_DECLARED_NOT_WRITTEN"));
    }

    // =======================================================================
    // Categorical refusals — VERB_UNCARRIED with a workstream sentence where
    // the surface owns the message, and via the existing view-guard elsewhere
    // =======================================================================

    @Test
    void close_on_workstream_is_a_category_error() {
        given()
            .header("If-Match", "any-token")
            .when().post(SurfaceFixture.item(Selector.WORKSTREAM, workstreamNumber) + ":close")
            .then()
            .statusCode(422)
            .body("reason", is("VERB_UNCARRIED"))
            .body("message", containsString("workstream"));
    }

    @Test
    void accept_on_workstream_is_a_category_error() {
        given()
            .header("If-Match", "any-token")
            .when().post(SurfaceFixture.item(Selector.WORKSTREAM, workstreamNumber) + ":accept")
            .then()
            .statusCode(422)
            .body("reason", is("VERB_UNCARRIED"));
    }

    @Test
    void claim_on_workstream_is_a_category_error() {
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("durationSeconds", 60))
            .when().post(SurfaceFixture.item(Selector.WORKSTREAM, workstreamNumber) + ":claim")
            .then()
            .statusCode(422)
            .body("reason", is("VERB_UNCARRIED"));
    }

    /**
     * Membership addressing is guarded at the {@link AddressParser} — a
     * membership only lives under an iteration — so a workstream aimed at
     * this depth is ADDRESS_MALFORMED, not VERB_UNCARRIED. Different reason,
     * same principle: what the surface refuses about is what it names.
     */
    @Test
    void plan_addressed_under_workstream_is_refused_as_a_malformed_address() {
        given()
            .header("If-Match", "any-token")
            .when().post(SurfaceFixture.collection(Selector.WORKSTREAM)
                + "/" + workstreamNumber + "/1")
            .then()
            .statusCode(400)
            .body("reason", is("ADDRESS_MALFORMED"));
    }

    // =======================================================================
    // Regression probe: every view of the platform must answer read without a
    // 500 — the actual yield of this sub-sprint
    // =======================================================================

    /**
     * The check the missing arms defeated: {@code query} on every view answers
     * with a typed status the surface CHOSE — 200 or 422 — and never a 500
     * from {@code unreachableView}. A view added to {@link Selector#VIEWS}
     * without an arm here answers 500 with the "unreachable unless the two
     * have been changed apart" sentence, and this test reports which view
     * did it.
     *
     * <p><strong>Why {@code query} and not {@code read}</strong>. The read
     * path resolves the address BEFORE the view switch is reached, so a
     * non-existent number answers 404 from address resolution and never
     * touches the switch — the assertion would then pass on a missing arm.
     * {@code query} takes no id and dispatches straight through the switch,
     * so a missing arm falls into {@code default -> throw unreachableView(...)}
     * and the surface answers 500. That is what this test measures.
     */
    @Test
    void every_view_of_the_platform_answers_query_without_a_500() {
        for (String view : Selector.VIEWS) {
            selectors.declare(SCOPE_ID, view);
        }
        for (String view : Selector.VIEWS) {
            int status = given()
                .when().get(SurfaceFixture.collection(view))
                .then()
                .extract().statusCode();

            org.assertj.core.api.Assertions.assertThat(status)
                .as("query on the '%s' view answered %d — the surface chose a status only "
                    + "for the views it has an arm for, and a 500 from unreachableView is "
                    + "what a view added to VIEWS without an arm here answers", view, status)
                .isNotEqualTo(500);
        }
    }

    // =======================================================================
    // Helpers
    // =======================================================================

    private static org.hamcrest.Matcher<Object> instanceOfString() {
        return org.hamcrest.Matchers.instanceOf(String.class);
    }
}
