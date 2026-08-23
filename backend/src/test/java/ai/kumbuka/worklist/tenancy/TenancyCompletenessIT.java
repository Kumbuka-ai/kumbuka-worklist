package ai.kumbuka.worklist.tenancy;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Completeness, read from the catalog rather than from a list somebody
 * maintains.
 *
 * <p>A table added to this schema next year will carry a tenant column
 * because the schema is tenant-scoped, and its author may not think to add a
 * policy. Nothing about writing that table would fail; the table would simply
 * be readable across tenants from the day it ships. That is the failure this
 * class exists to make impossible, and it can only do that by deriving what
 * to check from the database itself.
 *
 * <p>So there is no list of tables here. The query below asks the catalog
 * which tables carry {@code tenant_id}, and then asks, of each, whether it
 * carries the three things a tenant-scoped table must carry: row-level
 * security enabled, forced, and a policy with both {@code USING} and
 * {@code WITH CHECK}. A check that derives its expectation from a hand-kept
 * list proves that the list agrees with itself.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TenancyCompletenessIT {

    /** The one column name the tenancy axis is allowed to have in this schema. */
    private static final String TENANT_COLUMN = "tenant_id";

    @Test
    void every_table_carrying_a_tenant_column_is_covered_by_a_forced_policy()
            throws SQLException {
        List<String> tenantTables;
        List<String> uncovered;

        try (Connection c = Db.asAdmin()) {
            tenantTables = tablesWithTenantColumn(c);
            uncovered = defectsAcross(c, tenantTables);
        }

        // A schema that happened to contain no tenant-scoped table at all
        // would satisfy the check vacuously, and a vacuous pass is
        // indistinguishable from a real one in a report.
        assertThat(tenantTables)
            .as("the check must have had something to check — an empty schema passes the "
                + "assertion below without asserting anything")
            .isNotEmpty();

        assertThat(uncovered)
            .as("every table in this schema carrying a `%s` column must have row-level "
                + "security enabled AND forced AND a policy with both USING and WITH CHECK. "
                + "A table missing any of the three is readable or writable across the tenant "
                + "boundary, silently and from the moment it ships", TENANT_COLUMN)
            .isEmpty();
    }

    /**
     * The red state of the check above, observed on every build.
     *
     * <p>The assertion that nothing is uncovered is exactly as strong as the
     * detection behind it, and a detection that has never found anything is
     * an untested one. So a table is created here that has everything wrong
     * with it a real table could have — a tenant column, and no policy — and
     * the check is required to name it. Then it is dropped.
     *
     * <p>This is what turns "no table is uncovered" from a statement about
     * this schema into a statement about this schema AND the thing that
     * checks it.
     */
    @Test
    void the_check_finds_a_table_that_is_deliberately_left_uncovered() throws SQLException {
        String defective = "probe_uncovered_table";

        try (Connection c = Db.asAdmin()) {
            try {
                Db.exec(c, "CREATE TABLE worklist." + defective
                    + " (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL)");

                assertThat(tablesWithTenantColumn(c))
                    .as("the catalog query must find a newly added tenant-scoped table with "
                        + "no help from anybody — that is the point of reading the catalog "
                        + "rather than a maintained list")
                    .contains(defective);

                assertThat(defectsAcross(c, List.of(defective)))
                    .as("RED STATE, observed: a tenant-scoped table with no row-level security "
                        + "and no policy is exactly what would ship unnoticed, and the check "
                        + "must report it. If this list were empty the green assertion above "
                        + "would be worth nothing")
                    .isNotEmpty()
                    .anySatisfy(defect -> assertThat(defect).contains("row-level security"));
            } finally {
                Db.exec(c, "DROP TABLE IF EXISTS worklist." + defective);
            }
        }
    }

    /**
     * A policy is also a place where the tenancy axis can be spelled wrong.
     * A predicate naming a different column, or comparing against something
     * other than the session setting, would pass every structural check above
     * and isolate nothing.
     */
    @Test
    void every_policy_keys_on_the_tenant_column_and_the_session_setting() throws SQLException {
        List<String> wrong = new ArrayList<>();

        try (Connection c = Db.asAdmin()) {
            try (Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("""
                     SELECT tablename, policyname, coalesce(qual, ''), coalesce(with_check, '')
                     FROM pg_policies
                     WHERE schemaname = 'worklist'
                     """)) {
                while (rs.next()) {
                    String table = rs.getString(1);
                    String policy = rs.getString(2);
                    String using = rs.getString(3);
                    String check = rs.getString(4);
                    for (String expression : List.of(using, check)) {
                        if (!expression.contains(TENANT_COLUMN)) {
                            wrong.add(table + "." + policy + " does not name " + TENANT_COLUMN
                                + ": " + expression);
                        }
                        if (!expression.contains("app.tenant_id")) {
                            wrong.add(table + "." + policy + " does not read the session "
                                + "setting app.tenant_id: " + expression);
                        }
                    }
                }
            }
        }

        assertThat(wrong)
            .as("a policy that names the wrong column, or compares against something other "
                + "than the bound session setting, is structurally present and semantically "
                + "inert — the shape of a guarantee with none of its effect")
            .isEmpty();
    }

    /** Every defect found across the given tables, each naming its table. */
    private static List<String> defectsAcross(Connection c, List<String> tables)
            throws SQLException {
        List<String> defects = new ArrayList<>();
        for (String table : tables) {
            if (!hasRowLevelSecurity(c, table)) {
                defects.add(table + " (row-level security not enabled)");
                continue;
            }
            if (!hasForcedRowLevelSecurity(c, table)) {
                defects.add(table + " (enabled but NOT forced — the policy does not bind the "
                    + "owner, and the owner is the only role that connects)");
            }
            for (String defect : policyDefects(c, table)) {
                defects.add(table + " (" + defect + ")");
            }
        }
        return defects;
    }

    private static List<String> tablesWithTenantColumn(Connection c) throws SQLException {
        List<String> out = new ArrayList<>();
        try (var st = c.prepareStatement("""
                SELECT t.table_name
                FROM information_schema.tables t
                JOIN information_schema.columns col
                  ON col.table_schema = t.table_schema
                 AND col.table_name = t.table_name
                 AND col.column_name = ?
                WHERE t.table_schema = 'worklist'
                  AND t.table_type = 'BASE TABLE'
                ORDER BY t.table_name
                """)) {
            st.setString(1, TENANT_COLUMN);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private static boolean hasRowLevelSecurity(Connection c, String table) throws SQLException {
        return flag(c, table, "relrowsecurity");
    }

    private static boolean hasForcedRowLevelSecurity(Connection c, String table)
            throws SQLException {
        return flag(c, table, "relforcerowsecurity");
    }

    private static boolean flag(Connection c, String table, String column) throws SQLException {
        try (var st = c.prepareStatement("""
                SELECT %s FROM pg_class cl
                JOIN pg_namespace n ON n.oid = cl.relnamespace
                WHERE n.nspname = 'worklist' AND cl.relname = ?
                """.formatted(column))) {
            st.setString(1, table);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /** Missing policy, missing USING, or missing WITH CHECK — each named. */
    private static List<String> policyDefects(Connection c, String table) throws SQLException {
        List<String> defects = new ArrayList<>();
        boolean any = false;
        try (var st = c.prepareStatement(
                "SELECT policyname, qual, with_check FROM pg_policies "
              + "WHERE schemaname = 'worklist' AND tablename = ?")) {
            st.setString(1, table);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    any = true;
                    if (rs.getString(2) == null) {
                        defects.add("policy " + rs.getString(1) + " has no USING clause, so it "
                            + "constrains writes but filters no read");
                    }
                    if (rs.getString(3) == null) {
                        defects.add("policy " + rs.getString(1) + " has no WITH CHECK clause, so "
                            + "a session can write a row it will then be unable to see");
                    }
                }
            }
        }
        if (!any) {
            defects.add("no policy at all — row-level security is on and permits nothing, "
                + "or is off and permits everything");
        }
        return defects;
    }
}
