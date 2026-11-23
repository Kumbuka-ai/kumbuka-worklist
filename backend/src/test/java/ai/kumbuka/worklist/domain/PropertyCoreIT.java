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
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The property core of an item and the duplicate relation, against the
 * running database under the service's own role (REA-0011 S2 and D18,
 * DEC-0037 C3 to C6, TAR-0002 B4, B5 and Annex A2).
 *
 * <p>Each case names the acceptance criterion of the order it covers, K1 to
 * K5, or the element of D18. The expectations are written here from those
 * nodes: the value sets, the statuses and the subjects are literals of this
 * class, never read from the service.
 *
 * <p>Every case works in a scope of its own, declared with the statuses a
 * scope of the bootstrap kind carries: {@code open}; {@code done}, closed and
 * successful; {@code dropped} and {@code obsolete}, closed and not successful.
 * Like the bootstrap scope it declares that it uses {@code priority} and
 * {@code severity}; a scope that does not is {@code StatusPredicatesIT}'s.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class PropertyCoreIT {

    private static final String CALLER = "subject-of-the-caller";
    private static final String DUPLICATE_OF = "duplicate_of";

    private UUID scope;

    @Inject ItemService items;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;

    @BeforeEach
    void aScopeOfItsOwn() {
        scope = UUID.randomUUID();
        selectors.open(scope, Selector.ITEM);
        vocabulary.declareStatus(scope, "open", 1, true, false, false, false);
        vocabulary.declareStatus(scope, "done", 2, false, false, true, true);
        vocabulary.declareStatus(scope, "dropped", 3, false, false, true, false);
        vocabulary.declareStatus(scope, "obsolete", 4, false, false, true, false);
        vocabulary.declareRelationType(scope, "carries", false, 1);
        // Since S3 a scope carries priority and severity only where it
        // declares it uses them (DEC-0037 C5); the bootstrap scope does.
        vocabulary.declareUsage(scope, ScopeUsage.Property.PRIORITY);
        vocabulary.declareUsage(scope, ScopeUsage.Property.SEVERITY);
    }

    // ====================================================================
    // K1: creator from the caller; creator and close_date are the service's
    // ====================================================================

    @Test
    void k1_an_item_created_reads_back_with_the_calling_subject_as_creator() {
        UUID item = create("created by the caller");

        assertThat(items.read(scope, item).get("creator"))
            .as("the service writes the calling subject at the create (TAR-0002 B4)")
            .isEqualTo(CALLER);
    }

    @Test
    void k1_a_create_naming_creator_or_close_date_is_refused() {
        for (String field : List.of("creator", "close_date")) {
            WorklistException refusal = refusalFrom(() -> items.create(scope, CALLER, Map.of(
                "title", "names a field of the service", "status", "open",
                field, "2026-11-20T10:00:00Z")));

            assertThat(refusal.reason())
                .as("%s is set by the service and never by the caller", field)
                .isEqualTo(WorklistException.Reason.FIELD_NOT_SETTABLE);
            assertThat(refusal.offenders()).containsExactly("fields." + field);
        }
    }

    @Test
    void k1_an_update_setting_creator_or_close_date_is_refused_and_an_echo_is_not() {
        UUID item = create("its creator stays");
        String token = tokenOf(item);

        for (String field : List.of("creator", "close_date")) {
            WorklistException refusal = refusalFrom(() -> items.update(scope, item, Map.of(
                "conflict_token", token, field, "2026-11-20T10:00:00Z"), CALLER));
            assertThat(refusal.reason())
                .as("an update that gives %s a value of its own is refused", field)
                .isEqualTo(WorklistException.Reason.FIELD_NOT_SETTABLE);
            assertThat(refusal.offenders()).containsExactly("fields." + field);
        }

        Map<String, Object> echoed = items.update(scope, item, items.read(scope, item), CALLER).answer();
        assertThat(echoed.get("conflict_token"))
            .as("the read answer sent back as it came, creator included, changes nothing")
            .isEqualTo(token);
        assertThat(echoed.get("creator")).isEqualTo(CALLER);
    }

    // ====================================================================
    // K2: close_date is set by the transition into the phase closed, and
    // nothing reopens an item (REA-0011 D11, TAR-0002 B20)
    // ====================================================================

    @Test
    void k2_closing_by_close_sets_close_date_and_nothing_clears_it() {
        UUID item = create("closed for good");
        assertThat(items.read(scope, item).get("close_date"))
            .as("an open item has no close date")
            .isNull();

        WorklistException byStatus = refusalFrom(() -> update(item, Map.of("status", "done")));
        assertThat(byStatus.reason())
            .as("a status of the phase closed is no status of the open item's phase: the "
                + "phase moves through close, and the status write is refused by state")
            .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertThat(items.read(scope, item).get("close_date")).isNull();

        Map<String, Object> closed = ItemLifecycle.close(items, scope, item, "done");
        assertThat(closed.get("close_date"))
            .as("entering the phase closed sets close_date")
            .isNotNull();
        assertThat(closed.get("phase")).isEqualTo("closed");

        for (String status : List.of("dropped", "open")) {
            WorklistException refusal = refusalFrom(() -> update(item, Map.of("status", status)));
            assertThat(refusal.reason())
                .as("a closed item takes no update, to %s neither", status)
                .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        }
        assertThat(items.read(scope, item))
            .as("so the item keeps its status and the date it closed on")
            .containsEntry("status", "done")
            .containsEntry("close_date", closed.get("close_date"));
    }

    // ====================================================================
    // K3 and D18: duplicate_of closes the item, where closing is open
    // ====================================================================

    @Test
    void k3_duplicate_of_closes_an_open_item_into_its_target_status_and_makes_the_edge() {
        vocabulary.declareRelationType(scope, DUPLICATE_OF, false, 2, "obsolete");
        UUID duplicate = create("the duplicate");
        UUID original = create("the original");

        Map<String, Object> answer = items.relate(scope, duplicate, addressOf(original),
            DUPLICATE_OF, tokenOf(duplicate)).answer();

        assertThat(answer.get("status"))
            .as("the item is closed into the target status the type declares, closed and "
                + "not successful")
            .isEqualTo("obsolete");
        assertThat(answer.get("close_date"))
            .as("and its close_date is set in the same write")
            .isNotNull();
        assertThat(answer.get("phase"))
            .as("in the phase closed")
            .isEqualTo("closed");
        assertThat(answer.get("relations"))
            .as("and the edge stands")
            .isEqualTo(List.of(Map.of("type", DUPLICATE_OF, "item", addressOf(original))));
        assertThat(items.read(scope, duplicate))
            .as("which a later read answers the same way")
            .containsEntry("status", "obsolete")
            .containsEntry("close_date", answer.get("close_date"));
    }

    @Test
    void k3_duplicate_of_on_a_closed_item_is_refused_by_state_and_writes_nothing() {
        vocabulary.declareRelationType(scope, DUPLICATE_OF, false, 2, "obsolete");
        UUID duplicate = create("already closed");
        UUID original = create("the original");
        ItemLifecycle.close(items, scope, duplicate, "done");
        Map<String, Object> before = items.read(scope, duplicate);

        WorklistException refusal = refusalFrom(() -> items.relate(scope, duplicate,
            addressOf(original), DUPLICATE_OF, String.valueOf(before.get("conflict_token"))));

        assertThat(refusal.reason())
            .as("closing is not open on a closed item, so the relation that closes is not "
                + "either (TAR-0002 B5)")
            .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertNothingWritten(duplicate, before);
    }

    @Test
    void d18_duplicate_of_without_a_target_status_is_refused_and_writes_nothing() {
        vocabulary.declareRelationType(scope, DUPLICATE_OF, false, 2);
        UUID duplicate = create("no target named");
        UUID original = create("the original");
        Map<String, Object> before = items.read(scope, duplicate);

        WorklistException refusal = refusalFrom(() -> items.relate(scope, duplicate,
            addressOf(original), DUPLICATE_OF, String.valueOf(before.get("conflict_token"))));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertNothingWritten(duplicate, before);
    }

    @Test
    void d18_a_withdrawn_target_status_is_no_target() {
        vocabulary.declareRelationType(scope, DUPLICATE_OF, false, 2, "obsolete");
        vocabulary.withdrawStatus(scope, vocabulary.statusByName(scope, "obsolete")
            .orElseThrow().pk);
        UUID duplicate = create("target withdrawn");
        UUID original = create("the original");
        Map<String, Object> before = items.read(scope, duplicate);

        WorklistException refusal = refusalFrom(() -> items.relate(scope, duplicate,
            addressOf(original), DUPLICATE_OF, String.valueOf(before.get("conflict_token"))));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertNothingWritten(duplicate, before);
    }

    @Test
    void d18_a_successful_target_status_is_no_target() {
        vocabulary.declareRelationType(scope, DUPLICATE_OF, false, 2, "done");
        UUID duplicate = create("target successful");
        UUID original = create("the original");
        Map<String, Object> before = items.read(scope, duplicate);

        WorklistException refusal = refusalFrom(() -> items.relate(scope, duplicate,
            addressOf(original), DUPLICATE_OF, String.valueOf(before.get("conflict_token"))));

        assertThat(refusal.reason())
            .as("a duplicate is closed as not successful; a successful target is refused")
            .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertNothingWritten(duplicate, before);
    }

    @Test
    void d18_only_the_type_named_duplicate_of_takes_a_target_status() {
        WorklistException refusal = refusalFrom(() ->
            vocabulary.declareRelationType(scope, "supersedes", false, 3, "obsolete"));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(vocabulary.relationTypes(scope))
            .extracting(type -> type.name)
            .doesNotContain("supersedes");
    }

    @Test
    void d18_the_store_refuses_a_target_status_on_a_type_of_another_name() throws SQLException {
        long carries = vocabulary.relationTypes(scope).stream()
            .filter(type -> "carries".equals(type.name)).findFirst().orElseThrow().pk;
        long obsolete = vocabulary.statusByName(scope, "obsolete").orElseThrow().pk;

        try (Connection c = Db.asService()) {
            Db.bindTenant(c, UUID.fromString(SubstrateDatabaseResource.TENANT_ID));
            Throwable refused = catchThrowable(() -> {
                try (var st = c.prepareStatement(
                        "UPDATE worklist.relation_type SET target_status_pk = ? WHERE pk = ?")) {
                    st.setLong(1, obsolete);
                    st.setLong(2, carries);
                    st.executeUpdate();
                }
            });
            c.rollback();
            assertThat(refused)
                .as("the runtime role writing a target status onto a type not named "
                    + "duplicate_of is refused by the store")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ck_relation_type_target_duplicate_of");
        }
    }

    @Test
    void d18_the_duplicate_of_edge_of_a_closed_item_stays_and_nothing_reopens_it() {
        vocabulary.declareRelationType(scope, DUPLICATE_OF, false, 2, "obsolete");
        UUID duplicate = create("stays closed");
        UUID original = create("the original");
        Map<String, Object> closed = items.relate(scope, duplicate, addressOf(original),
            DUPLICATE_OF, tokenOf(duplicate)).answer();

        WorklistException refusal = refusalFrom(() -> items.unrelate(scope, duplicate,
            addressOf(original), DUPLICATE_OF, "a reason", CALLER,
            String.valueOf(closed.get("conflict_token"))));

        assertThat(refusal.reason())
            .as("a closed item takes no write: unrelate is refused by state (REA-0011 D6)")
            .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertThat(items.read(scope, duplicate))
            .as("the edge stands and the item stays in the status the edge closed it into")
            .containsEntry("relations",
                List.of(Map.of("type", DUPLICATE_OF, "item", addressOf(original))))
            .containsEntry("status", "obsolete")
            .containsEntry("phase", "closed")
            .containsEntry("close_date", closed.get("close_date"));
    }

    @Test
    void d18_duplicate_of_on_a_draft_is_refused_by_state_and_writes_nothing() {
        vocabulary.declareRelationType(scope, DUPLICATE_OF, false, 2, "obsolete");
        UUID duplicate = ItemLifecycle.draft(items, scope,
            Map.of("title", "still a draft", "status", "open"));
        UUID original = create("the original");
        Map<String, Object> before = items.read(scope, duplicate);

        WorklistException refusal = refusalFrom(() -> items.relate(scope, duplicate,
            addressOf(original), DUPLICATE_OF, String.valueOf(before.get("conflict_token"))));

        assertThat(refusal.reason())
            .as("a draft closes nowhere: it is submitted first (TAR-0002 B20)")
            .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertNothingWritten(duplicate, before);
    }

    /**
     * The red probe of D18's last clause: an update whose relations add a
     * {@code duplicate_of} edge is refused, so the whole-set write cannot
     * set the relation without its closing effect. Without the refusal the
     * edge would stand on an open item, which is the case asserted absent.
     */
    @Test
    void d18_an_update_whose_relations_add_duplicate_of_is_refused_by_state() {
        vocabulary.declareRelationType(scope, DUPLICATE_OF, false, 2, "obsolete");
        UUID duplicate = create("added by update");
        UUID original = create("the original");
        Map<String, Object> before = items.read(scope, duplicate);

        WorklistException refusal = refusalFrom(() -> items.update(scope, duplicate, Map.of(
            "conflict_token", before.get("conflict_token"),
            "relations", List.of(Map.of("type", DUPLICATE_OF, "item", addressOf(original)))), CALLER));

        assertThat(refusal.reason())
            .as("adding duplicate_of through update is refused by state; relate is the "
                + "open call that sets it")
            .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertNothingWritten(duplicate, before);
    }

    @Test
    void d18_an_update_whose_relations_remove_duplicate_of_is_refused_by_state() {
        vocabulary.declareRelationType(scope, DUPLICATE_OF, false, 2, "obsolete");
        UUID duplicate = create("removed by update");
        UUID original = create("the original");
        items.relate(scope, duplicate, addressOf(original), DUPLICATE_OF, tokenOf(duplicate));
        Map<String, Object> before = items.read(scope, duplicate);

        WorklistException refusal = refusalFrom(() -> items.update(scope, duplicate, Map.of(
            "conflict_token", before.get("conflict_token"),
            "relations", List.of()), CALLER));

        assertThat(refusal.reason())
            .as("removing duplicate_of through update is refused by state: the edge closed "
                + "the item, and a closed item takes no update")
            .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertThat(items.read(scope, duplicate))
            .as("nothing was written: the edge, the status and the token are as they were")
            .isEqualTo(before);
    }

    /**
     * The duplicate is closed by its edge, and a closed item is final: its
     * read answer, edge included, goes back to no update, and an edge of
     * another type beside it is refused the same way (REA-0011 D6).
     */
    @Test
    void d18_a_duplicate_closed_by_its_edge_takes_no_update_not_even_its_echo() {
        vocabulary.declareRelationType(scope, DUPLICATE_OF, false, 2, "obsolete");
        UUID duplicate = create("echoed");
        UUID original = create("the original");
        UUID other = create("another");
        items.relate(scope, duplicate, addressOf(original), DUPLICATE_OF, tokenOf(duplicate));
        Map<String, Object> read = items.read(scope, duplicate);

        WorklistException echo = refusalFrom(() -> items.update(scope, duplicate, read, CALLER));
        assertThat(echo.reason())
            .as("the closed item refuses the update by state, whatever it carries")
            .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);

        Map<String, Object> withAnother = new LinkedHashMap<>(read);
        withAnother.put("relations", List.of(
            Map.of("type", DUPLICATE_OF, "item", addressOf(original)),
            Map.of("type", "carries", "item", addressOf(other))));
        WorklistException another = refusalFrom(() -> items.update(scope, duplicate, withAnother, CALLER));
        assertThat(another.reason()).isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertNothingWritten(duplicate, read);
    }

    @Test
    void d18_a_create_whose_relations_carry_duplicate_of_is_refused() {
        vocabulary.declareRelationType(scope, DUPLICATE_OF, false, 2, "obsolete");
        UUID original = create("the original");

        WorklistException refusal = refusalFrom(() -> items.create(scope, CALLER, Map.of(
            "title", "born a duplicate", "status", "open",
            "relations", List.of(Map.of("type", DUPLICATE_OF, "item", addressOf(original))))));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly("fields.relations");
    }

    // ====================================================================
    // K4: contributor, parent, priority, severity
    // ====================================================================

    @Test
    void k4_the_four_caller_properties_are_set_on_create_and_update_and_read_back() {
        UUID parent = create("the whole");
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", "a part");
        fields.put("status", "open");
        fields.put("contributor", List.of("person-a", "agent-b"));
        fields.put("parent", addressOf(parent));
        fields.put("priority", "high");
        fields.put("severity", "major");
        Map<String, Object> created = items.create(scope, CALLER, fields).answer();
        UUID part = (UUID) created.get("id");

        assertThat(items.read(scope, part))
            .containsEntry("contributor", List.of("person-a", "agent-b"))
            .containsEntry("parent", addressOf(parent))
            .containsEntry("priority", "high")
            .containsEntry("severity", "major");

        UUID otherParent = create("another whole");
        Map<String, Object> updated = update(part, Map.of(
            "contributor", List.of("agent-b"), "parent", addressOf(otherParent),
            "priority", "low", "severity", "blocker"));
        assertThat(updated)
            .containsEntry("contributor", List.of("agent-b"))
            .containsEntry("parent", addressOf(otherParent))
            .containsEntry("priority", "low")
            .containsEntry("severity", "blocker");

        Map<String, Object> cleared = update(part, Map.of(
            "contributor", List.of(), "parent", "", "priority", "", "severity", ""));
        assertThat(cleared)
            .as("the empty value clears each of the four")
            .containsEntry("contributor", List.of())
            .containsEntry("parent", null)
            .containsEntry("priority", null)
            .containsEntry("severity", null);
    }

    @Test
    void k4_a_value_outside_the_value_set_is_refused() {
        UUID item = create("valued");
        String token = tokenOf(item);

        for (Map.Entry<String, String> outside : Map.of(
                "priority", "P1", "severity", "trivial").entrySet()) {
            WorklistException refusal = refusalFrom(() -> items.update(scope, item, Map.of(
                "conflict_token", token, outside.getKey(), outside.getValue()), CALLER));
            assertThat(refusal.reason())
                .as("%s is outside the value set of %s", outside.getValue(), outside.getKey())
                .isEqualTo(WorklistException.Reason.INVALID_VALUE);
            assertThat(refusal.offenders()).containsExactly("fields." + outside.getKey());
        }
        WorklistException onCreate = refusalFrom(() -> items.create(scope, CALLER, Map.of(
            "title", "valued wrongly", "status", "open", "priority", "urgent")));
        assertThat(onCreate.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(onCreate.offenders()).containsExactly("fields.priority");
    }

    @Test
    void k4_a_parent_on_itself_or_in_a_cycle_is_refused() {
        UUID a = create("a");
        UUID b = create("b");
        UUID c = create("c");

        WorklistException self = refusalFrom(() -> items.update(scope, a, Map.of(
            "conflict_token", tokenOf(a), "parent", addressOf(a)), CALLER));
        assertThat(self.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(self.offenders()).containsExactly("fields.parent");

        update(b, Map.of("parent", addressOf(a)));
        update(c, Map.of("parent", addressOf(b)));
        WorklistException cycle = refusalFrom(() -> items.update(scope, a, Map.of(
            "conflict_token", tokenOf(a), "parent", addressOf(c)), CALLER));
        assertThat(cycle.reason())
            .as("a's parent c, whose chain runs c, b, a, would close a cycle")
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(cycle.offenders()).containsExactly("fields.parent");
        assertThat(items.read(scope, a).get("parent")).isNull();
    }

    @Test
    void k4_a_parent_from_another_scope_is_refused() {
        UUID elsewhere = UUID.randomUUID();
        selectors.open(elsewhere, Selector.ITEM);
        vocabulary.declareStatus(elsewhere, "open", 1, true, false, false, false);
        Map<String, Object> foreign = items.create(elsewhere, CALLER, Map.of(
            "title", "in another scope", "status", "open")).answer();
        String foreignAddress = "worklist://" + foreign.get("scope") + "/item/"
            + foreign.get("number");
        UUID item = create("wants a foreign parent");

        WorklistException refusal = refusalFrom(() -> items.update(scope, item, Map.of(
            "conflict_token", tokenOf(item), "parent", foreignAddress), CALLER));

        assertThat(refusal.reason())
            .as("a parent lies in the item's own scope; another scope's item is one this "
                + "scope does not hold")
            .isEqualTo(WorklistException.Reason.ITEM_UNKNOWN);
        assertThat(items.read(scope, item).get("parent")).isNull();
    }

    // ====================================================================
    // K5 and K7: an item from before the step reads back unchanged
    // ====================================================================

    /**
     * The insert of the image before this step, which names none of the new
     * columns, still succeeds against the new schema (K7), and the row reads
     * back with every field it had and no value for the six properties (K5).
     */
    @Test
    void k5_an_item_inserted_as_the_previous_image_does_reads_back_without_the_properties()
            throws SQLException {
        create("so the default workstream stands");
        long status = vocabulary.statusByName(scope, "open").orElseThrow().pk;
        long number = selectors.allocate(scope, Selector.ITEM);
        UUID tenant = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);

        UUID id;
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            try (var st = c.prepareStatement("""
                    INSERT INTO worklist.item
                        (tenant_id, scope_id, title, description, status_pk, number,
                         workstream_number)
                    VALUES (?::uuid, ?::uuid, 'from the image before', 'as it was', ?, ?,
                        (SELECT number FROM worklist.workstream
                          WHERE tenant_id = ?::uuid AND scope_id = ?::uuid AND is_default))
                    RETURNING id
                    """)) {
                st.setString(1, tenant.toString());
                st.setString(2, scope.toString());
                st.setLong(3, status);
                st.setLong(4, number);
                st.setString(5, tenant.toString());
                st.setString(6, scope.toString());
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    id = UUID.fromString(rs.getString(1));
                }
            }
            c.commit();
        }

        Map<String, Object> read = items.read(scope, id);
        assertThat(read)
            .as("every field the item had reads as it was")
            .containsEntry("title", "from the image before")
            .containsEntry("description", "as it was")
            .containsEntry("status", "open")
            .containsEntry("phase", "open")
            .containsEntry("number", number);
        assertThat(read)
            .as("and the six properties carry no value: nothing is filled retroactively")
            .containsEntry("creator", null)
            .containsEntry("contributor", List.of())
            .containsEntry("parent", null)
            .containsEntry("priority", null)
            .containsEntry("severity", null)
            .containsEntry("close_date", null);
    }

    // ====================================================================
    // Fixtures
    // ====================================================================

    /** An open item: created as a draft and submitted, so that it may close. */
    private UUID create(String title) {
        return ItemLifecycle.open(items, scope, Map.of("title", title, "status", "open"));
    }

    /** An update of these fields, with the item's current token. */
    private Map<String, Object> update(UUID item, Map<String, Object> fields) {
        Map<String, Object> arguments = new LinkedHashMap<>(fields);
        arguments.put("conflict_token", tokenOf(item));
        return items.update(scope, item, arguments, CALLER).answer();
    }

    private void assertNothingWritten(UUID item, Map<String, Object> before) {
        Map<String, Object> after = items.read(scope, item);
        assertThat(after.get("relations")).as("no edge was made").isEqualTo(before.get("relations"));
        assertThat(after.get("status")).as("the status stands").isEqualTo(before.get("status"));
        assertThat(after.get("close_date")).isEqualTo(before.get("close_date"));
        assertThat(after.get("conflict_token"))
            .as("and the token did not move")
            .isEqualTo(before.get("conflict_token"));
    }

    private String addressOf(UUID item) {
        Map<String, Object> read = items.read(scope, item);
        return "worklist://" + read.get("scope") + "/item/" + read.get("number");
    }

    private String tokenOf(UUID item) {
        return String.valueOf(items.read(scope, item).get("conflict_token"));
    }

    private static WorklistException refusalFrom(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }
}
