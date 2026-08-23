package ai.kumbuka.worklist.boundary;

import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.worklist.tenancy.TenantMigrationCallback;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance criterion 3: V2 refuses to run against a wrongly-shaped service
 * role, and says which attribute is wrong.
 *
 * <h2>Why refusing is the right behaviour and repairing is not</h2>
 *
 * {@code SUPERUSER} and {@code BYPASSRLS} each make every policy in this
 * schema inert — unconditionally, silently, with rows returned and nothing
 * raised. Neither can be added or removed by this service's migrator, which
 * holds {@code CREATEROLE} and nothing more; both are superuser-only
 * operations. A migration that quietly stripped a security attribute would be
 * a migration that could quietly add one, so V2 stops instead and leaves the
 * decision with whoever shaped the role.
 *
 * <h2>Where the green state is observed</h2>
 *
 * Not here, and deliberately. A role carrying neither attribute is what every
 * other integration test in this suite migrates against — {@link
 * ai.kumbuka.worklist.tenancy.ColdStartIT} asserts both attributes are absent
 * on a role the migration itself created. Reconstructing that state in this
 * class would mean dropping and recreating a cluster-global role between
 * cases, which buys a second copy of an observation the suite already makes on
 * every run.
 *
 * <p>What is NOT already observed anywhere is the refusal, so that is what
 * this class is: two red states, one per attribute, each with the role shaped
 * wrong in exactly one way.
 */
class ServiceRoleAttributeGuardIT {

    private static final String SERVICE_ROLE = SubstrateDatabaseResource.SERVICE_ROLE;
    private static final String MIGRATOR = "attribute_guard_migrator";
    private static final String MIGRATOR_PASSWORD = "test-only-guard-password";

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startDatabase() throws SQLException {
        postgres = new PostgreSQLContainer<>(SubstrateDatabaseResource.POSTGRES_IMAGE)
            .withDatabaseName("kumbuka")
            .withUsername("postgres_admin")
            .withPassword("test-only-admin-password");
        postgres.start();

        asSuperuser("CREATE ROLE " + MIGRATOR + " LOGIN CREATEROLE NOSUPERUSER "
                + "NOBYPASSRLS PASSWORD '" + MIGRATOR_PASSWORD + "'",
            // Created by the SUPERUSER, not by the migration — which is the
            // situation the check exists for. A role the migrator creates
            // structurally cannot carry either attribute, because CREATEROLE
            // cannot confer them.
            "CREATE ROLE " + SERVICE_ROLE + " LOGIN PASSWORD 'test-only-service-password'");
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void the_migration_refuses_a_service_role_carrying_bypassrls() throws SQLException {
        asSuperuser("ALTER ROLE " + SERVICE_ROLE + " NOSUPERUSER BYPASSRLS");
        String url = freshDatabase("guard_bypassrls");

        assertThatThrownBy(() -> migrate(url))
            .as("RED STATE, observed: BYPASSRLS on the runtime role makes every policy in "
                + "V3 inert. The migration must stop rather than build a schema whose "
                + "isolation cannot hold, and it must name the attribute — a refusal that "
                + "only says 'wrong role' sends the reader to the wrong place")
            .isInstanceOf(FlywayException.class)
            .hasMessageContaining(SERVICE_ROLE)
            .hasMessageContaining("bypassrls=true");
    }

    @Test
    void the_migration_refuses_a_service_role_carrying_superuser() throws SQLException {
        asSuperuser("ALTER ROLE " + SERVICE_ROLE + " NOBYPASSRLS SUPERUSER");
        String url = freshDatabase("guard_superuser");

        assertThatThrownBy(() -> migrate(url))
            .as("RED STATE, observed: a superuser bypasses row-level security by a "
                + "different route and with the same result. Both are checked because "
                + "either alone is sufficient, and a check that only knew about one would "
                + "be green against the other")
            .isInstanceOf(FlywayException.class)
            .hasMessageContaining(SERVICE_ROLE)
            .hasMessageContaining("superuser=true");
    }

    /** Runs the service's real migration set against the given database. */
    private static void migrate(String url) {
        Flyway.configure()
            .dataSource(url, MIGRATOR, MIGRATOR_PASSWORD)
            .schemas("worklist")
            .defaultSchema("worklist")
            .createSchemas(true)
            .locations("classpath:db/migration")
            .callbacks(new TenantMigrationCallback())
            .load()
            .migrate();
    }

    /**
     * A database of this case's own — each case must migrate from nothing, or
     * the second would find the chain already applied and never reach V2.
     */
    private static String freshDatabase(String name) throws SQLException {
        asSuperuser("DROP DATABASE IF EXISTS " + name,
            "CREATE DATABASE " + name,
            "GRANT CREATE ON DATABASE " + name + " TO " + MIGRATOR);
        return postgres.getJdbcUrl().replace("/kumbuka?", "/" + name + "?");
    }

    private static void asSuperuser(String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }
}
