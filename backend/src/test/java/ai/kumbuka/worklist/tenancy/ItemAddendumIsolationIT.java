/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.tenancy;

import ai.kumbuka.worklist.domain.AddendumService;
import ai.kumbuka.worklist.domain.Field;
import ai.kumbuka.worklist.domain.ItemLifecycle;
import ai.kumbuka.worklist.domain.ItemService;
import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.domain.VocabularyRegistry;
import ai.kumbuka.worklist.domain.WorklistException;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The addenda of an item (V34) under the real roles: append-only by grant,
 * and isolated by tenant (K6).
 *
 * <h2>Append only</h2>
 *
 * The runtime role holds SELECT and INSERT on {@code worklist.item_addendum}
 * and nothing else, so an UPDATE or a DELETE of an addendum fails at the store
 * for want of the privilege, whatever the service would do.
 *
 * <p>RED PROBE: with a local copy of V34 granting UPDATE to the runtime role,
 * {@link #the_service_role_can_neither_change_nor_remove_an_addendum} turns
 * red at its first assertion -- the UPDATE runs -- and
 * {@code ServiceRolePrivilegeIT} reports {@code item_addendum: holds UPDATE}.
 *
 * <h2>Tenant isolation</h2>
 *
 * A second tenant's item sits on the SAME scope id and the SAME item number as
 * the probe tenant's item and carries addenda of its own. Every way a row
 * could leak would show: the read of the probe tenant's item would carry the
 * second tenant's addenda. The table is asked directly too, under the service
 * role and under its owner: {@code FORCE ROW LEVEL SECURITY} is what binds the
 * owner, the migrator, to the policy at all, and {@code WITH CHECK} is what
 * keeps a session of one tenant from writing for another.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ItemAddendumIsolationIT {

    private static final UUID TENANT = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);
    private static final String OPEN = "add-open";
    private static final String TEXT = "the probe tenant's addendum";

    @Inject ItemService items;
    @Inject AddendumService addenda;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;

    private UUID scope;
    private UUID foreignTenant;

    @BeforeEach
    void aScopeAndASecondTenant() {
        scope = UUID.randomUUID();
        foreignTenant = UUID.randomUUID();
        selectors.open(scope, Selector.ITEM);
        vocabulary.declareStatus(scope, OPEN, 1, true, false, false, false);
    }

    // =======================================================================
    // K6: append-only by grant
    // =======================================================================

    @Test
    void the_service_role_can_neither_change_nor_remove_an_addendum() throws SQLException {
        long number = numberOf(itemWithAnAddendum());

        try (Connection c = Db.asService()) {
            Db.bindTenant(c, TENANT);
            assertThatThrownBy(() -> Db.exec(c, "UPDATE worklist.item_addendum SET text = "
                    + "'rewritten' WHERE scope_id = '" + scope + "' AND item_number = " + number))
                .as("UPDATE on the addenda is refused for want of the privilege")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
            c.rollback();
        }
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, TENANT);
            assertThatThrownBy(() -> Db.exec(c, "DELETE FROM worklist.item_addendum "
                    + "WHERE scope_id = '" + scope + "' AND item_number = " + number))
                .as("and DELETE as well")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
            c.rollback();
        }

        assertThat(textsOf(number))
            .as("the addendum stands as it was written")
            .containsExactly(TEXT);
    }

    // =======================================================================
    // K6: tenant isolation
    // =======================================================================

    @Test
    void a_second_tenants_addenda_show_nowhere_in_the_read() throws SQLException {
        UUID item = itemWithAnAddendum();
        plantForeignAddenda(numberOf(item));

        assertThat(textsIn(items.read(scope, item)))
            .as("the second tenant's item has the same scope id and number; the read of this "
                + "tenant's item carries only its own addendum")
            .containsExactly(TEXT);
    }

    /**
     * The owner of the table is bound by the policy only under FORCE. RED
     * PROBE: V34 without its FORCE line, and this case reads the second
     * tenant's rows.
     */
    @Test
    void the_owner_bound_to_one_tenant_reads_no_addendum_of_another() throws SQLException {
        long number = numberOf(itemWithAnAddendum());
        plantForeignAddenda(number);

        try (Connection owner = Db.asMigrator()) {
            Db.bindTenant(owner, TENANT);
            assertThat(tenantsOfAddenda(owner, number))
                .as("the migrator owns worklist.item_addendum; bound to the probe tenant it "
                    + "sees that tenant's addendum and none of the second tenant's")
                .containsExactly(TENANT);
            owner.rollback();
        }
        try (Connection service = Db.asService()) {
            Db.bindTenant(service, TENANT);
            assertThat(tenantsOfAddenda(service, number))
                .as("and so does the service role")
                .containsExactly(TENANT);
            Db.bindTenant(service, null);
            assertThat(tenantsOfAddenda(service, number))
                .as("and with no tenant bound it sees no row: the policy fails closed")
                .isEmpty();
            service.rollback();
        }
    }

    /** WITH CHECK: an addendum cannot be written under another tenant. */
    @Test
    void a_session_bound_to_one_tenant_cannot_write_for_another() throws SQLException {
        long number = numberOf(itemWithAnAddendum());
        plantForeignAddenda(number);
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, TENANT);
            assertThatThrownBy(() -> insertAddendum(c, foreignTenant, number, "planted"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("row-level security");
            c.rollback();
        }
    }

    /**
     * The second tenant's item is invisible to the service bound to this one,
     * so an addendum on it is refused as an item it does not know.
     */
    @Test
    void an_addendum_through_the_service_reaches_no_item_of_another_tenant() throws SQLException {
        UUID foreignItem = plantForeignAddenda(numberOf(itemWithAnAddendum()));

        Throwable thrown = catchThrowable(() ->
            addenda.addendum(scope, foreignItem, "reaching over", "probe-subject"));
        assertThat(thrown).isInstanceOf(WorklistException.class);
        assertThat(((WorklistException) thrown).reason())
            .isEqualTo(WorklistException.Reason.ITEM_UNKNOWN);
    }

    // =======================================================================
    // Fixtures
    // =======================================================================

    /** The probe tenant's item, submitted -- a draft takes no addendum -- with one addendum. */
    private UUID itemWithAnAddendum() {
        UUID item = ItemLifecycle.open(items, scope, Map.of(
            Field.TITLE.canonicalName(), "the probe tenant's item",
            Field.STATUS.canonicalName(), OPEN));
        addenda.addendum(scope, item, TEXT, "probe-subject");
        return item;
    }

    private long numberOf(UUID item) {
        return ((Number) items.read(scope, item).get(Field.NUMBER.canonicalName())).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> textsIn(Map<String, Object> answer) {
        return ((List<Map<String, Object>>) answer.get("addenda")).stream()
            .map(entry -> entry.get("text")).toList();
    }

    /**
     * The second tenant's item at the same scope id and number, with two
     * addenda of its own, written under the second tenant and committed.
     *
     * @return the second tenant's item's id
     */
    private UUID plantForeignAddenda(long number) throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, foreignTenant);
            long status = Db.declaredStatus(c, foreignTenant);
            long workstream = Db.workstreamNumber(c,
                Db.ensureDefaultWorkstream(c, foreignTenant, scope));
            UUID id;
            try (var st = c.prepareStatement("""
                    INSERT INTO worklist.item
                        (tenant_id, scope_id, title, status_pk, number, workstream_number)
                    VALUES (?, ?, 'the second tenant''s item', ?, ?, ?)
                    RETURNING id
                    """)) {
                st.setObject(1, foreignTenant);
                st.setObject(2, scope);
                st.setLong(3, status);
                st.setLong(4, number);
                st.setLong(5, workstream);
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    id = (UUID) rs.getObject(1);
                }
            }
            insertAddendum(c, foreignTenant, number, "the second tenant's first");
            insertAddendum(c, foreignTenant, number, "the second tenant's second");
            c.commit();
            return id;
        }
    }

    private void insertAddendum(Connection c, UUID tenant, long itemNumber, String text)
            throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.item_addendum
                    (tenant_id, scope_id, item_number, text, author, written_at)
                VALUES (?, ?, ?, ?, 'foreign', now())
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, scope);
            st.setLong(3, itemNumber);
            st.setString(4, text);
            st.executeUpdate();
        }
    }

    /** The texts of the probe tenant's addenda on one item number. */
    private List<String> textsOf(long number) throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, TENANT);
            try (var st = c.prepareStatement("""
                    SELECT text FROM worklist.item_addendum
                    WHERE scope_id = ? AND item_number = ? ORDER BY written_at, pk
                    """)) {
                st.setObject(1, scope);
                st.setLong(2, number);
                try (ResultSet rs = st.executeQuery()) {
                    List<String> texts = new ArrayList<>();
                    while (rs.next()) {
                        texts.add(rs.getString(1));
                    }
                    c.rollback();
                    return texts;
                }
            }
        }
    }

    /** The tenants of the addenda on one item number, as the session sees them. */
    private List<UUID> tenantsOfAddenda(Connection c, long number) throws SQLException {
        try (var st = c.prepareStatement("""
                SELECT DISTINCT tenant_id FROM worklist.item_addendum
                WHERE scope_id = ? AND item_number = ? ORDER BY tenant_id
                """)) {
            st.setObject(1, scope);
            st.setLong(2, number);
            try (ResultSet rs = st.executeQuery()) {
                List<UUID> tenants = new ArrayList<>();
                while (rs.next()) {
                    tenants.add((UUID) rs.getObject(1));
                }
                return tenants;
            }
        }
    }
}
