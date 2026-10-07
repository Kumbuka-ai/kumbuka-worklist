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
public final class Db {

    private Db() {
    }

    /** The container superuser. Stages fixtures; never what the service uses. */
    public static Connection asAdmin() throws SQLException {
        return connect(config("test.db.admin.username"), config("test.db.admin.password"));
    }

    /**
     * CREATEROLE and nothing more — the role the migration set runs under, and
     * the owner of this schema and everything in it.
     *
     * <p>Public so a probe outside {@code tenancy} can borrow the owner
     * briefly, for instance to drop a check constraint and put it back so a
     * row that violates it can be planted for a read-path probe. What a
     * migrator can do that the service role cannot is exactly what such a
     * probe needs, and staging it in a helper here would mean the helper
     * imports the probe's intent — which is the coupling this class exists to
     * avoid.
     */
    public static Connection asMigrator() throws SQLException {
        return connect(SubstrateDatabaseResource.MIGRATOR_ROLE,
            SubstrateDatabaseResource.MIGRATOR_PASSWORD);
    }

    public static Connection asService() throws SQLException {
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
    public static void bindTenant(Connection c, UUID tenant) throws SQLException {
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

    public static void exec(Connection c, String sql) throws SQLException {
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
     *
     * <p>A status is declared first, because an item carries a mandatory
     * reference to one. That is not scaffolding this helper works around: a
     * status is a value the scope declared rather than a literal, so a scope
     * has a vocabulary before it has an item, and a fixture that faked its way
     * past that would be planting a row the service could not have written.
     *
     * <p>The address is the next free number in the scope, set with the
     * insert: an item's number is unique within its scope and is the target
     * of every key onto it (ADR-0042), so a fixture that reused one would be
     * planting a row the service could not have written.
     */
    static UUID insertItem(Connection c, UUID tenant, String title) throws SQLException {
        long status = declaredStatus(c, tenant);
        UUID scope = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);
        long workstream = workstreamNumber(c, ensureDefaultWorkstream(c, tenant, scope));
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.item
                    (tenant_id, scope_id, title, status_pk, number, workstream_number)
                VALUES (?::uuid, ?::uuid, ?, ?,
                    (SELECT coalesce(max(number), 0) + 1 FROM worklist.item
                      WHERE tenant_id = ?::uuid AND scope_id = ?::uuid), ?)
                RETURNING id
                """)) {
            st.setString(1, tenant.toString());
            st.setString(2, scope.toString());
            st.setString(3, title);
            st.setLong(4, status);
            st.setString(5, tenant.toString());
            st.setString(6, scope.toString());
            st.setLong(7, workstream);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return UUID.fromString(rs.getString(1));
            }
        }
    }

    /** The number of a workstream, by its identity. */
    public static long workstreamNumber(Connection c, UUID workstream) throws SQLException {
        try (var st = c.prepareStatement(
                "SELECT number FROM worklist.workstream WHERE id = ?::uuid")) {
            st.setString(1, workstream.toString());
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /**
     * The tenant's default workstream, planted on first use exactly the way
     * {@link #declaredStatus} plants a status.
     *
     * <p>V10 makes an item's workstream NOT NULL. A
     * fixture that inserts an item without it would be planting a row the
     * service could not have written; this helper is what keeps the
     * fixtures shaped like the service. (V10 narrowed the milestone's column
     * too; V13 widened it again and V17 dropped it.)
     *
     * <p>The workstream selector and its number-space row are opened along
     * the way if they were not already, and the mark is advanced to 1 so
     * the next allocation returns 2. The row is only created when it is
     * missing; subsequent calls return the existing one.
     */
    public static UUID ensureDefaultWorkstream(Connection c, UUID tenant) throws SQLException {
        return ensureDefaultWorkstream(c, tenant,
            UUID.fromString(SubstrateDatabaseResource.SCOPE_ID));
    }

    /** Same helper, for a scope other than the substrate's fixed one. */
    public static UUID ensureDefaultWorkstream(Connection c, UUID tenant, UUID scope)
            throws SQLException {
        try (var st = c.prepareStatement(
                "SELECT id FROM worklist.workstream "
                    + "WHERE tenant_id = ?::uuid AND scope_id = ?::uuid AND is_default = true")) {
            st.setString(1, tenant.toString());
            st.setString(2, scope.toString());
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    return UUID.fromString(rs.getString(1));
                }
            }
        }
        Long workstreamSelector = null;
        try (var st = c.prepareStatement(
                "SELECT pk FROM worklist.selector "
                    + "WHERE tenant_id = ?::uuid AND scope_id = ?::uuid AND token = 'workstream'")) {
            st.setString(1, tenant.toString());
            st.setString(2, scope.toString());
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    workstreamSelector = rs.getLong(1);
                }
            }
        }
        if (workstreamSelector == null) {
            try (var st = c.prepareStatement("""
                    INSERT INTO worklist.selector (tenant_id, scope_id, token)
                    VALUES (?::uuid, ?::uuid, 'workstream')
                    RETURNING pk
                    """)) {
                st.setString(1, tenant.toString());
                st.setString(2, scope.toString());
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    workstreamSelector = rs.getLong(1);
                }
            }
        }
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.number_space
                    (tenant_id, scope_id, selector_pk, high_water_mark)
                VALUES (?::uuid, ?::uuid, ?, 1)
                ON CONFLICT (tenant_id, scope_id, selector_pk)
                DO UPDATE SET high_water_mark = GREATEST(worklist.number_space.high_water_mark, 1)
                """)) {
            st.setString(1, tenant.toString());
            st.setString(2, scope.toString());
            st.setLong(3, workstreamSelector);
            st.execute();
        }
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.workstream
                    (tenant_id, scope_id, number, token, description, is_default)
                VALUES (?::uuid, ?::uuid, 1, 'default',
                    'test-fixture default workstream — the intake landing of every '
                        || 'row a fixture plants directly', true)
                RETURNING id
                """)) {
            st.setString(1, tenant.toString());
            st.setString(2, scope.toString());
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return UUID.fromString(rs.getString(1));
            }
        }
    }

    /**
     * The tenant's own actionable status, declared on first use.
     *
     * <p>Looked up before it is created, because these probes plant several
     * items under one tenant and a status per item would leave the scope
     * carrying a vocabulary of duplicates that mean the same thing.
     */
    static long declaredStatus(Connection c, UUID tenant) throws SQLException {
        try (var st = c.prepareStatement(
                "SELECT pk FROM worklist.item_status WHERE tenant_id = ?::uuid LIMIT 1")) {
            st.setString(1, tenant.toString());
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.item_status
                    (tenant_id, scope_id, name, actionable, in_progress, closed, successful)
                VALUES (?::uuid, ?::uuid, 'open', true, false, false, false)
                RETURNING pk
                """)) {
            st.setString(1, tenant.toString());
            st.setString(2, SubstrateDatabaseResource.SCOPE_ID);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
