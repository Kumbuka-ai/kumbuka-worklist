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

/**
 * V33 places every item that existed before it by its status, and binds every
 * status to a phase (REA-0011 S9, acceptance K6; section 4: "The data
 * migration of S9 places existing items by status and creates no draft").
 *
 * <h2>Why the service is not booted</h2>
 *
 * The question is what V33 does to a store V32 left behind, so the store is
 * built at V32 and migrated past it, as {@code NumberSpaceViewMigrationIT}
 * builds its own: the real migration chain with Flyway against a database of
 * this class's own, stopped at V32, a scope planted the way the previous image
 * leaves one, and then the rest of the chain.
 *
 * <h2>What is planted</h2>
 *
 * One item per status of the concept's example binding (section 9): an
 * {@code open} status, an {@code in_progress} status, a {@code done} status
 * closed and successful, a {@code dropped} status closed and unsuccessful. The
 * expectation is written here from S9: closed for the two closed statuses,
 * open for every other -- the item whose status answers {@code in_progress}
 * included -- and no draft.
 *
 * <p>The red probe: with the data migration of V33 taken out, the two items
 * of a closed status read {@code open}, and
 * {@link #every_item_is_placed_by_its_status_and_none_becomes_a_draft} fails on
 * them.
 */
class ItemPhaseMigrationIT {

    private static final String MIGRATOR = "phase_migrator";
    private static final String MIGRATOR_PASSWORD = "test-only-phase-password";

    /** The last version before the phase. */
    private static final String BEFORE = "32";

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

    @Test
    void every_item_is_placed_by_its_status_and_none_becomes_a_draft() throws SQLException {
        String url = freshDatabase("phase_items");
        migrate(url, BEFORE);
        UUID scope = UUID.randomUUID();
        plantScope(url, scope);

        migrate(url, null);

        assertThat(itemPhases(url, scope))
            .as("an item whose status answers closed is closed, every other is open, the one "
                + "whose status answers in_progress included; none is a draft (S9)")
            .containsExactlyInAnyOrderEntriesOf(Map.of(
                "an open item", "open",
                "an item in progress", "open",
                "a done item", "closed",
                "a dropped item", "closed"));
    }

    @Test
    void every_status_is_bound_to_the_phase_its_predicates_project() throws SQLException {
        String url = freshDatabase("phase_statuses");
        migrate(url, BEFORE);
        UUID scope = UUID.randomUUID();
        plantScope(url, scope);

        migrate(url, null);

        assertThat(statusPhases(url, scope))
            .as("closed binds the phase closed, in_progress the phase in progress, every "
                + "other status the phase open (TAR-0002 B2, B3)")
            .containsExactlyInAnyOrderEntriesOf(Map.of(
                "open", "open", "in_progress", "in_progress", "done", "closed",
                "dropped", "closed"));
    }

    /**
     * The previous image after a rollback: its inserts name neither column,
     * and the store answers an item in the phase open and a status bound by
     * its predicates (S9, rollback).
     */
    @Test
    void the_inserts_of_the_previous_image_store_an_open_item_and_a_bound_status()
            throws SQLException {
        String url = freshDatabase("phase_previous_image");
        migrate(url, null);
        UUID scope = UUID.randomUUID();
        plantScope(url, scope);

        assertThat(itemPhases(url, scope).values())
            .as("an insert without the phase stores the column's default")
            .containsOnly("open");
        assertThat(statusPhases(url, scope))
            .containsEntry("done", "closed")
            .containsEntry("in_progress", "in_progress");
    }

    // ------------------------------------------------------------------
    // The store as the previous image writes it.
    // ------------------------------------------------------------------

    /**
     * A scope as the image before V33 holds it: its selectors, a default
     * workstream, four statuses and one item in each, every statement naming
     * only the columns that image knows. Written as the superuser, which no
     * policy binds.
     */
    private static void plantScope(String url, UUID scope) throws SQLException {
        try (Connection c = DriverManager.getConnection(url,
                postgres.username(), postgres.password())) {
            for (String view : Selector.VIEWS) {
                execute(c, "INSERT INTO worklist.selector (tenant_id, scope_id, token) "
                    + "VALUES (?::uuid, ?, ?)", tenant, scope, view);
            }
            execute(c, "INSERT INTO worklist.workstream "
                + "(tenant_id, scope_id, number, token, description, is_default) "
                + "VALUES (?::uuid, ?, 1, 'default', 'planted', true)", tenant, scope);
            Map<String, Long> statuses = new LinkedHashMap<>();
            statuses.put("open", status(c, scope, "open", 1, true, false, false, false));
            statuses.put("in_progress",
                status(c, scope, "in_progress", 2, true, true, false, false));
            statuses.put("done", status(c, scope, "done", 3, false, false, true, true));
            statuses.put("dropped", status(c, scope, "dropped", 4, false, false, true, false));
            item(c, scope, 1, "an open item", statuses.get("open"));
            item(c, scope, 2, "an item in progress", statuses.get("in_progress"));
            item(c, scope, 3, "a done item", statuses.get("done"));
            item(c, scope, 4, "a dropped item", statuses.get("dropped"));
        }
    }

    private static long status(Connection c, UUID scope, String name, int rank,
                               boolean actionable, boolean inProgress, boolean closed,
                               boolean successful) throws SQLException {
        try (PreparedStatement st = prepare(c, "INSERT INTO worklist.item_status "
                + "(tenant_id, scope_id, name, rank, actionable, in_progress, closed, successful) "
                + "VALUES (?::uuid, ?, ?, ?, ?, ?, ?, ?) RETURNING pk",
                tenant, scope, name, rank, actionable, inProgress, closed, successful);
             ResultSet rs = st.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void item(Connection c, UUID scope, long number, String title, long status)
            throws SQLException {
        execute(c, "INSERT INTO worklist.item (tenant_id, scope_id, title, number, status_pk, "
            + "workstream_number) VALUES (?::uuid, ?, ?, ?, ?, 1)",
            tenant, scope, title, number, status);
    }

    // ------------------------------------------------------------------
    // Reading it back.
    // ------------------------------------------------------------------

    private static Map<String, String> itemPhases(String url, UUID scope) throws SQLException {
        return pairs(url, "SELECT title, phase FROM worklist.item WHERE scope_id = ?", scope);
    }

    private static Map<String, String> statusPhases(String url, UUID scope) throws SQLException {
        return pairs(url, "SELECT name, phase FROM worklist.item_status WHERE scope_id = ?",
            scope);
    }

    /** Two columns of a query as a map, read under the runtime role with the tenant bound. */
    private static Map<String, String> pairs(String url, String sql, UUID scope)
            throws SQLException {
        Map<String, String> pairs = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection(url,
                SubstrateDatabaseResource.SERVICE_ROLE, SubstrateDatabaseResource.SERVICE_PASSWORD)) {
            c.setAutoCommit(false);
            execute(c, "SELECT set_config('app.tenant_id', ?, false)", tenant);
            try (PreparedStatement st = prepare(c, sql, scope);
                 ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    pairs.put(rs.getString(1), rs.getString(2));
                }
            }
            c.rollback();
        }
        return pairs;
    }

    // ------------------------------------------------------------------
    // The harness.
    // ------------------------------------------------------------------

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
