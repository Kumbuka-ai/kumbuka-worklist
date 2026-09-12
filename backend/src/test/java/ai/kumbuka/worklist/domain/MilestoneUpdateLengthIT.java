package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The write-path length caps on a milestone's text fields.
 *
 * <h2>Kriterium 6 — the roter Nachweis</h2>
 *
 * <p>V7 caps {@code milestone.title}, {@code milestone.vision} and
 * {@code milestone.mission} as {@code CHECK (char_length(...) &lt;= N)}
 * constraints. Before SPRINT_180.5 the {@code update} path passed the
 * incoming text straight to {@code flushAndRefresh}; an over-cap value
 * therefore reached the database, was refused there as a constraint
 * violation, and escaped the flush as a {@link
 * jakarta.persistence.PersistenceException} that no adapter maps —
 * reaching the caller as a 500 with no JSON body.
 *
 * <p>The RED trace: comment out the {@code capped(...)} call on
 * {@code VISION} in {@code MilestoneService.applyOne} and this suite reads
 * red the same way Kumbuka's console did — the update raises a
 * {@code PersistenceException}, not a typed refusal, and the caller sees
 * an HTTP 500.
 *
 * <p>Post-fix each case below reads a typed refusal ({@code INVALID_VALUE},
 * carrying the field name), and the row is unchanged in the store.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class MilestoneUpdateLengthIT {

    @Inject MilestoneService milestones;
    @Inject ScopeSettingService settings;
    @Inject SelectorRegistry selectors;

    private UUID scope;

    @BeforeEach
    void aFreshScope() {
        scope = UUID.randomUUID();
        settings.create(scope, Map.of(
            "max_planned_iterations", 5,
            "warn_planned_iterations", 4,
            "max_memberships_per_iteration", 10,
            "warn_memberships_per_iteration", 9));
    }

    // ==================================================================
    // Vision, on update — the case observed on Kumbuka's own scope.
    // ==================================================================

    @Test
    void an_update_with_an_over_cap_vision_is_a_typed_refusal_and_not_a_500() {
        UUID milestoneId = createGoal();
        String token = tokenOf(milestoneId);
        String held = (String) milestones.read(scope, milestoneId).get("vision");
        String twoThousand = "v".repeat(2000);

        WorklistException refusal = refusalFrom(() ->
            milestones.update(scope, milestoneId, Map.of(
                "vision", twoThousand,
                "conflict_token", token)));

        assertThat(refusal.reason())
            .as("the 200-cap ck_milestone_vision_length is checked in the service "
                + "as a typed refusal — before the flush hands the value to the "
                + "constraint that would raise an unmapped PersistenceException")
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders())
            .as("the offender names the field, so the caller can act on it without "
                + "parsing prose")
            .containsExactly("vision");
        assertThat(refusal.getMessage())
            .as("the message names the cap and the length that was given — the "
                + "value the caller can compare against")
            .contains("200").contains("2000");

        // And the row is unchanged in the store.
        assertThat(milestones.read(scope, milestoneId).get("vision"))
            .as("nothing was written: a refusal before flush leaves the row exactly "
                + "as it was")
            .isEqualTo(held);
    }

    // ==================================================================
    // Title, on update.
    // ==================================================================

    @Test
    void an_update_with_an_over_cap_title_is_a_typed_refusal() {
        UUID milestoneId = createGoal();
        String token = tokenOf(milestoneId);
        String twoThousand = "t".repeat(2000);

        WorklistException refusal = refusalFrom(() ->
            milestones.update(scope, milestoneId, Map.of(
                "title", twoThousand,
                "conflict_token", token)));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly("title");
    }

    // ==================================================================
    // Mission, on update — same class of refusal, at the higher cap.
    // ==================================================================

    @Test
    void an_update_with_an_over_cap_mission_is_a_typed_refusal() {
        UUID milestoneId = createGoal();
        String token = tokenOf(milestoneId);
        // V7 caps mission at 1500.
        String twoThousand = "m".repeat(2000);

        WorklistException refusal = refusalFrom(() ->
            milestones.update(scope, milestoneId, Map.of(
                "mission", twoThousand,
                "conflict_token", token)));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly("mission");
        assertThat(refusal.getMessage()).contains("1500");
    }

    // ==================================================================
    // The mirror case on create.
    // ==================================================================

    @Test
    void a_create_with_an_over_cap_title_is_a_typed_refusal_and_no_row_is_written() {
        String twoThousand = "T".repeat(2000);
        long before = milestones.query(scope).size();

        WorklistException refusal = refusalFrom(() ->
            milestones.create(scope, Map.of(
                "title", twoThousand,
                "vision", "any vision")));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly("title");
        assertThat(milestones.query(scope))
            .as("nothing was allocated and nothing was written; the mark is not "
                + "advanced either")
            .hasSize((int) before);
    }

    // ==================================================================
    // The legitimate half — a value AT the cap passes.
    // ==================================================================

    @Test
    void an_update_with_a_vision_exactly_at_the_cap_passes() {
        UUID milestoneId = createGoal();
        String token = tokenOf(milestoneId);
        String twoHundred = "v".repeat(200);

        Map<String, Object> updated = milestones.update(scope, milestoneId, Map.of(
            "vision", twoHundred,
            "conflict_token", token));

        assertThat(updated.get("vision"))
            .as("the cap is INCLUSIVE — exactly 200 characters passes and reaches "
                + "the row")
            .isEqualTo(twoHundred);
    }

    // ==================================================================
    // Planting.
    // ==================================================================

    private UUID createGoal() {
        return (UUID) milestones.create(scope, Map.of(
            "title", "starting title",
            "vision", "starting vision",
            "mission", "starting mission")).get("id");
    }

    private String tokenOf(UUID milestoneId) {
        return (String) milestones.read(scope, milestoneId).get("conflict_token");
    }

    private static WorklistException refusalFrom(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown)
            .as("the call must be refused with this service's typed refusal, not "
                + "an escaping PersistenceException")
            .isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }
}
