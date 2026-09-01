package ai.kumbuka.worklist.boundary;

import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the runtime role may do to each table of its own schema, privilege by
 * privilege, read from {@code has_table_privilege} and from nothing else.
 *
 * <h2>Why this probe exists, and what it was written against</h2>
 *
 * The substrate template this service copies arranges the runtime role's
 * access by OWNERSHIP: its migration issues no table grant at all, and an
 * {@code afterMigrate} callback hands the schema and every relation in it to
 * the runtime role. The reasoning is that an owner needs no GRANT and a grant
 * list is a thing that drifts.
 *
 * <p>The consequence follows from PostgreSQL and not from anyone's intent: an
 * owner holds DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE and UPDATE
 * on what it owns, implicitly, with no grant anywhere to show for it. A sweep
 * that hands over every relation in a schema hands over the Flyway history
 * table along with them, because that table lives in the schema too.
 *
 * <p><strong>TRUNCATE is the one that is not a tidiness issue.</strong> It
 * bypasses row-level security completely, independently of every policy and of
 * whether {@code app.tenant_id} is bound. A runtime role holding it can empty
 * a tenant-scoped table across the tenant boundary and no part of the
 * isolation apparatus sees it happen. TRIGGER and REFERENCES are the same
 * shape with a smaller blast radius: both let a role attach something of its
 * own to a table it should only be reading and writing row by row.
 *
 * <p>So this service enumerates instead (V2), and this probe is what keeps the
 * enumeration honest. It derives the table list from the catalog rather than
 * from a constant, so a table added by a later migration is checked the day it
 * ships — which is the property the ownership arrangement was reaching for and
 * the reason it cannot simply be replaced by a list somebody maintains.
 *
 * <h2>Where the expectation comes from</h2>
 *
 * The schema is read from {@code quarkus.flyway.default-schema} and the role
 * from {@code quarkus.datasource.username} — the same two settings the
 * application itself runs on. A probe that read its expectation from a
 * duplicate key would be checking that two settings agree rather than that the
 * database is right.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ServiceRolePrivilegeIT {

    /** Exactly what a domain table of this schema grants the runtime role. */
    private static final Set<String> PERMITTED = Set.of("SELECT", "INSERT", "UPDATE");

    /**
     * Everything {@code has_table_privilege} can be asked about a table.
     *
     * <p>The list is written out rather than derived, because the question is
     * "which of the privileges PostgreSQL has does this role hold" and a
     * derived list would silently stop asking about one the day the catalog
     * view changed shape. Anything here and not in {@link #PERMITTED} must be
     * absent.
     */
    private static final List<String> ALL_TABLE_PRIVILEGES = List.of(
        "SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER");

    /**
     * The migrator's record of what the migrator did. The runtime role holds
     * NOTHING on it: a role that can rewrite this table can make a schema lie
     * about its own version, and there is no verb in this service that has any
     * business reading it.
     */
    private static final String HISTORY_TABLE = "flyway_schema_history";

    /**
     * Every base table this schema is supposed to have, by name.
     *
     * <p>The assertions above derive their table list from the catalog, which
     * is what makes a table added by a later migration get checked the day it
     * ships. That is one direction. This constant is the other: it is what
     * makes a table that was supposed to be added and was NOT a failure too.
     *
     * <p>The distinction matters because the enumerated-privilege arrangement
     * has exactly one failure mode — a migration that creates a relation and
     * forgets its grants. A catalog-derived check sees that immediately. It
     * does not see a migration that was never written, and a domain missing a
     * table is not a state a privilege probe should be silent about.
     *
     * <p>Written out and not derived: an expectation read from the running
     * catalog is a check that the catalog agrees with itself.
     */
    private static final Set<String> EXPECTED_TABLES = Set.of(
        // The substrate's, from V1.
        "item",
        // The item domain's, from V4.
        "selector", "number_space", "term", "item_dependency",
        // The migrator's own record: no privilege for the runtime role, and
        // nonetheless expected to BE there.
        HISTORY_TABLE);

    private static String schema() {
        return ConfigProvider.getConfig().getValue("quarkus.flyway.default-schema", String.class);
    }

    private static String role() {
        return ConfigProvider.getConfig().getValue("quarkus.datasource.username", String.class);
    }

    // ------------------------------------------------------------------
    // The green states.
    // ------------------------------------------------------------------

    /**
     * Acceptance criterion 6, in the form the dispatch asks for it: per table
     * and per privilege, from {@code has_table_privilege}.
     */
    @Test
    void the_runtime_role_holds_exactly_select_insert_update_on_every_domain_table()
            throws SQLException {
        List<String> tables;
        List<String> defects;

        try (Connection c = admin()) {
            tables = tablesIn(c, schema());
            defects = privilegeDefects(c, tables);
        }

        // A schema with no table would satisfy the assertion below without
        // asserting anything, and a vacuous pass is indistinguishable from a
        // real one in a report.
        assertThat(tables)
            .as("the probe must have had something to check")
            .isNotEmpty()
            .contains(HISTORY_TABLE);

        assertThat(defects)
            .as("the runtime role %s may hold exactly %s on a domain table of schema %s, "
                + "and nothing whatever on %s. TRUNCATE in particular bypasses row-level "
                + "security independently of every policy, so a role holding it can cross "
                + "the tenant boundary without any part of the isolation apparatus seeing "
                + "it", role(), PERMITTED, schema(), HISTORY_TABLE)
            .isEmpty();
    }

    /**
     * The privileges must be GRANTS and not ownership.
     *
     * <p>Without this the assertion above could be satisfied by a role that
     * owns everything — an owner holds the whole ACL, so it would report
     * SELECT, INSERT and UPDATE truthfully and TRUNCATE truthfully too. This
     * is the assertion that says which of the two arrangements is in place,
     * and it is the one that would have caught the sibling's defect on the day
     * it shipped.
     */
    @Test
    void the_runtime_role_owns_nothing_anywhere() throws SQLException {
        try (Connection c = admin();
             var st = c.prepareStatement("""
                 SELECT coalesce(string_agg(n.nspname || '.' || cl.relname, ', '), '')
                 FROM pg_class cl
                 JOIN pg_namespace n ON n.oid = cl.relnamespace
                 WHERE pg_get_userbyid(cl.relowner) = ?
                   AND cl.relkind IN ('r','v','m','S','p')
                 """)) {
            st.setString(1, role());
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                assertThat(rs.getString(1))
                    .as("the runtime role must own nothing: an owner holds every privilege "
                        + "on what it owns, implicitly, and can grant itself back anything "
                        + "revoked — so an enumerated list over an owned table describes "
                        + "nothing that is actually enforced")
                    .isEmpty();
            }
        }
    }

    /** And the schema itself, which is an owned object too. */
    @Test
    void the_schema_belongs_to_the_migrator() throws SQLException {
        try (Connection c = admin();
             var st = c.prepareStatement(
                 "SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = ?")) {
            st.setString(1, schema());
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                assertThat(rs.getString(1))
                    .as("the migrator keeps the schema, so the runtime role holds no CREATE "
                        + "on it and cannot add a table to its own entitlement")
                    .isEqualTo(SubstrateDatabaseResource.MIGRATOR_ROLE);
            }
        }
    }

    /**
     * USAGE on the schema is held, and is the only thing that is.
     *
     * <p>Without it every enumerated table privilege would be unreachable and
     * the service would not start — so a suite that only asserted absences
     * could be green against a deployment that does not run.
     */
    @Test
    void the_runtime_role_can_reach_the_schema_and_read_its_table() throws SQLException {
        try (Connection c = admin()) {
            assertThat(holdsOnSchema(c, "USAGE"))
                .as("without USAGE the enumerated table grants would be unreachable")
                .isTrue();
            assertThat(holdsOnSchema(c, "CREATE"))
                .as("CREATE on its own schema would let the runtime role add a table and "
                    + "own it, which is the enumeration defeated in one statement")
                .isFalse();
        }
        try (Connection c = DriverManager.getConnection(url(),
                SubstrateDatabaseResource.SERVICE_ROLE, SubstrateDatabaseResource.SERVICE_PASSWORD);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM " + schema() + ".item")) {
            rs.next();
            assertThat(rs.getLong(1))
                .as("and the role must actually reach its table — a role that can reach "
                    + "nothing satisfies every absence above and cannot run the service")
                .isNotNegative();
        }
    }

    /**
     * The schema holds exactly the relations it is supposed to hold.
     *
     * <p>Both directions, and both are real. A MISSING table means a
     * migration that was never written or never ran, and the service will
     * fail on its first use of it rather than here. An EXTRA table means a
     * relation nobody named — which, under this arrangement, is also a
     * relation whose privileges nobody thought about.
     */
    @Test
    void the_schema_holds_exactly_the_relations_that_were_declared() throws SQLException {
        List<String> tables;
        try (Connection c = admin()) {
            tables = tablesIn(c, schema());
        }

        assertThat(tables)
            .as("every declared relation of schema %s must exist. A missing one is a "
                + "migration that was never written or never ran, and it surfaces at the "
                + "first use of the table rather than here", schema())
            .containsAll(EXPECTED_TABLES);

        assertThat(tables)
            .as("and nothing beyond them. Under enumerated privileges an unnamed relation "
                + "is a relation whose entitlement nobody decided, so an extra table is a "
                + "finding rather than a detail")
            .allSatisfy(table -> assertThat(EXPECTED_TABLES).contains(table));
    }

    // ------------------------------------------------------------------
    // The red states, observed on every build.
    // ------------------------------------------------------------------

    /**
     * Probe A, as a permanent gate: TRUNCATE is granted for the length of one
     * assertion and the check must name the table and the privilege.
     *
     * <p>An assertion that nothing is wrong is exactly as strong as the
     * detection behind it, and a detection that has never found anything is an
     * untested one.
     */
    @Test
    void the_check_names_the_table_and_the_privilege_when_truncate_is_granted()
            throws SQLException {
        String table = schema() + ".item";
        try (Connection c = admin()) {
            try {
                exec(c, "GRANT TRUNCATE ON " + table + " TO " + role());

                List<String> defects = privilegeDefects(c, tablesIn(c, schema()));
                assertThat(defects)
                    .as("RED STATE, observed: with TRUNCATE granted the probe must report "
                        + "it, and must say which table and which privilege — a defect "
                        + "report that only says 'wrong' sends the reader back to the "
                        + "catalog to find out what it meant")
                    .anySatisfy(defect -> assertThat(defect)
                        .contains("item")
                        .contains("TRUNCATE"));
            } finally {
                exec(c, "REVOKE TRUNCATE ON " + table + " FROM " + role());
            }

            assertThat(privilegeDefects(c, tablesIn(c, schema())))
                .as("and gone again, so the red state was that grant and nothing else")
                .isEmpty();
        }
    }

    /**
     * The history table's own red state.
     *
     * <p>A separate case because it fails a different rule: the domain tables
     * are checked against a permitted set, and this one is checked against
     * nothing being permitted at all. A single case would have left the second
     * rule asserted only by the absence of a counter-example.
     */
    @Test
    void the_check_reports_any_privilege_at_all_on_the_history_table() throws SQLException {
        String table = schema() + "." + HISTORY_TABLE;
        try (Connection c = admin()) {
            try {
                exec(c, "GRANT SELECT ON " + table + " TO " + role());

                assertThat(privilegeDefects(c, tablesIn(c, schema())))
                    .as("RED STATE, observed: even SELECT on the migrator's history table "
                        + "must be reported. It is the least alarming privilege there is, "
                        + "which is exactly why a rule that only watched for the alarming "
                        + "ones would let the boundary erode")
                    .anySatisfy(defect -> assertThat(defect)
                        .contains(HISTORY_TABLE)
                        .contains("SELECT"));
            } finally {
                exec(c, "REVOKE SELECT ON " + table + " FROM " + role());
            }

            assertThat(privilegeDefects(c, tablesIn(c, schema())))
                .as("and closed again")
                .isEmpty();
        }
    }

    /**
     * The other direction: a privilege that SHOULD be held and is not must
     * also be reported.
     *
     * <p>Without this the probe would be a one-sided check that a schema with
     * every grant revoked would pass — a service that cannot read its own
     * table, reported as perfectly bounded.
     */
    @Test
    void the_check_reports_a_permitted_privilege_that_is_missing() throws SQLException {
        String table = schema() + ".item";
        try (Connection c = admin()) {
            try {
                exec(c, "REVOKE UPDATE ON " + table + " FROM " + role());

                assertThat(privilegeDefects(c, tablesIn(c, schema())))
                    .as("RED STATE, observed: a missing UPDATE is a defect too. A probe "
                        + "that only looked for excess would be green against a schema the "
                        + "service cannot write to at all")
                    .anySatisfy(defect -> assertThat(defect)
                        .contains("item")
                        .contains("UPDATE"));
            } finally {
                exec(c, "GRANT UPDATE ON " + table + " TO " + role());
            }

            assertThat(privilegeDefects(c, tablesIn(c, schema())))
                .as("and restored")
                .isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // The detection itself, used by both the green and the red cases.
    // ------------------------------------------------------------------

    /**
     * Every deviation from the enumerated entitlement, one string per table
     * and privilege, each naming both.
     */
    private static List<String> privilegeDefects(Connection c, List<String> tables)
            throws SQLException {
        List<String> defects = new ArrayList<>();
        for (String table : tables) {
            boolean history = HISTORY_TABLE.equals(table);
            for (String privilege : ALL_TABLE_PRIVILEGES) {
                boolean held = holds(c, table, privilege);
                boolean expected = !history && PERMITTED.contains(privilege);
                if (held && !expected) {
                    defects.add(schema() + "." + table + ": holds " + privilege
                        + " and must not" + (history
                            ? " — the history table belongs to the migrator and the runtime "
                                + "role holds nothing on it"
                            : " — permitted here is exactly " + PERMITTED));
                } else if (!held && expected) {
                    defects.add(schema() + "." + table + ": lacks " + privilege
                        + " and must hold it — the service cannot run without it");
                }
            }
        }
        return defects;
    }

    /** {@code has_schema_privilege}, read as a boolean rather than as text. */
    private static boolean holdsOnSchema(Connection c, String privilege) throws SQLException {
        try (var st = c.prepareStatement("SELECT has_schema_privilege(?, ?, ?)")) {
            st.setString(1, role());
            st.setString(2, schema());
            st.setString(3, privilege);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /** {@code has_table_privilege}, asked one privilege at a time. */
    private static boolean holds(Connection c, String table, String privilege)
            throws SQLException {
        try (var st = c.prepareStatement("SELECT has_table_privilege(?, ?, ?)")) {
            st.setString(1, role());
            st.setString(2, schema() + "." + table);
            st.setString(3, privilege);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /** Every base table in the schema, from the catalog rather than from a list. */
    private static List<String> tablesIn(Connection c, String schema) throws SQLException {
        List<String> out = new ArrayList<>();
        try (var st = c.prepareStatement("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = ? AND table_type = 'BASE TABLE'
                ORDER BY table_name
                """)) {
            st.setString(1, schema);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String url() {
        return ConfigProvider.getConfig().getValue("test.db.url", String.class);
    }

    private static Connection admin() throws SQLException {
        var config = ConfigProvider.getConfig();
        return DriverManager.getConnection(
            config.getValue("test.db.url", String.class),
            config.getValue("test.db.admin.username", String.class),
            config.getValue("test.db.admin.password", String.class));
    }
}
