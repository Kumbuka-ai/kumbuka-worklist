package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * What the platform's read contract says about a scope, enforced.
 *
 * <h2>What was measured, and what it cost</h2>
 *
 * Against the composed stack in sprint 186, with a real core and a second
 * tenant: tenant B read tenant A's {@code private} content, and a write into a
 * scope with no write right went through. The first was possible because the
 * contract published only four columns and this service could not tell a
 * private scope from a project one; the second because there was no column to
 * refuse on. V24 of the core (pinned at v0.10.0) publishes
 * {@code kind}, {@code locked} and {@code can_write}, and this class is what
 * turns the three into refusals.
 *
 * <h2>Both address forms, on every case</h2>
 *
 * The collection form ({@code /api/<scope>/<selector>}) and the item form
 * ({@code /api/<scope>/<selector>/<id>}) reach the surface through different
 * resource methods, and a check written into one of them would leave the other
 * open. The point of every pair below is that the two answer the SAME thing —
 * so they are compared with each other rather than each against a literal.
 * That is also why the refusal has to live in {@code VerbSurface.entry}: it is
 * the one place both forms pass through.
 *
 * <h2>Reading stays open, and the counter-probe says so</h2>
 *
 * A surface that refused everything would satisfy every refusal assertion
 * here. So each case has its other half: the same scope, read, answering
 * something that is not a refusal about the scope.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ScopeIsolationIT {

    /**
     * The tenant's private scope. Authorless, which is the one private scope a
     * fresh chain actually produces (V24's header measures this), so it is
     * visible to every active member — and refused here for its KIND rather
     * than being invisible.
     */
    private static final String PRIVATE_SCOPE = "isolation-private";
    private static final UUID PRIVATE_SCOPE_ID =
        UUID.fromString("00000000-0000-0000-0000-0000000001a1");

    /** A project scope whose content is frozen. */
    private static final String LOCKED_SCOPE = "isolation-locked";
    private static final UUID LOCKED_SCOPE_ID =
        UUID.fromString("00000000-0000-0000-0000-0000000001a2");

    /** An ordinary project scope, for the muted member to read and not write. */
    private static final String OPEN_SCOPE = "isolation-open";
    private static final UUID OPEN_SCOPE_ID =
        UUID.fromString("00000000-0000-0000-0000-0000000001a3");

    @Inject TestIdentityAssociation identity;
    @Inject SelectorRegistry selectors;

    @BeforeAll
    static void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.publishScope(PRIVATE_SCOPE, PRIVATE_SCOPE_ID, "private", false);
        SurfaceFixture.publishScope(LOCKED_SCOPE, LOCKED_SCOPE_ID, "project", true);
        SurfaceFixture.publishScope(OPEN_SCOPE, OPEN_SCOPE_ID, "project", false);
        SurfaceFixture.registerMutedMember();
    }

    /**
     * The item view, declared in the two scopes that are read in this class.
     *
     * <p>Without it a read would answer {@code SELECTOR_UNDECLARED} and the
     * counter-probes would pass for the wrong reason — measuring the
     * vocabulary stage rather than the scope one. The private scope gets no
     * declaration: nothing is ever meant to be declared in it.
     */
    @BeforeEach
    void declareTheItemView() {
        selectors.declare(LOCKED_SCOPE_ID, Selector.ITEM);
        selectors.declare(OPEN_SCOPE_ID, Selector.ITEM);
    }

    // =======================================================================
    // Obligation 3 — a private scope is refused, the same way on both forms
    // =======================================================================

    /**
     * RED PROBE (dispatch 187.4, probe 2): delete the {@code kind = private}
     * check in {@code ScopeDirectory.refuseUnservedKind} and this case goes
     * red — the collection call answers 200 with an empty listing and the item
     * call answers the not-found class, which is both of them failing and the
     * pair no longer agreeing. Observed on 2026-09-21.
     */
    @Test
    void a_private_scope_is_refused_identically_on_both_address_forms() {
        SurfaceFixture.asMember(identity);

        ValidatableResponse collection = given()
            .when().get("/api/" + PRIVATE_SCOPE + "/" + Selector.ITEM)
            .then()
            .body(RefusalSpecification.REASON,
                is(RefusalSpecification.SCOPE_KIND_UNSUPPORTED));

        ValidatableResponse item = given()
            .when().get("/api/" + PRIVATE_SCOPE + "/" + Selector.ITEM + "/1")
            .then()
            .body(RefusalSpecification.REASON,
                is(RefusalSpecification.SCOPE_KIND_UNSUPPORTED));

        assertThat(body(item))
            .as("the two address forms reach the surface through different resource "
                + "methods, and the whole point of resolving the scope in one place is "
                + "that a caller cannot tell which one they used from the answer")
            .isEqualTo(body(collection));
    }

    /**
     * A private scope is refused for reads as well as writes.
     *
     * <p>Deliberate: it holds no items to read either, so answering a read
     * would publish an address space that is empty by construction — and a
     * caller would reasonably conclude their items had gone missing.
     */
    @Test
    void a_private_scope_refuses_a_write_too_and_says_the_same_thing() {
        SurfaceFixture.asMember(identity);

        given()
            .contentType(ContentType.JSON).body(Map.of("title", "no"))
            .when().post("/api/" + PRIVATE_SCOPE + "/" + Selector.ITEM)
            .then()
            .statusCode(422)
            .body(RefusalSpecification.REASON,
                is(RefusalSpecification.SCOPE_KIND_UNSUPPORTED));
    }

    /**
     * The counter-probe for obligation 3: an ordinary project scope of the
     * same tenant, read by the same caller, answers.
     *
     * <p>Without it every assertion above would hold against a surface that
     * refused every scope, and the refusals would be measuring nothing.
     */
    @Test
    void a_project_scope_of_the_same_tenant_is_not_refused_for_its_kind() {
        SurfaceFixture.asMember(identity);

        given()
            .when().get("/api/" + OPEN_SCOPE + "/" + Selector.ITEM)
            .then()
            .statusCode(200);
    }

    // =======================================================================
    // Obligation 4a — a locked scope takes no write, and still reads
    // =======================================================================

    /**
     * RED PROBE (dispatch 187.4, probe 3, first half): delete the
     * {@code locked} check in {@code ScopeDirectory} and this case goes red —
     * the create is accepted (201) instead of refused. Observed on 2026-09-21.
     */
    @Test
    void a_locked_scope_refuses_a_write_on_both_address_forms() {
        SurfaceFixture.asMember(identity);

        ValidatableResponse collection = given()
            .contentType(ContentType.JSON).body(Map.of("title", "no"))
            .when().post("/api/" + LOCKED_SCOPE + "/" + Selector.ITEM)
            .then()
            .statusCode(409)
            .body(RefusalSpecification.REASON, is(RefusalSpecification.SCOPE_LOCKED));

        ValidatableResponse item = given()
            .contentType(ContentType.JSON).body(Map.of("title", "no"))
            .when().patch("/api/" + LOCKED_SCOPE + "/" + Selector.ITEM + "/1")
            .then()
            .statusCode(409)
            .body(RefusalSpecification.REASON, is(RefusalSpecification.SCOPE_LOCKED));

        assertThat(body(item))
            .as("the lock is a property of the scope, so both forms say the same "
                + "sentence — and the item form says it BEFORE resolving the item, "
                + "which is why the address names an id that does not exist and the "
                + "answer is still about the lock")
            .isEqualTo(body(collection));
    }

    @Test
    void a_locked_scope_is_still_readable_on_both_address_forms() {
        SurfaceFixture.asMember(identity);

        given()
            .when().get("/api/" + LOCKED_SCOPE + "/" + Selector.ITEM)
            // Freezing a scope keeps the record; a lock that also withdrew the
            // read would make the record unreachable, which is the opposite of
            // what locking one is for.
            .then()
            .statusCode(200);

        given()
            .when().get("/api/" + LOCKED_SCOPE + "/" + Selector.ITEM + "/999999")
            // The item form gets as far as resolving the item, which is what
            // shows the read was not refused at the scope: a scope-level
            // refusal would have answered before any id was looked for.
            .then()
            .statusCode(404)
            .body(RefusalSpecification.REASON, is(RefusalSpecification.NOT_FOUND));
    }

    // =======================================================================
    // Obligation 4b — no write right, and still a read
    // =======================================================================

    /**
     * RED PROBE (dispatch 187.4, probe 3, second half): delete the
     * {@code can_write} check in {@code ScopeDirectory} and this case goes red
     * — the create is accepted (201). Observed on 2026-09-21.
     *
     * <p>The two refusals are kept apart on purpose, and this case is what
     * makes the order in {@code ScopeDirectory} matter: V24 derives
     * {@code can_write} as {@code NOT locked AND …}, so a locked scope arrives
     * with the write right false as well. Judging the write right first would
     * answer the locked scope above with THIS code and leave
     * {@code SCOPE_LOCKED} unreachable — which is why that case asserts its
     * own code rather than merely asserting a refusal.
     */
    @Test
    void a_member_without_the_write_right_is_refused_on_both_address_forms() {
        SurfaceFixture.asMutedMember(identity);

        ValidatableResponse collection = given()
            .contentType(ContentType.JSON).body(Map.of("title", "no"))
            .when().post("/api/" + OPEN_SCOPE + "/" + Selector.ITEM)
            .then()
            .statusCode(403)
            .body(RefusalSpecification.REASON, is(RefusalSpecification.SCOPE_READ_ONLY));

        ValidatableResponse item = given()
            .contentType(ContentType.JSON).body(Map.of("title", "no"))
            .when().patch("/api/" + OPEN_SCOPE + "/" + Selector.ITEM + "/1")
            .then()
            .statusCode(403)
            .body(RefusalSpecification.REASON, is(RefusalSpecification.SCOPE_READ_ONLY));

        assertThat(body(item)).isEqualTo(body(collection));
    }

    @Test
    void a_member_without_the_write_right_may_still_read() {
        SurfaceFixture.asMutedMember(identity);

        given()
            .when().get("/api/" + OPEN_SCOPE + "/" + Selector.ITEM)
            // The write right is withdrawn, not the membership. A member who
            // can no longer read would be one who had been removed, and that
            // is a different act with a different answer.
            .then()
            .statusCode(200);
    }

    /**
     * The counter-probe for obligation 4b: the SAME call, byte for byte, from
     * a member whose write right stands.
     *
     * <p>It gets past the scope stage and is answered by the vocabulary stage
     * instead — {@code INVALID_VALUE}, because an item carries a status and
     * this scope has declared none. That is the assertion: not that the write
     * succeeds (it cannot, in a scope with no vocabulary, and staging one here
     * would put the counter-probe's weight on machinery that has nothing to do
     * with the scope), but that the refusal it meets is a DIFFERENT one, from
     * a LATER stage.
     *
     * <p>Without this case, the refusal above would hold just as well against
     * a surface that refused every write into this scope, from anybody.
     */
    @Test
    void the_same_write_from_an_unmuted_member_gets_past_the_scope_stage() {
        SurfaceFixture.asMember(identity);

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("title", "a write from a member whose right stands"))
            .when().post("/api/" + OPEN_SCOPE + "/" + Selector.ITEM)
            .then()
            .statusCode(422)
            .body(RefusalSpecification.REASON, is("INVALID_VALUE"));
    }

    /** The response body, as the bytes a caller receives. */
    private static String body(ValidatableResponse response) {
        return response.extract().asString();
    }
}
