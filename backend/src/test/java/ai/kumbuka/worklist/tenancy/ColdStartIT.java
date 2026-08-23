package ai.kumbuka.worklist.tenancy;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cold start: an empty database, and afterwards a service that runs.
 *
 * <p>This is the first acceptance criterion and it is also the one that is
 * easiest to fake. A suite that stages the schema and then asserts the schema
 * is there proves that its own setup ran. So nothing here is staged: the
 * container arrives empty apart from a migrating role, a provider role, the
 * platform's read contract and a neighbour's table, and everything asserted
 * below was created by the migration set during boot, in the order the
 * service will do it in production.
 *
 * <p>The role attributes are asserted, not assumed. Superuser or BYPASSRLS on
 * the service role would evaporate every policy in this schema silently — no
 * error, rows returned, and the rest of the suite green. They are two boolean
 * columns in the catalog and there is no reason to learn about them from an
 * incident instead. The migrator's attributes are asserted for the same
 * reason and a second one: acceptance criterion 2 is that the chain runs
 * UNDER AN UNPRIVILEGED MIGRATOR, and a suite that migrated as a superuser
 * would prove nothing about the deployment that does not.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ColdStartIT {

    @Test
    void flyway_creates_the_schema_and_its_table() throws SQLException, IOException {
        try (Connection c = Db.asAdmin()) {
            assertThat(scalar(c, "SELECT count(*) FROM information_schema.schemata "
                + "WHERE schema_name = 'worklist'"))
                .as("the service's named schema must exist after boot")
                .isEqualTo("1");

            assertThat(scalar(c, "SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = 'worklist' AND table_name = 'item'"))
                .as("V1 must have created the item table")
                .isEqualTo("1");

            // The expectation is counted from the migration directory rather
            // than written here as a number. A literal would have to be
            // maintained alongside every new migration, and the failure when
            // somebody forgets reads as "a migration did not apply" — which
            // sends the next reader looking at the database instead of at
            // this line. The two sources are genuinely different: one is the
            // files on disk, the other is what the database recorded running.
            long expected = countVersionedMigrationFiles();
            assertThat(expected)
                .as("the migration directory must have been found at all")
                .isPositive();

            // Only the versioned rows are counted. Flyway records its own
            // schema creation as an unversioned entry, and counting that as a
            // migration would make the assertion drift with the tool rather
            // than with the migration set.
            assertThat(scalar(c, "SELECT count(*) FROM worklist.flyway_schema_history "
                + "WHERE success AND version IS NOT NULL"))
                .as("every versioned migration on disk must have applied successfully")
                .isEqualTo(String.valueOf(expected));

            assertThat(scalar(c, "SELECT max(version::int) FROM worklist.flyway_schema_history "
                + "WHERE success AND version IS NOT NULL"))
                .as("and the schema must stand at the highest of them")
                .isEqualTo(String.valueOf(expected));
        }
    }

    /**
     * Acceptance criterion 2: the chain ran under the unprivileged migrator.
     *
     * <p>Read from the history table, which records the role each migration
     * was installed by, rather than from the test's own configuration. The
     * configuration says which role the suite INTENDED to migrate as; this
     * says which one the database saw.
     */
    @Test
    void the_chain_ran_under_the_unprivileged_migrator() throws SQLException {
        try (Connection c = Db.asAdmin()) {
            assertThat(scalar(c, """
                SELECT coalesce(string_agg(DISTINCT installed_by, ', '), '')
                FROM worklist.flyway_schema_history WHERE version IS NOT NULL
                """))
                .as("every versioned migration must have been installed by the migrating "
                    + "role and by nothing else")
                .isEqualTo(SubstrateDatabaseResource.MIGRATOR_ROLE);

            assertThat(scalar(c, "SELECT rolsuper FROM pg_roles WHERE rolname = '"
                + SubstrateDatabaseResource.MIGRATOR_ROLE + "'"))
                .as("a superuser migrator would additionally carry BYPASSRLS, and its own "
                    + "DML could then never be observed failing when it forgets the "
                    + "tenant binding")
                .isEqualTo("f");

            assertThat(scalar(c, "SELECT rolbypassrls FROM pg_roles WHERE rolname = '"
                + SubstrateDatabaseResource.MIGRATOR_ROLE + "'"))
                .as("and BYPASSRLS directly, which is the attribute that actually does it")
                .isEqualTo("f");

            assertThat(scalar(c, "SELECT rolcreaterole FROM pg_roles WHERE rolname = '"
                + SubstrateDatabaseResource.MIGRATOR_ROLE + "'"))
                .as("CREATEROLE is the one privileged thing the migration set needs, and "
                    + "asserting it is what makes the two absences above a deliberate "
                    + "shape rather than an unprivileged role that happens to work")
                .isEqualTo("t");
        }
    }

    @Test
    void the_migration_creates_the_service_role_with_the_attributes_that_matter()
            throws SQLException {
        try (Connection c = Db.asAdmin()) {
            assertThat(scalar(c, "SELECT count(*) FROM pg_roles WHERE rolname = '"
                + SubstrateDatabaseResource.SERVICE_ROLE + "'"))
                .as("V2 must create the service role against an empty database, so that a "
                    + "cold start needs no manual step")
                .isEqualTo("1");

            assertThat(scalar(c, "SELECT rolsuper FROM pg_roles WHERE rolname = '"
                + SubstrateDatabaseResource.SERVICE_ROLE + "'"))
                .as("a superuser bypasses row-level security unconditionally: the policies "
                    + "in V3 would exist and do nothing")
                .isEqualTo("f");

            assertThat(scalar(c, "SELECT rolbypassrls FROM pg_roles WHERE rolname = '"
                + SubstrateDatabaseResource.SERVICE_ROLE + "'"))
                .as("BYPASSRLS is the same evaporation by a different attribute")
                .isEqualTo("f");

            assertThat(scalar(c, "SELECT rolcreaterole FROM pg_roles WHERE rolname = '"
                + SubstrateDatabaseResource.SERVICE_ROLE + "'"))
                .as("and the runtime role creates no roles — the one privileged act in "
                    + "this repository belongs to the migrator alone")
                .isEqualTo("f");
        }
    }

    /**
     * Ownership, and this is where the service departs from the template it
     * copies.
     *
     * <p>The substrate template asserts the opposite of this: there an
     * {@code afterMigrate} callback hands every relation to the runtime role,
     * and the assertion is that nothing is left with the migrator. That also
     * hands the runtime role the full privilege set on each of them, TRUNCATE
     * and the history table included, because an owner holds the whole ACL
     * implicitly. Here the migrator keeps everything and the runtime role is
     * granted what it needs, table by table, in V2.
     */
    @Test
    void every_object_in_the_schema_belongs_to_the_migrator() throws SQLException {
        try (Connection c = Db.asAdmin()) {
            assertThat(scalar(c, """
                SELECT coalesce(string_agg(c.relname || ':' || pg_get_userbyid(c.relowner), ', '), '')
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'worklist'
                  AND c.relkind IN ('r','v','m','S','p')
                  AND pg_get_userbyid(c.relowner) <> '%s'
                """.formatted(SubstrateDatabaseResource.MIGRATOR_ROLE)))
                .as("the migrator creates and keeps every relation in this schema, the "
                    + "Flyway history table included. An object handed to the runtime role "
                    + "would hand it that object's whole privilege set with no grant "
                    + "anywhere to show for it")
                .isEmpty();

            assertThat(scalar(c, "SELECT pg_get_userbyid(nspowner) FROM pg_namespace "
                + "WHERE nspname = 'worklist'"))
                .as("the schema itself is an owned object too, and the owner is what "
                    + "decides whether the runtime role can add a table to its own "
                    + "entitlement")
                .isEqualTo(SubstrateDatabaseResource.MIGRATOR_ROLE);
        }
    }

    @Test
    void the_service_reaches_its_own_table_through_the_grants_it_was_given()
            throws SQLException {
        try (Connection c = Db.asService()) {
            // Not as owner — as grantee. V2 names the privileges one at a time.
            assertThat(Db.countItems(c))
                .as("the service role must reach its own table")
                .isNotNegative();
        }
    }

    /**
     * Counts {@code V<n>__*.sql} files in the migration directory.
     *
     * <p>Deliberately not a constant: this is the one number in the test that
     * would otherwise need editing every time a migration is added, and the
     * edit that gets forgotten produces a failure describing the wrong thing.
     */
    private static long countVersionedMigrationFiles() throws IOException {
        Path dir = Files.isDirectory(Paths.get("src/main/resources/db/migration"))
            ? Paths.get("src/main/resources/db/migration")
            : Paths.get("backend/src/main/resources/db/migration");
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString())
                .filter(n -> n.matches("V\\d+__.*\\.sql"))
                .count();
        }
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
