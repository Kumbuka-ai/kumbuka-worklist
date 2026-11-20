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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The fulfilment ledger of an item and its booking call, against the running
 * database under the service's own role.
 *
 * <p>Each case names the acceptance criterion of the order it covers, K1 to
 * K5 and K8. The expectations are written here from the order: the address
 * form {@code worklist://<scope>/item/<n>/criterion/<k>}, the three statuses
 * {@code met}, {@code not_met} and {@code not_applicable}, the member names of
 * an entry, and the instance addresses and evidence references are literals of
 * this class, never read from the service.
 *
 * <p>Every case works in a scope of its own. A probe scope is not published
 * to the platform's directory, so the scope position of an address carries
 * the scope's id, as it does for every address of such a scope.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class FulfilmentLedgerIT {

    private static final String CALLER = "subject-of-the-booking-caller";
    private static final String INSTANCE = "https://instances.example/run/41";
    private static final String OTHER_INSTANCE = "https://instances.example/run/42";

    private UUID scope;

    @Inject ItemService items;
    @Inject FulfilmentService ledger;
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
    // K1: two entries are recorded and read back
    // ====================================================================

    @Test
    void k1_a_booking_of_two_entries_is_recorded_and_reads_back() throws SQLException {
        UUID item = create("booked twice", 2);

        Map<String, Object> answered = book(item, INSTANCE, List.of(
            entry(1, "met", "report of criterion 1"),
            entry(address(item, 2), "not_met", "report of criterion 2")));

        List<Map<String, Object>> read = fulfilmentOf(items.read(scope, item));
        assertThat(read).as("the answer of book carries the ledger as read does")
            .isEqualTo(fulfilmentOf(answered));
        assertThat(read).hasSize(2);
        assertThat(read.get(0))
            .containsEntry("criterion", address(item, 1))
            .containsEntry("instance", INSTANCE)
            .containsEntry("status", "met")
            .containsEntry("evidence", "report of criterion 1")
            .containsEntry("actor", CALLER)
            .containsEntry("after_close", false);
        assertThat(read.get(0).get("booked_at")).as("the time of the booking").isNotNull();
        assertThat(read.get(1))
            .containsEntry("criterion", address(item, 2))
            .containsEntry("instance", INSTANCE)
            .containsEntry("status", "not_met")
            .containsEntry("evidence", "report of criterion 2")
            .containsEntry("actor", CALLER)
            .containsEntry("after_close", false);

        assertThat(storedRows(item))
            .as("one row per entry, under the actor the service was handed")
            .containsExactly(
                "1 " + INSTANCE + " met report of criterion 1 " + CALLER,
                "2 " + INSTANCE + " not_met report of criterion 2 " + CALLER);
    }

    @Test
    void k1_an_item_without_a_booking_answers_an_empty_ledger() {
        UUID item = create("never booked", 1);

        assertThat(items.read(scope, item).get("fulfilment"))
            .as("an empty list, as the item answers criteria it has none of")
            .isEqualTo(List.of());
        assertThat(items.read(scope, item, ItemService.Ledger.ALL).get("fulfilment"))
            .isEqualTo(List.of());
    }

    // ====================================================================
    // K2: a second booking is a new entry, and the latest holds
    // ====================================================================

    @Test
    void k2_a_second_booking_adds_an_entry_the_read_answers_the_latest_and_the_first_stands() {
        UUID item = create("booked again", 2);
        book(item, INSTANCE, List.of(entry(1, "not_met", "first run"),
            entry(2, "met", "first run")));
        Map<String, Object> firstOfCriterion1 = fulfilmentOf(items.read(scope, item)).get(0);

        book(item, INSTANCE, List.of(entry(1, "met", "second run")));

        List<Map<String, Object>> latest = fulfilmentOf(items.read(scope, item));
        assertThat(latest).as("per criterion and instance the latest entry").hasSize(2);
        assertThat(latest.get(0))
            .containsEntry("criterion", address(item, 1))
            .containsEntry("status", "met")
            .containsEntry("evidence", "second run");
        assertThat(latest.get(1))
            .containsEntry("criterion", address(item, 2))
            .containsEntry("status", "met")
            .containsEntry("evidence", "first run");

        List<Map<String, Object>> whole =
            fulfilmentOf(items.read(scope, item, ItemService.Ledger.ALL));
        assertThat(whole).as("the option answers every entry, in booking order").hasSize(3);
        assertThat(whole.get(0))
            .as("the first entry for criterion 1 stands as it was booked")
            .isEqualTo(firstOfCriterion1);
        assertThat(whole.get(2))
            .containsEntry("criterion", address(item, 1))
            .containsEntry("status", "met")
            .containsEntry("evidence", "second run");
    }

    @Test
    void k2_two_instances_on_one_criterion_each_answer_their_latest() {
        UUID item = create("two instances", 1);
        book(item, INSTANCE, List.of(entry(1, "met", "from the first")));
        book(item, OTHER_INSTANCE, List.of(entry(1, "not_applicable", "from the second")));

        List<Map<String, Object>> latest = fulfilmentOf(items.read(scope, item));
        assertThat(latest).extracting(e -> e.get("instance") + " " + e.get("status"))
            .as("per criterion and instance: both instances, each with its own entry")
            .containsExactly(INSTANCE + " met", OTHER_INSTANCE + " not_applicable");
    }

    // ====================================================================
    // K3: a closed item takes a booking, marked as booked after closing
    // ====================================================================

    @Test
    void k3_a_closed_item_takes_a_booking_and_only_the_entry_after_close_is_marked() {
        UUID item = create("closed, then booked", 2);
        book(item, INSTANCE, List.of(entry(1, "met", "before closing")));

        Map<String, Object> closed = update(item, Map.of("status", "done"));
        assertThat(closed.get("close_date")).as("the item closed").isNotNull();
        assertThat(closed.get("status")).isEqualTo("done");

        book(item, INSTANCE, List.of(entry(2, "met", "after closing")));

        List<Map<String, Object>> latest = fulfilmentOf(items.read(scope, item));
        assertThat(latest.get(0))
            .containsEntry("evidence", "before closing")
            .as("booked before the close date: unmarked")
            .containsEntry("after_close", false);
        assertThat(latest.get(1))
            .containsEntry("evidence", "after closing")
            .as("booked after the close date: marked")
            .containsEntry("after_close", true);
    }

    @Test
    void k3_the_mark_follows_the_current_close_date_and_is_stored_nowhere() throws SQLException {
        UUID item = create("closed and reopened", 1);
        update(item, Map.of("status", "done"));
        book(item, INSTANCE, List.of(entry(1, "met", "after the first close")));
        assertThat(fulfilmentOf(items.read(scope, item)).get(0))
            .containsEntry("after_close", true);

        update(item, Map.of("status", "open"));
        assertThat(fulfilmentOf(items.read(scope, item)).get(0))
            .as("reopened, the item carries no close date, and no entry is marked")
            .containsEntry("after_close", false);

        update(item, Map.of("status", "done"));
        assertThat(fulfilmentOf(items.read(scope, item)).get(0))
            .as("closed again later, the entry lies before the close date it carries now")
            .containsEntry("after_close", false);

        assertThat(columnsOfTheLedger())
            .as("no column holds the mark: it is derived from the two times")
            .containsExactly("pk", "tenant_id", "scope_id", "item_number", "criterion_number",
                "instance", "status", "evidence", "actor", "booked_at");
    }

    // ====================================================================
    // K4: a booking outside its form is refused whole; a withdrawn
    // identifier is taken
    // ====================================================================

    /**
     * RED PROBE: with the check of the identifier taking every whole number
     * from 1, the first case below -- 9, never issued, beside a valid entry
     * for 1 -- is booked, and the case turns red on the refusal and on the
     * row count.
     */
    @Test
    void k4_every_defect_refuses_the_booking_and_writes_none_of_its_entries()
            throws SQLException {
        UUID item = create("refused bookings", 2);
        Map<String, Object> before = items.read(scope, item);

        Map<String, List<Object>> cases = new LinkedHashMap<>();
        cases.put("an identifier the item never issued, beside a valid one",
            List.of(INSTANCE, List.of(entry(1, "met", "valid"), entry(9, "met", "never"))));
        cases.put("a status outside the three",
            List.of(INSTANCE, List.of(entry(1, "met", "valid"), entry(2, "done", "x"))));
        cases.put("an empty list", List.of(INSTANCE, List.of()));
        cases.put("the same identifier twice",
            List.of(INSTANCE, List.of(entry(1, "met", "a"), entry(address(item, 1), "not_met",
                "b"))));
        cases.put("an empty evidence reference",
            List.of(INSTANCE, List.of(entry(1, "met", "valid"), entry(2, "met", "  "))));
        cases.put("no evidence reference",
            List.of(INSTANCE, List.of(entry(1, "met", "valid"),
                Map.of("criterion", 2, "status", "met"))));

        for (Map.Entry<String, List<Object>> refused : cases.entrySet()) {
            WorklistException refusal = refusalFrom(() -> ledger.book(scope, item,
                refused.getValue().get(0), refused.getValue().get(1), CALLER));
            assertThat(refusal.reason()).as(refused.getKey())
                .isEqualTo(WorklistException.Reason.INVALID_VALUE);
            assertThat(refusal.offenders()).as(refused.getKey()).containsExactly("entries");
        }

        for (Object instance : Arrays.asList("", "   ", null)) {
            WorklistException refusal = refusalFrom(() -> ledger.book(scope, item, instance,
                List.of(entry(1, "met", "valid")), CALLER));
            assertThat(refusal.reason()).as("instance '%s'", instance)
                .isEqualTo(WorklistException.Reason.INVALID_VALUE);
            assertThat(refusal.offenders()).containsExactly("instance");
        }

        assertThat(storedRows(item))
            .as("no entry of any refused booking was written, the valid ones included")
            .isEmpty();
        Map<String, Object> after = items.read(scope, item);
        assertThat(after.get("conflict_token")).isEqualTo(before.get("conflict_token"));
        assertThat(after.get("fulfilment")).isEqualTo(List.of());
    }

    @Test
    void k4_a_booking_on_a_withdrawn_identifier_is_taken() throws SQLException {
        UUID item = create("a criterion withdrawn", 2);
        update(item, Map.of("criteria", List.of(
            Map.of("address", address(item, 1), "text", "criterion 1"))));
        assertThat(items.read(scope, item).get("criteria"))
            .as("criterion 2 is withdrawn")
            .isEqualTo(List.of(Map.of("address", address(item, 1), "text", "criterion 1")));

        book(item, INSTANCE, List.of(entry(2, "met", "the instance's copy named 2")));

        assertThat(fulfilmentOf(items.read(scope, item)))
            .extracting(e -> e.get("criterion"))
            .as("the contract outlives the criterion")
            .containsExactly(address(item, 2));
        assertThat(storedRows(item)).hasSize(1);
    }

    @Test
    void k4_an_item_of_another_scope_is_unknown_here() {
        UUID item = create("in this scope", 1);
        UUID elsewhere = UUID.randomUUID();
        selectors.open(elsewhere, Selector.ITEM);

        assertThat(refusalFrom(() -> ledger.book(elsewhere, item, INSTANCE,
                List.of(entry(1, "met", "x")), CALLER)).reason())
            .isEqualTo(WorklistException.Reason.ITEM_UNKNOWN);
    }

    // ====================================================================
    // K5: book writes the ledger and not the item
    // ====================================================================

    @Test
    void k5_book_leaves_the_conflict_token_and_the_change_time_of_the_item() {
        UUID item = create("token stays", 1);
        Map<String, Object> before = items.read(scope, item);

        Map<String, Object> answered = book(item, INSTANCE, List.of(entry(1, "met", "r")));

        assertThat(answered.get("conflict_token")).isEqualTo(before.get("conflict_token"));
        assertThat(answered.get("changed_at")).isEqualTo(before.get("changed_at"));
        Map<String, Object> after = items.read(scope, item);
        assertThat(after.get("conflict_token"))
            .as("the token read before the booking is the token now")
            .isEqualTo(before.get("conflict_token"));
        assertThat(after.get("changed_at")).isEqualTo(before.get("changed_at"));

        update(item, Map.of("status", "done"));
        Map<String, Object> closed = items.read(scope, item);
        book(item, INSTANCE, List.of(entry(1, "not_met", "after")));
        assertThat(items.read(scope, item).get("conflict_token"))
            .as("and on a closed item as well")
            .isEqualTo(closed.get("conflict_token"));
    }

    @Test
    void k5_a_read_answer_sent_back_with_its_ledger_in_either_form_changes_nothing() {
        UUID item = create("echoed", 1);
        book(item, INSTANCE, List.of(entry(1, "not_met", "first")));
        book(item, INSTANCE, List.of(entry(1, "met", "second")));
        Map<String, Object> read = items.read(scope, item);
        Map<String, Object> whole = items.read(scope, item, ItemService.Ledger.ALL);

        for (Map<String, Object> answer : List.of(read, whole)) {
            Map<String, Object> echoed = items.update(scope, item, Map.of(
                "conflict_token", answer.get("conflict_token"),
                "fulfilment", answer.get("fulfilment"))).answer();
            assertThat(echoed.get("conflict_token"))
                .as("the ledger sent back as a read answered it is accepted and writes nothing")
                .isEqualTo(read.get("conflict_token"));
        }

        WorklistException refusal = refusalFrom(() -> items.update(scope, item, Map.of(
            "conflict_token", read.get("conflict_token"), "fulfilment", List.of())));
        assertThat(refusal.reason())
            .as("a ledger of another value is a read-only field set: refused")
            .isEqualTo(WorklistException.Reason.FIELD_NOT_SETTABLE);
        assertThat(refusal.offenders()).containsExactly("fields.fulfilment");
    }

    // ====================================================================
    // K8: an item from before the step reads unchanged, with an empty ledger
    // ====================================================================

    @Test
    void k8_an_item_inserted_as_the_previous_image_does_reads_back_with_an_empty_ledger()
            throws SQLException {
        create("so the default workstream stands", 0);
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
            .containsEntry("number", number)
            .containsEntry("criteria", List.of())
            .as("and the ledger is empty: nothing is filled retroactively")
            .containsEntry("fulfilment", List.of());
    }

    // ====================================================================
    // Fixtures
    // ====================================================================

    /** An item with criteria 1 to {@code count}. */
    private UUID create(String title, int count) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("title", title);
        arguments.put("status", "open");
        if (count > 0) {
            List<Map<String, Object>> criteria = new ArrayList<>();
            for (int k = 1; k <= count; k++) {
                criteria.add(Map.of("text", "criterion " + k));
            }
            arguments.put("criteria", criteria);
        }
        return (UUID) items.create(scope, CALLER, arguments).answer().get("id");
    }

    private Map<String, Object> book(UUID item, String instance, List<Map<String, Object>> entries) {
        return ledger.book(scope, item, instance, entries, CALLER).answer();
    }

    private static Map<String, Object> entry(Object criterion, String status, String evidence) {
        return Map.of("criterion", criterion, "status", status, "evidence", evidence);
    }

    /** An update of these fields, with the item's current token. */
    private Map<String, Object> update(UUID item, Map<String, Object> fields) {
        Map<String, Object> arguments = new LinkedHashMap<>(fields);
        arguments.put("conflict_token", items.read(scope, item).get("conflict_token"));
        return items.update(scope, item, arguments).answer();
    }

    /** The address of criterion {@code k} of an item, in the form the item answers. */
    private String address(UUID item, long k) {
        Map<String, Object> read = items.read(scope, item);
        return "worklist://" + read.get("scope") + "/item/" + read.get("number")
            + "/criterion/" + k;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fulfilmentOf(Map<String, Object> answer) {
        return (List<Map<String, Object>>) answer.get("fulfilment");
    }

    /**
     * Every ledger row of an item as
     * {@code "<criterion> <instance> <status> <evidence> <actor>"}, in booking
     * order, read under the service role bound to the tenant.
     */
    private List<String> storedRows(UUID item) throws SQLException {
        long number = ((Number) items.read(scope, item).get("number")).longValue();
        List<String> rows = new ArrayList<>();
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, UUID.fromString(SubstrateDatabaseResource.TENANT_ID));
            try (var st = c.prepareStatement("""
                    SELECT criterion_number, instance, status, evidence, actor
                    FROM worklist.item_fulfilment
                    WHERE scope_id = ?::uuid AND item_number = ? ORDER BY booked_at, pk
                    """)) {
                st.setString(1, scope.toString());
                st.setLong(2, number);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        rows.add(rs.getLong(1) + " " + rs.getString(2) + " " + rs.getString(3)
                            + " " + rs.getString(4) + " " + rs.getString(5));
                    }
                }
            }
            c.rollback();
        }
        return rows;
    }

    /** The columns of the ledger table, in their order, from the catalog. */
    private static List<String> columnsOfTheLedger() throws SQLException {
        List<String> columns = new ArrayList<>();
        try (Connection c = Db.asAdmin();
             var st = c.prepareStatement("""
                 SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'worklist' AND table_name = 'item_fulfilment'
                 ORDER BY ordinal_position
                 """);
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
        }
        return columns;
    }

    private static WorklistException refusalFrom(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }
}
