package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.Db;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The write-path length caps on the four fields V7 and V16 also constrain in
 * the database. Both layers exist, and both are exercised here.
 *
 * <h2>Two halves per field</h2>
 *
 * The <strong>green half</strong> writes an over-cap value through the
 * service and observes {@code INVALID_VALUE} — the typed refusal that lands
 * BEFORE the flush, so the caller reads a domain error rather than an
 * internal one.
 *
 * <p>The <strong>red half</strong> writes the same value directly through
 * JDBC, so the check constraint answers instead. The refusal there is a
 * {@code CHECK} violation naming the constraint by its schema name — the
 * shape a caller would see if the typed refusal above were removed. Both
 * refusals stand: the typed one keeps a friendly answer on the fast path,
 * and the constraint keeps the invariant honest against every path,
 * including whatever future write goes around the domain.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class LengthCapProbeIT {

    @Inject SelectorRegistry selectors;
    @Inject ScopeSettingService settings;
    @Inject IterationService iterations;
    @Inject MilestoneService milestones;

    private UUID tenant;
    private UUID scope;

    @BeforeEach
    void aScopeOfItsOwn() {
        tenant = UUID.fromString(
            ConfigProvider.getConfig().getValue("worklist.tenant-id", String.class));
        scope = UUID.randomUUID();
        selectors.declare(scope, Selector.MILESTONE);
        selectors.declare(scope, Selector.ITERATION);
        settings.create(scope, Map.of(
            "max_planned_iterations", 10, "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10, "warn_memberships_per_iteration", 9));
    }

    // ==================================================================
    // Iteration.title — 200 characters.
    // ==================================================================

    @Test
    void iteration_title_over_200_is_refused_typed_at_the_verb_surface() {
        String tooLong = "a".repeat(201);
        WorklistException refusal = refusalFrom(() -> iterations.create(scope, Map.of(
            Field.TITLE.canonicalName(), tooLong,
            Field.MOTTO.canonicalName(), "motto",
            Field.DESCRIPTION.canonicalName(), "description")));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.getMessage())
            .as("the message names the field, the cap and the actual length")
            .contains(Field.TITLE.canonicalName(), "200", "201");
    }

    @Test
    void iteration_title_check_constraint_stands_behind_the_typed_refusal() throws SQLException {
        String tooLong = "a".repeat(201);
        SQLException raw = insertIterationExpectingConstraint(tooLong);
        assertThat(raw.getMessage())
            .as("V16 carries the CHECK on iteration.title as well; going around the "
                + "typed refusal reaches that constraint")
            .contains("ck_iteration_title_length");
    }

    // ==================================================================
    // Milestone.title — 200 characters.
    // ==================================================================

    @Test
    void milestone_title_over_200_is_refused_typed_at_the_verb_surface() {
        String tooLong = "a".repeat(201);
        WorklistException refusal = refusalFrom(() -> milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), tooLong,
            Field.KIND.canonicalName(), Milestone.GOAL)));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.getMessage()).contains(Field.TITLE.canonicalName(), "200", "201");
    }

    // ==================================================================
    // Milestone.mission — 1500 characters.
    // ==================================================================

    @Test
    void milestone_mission_over_1500_is_refused_typed_at_the_verb_surface() {
        String tooLong = "m".repeat(1501);
        WorklistException refusal = refusalFrom(() -> milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), "a fine title",
            Field.KIND.canonicalName(), Milestone.GOAL,
            Field.MISSION.canonicalName(), tooLong)));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.getMessage())
            .contains(Field.MISSION.canonicalName(), "1500", "1501");
    }

    // ==================================================================
    // Fixtures.
    // ==================================================================

    /**
     * The red half: send the value through JDBC as the migrator and record
     * the SQL exception. Returned rather than thrown so the assertion can
     * name the constraint the way a probe reads best.
     */
    private SQLException insertIterationExpectingConstraint(String tooLong) throws SQLException {
        try (Connection c = Db.asMigrator()) {
            Db.bindTenant(c, tenant);
            try (var st = c.prepareStatement("""
                    INSERT INTO worklist.iteration
                        (id, tenant_id, scope_id, number, title, motto, description)
                    VALUES (gen_random_uuid(), ?, ?, 999, ?, 'motto', 'description')
                    """)) {
                st.setObject(1, tenant);
                st.setObject(2, scope);
                st.setString(3, tooLong);
                st.executeUpdate();
                c.commit();
                throw new AssertionError(
                    "the CHECK on iteration.title lets a 201-character value pass — "
                        + "the invariant this probe is here to observe is gone");
            } catch (SQLException expected) {
                return expected;
            }
        }
    }

    private static WorklistException refusalFrom(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }
}
