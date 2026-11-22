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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The acceptance criteria of an item, against the running database under the
 * service's own role (REA-0011 S12, D14; TAR-0002 B17).
 *
 * <p>Each case names the acceptance criterion of the order it covers, K1 to
 * K6. The expectations are written here from D14: the address form
 * {@code worklist://<scope>/item/<n>/criterion/<k>}, the identifiers 1, 2, 3
 * and 4, and the texts are literals of this class, never read from the
 * service.
 *
 * <p>Every case works in a scope of its own. A probe scope is not published
 * to the platform's directory, so the scope position of an address carries
 * the scope's id, as it does for every address of such a scope.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class AcceptanceCriteriaIT {

    private static final String CALLER = "subject-of-the-caller";

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
    }

    // ====================================================================
    // K1: criteria written at the create read back with identifiers 1 and 2
    // ====================================================================

    @Test
    void k1_a_create_with_two_criteria_reads_back_with_identifiers_1_and_2() {
        UUID item = create("two criteria", List.of(
            Map.of("text", "the report names every scope"),
            Map.of("text", "the report loads in under a second")));

        assertThat(criteriaOf(items.read(scope, item)))
            .as("the service allocates 1 and 2 beneath the item, in the address form of D14")
            .containsExactly(
                criterion(item, 1, "the report names every scope"),
                criterion(item, 2, "the report loads in under a second"));
    }

    @Test
    void k1_the_store_holds_exactly_the_two_rows_under_their_numbers() throws SQLException {
        UUID item = create("stored as rows", List.of(
            Map.of("text", "first"), Map.of("text", "second")));

        assertThat(storedRows(item))
            .as("one row per criterion, numbered from 1, both asserted")
            .containsExactly("1 asserted first", "2 asserted second");
    }

    // ====================================================================
    // K2: a withdrawn identifier is never reissued
    // ====================================================================

    /**
     * The order's case, and one step beyond it: once the HIGHEST identifier
     * is withdrawn, the next new criterion still goes past it.
     *
     * <p>RED PROBE: with the next identifier formed from the living criteria
     * alone, the last update gives the new criterion 4 again -- the unique
     * index refuses that row -- and the case turns red; an allocation that
     * counted the living criteria instead turns red one update earlier, at 3.
     */
    @Test
    void k2_leaving_2_out_and_appending_withdraws_2_and_issues_3_then_4_never_2()
            throws SQLException {
        UUID item = create("criteria that move", List.of(
            Map.of("text", "kept"), Map.of("text", "dropped later")));

        Map<String, Object> first = update(item, Map.of("criteria", List.of(
            Map.of("address", address(item, 1), "text", "kept"),
            Map.of("text", "appended once"))));
        assertThat(criteriaOf(first))
            .as("2 is withdrawn and the new criterion gets 3: one after the highest issued")
            .containsExactly(
                criterion(item, 1, "kept"),
                criterion(item, 3, "appended once"));

        Map<String, Object> second = update(item, Map.of("criteria", List.of(
            Map.of("address", address(item, 1), "text", "kept"),
            Map.of("address", address(item, 3), "text", "appended once"),
            Map.of("text", "appended twice"))));
        assertThat(criteriaOf(second))
            .as("the next one gets 4, and 2 stays spent")
            .containsExactly(
                criterion(item, 1, "kept"),
                criterion(item, 3, "appended once"),
                criterion(item, 4, "appended twice"));

        assertThat(storedRows(item))
            .as("the withdrawn criterion stands in the store with its number and its text")
            .containsExactly("1 asserted kept", "2 withdrawn dropped later",
                "3 asserted appended once", "4 asserted appended twice");

        update(item, Map.of("criteria", List.of(
            Map.of("address", address(item, 1), "text", "kept"),
            Map.of("address", address(item, 3), "text", "appended once"))));
        Map<String, Object> fourth = update(item, Map.of("criteria", List.of(
            Map.of("address", address(item, 1), "text", "kept"),
            Map.of("address", address(item, 3), "text", "appended once"),
            Map.of("text", "appended after 4 left"))));
        assertThat(criteriaOf(fourth))
            .as("4, the highest issued, was withdrawn; the next is 5, and 4 stays spent")
            .containsExactly(
                criterion(item, 1, "kept"),
                criterion(item, 3, "appended once"),
                criterion(item, 5, "appended after 4 left"));
    }

    @Test
    void k2_an_entry_with_its_address_sets_the_text_of_that_criterion() {
        UUID item = create("a text corrected", List.of(Map.of("text", "the frist draft")));

        Map<String, Object> answer = update(item, Map.of("criteria", List.of(
            Map.of("address", address(item, 1), "text", "the first draft"))));

        assertThat(criteriaOf(answer))
            .as("the identifier stays and the text is the new one")
            .containsExactly(criterion(item, 1, "the first draft"));
    }

    // ====================================================================
    // K3: closing the item keeps the identifiers; a closed item takes no
    // further criterion (REA-0011 D6)
    // ====================================================================

    @Test
    void k3_a_closed_item_keeps_its_identifiers_and_takes_no_further_criterion() {
        UUID item = create("closed with its criteria", List.of(
            Map.of("text", "one"), Map.of("text", "two")));
        // The criteria are content: written while the item is a draft, and
        // frozen once it is submitted (REA-0011 D13, Annex A2).
        update(item, Map.of("criteria", List.of(
            Map.of("address", address(item, 1), "text", "one"))));
        Map<String, Object> appended = update(item, Map.of("criteria", List.of(
            Map.of("address", address(item, 1), "text", "one"),
            Map.of("text", "before closing"))));
        assertThat(criteriaOf(appended))
            .as("a criterion appended gets 3: 2 was issued before and stays spent")
            .containsExactly(
                criterion(item, 1, "one"),
                criterion(item, 3, "before closing"));
        ItemLifecycle.submit(items, scope, item);

        Map<String, Object> closed = ItemLifecycle.close(items, scope, item, "done");
        assertThat(closed.get("close_date")).as("the item is closed").isNotNull();
        assertThat(criteriaOf(items.read(scope, item)))
            .as("the closed item reads back with the identifiers it had")
            .containsExactly(criterion(item, 1, "one"), criterion(item, 3, "before closing"));

        Map<String, Object> before = items.read(scope, item);
        WorklistException refusal = refusalFrom(() -> update(item, Map.of("criteria", List.of(
            Map.of("address", address(item, 1), "text", "one"),
            Map.of("text", "after closing")))));
        assertThat(refusal.reason())
            .as("a closed item is final: an update is refused by state")
            .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertNothingWritten(item, before);
    }

    // ====================================================================
    // K4: an identifier the item does not carry, and an empty text
    // ====================================================================

    @Test
    void k4_an_identifier_the_item_never_issued_is_refused_and_writes_nothing() {
        UUID item = create("one criterion", List.of(Map.of("text", "the only one")));
        Map<String, Object> before = items.read(scope, item);

        WorklistException refusal = refusalFrom(() -> update(item, Map.of(
            "title", "a change beside it",
            "criteria", List.of(
                Map.of("address", address(item, 1), "text", "the only one"),
                Map.of("address", address(item, 9), "text", "never issued")))));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly("fields.criteria");
        assertNothingWritten(item, before);
    }

    @Test
    void k4_a_withdrawn_identifier_is_refused_and_writes_nothing() {
        UUID item = create("one withdrawn", List.of(
            Map.of("text", "stays"), Map.of("text", "goes")));
        update(item, Map.of("criteria", List.of(
            Map.of("address", address(item, 1), "text", "stays"))));
        Map<String, Object> before = items.read(scope, item);

        WorklistException refusal = refusalFrom(() -> update(item, Map.of("criteria", List.of(
            Map.of("address", address(item, 1), "text", "stays"),
            Map.of("address", address(item, 2), "text", "brought back")))));

        assertThat(refusal.reason())
            .as("a withdrawn criterion is not brought back by naming it")
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly("fields.criteria");
        assertNothingWritten(item, before);
    }

    @Test
    void k4_an_identifier_of_another_item_is_refused() {
        UUID other = create("the other item", List.of(Map.of("text", "its own")));
        UUID item = create("this item", List.of(Map.of("text", "mine")));
        Map<String, Object> before = items.read(scope, item);

        WorklistException refusal = refusalFrom(() -> update(item, Map.of("criteria", List.of(
            Map.of("address", address(other, 1), "text", "its own")))));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly("fields.criteria");
        assertNothingWritten(item, before);
    }

    @Test
    void k4_an_entry_with_an_empty_text_is_refused_on_update_and_on_create() {
        UUID item = create("its text is required", List.of(Map.of("text", "present")));
        Map<String, Object> before = items.read(scope, item);

        WorklistException onUpdate = refusalFrom(() -> update(item, Map.of("criteria", List.of(
            Map.of("address", address(item, 1), "text", "present"),
            Map.of("text", "   ")))));
        assertThat(onUpdate.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(onUpdate.offenders()).containsExactly("fields.criteria");
        assertNothingWritten(item, before);

        String title = "refused at the create " + UUID.randomUUID();
        WorklistException onCreate = refusalFrom(() -> items.create(scope, CALLER, Map.of(
            "title", title, "status", "open",
            "criteria", List.of(Map.of("text", "")))));
        assertThat(onCreate.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(onCreate.offenders()).containsExactly("fields.criteria");
        assertThat(Pages.all(scope, Selector.ITEM, items::query))
            .as("the create wrote no item")
            .noneMatch(object -> title.equals(object.get("title")));
    }

    @Test
    void k4_a_create_naming_an_identifier_is_refused() {
        WorklistException refusal = refusalFrom(() -> items.create(scope, CALLER, Map.of(
            "title", "chooses its own identifier", "status", "open",
            "criteria", List.of(Map.of(
                "address", "worklist://" + scope + "/item/1/criterion/1", "text", "mine")))));

        assertThat(refusal.reason())
            .as("a new item carries no criterion yet, and a caller never chooses an identifier")
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly("fields.criteria");
    }

    // ====================================================================
    // K5: the echo changes nothing; an update without criteria keeps them
    // ====================================================================

    @Test
    void k5_the_read_answer_sent_back_as_it_came_changes_nothing() throws SQLException {
        UUID item = create("echoed", List.of(
            Map.of("text", "one"), Map.of("text", "two")));
        Map<String, Object> read = items.read(scope, item);
        List<String> rowsBefore = storedRows(item);

        Written<Map<String, Object>, Item> echoed = items.update(scope, item, read);

        assertThat(echoed.answer().get("conflict_token"))
            .as("the token stays")
            .isEqualTo(read.get("conflict_token"));
        assertThat(echoed.answer().get("changed_at"))
            .as("and so does the modification time")
            .isEqualTo(read.get("changed_at"));
        assertThat(criteriaOf(echoed.answer())).isEqualTo(criteriaOf(read));
        assertThat(storedRows(item)).as("no criterion row moved").isEqualTo(rowsBefore);
    }

    @Test
    void k5_the_criteria_alone_echoed_in_another_order_change_nothing() {
        UUID item = create("echoed in another order", List.of(
            Map.of("text", "one"), Map.of("text", "two")));
        Map<String, Object> read = items.read(scope, item);
        List<Map<String, Object>> reversed = new ArrayList<>(criteriaOf(read));
        java.util.Collections.reverse(reversed);

        Map<String, Object> answer = items.update(scope, item, Map.of(
            "conflict_token", read.get("conflict_token"), "criteria", reversed)).answer();

        assertThat(answer.get("conflict_token"))
            .as("an item answers its criteria by identifier, so their order is no change")
            .isEqualTo(read.get("conflict_token"));
    }

    @Test
    void k5_an_update_without_criteria_leaves_them_standing() {
        UUID item = create("criteria stay", List.of(
            Map.of("text", "one"), Map.of("text", "two")));

        Map<String, Object> answer = update(item, Map.of("title", "criteria stay, renamed"));

        assertThat(answer.get("title")).isEqualTo("criteria stay, renamed");
        assertThat(criteriaOf(answer))
            .as("an update that leaves the field out leaves the criteria as they are")
            .containsExactly(criterion(item, 1, "one"), criterion(item, 2, "two"));
    }

    @Test
    void k5_an_empty_list_withdraws_every_criterion_and_moves_the_token() {
        UUID item = create("emptied", List.of(Map.of("text", "one")));
        String token = tokenOf(item);

        Map<String, Object> answer = update(item, Map.of("criteria", List.of()));

        assertThat(criteriaOf(answer)).as("the list given is the list held").isEmpty();
        assertThat(answer.get("conflict_token"))
            .as("a withdrawal is a change, so the token moves")
            .isNotEqualTo(token);
    }

    // ====================================================================
    // K6: an item from before the step reads back unchanged, without criteria
    // ====================================================================

    /**
     * The insert of the image before this step, which knows no criteria table,
     * still succeeds, and the row reads back with its description as it was and
     * an empty list of criteria: nothing is parsed out of the description and
     * nothing is filled retroactively.
     */
    @Test
    void k6_an_item_inserted_as_the_previous_image_does_reads_back_without_criteria()
            throws SQLException {
        create("so the default workstream stands", List.of());
        long status = vocabulary.statusByName(scope, "open").orElseThrow().pk;
        long number = selectors.allocate(scope, Selector.ITEM);
        UUID tenant = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);
        String prose = "Acceptance criteria:\n1. the report names every scope\n"
            + "2. the report loads in under a second";

        UUID id;
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            try (var st = c.prepareStatement("""
                    INSERT INTO worklist.item
                        (tenant_id, scope_id, title, description, status_pk, number,
                         workstream_number)
                    VALUES (?::uuid, ?::uuid, 'from the image before', ?, ?, ?,
                        (SELECT number FROM worklist.workstream
                          WHERE tenant_id = ?::uuid AND scope_id = ?::uuid AND is_default))
                    RETURNING id
                    """)) {
                st.setString(1, tenant.toString());
                st.setString(2, scope.toString());
                st.setString(3, prose);
                st.setLong(4, status);
                st.setLong(5, number);
                st.setString(6, tenant.toString());
                st.setString(7, scope.toString());
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    id = UUID.fromString(rs.getString(1));
                }
            }
            c.commit();
        }

        Map<String, Object> read = items.read(scope, id);
        assertThat(read)
            .as("every field the item had reads as it was, its prose criteria included")
            .containsEntry("title", "from the image before")
            .containsEntry("description", prose)
            .containsEntry("status", "open")
            .containsEntry("number", number);
        assertThat(read)
            .as("and it carries no criteria: prose stays prose and is not migrated")
            .containsEntry("criteria", List.of());
        assertThat(storedRows(id)).as("no criterion row was made for it").isEmpty();
    }

    // ====================================================================
    // The store refuses a reissued number on its own
    // ====================================================================

    /**
     * The second line behind the conflict token: the unique index over every
     * row of an item, withdrawn ones included, refuses a second row with a
     * number the item already issued.
     */
    @Test
    void the_store_refuses_a_number_a_withdrawn_criterion_holds() throws SQLException {
        UUID item = create("its numbers are spent", List.of(
            Map.of("text", "one"), Map.of("text", "two")));
        update(item, Map.of("criteria", List.of(
            Map.of("address", address(item, 1), "text", "one"))));
        long number = ((Number) items.read(scope, item).get("number")).longValue();
        UUID tenant = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);

        try (Connection c = Db.asService()) {
            Db.bindTenant(c, tenant);
            assertThatThrownBy(() -> {
                try (var st = c.prepareStatement("""
                        INSERT INTO worklist.item_criterion
                            (tenant_id, scope_id, item_number, number, text, created_at, updated_at)
                        VALUES (?::uuid, ?::uuid, ?, 2, 'reissued', now(), now())
                        """)) {
                    st.setString(1, tenant.toString());
                    st.setString(2, scope.toString());
                    st.setLong(3, number);
                    st.executeUpdate();
                }
            })
                .as("criterion 2 was withdrawn and its number is spent")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_item_criterion_number");
            c.rollback();
        }
    }

    // ====================================================================
    // Fixtures
    // ====================================================================

    private UUID create(String title, List<Map<String, Object>> criteria) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("title", title);
        arguments.put("status", "open");
        if (!criteria.isEmpty()) {
            arguments.put("criteria", criteria);
        }
        return (UUID) items.create(scope, CALLER, arguments).answer().get("id");
    }

    /** An update of these fields, with the item's current token. */
    private Map<String, Object> update(UUID item, Map<String, Object> fields) {
        Map<String, Object> arguments = new LinkedHashMap<>(fields);
        arguments.put("conflict_token", tokenOf(item));
        return items.update(scope, item, arguments).answer();
    }

    /** The address of criterion {@code k} of an item, in the form D14 fixes. */
    private String address(UUID item, long k) {
        Map<String, Object> read = items.read(scope, item);
        return "worklist://" + read.get("scope") + "/item/" + read.get("number")
            + "/criterion/" + k;
    }

    /** The entry an item answers for criterion {@code k}: its address and its text. */
    private Map<String, Object> criterion(UUID item, long k, String text) {
        return Map.of("address", address(item, k), "text", text);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> criteriaOf(Map<String, Object> answer) {
        return (List<Map<String, Object>>) answer.get("criteria");
    }

    private String tokenOf(UUID item) {
        return String.valueOf(items.read(scope, item).get("conflict_token"));
    }

    private void assertNothingWritten(UUID item, Map<String, Object> before) {
        Map<String, Object> after = items.read(scope, item);
        assertThat(after.get("criteria")).as("the criteria stand").isEqualTo(before.get("criteria"));
        assertThat(after.get("title")).as("the title stands").isEqualTo(before.get("title"));
        assertThat(after.get("conflict_token"))
            .as("and the token did not move")
            .isEqualTo(before.get("conflict_token"));
    }

    /**
     * Every criterion row of an item as {@code "<number> <status> <text>"}, by
     * number, read under the service role bound to the tenant.
     */
    private List<String> storedRows(UUID item) throws SQLException {
        long number = ((Number) items.read(scope, item).get("number")).longValue();
        List<String> rows = new ArrayList<>();
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, UUID.fromString(SubstrateDatabaseResource.TENANT_ID));
            try (var st = c.prepareStatement("""
                    SELECT number, status, text FROM worklist.item_criterion
                    WHERE scope_id = ?::uuid AND item_number = ? ORDER BY number
                    """)) {
                st.setString(1, scope.toString());
                st.setLong(2, number);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        rows.add(rs.getLong(1) + " " + rs.getString(2) + " " + rs.getString(3));
                    }
                }
            }
            c.rollback();
        }
        return rows;
    }

    private static WorklistException refusalFrom(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }
}
