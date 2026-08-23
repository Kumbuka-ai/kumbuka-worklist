package ai.kumbuka.worklist.tenancy;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Probe 1 — row-level security, with the red state observed rather than
 * described.
 *
 * <p>Each test below asserts the guarantee AND then removes the line the
 * guarantee rests on and watches it break, in the same run, on the same
 * connection, before putting the line back. That is the difference between a
 * gate and a comment. A test that only asserts the green half passes
 * identically on a schema where the policy was silently dropped — which is
 * the failure mode this whole arrangement exists to catch, so a probe blind
 * to it is worth very little.
 *
 * <h2>What is different here from the sibling service</h2>
 *
 * There the runtime role owns its tables, so {@code ENABLE} alone would have
 * exempted the only role that ever connects, and removing {@code FORCE} was
 * the way to watch isolation collapse for that role.
 *
 * <p>Here the runtime role owns nothing (V2), so {@code ENABLE} already binds
 * it and removing {@code FORCE} changes nothing about what it sees. The two
 * halves therefore separate, and both are probed:
 *
 * <ul>
 *   <li>the RUNTIME role's isolation rests on the POLICY and on row-level
 *       security being switched on at all, and the two behave OPPOSITELY when
 *       taken away — dropping the policy closes the table, disabling
 *       row-level security opens it. Both are watched;</li>
 *   <li>{@code FORCE} is still load-bearing, for the MIGRATOR, which owns
 *       these tables and is the role every future migration carrying DML runs
 *       as. Removing it is watched there, where it actually does something.</li>
 * </ul>
 *
 * <p>The removals are made against the running database and undone in a
 * {@code finally}, rather than as a throwaway migration. The effect is the
 * same and the observation is stronger: a throwaway migration is run once by
 * whoever wrote it, and this runs on every build.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class RowLevelSecurityProbeIT {

    private static final String POLICY = "item_tenant_isolation";
    private static final String TABLE = "worklist.item";

    /**
     * A fresh pair of tenants per test method.
     *
     * <p>The tests share one database, and row-level security counts every row
     * a tenant owns — including rows an earlier test planted. Fixed tenant ids
     * would make each assertion depend on which tests ran before it, so the
     * suite would pass or fail by execution order and the failure would look
     * like a broken policy. Fresh ids make each test's arithmetic its own.
     */
    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void freshTenants() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
    }

    /**
     * The policy filters a read to the bound tenant, and rows belonging to
     * another tenant are not merely hidden from a listing — they are absent
     * from a count, which is the form that cannot be papered over by a
     * presentation layer.
     */
    @Test
    void a_read_under_one_tenant_does_not_see_another_tenants_row() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            Db.insertItem(c, tenantA, "probe-a");
            Db.bindTenant(c, tenantB);
            Db.insertItem(c, tenantB, "probe-b");
            c.commit();

            Db.bindTenant(c, tenantA);
            assertThat(Db.countItems(c))
                .as("a session bound to tenant A must see A's row and only A's")
                .isEqualTo(1);

            Db.bindTenant(c, tenantB);
            assertThat(Db.countItems(c))
                .as("and symmetrically for B — otherwise the filter is not a filter but "
                    + "a coincidence about which rows happen to exist")
                .isEqualTo(1);
        }
    }

    /**
     * <strong>Probe B of the dispatch.</strong> The two ways this table's
     * isolation can be taken away, and they behave oppositely.
     *
     * <p>Both halves of the green state are asserted first, and the second one
     * is the half that is usually left out. Before anything is touched, the
     * foreign tenant's row is counted UNDER ITS OWN BINDING and must be there:
     * a probe that is green because the table is empty proves nothing at all,
     * and "tenant A sees zero rows of tenant B" is satisfied perfectly by a
     * tenant B that has no rows.
     *
     * <h2>Dropping the policy fails CLOSED, and that was worth measuring</h2>
     *
     * With row-level security enabled and no policy present, PostgreSQL
     * applies a default-deny: the table returns nothing to a non-owner, not
     * everything. So a schema that loses its policy loses ACCESS rather than
     * isolation — the service stops working instead of leaking, which is the
     * direction one wants a mistake to go in and is not what one would guess.
     *
     * <h2>Disabling row-level security fails OPEN</h2>
     *
     * That is the collapse. {@code DISABLE ROW LEVEL SECURITY} leaves the
     * policy sitting in the catalog, readable, apparently correct, and applied
     * to nobody. The foreign row appears with no error and no warning.
     *
     * <p>Both are probed because a reader who knew only the first would draw
     * the wrong conclusion — that this table cannot leak by losing a line of
     * DDL — and it can.
     */
    @Test
    void the_policy_admits_the_rows_and_switching_rls_off_admits_everybody_elses()
            throws SQLException {
        String ownTitle = "policy-probe-own-" + tenantA;
        String foreignTitle = "policy-probe-foreign-" + tenantB;

        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            Db.insertItem(c, tenantA, ownTitle);
            Db.bindTenant(c, tenantB);
            Db.insertItem(c, tenantB, foreignTitle);
            c.commit();

            // The half that makes every other assertion mean something: the
            // foreign row EXISTS, counted under the tenant that owns it.
            Db.bindTenant(c, tenantB);
            assertThat(Db.countItemsTitled(c, foreignTitle))
                .as("green run, and this is the count the whole probe rests on: tenant B's "
                    + "row is really in the table. Without it the assertions below would be "
                    + "satisfied by an empty table")
                .isEqualTo(1);

            Db.bindTenant(c, tenantA);
            assertThat(Db.countItemsTitled(c, ownTitle))
                .as("green state: this tenant sees its own row")
                .isEqualTo(1);
            assertThat(Db.countItemsTitled(c, foreignTitle))
                .as("green state: and not the other tenant's")
                .isZero();

            // Every lock this connection holds is released BEFORE the owner
            // asks for an ACCESS EXCLUSIVE one. The reads above left an open
            // transaction with a share lock on the table; without this commit
            // the DDL below waits for it, forever, and a hung build is much
            // worse than a failed one.
            c.commit();

            try {
                asOwner("DROP POLICY " + POLICY + " ON " + TABLE);

                Db.bindTenant(c, tenantA);
                assertThat(Db.countItemsTitled(c, ownTitle))
                    .as("RED STATE, observed: with the policy gone the table is CLOSED, not "
                        + "open — row-level security with no policy is a default deny, so "
                        + "even this tenant's own row disappears. The failure mode of a "
                        + "dropped policy is a service that cannot read, which is the "
                        + "direction a mistake should fall in")
                    .isZero();
                assertThat(Db.countItemsTitled(c, foreignTitle))
                    .as("and nothing of the other tenant's either")
                    .isZero();
                c.commit();

                asOwner("ALTER TABLE " + TABLE + " DISABLE ROW LEVEL SECURITY");

                Db.bindTenant(c, tenantA);
                assertThat(Db.countItemsTitled(c, foreignTitle))
                    .as("RED STATE, observed, and this is the one that leaks: with "
                        + "row-level security switched OFF the same session under the same "
                        + "tenant reads the other tenant's row. No error, no warning, and "
                        + "every tenant's rows returned")
                    .isEqualTo(1);
            } finally {
                // Unconditionally, and before the restore — a failed assertion
                // above must not leave a lock for the DDL to wait on.
                c.commit();
                asOwner("ALTER TABLE " + TABLE + " ENABLE ROW LEVEL SECURITY");
                asOwner("CREATE POLICY " + POLICY + " ON " + TABLE
                    + " USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)"
                    + " WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)");
            }

            Db.bindTenant(c, tenantA);
            assertThat(Db.countItemsTitled(c, ownTitle))
                .as("and restored: this tenant reads its own row again")
                .isEqualTo(1);
            assertThat(Db.countItemsTitled(c, foreignTitle))
                .as("and still not the other tenant's, so both red states above were the "
                    + "changes made and not some other drift")
                .isZero();
        }
    }

    /** Runs one statement as the table's owner, on a connection of its own. */
    private static void asOwner(String statement) throws SQLException {
        try (Connection owner = Db.asMigrator()) {
            Db.exec(owner, statement);
            owner.commit();
        }
    }

    /**
     * {@code FORCE}, where it still does something: the OWNER.
     *
     * <p>{@code ENABLE ROW LEVEL SECURITY} switches a policy on for every role
     * EXCEPT the table's owner. Here the owner is the migrator, and the
     * migrator is the role every future migration carrying DML runs as — a
     * seed, a backfill, a data correction. Without {@code FORCE} such a
     * migration would read and write across every tenant in the table and
     * report success.
     *
     * <p>So the probe removes FORCE and watches the migrator walk past the
     * policy that binds it a moment earlier.
     */
    @Test
    void without_force_the_owner_walks_straight_past_the_policy() throws SQLException {
        String foreignTitle = "force-probe-foreign-" + tenantB;

        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantB);
            Db.insertItem(c, tenantB, foreignTitle);
            c.commit();
        }

        try (Connection owner = Db.asMigrator()) {
            Db.bindTenant(owner, tenantA);
            assertThat(Db.countItemsTitled(owner, foreignTitle))
                .as("green state: FORCE binds the owner to its own policy, so the migrator "
                    + "bound to tenant A does not see tenant B's row either")
                .isZero();

            try {
                Db.exec(owner, "ALTER TABLE " + TABLE + " NO FORCE ROW LEVEL SECURITY");
                owner.commit();
                Db.bindTenant(owner, tenantA);

                assertThat(Db.countItemsTitled(owner, foreignTitle))
                    .as("RED STATE, observed: with FORCE removed the owner reads the other "
                        + "tenant's row. The policy still exists and still says the right "
                        + "thing; it simply does not apply to the owner. This is what a "
                        + "migration carrying DML would silently do to every tenant in the "
                        + "table")
                    .isEqualTo(1);
            } finally {
                Db.exec(owner, "ALTER TABLE " + TABLE + " FORCE ROW LEVEL SECURITY");
                owner.commit();
            }

            Db.bindTenant(owner, tenantA);
            assertThat(Db.countItemsTitled(owner, foreignTitle))
                .as("and restored, so the red state was the removal and nothing else")
                .isZero();
        }
    }

    /**
     * The write half. A policy with {@code USING} but no {@code WITH CHECK}
     * would let a session insert a row under a foreign tenant and then lose
     * sight of it — data planted across the boundary, invisible to the planter
     * and to the tenant that now owns it. The refusal is the database's, and
     * it names row-level security.
     */
    @Test
    void a_write_under_a_foreign_tenant_is_refused_by_the_policy() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            try {
                Db.insertItem(c, tenantB, "planted-across-the-boundary");
                throw new AssertionError(
                    "a session bound to tenant A inserted a row owned by tenant B — WITH CHECK "
                        + "is missing from the policy, and every write path can now cross the "
                        + "boundary the reads defend");
            } catch (SQLException expected) {
                assertThat(expected.getMessage())
                    .as("the refusal must come from the policy rather than from a constraint "
                        + "that happens to fire first")
                    .contains("row-level security");
            } finally {
                c.rollback();
            }
        }
    }
}
