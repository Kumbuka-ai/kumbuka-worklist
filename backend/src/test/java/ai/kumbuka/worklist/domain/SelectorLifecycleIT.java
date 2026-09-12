package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.Db;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The selector lifecycle across the Kumbuka-shaped state a bootstrap-scope
 * script leaves behind: selectors declared as raw rows, no scope-wide
 * {@code number_space} row for {@code iteration} or {@code milestone}.
 *
 * <h2>Kriterium 10 — the roter Nachweis, both halves</h2>
 *
 * <p>SPRINT_180.5 lets the allocator open a missing scope-wide counter
 * lazily. The two probes below hold the fix in place.
 *
 * <ul>
 *   <li>{@link
 *       #create_on_iteration_refuses_when_the_selector_row_is_missing}
 *       preserves the {@code SELECTOR_UNDECLARED} refusal for the state it
 *       still means: the selector itself does not exist in this scope. The
 *       guard against a mis-spelt address is unchanged — a caller who
 *       reaches this refusal has named a selector no scope declared.</li>
 *   <li>{@link
 *       #create_on_iteration_allocates_a_number_space_lazily_and_succeeds}
 *       captures the RED trace: with the fix REMOVED, this call throws
 *       {@code SELECTOR_UNDECLARED} because the selector row is present but
 *       the scope-wide {@code number_space} row is not. With the fix in
 *       place, {@code allocate} opens the missing row and the create
 *       returns the axis's first number.</li>
 * </ul>
 *
 * <p>The pair is the "beide Hälften" the dispatch names. Neither probe on
 * its own would be enough: the guard is preserved for the case it still
 * catches, and the lazy path is exercised on the case it now admits.
 *
 * <h2>How the Kumbuka state is planted, and why through the migrator</h2>
 *
 * <p>The Kumbuka scope was opened by a bootstrap script that inserts
 * {@code selector} rows as raw SQL — bypassing
 * {@link SelectorRegistry#declare}, which is the ONLY path that opens the
 * accompanying {@code number_space} row. Reproducing that shape here means
 * planting the rows without going through the declaring verb: the fixture
 * writes {@code scope_setting} and the four selector rows via the migrator
 * (the schema owner, subject to RLS like every other writer), and then
 * calls the service. Anything less faithful would exercise a different
 * state; anything more elaborate would be probing the bootstrap script
 * rather than the allocator.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class SelectorLifecycleIT {

    @Inject IterationService iterations;
    @Inject MilestoneService milestones;
    @Inject SelectorRegistry selectors;

    private UUID scope;
    private UUID tenant;

    @BeforeEach
    void aFreshScope() {
        scope  = UUID.randomUUID();
        tenant = PlanningFixture.boundTenant();
    }

    // ==================================================================
    // Probe A — the SELECTOR_UNDECLARED guard, still in place.
    // ==================================================================

    /**
     * A scope where the {@code iteration} selector row itself does not
     * exist refuses with {@code SELECTOR_UNDECLARED} — the guard against a
     * mis-spelt address is unchanged.
     *
     * <p>Post-fix this test still passes: the lazy path is entered only
     * when the selector row was resolved. When it was not, the refusal
     * comes from {@link SelectorRegistry#require}, which never reaches the
     * allocator.
     */
    @Test
    void create_on_iteration_refuses_when_the_selector_row_is_missing() {
        plantKumbukaShape(scope, tenant, false, false);

        WorklistException refusal = refusalFrom(() ->
            iterations.create(scope, Map.of(
                "motto", "any", "description", "any")));

        assertThat(refusal.reason())
            .as("selector row absent: the guard against a mis-spelt address stands, "
                + "unchanged by the lazy-init path")
            .isEqualTo(WorklistException.Reason.SELECTOR_UNDECLARED);
    }

    // ==================================================================
    // Probe B — the Kumbuka shape now admits the create.
    // ==================================================================

    /**
     * A scope with the iteration selector declared but no scope-wide
     * {@code number_space} row for it — the Kumbuka shape — used to refuse
     * with {@code SELECTOR_UNDECLARED} at
     * {@link SelectorRegistry#allocate}. Post-fix the allocator opens the
     * row lazily and hands out the first number.
     *
     * <p>The RED trace: comment out the lazy-init branch in
     * {@code SelectorRegistry.allocate} and this test throws
     * {@code SELECTOR_UNDECLARED} — the same refusal Kumbuka's own
     * scope observed for weeks.
     */
    @Test
    void create_on_iteration_allocates_a_number_space_lazily_and_succeeds() {
        plantKumbukaShape(scope, tenant, true, false);

        Map<String, Object> created = iterations.create(scope, Map.of(
            "motto", "sprint 180.5",
            "description", "the lazy-init probe"));

        assertThat(created.get("number"))
            .as("with the row missing the pre-fix service refused; the fix opens the "
                + "counter at zero and the first allocation returns one")
            .isEqualTo(1L);
    }

    /**
     * The milestone counter has the second shape: the pre-fix Kumbuka
     * bootstrap did seed a per-workstream milestone row alongside the
     * default workstream. {@link SelectorRegistry#allocate} still reads
     * the scope-wide row (V12), so it read null on Kumbuka too. Post-fix
     * the allocator carries the stray row forward — sets its
     * {@code workstream_id} to null — and hands out the first number.
     *
     * <p>Two states verified together: the create succeeds AND the row
     * that was per-workstream is now scope-wide, so the next allocation
     * takes it under lockSpace directly.
     */
    @Test
    void create_on_milestone_carries_the_stray_per_workstream_row_forward() throws SQLException {
        plantKumbukaShape(scope, tenant, true, true);

        Map<String, Object> first = milestones.create(scope, Map.of(
            "title", "a real goal", "vision", "the north star in a sentence"));

        assertThat(first.get("number"))
            .as("the stray per-workstream row was carried forward to scope-wide, "
                + "its mark advanced from zero to one")
            .isEqualTo(1L);

        // The row is now scope-wide, so a second allocation goes through
        // the normal lockSpace path without the fallback.
        Map<String, Object> second = milestones.create(scope, Map.of(
            "title", "another goal", "vision", "another star"));
        assertThat(second.get("number"))
            .as("the normalised row keeps counting: mark advanced to two, no second "
                + "fallback needed")
            .isEqualTo(2L);

        assertThat(strayPerWorkstreamRowsFor(scope, "milestone"))
            .as("the stray row was normalised in place — no per-workstream row for "
                + "the milestone selector survives")
            .isZero();
    }

    // ==================================================================
    // Planting.
    // ==================================================================

    /**
     * Plant a scope-opening as bootstrap-scope.sql used to leave it: the
     * settings row, four selector rows, and — optionally — the stray
     * per-workstream milestone {@code number_space} row that the pre-fix
     * bootstrap seeded. No scope-wide {@code number_space} row for any
     * of the four selectors is planted here; that is the Kumbuka state
     * the fix meets.
     *
     * <p>When {@code declareSelectors} is false, no selector rows are
     * planted either — the state where the guard for a missing selector
     * still fires.
     */
    private static void plantKumbukaShape(UUID scope, UUID tenant,
            boolean declareSelectors, boolean strayMilestoneRow) {
        try (Connection c = Db.asMigrator()) {
            Db.bindTenant(c, tenant);
            insertSetting(c, tenant, scope);
            if (declareSelectors) {
                for (String token : Selector.VIEWS) {
                    insertSelector(c, tenant, scope, token);
                }
                if (strayMilestoneRow) {
                    UUID defaultWorkstream = insertDefaultWorkstream(c, tenant, scope);
                    insertPerWorkstreamMilestoneCounter(c, tenant, scope, defaultWorkstream);
                }
            }
            c.commit();
        } catch (SQLException e) {
            throw new IllegalStateException(
                "the Kumbuka-shape planting failed", e);
        }
    }

    private static void insertSetting(Connection c, UUID tenant, UUID scope) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("""
                INSERT INTO worklist.scope_setting
                    (tenant_id, scope_id, max_planned_iterations, warn_planned_iterations,
                     max_memberships_per_iteration, warn_memberships_per_iteration,
                     default_columns)
                VALUES (?::uuid, ?::uuid, 10, 9, 10, 9, '{}')
                """)) {
            st.setString(1, tenant.toString());
            st.setString(2, scope.toString());
            st.execute();
        }
    }

    private static void insertSelector(Connection c, UUID tenant, UUID scope, String token)
            throws SQLException {
        try (PreparedStatement st = c.prepareStatement("""
                INSERT INTO worklist.selector (tenant_id, scope_id, token)
                VALUES (?::uuid, ?::uuid, ?)
                """)) {
            st.setString(1, tenant.toString());
            st.setString(2, scope.toString());
            st.setString(3, token);
            st.execute();
        }
    }

    private static UUID insertDefaultWorkstream(Connection c, UUID tenant, UUID scope)
            throws SQLException {
        try (PreparedStatement st = c.prepareStatement("""
                INSERT INTO worklist.workstream
                    (tenant_id, scope_id, number, token, description, is_default)
                VALUES (?::uuid, ?::uuid, 1, 'default',
                    'planted default workstream for the Kumbuka-shape probe', true)
                RETURNING id
                """)) {
            st.setString(1, tenant.toString());
            st.setString(2, scope.toString());
            try (var rs = st.executeQuery()) {
                rs.next();
                return UUID.fromString(rs.getString(1));
            }
        }
    }

    /**
     * The exact row bootstrap-scope.sql used to seed for the milestone
     * counter — bound to a workstream, not scope-wide. V12
     * {@code lockSpace} filters {@code workstream_id IS NULL} and so
     * reads null against a scope shaped this way; the lazy-init branch
     * carries this row forward.
     */
    private static void insertPerWorkstreamMilestoneCounter(Connection c, UUID tenant,
            UUID scope, UUID workstreamId) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("""
                INSERT INTO worklist.number_space
                    (tenant_id, scope_id, selector_id, workstream_id, high_water_mark)
                SELECT ?::uuid, ?::uuid, s.id, ?::uuid, 0
                FROM worklist.selector s
                WHERE s.tenant_id = ?::uuid AND s.scope_id = ?::uuid
                  AND s.token = 'milestone'
                """)) {
            st.setString(1, tenant.toString());
            st.setString(2, scope.toString());
            st.setString(3, workstreamId.toString());
            st.setString(4, tenant.toString());
            st.setString(5, scope.toString());
            st.execute();
        }
    }

    /** Count of number_space rows for the named selector that still carry a workstream_id. */
    private static int strayPerWorkstreamRowsFor(UUID scope, String selectorToken)
            throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, PlanningFixture.boundTenant());
            try (var st = c.prepareStatement("""
                    SELECT count(*)
                    FROM worklist.number_space n
                    JOIN worklist.selector s ON s.id = n.selector_id
                    WHERE n.scope_id = ?::uuid
                      AND s.token = ?
                      AND n.workstream_id IS NOT NULL
                    """)) {
                st.setString(1, scope.toString());
                st.setString(2, selectorToken);
                try (var rs = st.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            }
        }
    }

    private static WorklistException refusalFrom(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown)
            .as("the call must be refused with this service's typed refusal")
            .isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }
}
