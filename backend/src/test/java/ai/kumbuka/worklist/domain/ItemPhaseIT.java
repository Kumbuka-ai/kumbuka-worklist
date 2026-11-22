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
 * The phase of an item and its transitions, against the running database
 * under the service's own role (REA-0011 S9; D1, D6, D11, D12; TAR-0002 B1,
 * B3, B18 to B20).
 *
 * <p>Each case names the acceptance criterion of the order it covers. The
 * expectations are written here from those nodes -- the four phases, which
 * transition opens where, which write a closed item refuses, the status a
 * transition sets -- and none is read from the service.
 *
 * <p>Every case works in a scope of its own, with the statuses of the concept's
 * example binding (section 9): {@code intake} and {@code planned} in the phase
 * open, the first not actionable, {@code in_progress} in the phase in
 * progress, {@code done} and {@code dropped} in the phase closed.
 *
 * <h2>The red probe</h2>
 *
 * With {@code update} taking a status of another phase -- in
 * {@code ItemService.applyStatus}, the comparison against
 * {@code item.phase().statusPhase()} removed -- and {@code create} storing the
 * item in the phase open -- {@code item.enter(Phase.DRAFT)} replaced by
 * {@code Phase.OPEN} -- {@link #k7_an_update_naming_a_status_of_another_phase_is_refused_by_state}
 * turns red at its first {@code refusalFrom}, where the write is accepted and
 * nothing is thrown, and {@link #k1_a_created_item_reads_back_as_a_draft_and_is_open_after_submit}
 * at its first assertion, which reads {@code open} where {@code draft} is
 * expected.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ItemPhaseIT {

    private static final UUID TENANT = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);

    private UUID scope;

    @Inject ItemService items;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject TagService tags;
    @Inject FulfilmentService fulfilment;

    @BeforeEach
    void aScopeOfItsOwn() {
        scope = UUID.randomUUID();
        selectors.open(scope, Selector.ITEM);
        // name, rank, actionable, in_progress, closed, successful
        vocabulary.declareStatus(scope, "intake", 1, false, false, false, false);
        vocabulary.declareStatus(scope, "planned", 2, true, false, false, false);
        vocabulary.declareStatus(scope, "in_progress", 3, true, true, false, false);
        vocabulary.declareStatus(scope, "done", 4, false, false, true, true);
        vocabulary.declareStatus(scope, "dropped", 5, false, false, true, false);
        vocabulary.declareRelationType(scope, "relates", false, 1);
    }

    // ====================================================================
    // K1: create makes a draft, and submit makes it open
    // ====================================================================

    @Test
    void k1_a_created_item_reads_back_as_a_draft_and_is_open_after_submit() {
        UUID item = ItemLifecycle.draft(items, scope, Map.of("title", "called in"));

        Map<String, Object> draft = items.read(scope, item);
        assertThat(draft.get("phase")).as("create makes a draft (D12)").isEqualTo("draft");
        assertThat(draft.get("status"))
            .as("a draft carries the first status of the phase open, by rank")
            .isEqualTo("intake");
        assertThat(predicates(draft))
            .as("a draft is neither closed nor in progress")
            .containsEntry("closed", false)
            .containsEntry("in_progress", false);

        Map<String, Object> submitted = ItemLifecycle.submit(items, scope, item);
        assertThat(submitted.get("phase")).isEqualTo("open");
        assertThat(submitted.get("status")).as("submit keeps the status").isEqualTo("intake");
        assertThat(items.read(scope, item).get("phase")).isEqualTo("open");
    }

    @Test
    void k1_a_draft_takes_the_status_it_names_where_it_belongs_to_the_phase_open() {
        UUID planned = ItemLifecycle.draft(items, scope,
            Map.of("title", "named open", "status", "planned"));
        assertThat(items.read(scope, planned).get("status")).isEqualTo("planned");

        UUID named = ItemLifecycle.draft(items, scope,
            Map.of("title", "named closed", "status", "done"));
        Map<String, Object> read = items.read(scope, named);
        assertThat(read.get("phase")).isEqualTo("draft");
        assertThat(read.get("status"))
            .as("a status of another phase falls back to the first of the phase open")
            .isEqualTo("intake");
    }

    @Test
    void k1_submit_is_refused_on_an_item_that_is_no_draft() {
        UUID item = ItemLifecycle.open(items, scope, Map.of("title", "open already"));

        WorklistException refusal = refusalFrom(() ->
            items.submit(scope, item, ItemLifecycle.tokenOf(items, scope, item)));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
    }

    // ====================================================================
    // K3: start is refused by state without actionable or without a type
    // ====================================================================

    @Test
    void k3_start_is_refused_where_the_status_is_not_actionable() {
        UUID item = ItemLifecycle.open(items, scope, Map.of("title", "raw", "status", "intake"));
        ItemLifecycle.typed(items, vocabulary, scope, item);
        Map<String, Object> before = items.read(scope, item);

        WorklistException refusal = refusalFrom(() -> ItemLifecycle.start(items, scope, item, null));
        assertThat(refusal.reason())
            .as("intake does not answer actionable (TAR-0002 B18)")
            .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertThat(items.read(scope, item)).as("nothing was written").isEqualTo(before);
    }

    @Test
    void k3_start_is_refused_where_the_item_carries_no_type() {
        UUID item = ItemLifecycle.open(items, scope,
            Map.of("title", "untyped", "status", "planned"));

        WorklistException refusal = refusalFrom(() -> ItemLifecycle.start(items, scope, item, null));
        assertThat(refusal.reason())
            .as("an item without a type is not started (TAR-0002 B18)")
            .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertThat(items.read(scope, item).get("phase")).isEqualTo("open");
    }

    @Test
    void k3_an_actionable_typed_item_starts_into_the_first_status_of_the_phase_in_progress() {
        UUID item = ItemLifecycle.open(items, scope,
            Map.of("title", "ready", "status", "planned"));
        ItemLifecycle.typed(items, vocabulary, scope, item);

        Map<String, Object> started = ItemLifecycle.start(items, scope, item, null);
        assertThat(started.get("phase")).isEqualTo("in_progress");
        assertThat(started.get("status")).isEqualTo("in_progress");
        assertThat(predicates(started))
            .as("in_progress holds exactly in the phase in progress (TAR-0002 B3)")
            .containsEntry("in_progress", true)
            .containsEntry("closed", false);
    }

    @Test
    void a_transition_naming_a_status_of_another_phase_is_refused_naming_it() {
        UUID item = ItemLifecycle.open(items, scope,
            Map.of("title", "ready", "status", "planned"));
        ItemLifecycle.typed(items, vocabulary, scope, item);

        WorklistException refusal = refusalFrom(() ->
            ItemLifecycle.start(items, scope, item, "done"));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly("status");

        WorklistException undeclared = refusalFrom(() ->
            ItemLifecycle.start(items, scope, item, "nowhere"));
        assertThat(undeclared.reason()).isEqualTo(WorklistException.Reason.VALUE_UNDECLARED);
        assertThat(undeclared.offenders()).containsExactly("status");
        assertThat(items.read(scope, item).get("phase")).isEqualTo("open");
    }

    // ====================================================================
    // K4: a draft without a title is not stored, so none is submitted
    // ====================================================================

    @Test
    void k4_a_create_without_a_title_is_refused_and_stores_no_draft() {
        WorklistException refusal = refusalFrom(() ->
            items.create(scope, ItemLifecycle.CALLER, Map.of("status", "planned")));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly("fields.title");
        assertThat(rowsOf(scope)).as("no draft was stored").isZero();
    }

    // ====================================================================
    // K5: a closed item takes no write but a booking
    // ====================================================================

    @Test
    void k5_a_closed_item_refuses_every_write_and_takes_a_booking() {
        UUID other = ItemLifecycle.open(items, scope, Map.of("title", "the other end"));
        UUID item = ItemLifecycle.open(items, scope, Map.of("title", "to close",
            "criteria", List.of(Map.of("text", "the criterion a booking reports on"))));
        items.relate(scope, item, addressOf(other), "relates",
            ItemLifecycle.tokenOf(items, scope, item));

        Map<String, Object> closed = ItemLifecycle.close(items, scope, item, "done");
        assertThat(closed.get("phase")).isEqualTo("closed");
        assertThat(closed.get("close_date")).as("close sets the close date").isNotNull();
        String token = String.valueOf(closed.get("conflict_token"));

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("conflict_token", token);
        update.put("title", "renamed after the close");
        assertByState(() -> items.update(scope, item, update), "update");
        assertByState(() -> items.relate(scope, item, addressOf(other), "relates", token),
            "relate");
        assertByState(() -> items.unrelate(scope, item, addressOf(other), "relates", token),
            "unrelate");
        assertByState(() -> tags.tagAttach(scope, item, "any-tag", ItemLifecycle.CALLER),
            "tag_attach");
        assertByState(() -> tags.tagDetach(scope, item, "any-tag"), "tag_detach");
        assertByState(() -> items.close(scope, item, token, null, "dropped"),
            "close, a second time: nothing reopens or moves a closed item");
        assertThat(items.read(scope, item))
            .as("the refused writes wrote nothing")
            .containsEntry("title", "to close")
            .containsEntry("conflict_token", token);

        Map<String, Object> booked = fulfilment.book(scope, item, "https://instances.example/run/1",
            List.of(Map.of("criterion", 1, "status", "met", "evidence", "a report")),
            ItemLifecycle.CALLER).answer();
        assertThat((List<?>) booked.get("fulfilment"))
            .as("book stays open on a closed item (S13, TAR-0002 B21)")
            .hasSize(1);
    }

    @Test
    void a_draft_takes_no_booking() {
        UUID item = ItemLifecycle.draft(items, scope, Map.of("title", "a draft",
            "criteria", List.of(Map.of("text", "a criterion"))));

        assertByState(() -> fulfilment.book(scope, item, "https://instances.example/run/1",
                List.of(Map.of("criterion", 1, "status", "met", "evidence", "a report")),
                ItemLifecycle.CALLER),
            "book on a draft (Annex A1)");
        assertThat((List<?>) items.read(scope, item).get("fulfilment"))
            .as("and nothing was booked")
            .isEmpty();
    }

    // ====================================================================
    // K7: update moves a status within its phase and never across
    // ====================================================================

    @Test
    void k7_an_update_naming_a_status_of_another_phase_is_refused_by_state() {
        UUID item = ItemLifecycle.open(items, scope, Map.of("title", "open", "status", "intake"));
        Map<String, Object> before = items.read(scope, item);

        for (String other : List.of("done", "dropped", "in_progress")) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("conflict_token", before.get("conflict_token"));
            fields.put("status", other);
            WorklistException refusal = refusalFrom(() -> items.update(scope, item, fields));
            assertThat(refusal.reason())
                .as("%s belongs to another phase; the phase moves through a transition (D11)",
                    other)
                .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        }
        assertThat(items.read(scope, item)).as("nothing was written").isEqualTo(before);

        Map<String, Object> within = new LinkedHashMap<>();
        within.put("conflict_token", before.get("conflict_token"));
        within.put("status", "planned");
        Map<String, Object> moved = items.update(scope, item, within).answer();
        assertThat(moved.get("status")).as("a status of the same phase is taken")
            .isEqualTo("planned");
        assertThat(moved.get("phase")).isEqualTo("open");
    }

    @Test
    void k7_a_draft_takes_a_status_of_the_phase_open_and_no_other() {
        UUID item = ItemLifecycle.draft(items, scope, Map.of("title", "a draft"));
        Map<String, Object> before = items.read(scope, item);

        Map<String, Object> closing = new LinkedHashMap<>();
        closing.put("conflict_token", before.get("conflict_token"));
        closing.put("status", "done");
        assertByState(() -> items.update(scope, item, closing), "a closed status on a draft");

        Map<String, Object> planned = new LinkedHashMap<>();
        planned.put("conflict_token", before.get("conflict_token"));
        planned.put("status", "planned");
        Map<String, Object> answer = items.update(scope, item, planned).answer();
        assertThat(answer.get("status")).isEqualTo("planned");
        assertThat(answer.get("phase")).isEqualTo("draft");
    }

    @Test
    void k7_the_phase_is_read_only_through_update() {
        UUID item = ItemLifecycle.open(items, scope, Map.of("title", "open"));
        Map<String, Object> read = items.read(scope, item);

        Map<String, Object> echoed = items.update(scope, item, read).answer();
        assertThat(echoed.get("conflict_token"))
            .as("the phase sent back as it came changes nothing")
            .isEqualTo(read.get("conflict_token"));

        Map<String, Object> changed = new LinkedHashMap<>(read);
        changed.put("phase", "closed");
        WorklistException refusal = refusalFrom(() -> items.update(scope, item, changed));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.FIELD_NOT_SETTABLE);
        assertThat(refusal.offenders()).containsExactly("fields.phase");
    }

    // ====================================================================
    // return and close out of the phase in progress take a reason
    // ====================================================================

    @Test
    void return_and_close_out_of_the_phase_in_progress_take_a_reason() {
        UUID item = ItemLifecycle.inProgress(items, vocabulary, scope,
            Map.of("title", "worked on", "status", "planned"), null);

        WorklistException noReason = refusalFrom(() -> items.close(scope, item,
            ItemLifecycle.tokenOf(items, scope, item), null, null));
        assertThat(noReason.reason()).isEqualTo(WorklistException.Reason.ARGUMENT_MISSING);
        assertThat(noReason.offenders()).containsExactly("reason");

        WorklistException blank = refusalFrom(() -> items.return_(scope, item,
            ItemLifecycle.tokenOf(items, scope, item), " ", null));
        assertThat(blank.reason()).isEqualTo(WorklistException.Reason.ARGUMENT_MISSING);

        Map<String, Object> returned = ItemLifecycle.returnToOpen(items, scope, item, "planned");
        assertThat(returned.get("phase")).isEqualTo("open");
        assertThat(returned.get("status")).isEqualTo("planned");

        WorklistException notInProgress = refusalFrom(() -> ItemLifecycle.returnToOpen(items,
            scope, item, null));
        assertThat(notInProgress.reason()).isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);

        ItemLifecycle.start(items, scope, item, null);
        Map<String, Object> closed = ItemLifecycle.close(items, scope, item, "finished",
            "dropped");
        assertThat(closed.get("phase")).isEqualTo("closed");
        assertThat(closed.get("status")).isEqualTo("dropped");
        assertThat(predicates(closed))
            .containsEntry("closed", true)
            .containsEntry("successful", false)
            .containsEntry("in_progress", false);
    }

    @Test
    void close_is_refused_on_a_draft() {
        UUID item = ItemLifecycle.draft(items, scope, Map.of("title", "a draft"));
        assertByState(() -> ItemLifecycle.close(items, scope, item, null), "close on a draft");
        assertThat(items.read(scope, item).get("phase")).isEqualTo("draft");
    }

    @Test
    void duplicate_of_closes_an_open_item_into_the_phase_closed() {
        vocabulary.declareRelationType(scope, RelationType.DUPLICATE_OF, false, 2, "dropped");
        UUID original = ItemLifecycle.open(items, scope, Map.of("title", "the original"));
        UUID duplicate = ItemLifecycle.open(items, scope, Map.of("title", "the duplicate"));

        Map<String, Object> closed = items.relate(scope, duplicate, addressOf(original),
            RelationType.DUPLICATE_OF, ItemLifecycle.tokenOf(items, scope, duplicate)).answer();
        assertThat(closed.get("phase")).isEqualTo("closed");
        assertThat(closed.get("status")).isEqualTo("dropped");
        assertThat(closed.get("close_date")).isNotNull();

        UUID draft = ItemLifecycle.draft(items, scope, Map.of("title", "a draft duplicate"));
        assertByState(() -> items.relate(scope, draft, addressOf(original),
                RelationType.DUPLICATE_OF, ItemLifecycle.tokenOf(items, scope, draft)),
            "duplicate_of on a draft, which closes from the phase open alone");
    }

    // ====================================================================
    // K8: discard deletes a draft, and the query lists old drafts
    // ====================================================================

    @Test
    void k8_discard_deletes_a_draft_and_its_number_stays_spent() throws SQLException {
        UUID keeper = ItemLifecycle.open(items, scope, Map.of("title", "keeps a relation"));
        UUID draft = ItemLifecycle.draft(items, scope, Map.of("title", "to discard",
            "criteria", List.of(Map.of("text", "a criterion")),
            "references", List.of(Map.of("target", "https://example.org/a"))));
        long number = ((Number) items.read(scope, draft).get("number")).longValue();
        items.relate(scope, keeper, addressOf(draft), "relates",
            ItemLifecycle.tokenOf(items, scope, keeper));
        String keeperToken = ItemLifecycle.tokenOf(items, scope, keeper);

        Map<String, Object> discarded = ItemLifecycle.discard(items, scope, draft);
        assertThat(discarded.get("phase")).as("the answer is the draft as it stood")
            .isEqualTo("draft");

        assertThat(catchThrowable(() -> items.read(scope, draft)))
            .as("nothing stands at the address any more")
            .isInstanceOf(WorklistException.class);
        assertThat(rowsNumbered(scope, number)).as("the row is deleted").isZero();
        Map<String, Object> pointing = items.read(scope, keeper);
        assertThat((List<?>) pointing.get("relations"))
            .as("the relation to the discarded draft went with it")
            .isEmpty();
        assertThat(pointing.get("conflict_token"))
            .as("and the item it pointed from moved its token")
            .isNotEqualTo(keeperToken);

        UUID next = ItemLifecycle.draft(items, scope, Map.of("title", "the next one"));
        assertThat(((Number) items.read(scope, next).get("number")).longValue())
            .as("the discarded draft's number is never handed out again (TAR-0002 B1)")
            .isGreaterThan(number);
    }

    @Test
    void k8_discard_is_refused_on_an_item_that_is_no_draft() {
        UUID item = ItemLifecycle.open(items, scope, Map.of("title", "open"));
        assertByState(() -> ItemLifecycle.discard(items, scope, item), "discard of an open item");
        assertThat(items.read(scope, item).get("phase")).isEqualTo("open");
    }

    @Test
    void k8_a_query_by_phase_and_age_lists_only_the_older_drafts() throws SQLException {
        UUID old = ItemLifecycle.draft(items, scope, Map.of("title", "an old draft"));
        UUID fresh = ItemLifecycle.draft(items, scope, Map.of("title", "a fresh draft"));
        UUID oldOpen = ItemLifecycle.open(items, scope, Map.of("title", "an old open item"));
        age(old, 10);
        age(oldOpen, 10);

        assertThat(titles(Map.of("phase", "draft", "older_than_days", 7)))
            .as("the drafts created more than seven days ago, and no item of another phase")
            .containsExactly("an old draft");
        assertThat(titles(Map.of("phase", "draft")))
            .containsExactlyInAnyOrder("an old draft", "a fresh draft");
        assertThat(titles(Map.of("phase", "open"))).containsExactly("an old open item");
        assertThat(fresh).isNotNull();
    }

    @Test
    void a_query_with_a_phase_outside_the_four_is_refused_as_a_form_fault() {
        WorklistException refusal = refusalFrom(() -> titles(Map.of("phase", "planned")));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.ARGUMENT_INVALID);
        assertThat(refusal.offenders()).containsExactly("filter.phase");

        WorklistException days = refusalFrom(() -> titles(Map.of("older_than_days", 0)));
        assertThat(days.reason()).isEqualTo(WorklistException.Reason.ARGUMENT_INVALID);
        assertThat(days.offenders()).containsExactly("filter.older_than_days");
    }

    // ====================================================================
    // Every status names its phase (TAR-0002 B2, B3), in the store
    // ====================================================================

    @Test
    void every_declared_status_names_its_phase_in_the_store() throws SQLException {
        assertThat(storedPhases(scope))
            .as("the column V33 generates: closed binds closed, in_progress binds in "
                + "progress, every other open; none binds draft")
            .containsExactlyInAnyOrderEntriesOf(Map.of(
                "intake", "open", "planned", "open", "in_progress", "in_progress",
                "done", "closed", "dropped", "closed"));
    }

    // ====================================================================
    // Fixtures
    // ====================================================================

    private String addressOf(UUID item) {
        Map<String, Object> read = items.read(scope, item);
        return "worklist://" + read.get("scope") + "/item/" + read.get("number");
    }

    private List<String> titles(Map<String, Object> filter) {
        PageRequest request = new QuerySpec(filter, QuerySpec.MAX_PAGE_SIZE)
            .pageFor(scope, Selector.ITEM);
        return items.query(scope, request).objects().stream()
            .map(object -> String.valueOf(object.get("title")))
            .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> predicates(Map<String, Object> answer) {
        return (Map<String, Object>) answer.get("predicates");
    }

    private static void assertByState(Runnable call, String what) {
        WorklistException refusal = refusalFrom(call);
        assertThat(refusal.reason())
            .as("%s is refused by state", what)
            .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
    }

    private static WorklistException refusalFrom(Runnable call) {
        Throwable thrown = catchThrowable(call::run);
        assertThat(thrown).isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }

    /** Moves an item's creation back by whole days, as if it had stood that long. */
    private static void age(UUID item, int days) throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, TENANT);
            try (var st = c.prepareStatement(
                    "UPDATE worklist.item SET created_at = created_at - make_interval(days => ?) "
                        + "WHERE id = ?")) {
                st.setInt(1, days);
                st.setObject(2, item);
                assertThat(st.executeUpdate()).isEqualTo(1);
            }
            c.commit();
        }
    }

    private static long rowsOf(UUID scope) {
        return count("SELECT count(*) FROM worklist.item WHERE scope_id = ?", scope, null);
    }

    private static long rowsNumbered(UUID scope, long number) {
        return count("SELECT count(*) FROM worklist.item WHERE scope_id = ? AND number = ?",
            scope, number);
    }

    private static long count(String sql, UUID scope, Long number) {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, TENANT);
            try (var st = c.prepareStatement(sql)) {
                st.setObject(1, scope);
                if (number != null) {
                    st.setLong(2, number);
                }
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    long found = rs.getLong(1);
                    c.rollback();
                    return found;
                }
            }
        } catch (SQLException failed) {
            throw new IllegalStateException(failed);
        }
    }

    private static Map<String, String> storedPhases(UUID scope) throws SQLException {
        Map<String, String> phases = new LinkedHashMap<>();
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, TENANT);
            try (var st = c.prepareStatement(
                    "SELECT name, phase FROM worklist.item_status WHERE scope_id = ?")) {
                st.setObject(1, scope);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        phases.put(rs.getString(1), rs.getString(2));
                    }
                }
            }
            c.rollback();
        }
        return phases;
    }
}
