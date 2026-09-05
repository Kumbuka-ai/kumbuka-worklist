package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * The order of the checks, which is security-relevant and is therefore probed
 * rather than commented.
 *
 * <h2>What is being defended</h2>
 *
 * Scope visibility is stage 2 and vocabulary is stage 3, and the order is the
 * whole point. Admitted schemes, declared selectors and carried verbs are
 * configured PER SCOPE, so a 422 carries information about a scope: that it
 * exists, and something about the vocabulary it runs. If a call against a scope
 * the caller cannot see answered 422, the entry point would be a scope
 * enumerator — one built out of an error path nobody audits, because it reads
 * like a bad request.
 *
 * <p>In one sentence: for a scope the caller cannot see, the answer is 404 no
 * matter how broken the rest of the call is.
 *
 * <h2>Every assertion here has its counter-probe</h2>
 *
 * A surface that answered 404 to everything would satisfy the first half of
 * each pair. So each case is run twice — once as a subject the scope is closed
 * to and once as a subject it is open to — and the second run has to answer
 * something ELSE. Without that, this class would pass against a service that
 * had been switched off.
 *
 * <h2>The red state, and how it was observed</h2>
 *
 * The order is one line in {@link VerbSurface}: the refusal for a verb the
 * scheme does not carry sits behind {@code entry()}, which resolves the scope.
 * Moving the throw in front of it — which is the shorter and more obvious way
 * to write that method — makes {@link #a_scope_the_caller_cannot_see_answers_404_however_broken_the_call}
 * fail with 422 where it expects 404, measured on 2026-09-05. That is the whole
 * defect: the stranger learns that the scope exists and that it does not carry
 * {@code send}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class CheckOrderProbeIT {

    /** A scope slug that is well formed and belongs to nobody. */
    private static final String NO_SUCH_SCOPE = "no-such-scope";

    @Inject TestIdentityAssociation identity;
    @Inject SelectorRegistry selectors;

    @BeforeAll
    static void stage() {
        SurfaceFixture.stage();
    }

    /**
     * The view, declared so that the vocabulary stage has something to pass.
     *
     * <p>Without it the counter-probes below would answer 422 for the wrong
     * reason — an undeclared selector rather than the thing each one is about —
     * and would pass while measuring something else entirely.
     */
    @BeforeEach
    void declareTheItemView() {
        selectors.declare(UUID.fromString(SubstrateDatabaseResource.SCOPE_ID), Selector.ITEM);
    }

    // =======================================================================
    // Stage 2 before stage 3: the vocabulary answer never reaches a stranger
    // =======================================================================

    @Test
    void a_scope_the_caller_cannot_see_answers_404_however_broken_the_call() {
        SurfaceFixture.asStranger(identity);

        given()
            .when().post(SurfaceFixture.item(Selector.ITEM, 1) + ":send")
            .then()
            .statusCode(404)
            .body("reason", is("SCOPE_UNRESOLVED"));
    }

    /**
     * The counter-probe: the same call, from a subject the scope is open to.
     *
     * <p>422 and a category error naming the verb. This is what the stranger
     * above must NOT be able to learn, and it is also what makes the 404 above
     * a statement about the check order rather than about a surface that
     * refuses everything.
     */
    @Test
    void the_same_call_from_a_member_answers_the_vocabulary_error() {
        SurfaceFixture.asMember(identity);

        given()
            .when().post(SurfaceFixture.item(Selector.ITEM, 1) + ":send")
            .then()
            .statusCode(422)
            .body("reason", is("VERB_UNCARRIED"));
    }

    /**
     * The same order holds for a verb that is carried and unbuilt.
     *
     * <p>Worth its own case because this refusal carries a 501 — the one status
     * on this surface that says the fault is ours — and a 5xx is exactly the
     * kind of answer that gets written before the visibility check rather than
     * after it, on the reasoning that it is not about the caller at all.
     */
    @Test
    void an_unbuilt_verb_leaks_nothing_either() {
        SurfaceFixture.asStranger(identity);
        given()
            .when().post(SurfaceFixture.item(Selector.ITEM, 1) + ":claim")
            .then()
            .statusCode(404)
            .body("reason", is("SCOPE_UNRESOLVED"));

        SurfaceFixture.asMember(identity);
        given()
            .when().post(SurfaceFixture.item(Selector.ITEM, 1) + ":claim")
            .then()
            .statusCode(501)
            .body("reason", is("VERB_UNBUILT"));
    }

    /**
     * A scope that does not exist and a scope that is closed answer the same
     * thing.
     *
     * <p>This is the other half of "404 and never 403", and it is the half a
     * probe has to state: if the two answered differently, the difference would
     * be the enumerator, whatever status either of them carried.
     */
    @Test
    void an_absent_scope_and_a_closed_one_are_indistinguishable() {
        SurfaceFixture.asMember(identity);

        given()
            .when().get("/api/" + NO_SUCH_SCOPE + "/" + Selector.ITEM)
            .then()
            .statusCode(404)
            .body("reason", is("SCOPE_UNRESOLVED"));

        SurfaceFixture.asStranger(identity);
        given()
            .when().get(SurfaceFixture.collection(Selector.ITEM))
            .then()
            .statusCode(404)
            .body("reason", is("SCOPE_UNRESOLVED"));
    }

    // =======================================================================
    // Stage 1 is the deliberate exception
    // =======================================================================

    /**
     * A grammar violation answers 400 even to a stranger, and that is correct.
     *
     * <p>A form error is decidable without knowing any scope, so answering it
     * leaks nothing: the caller learns that {@code FEAT} is not a view, which is
     * a fact about this platform's object model and not about any deployment of
     * it. Moving this behind stage 2 would cost a caller the one diagnosis they
     * can act on without help.
     */
    @Test
    void a_form_error_answers_400_to_a_stranger_because_it_names_no_scope() {
        SurfaceFixture.asStranger(identity);

        given()
            .when().get("/api/" + SurfaceFixture.SCOPE + "/FEAT/1")
            .then()
            .statusCode(400)
            .body("reason", is("ADDRESS_MALFORMED"));
    }

    /**
     * And the depth check is in stage 1 with it.
     *
     * <p>Whether a verb accepts a truncated address depends on the verb, which
     * is known immediately, so it is scope-independent and must never migrate to
     * the vocabulary stage. A stranger gets 405 here rather than 404 — and that
     * is not a leak: what they learn is that {@code close} acts on a complete
     * address, which is true of this platform everywhere.
     */
    @Test
    void the_depth_check_runs_in_stage_one_and_answers_a_stranger_directly() {
        SurfaceFixture.asStranger(identity);

        given()
            .when().post(SurfaceFixture.collection(Selector.ITEM) + ":close")
            .then()
            .statusCode(405)
            .header("Allow", is("GET, POST"))
            .body("reason", is("WRITE_ON_TRUNCATED_ADDRESS"));
    }

    // =======================================================================
    // Stage 3 before stage 4: vocabulary before resolution
    // =======================================================================

    /**
     * An undeclared view is a vocabulary error and not a not-found.
     *
     * <p>The distinction is what a caller does next. "Not found" sends them
     * looking for the object at another number; "this scope has not declared
     * that view" tells them the address space itself is not open here, which no
     * amount of retrying will change.
     *
     * <p>The counter-probe is the declared view answering something else for the
     * very same shape of call — a 404 from the resolution stage, which is the
     * answer that would be wrong one line earlier.
     */
    @Test
    void an_undeclared_view_is_answered_apart_from_an_absent_object() {
        SurfaceFixture.asMember(identity);

        given()
            .when().get(SurfaceFixture.item("milestone", 999_999))
            .then()
            .statusCode(422)
            .body("reason", is("SELECTOR_UNDECLARED"));

        given()
            .when().get(SurfaceFixture.item(Selector.ITEM, 999_999))
            .then()
            .statusCode(404)
            .body("reason", is("ITEM_UNKNOWN"));
    }
}
