package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.Db;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * ADR-0042 stage R1, observed against a running database under the role the
 * service writes as.
 *
 * <p>V18 adds a readable sibling beside every uuid reference — a number where
 * the target is a primary object, a surrogate where it is a support row — and
 * a trigger per child table that keeps the two in step. The image before V18
 * writes only uuids, the image after writes only the siblings, and both have
 * to leave a row whose two sides name the same parent. These probes write
 * through each side alone, through both in contradiction, and across a scope
 * and a tenant boundary, and read back what the row holds.
 *
 * <p>Every write runs as {@code kumbuka_worklist} with the tenant bound, so
 * the triggers resolve under exactly the row-level-security binding a real
 * write has. Expectations are literal numbers chosen here, never read back
 * from the parent rows the trigger resolves against.
 *
 * <p>The backfill is exercised by running the DO-blocks of V18 itself, read
 * from the classpath, rather than a re-implementation of them: a copy would
 * agree with itself whatever the migration says.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ReferenceSiblingSyncIT {

    private static final UUID TENANT = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);
    private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final String V18 = "db/migration/V18__adr0042_expand_number_and_surrogate_keys.sql";

    private UUID scope;
    private UUID selector;
    private UUID statusOpen;
    private UUID workstream;

    @BeforeEach
    void aScopeWithItsVocabulary() throws SQLException {
        scope = UUID.randomUUID();
        try (Connection c = service()) {
            selector = insertReturningId(c, "INSERT INTO worklist.selector (tenant_id, scope_id, token) "
                + "VALUES (?, ?, 'item') RETURNING id", TENANT, scope);
            statusOpen = status(c, scope, "open");
            workstream = insertReturningId(c, "INSERT INTO worklist.workstream "
                + "(tenant_id, scope_id, number, token, description) VALUES (?, ?, 4, 'main', 'd') "
                + "RETURNING id", TENANT, scope);
            c.commit();
        }
    }

    // ------------------------------------------------------------------
    // INSERT through one side fills the other.
    // ------------------------------------------------------------------

    @Test
    void an_insert_through_the_uuid_alone_fills_the_number_and_the_surrogate() throws SQLException {
        try (Connection c = service()) {
            UUID item = item(c, 7);
            c.commit();

            assertThat(longOf(c, "SELECT workstream_number FROM worklist.item WHERE id = ?", item))
                .as("the item was written with workstream_id only; V18's sync trigger must "
                    + "resolve the workstream's number, 4, into workstream_number")
                .isEqualTo(4L);
            assertThat(longOf(c, "SELECT status_pk FROM worklist.item WHERE id = ?", item))
                .as("status_pk must name the surrogate of the status the uuid names")
                .isEqualTo(longOf(c, "SELECT pk FROM worklist.item_status WHERE id = ?", statusOpen));

            execute(c, "INSERT INTO worklist.claim (tenant_id, scope_id, item_id, receipt, actor, "
                + "expires_at) VALUES (?, ?, ?, 'r', 'a', now() + interval '1 hour')", TENANT, scope, item);
            c.commit();
            assertThat(longOf(c, "SELECT item_number FROM worklist.claim WHERE item_id = ?", item))
                .as("a claim written through item_id alone must carry the item's number, 7")
                .isEqualTo(7L);
        }
    }

    @Test
    void an_insert_through_the_number_alone_fills_the_uuid() throws SQLException {
        try (Connection c = service()) {
            UUID item = item(c, 3);
            execute(c, "INSERT INTO worklist.claim (tenant_id, scope_id, item_number, receipt, actor, "
                + "expires_at) VALUES (?, ?, 3, 'r', 'a', now() + interval '1 hour')", TENANT, scope);
            c.commit();

            assertThat(uuidOf(c, "SELECT item_id FROM worklist.claim WHERE scope_id = ?", scope))
                .as("a claim written through item_number alone must carry the uuid of item 3")
                .isEqualTo(item);
        }
    }

    @Test
    void a_contradicting_pair_is_refused_rather_than_stored() throws SQLException {
        try (Connection c = service()) {
            UUID seven = item(c, 7);
            item(c, 8);
            c.commit();

            SQLException refused = catchThrowableOfType(SQLException.class, () -> execute(c,
                "INSERT INTO worklist.claim (tenant_id, scope_id, item_id, item_number, receipt, "
                    + "actor, expires_at) VALUES (?, ?, ?, 8, 'r', 'a', now() + interval '1 hour')",
                TENANT, scope, seven));
            c.rollback();
            assertThat((Throwable) refused)
                .as("item_id names item 7 while item_number names 8; the trigger must refuse "
                    + "the pair instead of letting either side win")
                .isNotNull();
            assertThat(refused.getMessage()).contains("contradicting reference");
        }
    }

    // ------------------------------------------------------------------
    // UPDATE: the side that moved wins.
    // ------------------------------------------------------------------

    @Test
    void an_update_through_either_side_carries_the_other_along() throws SQLException {
        try (Connection c = service()) {
            UUID item = item(c, 5);
            UUID done = status(c, scope, "done");
            long donePk = longOf(c, "SELECT pk FROM worklist.item_status WHERE id = ?", done);
            c.commit();

            execute(c, "UPDATE worklist.item SET status_id = ? WHERE id = ?", done, item);
            c.commit();
            assertThat(longOf(c, "SELECT status_pk FROM worklist.item WHERE id = ?", item))
                .as("the uuid side moved, so status_pk must follow it to the new status")
                .isEqualTo(donePk);

            execute(c, "UPDATE worklist.item SET status_pk = (SELECT pk FROM worklist.item_status "
                + "WHERE id = ?) WHERE id = ?", statusOpen, item);
            c.commit();
            assertThat(uuidOf(c, "SELECT status_id FROM worklist.item WHERE id = ?", item))
                .as("the surrogate side moved, so status_id must follow it back to 'open'")
                .isEqualTo(statusOpen);
        }
    }

    @Test
    void a_number_that_names_no_object_is_refused_not_nulled() throws SQLException {
        try (Connection c = service()) {
            item(c, 1);
            c.commit();
            SQLException refused = catchThrowableOfType(SQLException.class, () -> execute(c,
                "INSERT INTO worklist.claim (tenant_id, scope_id, item_number, receipt, actor, "
                    + "expires_at) VALUES (?, ?, 99, 'r', 'a', now() + interval '1 hour')",
                TENANT, scope));
            c.rollback();
            assertThat((Throwable) refused)
                .as("no item 99 exists in this scope; a NULL uuid would turn a dangling "
                    + "reference into no reference, so the trigger must refuse")
                .isNotNull();
            assertThat(refused.getMessage()).contains("no item numbered 99");
        }
    }

    // ------------------------------------------------------------------
    // Boundaries: another scope, another tenant.
    // ------------------------------------------------------------------

    @Test
    void a_uuid_naming_an_item_of_another_scope_is_refused() throws SQLException {
        UUID otherScope = UUID.randomUUID();
        UUID foreign;
        try (Connection c = service()) {
            insertReturningId(c, "INSERT INTO worklist.selector (tenant_id, scope_id, token) "
                + "VALUES (?, ?, 'item') RETURNING id", TENANT, otherScope);
            UUID otherStatus = status(c, otherScope, "open");
            UUID otherWorkstream = insertReturningId(c, "INSERT INTO worklist.workstream "
                + "(tenant_id, scope_id, number, token, description) VALUES (?, ?, 1, 'main', 'd') "
                + "RETURNING id", TENANT, otherScope);
            foreign = insertReturningId(c, "INSERT INTO worklist.item (tenant_id, scope_id, title, "
                + "selector_id, number, status_id, workstream_id) VALUES (?, ?, 't', "
                + "(SELECT id FROM worklist.selector WHERE scope_id = ? AND token = 'item'), 2, ?, ?) "
                + "RETURNING id", TENANT, otherScope, otherScope, otherStatus, otherWorkstream);
            item(c, 2);
            c.commit();

            SQLException refused = catchThrowableOfType(SQLException.class, () -> execute(c,
                "INSERT INTO worklist.claim (tenant_id, scope_id, item_id, receipt, actor, "
                    + "expires_at) VALUES (?, ?, ?, 'r', 'a', now() + interval '1 hour')",
                TENANT, scope, foreign));
            c.rollback();
            assertThat((Throwable) refused)
                .as("the uuid names item 2 of ANOTHER scope; turned into number 2 it would name "
                    + "a different item in this scope, so the trigger must refuse")
                .isNotNull();
            assertThat(refused.getMessage()).contains("not in scope");
        }
    }

    @Test
    void a_uuid_of_another_tenant_cannot_be_resolved_by_the_writer() throws SQLException {
        UUID foreignItem = itemOfAnotherTenant();
        try (Connection c = service()) {
            SQLException refused = catchThrowableOfType(SQLException.class, () -> execute(c,
                "INSERT INTO worklist.claim (tenant_id, scope_id, item_id, receipt, actor, "
                    + "expires_at) VALUES (?, ?, ?, 'r', 'a', now() + interval '1 hour')",
                TENANT, scope, foreignItem));
            c.rollback();
            assertThat((Throwable) refused)
                .as("the item lives in another tenant; the resolver runs as the writer, under "
                    + "the writer's binding, and must not find it")
                .isNotNull();
            assertThat(refused.getMessage())
                .as("refused by the resolver itself, not only by the foreign key behind it")
                .contains("cannot be resolved");
        }
    }

    // ------------------------------------------------------------------
    // A primary object's address does not move.
    // ------------------------------------------------------------------

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
                "UPDATE worklist.workstream SET number = 5 WHERE id = ?", workstream));
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

    // ------------------------------------------------------------------
    // The backfill of V18 itself.
    // ------------------------------------------------------------------

    @Test
    void the_backfill_fills_a_row_written_without_its_sibling_and_is_idempotent() throws Exception {
        UUID item;
        try (Connection c = service()) {
            item = item(c, 21);
            c.commit();
        }
        try (Connection m = Db.asMigrator()) {
            Db.bindTenant(m, TENANT);
            // The shape before V18: a row whose sibling was never filled. Only
            // the owner can switch the trigger off to plant it.
            execute(m, "ALTER TABLE worklist.claim DISABLE TRIGGER claim_sync_references");
            execute(m, "INSERT INTO worklist.claim (tenant_id, scope_id, item_id, receipt, actor, "
                + "expires_at) VALUES (?, ?, ?, 'r', 'a', now() + interval '1 hour')", TENANT, scope, item);
            execute(m, "ALTER TABLE worklist.claim ENABLE TRIGGER claim_sync_references");
            m.commit();

            runBackfillOfV18(m);
            m.commit();
            assertThat(longOf(m, "SELECT item_number FROM worklist.claim WHERE item_id = ?", item))
                .as("the backfill must fill item_number of a claim planted without it")
                .isEqualTo(21L);

            runBackfillOfV18(m);
            m.commit();
            assertThat(longOf(m, "SELECT item_number FROM worklist.claim WHERE item_id = ?", item))
                .as("a second run of the backfill must leave the filled row as it is")
                .isEqualTo(21L);
        }
    }

    @Test
    void the_backfill_stops_on_a_reference_into_another_scope() throws Exception {
        UUID otherScope = UUID.randomUUID();
        UUID foreignStatus;
        try (Connection c = service()) {
            foreignStatus = status(c, otherScope, "elsewhere");
            c.commit();
        }
        try (Connection m = Db.asMigrator()) {
            Db.bindTenant(m, TENANT);
            UUID otherWorkstream = insertReturningId(m, "INSERT INTO worklist.workstream "
                + "(tenant_id, scope_id, number, token, description) VALUES (?, ?, 1, 'main', 'd') "
                + "RETURNING id", TENANT, otherScope);
            execute(m, "ALTER TABLE worklist.item DISABLE TRIGGER item_sync_references");
            // An item of THIS scope whose workstream is one of ANOTHER scope:
            // the uuid key accepts it, a (tenant, scope, number) key cannot.
            execute(m, "INSERT INTO worklist.item (tenant_id, scope_id, title, selector_id, number, "
                + "status_id, workstream_id) VALUES (?, ?, 't', ?, 31, ?, ?)",
                TENANT, scope, selector, foreignStatus, otherWorkstream);

            Throwable stopped = catchThrowableOfType(SQLException.class, () -> runBackfillOfV18(m));
            m.rollback();
            assertThat(stopped)
                .as("the item's workstream lives in another scope; the backfill must name it and "
                    + "stop rather than leave workstream_number NULL")
                .isNotNull();
            assertThat(stopped.getMessage()).contains("item.workstream=1");
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
        return insertReturningId(c, "INSERT INTO worklist.item (tenant_id, scope_id, title, "
            + "selector_id, number, status_id, workstream_id) VALUES (?, ?, 't', ?, ?, ?, ?) "
            + "RETURNING id", TENANT, scope, selector, number, statusOpen, workstream);
    }

    private static UUID status(Connection c, UUID inScope, String name) throws SQLException {
        return insertReturningId(c, "INSERT INTO worklist.item_status (tenant_id, scope_id, name, "
            + "actionable, in_progress, closed) VALUES (?, ?, ?, true, false, false) RETURNING id",
            TENANT, inScope, name);
    }

    /** An item planted under another tenant, as the administrator. */
    private static UUID itemOfAnotherTenant() throws SQLException {
        UUID otherScope = UUID.randomUUID();
        try (Connection a = Db.asAdmin()) {
            Db.bindTenant(a, OTHER_TENANT);
            UUID sel = insertReturningId(a, "INSERT INTO worklist.selector (tenant_id, scope_id, token) "
                + "VALUES (?, ?, 'item') RETURNING id", OTHER_TENANT, otherScope);
            UUID st = insertReturningId(a, "INSERT INTO worklist.item_status (tenant_id, scope_id, "
                + "name, actionable, in_progress, closed) VALUES (?, ?, 'open', true, false, false) "
                + "RETURNING id", OTHER_TENANT, otherScope);
            UUID ws = insertReturningId(a, "INSERT INTO worklist.workstream (tenant_id, scope_id, "
                + "number, token, description) VALUES (?, ?, 1, 'main', 'd') RETURNING id",
                OTHER_TENANT, otherScope);
            UUID item = insertReturningId(a, "INSERT INTO worklist.item (tenant_id, scope_id, title, "
                + "selector_id, number, status_id, workstream_id) VALUES (?, ?, 't', ?, 1, ?, ?) "
                + "RETURNING id", OTHER_TENANT, otherScope, sel, st, ws);
            a.commit();
            return item;
        }
    }

    /** The two DO-blocks of V18 that follow its "7. Backfill" heading. */
    private static void runBackfillOfV18(Connection m) throws SQLException, IOException {
        String sql;
        try (InputStream in = ReferenceSiblingSyncIT.class.getClassLoader().getResourceAsStream(V18)) {
            assertThat(in).as("V18 must be on the classpath").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String backfill = sql.substring(sql.indexOf("-- 7. Backfill"));
        int first = backfill.indexOf("DO $$");
        int second = backfill.indexOf("DO $$", first + 1);
        assertThat(second).as("V18 carries the backfill and its completeness check").isPositive();
        try (Statement s = m.createStatement()) {
            s.execute(blockAt(backfill, first));
            s.execute(blockAt(backfill, second));
        }
    }

    private static String blockAt(String text, int start) {
        int end = text.indexOf("END $$;", start);
        return text.substring(start, end + "END $$;".length());
    }

    private static UUID insertReturningId(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement st = prepare(c, sql, args); ResultSet rs = st.executeQuery()) {
            rs.next();
            return rs.getObject(1, UUID.class);
        }
    }

    private static void execute(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement st = prepare(c, sql, args)) {
            st.execute();
        }
    }

    private static long longOf(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement st = prepare(c, sql, args); ResultSet rs = st.executeQuery()) {
            assertThat(rs.next()).as("a row for: " + sql).isTrue();
            return rs.getLong(1);
        }
    }

    private static UUID uuidOf(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement st = prepare(c, sql, args); ResultSet rs = st.executeQuery()) {
            assertThat(rs.next()).as("a row for: " + sql).isTrue();
            return rs.getObject(1, UUID.class);
        }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement st = c.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            st.setObject(i + 1, args[i]);
        }
        return st;
    }
}
