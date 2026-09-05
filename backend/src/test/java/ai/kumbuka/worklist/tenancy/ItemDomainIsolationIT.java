package ai.kumbuka.worklist.tenancy;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Probe A — a foreign tenant's row is neither readable nor writable, on every
 * relation the item domain added.
 *
 * <h2>Why the write case is asked separately</h2>
 *
 * A read that returns nothing and a write that is refused are two different
 * mechanisms: {@code USING} filters what a statement may see, {@code WITH
 * CHECK} constrains what it may write. A probe that establishes the read half
 * and infers the write half has tested one clause and asserted two, and the
 * clause it did not test is the one whose absence lets a session plant a row
 * under a foreign tenant — invisible to the planter and to the tenant that
 * now owns it.
 *
 * <h2>The blind spot this probe is built to avoid</h2>
 *
 * The obvious way to test the write half is
 * {@code INSERT … RETURNING id} under tenant A with {@code tenant_id = B}.
 * It fails, and it proves nothing: the row RETURNING reads back is subject to
 * {@code USING} as well, so the statement is refused twice over and both
 * messages name row-level security. Drop {@code WITH CHECK} entirely and the
 * probe stays green.
 *
 * <p>So no statement in this class uses {@code RETURNING}. Ids are generated
 * here and sent, never read back, which means a refusal has exactly one
 * possible source.
 *
 * <h2>The second blind spot, which is about the fixture rather than the SQL</h2>
 *
 * Three of the four relations cannot hold a row on their own: a number space
 * needs its selector, a dependency needs both its items. A foreign-write case
 * that supplied those preconditions inside the same failing statement would
 * be refused on the FIRST of them — so it would report success while testing
 * a table it never reached. Each precondition is therefore established under
 * the foreign tenant and COMMITTED first, and only the row of the relation
 * under test is attempted across the boundary.
 *
 * <h2>And the case that is not an error at all</h2>
 *
 * An {@code UPDATE} aimed at a foreign row does not fail. {@code USING}
 * removes the row from the statement's view, so the update matches nothing
 * and reports success with zero rows affected. That is the quietest way a
 * cross-tenant write can go wrong — a caller that checks only for an
 * exception concludes it worked — and it is asserted by row count rather than
 * by the absence of a throw.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ItemDomainIsolationIT {

    /**
     * Every relation the item domain added.
     *
     * <p>Written out rather than derived from the catalog, deliberately: a
     * catalog-derived list tests whatever happens to exist, and the point
     * here is that a specific set of relations was added and each was
     * checked. A relation added LATER and forgotten here is caught by
     * {@link TenancyCompletenessIT}, which reads the catalog for exactly that
     * reason. The two probes answer different questions and neither replaces
     * the other.
     */
    private static final List<String> DOMAIN_TABLES = List.of(
        // The declared vocabularies.
        "item_status", "attribute_definition", "attribute_option", "relation_type",
        // Identity.
        "selector", "number_space",
        // The item's satellites.
        "item_reference", "item_relation",
        // The planning layer, whose tables exist here and whose verbs do not.
        "milestone", "iteration", "iteration_membership",
        // The lease, and what the scope decides.
        "claim", "scope_setting", "view_preference");

    private static final UUID SCOPE = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void freshTenants() {
        // Fresh per method: the suite shares one database and counts rows, so
        // a fixed pair would make each assertion depend on which test ran
        // before it — the suite would pass or fail by execution order and the
        // failure would look like a broken policy.
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
    }

    // ------------------------------------------------------------------
    // The read half.
    // ------------------------------------------------------------------

    @Test
    void a_bound_session_sees_only_its_own_rows_on_every_new_relation()
            throws SQLException {
        try (Connection c = Db.asService()) {
            plantOneOfEach(c, tenantA);
            plantOneOfEach(c, tenantB);
            c.commit();

            for (String table : DOMAIN_TABLES) {
                Db.bindTenant(c, tenantA);
                assertThat(count(c, table))
                    .as("bound to tenant A, worklist.%s must show A's row and only A's", table)
                    .isEqualTo(1);

                Db.bindTenant(c, tenantB);
                assertThat(count(c, table))
                    .as("and symmetrically for B in worklist.%s — otherwise the filter is "
                        + "a coincidence about which rows happen to exist", table)
                    .isEqualTo(1);
            }
            c.commit();
        }
    }

    /**
     * The unbound case, which is what a forgotten binding produces.
     *
     * <p>The predicate compares against {@code current_setting(…, true)},
     * which is NULL when nothing is bound, and {@code tenant_id = NULL} is
     * NULL rather than FALSE — which a policy treats as failing. So an
     * unbound session sees NOTHING rather than everything. That is the design
     * and not a side effect: it makes a forgotten binding a visible emptiness
     * instead of a silent leak.
     */
    @Test
    void an_unbound_session_sees_nothing_on_every_new_relation() throws SQLException {
        try (Connection c = Db.asService()) {
            plantOneOfEach(c, tenantA);
            c.commit();

            Db.bindTenant(c, null);
            for (String table : DOMAIN_TABLES) {
                assertThat(count(c, table))
                    .as("with no tenant bound, worklist.%s must return nothing. Row-level "
                        + "security fails CLOSED here, which is what turns a forgotten "
                        + "binding into an empty answer instead of every tenant's rows",
                        table)
                    .isZero();
            }
            c.commit();
        }
    }

    // ------------------------------------------------------------------
    // The write half, asked separately and without RETURNING.
    // ------------------------------------------------------------------

    /**
     * {@code WITH CHECK} on {@code worklist.selector}, on its own.
     *
     * <p>A selector has no precondition, so this case is the clean one: one
     * insert, one possible source of refusal.
     */
    @Test
    void a_bound_session_cannot_plant_a_selector_under_a_foreign_tenant()
            throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            assertThatThrownBy(() -> insertSelector(c, tenantB, UUID.randomUUID()))
                .as("bound to tenant A, an insert naming tenant B must be refused. "
                    + "Without WITH CHECK it would succeed and then vanish from the "
                    + "planter's own view — data across the boundary, invisible to "
                    + "everyone including its new owner")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("row-level security");
            c.rollback();
        }
    }

    /**
     * {@code WITH CHECK} on {@code worklist.number_space}.
     *
     * <p>Its selector is created under tenant B and committed FIRST, so the
     * statement under test is the number-space insert and nothing else. The
     * foreign key resolves — a foreign key check is made by the system and is
     * not subject to row-level security — so the only thing that can refuse
     * this row is the policy on the table it is going into.
     */
    @Test
    void a_bound_session_cannot_plant_a_number_space_under_a_foreign_tenant()
            throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantB);
            UUID foreignSelector = UUID.randomUUID();
            insertSelector(c, tenantB, foreignSelector);
            c.commit();

            Db.bindTenant(c, tenantA);
            assertThatThrownBy(() -> insertNumberSpace(c, tenantB, foreignSelector))
                .as("the selector exists and belongs to tenant B; the number space for it "
                    + "may still not be written from a session bound to tenant A")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("row-level security");
            c.rollback();
        }
    }

    /**
     * {@code WITH CHECK} on {@code worklist.item_status}, which also has no
     * precondition.
     *
     * <p>A status is the vocabulary case now, and it is the one that matters
     * most: a status carries the four predicates the platform reasons about,
     * so a status planted across the boundary would be a foreign tenant
     * deciding what "closed" means in a scope it cannot see.
     */
    @Test
    void a_bound_session_cannot_plant_a_status_under_a_foreign_tenant() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            assertThatThrownBy(() -> insertStatus(c, tenantB))
                .as("a vocabulary is a scope's own data, and a session bound to another "
                    + "tenant may not add to it")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("row-level security");
            c.rollback();
        }
    }

    /**
     * {@code WITH CHECK} on {@code worklist.item_relation}.
     *
     * <p>Both items and the relation type are created under tenant B and
     * committed first, for the same reason as the number space: the edge is
     * the statement under test, and its preconditions must not be what gets
     * refused.
     */
    @Test
    void a_bound_session_cannot_plant_a_relation_under_a_foreign_tenant()
            throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantB);
            UUID from = insertItem(c, tenantB, "edge-source");
            UUID to = insertItem(c, tenantB, "edge-target");
            UUID type = insertRelationType(c, tenantB);
            c.commit();

            Db.bindTenant(c, tenantA);
            assertThatThrownBy(() -> insertRelation(c, tenantB, from, to, type))
                .as("both items and the type exist and belong to tenant B; the edge "
                    + "between them may still not be written from a session bound to "
                    + "tenant A")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("row-level security");
            c.rollback();
        }
    }

    /**
     * And the runtime role cannot DELETE anything at all, on any of them.
     *
     * <p>A different mechanism from everything above and it is worth its own
     * case: row-level security decides which rows a statement sees, and this
     * decides whether the statement may be issued. "No verb deletes" is held
     * by the GRANTS, so it holds against raw SQL that never passes a policy —
     * and TRUNCATE, which is the one that bypasses every policy there is,
     * falls under the same absence.
     */
    @Test
    void the_runtime_role_may_not_delete_from_any_relation_of_this_domain()
            throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantA);
            plantOneOfEach(c, tenantA);
            c.commit();

            for (String table : DOMAIN_TABLES) {
                assertThatThrownBy(() -> Db.exec(c, "DELETE FROM worklist." + table))
                    .as("RED STATE, observed: a DELETE against worklist.%s must be "
                        + "refused for want of a privilege. Withdrawal is a status "
                        + "everywhere in this schema, and one granted DELETE would cost "
                        + "the whole of that guarantee", table)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
                c.rollback();
            }
        }
    }

    /**
     * The quiet one: an update aimed across the boundary is not an error.
     *
     * <p>{@code USING} takes the foreign row out of the statement's view, so
     * the update matches nothing, affects zero rows and reports success. A
     * probe that watched for an exception here would be green on a schema
     * with no isolation at all, because there is no exception to catch.
     */
    @Test
    void a_bound_session_cannot_change_a_foreign_row_and_is_not_told_so() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenantB);
            UUID foreignSelector = UUID.randomUUID();
            insertSelector(c, tenantB, foreignSelector);
            c.commit();

            Db.bindTenant(c, tenantA);
            int affected;
            try (var st = c.prepareStatement(
                    "UPDATE worklist.selector SET status = 'withdrawn' WHERE id = ?")) {
                st.setObject(1, foreignSelector);
                affected = st.executeUpdate();
            }
            c.commit();

            assertThat(affected)
                .as("an update across the tenant boundary affects nothing AND raises "
                    + "nothing. This is the failure mode a caller cannot see: it checks "
                    + "for an exception, finds none, and concludes the write landed")
                .isZero();

            Db.bindTenant(c, tenantB);
            assertThat(statusOfSelector(c, foreignSelector))
                .as("and the row is untouched, read back under its own tenant — the only "
                    + "binding that can see it at all")
                .isEqualTo("declared");
            c.commit();
        }
    }

    /** And an unbound session cannot write either — fail-closed on both halves. */
    @Test
    void an_unbound_session_cannot_plant_a_selector() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, null);
            assertThatThrownBy(() -> insertSelector(c, tenantA, UUID.randomUUID()))
                .as("with no tenant bound the predicate is NULL rather than false, and a "
                    + "policy treats that as failing — for writes as well as for reads")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("row-level security");
            c.rollback();
        }
    }

    // ------------------------------------------------------------------
    // Planting. No RETURNING anywhere: ids are generated here and sent.
    // ------------------------------------------------------------------

    /**
     * Exactly one row in each relation of {@link #DOMAIN_TABLES}, under one
     * tenant.
     *
     * <p>Exactly one, because the read half counts. The two items the edge
     * needs are not counted — {@code item} comes from V1 and its isolation is
     * asserted by {@code RowLevelSecurityProbeIT}, which is why it is not in
     * the list.
     */
    private void plantOneOfEach(Connection c, UUID tenant) throws SQLException {
        Db.bindTenant(c, tenant);

        UUID selector = UUID.randomUUID();
        insertSelector(c, tenant, selector);
        insertNumberSpace(c, tenant, selector);

        insertStatus(c, tenant);
        UUID definition = insertAttributeDefinition(c, tenant);
        insertAttributeOption(c, tenant, definition);
        UUID type = insertRelationType(c, tenant);

        UUID from = insertItem(c, tenant, "edge-source");
        UUID to = insertItem(c, tenant, "edge-target");
        insertRelation(c, tenant, from, to, type);
        insertReference(c, tenant, from);

        insertMilestone(c, tenant);
        UUID iteration = insertIteration(c, tenant);
        insertMembership(c, tenant, iteration, from);

        insertClaim(c, tenant, from);
        insertScopeSetting(c, tenant, iteration);
        insertViewPreference(c, tenant);
    }

    private void insertSelector(Connection c, UUID tenant, UUID id) throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.selector (id, tenant_id, scope_id, token)
                VALUES (?, ?, ?, ?)
                """)) {
            st.setObject(1, id);
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.setString(4, freshToken());
            st.executeUpdate();
        }
    }

    private void insertNumberSpace(Connection c, UUID tenant, UUID selectorId)
            throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.number_space (id, selector_id, tenant_id, scope_id)
                VALUES (?, ?, ?, ?)
                """)) {
            st.setObject(1, UUID.randomUUID());
            st.setObject(2, selectorId);
            st.setObject(3, tenant);
            st.setObject(4, SCOPE);
            st.executeUpdate();
        }
    }

    private UUID insertStatus(Connection c, UUID tenant) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.item_status
                    (id, tenant_id, scope_id, name, actionable, in_progress, closed,
                     successful)
                VALUES (?, ?, ?, ?, true, false, false, false)
                """)) {
            st.setObject(1, id);
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.setString(4, freshToken());
            st.executeUpdate();
        }
        return id;
    }

    private UUID insertAttributeDefinition(Connection c, UUID tenant) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.attribute_definition
                    (id, tenant_id, scope_id, key, name, type)
                VALUES (?, ?, ?, ?, ?, 'choice')
                """)) {
            st.setObject(1, id);
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.setString(4, freshKey());
            st.setString(5, "an attribute");
            st.executeUpdate();
        }
        return id;
    }

    private void insertAttributeOption(Connection c, UUID tenant, UUID definition)
            throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.attribute_option
                    (id, tenant_id, scope_id, definition_id, name)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            st.setObject(1, UUID.randomUUID());
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.setObject(4, definition);
            st.setString(5, "an option");
            st.executeUpdate();
        }
    }

    private UUID insertRelationType(Connection c, UUID tenant) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.relation_type (id, tenant_id, scope_id, name, blocks)
                VALUES (?, ?, ?, ?, true)
                """)) {
            st.setObject(1, id);
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.setString(4, freshToken());
            st.executeUpdate();
        }
        return id;
    }

    /**
     * An item, with the status its tenant declared.
     *
     * <p>Planted after {@link #insertStatus}, because the reference is
     * mandatory: a status is a declared value and there is no literal to fall
     * back on.
     */
    private UUID insertItem(Connection c, UUID tenant, String title) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.item (id, tenant_id, scope_id, title, status_id)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            st.setObject(1, id);
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.setString(4, title);
            st.setObject(5, anyStatus(c, tenant));
            st.executeUpdate();
        }
        return id;
    }

    private void insertRelation(Connection c, UUID tenant, UUID from, UUID to, UUID type)
            throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.item_relation
                    (tenant_id, scope_id, from_item_id, to_item_id, relation_type_id)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, SCOPE);
            st.setObject(3, from);
            st.setObject(4, to);
            st.setObject(5, type);
            st.executeUpdate();
        }
    }

    private void insertReference(Connection c, UUID tenant, UUID item) throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.item_reference
                    (tenant_id, scope_id, item_id, ordinal, target)
                VALUES (?, ?, ?, 0, 'docs/a.md')
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, SCOPE);
            st.setObject(3, item);
            st.executeUpdate();
        }
    }

    private void insertMilestone(Connection c, UUID tenant) throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.milestone (id, tenant_id, scope_id, number, title)
                VALUES (?, ?, ?, 1, 'a milestone')
                """)) {
            st.setObject(1, UUID.randomUUID());
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.executeUpdate();
        }
    }

    private UUID insertIteration(Connection c, UUID tenant) throws SQLException {
        UUID id = UUID.randomUUID();
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.iteration
                    (id, tenant_id, scope_id, number, motto, description)
                VALUES (?, ?, ?, 1, 'a motto', 'a description')
                """)) {
            st.setObject(1, id);
            st.setObject(2, tenant);
            st.setObject(3, SCOPE);
            st.executeUpdate();
        }
        return id;
    }

    private void insertMembership(Connection c, UUID tenant, UUID iteration, UUID item)
            throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.iteration_membership
                    (tenant_id, scope_id, iteration_id, item_id, position)
                VALUES (?, ?, ?, ?, 0)
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, SCOPE);
            st.setObject(3, iteration);
            st.setObject(4, item);
            st.executeUpdate();
        }
    }

    private void insertClaim(Connection c, UUID tenant, UUID item) throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.claim
                    (tenant_id, scope_id, item_id, receipt, actor, expires_at)
                VALUES (?, ?, ?, 'a receipt', 'an actor', now() + interval '1 hour')
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, SCOPE);
            st.setObject(3, item);
            st.executeUpdate();
        }
    }

    private void insertScopeSetting(Connection c, UUID tenant, UUID iteration)
            throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.scope_setting
                    (tenant_id, scope_id, current_iteration_id, max_planned_iterations,
                     warn_planned_iterations, max_memberships_per_iteration,
                     warn_memberships_per_iteration)
                VALUES (?, ?, ?, 4, 3, 40, 30)
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, SCOPE);
            st.setObject(3, iteration);
            st.executeUpdate();
        }
    }

    private void insertViewPreference(Connection c, UUID tenant) throws SQLException {
        try (var st = c.prepareStatement("""
                INSERT INTO worklist.view_preference (tenant_id, scope_id, actor)
                VALUES (?, ?, 'a reader')
                """)) {
            st.setObject(1, tenant);
            st.setObject(2, SCOPE);
            st.executeUpdate();
        }
    }

    /** The tenant's declared status, or a fresh one when it has none yet. */
    private UUID anyStatus(Connection c, UUID tenant) throws SQLException {
        try (var st = c.prepareStatement(
                "SELECT id FROM worklist.item_status WHERE tenant_id = ? LIMIT 1")) {
            st.setObject(1, tenant);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    return UUID.fromString(rs.getString(1));
                }
            }
        }
        return insertStatus(c, tenant);
    }

    /** An attribute key the check constraint accepts, unique per call. */
    private static String freshKey() {
        return "k" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** A token the check constraints accept, unique per call. */
    /**
     * A selector token that is unique per planted row.
     *
     * <p>Lower case, because the form constraint says so since V6. It is
     * deliberately not one of the three views: what this class plants are rows
     * to prove isolation on, and the constraint checks FORM only — which view
     * tokens a deployment admits is the domain's refusal and not the database's.
     * A token here that happened to be a view would collide with the rows the
     * service's own probes declare.
     */
    private static String freshToken() {
        return "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static long count(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM worklist." + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /**
     * The status of one selector, as the CURRENT session sees it.
     *
     * <p>Returns whatever it found rather than assuming one row, so that a
     * failure says what was actually there instead of raising a
     * no-such-element from inside the fixture.
     */
    private static String statusOfSelector(Connection c, UUID id) throws SQLException {
        try (var st = c.prepareStatement(
                "SELECT status FROM worklist.selector WHERE id = ?")) {
            st.setObject(1, id);
            try (ResultSet rs = st.executeQuery()) {
                List<String> found = new ArrayList<>();
                while (rs.next()) {
                    found.add(rs.getString(1));
                }
                return found.size() == 1 ? found.get(0) : String.valueOf(found);
            }
        }
    }
}
