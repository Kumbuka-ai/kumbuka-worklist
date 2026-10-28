/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.MigrationHarness;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.worklist.tenancy.TenantMigrationCallback;
import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V24 keys every counter by its view, and loses no mark doing it.
 *
 * <h2>Why the service is not booted</h2>
 *
 * The question is what V24 does to a store that V23 left behind, so the store
 * has to be built at V23 and migrated past it. A booted service has migrated
 * to the latest version before a test can plant anything. This probe drives
 * the real migration chain with Flyway against a database of its own, stops at
 * V23, plants a scope the way the previous image leaves one, and then lets V24
 * run. The next number is then drawn under the runtime role, by the statement
 * the registry runs: lock the counter of (scope, view), move it by one.
 *
 * <h2>The marks are different per view, and none is zero</h2>
 *
 * A key that swapped two views would still find a counter for each, and with
 * equal marks it would hand out the same numbers. So every view stands at a
 * mark of its own — item 7, iteration 3, milestone 5, workstream 4 — and a
 * swapped key turns into a wrong next number. That is the counter-probe of
 * this class, and it was observed: V24 with the item and iteration tokens
 * exchanged in its fill makes the first case fail on item (next 4, expected
 * 8).
 */
class NumberSpaceViewMigrationIT {

    private static final String MIGRATOR = "view_key_migrator";
    private static final String MIGRATOR_PASSWORD = "test-only-view-key-password";

    /** The last version before the view became the key. */
    private static final String BEFORE = "23";

    private static final Map<String, Long> MARKS = marks();

    private static MigrationHarness postgres;
    private static String tenant;

