/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.tenancy;

import ai.kumbuka.worklist.domain.Field;
import ai.kumbuka.worklist.domain.ItemService;
import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.domain.VocabularyRegistry;
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

/**
 * The tenant test of the acceptance criteria (V29).
 *
 * <p>A second tenant's item sits on the SAME scope id and the SAME item
 * number as the probe tenant's item and carries criteria of its own under the
 * numbers 1 and 2. So every way a row could leak would show: the read of the
 * probe tenant's item would carry the second tenant's text, or the next
 * identifier the probe tenant's item gets would skip past the second tenant's
 * numbers.
 *
 * <h2>Both layers, and the owner</h2>
 *
 * The service reads through Hibernate, whose tenant discriminator narrows
 * every statement it builds; the database policy is the second layer and the
 * only one raw SQL meets. So the read is asked of the service, and the table is
 * asked directly, under the service role and under its owner:
 * {@code FORCE ROW LEVEL SECURITY} is what binds the owner, the migrator, to
 * the policy at all.
 *
 * <h2>The red probe</h2>
 *
 * Without {@code FORCE ROW LEVEL SECURITY} in V29 the owner bound to the probe
 * tenant reads the second tenant's rows, and
 * {@link #the_owner_bound_to_one_tenant_reads_no_criterion_of_another} turns
 * red. Without the policy's {@code WITH CHECK}, the planted insert of
 * {@link #a_session_bound_to_one_tenant_cannot_plant_a_criterion_for_another}
 * succeeds and that case turns red.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ItemCriterionIsolationIT {

    private static final UUID TENANT = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);
    private static final String OPEN = "iso-open";

    @Inject ItemService items;
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

    @Test
    void a_second_tenants_criteria_show_neither_in_the_read_nor_in_the_allocation()
            throws SQLException {
        UUID item = createItem(List.of(Map.of("text", "the probe tenant's own")));
        long number = numberOf(item);
        plantForeignCriteria(number);

        Map<String, Object> read = items.read(scope, item);
        assertThat(texts(read))
            .as("the second tenant's item has the same scope id and number; the read of "
                + "this tenant's item carries only its own criterion")
            .containsExactly("the probe tenant's own");

        Map<String, Object> appended = items.update(scope, item, Map.of(
            Field.CONFLICT_TOKEN.canonicalName(), read.get(Field.CONFLICT_TOKEN.canonicalName()),
            Field.CRITERIA.canonicalName(), List.of(
                Map.of("address", addressOf(read, 1), "text", "the probe tenant's own"),
                Map.of("text", "appended here"))), "probe-subject").answer();
        assertThat(addresses(appended))
            .as("the next identifier is this item's 2: the second tenant's 1, 2 and 3 are "
                + "neither seen nor counted")
            .containsExactly(addressOf(read, 1), addressOf(read, 2));
    }

    /**
     * The owner of the table is bound by the policy only under FORCE. RED
     * PROBE: V29 without its FORCE line, and this case reads the second
     * tenant's rows.
     */
    @Test
    void the_owner_bound_to_one_tenant_reads_no_criterion_of_another() throws SQLException {
        UUID item = createItem(List.of(Map.of("text", "owned rows")));
        long number = numberOf(item);
        plantForeignCriteria(number);

        try (Connection owner = Db.asMigrator()) {
            Db.bindTenant(owner, TENANT);
            assertThat(tenantsOfCriteria(owner, number))
                .as("the migrator owns worklist.item_criterion; bound to the probe tenant it "
                    + "sees that tenant's criterion and none of the second tenant's")
                .containsExactly(TENANT);
            owner.rollback();
        }
        try (Connection service = Db.asService()) {
            Db.bindTenant(service, TENANT);
            assertThat(tenantsOfCriteria(service, number))
                .as("and so does the service role")
                .containsExactly(TENANT);
            Db.bindTenant(service, null);
            assertThat(tenantsOfCriteria(service, number))
                .as("and with no tenant bound it sees nothing: the policy fails closed")
                .isEmpty();
            service.rollback();
        }
    }

    /** WITH CHECK: a criterion cannot be planted under another tenant. */
    @Test
    void a_session_bound_to_one_tenant_cannot_plant_a_criterion_for_another()
            throws SQLException {
        UUID item = createItem(List.of());
        long number = numberOf(item);
        plantForeignCriteria(number);
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, TENANT);
            assertThatThrownBy(() -> insertCriterion(c, foreignTenant, number, 9, "planted"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("row-level security");
            c.rollback();
        }
    }

    // =======================================================================
    // Fixtures
    // =======================================================================

    private UUID createItem(List<Map<String, Object>> criteria) {
        Map<String, Object> fields = criteria.isEmpty()
            ? Map.of(Field.TITLE.canonicalName(), "the probe tenant's item",
                Field.STATUS.canonicalName(), OPEN)
            : Map.of(Field.TITLE.canonicalName(), "the probe tenant's item",
                Field.STATUS.canonicalName(), OPEN,
                Field.CRITERIA.canonicalName(), criteria);
        Map<String, Object> created = items.create(scope, "probe-subject", fields).answer();
        return UUID.fromString(String.valueOf(created.get(Field.ID.canonicalName())));
    }

    private long numberOf(UUID item) {
        return ((Number) items.read(scope, item).get(Field.NUMBER.canonicalName())).longValue();
    }

    private static String addressOf(Map<String, Object> read, long k) {
        return "worklist://" + read.get(Field.SCOPE.canonicalName()) + "/item/"
            + read.get(Field.NUMBER.canonicalName()) + "/criterion/" + k;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> criteria(Map<String, Object> answer) {
        return (List<Map<String, Object>>) answer.get(Field.CRITERIA.canonicalName());
    }

    private static List<Object> texts(Map<String, Object> answer) {
        return criteria(answer).stream().map(entry -> entry.get("text")).toList();
    }

    private static List<Object> addresses(Map<String, Object> answer) {
        return criteria(answer).stream().map(entry -> entry.get("address")).toList();
    }

    /**
     * The second tenant's item at the same scope id and number, with criteria
     * 1, 2 and 3 of its own, written under the second tenant and committed.
     */
    private void plantForeignCriteria(long number) throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, foreignTenant);
            long status = Db.declaredStatus(c, foreignTenant);
            long workstream = Db.workstreamNumber(c,
                Db.ensureDefaultWorkstream(c, foreignTenant, scope));
            try (var st = c.prepareStatement("""
                    INSERT INTO worklist.item
                        (tenant_id, scope_id, title, status_pk, number, workstream_number)
                    VALUES (?, ?, 'the second tenant''s item', ?, ?, ?)
                    """)) {
                st.setObject(1, foreignTenant);
                st.setObject(2, scope);
                st.setLong(3, status);
                st.setLong(4, number);
                st.setLong(5, workstream);
                st.executeUpdate();
            }
            for (int k = 1; k <= 3; k++) {
                insertCriterion(c, foreignTenant, number, k, "the second tenant's " + k);
            }
            c.commit();
        }
    }

    private void insertCriterion(Connection c, UUID tenant, long itemNumber, long k,
                                 String text) throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.item_criterion
                    (tenant_id, scope_id, item_number, number, text, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, now(), now())
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, scope);
            st.setLong(3, itemNumber);
            st.setLong(4, k);
            st.setString(5, text);
            st.executeUpdate();
        }
    }

    /** The tenants of the criteria on one item number, as the session sees them. */
    private List<UUID> tenantsOfCriteria(Connection c, long number) throws SQLException {
        try (var st = c.prepareStatement("""
                SELECT DISTINCT tenant_id FROM worklist.item_criterion
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
