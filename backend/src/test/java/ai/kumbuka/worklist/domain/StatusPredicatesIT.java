/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.Db;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The predicates {@code reviewed}, {@code approved} and {@code verified}, what
 * a scope declares it uses, and the consistency rules over one status, against
 * the running database under the service's own role (REA-0011 S3, TAR-0002 B3
 * and Annex A1, DEC-0037 C2 to C6).
 *
 * <p>Each case names the acceptance criterion of the order it covers, K1 to
 * K7. The expectations are written here from those nodes: the predicate
 * names, which combination is refused, which predicate is answered where. None
 * is read from the service.
 *
 * <p>Every case works in a scope of its own, opened with the item view and
 * with no usage declared; a case declares what it needs.
 *
 * <h2>The red probe</h2>
 *
 * With the third rule taken out of the declaration of a status -- in
 * {@code VocabularyRegistry.declareStatus}, {@code usesReviewOrApproval}
 * replaced by {@code false} -- the declaration of an actionable status that
 * is neither reviewed nor approved in a scope using {@code reviewed} is
 * accepted, and
 * {@link #k2_in_a_scope_using_reviewed_an_actionable_status_must_be_reviewed_and_approved}
 * turns red at its first {@code refusalFrom}: nothing is thrown where a
 * refusal is expected.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class StatusPredicatesIT {

    private static final String CALLER = "subject-of-the-caller";
    private static final UUID TENANT = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);
    private static final UUID FOREIGN_TENANT =
        UUID.fromString("00000000-0000-0000-0000-0000000004ff");

    private UUID scope;

    @Inject ItemService items;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;

    @BeforeEach
    void aScopeOfItsOwn() {
        scope = UUID.randomUUID();
        selectors.open(scope, Selector.ITEM);
    }

    // ====================================================================
    // K1: a status whose predicates contradict each other is refused
    // ====================================================================

    @Test
    void k1_verified_without_closed_and_successful_is_refused_and_writes_nothing() {
        // name, actionable, in_progress, closed, successful, reviewed, approved, verified
        assertRefusedUnwritten("verified-alone", false, false, false, false, false, false, true);
        assertRefusedUnwritten("verified-closed", false, false, true, false, false, false, true);
        assertRefusedUnwritten("verified-successful",
            false, false, false, true, false, false, true);

        ItemStatus confirmed = vocabulary.declareStatus(scope, "confirmed", 1,
            false, false, true, true, false, false, true);
        assertThat(confirmed.verified)
            .as("verified with closed and successful is the one admitted form")
            .isTrue();
    }

    @Test
    void k1_closed_with_in_progress_is_refused_and_writes_nothing() {
        assertRefusedUnwritten("finished-and-running", false, true, true, false, false, false, false);
    }

    @Test
    void k1_successful_without_closed_is_accepted() {
        ItemStatus status = vocabulary.declareStatus(scope, "achieved-early", 1,
            true, false, false, true);
        assertThat(status.successful)
            .as("a rule of the answer and not of the declaration (TAR-0002 B3)")
            .isTrue();
    }

    // ====================================================================
    // K2: in a scope that uses reviewed or approved, actionable means both
    // ====================================================================

    @Test
    void k2_in_a_scope_using_reviewed_an_actionable_status_must_be_reviewed_and_approved() {
        vocabulary.declareUsage(scope, ScopeUsage.Property.REVIEWED);

        assertRefusedUnwritten("ready-unreviewed", true, false, false, false, false, false, false);
        assertRefusedUnwritten("ready-reviewed-only",
            true, false, false, false, true, false, false);
        assertRefusedUnwritten("ready-approved-only",
            true, false, false, false, false, true, false);

        assertThat(vocabulary.declareStatus(scope, "ready", 1,
                true, false, false, false, true, true, false).actionable)
            .as("actionable, reviewed and approved together are admitted")
            .isTrue();
        assertThat(vocabulary.declareStatus(scope, "triage", 2,
                false, false, false, false).name)
            .as("a status that is not actionable needs neither")
            .isEqualTo("triage");
    }

    @Test
    void k2_in_a_scope_using_approved_an_actionable_status_must_be_reviewed_and_approved() {
        vocabulary.declareUsage(scope, ScopeUsage.Property.APPROVED);

        assertRefusedUnwritten("ready-unapproved", true, false, false, false, false, false, false);
        assertRefusedUnwritten("ready-approved-only",
            true, false, false, false, false, true, false);
    }

    @Test
    void k2_a_scope_using_neither_admits_an_actionable_status_alone() {
        vocabulary.declareUsage(scope, ScopeUsage.Property.VERIFIED);
        vocabulary.declareUsage(scope, ScopeUsage.Property.PRIORITY);

        assertThat(vocabulary.declareStatus(scope, "open", 1, true, false, false, false).actionable)
            .as("verified and priority do not bring the rule along")
            .isTrue();
    }

    @Test
    void k2_declaring_reviewed_over_an_actionable_status_without_it_is_refused_and_writes_nothing() {
        vocabulary.declareStatus(scope, "open", 1, true, false, false, false);

        for (ScopeUsage.Property property
                : List.of(ScopeUsage.Property.REVIEWED, ScopeUsage.Property.APPROVED)) {
            WorklistException refusal = refusalFrom(() -> vocabulary.declareUsage(scope, property));
            assertThat(refusal.reason())
                .as("using %s would leave 'open' actionable and neither reviewed nor approved",
                    property.token())
                .isEqualTo(WorklistException.Reason.INVALID_VALUE);
        }
        assertThat(vocabulary.usages(scope)).as("no usage was written").isEmpty();
        assertThat(usageRows(scope)).isEmpty();
    }

    @Test
    void k2_a_withdrawn_actionable_status_still_refuses_the_usage() {
        ItemStatus open = vocabulary.declareStatus(scope, "open", 1, true, false, false, false);
        vocabulary.withdrawStatus(scope, open.pk);

        WorklistException refusal = refusalFrom(() ->
            vocabulary.declareUsage(scope, ScopeUsage.Property.REVIEWED));
        assertThat(refusal.reason())
            .as("items may still carry a withdrawn status, so it counts")
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);
    }

    @Test
    void k2_the_usage_is_declared_where_every_actionable_status_is_reviewed_and_approved() {
        vocabulary.declareStatus(scope, "ready", 1, true, false, false, false, true, true, false);
        vocabulary.declareStatus(scope, "new", 2, false, false, false, false);

        ScopeUsage first = vocabulary.declareUsage(scope, ScopeUsage.Property.REVIEWED);
        ScopeUsage again = vocabulary.declareUsage(scope, ScopeUsage.Property.REVIEWED);

        assertThat(first.name).isEqualTo("reviewed");
        assertThat(again.name).as("a second declaration answers the row that stands")
            .isEqualTo("reviewed");
        assertThat(usageRows(scope)).containsExactly("reviewed");
    }

    // ====================================================================
    // K3: reviewed only where the scope uses it; no value elsewhere
    // ====================================================================

    @Test
    void k3_an_item_of_a_scope_that_does_not_use_reviewed_reads_without_a_value_for_it() {
        vocabulary.declareStatus(scope, "open", 1, true, false, false, false, true, true, false);
        UUID item = create("in a scope without review", "open");

        Map<String, Object> predicates = predicates(items.read(scope, item));
        assertThat(predicates)
            .as("the status declares reviewed, the scope does not use it: no value, not false")
            .doesNotContainKeys("reviewed", "approved", "verified");
    }

    @Test
    void k3_an_item_of_a_scope_that_uses_reviewed_reads_the_value_of_its_status() {
        vocabulary.declareUsage(scope, ScopeUsage.Property.REVIEWED);
        vocabulary.declareStatus(scope, "triage", 1, false, false, false, false);
        vocabulary.declareStatus(scope, "ready", 2, true, false, false, false, true, true, false);
        UUID raw = create("not yet reviewed", "triage");
        UUID ready = create("reviewed", "ready");

        assertThat(predicates(items.read(scope, raw)))
            .as("used and not held: false")
            .containsEntry("reviewed", false)
            .doesNotContainKeys("approved", "verified");
        assertThat(predicates(items.read(scope, ready)))
            .as("used and held: true; approved is held by the status and unused by the scope")
            .containsEntry("reviewed", true)
            .doesNotContainKeys("approved", "verified");
    }

    @Test
    void k3_the_predicate_follows_the_status_through_update() {
        vocabulary.declareUsage(scope, ScopeUsage.Property.APPROVED);
        vocabulary.declareStatus(scope, "proposed", 1, false, false, false, false);
        vocabulary.declareStatus(scope, "approved", 2, true, false, false, false, true, true, false);
        UUID item = create("moves into approval", "proposed");
        assertThat(predicates(items.read(scope, item))).containsEntry("approved", false);

        Map<String, Object> moved = update(item, Map.of("status", "approved"));

        assertThat(predicates(moved))
            .as("a predicate holds because the status declares it (REA-0011 D1)")
            .containsEntry("approved", true)
            .containsEntry("actionable", true);
    }

    @Test
    void k3_verified_is_answered_in_a_scope_that_uses_it() {
        vocabulary.declareUsage(scope, ScopeUsage.Property.VERIFIED);
        vocabulary.declareStatus(scope, "open", 1, true, false, false, false);
        vocabulary.declareStatus(scope, "confirmed", 2, false, false, true, true, false, false, true);
        UUID item = ItemLifecycle.open(items, scope,
            Map.of("title", "confirmed incident", "status", "open"));
        assertThat(predicates(items.read(scope, item))).containsEntry("verified", false);

        assertThat(predicates(ItemLifecycle.close(items, scope, item, "confirmed")))
            .as("closed into a status of the phase closed, through close (REA-0011 D1)")
            .containsEntry("verified", true)
            .doesNotContainKeys("reviewed", "approved");
    }

    // ====================================================================
    // K4: closed, actionable, in_progress always; successful where closed
    // ====================================================================

    @Test
    void k4_the_answer_carries_the_four_predicates_of_every_scope_from_phase_and_status() {
        vocabulary.declareStatus(scope, "open", 1, true, false, false, false);
        vocabulary.declareStatus(scope, "running", 2, false, true, false, false);
        vocabulary.declareStatus(scope, "done", 3, false, false, true, true);
        vocabulary.declareStatus(scope, "dropped", 4, false, false, true, false);
        vocabulary.declareStatus(scope, "achieved-early", 5, false, false, false, true);

        assertThat(predicates(items.read(scope, create("open", "open"))))
            .isEqualTo(Map.of("closed", false, "actionable", true, "in_progress", false));
        UUID running = ItemLifecycle.inProgress(items, vocabulary, scope,
            Map.of("title", "running", "status", "open"), "running");
        assertThat(predicates(items.read(scope, running)))
            .as("started into a status of the phase in progress (TAR-0002 B3)")
            .isEqualTo(Map.of("closed", false, "actionable", false, "in_progress", true));
        UUID done = ItemLifecycle.closed(items, scope,
            Map.of("title", "done", "status", "open"), "done");
        assertThat(predicates(items.read(scope, done)))
            .isEqualTo(Map.of("closed", true, "successful", true, "actionable", false,
                "in_progress", false));
        UUID dropped = ItemLifecycle.closed(items, scope,
            Map.of("title", "dropped", "status", "open"), "dropped");
        assertThat(predicates(items.read(scope, dropped)))
            .isEqualTo(Map.of("closed", true, "successful", false, "actionable", false,
                "in_progress", false));
        assertThat(predicates(items.read(scope, create("early", "achieved-early"))))
            .as("successful is answered only where closed holds (TAR-0002 B3)")
            .isEqualTo(Map.of("closed", false, "actionable", false, "in_progress", false));
    }

    @Test
    void k4_a_read_answer_carrying_its_predicates_goes_back_as_it_came() {
        vocabulary.declareStatus(scope, "open", 1, true, false, false, false);
        UUID item = create("echoed", "open");
        Map<String, Object> read = items.read(scope, item);

        Map<String, Object> echoed = items.update(scope, item, read).answer();
        assertThat(echoed.get("conflict_token"))
            .as("the predicates are read-only and an unchanged echo changes nothing")
            .isEqualTo(read.get("conflict_token"));

        Map<String, Object> changed = new LinkedHashMap<>(read);
        changed.put("predicates", Map.of("closed", true, "actionable", true,
            "in_progress", false));
        WorklistException refusal = refusalFrom(() -> items.update(scope, item, changed));
        assertThat(refusal.reason())
            .as("a predicate is changed through the status, never set (REA-0011 D1)")
            .isEqualTo(WorklistException.Reason.FIELD_NOT_SETTABLE);
        assertThat(refusal.offenders()).containsExactly("fields.predicates");
    }

    // ====================================================================
    // K5: priority and severity only in a scope that uses them
    // ====================================================================

    @Test
    void k5_a_create_with_priority_or_severity_in_a_scope_without_them_is_refused() {
        vocabulary.declareStatus(scope, "open", 1, true, false, false, false);

        for (Map.Entry<String, String> valued
                : Map.of("priority", "high", "severity", "major").entrySet()) {
            String title = "valued in a scope without " + valued.getKey();
            WorklistException refusal = refusalFrom(() -> items.create(scope, CALLER, Map.of(
                "title", title, "status", "open", valued.getKey(), valued.getValue())));
            assertThat(refusal.reason())
                .as("an item of a scope that does not use %s carries no value for it "
                    + "(DEC-0037 C5)", valued.getKey())
                .isEqualTo(WorklistException.Reason.INVALID_VALUE);
            assertThat(refusal.offenders()).containsExactly("fields." + valued.getKey());
            assertThat(itemsTitled(title)).as("and no item was stored").isZero();
        }

        Map<String, Object> created = items.create(scope, CALLER, Map.of(
            "title", "empty values", "status", "open", "priority", "", "severity", "")).answer();
        assertThat(created)
            .as("the empty value is accepted, and the answer names none")
            .containsEntry("priority", null)
            .containsEntry("severity", null);
    }

    @Test
    void k5_an_update_with_priority_or_severity_in_a_scope_without_them_is_refused() {
        vocabulary.declareStatus(scope, "open", 1, true, false, false, false);
        UUID item = create("unvalued", "open");
        Map<String, Object> before = items.read(scope, item);

        for (Map.Entry<String, String> valued
                : Map.of("priority", "low", "severity", "minor").entrySet()) {
            WorklistException refusal = refusalFrom(() -> items.update(scope, item, Map.of(
                "conflict_token", before.get("conflict_token"),
                valued.getKey(), valued.getValue())));
            assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
            assertThat(refusal.offenders()).containsExactly("fields." + valued.getKey());
        }
        assertThat(items.read(scope, item))
            .as("nothing was written")
            .isEqualTo(before);

        Map<String, Object> echoed = items.update(scope, item, before).answer();
        assertThat(echoed.get("conflict_token"))
            .as("the read answer, which names neither, goes back as it came")
            .isEqualTo(before.get("conflict_token"));
    }

    @Test
    void k5_a_value_stored_before_stays_in_the_column_and_is_answered_once_the_scope_uses_it()
            throws SQLException {
        vocabulary.declareStatus(scope, "open", 1, true, false, false, false);
        UUID item = create("valued before the usage", "open");
        setStoredProperties(item, "high", "critical");

        assertThat(items.read(scope, item))
            .as("a scope without the usage answers no value")
            .containsEntry("priority", null)
            .containsEntry("severity", null);

        update(item, Map.of("title", "renamed", "priority", "", "severity", ""));
        assertThat(storedProperties(item))
            .as("the empty value changes nothing, so the stored values stay")
            .containsExactly("high", "critical");

        vocabulary.declareUsage(scope, ScopeUsage.Property.PRIORITY);
        vocabulary.declareUsage(scope, ScopeUsage.Property.SEVERITY);
        assertThat(items.read(scope, item))
            .as("and once the scope uses them, the item answers them")
            .containsEntry("priority", "high")
            .containsEntry("severity", "critical");
    }

    @Test
    void k5_in_a_scope_that_uses_them_priority_and_severity_behave_as_before() {
        vocabulary.declareStatus(scope, "open", 1, true, false, false, false);
        vocabulary.declareUsage(scope, ScopeUsage.Property.PRIORITY);
        UUID item = create("valued", "open");

        assertThat(update(item, Map.of("priority", "medium")))
            .containsEntry("priority", "medium");
        WorklistException severity = refusalFrom(() -> items.update(scope, item, Map.of(
            "conflict_token", items.read(scope, item).get("conflict_token"),
            "severity", "major")));
        assertThat(severity.offenders())
            .as("each of the two is declared on its own")
            .containsExactly("fields.severity");

        vocabulary.declareUsage(scope, ScopeUsage.Property.SEVERITY);
        assertThat(update(item, Map.of("severity", "major")))
            .containsEntry("priority", "medium")
            .containsEntry("severity", "major");
    }

    // ====================================================================
    // K6: a status and an item from before the step read as they did
    // ====================================================================

    /**
     * The inserts of the image before this step, which name none of the new
     * columns, still succeed against the new schema, and the status reads
     * back with the three new predicates false and the item with its fields.
     */
    @Test
    void k6_a_status_and_an_item_inserted_as_the_previous_image_does_read_back_unchanged()
            throws SQLException {
        vocabulary.declareStatus(scope, "seed", 1, true, false, false, false);
        create("so the default workstream stands", "seed");
        long number = selectors.allocate(scope, Selector.ITEM);

        long status;
        UUID id;
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, TENANT);
            try (var st = c.prepareStatement("""
                    INSERT INTO worklist.item_status
                        (tenant_id, scope_id, name, rank, actionable, in_progress, closed,
                         successful)
                    VALUES (?::uuid, ?::uuid, 'from-before', 7, true, false, false, false)
                    RETURNING pk
                    """)) {
                st.setString(1, TENANT.toString());
                st.setString(2, scope.toString());
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    status = rs.getLong(1);
                }
            }
            try (var st = c.prepareStatement("""
                    INSERT INTO worklist.item
                        (tenant_id, scope_id, title, description, status_pk, number,
                         workstream_number)
                    VALUES (?::uuid, ?::uuid, 'from the image before', 'as it was', ?, ?,
                        (SELECT number FROM worklist.workstream
                          WHERE tenant_id = ?::uuid AND scope_id = ?::uuid AND is_default))
                    RETURNING id
                    """)) {
                st.setString(1, TENANT.toString());
                st.setString(2, scope.toString());
                st.setLong(3, status);
                st.setLong(4, number);
                st.setString(5, TENANT.toString());
                st.setString(6, scope.toString());
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    id = UUID.fromString(rs.getString(1));
                }
            }
            c.commit();
        }

        ItemStatus declared = vocabulary.statusByName(scope, "from-before").orElseThrow();
        assertThat(List.of(declared.actionable, declared.inProgress, declared.closed,
                declared.successful))
            .as("the four predicates it was declared with")
            .containsExactly(true, false, false, false);
        assertThat(List.of(declared.reviewed, declared.approved, declared.verified))
            .as("and the three new ones false, by their default")
            .containsExactly(false, false, false);

        Map<String, Object> read = items.read(scope, id);
        assertThat(read)
            .containsEntry("title", "from the image before")
            .containsEntry("description", "as it was")
            .containsEntry("status", "from-before")
            .as("an item the image before inserts takes the column's default phase")
            .containsEntry("phase", "open")
            .containsEntry("number", number);
        assertThat(predicates(read))
            .isEqualTo(Map.of("closed", false, "actionable", true, "in_progress", false));
    }

    // ====================================================================
    // K7: the usage rows are the tenant's and the service role's
    // ====================================================================

    @Test
    void k7_the_runtime_role_writes_and_reads_the_usage_of_its_own_tenant() {
        vocabulary.declareUsage(scope, ScopeUsage.Property.VERIFIED);

        assertThat(vocabulary.usages(scope)).containsExactly(ScopeUsage.Property.VERIFIED);
        assertThat(usageRows(scope)).containsExactly("verified");
    }

    @Test
    void k7_a_second_tenants_usage_on_the_same_scope_id_is_invisible() throws SQLException {
        vocabulary.declareStatus(scope, "open", 1, true, false, false, false);
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, FOREIGN_TENANT);
            insertUsage(c, FOREIGN_TENANT, "reviewed");
            c.commit();
        }

        assertThat(vocabulary.usages(scope))
            .as("the second tenant declared reviewed on the same scope id; this tenant's "
                + "scope uses nothing")
            .isEmpty();
        assertThat(predicates(items.read(scope, create("unreviewed", "open"))))
            .doesNotContainKey("reviewed");
        assertThat(vocabulary.declareStatus(scope, "also-open", 2,
                true, false, false, false).actionable)
            .as("and the rule over reviewed does not reach this tenant's statuses")
            .isTrue();
    }

    @Test
    void k7_a_session_bound_to_one_tenant_cannot_plant_a_usage_for_another() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, TENANT);
            assertThatThrownBy(() -> insertUsage(c, FOREIGN_TENANT, "approved"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("row-level security");
            c.rollback();
        }
    }

    @Test
    void k7_the_store_refuses_a_usage_outside_the_five_names() throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, TENANT);
            assertThatThrownBy(() -> insertUsage(c, TENANT, "urgency"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_scope_usage_name");
            c.rollback();
        }
    }

    // ====================================================================
    // Fixtures
    // ====================================================================

    /**
     * Declares a status with these predicates, expects the refusal, and that
     * no status of that name was written.
     */
    private void assertRefusedUnwritten(String name, boolean actionable, boolean inProgress,
            boolean closed, boolean successful, boolean reviewed, boolean approved,
            boolean verified) {
        WorklistException refusal = refusalFrom(() -> vocabulary.declareStatus(scope, name, 9,
            actionable, inProgress, closed, successful, reviewed, approved, verified));
        assertThat(refusal.reason())
            .as("the predicates of %s contradict each other", name)
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(vocabulary.statusByName(scope, name))
            .as("and nothing was written for %s", name)
            .isEmpty();
    }

    private UUID create(String title, String status) {
        Map<String, Object> created = items.create(scope, CALLER, Map.of(
            "title", title, "status", status)).answer();
        return (UUID) created.get("id");
    }

    /** An update of these fields, with the item's current token. */
    private Map<String, Object> update(UUID item, Map<String, Object> fields) {
        Map<String, Object> arguments = new LinkedHashMap<>(fields);
        arguments.put("conflict_token", items.read(scope, item).get("conflict_token"));
        return items.update(scope, item, arguments).answer();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> predicates(Map<String, Object> answer) {
        Object predicates = answer.get("predicates");
        assertThat(predicates).as("the answer carries predicates").isInstanceOf(Map.class);
        return (Map<String, Object>) predicates;
    }

    private static WorklistException refusalFrom(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }

    private void insertUsage(Connection c, UUID tenant, String name) throws SQLException {
        try (var st = c.prepareStatement(
                "INSERT INTO worklist.scope_usage (tenant_id, scope_id, name) VALUES (?, ?, ?)")) {
            st.setObject(1, tenant);
            st.setObject(2, scope);
            st.setString(3, name);
            st.executeUpdate();
        }
    }

    /** The usage rows of the probe tenant on a scope, read under the service role. */
    private static List<String> usageRows(UUID scope) {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, TENANT);
            List<String> names = new java.util.ArrayList<>();
            try (var st = c.prepareStatement(
                    "SELECT name FROM worklist.scope_usage WHERE scope_id = ? ORDER BY name")) {
                st.setObject(1, scope);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        names.add(rs.getString(1));
                    }
                }
            }
            c.rollback();
            return names;
        } catch (SQLException failed) {
            throw new IllegalStateException(failed);
        }
    }

    private long itemsTitled(String title) {
        try (Connection c = Db.asAdmin();
             var st = c.prepareStatement(
                 "SELECT count(*) FROM worklist.item WHERE scope_id = ? AND title = ?")) {
            st.setObject(1, scope);
            st.setString(2, title);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                long count = rs.getLong(1);
                c.rollback();
                return count;
            }
        } catch (SQLException failed) {
            throw new IllegalStateException(failed);
        }
    }

    /** Writes the two columns directly, as a value stored before the usage existed. */
    private void setStoredProperties(UUID item, String priority, String severity)
            throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, TENANT);
            try (var st = c.prepareStatement(
                    "UPDATE worklist.item SET priority = ?, severity = ? WHERE id = ?")) {
                st.setString(1, priority);
                st.setString(2, severity);
                st.setObject(3, item);
                assertThat(st.executeUpdate()).isEqualTo(1);
            }
            c.commit();
        }
    }

    private List<String> storedProperties(UUID item) throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, TENANT);
            try (var st = c.prepareStatement(
                    "SELECT priority, severity FROM worklist.item WHERE id = ?")) {
                st.setObject(1, item);
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    List<String> values = List.of(rs.getString(1), rs.getString(2));
                    c.rollback();
                    return values;
                }
            }
        }
    }
}
