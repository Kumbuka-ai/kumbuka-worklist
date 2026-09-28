package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.Field;
import ai.kumbuka.worklist.domain.ItemService;
import ai.kumbuka.worklist.domain.MilestoneService;
import ai.kumbuka.worklist.domain.ScopeSettingService;
import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.domain.VocabularyRegistry;
import ai.kumbuka.worklist.domain.WorklistException;
import ai.kumbuka.worklist.domain.Workstream;
import ai.kumbuka.worklist.domain.WorkstreamService;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * {@code workstream} is off the milestone, in both directions.
 *
 * <h2>What went wrong, and what this suite is therefore about</h2>
 *
 * V12 retracted the milestone-workstream edge in the database on 2026-09-09 —
 * TAR-0002 section 4 (accepted) says a milestone belongs to no workstream, and
 * REQ-0148 is obsolete. The SURFACE kept the field for nineteen days. It came
 * back in every milestone answer carrying a workstream token, and a write on it
 * was taken, answered with the new value and an empty warning list, and landed
 * on a column no invariant reads. On 2026-09-28 a caller read that field,
 * concluded milestones could be filed per workstream, and built two decisions
 * on the conclusion.
 *
 * <p>So the defect is not the dead column. It is that the surface kept selling
 * a retracted relation as a live one, and said nothing when written to. Both
 * halves are asserted here, because fixing one without the other leaves the
 * other: a field removed from the answer but still accepted on a write is the
 * same silent write with less warning, and a field refused on a write but still
 * in the answer invites the write it then refuses.
 *
 * <h2>The two directions</h2>
 *
 * <p><strong>Reading shows no field.</strong> Not on {@code read}, not on
 * {@code query}, and not on the answers {@code create} and {@code update} give
 * back — the four are one projection, and asserting one of them would leave the
 * other three able to drift apart from it.
 *
 * <p><strong>Writing is refused, by name.</strong>
 * {@link WorklistException.Reason#FIELD_RETRACTED} and not
 * {@code UNKNOWN_FIELD}: the caller is not holding a typo, and the message
 * carries the retraction's own prose naming TAR-0002 section 4, so they can act
 * on it without asking anybody. Refused on {@code create} and on
 * {@code update}, because the two resolve the caller's map on separate paths.
 *
 * <h2>Why a hard refusal does not break the round trip</h2>
 *
 * A field a caller may not set is normally refused only when it carries a
 * CHANGED value, because a caller sending a read answer back is doing the
 * obvious thing. That rule needs the field to be IN the read answer. This one
 * is not, in the same change — which is why the refusal can be absolute, and
 * why {@link #echoing_a_read_answer_back_does_not_trip_the_refusal} is here to
 * hold the two halves together: it reads a milestone and writes the whole
 * answer back, and the write must not be refused.
 *
 * <h2>The red probes, observed</h2>
 *
 * Recorded in the return of satellite/32.0. Each was run by putting back the
 * one line the fix removed:
 *
 * <ul>
 *   <li><strong>read direction</strong> — restoring the
 *       {@code fields.put(Field.WORKSTREAM_ID…)} line in
 *       {@code MilestoneService.project} turns the four
 *       {@code no_workstream_…} tests red.</li>
 *   <li><strong>write direction</strong> — restoring {@code MILESTONE} to
 *       {@code Field.WORKSTREAM_ID}'s carried-by and settable-on sets turns the
 *       refusal tests red: the write is taken again, which is the defect
 *       itself.</li>
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class MilestoneWorkstreamRetractionIT {

    /** The scope the surface probes address, staged by {@link SurfaceFixture}. */
    private static final UUID SURFACE_SCOPE = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);

    @Inject TestIdentityAssociation identity;
    @Inject MilestoneService milestones;
    @Inject ItemService items;
    @Inject WorkstreamService workstreams;
    @Inject VocabularyRegistry vocabulary;
    @Inject SelectorRegistry selectors;
    @Inject ScopeSettingService settings;

    /**
     * A scope of this class's own, for the assertions that count what the scope
     * holds.
     *
     * <p>The surface probes address {@link #SURFACE_SCOPE}, which other classes
     * also write into, so "this scope holds no milestone" is not decidable
     * there. Separating the two is what makes
     * {@link #a_refused_create_leaves_no_milestone_behind} an assertion about
     * the refusal rather than about execution order.
     */
    private UUID scope;

    private UUID openStatus;

    @BeforeEach
    void twoScopes() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);
        selectors.declare(SURFACE_SCOPE, Selector.MILESTONE);
        openTheSurfaceScope();

        scope = UUID.randomUUID();
        openStatus = vocabulary.declareStatus(scope, "open", 1, true, false, false, false).id;
        settings.create(scope, Map.of(
            "max_planned_iterations", 10, "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10, "warn_memberships_per_iteration", 9));
        selectors.declare(scope, Selector.ITEM);
        selectors.declare(scope, Selector.MILESTONE);
    }

    // =======================================================================
    // Reading: the field is gone from all four answers
    // =======================================================================

    @Test
    void no_workstream_in_the_create_answer() {
        assertThat(aMilestone("a goal with no stream"))
            .as("the answer a create gives back is a projection like any other, and "
                + "the first one a caller sees. A field here that read does not carry "
                + "would be the round-trip trap in reverse")
            .doesNotContainKey(Field.WORKSTREAM_ID.canonicalName());
    }

    @Test
    void no_workstream_in_the_read_answer() {
        UUID id = (UUID) aMilestone("a goal to read back").get(Field.ID.canonicalName());

        assertThat(milestones.read(scope, id))
            .as("a milestone belongs to no workstream (TAR-0002 section 4), so its "
                + "answer names none")
            .doesNotContainKey(Field.WORKSTREAM_ID.canonicalName());
    }

    @Test
    void no_workstream_in_the_query_answer() {
        aMilestone("first goal");
        aMilestone("second goal");

        assertThat(milestones.query(scope))
            .hasSize(2)
            .allSatisfy(milestone -> assertThat(milestone)
                .as("query and read are the same projection; a field that survived in "
                    + "one of them would be the drift this shares a method to prevent")
                .doesNotContainKey(Field.WORKSTREAM_ID.canonicalName()));
    }

    @Test
    void no_workstream_in_the_update_answer() {
        Map<String, Object> created = aMilestone("a goal to rename");

        Map<String, Object> updated = milestones.update(
            scope, (UUID) created.get(Field.ID.canonicalName()),
            Map.of(Field.TITLE.canonicalName(), "a goal, renamed",
                Field.CONFLICT_TOKEN.canonicalName(),
                created.get(Field.CONFLICT_TOKEN.canonicalName())));

        assertThat(updated)
            .as("the answer an effective write gives back is the same projection again")
            .doesNotContainKey(Field.WORKSTREAM_ID.canonicalName());
    }

    /**
     * On the wire too, and this is the assertion the finding was actually made
     * against: the caller who drew the wrong conclusion was reading the MCP
     * surface, not a Java map.
     */
    @Test
    void no_workstream_on_the_wire() {
        long number = numberOnTheSurface("a goal read over the surface");

        given()
            .when().get(SurfaceFixture.item(Selector.MILESTONE, number))
            .then()
            .statusCode(200)
            .body("fields.title", is("a goal read over the surface"))
            .body("fields.workstream", is(nullValue()))
            .body("fields", not(org.hamcrest.Matchers.hasKey("workstream")));
    }

    // =======================================================================
    // Writing: refused by name, and nothing lands
    // =======================================================================

    @Test
    void create_naming_a_workstream_is_refused_as_retracted() {
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");

        WorklistException refusal = refusalFrom(() -> milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), "a goal filed in a stream",
            Field.VISION.canonicalName(), "a north star",
            Field.WORKSTREAM_ID.canonicalName(), mobile.token)));

        assertThat(refusal.reason())
            .as("FIELD_RETRACTED and not UNKNOWN_FIELD. The caller is holding a name "
                + "this service published and answered reads with; telling them no "
                + "such field exists sends them to check their spelling")
            .isEqualTo(WorklistException.Reason.FIELD_RETRACTED);
        assertThat(refusal.offenders())
            .as("the offender is the argument name, as with the other two field refusals")
            .containsExactly(Field.WORKSTREAM_ID.canonicalName());
        assertThat(refusal.getMessage())
            .as("the message has to let the caller act without asking anybody: what was "
                + "retracted, the ratified document it rests on, that nothing was "
                + "written, and which edge stands in its place")
            .contains("belongs to no workstream")
            .contains("TAR-0002 section 4")
            .contains("Nothing was written")
            .contains("an item belongs to exactly one workstream");
    }

    @Test
    void update_naming_a_workstream_is_refused_as_retracted() {
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");
        Map<String, Object> created = aMilestone("a settled goal");

        WorklistException refusal = refusalFrom(() -> milestones.update(
            scope, (UUID) created.get(Field.ID.canonicalName()),
            Map.of(Field.WORKSTREAM_ID.canonicalName(), mobile.token,
                Field.CONFLICT_TOKEN.canonicalName(),
                created.get(Field.CONFLICT_TOKEN.canonicalName()))));

        assertThat(refusal.reason())
            .as("create and update resolve the caller's map on separate paths, so the "
                + "refusal is asserted on both. This is the exact call that was taken "
                + "silently on 2026-09-28")
            .isEqualTo(WorklistException.Reason.FIELD_RETRACTED);
    }

    /**
     * The refusal is a write that did not land, and not merely a message.
     *
     * <p>Counted in this class's own scope, which is what makes the count mean
     * something: the create is the only one that has run there.
     */
    @Test
    void a_refused_create_leaves_no_milestone_behind() {
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");

        refusalFrom(() -> milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), "a goal that must not exist",
            Field.WORKSTREAM_ID.canonicalName(), mobile.token)));

        assertThat(milestones.query(scope))
            .as("the refusal happens while the caller's map is being resolved, before "
                + "a row or a number is taken. A refusal that had already allocated a "
                + "number would burn one on every typo")
            .isEmpty();
    }

    /** And on the wire, with the status and the reason name a caller matches on. */
    @Test
    void the_wire_refusal_is_422_and_names_the_reason() {
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("title", "a goal filed in a stream", "workstream", "default"))
            .when().post(SurfaceFixture.collection(Selector.MILESTONE))
            .then()
            .statusCode(422)
            .body("reason", is(WorklistException.Reason.FIELD_RETRACTED.name()))
            .body("message", containsString("TAR-0002 section 4"))
            .body("data.offenders", is(List.of("workstream")));
    }

    // =======================================================================
    // What must NOT have moved with it
    // =======================================================================

    /**
     * Reading a milestone and writing the whole answer back is not refused.
     *
     * <p>This is the pair to the hard refusal above. The refusal is absolute
     * rather than echo-tolerant only because the field left the answer in the
     * same change; if it ever came back into the projection without coming back
     * into the catalogue, every read-modify-write would start failing and this
     * test is what says so.
     */
    @Test
    void echoing_a_read_answer_back_does_not_trip_the_refusal() {
        UUID id = (UUID) aMilestone("a goal to round-trip").get(Field.ID.canonicalName());

        Map<String, Object> answer = new HashMap<>(milestones.read(scope, id));
        answer.put(Field.TITLE.canonicalName(), "a goal, round-tripped");

        assertThat(milestones.update(scope, id, answer))
            .as("the read answer carries no retracted name, so sending it back whole is "
                + "not a refusal. If this fails, the projection and the catalogue have "
                + "been changed apart")
            .containsEntry(Field.TITLE.canonicalName(), "a goal, round-tripped");
    }

    /**
     * The item's workstream is untouched. That edge is the one TAR-0002
     * section 4 keeps — every item belongs to exactly one workstream — and a
     * removal that took it with it would break the model in the other
     * direction.
     */
    @Test
    void an_item_still_carries_its_workstream() {
        Workstream mobile = workstreams.declare(scope, "mobile", "mobile stream");

        Map<String, Object> item = items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "an item in a stream",
            Field.STATUS.canonicalName(), vocabulary.requireStatus(scope, openStatus).name,
            Field.WORKSTREAM_ID.canonicalName(), mobile.token));

        assertThat(item)
            .as("the edge that stands: an item belongs to exactly one workstream, and "
                + "the retraction on the milestone must not have reached it")
            .containsEntry(Field.WORKSTREAM_ID.canonicalName(), mobile.token);
    }

    /**
     * A retracted name is answered as retracted even beside a misspelt one.
     *
     * <p>Precedence rather than decoration: the unknown-field message lists the
     * milestone's fields, and a caller who saw {@code workstream} missing from
     * that list would go looking for a spelling that never existed.
     */
    @Test
    void the_retraction_is_reported_before_a_typo() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put(Field.TITLE.canonicalName(), "a goal with two bad names");
        arguments.put(Field.WORKSTREAM_ID.canonicalName(), "default");
        arguments.put("Vision", "a capitalised misspelling");

        WorklistException refusal = refusalFrom(() -> milestones.create(scope, arguments));

        assertThat(refusal.reason())
            .isEqualTo(WorklistException.Reason.FIELD_RETRACTED);
        assertThat(refusal.offenders())
            .as("only the retracted name; the typo is the caller's next round trip and "
                + "the message that names it is a different sentence")
            .containsExactly(Field.WORKSTREAM_ID.canonicalName());
    }

    // =======================================================================
    // Planting.
    // =======================================================================

    /** A milestone in this class's own scope, named by nothing but its title. */
    private Map<String, Object> aMilestone(String title) {
        return milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), title,
            Field.VISION.canonicalName(), "the north star of " + title));
    }

    /**
     * The surface scope's settings row, which the milestone allocator needs.
     *
     * <p>Written through the service and not the surface, because
     * {@code scope_setting} carries no view and is not addressed — the same
     * note {@code SurfaceCoverageIT} records. Tolerating
     * {@code SETTING_PRESENT} is what makes it idempotent against a scope
     * another class in this suite has already opened.
     */
    private void openTheSurfaceScope() {
        try {
            settings.create(SURFACE_SCOPE, Map.of(
                "max_planned_iterations", 100, "warn_planned_iterations", 99,
                "max_memberships_per_iteration", 100,
                "warn_memberships_per_iteration", 99));
        } catch (WorklistException alreadyOpen) {
            if (alreadyOpen.reason() != WorklistException.Reason.SETTING_PRESENT) {
                throw alreadyOpen;
            }
        }
    }

    /** A milestone in the surface scope, and its number. */
    private long numberOnTheSurface(String title) {
        return ((Number) given()
            .contentType(ContentType.JSON)
            .body(Map.of("title", title, "vision", "the north star of " + title))
            .when().post(SurfaceFixture.collection(Selector.MILESTONE))
            .then()
            .statusCode(201)
            .extract().path("fields.number")).longValue();
    }

    private static WorklistException refusalFrom(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown)
            .as("the call must be refused with the service's typed refusal")
            .isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }
}
