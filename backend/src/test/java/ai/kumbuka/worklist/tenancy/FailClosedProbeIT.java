package ai.kumbuka.worklist.tenancy;

import ai.kumbuka.worklist.domain.Item;
import ai.kumbuka.worklist.domain.ItemStore;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Probe 3 — the lock closes, and the data is still there.
 *
 * <p>Both halves belong to the probe. A read with no tenant bound returns
 * nothing, which is the guarantee; and the same read with the tenant bound
 * returns the rows, which is what distinguishes a lock from an empty table.
 * Asserting only the first half would pass against a database where the
 * insert never landed, the schema is wrong, or the rows were deleted — every
 * one of which looks exactly like perfect isolation from the outside.
 *
 * <p>The second pair of tests takes the two enforcement layers apart. Both
 * are on at once in normal operation, so an ordinary assertion cannot say
 * which one did the work — and a layer that is quietly doing nothing is a
 * layer that will not be there when the other one is removed. Each is
 * therefore observed alone, by switching the other off for the length of one
 * assertion.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class FailClosedProbeIT {

    /** The scope the probe items sit in. Any uuid; fixed for legibility. */
    static final UUID SCOPE = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);

    private static final String TABLE = "worklist.item";

    /**
     * A fresh pair of tenants per test method — the tests share one database,
     * and a count under a fixed tenant would include whatever an earlier test
     * planted there. See the same note in {@link RowLevelSecurityProbeIT}.
     */
    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void freshTenants() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
    }

    @Inject ItemStore items;
    @Inject TenantContext tenantContext;

    /** Acceptance criterion 5, in both of its halves. */
    @Test
    void an_unbound_read_returns_nothing_and_the_rows_are_still_there() throws SQLException {
        String title = "fail-closed-" + tenantA;

        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            Db.insertItem(c, tenantA, title);
            c.commit();

            // First half: no binding, no rows. The predicate compares against
            // NULL, which a policy treats as failing, so the table is closed
            // rather than open.
            Db.bindTenant(c, null);
            assertThat(Db.countItemsTitled(c, title))
                .as("a transaction that never bound a tenant must see nothing at all — "
                    + "the predicate fails closed, and this is the half that is the guarantee")
                .isZero();
            assertThat(Db.countItems(c))
                .as("and nothing of anybody else's either, so the emptiness is the whole "
                    + "table and not one row that happens to be missing")
                .isZero();

            // Second half: bind, and the row is there. Without this the
            // assertion above would hold just as well against a table that is
            // simply empty, and an empty table proves nothing about a policy.
            Db.bindTenant(c, tenantA);
            assertThat(Db.countItemsTitled(c, title))
                .as("and with the tenant bound the row is present and unchanged — which is "
                    + "what makes the emptiness above a lock rather than an absence")
                .isEqualTo(1);
        }
    }

    /**
     * An unbound WRITE is refused rather than silently dropped.
     *
     * <p>The read half above fails closed by returning nothing. The write half
     * fails closed by raising, because {@code WITH CHECK} compares the
     * incoming row against an unbound setting and cannot admit it. The two are
     * worth separating: a write that quietly affected no rows would leave a
     * caller believing it had stated an item.
     */
    @Test
    void an_unbound_write_is_refused_by_the_policy() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, null);
            try {
                Db.insertItem(c, tenantA, "written-with-nothing-bound");
                throw new AssertionError(
                    "an insert with no tenant bound was admitted — the policy's WITH CHECK "
                        + "is comparing against something other than the session setting");
            } catch (SQLException expected) {
                assertThat(expected.getMessage()).contains("row-level security");
            } finally {
                c.rollback();
            }
        }
    }

    /**
     * Layer 1 alone: the ORM filter, with the database policy switched off.
     *
     * <p>Hibernate rewrites every statement it routes with the tenant
     * predicate. That holds without any help from the database, and this is
     * where it is seen holding: row-level security is disabled for the length
     * of the assertion, so nothing but the filter is left to do the work.
     */
    @Test
    void the_orm_filter_isolates_on_its_own_when_the_policy_is_off() throws Exception {
        plantOneItemPerTenant();

        try (Connection owner = Db.asMigrator()) {
            try {
                Db.exec(owner, "ALTER TABLE " + TABLE + " DISABLE ROW LEVEL SECURITY");
                owner.commit();

                try (AutoCloseable ignored = tenantContext.bind(tenantA)) {
                    // The answer carries no tenant — the axis is never a field
                    // a caller reads. The titles carry it instead, which is
                    // why plantOneItemPerTenant writes them that way: with the
                    // policy off, a row of tenant B would appear here, and its
                    // title would say so.
                    var rows = items.survey(SCOPE);
                    assertThat(rows)
                        .as("with the policy disabled, the ORM filter is the only thing "
                            + "scoping this read — and it must still scope it")
                        .isNotEmpty()
                        .allSatisfy(item -> assertThat(String.valueOf(item.get("title")))
                            .endsWith(tenantA.toString()));
                }

                // The other side of the same observation: raw SQL, which the
                // ORM never rewrote, now sees both tenants. That is precisely
                // the gap layer 2 exists to close, and it is visible here.
                try (Connection c = Db.asService()) {
                    Db.bindTenant(c, tenantA);
                    assertThat(Db.countItemsTitled(c, titleB()))
                        .as("RED STATE, observed: with row-level security off, raw SQL "
                            + "under tenant A reads tenant B's row too. The ORM filter "
                            + "cannot reach a statement it did not build, which is the "
                            + "whole reason for a second layer")
                        .isEqualTo(1);
                }
            } finally {
                Db.exec(owner, "ALTER TABLE " + TABLE + " ENABLE ROW LEVEL SECURITY");
                owner.commit();
            }
        }
    }

    /**
     * Layer 2 alone: the database policy, against a statement the ORM never
     * saw. No switching is needed here — raw SQL is by definition outside
     * layer 1, so anything that scopes it is the policy.
     */
    @Test
    void the_policy_isolates_raw_sql_that_the_orm_never_touched() throws Exception {
        plantOneItemPerTenant();

        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            assertThat(Db.countItemsTitled(c, titleB()))
                .as("raw SQL bypasses the ORM filter entirely, so this count is the policy's "
                    + "work and nobody else's")
                .isZero();
            assertThat(Db.countItemsTitled(c, titleA()))
                .as("and its own row is there, so the zero above is a filter rather than "
                    + "an empty table")
                .isEqualTo(1);
        }
    }

    /**
     * One item per tenant, written through the ORM so both layers are on the
     * path.
     *
     * <p>The titles carry the tenant id. Two cases below plant a pair each,
     * and one of them then reads with row-level security switched OFF — where
     * raw SQL sees the whole table, the other case's rows included. Fixed
     * titles would make that count depend on execution order, and a failure
     * would read as a broken policy rather than as two tests sharing a name.
     */
    private void plantOneItemPerTenant() throws Exception {
        try (AutoCloseable ignored = tenantContext.bind(tenantA)) {
            items.state(SCOPE, java.util.Map.of("title", titleA()));
        }
        try (AutoCloseable ignored = tenantContext.bind(tenantB)) {
            items.state(SCOPE, java.util.Map.of("title", titleB()));
        }
    }

    private String titleA() {
        return "layers-a-" + tenantA;
    }

    private String titleB() {
        return "layers-b-" + tenantB;
    }
}
