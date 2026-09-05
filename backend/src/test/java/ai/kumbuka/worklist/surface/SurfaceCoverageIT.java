package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.domain.VocabularyRegistry;
import ai.kumbuka.worklist.domain.WorklistException;
import ai.kumbuka.worklist.platform.PlatformFixture;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conformance probe, dynamic half: is every specified outward form
 * reachable, end to end, against a running service and a running database.
 *
 * <p>The static half checks closure — that no route exists which the
 * specification does not declare — by reading the annotations. It cannot check
 * the other direction, because a form is reachable or it is not and only a call
 * can say. So this class calls every one of them.
 *
 * <h2>What counts as reached</h2>
 *
 * <strong>Answered by the surface</strong>, not by the framework. A route the
 * framework never registered answers 404 with no body of ours; a verb whose arm
 * was never written answers 500. So every assertion below is on a status the
 * surface chose AND on the {@code reason} it carries — the second half is what
 * tells a framework 404 apart from a surface one.
 *
 * <p>The ten carried verbs are <strong>executed</strong> rather than probed for
 * a refusal. A verb that answered a typed refusal for every input would satisfy
 * a probe that only looked at the shape of the answer, and it would be a surface
 * that does nothing.
 *
 * <h2>Why the chain runs in one method</h2>
 *
 * The acts form one: an item is created, planned into an iteration, its
 * membership is read and changed, unplanned, and the iteration is closed. Split
 * across methods, each would have to stage the middle of the chain behind the
 * surface being probed — writing the rows directly, which is exactly the thing
 * the probe exists to avoid doing.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class SurfaceCoverageIT {

    private static final UUID SCOPE_ID = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);

    /** Roomy, so that a shared scope's earlier probes cannot exhaust the limit. */
    private static final int GENEROUS = 1_000;

    /** The forms this run reached, as {@code METHOD path}. */
    private final Set<String> reached = new LinkedHashSet<>();

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
        openTheScope();
    }

    // =======================================================================
    // The chain
    // =======================================================================

    @Test
    void every_specified_outward_form_is_reachable_and_answered_by_the_surface() {
        String status = String.valueOf(actionableStatus());
        String closed = String.valueOf(closedStatus());

        // ---- create, at collection depth -------------------------------
        ValidatableResponse created = call("POST", collection(Selector.ITEM), null,
            Map.of("title", "a coverage probe", "status", status))
            .statusCode(201);
        String itemAddress = created.extract().path("address");
        long itemNumber = numberOf(created);
        assertThat(created.extract().header("Location"))
            .as("create answers 201 with Location, built from the address the allocator "
                + "handed out and never echoed from the request")
            .endsWith("/" + Selector.ITEM + "/" + itemNumber);
        assertThat(itemAddress)
            .as("and the answer names the complete address, which is the one thing a "
                + "caller could not have predicted")
            .isEqualTo(SurfaceFixture.address(Selector.ITEM, itemNumber));

        // ---- query ------------------------------------------------------
        call("GET", collection(Selector.ITEM), null, null)
            .statusCode(200)
            .body("objects.size()", org.hamcrest.Matchers.greaterThan(0));

        // ---- read -------------------------------------------------------
        String itemToken = call("GET", item(Selector.ITEM, itemNumber), null, null)
            .statusCode(200)
            .extract().path("fields.conflict_token");

        // ---- update, with the token in the request line -----------------
        itemToken = call("PATCH", item(Selector.ITEM, itemNumber), itemToken,
            Map.of("title", "a coverage probe, renamed"))
            .statusCode(200)
            .body("fields.title", org.hamcrest.Matchers.is("a coverage probe, renamed"))
            .extract().path("fields.conflict_token");

        // ---- the goal axis: create and close ----------------------------
        ValidatableResponse milestone = call("POST", collection(Selector.MILESTONE), null,
            Map.of("title", "a coverage goal", "vision", "the north star"))
            .statusCode(201);
        long milestoneNumber = numberOf(milestone);
        String milestoneToken = milestone.extract().path("fields.conflict_token");

        // ---- the time axis: create --------------------------------------
        ValidatableResponse iteration = call("POST", collection(Selector.ITERATION), null,
            Map.of("motto", "coverage", "description", "what this iteration contains"))
            .statusCode(201);
        long iterationNumber = numberOf(iteration);
        String iterationToken = iteration.extract().path("fields.conflict_token");

        // ---- plan, which is the membership coming into being -------------
        //
        // The item has to carry a milestone on the product path, and no verb
        // of this service assigns one: `item.milestone_id` is not settable and
        // no planning verb addresses an item. That gap is reported rather than
        // closed here, and the fixture below is what standing in for the
        // missing verb looks like.
        pointAtMilestone(itemAddress, milestoneNumber);

        iterationToken = call("POST", membership(iterationNumber, itemNumber), iterationToken,
            null)
            .statusCode(201)
            .extract().path("fields.conflict_token");

        // ---- read and update the membership -----------------------------
        call("GET", membership(iterationNumber, itemNumber), null, null)
            .statusCode(200)
            .body("fields.membership_status", org.hamcrest.Matchers.notNullValue());

        iterationToken = call("PATCH", membership(iterationNumber, itemNumber), iterationToken,
            Map.of("membership_status", "active"))
            .statusCode(200)
            .extract().path("fields.conflict_token");

        // ---- advance, the one carried transition on a truncated address --
        call("POST", collection(Selector.ITERATION) + ":advance", settingToken(), null)
            .statusCode(200)
            .body("fields.current_iteration", org.hamcrest.Matchers.notNullValue());

        // ---- the moving pointer: readable, and not writable ---------------
        call("GET", item(Selector.ITERATION, AddressParser.CURRENT), null, null)
            .statusCode(200)
            .body("address", org.hamcrest.Matchers.endsWith("/" + AddressParser.CURRENT));

        call("PATCH", item(Selector.ITERATION, AddressParser.CURRENT), iterationToken,
            Map.of("motto", "written through the pointer"))
            .statusCode(405)
            .header("Allow", org.hamcrest.Matchers.is("GET"))
            .body("reason", org.hamcrest.Matchers.is("ADDRESS_READ_ONLY"));

        // ---- unplan ------------------------------------------------------
        iterationToken = call("DELETE", membership(iterationNumber, itemNumber), iterationToken,
            null)
            .statusCode(200)
            .extract().path("fields.conflict_token");

        // ---- close, on both axes -----------------------------------------
        call("POST", item(Selector.ITERATION, iterationNumber) + ":close", iterationToken, null)
            .statusCode(200)
            .body("fields.closed_at", org.hamcrest.Matchers.notNullValue());

        call("POST", item(Selector.MILESTONE, milestoneNumber) + ":close", milestoneToken, null)
            .statusCode(200)
            .body("fields.status", org.hamcrest.Matchers.is("closed"));

        // ---- accept, which is carried and refuses -------------------------
        call("POST", item(Selector.ITEM, itemNumber) + ":accept", itemToken, null)
            .statusCode(501)
            .body("reason", org.hamcrest.Matchers.is("IDENTIFIER_UNDECIDED"));

        // ---- withdraw, which is the last thing done to this item ----------
        call("POST", item(Selector.ITEM, itemNumber) + ":withdraw", itemToken,
            Map.of("status", closed))
            .statusCode(200)
            .body("fields.status", org.hamcrest.Matchers.is(closed));

        // ---- the seven the scheme does not carry --------------------------
        for (String verb : List.of("send", "append", "abandon", "block", "resume", "consume")) {
            call("POST", item(Selector.ITEM, itemNumber) + ":" + verb, null, null)
                .statusCode(422)
                .body("reason", org.hamcrest.Matchers.is("VERB_UNCARRIED"));
        }
        call("POST", collection(Selector.ITEM) + ":digest", null, null)
            .statusCode(422)
            .body("reason", org.hamcrest.Matchers.is("VERB_UNCARRIED"));

        // ---- the six it carries and this service has not built ------------
        for (String verb : List.of("claim", "release", "relate", "unrelate", "validate")) {
            call("POST", item(Selector.ITEM, itemNumber) + ":" + verb, null, null)
                .statusCode(501)
                .body("reason", org.hamcrest.Matchers.is("VERB_UNBUILT"));
        }
        call("POST", collection(Selector.ITEM) + ":claim_next", null, null)
            .statusCode(501)
            .body("reason", org.hamcrest.Matchers.is("VERB_UNBUILT"));

        // ---- and the refusal of a writing verb on a truncated address -----
        call("POST", collection(Selector.ITEM) + ":withdraw", null, null)
            .statusCode(405)
            .header("Allow", org.hamcrest.Matchers.is("GET, POST"))
            .body("reason", org.hamcrest.Matchers.is("WRITE_ON_TRUNCATED_ADDRESS"));

        // ---- a verb aimed at the wrong kind of thing ----------------------
        call("POST", item(Selector.MILESTONE, milestoneNumber) + ":withdraw", milestoneToken,
            Map.of("status", closed))
            .statusCode(422)
            .body("reason", org.hamcrest.Matchers.is("VERB_UNCARRIED"));

        call("POST", item(Selector.ITEM, itemNumber) + ":close", itemToken, null)
            .statusCode(422)
            .body("reason", org.hamcrest.Matchers.is("VERB_UNCARRIED"));

        call("POST", collection(Selector.ITEM) + ":advance", settingToken(), null)
            .statusCode(422)
            .body("reason", org.hamcrest.Matchers.is("VERB_UNCARRIED"));

        // ---- what the transport has to carry, and what the body has to say -
        call("PATCH", item(Selector.ITEM, itemNumber), null, Map.of("title", "no token"))
            .statusCode(428)
            .body("reason", org.hamcrest.Matchers.is("CONFLICT_TOKEN_MISSING"));

        call("PATCH", item(Selector.ITEM, itemNumber), "a token from nowhere",
            Map.of("title", "a stale write"))
            .statusCode(412)
            .body("reason", org.hamcrest.Matchers.is("CONFLICT"));

        call("POST", collection(Selector.ITEM), null, null)
            .statusCode(400)
            .body("reason", org.hamcrest.Matchers.is("PAYLOAD_MALFORMED"));

        call("POST", item(Selector.ITEM, itemNumber) + ":withdraw", itemToken,
            Map.of("status", "not a status identity"))
            .statusCode(400)
            .body("reason", org.hamcrest.Matchers.is("PAYLOAD_MALFORMED"));

        // ---- POST on an item address with no verb in it --------------------
        call("POST", item(Selector.ITEM, itemNumber), null, null)
            .statusCode(405)
            .header("Allow", org.hamcrest.Matchers.is("GET, PATCH, POST"))
            .body("reason", org.hamcrest.Matchers.is("WRITE_ON_TRUNCATED_ADDRESS"));

        // ===================================================================
        // The coverage assertion itself
        // ===================================================================
        assertThat(reached)
            .as("every outward form the specification declares must have been called and "
                + "answered by the surface. A form nothing reaches is a verb no caller "
                + "can use, however carefully it was written")
            .containsAll(specifiedForms());
    }

    /**
     * The forms the specification declares, in the shape this probe records
     * them.
     *
     * <p>Template parameters are what is compared, not the values this run
     * happened to use: {@code /api/{scope}/{selector}/{id}} is the form, and the
     * probe called it with a scope, a view and a number. Comparing the
     * substituted strings would compare this run with itself.
     */
    private static Set<String> specifiedForms() {
        return VerbSurfaceSpecification.outwardForms().stream()
            .map(VerbSurfaceSpecification.Row::route)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    // =======================================================================
    // Calling, and recording which form was called
    // =======================================================================

    /**
     * One call, recorded against the form it exercises.
     *
     * <p>The form is derived from the URI by putting the templates back — which
     * is the only way a probe can say "this call reached that specified row"
     * without the specification and the call sharing a variable.
     */
    private ValidatableResponse call(String method, String path, String token,
                                     Map<String, ?> body) {
        reached.add(method + " " + formOf(path));

        var request = given();
        if (token != null) {
            request = request.header("If-Match", token);
        }
        if (body != null) {
            request = request.contentType(ContentType.JSON).body(body);
        }

        return switch (method) {
            case "GET" -> request.when().get(path).then();
            case "POST" -> request.when().post(path).then();
            case "PATCH" -> request.when().patch(path).then();
            case "DELETE" -> request.when().delete(path).then();
            default -> throw new IllegalArgumentException(method);
        };
    }

    /**
     * The specified form a concrete URI exercises.
     *
     * <p>The scope and the view are put back as templates by position; the id
     * parts are recognised as numbers, and a colon verb is kept because it is
     * part of the form rather than a value in it. The one exception is the
     * refusal row, whose verb is itself a template: a colon verb at collection
     * depth that names no verb of that depth is {@code {verb}}, which is what
     * makes one row cover every such call.
     */
    private static String formOf(String path) {
        String[] parts = path.substring("/api/".length()).split("/");
        StringBuilder form = new StringBuilder("/api/{scope}");

        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (i == 1) {
                form.append("/{selector}").append(colonOf(part, true));
            } else if (i == 2) {
                form.append("/{id}").append(colonOf(part, false));
            } else {
                form.append("/{member}");
            }
        }
        return form.toString();
    }

    /** The colon verb of a segment, as the form spells it. */
    private static String colonOf(String segment, boolean atCollection) {
        int at = segment.lastIndexOf(':');
        if (at < 0) {
            return "";
        }
        String verb = segment.substring(at + 1);
        boolean declared = ai.kumbuka.worklist.adapter.rest.CustomMethod
            .at(atCollection
                ? ai.kumbuka.worklist.adapter.rest.CustomMethod.Depth.COLLECTION
                : ai.kumbuka.worklist.adapter.rest.CustomMethod.Depth.ITEM)
            .stream().anyMatch(m -> m.verb().equals(verb));
        return ":" + (declared ? verb : "{verb}");
    }

    private static String collection(String view) {
        return SurfaceFixture.collection(view);
    }

    private static String item(String view, Object number) {
        return SurfaceFixture.item(view, number);
    }

    private static String membership(Object iteration, Object item) {
        return SurfaceFixture.membership(iteration, item);
    }

    private static long numberOf(ValidatableResponse response) {
        return ((Number) response.extract().path("fields.number")).longValue();
    }

    // =======================================================================
    // The vocabulary and the settings this scope needs before it holds anything
    // =======================================================================

    /**
     * The scope's settings row, with limits roomy enough for a shared scope.
     *
     * <p>Written through the service rather than the surface, because the
     * surface does not carry it: {@code scope_setting} has no view and is not
     * addressed. That is the ratified design and not a gap in this probe — but
     * it does mean a scope cannot be opened over the machine surface at all,
     * which is reported.
     */
    private void openTheScope() {
        Map<String, Object> limits = Map.of(
            "max_planned_iterations", GENEROUS,
            "warn_planned_iterations", GENEROUS,
            "max_memberships_per_iteration", GENEROUS,
            "warn_memberships_per_iteration", GENEROUS);
        try {
            settings.create(SCOPE_ID, limits);
        } catch (WorklistException alreadyOpen) {
            if (alreadyOpen.reason() != WorklistException.Reason.SETTING_PRESENT) {
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

    private UUID actionableStatus() {
        return vocabulary.declareStatus(SCOPE_ID, "coverage-open", 1,
            true, false, false, false).id;
    }

    private UUID closedStatus() {
        return vocabulary.declareStatus(SCOPE_ID, "coverage-done", 2,
            false, false, true, true).id;
    }

    /**
     * Points an item at a milestone, over JDBC and under the runtime role.
     *
     * <p>No verb of this service assigns {@code item.milestone_id}: the field is
     * not settable on an item and no planning verb addresses an item, so the
     * precondition {@code plan} enforces — an item carries a milestone on the
     * product path — is satisfiable only by a write like this one. It is named
     * for what it is rather than hidden in a helper, because a fixture that goes
     * around the surface is a finding about the surface.
     */
    private void pointAtMilestone(String itemAddress, long milestoneNumber) {
        String number = itemAddress.substring(itemAddress.lastIndexOf('/') + 1);
        PlatformFixture.run(
            "SELECT set_config('app.tenant_id', '"
                + SubstrateDatabaseResource.TENANT_ID + "', false)",
            "UPDATE worklist.item SET milestone_id = ("
                + "  SELECT id FROM worklist.milestone WHERE scope_id = '"
                + SCOPE_ID + "' AND number = " + milestoneNumber + ")"
                + " WHERE scope_id = '" + SCOPE_ID + "' AND number = " + number);
    }
}
