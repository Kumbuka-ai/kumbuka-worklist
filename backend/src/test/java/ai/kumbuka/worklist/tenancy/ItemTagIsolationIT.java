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
import ai.kumbuka.worklist.domain.Page;
import ai.kumbuka.worklist.domain.QuerySpec;
import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.domain.TagService;
import ai.kumbuka.worklist.domain.VocabularyRegistry;
import ai.kumbuka.worklist.domain.WorklistException;
import ai.kumbuka.worklist.platform.PlatformFixture;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The tenant test of the tags (sprint/210, acceptance c and f).
 *
 * <p>A second tenant's assignment sits on the SAME scope id and the SAME item
 * number as the probe tenant's item, carrying a tag of the probe tenant's own
 * vocabulary. So every way the row could leak would show: the read of the
 * probe tenant's item would carry the tag, and the filter would answer the
 * probe tenant's item for it.
 *
 * <h2>Both layers, and the owner</h2>
 *
 * The service reads through Hibernate, whose tenant discriminator already
 * narrows every statement it builds; the database policy is the second layer
 * and the only one raw SQL meets. So the read and the filter are asked of the
 * service, and the table is asked directly, under the service role and under
 * its owner: {@code FORCE ROW LEVEL SECURITY} is what binds the owner, the
 * migrator, to the policy at all.
 *
 * <h2>The red probe</h2>
 *
 * Without {@code FORCE ROW LEVEL SECURITY} in V26 the owner bound to the probe
 * tenant reads the second tenant's row, and {@link
 * #the_owner_bound_to_one_tenant_reads_no_assignment_of_another} turns red.
 * Without the policy, row-level security stays on with nothing admitting a
 * row, so no session writes or reads an assignment at all, and the cases that
 * attach and read the probe tenant's own tag turn red.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ItemTagIsolationIT {

    private static final UUID TENANT = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);

    /** The probe tenant's scope the items live in. */
    private static final String SCOPE = "tag-isolation";
    private static final UUID SCOPE_ID = UUID.fromString("00000000-0000-0000-0000-0000000003a1");

    /** Another visible scope of the probe tenant, with a vocabulary of its own. */
    private static final String ELSEWHERE = "tag-elsewhere";
    private static final UUID ELSEWHERE_ID =
        UUID.fromString("00000000-0000-0000-0000-0000000003a2");

    /** A private scope of the probe tenant that another subject authored: invisible. */
    private static final String UNSEEN = "tag-unseen";
    private static final UUID UNSEEN_ID = UUID.fromString("00000000-0000-0000-0000-0000000003a3");

    /** The second tenant, and a scope of its own. */
    private static final UUID FOREIGN_TENANT =
        UUID.fromString("00000000-0000-0000-0000-0000000003ff");
    private static final UUID FOREIGN_SCOPE_ID =
        UUID.fromString("00000000-0000-0000-0000-0000000003b1");

    private static final String SHARED = "shared";
    private static final String OPEN = "iso-open";

    @Inject ItemService items;
    @Inject TagService tags;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;

    @BeforeAll
    static void stagePlatform() {
        PlatformFixture.grantDirectoryAccess();
        PlatformFixture.run(
            "SELECT set_config('app.tenant_id', '" + TENANT + "', false)",
            scope(SCOPE_ID, TENANT, SCOPE, "project", null),
            scope(ELSEWHERE_ID, TENANT, ELSEWHERE, "project", null),
            scope(UNSEEN_ID, TENANT, UNSEEN, "private", "'someone-else'"),
            tag(TENANT, SCOPE_ID, SHARED),
            tag(TENANT, ELSEWHERE_ID, "elsewhere-only"),
            tag(TENANT, UNSEEN_ID, "unseen-only"),
            "SELECT set_config('app.tenant_id', '" + FOREIGN_TENANT + "', false)",
            scope(FOREIGN_SCOPE_ID, FOREIGN_TENANT, "tag-foreign", "project", null),
            tag(FOREIGN_TENANT, FOREIGN_SCOPE_ID, "foreign-only"),
            "RESET app.tenant_id");
    }

    @BeforeEach
    void openTheScope() {
        selectors.open(SCOPE_ID, Selector.ITEM);
        if (vocabulary.statusByName(SCOPE_ID, OPEN).isEmpty()) {
            vocabulary.declareStatus(SCOPE_ID, OPEN, 1, true, false, false, false);
        }
    }

    // =======================================================================
    // The second tenant's assignment is invisible to the read and the filter
    // =======================================================================

    @Test
    void a_second_tenants_assignment_shows_neither_in_the_read_nor_through_the_filter()
            throws SQLException {
        UUID item = createItem("the probe tenant's item");
        long number = numberOf(item);
        plantForeignAssignment(number);

        assertThat(tagsOf(items.read(SCOPE_ID, item)))
            .as("the second tenant tagged an item of the same scope id and number; the "
                + "read of this tenant's item carries none of it")
            .isEmpty();
        assertThat(numbersUnderFilter(SHARED))
            .as("and the filter does not answer this tenant's item for the other "
                + "tenant's assignment")
            .doesNotContain(number);

        // The counter-probe: this tenant's own assignment is what both answer.
        tags.tagAttach(SCOPE_ID, item, SHARED, "probe-subject");
        assertThat(tagsOf(items.read(SCOPE_ID, item))).containsExactly(SHARED);
        assertThat(numbersUnderFilter(SHARED)).contains(number);
    }

    /**
     * The owner of the table is bound by the policy only under FORCE. RED
     * PROBE: V26 without its FORCE line, and this case reads the second
     * tenant's row.
     */
    @Test
    void the_owner_bound_to_one_tenant_reads_no_assignment_of_another() throws SQLException {
        UUID item = createItem("owned rows");
        long number = numberOf(item);
        plantForeignAssignment(number);
        tags.tagAttach(SCOPE_ID, item, SHARED, "probe-subject");

        try (Connection owner = Db.asMigrator()) {
            Db.bindTenant(owner, TENANT);
            assertThat(tenantsOfAssignments(owner, number))
                .as("the migrator owns worklist.item_tag; bound to the probe tenant it sees "
                    + "that tenant's assignment and not the second tenant's")
                .containsExactly(TENANT);
            owner.rollback();
        }
        try (Connection service = Db.asService()) {
            Db.bindTenant(service, TENANT);
            assertThat(tenantsOfAssignments(service, number))
                .as("and so does the service role")
                .containsExactly(TENANT);
            service.rollback();
        }
    }

    /** WITH CHECK: an assignment cannot be planted under another tenant. */
    @Test
    void a_session_bound_to_one_tenant_cannot_plant_an_assignment_for_another()
            throws SQLException {
        UUID item = createItem("planted across");
        long number = numberOf(item);
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, TENANT);
            assertThatThrownBy(() -> insertAssignment(c, FOREIGN_TENANT, number, UUID.randomUUID()))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("row-level security");
            c.rollback();
        }
    }

    // =======================================================================
    // A token of a foreign or invisible scope does not attach
    // =======================================================================

    @Test
    void a_token_of_another_tenants_scope_does_not_attach() {
        UUID item = createItem("foreign token");
        assertThat(refusalOf(() -> tags.tagAttach(SCOPE_ID, item, "foreign-only", "probe-subject")))
            .isEqualTo(WorklistException.Reason.VALUE_UNDECLARED);
        assertThat(tagsOf(items.read(SCOPE_ID, item))).isEmpty();
    }

    @Test
    void a_token_of_another_scope_of_the_same_tenant_does_not_attach() {
        UUID item = createItem("token of a neighbouring scope");
        assertThat(refusalOf(() ->
                tags.tagAttach(SCOPE_ID, item, "elsewhere-only", "probe-subject")))
            .as("the token is resolved in the item's own scope")
            .isEqualTo(WorklistException.Reason.VALUE_UNDECLARED);
    }

    @Test
    void a_token_of_an_invisible_scope_does_not_attach() {
        UUID item = createItem("token of an invisible scope");
        assertThat(refusalOf(() -> tags.tagAttach(SCOPE_ID, item, "unseen-only", "probe-subject")))
            .isEqualTo(WorklistException.Reason.VALUE_UNDECLARED);
    }

    // =======================================================================
    // Fixtures
    // =======================================================================

    private UUID createItem(String title) {
        Map<String, Object> created = items.create(SCOPE_ID, Map.of(
            Field.TITLE.canonicalName(), title,
            Field.STATUS.canonicalName(), OPEN));
        return UUID.fromString(String.valueOf(created.get(Field.ID.canonicalName())));
    }

    private long numberOf(UUID item) {
        return ((Number) items.read(SCOPE_ID, item).get(Field.NUMBER.canonicalName())).longValue();
    }

    @SuppressWarnings("unchecked")
    private static List<String> tagsOf(Map<String, Object> read) {
        return (List<String>) read.get(Field.TAGS.canonicalName());
    }

    private List<Long> numbersUnderFilter(String token) {
        Page page = items.query(SCOPE_ID, new QuerySpec(Map.<String, Object>of("tag", token), 200)
            .pageFor(SCOPE_ID, Selector.ITEM));
        return page.objects().stream()
            .map(object -> ((Number) object.get(Field.NUMBER.canonicalName())).longValue())
            .toList();
    }

    /**
     * The second tenant's item at the same scope id and number, carrying the
     * probe tenant's tag, written under the second tenant and committed.
     */
    private static void plantForeignAssignment(long number) throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, FOREIGN_TENANT);
            long status = Db.declaredStatus(c, FOREIGN_TENANT);
            long workstream = Db.workstreamNumber(c,
                Db.ensureDefaultWorkstream(c, FOREIGN_TENANT, SCOPE_ID));
            try (var st = c.prepareStatement("""
                    INSERT INTO worklist.item
                        (tenant_id, scope_id, title, status_pk, number, workstream_number)
                    SELECT ?, ?, 'the second tenant''s item', ?, ?, ?
                    WHERE NOT EXISTS (SELECT 1 FROM worklist.item
                                      WHERE tenant_id = ? AND scope_id = ? AND number = ?)
                    """)) {
                st.setObject(1, FOREIGN_TENANT);
                st.setObject(2, SCOPE_ID);
                st.setLong(3, status);
                st.setLong(4, number);
                st.setLong(5, workstream);
                st.setObject(6, FOREIGN_TENANT);
                st.setObject(7, SCOPE_ID);
                st.setLong(8, number);
                st.executeUpdate();
            }
            insertAssignment(c, FOREIGN_TENANT, number, sharedTagId());
            c.commit();
        }
    }

    private static void insertAssignment(Connection c, UUID tenant, long number, UUID tag)
            throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.item_tag (tenant_id, scope_id, item_number, tag_id, attached_by)
                VALUES (?, ?, ?, ?, 'foreign-subject')
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, SCOPE_ID);
            st.setLong(3, number);
            st.setObject(4, tag);
            st.executeUpdate();
        }
    }

    /** The probe tenant's tag {@link #SHARED}, read as the platform holds it. */
    private static UUID sharedTagId() throws SQLException {
        try (Connection admin = Db.asAdmin();
             var st = admin.prepareStatement(
                 "SELECT id FROM platform.tag WHERE scope_id = ? AND token = ?")) {
            st.setObject(1, SCOPE_ID);
            st.setString(2, SHARED);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    /** The tenants of the assignments on one item number, as the session sees them. */
    private static List<UUID> tenantsOfAssignments(Connection c, long number)
            throws SQLException {
        try (var st = c.prepareStatement("""
                SELECT tenant_id FROM worklist.item_tag
                WHERE scope_id = ? AND item_number = ? ORDER BY tenant_id
                """)) {
            st.setObject(1, SCOPE_ID);
            st.setLong(2, number);
            try (ResultSet rs = st.executeQuery()) {
                List<UUID> tenants = new java.util.ArrayList<>();
                while (rs.next()) {
                    tenants.add((UUID) rs.getObject(1));
                }
                return tenants;
            }
        }
    }

    private static WorklistException.Reason refusalOf(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(WorklistException.class);
        return ((WorklistException) thrown).reason();
    }

    private static String scope(UUID id, UUID tenant, String slug, String kind,
                                String createdBy) {
        return "INSERT INTO public.scope (id, tenant_id, slug, kind, created_by) SELECT '" + id
            + "', '" + tenant + "', '" + slug + "', '" + kind + "', " + createdBy
            + " WHERE NOT EXISTS (SELECT 1 FROM public.scope WHERE id = '" + id + "')";
    }

    private static String tag(UUID tenant, UUID scope, String token) {
        return "INSERT INTO platform.tag (tenant_id, scope_id, token, created_by) SELECT '"
            + tenant + "', '" + scope + "', '" + token + "', 'probe-admin' WHERE NOT EXISTS "
            + "(SELECT 1 FROM platform.tag WHERE scope_id = '" + scope + "' AND token = '"
            + token + "')";
    }
}
