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
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard on who owns the read contract.
 *
 * <p>A view without {@code security_invoker} reads its base tables with the
 * privileges of its OWNER. That is what lets a consumer hold nothing on the
 * base tables — the whole point of publishing a contract rather than a table.
 * It is also the reason the owner matters more than it looks: with
 * {@code FORCE ROW LEVEL SECURITY} the ordinary owner is bound by the policy,
 * but a superuser and a {@code BYPASSRLS} role are exempt regardless.
 *
 * <p><strong>The failure is silent.</strong> A wrongly-owned view returns
 * rows, raises nothing, and leaves every test green. There is no error to
 * notice and no log line to find; the only observable is data that should not
 * have been there. So the property is asserted from the catalog, and the
 * assertion is watched failing.
 *
 * <h2>What was measured, and one correction it produced</h2>
 *
 * Measured on PostgreSQL 16.13 against a view carrying no tenant predicate of
 * its own — so that row-level security is genuinely its only guard:
 * superuser-owned returns every tenant's rows, {@code BYPASSRLS}-owned returns
 * every tenant's rows, and an owner that is neither returns only the bound
 * tenant's.
 *
 * <p>The published contract, however, carries the tenant predicate in its own
 * definition, and measured under a superuser owner it still returned only the
 * bound tenant's row. So for THIS view the owner guard is defence in depth
 * rather than the only defence. It stays load-bearing all the same: that
 * predicate is exactly the kind of line a later reader deletes as redundant
 * because "the policy covers it", and at that moment the owner becomes the
 * only thing left.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ViewOwnerGuardIT {

    private static final String VIEW = SubstrateDatabaseResource.PLATFORM_SCHEMA
        + "." + SubstrateDatabaseResource.DIRECTORY_VIEW;

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @Test
    void the_read_contract_is_owned_by_a_role_that_cannot_bypass_the_policy() throws SQLException {
        OwnerFacts owner = ownerOf(SubstrateDatabaseResource.DIRECTORY_VIEW);

        assertThat(owner.superuser())
            .as("a superuser-owned view is exempt from FORCE ROW LEVEL SECURITY on its "
                + "base tables, and the exemption is silent — rows returned, nothing "
                + "raised, every test green")
            .isFalse();

        assertThat(owner.bypassRls())
            .as("BYPASSRLS is the same exemption by a different attribute")
            .isFalse();
    }

    /**
     * The red state, observed on every build.
     *
     * <p>The view is handed to a superuser for the length of one assertion and
     * the guard has to notice. Without this the guard is a query that finds
     * nothing rather than an owner that can do nothing, and the two look
     * identical in a passing build.
     */
    @Test
    void the_guard_notices_a_superuser_owner() throws SQLException {
        String original = ownerOf(SubstrateDatabaseResource.DIRECTORY_VIEW).name();
        String admin = ConfigProvider.getConfig()
            .getValue("test.db.admin.username", String.class);

        try {
            PlatformFixture.run("ALTER VIEW " + VIEW + " OWNER TO " + admin);

            OwnerFacts wrong = ownerOf(SubstrateDatabaseResource.DIRECTORY_VIEW);
            assertThat(wrong.superuser())
                .as("RED STATE, observed: with the view handed to a superuser the guard's "
                    + "own query must report it. If it did not, the green assertion would "
                    + "be measuring nothing")
                .isTrue();
        } finally {
            PlatformFixture.run("ALTER VIEW " + VIEW + " OWNER TO "
                + SubstrateDatabaseResource.PLATFORM_ROLE);
        }

        assertThat(ownerOf(SubstrateDatabaseResource.DIRECTORY_VIEW).name())
            .as("and restored, so the red state was the ownership change and nothing else")
            .isEqualTo(original);
    }

    /**
     * The mechanism itself, isolated: a view whose only guard is the policy.
     *
     * <p>The published contract filters on its own, so it cannot show what the
     * owner alone decides. This one has no predicate, so it can — and it is
     * what turns "a superuser owner is exempt" from a statement about
     * PostgreSQL into an observation about this database on this version.
     */
    @Test
    void an_owner_that_can_bypass_the_policy_leaks_through_a_view_without_a_predicate()
            throws SQLException {
        String bare = SubstrateDatabaseResource.PLATFORM_SCHEMA + ".scope_bare_probe";
        String admin = ConfigProvider.getConfig()
            .getValue("test.db.admin.username", String.class);
        try {
            PlatformFixture.run(
                "CREATE VIEW " + bare + " AS SELECT slug, tenant_id FROM public.scope",
                "GRANT SELECT ON " + bare + " TO " + SubstrateDatabaseResource.SERVICE_ROLE,
                // Plant a second tenant's row, so a leak has something to show.
                "SELECT set_config('app.tenant_id', "
                    + "'00000000-0000-0000-0000-0000000000ff', false)",
                "INSERT INTO public.scope (tenant_id, slug, kind) VALUES "
                    + "('00000000-0000-0000-0000-0000000000ff', 'foreign-scope', 'project')",
                "RESET app.tenant_id");

            // Superuser-owned: the policy does not reach it.
            PlatformFixture.run("ALTER VIEW " + bare + " OWNER TO " + admin);
            assertThat(slugsThrough(bare))
                .as("RED STATE, observed: a superuser-owned view whose only guard is the "
                    + "policy returns another tenant's rows to a caller bound to ours")
                .contains("foreign-scope");

            // Owned by a role that is neither superuser nor BYPASSRLS: FORCE binds it.
            PlatformFixture.run("ALTER VIEW " + bare + " OWNER TO "
                + SubstrateDatabaseResource.PLATFORM_ROLE);
            assertThat(slugsThrough(bare))
                .as("and with an owner the policy does reach, the same view under the same "
                    + "binding returns only ours — so the difference was the owner")
                .doesNotContain("foreign-scope");
        } finally {
            PlatformFixture.run(
                "DROP VIEW IF EXISTS " + bare,
                "SELECT set_config('app.tenant_id', "
                    + "'00000000-0000-0000-0000-0000000000ff', false)",
                "DELETE FROM public.scope WHERE slug = 'foreign-scope'",
                "RESET app.tenant_id");
        }
    }

    /** Reads the view under the service role, with our tenant bound. */
    private static List<String> slugsThrough(String view) throws SQLException {
        var config = ConfigProvider.getConfig();
        try (Connection c = DriverManager.getConnection(config.getValue("test.db.url", String.class),
                SubstrateDatabaseResource.SERVICE_ROLE, SubstrateDatabaseResource.SERVICE_PASSWORD)) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("SELECT set_config('app.tenant_id', '"
                    + SubstrateDatabaseResource.TENANT_ID + "', true)");
                try (ResultSet rs = s.executeQuery("SELECT slug FROM " + view)) {
                    List<String> slugs = new java.util.ArrayList<>();
                    while (rs.next()) {
                        slugs.add(rs.getString(1));
                    }
                    return slugs;
                }
            } finally {
                c.rollback();
            }
        }
    }

    private static OwnerFacts ownerOf(String relname) throws SQLException {
        var config = ConfigProvider.getConfig();
        try (Connection c = DriverManager.getConnection(
                config.getValue("test.db.url", String.class),
                config.getValue("test.db.admin.username", String.class),
                config.getValue("test.db.admin.password", String.class));
             var st = c.prepareStatement("""
                 SELECT pg_get_userbyid(cl.relowner),
                        (SELECT rolsuper     FROM pg_roles WHERE oid = cl.relowner),
                        (SELECT rolbypassrls FROM pg_roles WHERE oid = cl.relowner)
                 FROM pg_class cl JOIN pg_namespace n ON n.oid = cl.relnamespace
                 WHERE n.nspname = ? AND cl.relname = ?
                 """)) {
            st.setString(1, SubstrateDatabaseResource.PLATFORM_SCHEMA);
            st.setString(2, relname);
            try (ResultSet rs = st.executeQuery()) {
                assertThat(rs.next())
                    .as("the read contract must exist for its owner to be checked")
                    .isTrue();
                return new OwnerFacts(rs.getString(1), rs.getBoolean(2), rs.getBoolean(3));
            }
        }
    }

    private record OwnerFacts(String name, boolean superuser, boolean bypassRls) {
    }
}
