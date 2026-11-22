/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

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
 * The phase decides what may be written, and the addendum arrives
 * (REA-0011 S10, D13, Annex A1 and A2), against the running database under
 * the service's own role.
 *
 * <p>Each case names the acceptance criterion of the order it covers. The
 * expectations are written here from Annex A2 and A1: which field is content,
 * classification or planning, which calls each phase opens. Nothing is read
 * from {@link FieldClass} or {@link OpenCallService} to form an expectation.
 *
 * <p>Every case works in a scope of its own, with a status of each of the
 * phases open, in progress and closed, the attribute {@code type} and one
 * other declared attribute, a second workstream, a milestone and the priority
 * in use.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ItemFieldClassIT {

    private static final String AUTHOR = "subject-of-the-author";
    private static final String OPEN = "open";
    private static final String DOING = "doing";
    private static final String DONE = "done";
    private static final String OTHER_WORKSTREAM = "backend";

    @Inject ItemService items;
    @Inject AddendumService addenda;
    @Inject OpenCallService openCalls;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject WorkstreamService workstreams;
    @Inject MilestoneService milestones;

    private UUID scope;
    private long milestone;

    @BeforeEach
    void aScopeOfItsOwn() {
        scope = UUID.randomUUID();
        selectors.open(scope, Selector.ITEM);
        selectors.open(scope, Selector.MILESTONE);
        vocabulary.declareStatus(scope, OPEN, 1, true, false, false, false);
        vocabulary.declareStatus(scope, DOING, 2, true, true, false, false);
        vocabulary.declareStatus(scope, DONE, 3, false, false, true, true);
        vocabulary.declareAttribute(scope, "type", "Type", AttributeDefinition.TEXT, 1, false);
        vocabulary.declareAttribute(scope, "effort", "Effort", AttributeDefinition.TEXT, 2, false);
        vocabulary.declareUsage(scope, ScopeUsage.Property.PRIORITY);
        workstreams.declare(scope, OTHER_WORKSTREAM, "the backend stream");
        milestone = ((Number) milestones.create(scope, Map.of(
                Field.TITLE.canonicalName(), "the first goal",
                Field.VISION.canonicalName(), "the north star of the first goal"))
            .answer().get(Field.NUMBER.canonicalName())).longValue();
    }

    // ====================================================================
    // K1: content is written in a draft alone
    // ====================================================================

    @Test
    void k1_title_description_and_criteria_are_taken_in_a_draft() {
        UUID item = draft();

        Map<String, Object> answer = update(item, Map.of(
            "title", "a better title",
            "description", "what it is and why it matters",
            "criteria", List.of(Map.of("text", "it holds"))));

        assertThat(answer.get("title")).isEqualTo("a better title");
        assertThat(answer.get("description")).isEqualTo("what it is and why it matters");
        assertThat(criteriaTexts(answer)).containsExactly("it holds");
    }

    @Test
    void k1_a_change_of_content_on_an_open_item_is_refused_by_state_and_names_the_addendum() {
        UUID item = open();
        for (Map<String, Object> change : contentChanges()) {
            Map<String, Object> before = items.read(scope, item);
            assertThat(refusal(() -> update(item, change)).reason())
                .as("open: %s is content, frozen at submit", change.keySet())
                .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
            assertThat(items.read(scope, item)).as("and nothing is written").isEqualTo(before);
        }
        assertThat(openVerbs(item))
            .as("the refusal by state names the open calls, and addendum is among them")
            .contains("addendum");
    }

    @Test
    void k1_a_change_of_content_on_an_item_in_progress_is_refused_by_state_and_names_the_addendum() {
        UUID item = inProgress();
        for (Map<String, Object> change : contentChanges()) {
            Map<String, Object> before = items.read(scope, item);
            assertThat(refusal(() -> update(item, change)).reason())
                .as("in_progress: %s is content, frozen at submit", change.keySet())
                .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
            assertThat(items.read(scope, item)).as("and nothing is written").isEqualTo(before);
        }
        assertThat(openVerbs(item)).contains("addendum");
    }

    // ====================================================================
    // K2: classification is written in a draft and an open item
    // ====================================================================

    @Test
    void k2_type_and_workstream_are_taken_on_an_open_item() {
        UUID item = open();

        Map<String, Object> answer = update(item, Map.of(
            "workstream", OTHER_WORKSTREAM,
            "attributes", Map.of("type", "bug")));

        assertThat(answer.get("workstream")).isEqualTo(OTHER_WORKSTREAM);
        assertThat(attributes(answer)).containsEntry("type", "bug");
    }

    @Test
    void k2_a_change_of_type_or_workstream_on_an_item_in_progress_is_refused() {
        UUID item = inProgress();
        Map<String, Object> before = items.read(scope, item);

        assertThat(refusal(() -> update(item, Map.of("workstream", OTHER_WORKSTREAM))).reason())
            .as("the workstream is classification, frozen at start")
            .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertThat(refusal(() -> update(item, Map.of("attributes", Map.of("type", "bug"))))
                .reason())
            .as("and so is the attribute type")
            .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertThat(refusal(() -> update(item, Map.of("attributes", Map.of("effort", "small"))))
                .reason())
            .as("an attribute map that leaves the type out removes it, and is refused too")
            .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertThat(items.read(scope, item)).as("nothing is written").isEqualTo(before);
    }

    @Test
    void k2_another_declared_attribute_is_taken_on_an_item_in_progress_beside_its_type() {
        UUID item = inProgress();
        Map<String, Object> attributes = new LinkedHashMap<>(attributes(items.read(scope, item)));
        attributes.put("effort", "small");

        Map<String, Object> answer = update(item, Map.of("attributes", attributes));

        assertThat(attributes(answer))
            .as("the type stays as it was, and the other attribute is planning")
            .containsEntry("type", "task")
            .containsEntry("effort", "small");
    }

    // ====================================================================
    // K3: planning is written in the phases open and in_progress
    // ====================================================================

    @Test
    void k3_planning_fields_are_taken_on_an_open_item() {
        assertPlanningTaken(open());
    }

    @Test
    void k3_planning_fields_are_taken_on_an_item_in_progress() {
        assertPlanningTaken(inProgress());
    }

    private void assertPlanningTaken(UUID item) {
        Map<String, Object> attributes = new LinkedHashMap<>(attributes(items.read(scope, item)));
        attributes.put("effort", "large");

        Map<String, Object> answer = update(item, Map.of(
            "priority", "high",
            "milestone", milestone,
            "contributor", List.of("subject-of-a-contributor"),
            "attributes", attributes));

        assertThat(answer.get("priority")).isEqualTo("high");
        assertThat(((Number) answer.get("milestone")).longValue()).isEqualTo(milestone);
        assertThat(answer.get("contributor")).isEqualTo(List.of("subject-of-a-contributor"));
        assertThat(attributes(answer)).containsEntry("effort", "large");
    }

    // ====================================================================
    // K4: a closed item takes no write, the addendum included
    // ====================================================================

    @Test
    void k4_every_write_to_a_closed_item_is_refused_the_addendum_included() {
        UUID item = open();
        ItemLifecycle.close(items, scope, item, DONE);
        Map<String, Object> before = items.read(scope, item);

        for (Map<String, Object> change : List.<Map<String, Object>>of(
                Map.of("title", "a late title"),
                Map.of("workstream", OTHER_WORKSTREAM),
                Map.of("priority", "low"),
                Map.of("milestone", milestone))) {
            assertThat(refusal(() -> update(item, change)).reason())
                .as("closed: %s", change.keySet())
                .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        }
        assertThat(refusal(() -> addenda.addendum(scope, item, "too late", AUTHOR)).reason())
            .as("and an addendum")
            .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertThat(items.read(scope, item)).as("nothing is written").isEqualTo(before);
    }

    // ====================================================================
    // K5: the addendum
    // ====================================================================

    @Test
    void k5_an_addendum_on_an_open_item_reads_back_with_text_author_and_time() {
        UUID item = open();
        Map<String, Object> before = items.read(scope, item);

        Map<String, Object> answer = addenda.addendum(scope, item, "  the first correction  ",
            AUTHOR).answer();

        List<Map<String, Object>> written = addendaOf(items.read(scope, item));
        assertThat(written).hasSize(1);
        assertThat(written.get(0))
            .containsEntry("text", "the first correction")
            .containsEntry("author", AUTHOR)
            .doesNotContainKey("delegated_identity");
        assertThat(written.get(0).get("written_at")).isNotNull();
        assertThat(addendaOf(answer)).as("the answer is the item with its addenda")
            .isEqualTo(written);
        assertThat(answer.get("conflict_token"))
            .as("an addendum stands beside the item: its token stays")
            .isEqualTo(before.get("conflict_token"));
        assertThat(answer.get("changed_at"))
            .as("and so does its change time")
            .isEqualTo(before.get("changed_at"));
    }

    @Test
    void k5_a_second_addendum_leaves_the_first_as_it_was_and_follows_it() {
        UUID item = inProgress();
        addenda.addendum(scope, item, "the first", AUTHOR);
        Map<String, Object> first = addendaOf(items.read(scope, item)).get(0);

        addenda.addendum(scope, item, "the second", "subject-of-another-author");

        List<Map<String, Object>> written = addendaOf(items.read(scope, item));
        assertThat(written).as("in the order they were written").hasSize(2);
        assertThat(written.get(0)).as("the first is unchanged").isEqualTo(first);
        assertThat(written.get(1))
            .containsEntry("text", "the second")
            .containsEntry("author", "subject-of-another-author");
    }

    @Test
    void k5_an_addendum_in_a_draft_is_refused_by_state_and_writes_nothing() {
        UUID item = draft();
        Map<String, Object> before = items.read(scope, item);

        assertThat(refusal(() -> addenda.addendum(scope, item, "early", AUTHOR)).reason())
            .as("a draft is edited, not annotated (Annex A1)")
            .isEqualTo(WorklistException.Reason.TRANSITION_REFUSED);
        assertThat(items.read(scope, item)).isEqualTo(before);
        assertThat(addendaOf(before)).isEmpty();
    }

    @Test
    void k5_an_addendum_without_text_is_refused_naming_the_text() {
        UUID item = open();

        WorklistException refusal = refusal(() -> addenda.addendum(scope, item, "   ", AUTHOR));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly("text");
        assertThat(addendaOf(items.read(scope, item))).isEmpty();
    }

    // ====================================================================
    // K7: each phase names exactly the calls of Annex A1, and each is taken
    // ====================================================================

    /**
     * Annex A1, without the narrowing of {@code unrelate} in progress, which
     * is a later step's; {@code plan} is absent because the scope holds no
     * open iteration.
     */
    @Test
    void k7_an_item_driven_through_every_phase_names_exactly_the_calls_of_annex_a1() {
        UUID item = draft();
        update(item, Map.of("attributes", Map.of("type", "task")));
        assertThat(openVerbs(item)).as("draft").containsExactlyInAnyOrder(
            "read", "update", "relate", "unrelate", "tag_attach", "tag_detach",
            "submit", "discard");

        ItemLifecycle.submit(items, scope, item);
        assertThat(openVerbs(item)).as("open").containsExactlyInAnyOrder(
            "read", "update", "relate", "unrelate", "tag_attach", "tag_detach",
            "addendum", "book", "start", "close");
        assertThat(addenda.addendum(scope, item, "named in open", AUTHOR).stored())
            .as("addendum named in open is taken").isNotNull();

        ItemLifecycle.start(items, scope, item, DOING);
        assertThat(openVerbs(item)).as("in_progress").containsExactlyInAnyOrder(
            "read", "update", "relate", "unrelate", "tag_attach", "tag_detach",
            "addendum", "book", "return", "close");
        assertThat(addenda.addendum(scope, item, "named in progress", AUTHOR).stored())
            .as("addendum named in progress is taken").isNotNull();

        ItemLifecycle.close(items, scope, item, "finished", DONE);
        assertThat(openVerbs(item)).as("closed").containsExactly("read");
        assertThat(addendaOf(items.read(scope, item)))
            .extracting(entry -> entry.get("text"))
            .containsExactly("named in open", "named in progress");
    }

    // ====================================================================
    // K8: the read answer goes back as it came
    // ====================================================================

    @Test
    void k8_the_read_answer_sent_back_unchanged_to_an_open_item_is_taken_and_writes_nothing() {
        UUID item = open();
        addenda.addendum(scope, item, "so the echo carries an addendum", AUTHOR);
        Map<String, Object> read = items.read(scope, item);

        Written<Map<String, Object>, Item> echoed = items.update(scope, item, read);

        assertThat(echoed.stored()).as("no field differs, so nothing is stored").isNull();
        assertThat(echoed.answer().get("conflict_token")).isEqualTo(read.get("conflict_token"));
        assertThat(items.read(scope, item)).isEqualTo(read);
    }

    @Test
    void k8_the_read_answer_sent_back_unchanged_to_an_item_in_progress_is_taken() {
        UUID item = inProgress();
        Map<String, Object> read = items.read(scope, item);

        assertThat(items.update(scope, item, read).stored()).isNull();
    }

    // ====================================================================
    // Fixtures
    // ====================================================================

    /** One change of each content field, each with a value the item does not hold. */
    private static List<Map<String, Object>> contentChanges() {
        return List.of(
            Map.of("title", "a title changed after submit"),
            Map.of("description", "a description changed after submit"),
            Map.of("criteria", List.of(Map.of("text", "a criterion added after submit"))));
    }

    private Map<String, Object> fields() {
        return Map.of(
            Field.TITLE.canonicalName(), "an item through its phases",
            Field.STATUS.canonicalName(), OPEN,
            Field.DESCRIPTION.canonicalName(), "as written at the start",
            Field.CRITERIA.canonicalName(), List.of(Map.of("text", "as agreed at the start")));
    }

    private UUID draft() {
        return ItemLifecycle.draft(items, scope, fields());
    }

    private UUID open() {
        return ItemLifecycle.open(items, scope, fields());
    }

    /** An item in progress, typed {@code task} while it was open. */
    private UUID inProgress() {
        return ItemLifecycle.inProgress(items, vocabulary, scope, fields(), DOING);
    }

    /** An update with the item's current token. */
    private Map<String, Object> update(UUID item, Map<String, ?> change) {
        Map<String, Object> fields = new LinkedHashMap<>(change);
        fields.put(Field.CONFLICT_TOKEN.canonicalName(), ItemLifecycle.tokenOf(items, scope, item));
        return items.update(scope, item, fields).answer();
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> answer) {
        return (Map<String, Object>) answer.get("attributes");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> addendaOf(Map<String, Object> answer) {
        return (List<Map<String, Object>>) answer.get("addenda");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> criteriaTexts(Map<String, Object> answer) {
        return ((List<Map<String, Object>>) answer.get("criteria")).stream()
            .map(entry -> entry.get("text")).toList();
    }
}