    @BeforeAll
    static void startDatabase() throws SQLException {
        postgres = MigrationHarness.start();
        tenant = ConfigProvider.getConfig().getValue("worklist.tenant-id", String.class);
        asSuperuser(postgres.jdbcUrl(), "CREATE ROLE " + MIGRATOR + " LOGIN CREATEROLE "
            + "NOSUPERUSER NOBYPASSRLS PASSWORD '" + MIGRATOR_PASSWORD + "'");
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.close();
        }
    }

    /**
     * Acceptance criterion 1 and the second half of the red probe: every mark
     * survives, and the next number per view is the mark plus one.
     */
    @Test
    void every_view_hands_out_its_mark_plus_one_after_the_migration() throws SQLException {
        String url = freshDatabase("view_key_marks");
        migrate(url, BEFORE);
        UUID scope = UUID.randomUUID();
        plantScope(url, scope, MARKS);

        migrate(url, null);

        Map<String, Long> next = new LinkedHashMap<>();
        for (String view : Selector.VIEWS) {
            next.put(view, allocateAsService(url, scope, view));
        }
        assertThat(next)
            .as("each view draws from its own counter, keyed by the view since V24, and the "
                + "number it hands out is the mark V23 left plus one — item 8, iteration 4, "
                + "milestone 6, workstream 5")
            .containsExactlyInAnyOrderEntriesOf(Map.of(
                Selector.ITEM, 8L, Selector.ITERATION, 4L,
                Selector.MILESTONE, 6L, Selector.WORKSTREAM, 5L));
    }

    /**
     * The gap the bootstrap script left: a workstream selector with no
     * counter. Existence is read off the counter since V24, so V24 opens it,
     * past the highest workstream number the scope already holds.
     */
    @Test
    void a_selector_without_its_counter_gets_one_above_its_highest_number()
            throws SQLException {
        String url = freshDatabase("view_key_gap");
        migrate(url, BEFORE);
        UUID scope = UUID.randomUUID();
        Map<String, Long> withoutWorkstream = new LinkedHashMap<>(MARKS);
        withoutWorkstream.remove(Selector.WORKSTREAM);
        plantScope(url, scope, withoutWorkstream);

        migrate(url, null);

        assertThat(allocateAsService(url, scope, Selector.WORKSTREAM))
            .as("the scope holds workstreams 1 to 4 and no workstream counter; the counter "
                + "V24 opens stands at 4, so the next workstream is 5 and no number in use "
                + "is handed out twice")
            .isEqualTo(5L);
    }

    /**
     * Both images write a counter after V24, each naming one key: the
     * previous image the selector, this one the view. The trigger fills the
     * other, so the previous image runs against this schema after a
     * rollback, and the selector rows it reads keep their counters.
     */
    @Test
    void a_counter_written_with_either_key_carries_both() throws SQLException {
        String url = freshDatabase("view_key_both");
        migrate(url, null);
        UUID scope = UUID.randomUUID();

        try (Connection c = asService(url)) {
            for (String view : Selector.VIEWS) {
                execute(c, "INSERT INTO worklist.selector (tenant_id, scope_id, token) "
                    + "VALUES (?::uuid, ?, ?)", tenant, scope, view);
            }
            // The previous image: selector key only.
            execute(c, "INSERT INTO worklist.number_space (tenant_id, scope_id, selector_pk) "
                + "SELECT tenant_id, scope_id, pk FROM worklist.selector "
                + "WHERE scope_id = ? AND token = 'item'", scope);
            // This image: view only.
            execute(c, "INSERT INTO worklist.number_space (tenant_id, scope_id, view) "
                + "VALUES (?::uuid, ?, 'iteration')", tenant, scope);
            c.commit();

            assertThat(keysOf(c, scope))
                .as("each counter carries the view and the selector of the same token, "
                    + "whichever of the two its writer named")
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                    Selector.ITEM, Selector.ITEM, Selector.ITERATION, Selector.ITERATION));
        }
    }

    /**
     * A withdrawn selector stops V24. This image reads no selector status, so
     * a withdrawn view would accept writes again; no surface ever withdrew
     * one, and one found here was set by hand.
     */
    @Test
    void a_withdrawn_selector_stops_the_migration() throws SQLException {
        String url = freshDatabase("view_key_withdrawn");
        migrate(url, BEFORE);
        UUID scope = UUID.randomUUID();
        plantScope(url, scope, MARKS);
        asSuperuser(url, "UPDATE worklist.selector SET status = 'withdrawn' "
            + "WHERE scope_id = '" + scope + "' AND token = 'milestone'");

        assertThatThrownBy(() -> migrate(url, null))
            .as("RED STATE, observed: a withdrawn view in the store must stop V24 rather "
                + "than be opened again by it")
            .isInstanceOf(FlywayException.class)
            .hasMessageContaining("withdrawn view");
    }

    // ------------------------------------------------------------------
    // The store at V23.
    // ------------------------------------------------------------------

    /**
     * A scope as V23 holds it: a selector per view, a counter per entry of
     * {@code marks} at that mark, and rows in each view numbered 1 up to the
     * view's mark. Written as the superuser, which no policy binds.
     */
    private static void plantScope(String url, UUID scope, Map<String, Long> marks)
            throws SQLException {
        try (Connection c = DriverManager.getConnection(url,
                postgres.username(), postgres.password())) {
            for (String view : Selector.VIEWS) {
                execute(c, "INSERT INTO worklist.selector (tenant_id, scope_id, token) "
                    + "VALUES (?::uuid, ?, ?)", tenant, scope, view);
            }
            for (Map.Entry<String, Long> mark : marks.entrySet()) {
                execute(c, "INSERT INTO worklist.number_space "
                    + "(tenant_id, scope_id, selector_pk, high_water_mark) "
                    + "SELECT tenant_id, scope_id, pk, ? FROM worklist.selector "
                    + "WHERE scope_id = ? AND token = ?", mark.getValue(), scope, mark.getKey());
            }
            long status;
            try (PreparedStatement st = prepare(c, "INSERT INTO worklist.item_status "
                    + "(tenant_id, scope_id, name, rank, actionable, in_progress, closed, "
                    + "successful) VALUES (?::uuid, ?, 'open', 1, true, false, false, false) "
                    + "RETURNING pk", tenant, scope);
                 ResultSet rs = st.executeQuery()) {
                rs.next();
                status = rs.getLong(1);
            }
            for (long n = 1; n <= MARKS.get(Selector.WORKSTREAM); n++) {
                execute(c, "INSERT INTO worklist.workstream "
                    + "(tenant_id, scope_id, number, token, description, is_default) "
                    + "VALUES (?::uuid, ?, ?, ?, 'planted', ?)",
                    tenant, scope, n, "ws-" + n, n == 1);
            }
            for (long n = 1; n <= MARKS.get(Selector.ITEM); n++) {
                execute(c, "INSERT INTO worklist.item (tenant_id, scope_id, title, number, "
                    + "status_pk, workstream_number) VALUES (?::uuid, ?, 't', ?, ?, 1)",
                    tenant, scope, n, status);
            }
            for (long n = 1; n <= MARKS.get(Selector.ITERATION); n++) {
                execute(c, "INSERT INTO worklist.iteration "
                    + "(id, tenant_id, scope_id, number, title, motto, description) "
                    + "VALUES (gen_random_uuid(), ?::uuid, ?, ?, 't', 'm', 'd')",
                    tenant, scope, n);
            }
            for (long n = 1; n <= MARKS.get(Selector.MILESTONE); n++) {
                execute(c, "INSERT INTO worklist.milestone "
                    + "(id, tenant_id, scope_id, number, title, kind, status) "
                    + "VALUES (gen_random_uuid(), ?::uuid, ?, ?, 't', 'milestone', 'planned')",
                    tenant, scope, n);
            }
        }
    }

    // ------------------------------------------------------------------
    // Reading it back as the service.
    // ------------------------------------------------------------------

    /** The registry's allocation, as the runtime role: the counter of (scope, view), plus one. */
    private static long allocateAsService(String url, UUID scope, String view)
            throws SQLException {
        try (Connection c = asService(url);
             PreparedStatement st = prepare(c, "UPDATE worklist.number_space "
                 + "SET high_water_mark = high_water_mark + 1 "
                 + "WHERE scope_id = ? AND view = ? RETURNING high_water_mark", scope, view);
             ResultSet rs = st.executeQuery()) {
            assertThat(rs.next())
                .as("the scope has a counter for %s", view)
                .isTrue();
            long next = rs.getLong(1);
            c.rollback();
            return next;
        }
    }

    /** Each counter's view, mapped to the token of the selector it names. */
    private static Map<String, String> keysOf(Connection c, UUID scope) throws SQLException {
        Map<String, String> keys = new LinkedHashMap<>();
        try (PreparedStatement st = prepare(c, "SELECT n.view, s.token "
                + "FROM worklist.number_space n JOIN worklist.selector s "
                + "ON s.tenant_id = n.tenant_id AND s.pk = n.selector_pk "
                + "WHERE n.scope_id = ?", scope);
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                keys.put(rs.getString(1), rs.getString(2));
            }
        }
        return keys;
    }

    /** The runtime role with the tenant bound, as the service runs; autocommit off. */
    private static Connection asService(String url) throws SQLException {
        Connection c = DriverManager.getConnection(url,
            SubstrateDatabaseResource.SERVICE_ROLE, SubstrateDatabaseResource.SERVICE_PASSWORD);
        c.setAutoCommit(false);
        execute(c, "SELECT set_config('app.tenant_id', ?, false)", tenant);
        return c;
    }

    // ------------------------------------------------------------------
    // The harness.
    // ------------------------------------------------------------------

    private static Map<String, Long> marks() {
        Map<String, Long> marks = new LinkedHashMap<>();
        marks.put(Selector.ITEM, 7L);
        marks.put(Selector.ITERATION, 3L);
        marks.put(Selector.MILESTONE, 5L);
        marks.put(Selector.WORKSTREAM, 4L);
        return marks;
    }

    /** The real chain, to {@code target} or to the end when it is null. */
    private static void migrate(String url, String target) {
        var configuration = Flyway.configure()
            .dataSource(url, MIGRATOR, MIGRATOR_PASSWORD)
            .schemas("worklist")
            .defaultSchema("worklist")
            .createSchemas(true)
            .locations("classpath:db/migration")
            .callbacks(new TenantMigrationCallback());
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    /** A database of this case's own: each case starts from nothing. */
    private static String freshDatabase(String name) throws SQLException {
        asSuperuser(postgres.jdbcUrl(), "DROP DATABASE IF EXISTS " + name,
            "CREATE DATABASE " + name,
            "GRANT CREATE ON DATABASE " + name + " TO " + MIGRATOR);
        return postgres.jdbcUrl().replace("/kumbuka?", "/" + name + "?");
    }

    private static void asSuperuser(String url, String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(url,
                postgres.username(), postgres.password());
             Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }

    private static void execute(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement st = prepare(c, sql, values)) {
            st.execute();
        }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... values)
            throws SQLException {
        PreparedStatement st = c.prepareStatement(sql);
        for (int i = 0; i < values.length; i++) {
            st.setObject(i + 1, values[i]);
        }
        return st;
    }
}
