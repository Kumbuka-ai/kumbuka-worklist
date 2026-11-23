/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.repository.ItemRepository;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * References and relations are cancelled, never deleted (REA-0011 S11, D15),
 * against the running database under the service's own role.
 *
 * <p>Each case names the acceptance criterion of the order it covers. The
 * expectations are written here from D15, D8, D18, Annex A1 and TAR-0002
 * Annex A4: which phase cancels, which only appends, which calls each phase
 * opens. Nothing is read from {@link FieldClass} or {@link OpenCallService}
 * to form an expectation, and the rows are read back through the repository,
 * so that "the row stays" is a statement about the store and not about the
 * projection.
 *
 * <p>Every case works in a scope of its own, with a status of each of the
 * phases open, in progress and closed, one relation type that blocks and one
 * that does not, and the attribute {@code type}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class CancelledEdgesIT {

    /** The subject that cancels, distinct from the one that created the items. */
    private static final String CANCELLER = "subject-of-the-canceller";
    private static final String OPEN = "open";
    private static final String DOING = "doing";
    private static final String DONE = "done";
    private static final String BLOCKS = "blocks";
    private static final String RELATES = "relates";
    private static final String WHY = "the dependency was a misreading of the design";

    @Inject ItemService items;
    @Inject ItemRepository rows;
    @Inject OpenCallService openCalls;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject WorkstreamService workstreams;

    private UUID scope;

    @BeforeEach
    void aScopeOfItsOwn() {
        scope = UUID.randomUUID();
        for (String view : Selector.VIEWS) {
            selectors.open(scope, view);
        }
        vocabulary.declareStatus(scope, OPEN, 1, true, false, false, false);
        vocabulary.declareStatus(scope, DOING, 2, true, true, false, false);
        vocabulary.declareStatus(scope, DONE, 3, false, false, true, true);
        vocabulary.declareRelationType(scope, BLOCKS, true, 1);
        vocabulary.declareRelationType(scope, RELATES, false, 2);
        vocabulary.declareAttribute(scope, "type", "Type", AttributeDefinition.TEXT, 1, false);
        workstreams.requireDefault(scope);
    }

    // ====================================================================
    // K1: unrelate on an open item keeps the row with its end
    // ====================================================================

    @Test
    void k1_unrelate_on_an_open_item_keeps_the_row_and_sets_its_end() {
        UUID item = open("the source");
        UUID other = open("the target");
        relate(item, other, RELATES);

        Map<String, Object> answer = items.unrelate(scope, item, addressOf(other), RELATES, WHY,
            CANCELLER, tokenOf(item)).answer();

        assertThat(relations(answer)).as("the cancelled edge is no relation any more").isEmpty();
        assertThat(answer.get("cancelled_relations_count")).as("but it is counted").isEqualTo(1);

        List<ItemRelation> stored = rows.edgesOf(stored(item));
        assertThat(stored).as("the row stays: cancelled, never deleted").hasSize(1);
        ItemRelation edge = stored.get(0);
        assertThat(edge.status).isEqualTo("withdrawn");
        assertThat(edge.endedAt).as("the end time, by the service's clock").isNotNull();
        assertThat(edge.endedBy).as("the caller, derived by the service").isEqualTo(CANCELLER);
        assertThat(edge.endReason).isEqualTo(WHY);

        Map<String, Object> plain = items.read(scope, item);
        assertThat(relations(plain)).isEmpty();
        assertThat(plain.get("cancelled_relations_count")).isEqualTo(1);
        assertThat(plain).as("without the option the entries are not answered")
            .doesNotContainKey("cancelled_relations");

        Map<String, Object> all = items.read(scope, item, ItemService.Ledger.LATEST,
            ItemService.Cancelled.ALL);
        assertThat(relations(all)).as("the option keeps the active list as it is").isEmpty();
        List<Map<String, Object>> cancelled = list(all, "cancelled_relations");
        assertThat(cancelled).hasSize(1);
        assertThat(cancelled.get(0))
            .containsEntry("type", RELATES)
            .containsEntry("item", addressOf(other))
            .containsEntry("ended_by", CANCELLER)
            .containsEntry("end_reason", WHY)
            .containsKey("ended_at");
    }

    @Test
    void k1_unrelate_on_a_draft_is_taken_as_well() {
        UUID item = draft("a draft");
        UUID other = open("the target");
        relate(item, other, RELATES);

        Map<String, Object> answer = items.unrelate(scope, item, addressOf(other), RELATES, WHY,
            CANCELLER, tokenOf(item)).answer();

        assertThat(relations(answer)).isEmpty();
        assertThat(answer.get("cancelled_relations_count")).isEqualTo(1);
    }

    @Test
    void k1_an_unknown_or_already_cancelled_edge_answers_relation_unknown() {
        UUID item = open("the source");
        UUID other = open("the target");

        assertThat(refusal(() -> items.unrelate(scope, item, addressOf(other), RELATES, WHY,
                CANCELLER, tokenOf(item))).reason())
            .as("never asserted").isEqualTo(WorklistException.Reason.RELATION_UNKNOWN);

        relate(item, other, RELATES);
        items.unrelate(scope, item, addressOf(other), RELATES, WHY, CANCELLER, tokenOf(item));
        assertThat(refusal(() -> items.unrelate(scope, item, addressOf(other), RELATES, WHY,
                CANCELLER, tokenOf(item))).reason())
            .as("already cancelled").isEqualTo(WorklistException.Reason.RELATION_UNKNOWN);
    }

    // ====================================================================
    // K2: unrelate in progress is refused by state, and not named there
    // ====================================================================

    @Test
    void k2_unrelate_on_an_item_in_progress_is_refused_by_state_and_the_edge_stays_active() {
        UUID other = open("the target");
        UUID item = open("the source");
        relate(item, other, RELATES);
        ItemLifecycle.typed(items, vocabulary, scope, item);
        ItemLifecycle.start(items, scope, item, DOING);
        Map<String, Object> before = items.read(scope, item);

        WorklistException refusal = refusal(() -> items.unrelate(scope, item, addressOf(other),
            RELATES, WHY, CANCELLER, tokenOf(item)));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertThat(items.read(scope, item)).as("nothing is written").isEqualTo(before);
        assertThat(relations(items.read(scope, item))).as("the edge stays active").hasSize(1);
        assertThat(rows.edgesOf(stored(item)).get(0).status).isEqualTo("asserted");
        assertThat(openVerbs(item))
            .as("the refusal names the open calls: unrelate is none of them, return is the "
                + "way out")
            .doesNotContain("unrelate")
            .contains("return", "relate");
    }

    @Test
    void k2_the_open_calls_name_unrelate_in_draft_and_open_and_never_in_progress_or_closed() {
        UUID item = draft("through the phases");
        assertThat(openVerbs(item)).as("draft").contains("unrelate", "relate");
        ItemLifecycle.submit(items, scope, item);
        assertThat(openVerbs(item)).as("open").contains("unrelate", "relate");
        ItemLifecycle.typed(items, vocabulary, scope, item);
        ItemLifecycle.start(items, scope, item, DOING);
        assertThat(openVerbs(item)).as("in_progress").contains("relate")
            .doesNotContain("unrelate");
        ItemLifecycle.close(items, scope, item, "finished", DONE);
        assertThat(openVerbs(item)).as("closed").doesNotContain("unrelate", "relate");
    }

    @Test
    void k2_unrelate_on_a_closed_item_is_refused_by_state() {
        UUID other = open("the target");
        UUID item = open("the source");
        relate(item, other, RELATES);
        ItemLifecycle.close(items, scope, item, DONE);

        assertThat(refusal(() -> items.unrelate(scope, item, addressOf(other), RELATES, WHY,
                CANCELLER, tokenOf(item))).reason())
            .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertThat(relations(items.read(scope, item))).hasSize(1);
    }

    // ====================================================================
    // K3: in progress an update only appends
    // ====================================================================

    @Test
    void k3_an_update_in_progress_that_leaves_out_an_active_relation_is_refused_and_writes_nothing() {
        UUID other = open("the target");
        UUID item = inProgressWith(other);
        Map<String, Object> before = items.read(scope, item);

        assertThat(refusal(() -> update(item, Map.of("relations", List.of()))).reason())
            .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertThat(items.read(scope, item)).as("nothing is written").isEqualTo(before);
    }

    @Test
    void k3_an_update_in_progress_that_leaves_out_or_changes_an_active_reference_is_refused() {
        UUID other = open("the target");
        UUID item = inProgressWith(other);
        Map<String, Object> before = items.read(scope, item);

        assertThat(refusal(() -> update(item, Map.of("references", List.of()))).reason())
            .as("left out").isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertThat(refusal(() -> update(item, Map.of("references",
                List.of(Map.of("label", "renamed", "target", "docs/design.md"))))).reason())
            .as("changed").isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertThat(items.read(scope, item)).as("nothing is written").isEqualTo(before);
    }

    @Test
    void k3_an_update_in_progress_that_appends_a_reference_and_a_relation_is_taken() {
        UUID other = open("the target");
        UUID third = open("a third item");
        UUID item = inProgressWith(other);
        Map<String, Object> read = items.read(scope, item);

        List<Object> references = new java.util.ArrayList<>(list(read, "references"));
        references.add(Map.of("label", "the review", "target", "docs/review.md"));
        List<Object> relations = new java.util.ArrayList<>(list(read, "relations"));
        relations.add(Map.of("type", RELATES, "item", addressOf(third)));
        Map<String, Object> answer = update(item, Map.of(
            "references", references, "relations", relations));

        assertThat(list(answer, "references")).extracting(entry -> entry.get("target"))
            .containsExactly("docs/design.md", "docs/review.md");
        assertThat(relations(answer)).hasSize(2);
        assertThat(answer.get("cancelled_references_count")).isEqualTo(0);
        assertThat(answer.get("cancelled_relations_count")).isEqualTo(0);
    }

    // ====================================================================
    // K4: a reference out of the middle is cancelled alone
    // ====================================================================

    @Test
    void k4_a_reference_left_out_of_the_middle_of_three_is_cancelled_and_overwrites_no_other_row() {
        UUID item = open("three references");
        update(item, Map.of("references", List.of(
            Map.of("label", "first", "target", "docs/first.md"),
            Map.of("label", "second", "target", "docs/second.md"),
            Map.of("label", "third", "target", "docs/third.md"))));
        Map<String, Long> pkByTarget = new LinkedHashMap<>();
        for (ItemReference row : rows.referencesOf(stored(item))) {
            pkByTarget.put(row.target, row.pk);
        }

        Map<String, Object> answer = update(item, Map.of("references", List.of(
            Map.of("label", "first", "target", "docs/first.md"),
            Map.of("label", "third", "target", "docs/third.md"))), CANCELLER);

        assertThat(list(answer, "references")).extracting(entry -> entry.get("target"))
            .as("the two others, in the wanted order").containsExactly("docs/first.md",
                "docs/third.md");
        assertThat(answer.get("cancelled_references_count")).isEqualTo(1);

        List<ItemReference> stored = rows.referencesOf(stored(item));
        assertThat(stored).as("no row is deleted and none is added").hasSize(3);
        for (ItemReference row : stored) {
            assertThat(row.pk).as("%s keeps its row", row.target)
                .isEqualTo(pkByTarget.get(row.target));
            assertThat(row.label).as("%s keeps its label", row.target)
                .isEqualTo(row.target.substring("docs/".length(), row.target.length() - 3));
            if (row.target.equals("docs/second.md")) {
                assertThat(row.status).isEqualTo("withdrawn");
                assertThat(row.endedAt).isNotNull();
                assertThat(row.endedBy).isEqualTo(CANCELLER);
                assertThat(row.endReason).as("update carries no reason").isNull();
            } else {
                assertThat(row.status).isEqualTo("asserted");
                assertThat(row.endedAt).isNull();
            }
        }

        Map<String, Object> all = items.read(scope, item, ItemService.Ledger.LATEST,
            ItemService.Cancelled.ALL);
        assertThat(list(all, "cancelled_references")).singleElement()
            .satisfies(entry -> assertThat(entry)
                .containsEntry("label", "second")
                .containsEntry("target", "docs/second.md")
                .containsEntry("ended_by", CANCELLER)
                .containsKey("ended_at")
                .doesNotContainKey("end_reason"));
    }

    @Test
    void k4_a_relation_left_out_of_the_set_on_an_open_item_is_cancelled_with_its_end() {
        UUID other = open("the target");
        UUID item = open("the source");
        relate(item, other, RELATES);

        Map<String, Object> answer = update(item, Map.of("relations", List.of()), CANCELLER);

        assertThat(relations(answer)).isEmpty();
        assertThat(answer.get("cancelled_relations_count")).isEqualTo(1);
        ItemRelation edge = rows.edgesOf(stored(item)).get(0);
        assertThat(edge.status).isEqualTo("withdrawn");
        assertThat(edge.endedBy).isEqualTo(CANCELLER);
        assertThat(edge.endedAt).isNotNull();
        assertThat(edge.endReason).isNull();
    }

    // ====================================================================
    // K5: a cancelled blocking edge counts nowhere
    // ====================================================================

    @Test
    void k5_validate_reports_a_blocking_cycle_and_no_longer_after_one_of_its_edges_is_cancelled() {
        UUID a = open("a");
        UUID b = open("b");
        relate(a, b, BLOCKS);
        relate(b, a, BLOCKS);

        assertThat(findings(items.validate(scope))).as("the cycle a -> b -> a").hasSize(1);

        items.unrelate(scope, b, addressOf(a), BLOCKS, WHY, CANCELLER, tokenOf(b));

        assertThat(findings(items.validate(scope)))
            .as("the cancelled edge is no part of any cycle").isEmpty();
        assertThat(items.validate(scope).get("consistent")).isEqualTo(true);
    }

    // ====================================================================
    // K6: the unchanged echo writes nothing in open and in progress
    // ====================================================================

    @Test
    void k6_the_read_answer_sent_back_to_an_open_item_cancels_nothing() {
        UUID other = open("the target");
        UUID item = open("the source");
        update(item, Map.of("references", List.of(Map.of("target", "docs/a.md"),
            Map.of("label", "b", "target", "docs/b.md"))));
        relate(item, other, RELATES);
        Map<String, Object> read = items.read(scope, item);

        Written<Map<String, Object>, Item> echoed = items.update(scope, item, read, CANCELLER);

        assertThat(echoed.stored()).as("nothing differs, nothing is stored").isNull();
        assertThat(items.read(scope, item)).isEqualTo(read);
        assertThat(read.get("cancelled_references_count")).isEqualTo(0);
        assertThat(read.get("cancelled_relations_count")).isEqualTo(0);
    }

    @Test
    void k6_the_read_answer_sent_back_to_an_item_in_progress_is_taken_and_cancels_nothing() {
        UUID other = open("the target");
        UUID item = inProgressWith(other);
        Map<String, Object> read = items.read(scope, item);

        assertThat(items.update(scope, item, read, CANCELLER).stored()).isNull();
        assertThat(items.read(scope, item)).isEqualTo(read);
    }

    @Test
    void k6_the_answer_of_the_option_sent_back_writes_nothing_either() {
        UUID other = open("the target");
        UUID item = open("the source");
        relate(item, other, RELATES);
        items.unrelate(scope, item, addressOf(other), RELATES, WHY, CANCELLER, tokenOf(item));
        Map<String, Object> all = items.read(scope, item, ItemService.Ledger.LATEST,
            ItemService.Cancelled.ALL);

        assertThat(items.update(scope, item, all, CANCELLER).stored()).isNull();

        Map<String, Object> tampered = new LinkedHashMap<>(all);
        tampered.put("cancelled_relations", List.of());
        assertThat(refusal(() -> items.update(scope, item, tampered, CANCELLER)).reason())
            .as("the cancelled entries are read-only")
            .isEqualTo(WorklistException.Reason.FIELD_NOT_SETTABLE);
    }

    // ====================================================================
    // K7: relate on a cancelled edge makes the same row active again
    // ====================================================================

    @Test
    void k7_relate_on_a_cancelled_edge_makes_the_same_row_active_again() {
        UUID other = open("the target");
        UUID item = open("the source");
        relate(item, other, RELATES);
        items.unrelate(scope, item, addressOf(other), RELATES, WHY, CANCELLER, tokenOf(item));

        Map<String, Object> answer = relate(item, other, RELATES);

        assertThat(relations(answer)).singleElement()
            .satisfies(entry -> assertThat(entry)
                .containsEntry("type", RELATES)
                .containsEntry("item", addressOf(other))
                .as("an active edge answers no end").doesNotContainKey("ended_at"));
        assertThat(answer.get("cancelled_relations_count")).isEqualTo(0);
        List<ItemRelation> stored = rows.edgesOf(stored(item));
        assertThat(stored).as("one row, no second record").hasSize(1);
        assertThat(stored.get(0).status).isEqualTo("asserted");
        assertThat(stored.get(0).endedBy)
            .as("the trace of the last cancellation stays on the row").isEqualTo(CANCELLER);
        assertThat(stored.get(0).endReason).isEqualTo(WHY);

        items.unrelate(scope, item, addressOf(other), RELATES, "a second reason", CANCELLER,
            tokenOf(item));
        assertThat(rows.edgesOf(stored(item)).get(0).endReason)
            .as("a further cancellation overwrites the trace").isEqualTo("a second reason");
    }

    // ====================================================================
    // K8: each phase names the calls of Annex A1, and each named is taken
    // ====================================================================

    /**
     * Annex A1, the item rows; {@code plan} is absent because the scope holds
     * no open iteration, {@code book} and {@code addendum} are another step's
     * and named where Annex A1 names them.
     */
    @Test
    void k8_an_item_driven_through_every_phase_names_exactly_annex_a1_and_each_edge_call_is_taken() {
        UUID other = open("the target");
        UUID item = draft("through every phase");
        update(item, Map.of("attributes", Map.of("type", "task")));
        assertThat(openVerbs(item)).as("draft").containsExactlyInAnyOrder(
            "read", "update", "relate", "unrelate", "tag_attach", "tag_detach",
            "submit", "discard");
        relate(item, other, RELATES);
        assertThat(relations(items.unrelate(scope, item, addressOf(other), RELATES, WHY,
            CANCELLER, tokenOf(item)).answer())).as("unrelate named in draft is taken").isEmpty();

        ItemLifecycle.submit(items, scope, item);
        assertThat(openVerbs(item)).as("open").containsExactlyInAnyOrder(
            "read", "update", "relate", "unrelate", "tag_attach", "tag_detach",
            "addendum", "book", "start", "close");
        relate(item, other, RELATES);
        assertThat(relations(items.unrelate(scope, item, addressOf(other), RELATES, WHY,
            CANCELLER, tokenOf(item)).answer())).as("unrelate named in open is taken").isEmpty();

        ItemLifecycle.start(items, scope, item, DOING);
        assertThat(openVerbs(item)).as("in_progress").containsExactlyInAnyOrder(
            "read", "update", "relate", "tag_attach", "tag_detach",
            "addendum", "book", "return", "close");
        assertThat(relations(relate(item, other, RELATES)))
            .as("relate named in progress is taken").hasSize(1);
        assertThat(items.update(scope, item, items.read(scope, item), CANCELLER).stored())
            .as("update named in progress takes the read answer").isNull();

        ItemLifecycle.close(items, scope, item, "finished", DONE);
        assertThat(openVerbs(item)).as("closed").containsExactly("read");
    }

    // ====================================================================
    // D18: unrelate reopens nothing; a duplicate is closed and takes none
    // ====================================================================

    @Test
    void d18_an_item_closed_by_duplicate_of_takes_no_unrelate_and_stays_closed() {
        vocabulary.declareStatus(scope, "duplicate", 4, false, false, true, false);
        vocabulary.declareRelationType(scope, "duplicate_of", false, 3, "duplicate");
        UUID original = open("the original");
        UUID duplicate = open("the duplicate");
        relate(duplicate, original, "duplicate_of");

        assertThat(refusal(() -> items.unrelate(scope, duplicate, addressOf(original),
                "duplicate_of", WHY, CANCELLER, tokenOf(duplicate))).reason())
            .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        Map<String, Object> read = items.read(scope, duplicate);
        assertThat(read.get("phase")).isEqualTo("closed");
        assertThat(relations(read)).hasSize(1);
    }

    // ====================================================================
    // Discard stays the one deletion
    // ====================================================================

    @Test
    void discard_of_a_draft_still_deletes_it_with_its_cancelled_rows() {
        UUID other = open("the target");
        UUID item = draft("a draft to discard");
        update(item, Map.of("references", List.of(Map.of("target", "docs/a.md"))));
        update(item, Map.of("references", List.of()));
        relate(item, other, RELATES);
        items.unrelate(scope, item, addressOf(other), RELATES, WHY, CANCELLER, tokenOf(item));
        Item before = stored(item);

        ItemLifecycle.discard(items, scope, item);

        assertThat(rows.referencesOf(before)).isEmpty();
        assertThat(rows.edgesOf(before)).isEmpty();
        assertThat(rows.byId(item)).isEmpty();
    }

    // ====================================================================
    // Fixtures
    // ====================================================================

    private Map<String, Object> fields(String title) {
        return Map.of(Field.TITLE.canonicalName(), title, Field.STATUS.canonicalName(), OPEN);
    }

    private UUID draft(String title) {
        return ItemLifecycle.draft(items, scope, fields(title));
    }

    private UUID open(String title) {
        return ItemLifecycle.open(items, scope, fields(title));
    }

    /** An item in progress holding one reference and one relation to the item given. */
    private UUID inProgressWith(UUID other) {
        UUID item = open("in progress");
        update(item, Map.of("references",
            List.of(Map.of("label", "the design", "target", "docs/design.md"))));
        relate(item, other, RELATES);
        ItemLifecycle.typed(items, vocabulary, scope, item);
        ItemLifecycle.start(items, scope, item, DOING);
        return item;
    }

    private Map<String, Object> relate(UUID from, UUID to, String type) {
        return items.relate(scope, from, addressOf(to), type, tokenOf(from)).answer();
    }

    private Map<String, Object> update(UUID item, Map<String, ?> change) {
        return update(item, change, ItemLifecycle.CALLER);
    }

    /** An update with the item's current token, by the actor named. */
    private Map<String, Object> update(UUID item, Map<String, ?> change, String actor) {
        Map<String, Object> fields = new LinkedHashMap<>(change);
        fields.put(Field.CONFLICT_TOKEN.canonicalName(), tokenOf(item));
        return items.update(scope, item, fields, actor).answer();
    }

    private String tokenOf(UUID item) {
        return ItemLifecycle.tokenOf(items, scope, item);
    }

    private String addressOf(UUID item) {
        Map<String, Object> read = items.read(scope, item);
        return "worklist://" + read.get("scope") + "/item/" + read.get("number");
    }

    private Item stored(UUID item) {
        return rows.byId(item).orElseThrow();
    }

    private List<String> openVerbs(UUID item) {
        long number = ((Number) items.read(scope, item).get(Field.NUMBER.canonicalName()))
            .longValue();
        return openCalls.forItem(scope, number).calls().stream()
            .map(call -> call.verb().name().toLowerCase(Locale.ROOT))
            .toList();
    }

    private static WorklistException refusal(ThrowingSupplier<?> call) {
        Throwable thrown = catchThrowable(call::get);
        assertThat(thrown).isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }

    private static List<Map<String, Object>> relations(Map<String, Object> answer) {
        return list(answer, "relations");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> answer, String key) {
        return (List<Map<String, Object>>) answer.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> findings(Map<String, Object> answer) {
        return (List<Object>) answer.get("findings");
    }
}
