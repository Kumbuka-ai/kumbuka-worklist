package ai.kumbuka.worklist.boundary;

import ai.kumbuka.worklist.tenancy.Db;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
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
 * No function in this schema runs with its owner's rights, and every function
 * that resolves or guards a reference pins its search path.
 *
 * <p>The sync triggers of ADR-0042 stage R1 resolve a parent on behalf of the
 * writer of a child row. As SECURITY INVOKER they see what the writer sees:
 * the writer's tenant, under the writer's row-level-security binding. As
 * SECURITY DEFINER they would see what their owner sees, and the isolation
 * would then rest on who owns them. A pinned {@code search_path} keeps a
 * caller from substituting a table of the same name ahead of {@code worklist}.
 *
 * <p>Read from the catalogue as the administrator, so a function the service
 * role cannot see still counts.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TriggerFunctionSecurityGuardIT {

    /** The functions V18 introduces, named here rather than read from the schema. */
    private static final List<String> REFERENCE_FUNCTIONS = List.of(
        "primary_address_is_immutable",
        "sync_number_reference",
        "sync_pk_reference",
        "item_sync_references",
        "attribute_option_sync_references",
        "number_space_sync_references",
        "item_reference_sync_references",
        "item_relation_sync_references",
        "iteration_membership_sync_references",
        "claim_sync_references",
        "scope_setting_sync_references");

    private static final String PINNED_PATH = "search_path=worklist, pg_temp";

    @Test
    void no_function_in_the_schema_runs_with_its_owners_rights() throws SQLException {
        try (Connection c = Db.asAdmin()) {
            assertThat(definerFunctions(c))
                .as("a SECURITY DEFINER function resolves rows with its owner's rights instead "
                    + "of the writer's; the tenant boundary would then rest on who owns it")
                .isEmpty();
        }
    }

    @Test
    void every_reference_function_is_present_and_pins_its_search_path() throws SQLException {
        try (Connection c = Db.asAdmin()) {
            for (String name : REFERENCE_FUNCTIONS) {
                assertThat(configOf(c, name))
                    .as("worklist.%s must exist and carry SET %s", name, PINNED_PATH)
                    .contains(PINNED_PATH);
            }
        }
    }

    @Test
    void the_check_reports_a_planted_definer_function() throws SQLException {
        try (Connection m = Db.asMigrator()) {
            try (Statement s = m.createStatement()) {
                s.execute("CREATE FUNCTION worklist.planted_definer() RETURNS integer "
                    + "LANGUAGE sql SECURITY DEFINER AS 'SELECT 1'");
            }
            List<String> found = definerFunctions(m);
            m.rollback();
            assertThat(found)
                .as("RED STATE, observed: a SECURITY DEFINER function planted in the schema must be "
                    + "reported, or the check above would pass whatever the schema holds")
                .contains("planted_definer");
        }
    }

    private static List<String> definerFunctions(Connection c) throws SQLException {
        List<String> found = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                 SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = 'worklist' AND p.prosecdef
                 """)) {
            while (rs.next()) {
                found.add(rs.getString(1));
            }
        }
        return found;
    }

    private static String configOf(Connection c, String name) throws SQLException {
        try (var st = c.prepareStatement("""
                 SELECT coalesce(array_to_string(p.proconfig, ';'), '')
                 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                 WHERE n.nspname = 'worklist' AND p.proname = ?
                 """)) {
            st.setString(1, name);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
    }
}
