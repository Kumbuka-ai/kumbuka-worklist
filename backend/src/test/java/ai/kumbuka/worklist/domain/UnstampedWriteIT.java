/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A changed row of an aggregate root whose change did not pass
 * {@link AggregateRoot#stamp} is refused when it is flushed, so the conflict
 * token moves on every write that changes a root, by mechanism rather than by
 * convention (C10.1). Each of the five roots is shown on its own: the refusal
 * sits on their common superclass, and a case for one of them says nothing
 * about whether the persistence context reaches it on another.
 *
 * <p>The unstamped write is made the way a careless one would be: the root is
 * read in a transaction, a field is set, and the persistence context is
 * flushed. A create, an update through the verb, an update that changes
 * nothing and the update that follows are the writes the service makes, and
 * none of them is refused; nor is a write that reaches the database in more
 * than one flush, as activating a milestone and reordering an iteration do.
 *
 * <p>RED STATE, observed on worklist {@code 01df14c} (before the stamp was
 * enforced): each of the five refusal cases failed at its first assertion,
 * {@code Expecting actual not to be null}, because the unstamped change was
 * flushed and committed; the five cases of the service's own writes passed
 * there as well, as they must. The two cases of a write in more than one
 * flush were added with the refusal; they were seen red against a service
 * that stamps after its last field and against a flag cleared at each
 * flush, both {@code milestone is written without stamp()} and
 * {@code iteration is written without stamp()}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class UnstampedWriteIT {

    @Inject ItemService items;
    @Inject IterationService iterations;
    @Inject MilestoneService milestones;
    @Inject MembershipService memberships;
    @Inject WorkstreamService workstreams;
    @Inject ScopeSettingService settings;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject EntityManager em;

    private UUID scope;
    private String openStatus;

    @BeforeEach
    void aScopeOfItsOwn() {
        scope = UUID.randomUUID();
        selectors.declare(scope, Selector.ITEM);
        openStatus = vocabulary.declareStatus(scope, "open", 1,
            true, false, false, false).name;
        settings.create(scope, Map.of(
            "max_planned_iterations", 10,
            "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10,
            "warn_memberships_per_iteration", 9));
    }

    // -----------------------------------------------------------------------
    // The write behind the stamp is refused, and nothing of it is stored.
    // -----------------------------------------------------------------------

    @Test
    void an_item_changed_behind_the_stamp_is_refused_at_the_flush() {
        Map<String, Object> created = item("as created");

        refusedAtTheFlush(() -> {
            items.read(scope, idOf(created));
            em.find(Item.class, idOf(created)).title = "changed behind the stamp";
        });

        Map<String, Object> after = items.read(scope, idOf(created));
        assertThat(after.get(Field.TITLE.canonicalName())).as("nothing of it is stored")
            .isEqualTo("as created");
        assertThat(tokenOf(after)).isEqualTo(tokenOf(created));
    }

    @Test
    void an_iteration_changed_behind_the_stamp_is_refused_at_the_flush() {
        Map<String, Object> created = iteration("as created");

        refusedAtTheFlush(() -> {
            iterations.read(scope, idOf(created));
            em.find(Iteration.class, idOf(created)).title = "changed behind the stamp";
        });

        Map<String, Object> after = iterations.read(scope, idOf(created));
        assertThat(after.get(Field.TITLE.canonicalName())).as("nothing of it is stored")
            .isEqualTo("as created");
        assertThat(tokenOf(after)).isEqualTo(tokenOf(created));
    }

    @Test
    void a_milestone_changed_behind_the_stamp_is_refused_at_the_flush() {
        Map<String, Object> created = milestone("as created");

        refusedAtTheFlush(() -> {
            milestones.read(scope, idOf(created));
            em.find(Milestone.class, idOf(created)).title = "changed behind the stamp";
        });

        Map<String, Object> after = milestones.read(scope, idOf(created));
        assertThat(after.get(Field.TITLE.canonicalName())).as("nothing of it is stored")
            .isEqualTo("as created");
        assertThat(tokenOf(after)).isEqualTo(tokenOf(created));
    }

    @Test
    void a_workstream_changed_behind_the_stamp_is_refused_at_the_flush() {
        Workstream created = workstreams.declare(scope, "stamped", "as declared");

        refusedAtTheFlush(() -> {
            workstreams.read(scope, created.id);
            em.find(Workstream.class, created.id).description = "changed behind the stamp";
        });

        Map<String, Object> after = workstreams.read(scope, created.id);
        assertThat(after.get(Field.DESCRIPTION.canonicalName())).as("nothing of it is stored")
            .isEqualTo("as declared");
        assertThat(tokenOf(after)).isEqualTo(created.conflictToken());
    }

    @Test
    void a_scope_setting_changed_behind_the_stamp_is_refused_at_the_flush() {
        Map<String, Object> created = settings.read(scope);

        refusedAtTheFlush(() -> {
            settings.read(scope);
            em.find(ScopeSetting.class, scope).warnPlannedIterations = 8;
        });

        Map<String, Object> after = settings.read(scope);
        assertThat(after.get(Field.WARN_PLANNED_ITERATIONS.canonicalName()))
            .as("nothing of it is stored").isEqualTo(9);
        assertThat(tokenOf(after)).isEqualTo(tokenOf(created));
    }

    // -----------------------------------------------------------------------
    // The service's own writes pass: create, change, no-op, the next change.
    // -----------------------------------------------------------------------

    @Test
    void an_item_written_through_its_verbs_passes_the_flush() {
        Map<String, Object> created = item("first");
        passesTheFlush(created, Field.TITLE, fields -> items.update(scope, idOf(created), fields));
    }

    @Test
    void an_iteration_written_through_its_verbs_passes_the_flush() {
        Map<String, Object> created = iteration("first");
        passesTheFlush(created, Field.TITLE,
            fields -> iterations.update(scope, idOf(created), fields));
    }

    @Test
    void a_milestone_written_through_its_verbs_passes_the_flush() {
        Map<String, Object> created = milestone("first");
        passesTheFlush(created, Field.TITLE,
            fields -> milestones.update(scope, idOf(created), fields));
    }

    @Test
    void an_iteration_reordered_with_a_new_title_passes_the_flush_of_the_reorder() {
        Map<String, Object> created = iteration("first");
        UUID iteration = idOf(created);
        Object goal = milestone("a goal").get(Field.NUMBER.canonicalName());
        Map<String, Object> one = itemOnPath("one", goal);
        Map<String, Object> two = itemOnPath("two", goal);
        memberships.plan(scope, iteration, idOf(one), tokenOf(iterations.read(scope, iteration)));
        memberships.plan(scope, iteration, idOf(two), tokenOf(iterations.read(scope, iteration)));
        List<String> reversed = List.of(addressOf(two), addressOf(one));

        Map<String, Object> written = iterations.update(scope, iteration, Map.of(
            Field.TITLE.canonicalName(), "first, renamed",
            Field.ORDER.canonicalName(), reversed,
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(iterations.read(scope, iteration))));

        assertThat(written)
            .as("the reorder reads and flushes inside the write, after the title was set, "
                + "and the renamed iteration is already stamped there")
            .containsEntry(Field.TITLE.canonicalName(), "first, renamed")
            .containsEntry(Field.ORDER.canonicalName(), reversed);
    }

    @Test
    void a_milestone_activated_with_a_new_title_passes_the_flush_of_the_demotion() {
        Map<String, Object> active = milestone("active");
        milestones.update(scope, idOf(active), Map.of(
            Field.STATUS.canonicalName(), Milestone.ACTIVE,
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(active)));
        Map<String, Object> next = milestone("next");

        Map<String, Object> activated = milestones.update(scope, idOf(next), Map.of(
            Field.TITLE.canonicalName(), "next, renamed",
            Field.STATUS.canonicalName(), Milestone.ACTIVE,
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(next)));

        assertThat(activated)
            .as("the demotion flushes inside the write, after the title was set, and the "
                + "renamed milestone is already stamped there")
            .containsEntry(Field.TITLE.canonicalName(), "next, renamed")
            .containsEntry(Field.STATUS.canonicalName(), Milestone.ACTIVE);
        assertThat(milestones.read(scope, idOf(active)).get(Field.STATUS.canonicalName()))
            .isEqualTo(Milestone.PLANNED);
    }

    @Test
    void a_workstream_written_through_its_verbs_passes_the_flush() {
        Workstream created = workstreams.declare(scope, "passing", "first");
        passesTheFlush(workstreams.read(scope, created.id), Field.DESCRIPTION,
            fields -> {
                workstreams.update(scope, created.id,
                    String.valueOf(fields.get(Field.DESCRIPTION.canonicalName())),
                    tokenOf(fields));
                return workstreams.read(scope, created.id);
            });
    }

    @Test
    void a_scope_setting_written_through_its_verbs_passes_the_flush() {
        Field warn = Field.WARN_PLANNED_ITERATIONS;
        Map<String, Object> created = settings.read(scope);

        Map<String, Object> changed = settings.update(scope, Map.of(
            warn.canonicalName(), 8,
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(created)));
        assertThat(changed.get(warn.canonicalName())).as("an update through the verb is stamped")
            .isEqualTo(8);
        assertThat(tokenOf(changed)).isNotEqualTo(tokenOf(created));

        Map<String, Object> same = settings.update(scope, Map.of(
            warn.canonicalName(), 8,
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(changed)));
        assertThat(tokenOf(same))
            .as("a write that changes nothing flushes nothing and keeps its token")
            .isEqualTo(tokenOf(changed));

        Map<String, Object> again = settings.update(scope, Map.of(
            warn.canonicalName(), 7,
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(same)));
        assertThat(again.get(warn.canonicalName()))
            .as("and the stamp of one write does not stand in for the next").isEqualTo(7);
        assertThat(settings.read(scope).get(warn.canonicalName())).isEqualTo(7);
    }

    // -----------------------------------------------------------------------

    /**
     * Runs {@code change} and flushes in one transaction, and requires the
     * flush to be refused for the missing stamp. The read through the verb in
     * {@code change} binds the tenant onto the transaction first, which the
     * row-level policy needs before the entity manager may see the row.
     */
    private void refusedAtTheFlush(Runnable change) {
        Throwable thrown = catchThrowable(() -> inOneTransaction(() -> {
            change.run();
            em.flush();
        }));
        assertThat(thrown)
            .as("a changed row whose write did not pass stamp() is refused at the flush")
            .isNotNull()
            .hasStackTraceContaining("written without stamp()");
    }

    /**
     * A change of {@code field}, the same value again, and a second change,
     * each through {@code update}, which takes the fields to write and answers
     * the root as stored.
     */
    private static void passesTheFlush(Map<String, Object> created, Field field,
            Function<Map<String, Object>, Map<String, Object>> update) {
        Map<String, Object> changed = update.apply(write(created, field, "second"));
        assertThat(changed.get(field.canonicalName())).as("an update through the verb is stamped")
            .isEqualTo("second");
        assertThat(tokenOf(changed)).isNotEqualTo(tokenOf(created));

        Map<String, Object> same = update.apply(write(changed, field, "second"));
        assertThat(tokenOf(same))
            .as("a write that changes nothing flushes nothing and keeps its token")
            .isEqualTo(tokenOf(changed));

        Map<String, Object> again = update.apply(write(same, field, "third"));
        assertThat(again.get(field.canonicalName()))
            .as("and the stamp of one write does not stand in for the next")
            .isEqualTo("third");
        assertThat(tokenOf(again)).isNotEqualTo(tokenOf(same));
    }

    /** The fields an update of {@code root} sends: its token and one new value. */
    private static Map<String, Object> write(Map<String, Object> root, Field field, String value) {
        return Map.of(
            field.canonicalName(), value,
            Field.CONFLICT_TOKEN.canonicalName(), tokenOf(root));
    }

    private Map<String, Object> item(String title) {
        return items.create(scope, Map.of(
            Field.TITLE.canonicalName(), title,
            Field.STATUS.canonicalName(), openStatus));
    }

    /** An item pointed at a milestone, which is what makes it plannable. */
    private Map<String, Object> itemOnPath(String title, Object milestoneNumber) {
        return items.create(scope, Map.of(
            Field.TITLE.canonicalName(), title,
            Field.STATUS.canonicalName(), openStatus,
            Field.MILESTONE_ID.canonicalName(), milestoneNumber));
    }

    private Map<String, Object> iteration(String title) {
        return iterations.create(scope, Map.of(
            Field.TITLE.canonicalName(), title,
            Field.MOTTO.canonicalName(), "a motto",
            Field.DESCRIPTION.canonicalName(), "a description"));
    }

    private Map<String, Object> milestone(String title) {
        return milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), title,
            Field.KIND.canonicalName(), Milestone.GOAL,
            Field.VISION.canonicalName(), "a vision",
            Field.MISSION.canonicalName(), "a mission"));
    }

    /** The canonical address of an item, the wire form of an order entry. */
    private String addressOf(Map<String, Object> item) {
        Map<String, Object> read = items.read(scope, idOf(item));
        Object slug = read.get("scope");
        return "worklist://" + (slug == null ? scope : slug) + "/item/" + read.get("number");
    }

    private static void inOneTransaction(Runnable work) {
        QuarkusTransaction.begin();
        try {
            work.run();
        } catch (RuntimeException failed) {
            QuarkusTransaction.rollback();
            throw failed;
        }
        QuarkusTransaction.commit();
    }

    private static UUID idOf(Map<String, Object> fields) {
        return UUID.fromString(String.valueOf(fields.get(Field.ID.canonicalName())));
    }

    private static String tokenOf(Map<String, Object> fields) {
        return String.valueOf(fields.get(Field.CONFLICT_TOKEN.canonicalName()));
    }
}
