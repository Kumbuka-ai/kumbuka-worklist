package ai.kumbuka.worklist.platform;

import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import org.eclipse.microprofile.config.ConfigProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Issues the one grant the platform gives a consuming service, once the
 * consuming role exists.
 *
 * <p>Not part of {@code SubstrateDatabaseResource}, because of ordering that
 * mirrors a deployment: the service's own migration creates its role during
 * boot, and the test resource runs before that. In a deployment the platform
 * grants to a role it already knows about, from its own migration, and this
 * helper stands in for exactly that step and no other.
 *
 * <p>Idempotent, so a test class can call it without knowing whether another
 * already did.
 */
public final class PlatformFixture {

    private PlatformFixture() {
    }

    /** {@code USAGE} on the schema and {@code SELECT} on the view. Nothing else. */
    public static void grantDirectoryAccess() {
        run("GRANT USAGE ON SCHEMA " + SubstrateDatabaseResource.PLATFORM_SCHEMA
                + " TO " + SubstrateDatabaseResource.SERVICE_ROLE,
            "GRANT SELECT ON " + SubstrateDatabaseResource.PLATFORM_SCHEMA + "."
                + SubstrateDatabaseResource.DIRECTORY_VIEW
                + " TO " + SubstrateDatabaseResource.SERVICE_ROLE);
    }

    /** Runs statements as the container superuser — the one role the service never uses. */
    public static void run(String... statements) {
        var config = ConfigProvider.getConfig();
        try (Connection c = DriverManager.getConnection(
                config.getValue("test.db.url", String.class),
                config.getValue("test.db.admin.username", String.class),
                config.getValue("test.db.admin.password", String.class));
             Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("platform fixture failed: " + e.getMessage(), e);
        }
    }
}
