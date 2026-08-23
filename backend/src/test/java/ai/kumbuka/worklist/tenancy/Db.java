package ai.kumbuka.worklist.tenancy;

import org.eclipse.microprofile.config.ConfigProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Raw JDBC under a named role, for probes that need to see what a role can
 * actually do.
 *
 * <p>The probes deliberately go around the ORM. Layer 1 — the Hibernate
 * tenant filter — rewrites every query it routes, so a statement that goes
 * through it can never demonstrate what layer 2 does on its own. Raw SQL is
 * the only way to ask the database directly, and asking the database directly
 * is the whole point of a policy that exists because raw SQL is possible.
 */
final class Db {

    private Db() {
    }

    /** The container superuser. Stages fixtures; never what the service uses. */
    static Connection asAdmin() throws SQLException {
        return connect(config("test.db.admin.username"), config("test.db.admin.password"));
    }

    /**
     * CREATEROLE and nothing more — the role the migration set runs under, and
     * the owner of this schema and everything in it.
     */
    static Connection asMigrator() throws SQLException {
        return connect(SubstrateDatabaseResource.MIGRATOR_ROLE,
            SubstrateDatabaseResource.MIGRATOR_PASSWORD);
    }

    static Connection asService() throws SQLException {
        return connect(SubstrateDatabaseResource.SERVICE_ROLE,
            SubstrateDatabaseResource.SERVICE_PASSWORD);
    }

    static Connection asProvider() throws SQLException {
        return connect(SubstrateDatabaseResource.PROVIDER_ROLE,
            SubstrateDatabaseResource.PROVIDER_PASSWORD);
    }

    /**
     * Every probe connection carries a lock timeout, and it is not a
     * performance setting.
     *
     * <p>Several probes change the schema from one connection while reading
     * from another — dropping a policy, removing FORCE. DDL takes an
     * {@code ACCESS EXCLUSIVE} lock and waits, by default forever, for every
     * reader that has an open transaction on the table. Get the order wrong by
     * one statement and the build does not fail: it HANGS, until a CI job
     * times out twenty minutes later with no indication of what it was waiting
     * for.
     *
     * <p>Ten seconds is far longer than any statement in this suite needs and
     * far shorter than a person's patience. What it buys is that the mistake
     * arrives as an error naming the lock, on the line that took it.
     */
    private static final String LOCK_TIMEOUT = "10s";

    private static Connection connect(String user, String password) throws SQLException {
        Connection c = DriverManager.getConnection(config("test.db.url"), user, password);
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            s.execute("SET lock_timeout = '" + LOCK_TIMEOUT + "'");
        }
        c.commit();
        return c;
    }

    private static String config(String key) {
        return ConfigProvider.getConfig().getValue(key, String.class);
    }

    /**
     * Bind, or deliberately fail to bind, the tenant GUC on this connection.
     * A null tenant resets it, which is how the fail-closed half of the
     * probes reaches the state a forgotten binding would produce.
     */
    static void bindTenant(Connection c, UUID tenant) throws SQLException {
        try (Statement s = c.createStatement()) {
            if (tenant == null) {
                s.execute("RESET app.tenant_id");
            } else {
                s.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
            }
        }
    }

    static long countItems(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM worklist.item")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Rows with a given title as the CURRENT session sees them. */
    static long countItemsTitled(Connection c, String title) throws SQLException {
        try (var st = c.prepareStatement(
                "SELECT count(*) FROM worklist.item WHERE title = ?")) {
            st.setString(1, title);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /**
     * Insert an item directly, bypassing the ORM, under the tenant named.
     * Used to plant rows a later read must or must not see.
     *
     * <p>Going around the ORM is the point: layer 1 rewrites every statement
     * it builds, so a row planted through it could never demonstrate what
     * layer 2 does on its own.
     */
    static UUID insertItem(Connection c, UUID tenant, String title) throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.item (tenant_id, scope_id, title)
                VALUES (?::uuid, ?::uuid, ?)
                RETURNING id
                """)) {
            st.setString(1, tenant.toString());
            st.setString(2, SubstrateDatabaseResource.SCOPE_ID);
            st.setString(3, title);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return UUID.fromString(rs.getString(1));
            }
        }
    }
}
