package ai.kumbuka.worklist.boundary;

import ai.kumbuka.worklist.platform.PlatformFixture;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conformance probe: this service's role holds its own schema, plus
 * {@code SELECT} on exactly one view, and nothing else anywhere.
 *
 * <p>{@link MissingGrantProbeIT} observes one boundary against one
 * neighbouring table. This asks the question the architecture actually poses —
 * <em>does this role hold anything it should not</em> — of the whole catalog,
 * so it also covers the neighbour that does not exist yet and the grant
 * somebody issues next year.
 *
 * <h2>The permitted set is a list of objects, not of schemas</h2>
 *
 * The service now consumes a platform read contract, so it holds something
 * outside its own schema for the first time. Adding {@code platform} to a list
 * of permitted SCHEMAS would have been the obvious edit and would have been a
 * blank cheque: every future object in that schema would pass unnoticed,
 * including one granted by mistake. What is permitted is one view and one
 * privilege on it, so that is what the list holds — and
 * {@link #the_check_finds_a_second_object_in_a_permitted_schema} watches the
 * distinction hold.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ServiceRoleConformanceIT {

    private static final String SERVICE_ROLE = SubstrateDatabaseResource.SERVICE_ROLE;

    /**
     * The service's own schema. Its entitlement inside it is the enumerated
     * grant list of V2, checked privilege by privilege in
     * {@link ServiceRolePrivilegeIT}; what THIS probe asks is the other
     * question — whether the role holds anything ANYWHERE ELSE.
     */
    private static final String OWN_SCHEMA = "worklist";

    /**
     * Everything the role may hold OUTSIDE its own schema, named object by
     * object. One entry today: the published read contract.
     */
    private static final Set<String> PERMITTED_FOREIGN_OBJECTS = Set.of(
        SubstrateDatabaseResource.PLATFORM_SCHEMA + "." + SubstrateDatabaseResource.DIRECTORY_VIEW);

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @Test
    void the_service_role_holds_nothing_it_was_not_meant_to() throws SQLException {
        assertThat(foreignHoldings())
            .as("outside %s the service role may hold only %s. Anything else listed here "
                + "is a route from this service into data it does not own, and the "
                + "isolation rests on there being no such route rather than on nobody "
                + "taking it", OWN_SCHEMA, PERMITTED_FOREIGN_OBJECTS)
            .isEmpty();
    }

    /**
     * The red state, and the reason the permitted set names objects.
     *
     * <p>A second object is granted in the SAME schema the read contract lives
     * in. The check must still name it. If it did not, adding that schema to
     * the permitted set would have quietly turned one permitted view into a
     * permitted schema, and the next grant issued there — by a migration, by a
     * mistake — would pass without anybody seeing it.
     */
    @Test
    void the_check_finds_a_second_object_in_a_permitted_schema() throws SQLException {
        String extra = SubstrateDatabaseResource.PLATFORM_SCHEMA + ".scope_access_copy";
        try {
            PlatformFixture.run(
                "CREATE VIEW " + extra + " AS SELECT 1 AS one",
                "GRANT SELECT ON " + extra + " TO " + SERVICE_ROLE);

            assertThat(foreignHoldings())
                .as("RED STATE, observed: a grant on a DIFFERENT object in the permitted "
                    + "schema must be reported. The entitlement is one view, not the "
                    + "schema it happens to live in")
                .anyMatch(holding -> holding.contains("scope_access_copy"));
        } finally {
            PlatformFixture.run("DROP VIEW IF EXISTS " + extra);
        }

        assertThat(foreignHoldings())
            .as("and gone again, so the red state was that grant and nothing else")
            .isEmpty();
    }

    /**
     * The other red state: the permitted grant itself must be what the probe
     * is seeing. Revoking it has to change the answer, or the check is
     * matching on something else entirely.
     */
    @Test
    void the_permitted_grant_is_really_the_one_being_permitted() throws SQLException {
        assertThat(grantsOn(SubstrateDatabaseResource.PLATFORM_SCHEMA,
                SubstrateDatabaseResource.DIRECTORY_VIEW))
            .as("the role must actually hold SELECT on the read contract — a permitted "
                + "entry that is not held would make the check pass by absence")
            .containsExactly("SELECT");
    }

    /**
     * The floor under every absence above: the role must actually hold
     * something in its own schema.
     *
     * <p>The sibling service asks this as an ownership question, because there
     * the runtime role owns its tables. Here it owns nothing anywhere — that
     * is asserted in {@link ServiceRolePrivilegeIT} and it is the point of the
     * whole arrangement — so the question has to be asked of the grants
     * instead. Without it, a role stripped of every privilege would satisfy
     * each assertion above: a green suite describing a deployment that cannot
     * start.
     */
    @Test
    void the_service_role_does_hold_grants_in_its_own_schema() throws SQLException {
        assertThat(grantsOn(OWN_SCHEMA, "item"))
            .as("the enumerated entitlement of V2 must actually be in place. A role "
                + "holding nothing would pass every absence checked above and be unable "
                + "to read a single row")
            .containsExactly("INSERT", "SELECT", "UPDATE");
    }

    /**
     * Everything the service role owns or was granted outside its own schema,
     * minus what it is explicitly permitted.
     */
    private static List<String> foreignHoldings() throws SQLException {
        List<String> foreign = new ArrayList<>();

        try (Connection c = admin()) {
            // Ownership and explicit grants are two different ways to hold a
            // privilege; asking about only one would miss the other entirely.
            try (var st = c.prepareStatement("""
                    SELECT n.nspname, cl.relname, 'owns'
                    FROM pg_class cl
                    JOIN pg_namespace n ON n.oid = cl.relnamespace
                    WHERE pg_get_userbyid(cl.relowner) = ?
                      AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                      AND cl.relkind IN ('r','v','m','S','p')
                    UNION
                    SELECT g.table_schema, g.table_name, string_agg(DISTINCT g.privilege_type, ',')
                    FROM information_schema.role_table_grants g
                    WHERE g.grantee = ?
                      AND g.table_schema NOT IN ('pg_catalog', 'information_schema')
                    GROUP BY g.table_schema, g.table_name
                    """)) {
                st.setString(1, SERVICE_ROLE);
                st.setString(2, SERVICE_ROLE);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        String schema = rs.getString(1);
                        String object = rs.getString(2);
                        if (OWN_SCHEMA.equals(schema)
                            || PERMITTED_FOREIGN_OBJECTS.contains(schema + "." + object)) {
                            continue;
                        }
                        foreign.add(schema + "." + object + " [" + rs.getString(3) + "]");
                    }
                }
            }
        }
        return foreign;
    }

    private static List<String> grantsOn(String schema, String object) throws SQLException {
        List<String> privileges = new ArrayList<>();
        try (Connection c = admin();
             var st = c.prepareStatement("""
                 SELECT DISTINCT privilege_type FROM information_schema.role_table_grants
                 WHERE grantee = ? AND table_schema = ? AND table_name = ?
                 ORDER BY privilege_type
                 """)) {
            st.setString(1, SERVICE_ROLE);
            st.setString(2, schema);
            st.setString(3, object);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    privileges.add(rs.getString(1));
                }
            }
        }
        return privileges;
    }

    private static Connection admin() throws SQLException {
        var config = ConfigProvider.getConfig();
        return DriverManager.getConnection(
            config.getValue("test.db.url", String.class),
            config.getValue("test.db.admin.username", String.class),
            config.getValue("test.db.admin.password", String.class));
    }
}
