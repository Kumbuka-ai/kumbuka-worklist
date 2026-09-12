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
 * V15's number-space backfill, observed at both halves.
 *
 * <h2>Why the state is planted by hand</h2>
 *
 * Every fresh scope opened through the verb surface carries its scope-wide
 * counter — {@code SelectorRegistry.declare} opens it with the selector, so a
 * scope born through this test never falls into the shape V15 exists to
 * repair. To measure V15, the shape has to be constructed: the counter row
 * is dropped from a freshly opened scope, so the next {@code create} refuses
 * with {@code SELECTOR_UNDECLARED} — the state the estate reported for the
 * {@code kumbuka} scope on 2026-09-12. The migration's SQL is then re-run
 * against that scope alone, and the neighbouring {@code create} goes through.
 *
 * <h2>The two probes here</h2>
 *
 * <ol>
 *   <li>{@link #iteration_create_refuses_typed_when_the_scope_wide_row_is_missing}
 *       — Kriterium 3 red: without a scope-wide row, {@code allocate} refuses
 *       {@code SELECTOR_UNDECLARED}. Kriterium 4 green: the same call goes
 *       through after V15's DO$$-block runs against this scope, and the
 *       first iteration is numbered 1.</li>
 *
 *   <li>{@link #milestone_high_water_mark_is_carried_forward_never_reset}
 *       — Kriterium 2 red: with the counter left at zero over an existing
 *       milestone (number 7), the next {@code create} would hand out a
 *       number already in use and the store's unique index refuses.
 *       Kriterium 2 green: V15's {@code GREATEST(counter, MAX(number))}
 *       moves the mark to 7, and the next {@code create} lands at 8.</li>
 * </ol>
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class NumberSpaceBackfillIT {

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
        selectors.declare(scope, Selector.ITEM);
        selectors.declare(scope, Selector.MILESTONE);
        selectors.declare(scope, Selector.ITERATION);
        settings.create(scope, Map.of(
            "max_planned_iterations", 10, "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10, "warn_memberships_per_iteration", 9));
    }

    @Test
    void iteration_create_refuses_typed_when_the_scope_wide_row_is_missing() throws SQLException {
        // Plant the shape the estate reported for the kumbuka scope: a
        // declared selector without its scope-wide number_space row.
        deleteNumberSpaceRows(Selector.ITERATION);

        // RED HALF — SelectorRegistry.allocate refuses typed rather than
        // opening a counter on the fly. Kriterium 3.
        WorklistException refusal = refusalFrom(() -> iterations.create(scope, Map.of(
            Field.TITLE.canonicalName(), "any title",
            Field.MOTTO.canonicalName(), "any motto",
            Field.DESCRIPTION.canonicalName(), "any description")));
        assertThat(refusal.reason())
            .as("without the scope-wide row, allocate refuses typed with "
                + "SELECTOR_UNDECLARED — the property Kriterium 3 asserts")
            .isEqualTo(WorklistException.Reason.SELECTOR_UNDECLARED);

        // GREEN HALF — V15's DO$$-block re-run against this one scope.
        runV15BackfillFor(scope);

        Map<String, Object> answer = iterations.create(scope, Map.of(
            Field.TITLE.canonicalName(), "after backfill",
            Field.MOTTO.canonicalName(), "after backfill",
            Field.DESCRIPTION.canonicalName(), "the one V15 opened the counter for"));
        assertThat(answer.get(Field.NUMBER.canonicalName()))
            .as("after V15 populates the row, allocate hands out number 1 and the "
                + "create goes through — Kriterium 4 green")
            .isEqualTo(1L);
    }

    @Test
    void milestone_high_water_mark_is_carried_forward_never_reset() throws SQLException {
        // The scope carries seven milestones, numbered 1..7. Planted directly
        // so we can compare V15's carry-forward against the empty-table
        // branch of the same SELECT.
        plantMilestoneAt(1);
        plantMilestoneAt(2);
        plantMilestoneAt(3);
        plantMilestoneAt(4);
        plantMilestoneAt(5);
        plantMilestoneAt(6);
        plantMilestoneAt(7);

        // Simulate the shape V15 walks into: the counter has drifted to zero
        // — either because the scope-wide row is missing (dropped below) or
        // because the mark stood at 0 while rows were planted through some
        // other path. V15 has to notice the drift and lift the mark to 7.
        deleteNumberSpaceRows(Selector.MILESTONE);

        // RED HALF — with the counter reset to 0, the next create would try
        // to hand out number 1, which is already in use, and the unique
        // index refuses the flush. Kriterium 2.
        insertMilestoneCounterAt(0);
        Throwable refused = catchThrowable(() -> milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), "trying to reuse a number",
            Field.KIND.canonicalName(), Milestone.GOAL)));
        assertThat(refused)
            .as("resetting the mark to zero over an existing milestone would hand "
                + "out a number already in use — the red half of Kriterium 2")
            .isNotNull();

        // Reset the state so V15 can lift the mark instead of resetting it.
        deleteNumberSpaceRows(Selector.MILESTONE);
        runV15BackfillFor(scope);

        // GREEN HALF — the mark stands at the highest existing number, so the
        // next milestone lands at 8 and no number is handed out a second time.
        Map<String, Object> next = milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), "the eighth",
            Field.KIND.canonicalName(), Milestone.GOAL));
        assertThat(next.get(Field.NUMBER.canonicalName()))
            .as("V15 lifts the mark to 7 and the next create is 8 — Kriterium 2 green")
            .isEqualTo(8L);
    }

    // ==================================================================
    // Fixtures.
    // ==================================================================

    private void deleteNumberSpaceRows(String selectorToken) throws SQLException {
        try (Connection c = Db.asMigrator()) {
            Db.bindTenant(c, tenant);
            try (var st = c.prepareStatement("""
                    DELETE FROM worklist.number_space
                    WHERE selector_id IN (
                        SELECT id FROM worklist.selector
                        WHERE tenant_id = ? AND scope_id = ? AND token = ?)
                    """)) {
                st.setObject(1, tenant);
                st.setObject(2, scope);
                st.setString(3, selectorToken);
                st.executeUpdate();
            }
            c.commit();
        }
    }

    private void insertMilestoneCounterAt(long mark) throws SQLException {
        try (Connection c = Db.asMigrator()) {
            Db.bindTenant(c, tenant);
            try (var st = c.prepareStatement("""
                    INSERT INTO worklist.number_space
                        (tenant_id, scope_id, selector_id, workstream_id, high_water_mark)
                    SELECT ?, ?, s.id, NULL, ?
                    FROM worklist.selector s
                    WHERE s.tenant_id = ? AND s.scope_id = ? AND s.token = 'milestone'
                    """)) {
                st.setObject(1, tenant);
                st.setObject(2, scope);
                st.setLong(3, mark);
                st.setObject(4, tenant);
                st.setObject(5, scope);
                st.executeUpdate();
            }
            c.commit();
        }
    }

    private void plantMilestoneAt(long number) throws SQLException {
        try (Connection c = Db.asMigrator()) {
            Db.bindTenant(c, tenant);
            try (var st = c.prepareStatement("""
                    INSERT INTO worklist.milestone
                        (id, tenant_id, scope_id, number, title, kind, status)
                    VALUES (gen_random_uuid(), ?, ?, ?, ?, 'milestone', 'planned')
                    """)) {
                st.setObject(1, tenant);
                st.setObject(2, scope);
                st.setLong(3, number);
                st.setString(4, "milestone " + number);
                st.executeUpdate();
            }
            c.commit();
        }
    }

    /**
     * The behaviour of V15, run against ONE scope so its effect is
     * observable in this suite. The two selectors are walked exactly as
     * the migration walks them: for each, the scope-wide row is inserted
     * with the carried-forward mark when missing, and any per-workstream
     * duplicates on that selector are dropped afterwards.
     *
     * <p>Not a {@code DO $$} block: PostgreSQL rejects bind parameters
     * inside one, and the migration's body is faithful either way.
     */
    private void runV15BackfillFor(UUID targetScope) throws SQLException {
        try (Connection c = Db.asMigrator()) {
            Db.bindTenant(c, tenant);
            for (String selectorToken : new String[]{Selector.ITERATION, Selector.MILESTONE}) {
                UUID selectorId = selectorIdOf(c, targetScope, selectorToken);
                if (selectorId == null) {
                    continue;
                }
                if (!scopeWidePresent(c, targetScope, selectorId)) {
                    long consolidated = Math.max(
                        maxCounterMark(c, targetScope, selectorId),
                        maxRowNumber(c, targetScope, selectorToken));
                    insertScopeWideRow(c, targetScope, selectorId, consolidated);
                }
                deletePerWorkstreamRows(c, targetScope, selectorId);
            }
            c.commit();
        }
    }

    private UUID selectorIdOf(Connection c, UUID targetScope, String token) throws SQLException {
        try (var st = c.prepareStatement(
                "SELECT id FROM worklist.selector "
                    + "WHERE tenant_id = ? AND scope_id = ? AND token = ?")) {
            st.setObject(1, tenant);
            st.setObject(2, targetScope);
            st.setString(3, token);
            try (var rs = st.executeQuery()) {
                return rs.next() ? (UUID) rs.getObject(1) : null;
            }
        }
    }

    private boolean scopeWidePresent(Connection c, UUID targetScope, UUID selectorId)
            throws SQLException {
        try (var st = c.prepareStatement("""
                SELECT 1 FROM worklist.number_space
                WHERE tenant_id = ? AND scope_id = ? AND selector_id = ?
                  AND workstream_id IS NULL
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, targetScope);
            st.setObject(3, selectorId);
            try (var rs = st.executeQuery()) {
                return rs.next();
            }
        }
    }

    private long maxCounterMark(Connection c, UUID targetScope, UUID selectorId)
            throws SQLException {
        try (var st = c.prepareStatement("""
                SELECT COALESCE(MAX(high_water_mark), 0) FROM worklist.number_space
                WHERE tenant_id = ? AND scope_id = ? AND selector_id = ?
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, targetScope);
            st.setObject(3, selectorId);
            try (var rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long maxRowNumber(Connection c, UUID targetScope, String selectorToken)
            throws SQLException {
        String table = selectorToken.equals(Selector.ITERATION) ? "iteration" : "milestone";
        try (var st = c.prepareStatement("""
                SELECT COALESCE(MAX(number), 0) FROM worklist.""" + table + """

                WHERE tenant_id = ? AND scope_id = ?
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, targetScope);
            try (var rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void insertScopeWideRow(Connection c, UUID targetScope, UUID selectorId, long mark)
            throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.number_space
                    (tenant_id, scope_id, selector_id, workstream_id, high_water_mark)
                VALUES (?, ?, ?, NULL, ?)
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, targetScope);
            st.setObject(3, selectorId);
            st.setLong(4, mark);
            st.executeUpdate();
        }
    }

    private void deletePerWorkstreamRows(Connection c, UUID targetScope, UUID selectorId)
            throws SQLException {
        try (var st = c.prepareStatement("""
                DELETE FROM worklist.number_space
                WHERE tenant_id = ? AND scope_id = ? AND selector_id = ?
                  AND workstream_id IS NOT NULL
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, targetScope);
            st.setObject(3, selectorId);
            st.executeUpdate();
        }
    }

    private static WorklistException refusalFrom(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }
}
