package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.Db;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The declared keys of ADR-0042, observed against a running database under
 * the role the service writes as.
 *
 * <p>A key onto a primary object is {@code (tenant_id, scope_id, number)}, a
 * key onto a support row {@code (tenant_id, pk)}. Stage R1 held these
 * invariants with sync triggers while the uuid references still stood; since
 * stage R3 the keys hold them alone. This class was the probe of those
 * triggers and now probes the keys: a number that names nothing, a number
 * that names an object only in another scope, a surrogate of another tenant,
 * and the immutability of a primary object's number and scope that makes the
 * number fit to be a key target.
 *
 * <p>Every write runs as {@code kumbuka_worklist} with the tenant bound, the
 * binding a real write has. Expectations are literal numbers chosen here.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ReferenceKeyIT {

    private static final UUID TENANT = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);
    private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private UUID scope;
    private long statusOpen;

    @BeforeEach
    void aScopeWithItsVocabulary() throws SQLException {
        scope = UUID.randomUUID();
        try (Connection c = service()) {
            statusOpen = status(c, TENANT, scope, "open");
            execute(c, "INSERT INTO worklist.workstream (tenant_id, scope_id, number, token, "
                + "description) VALUES (?, ?, 4, 'main', 'd')", TENANT, scope);
            c.commit();
        }
    }

    @Test
    void a_reference_by_number_resolves_to_the_item_of_its_scope() throws SQLException {
        try (Connection c = service()) {
            UUID item = item(c, 7);
            execute(c, "INSERT INTO worklist.claim (tenant_id, scope_id, item_number, receipt, "
                + "actor, expires_at) VALUES (?, ?, 7, 'r', 'a', now() + interval '1 hour')",
                TENANT, scope);
            c.commit();

            assertThat(uuidOf(c, "SELECT i.id FROM worklist.claim cl JOIN worklist.item i "
                    + "ON i.tenant_id = cl.tenant_id AND i.scope_id = cl.scope_id "
                    + "AND i.number = cl.item_number WHERE cl.scope_id = ?", scope))
                .as("the claim holds number 7, and (tenant, scope, number) names one item")
                .isEqualTo(item);
        }
    }

    @Test
    void a_number_that_names_no_object_is_refused_by_the_key() throws SQLException {
        try (Connection c = service()) {
            item(c, 1);
            c.commit();
            SQLException refused = catchThrowableOfType(SQLException.class, () -> execute(c,
                "INSERT INTO worklist.claim (tenant_id, scope_id, item_number, receipt, actor, "
                    + "expires_at) VALUES (?, ?, 99, 'r', 'a', now() + interval '1 hour')",
                TENANT, scope));
            c.rollback();
            assertThat((Throwable) refused)
                .as("no item 99 exists in this scope; the declared key refuses the dangling "
                    + "reference the store used to accept as a uuid")
                .isNotNull();
            assertThat(refused.getSQLState()).isEqualTo(FOREIGN_KEY_VIOLATION);
            assertThat(refused.getMessage()).contains("fk_claim_item_number");
        }
    }

    @Test
    void a_number_that_names_an_item_only_in_another_scope_is_refused_by_the_key()
            throws SQLException {
        UUID otherScope = UUID.randomUUID();
        try (Connection c = service()) {
            long otherStatus = status(c, TENANT, otherScope, "open");
            execute(c, "INSERT INTO worklist.workstream (tenant_id, scope_id, number, token, "
                + "description) VALUES (?, ?, 1, 'main', 'd')", TENANT, otherScope);
            execute(c, "INSERT INTO worklist.item (tenant_id, scope_id, title, number, status_pk, "
                + "workstream_number) VALUES (?, ?, 't', 2, ?, 1)", TENANT, otherScope, otherStatus);
            c.commit();

            SQLException refused = catchThrowableOfType(SQLException.class, () -> execute(c,
                "INSERT INTO worklist.claim (tenant_id, scope_id, item_number, receipt, actor, "
                    + "expires_at) VALUES (?, ?, 2, 'r', 'a', now() + interval '1 hour')",
                TENANT, scope));
            c.rollback();
            assertThat((Throwable) refused)
                .as("item 2 exists, but in another scope; a key onto a primary object stays "
                    + "inside its scope, so it does not resolve here")
                .isNotNull();
            assertThat(refused.getSQLState()).isEqualTo(FOREIGN_KEY_VIOLATION);
        }
    }

    @Test
    void a_surrogate_of_another_tenant_is_refused_by_the_key() throws SQLException {
        long foreignStatus;
        try (Connection a = Db.asAdmin()) {
            Db.bindTenant(a, OTHER_TENANT);
            foreignStatus = status(a, OTHER_TENANT, UUID.randomUUID(), "open");
            a.commit();
        }
        try (Connection c = service()) {
            SQLException refused = catchThrowableOfType(SQLException.class, () -> execute(c,
                "INSERT INTO worklist.item (tenant_id, scope_id, title, number, status_pk, "
                    + "workstream_number) VALUES (?, ?, 't', 3, ?, 4)", TENANT, scope, foreignStatus));
            c.rollback();
            assertThat((Throwable) refused)
                .as("the status belongs to another tenant; every key carries the tenant "
                    + "because a key is checked with row-level security bypassed")
                .isNotNull();
            assertThat(refused.getSQLState()).isEqualTo(FOREIGN_KEY_VIOLATION);
            assertThat(refused.getMessage()).contains("fk_item_status_pk");
        }
    }

    @Test
    void the_number_of_a_primary_object_cannot_change() throws SQLException {
        try (Connection c = service()) {
            UUID item = item(c, 11);
            c.commit();
            SQLException refused = catchThrowableOfType(SQLException.class,
                () -> execute(c, "UPDATE worklist.item SET number = 12 WHERE id = ?", item));
            c.rollback();
            assertThat((Throwable) refused)
                .as("an item's number is the target of every key onto it; V18 makes it "
                    + "immutable below the domain")
                .isNotNull();
            assertThat(refused.getMessage()).contains("is immutable");

            refused = catchThrowableOfType(SQLException.class, () -> execute(c,
                "UPDATE worklist.workstream SET number = 5 WHERE scope_id = ?", scope));
            c.rollback();
            assertThat((Throwable) refused).as("the same holds for a workstream").isNotNull();
        }
    }

    @Test
    void the_scope_of_a_primary_object_cannot_change() throws SQLException {
        try (Connection c = service()) {
            UUID item = item(c, 13);
            c.commit();
            SQLException refused = catchThrowableOfType(SQLException.class, () -> execute(c,
                "UPDATE worklist.item SET scope_id = ? WHERE id = ?", UUID.randomUUID(), item));
            c.rollback();
            assertThat((Throwable) refused)
                .as("moving an item between scopes would re-point every key that holds its "
                    + "number; V18 refuses it below the domain")
                .isNotNull();
            assertThat(refused.getMessage()).contains("is immutable");
        }
    }

    // ==================================================================
    // Fixtures.
    // ==================================================================

    private static Connection service() throws SQLException {
        Connection c = Db.asService();
        Db.bindTenant(c, TENANT);
        return c;
    }

    private UUID item(Connection c, long number) throws SQLException {
        try (PreparedStatement st = prepare(c, "INSERT INTO worklist.item (tenant_id, scope_id, "
                + "title, number, status_pk, workstream_number) VALUES (?, ?, 't', ?, ?, 4) "
                + "RETURNING id", TENANT, scope, number, statusOpen);
             ResultSet rs = st.executeQuery()) {
            rs.next();
            return rs.getObject(1, UUID.class);
        }
    }

    private static long status(Connection c, UUID tenant, UUID inScope, String name)
            throws SQLException {
        try (PreparedStatement st = prepare(c, "INSERT INTO worklist.item_status (tenant_id, "
                + "scope_id, name, actionable, in_progress, closed) VALUES (?, ?, ?, true, false, "
                + "false) RETURNING pk", tenant, inScope, name);
             ResultSet rs = st.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void execute(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement st = prepare(c, sql, args)) {
            st.execute();
        }
    }

    private static UUID uuidOf(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement st = prepare(c, sql, args); ResultSet rs = st.executeQuery()) {
            assertThat(rs.next()).as("a row for: " + sql).isTrue();
            return rs.getObject(1, UUID.class);
        }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... args)
            throws SQLException {
        PreparedStatement st = c.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            st.setObject(i + 1, args[i]);
        }
        return st;
    }
}
