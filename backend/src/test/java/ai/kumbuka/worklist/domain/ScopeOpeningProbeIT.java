package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A scope is opened by {@code scope_setting.create}, and that call seeds the
 * three views. An item, an iteration and a milestone created afterwards each
 * carry the number {@code 1} — three different addresses that name three
 * different objects.
 *
 * <h2>What is being defended</h2>
 *
 * <p><strong>Openness through one act.</strong> Before the seeding rule a
 * scope had to have its three views declared separately by a caller who
 * knew — a fixture, because nothing else reached the declaration path. The
 * settings row IS the scope-opening act now, and it seeds the three views.
 * A caller opening a scope has no verb to declare a view with and no reason
 * to know one exists; forcing three declarations would be a call the surface
 * cannot support.
 *
 * <p><strong>One counter per view, and there is no other.</strong> Each
 * view — item, iteration, milestone — has a {@link NumberSpace} row of its
 * own, and that row is the one position. The address form carries the view,
 * so {@code .../item/1}, {@code .../iteration/1} and {@code .../milestone/1}
 * are three different addresses already, and a bare number that had to
 * disambiguate itself across them was solving a problem the address form
 * does not have.
 *
 * <h2>The red state, and how it was observed</h2>
 *
 * <p>The seeding half was observed against {@link ScopeSettingService#create}:
 * removing the {@code selectors.declare} loop makes
 * {@link #a_freshly_opened_scope_carries_its_three_seeded_views} fail, and
 * {@link ItemService#create} refuses with {@code SELECTOR_UNDECLARED} before
 * reaching the allocation. Measured 2026-09-07 against the current build.
 *
 * <p>The separated-counters half was observed against
 * {@link SelectorRegistry#allocate}: routing every allocation to the item
 * selector's counter — collapsing the three views to one number line —
 * makes {@link #the_three_views_number_from_one_each_in_a_fresh_scope} fail.
 * Under the collapsed allocator the item is {@code 1}, the iteration is
 * {@code 2} and the milestone is {@code 3}; the case above stays green
 * because the seeding path is unchanged, so the whole probe reads red on
 * the counter half alone, which is the assertion the change would have
 * removed. Measured 2026-09-07.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ScopeOpeningProbeIT {

    @Inject ItemService items;
    @Inject IterationService iterations;
    @Inject MilestoneService milestones;
    @Inject ScopeSettingService settings;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;

    private UUID scope;
    private UUID openStatus;

    @BeforeEach
    void aFreshScope() {
        // A fresh scope every case. Under the seeding rule the setting IS
        // the whole opening, so nothing here declares a view — that is what
        // the case below defends.
        scope = UUID.randomUUID();
        openStatus = vocabulary.declareStatus(scope, "open", 1,
            true, false, false, false).id;
    }

    // ==================================================================
    // Probe 1 — the three views are seeded, on one call
    // ==================================================================

    @Test
    void a_freshly_opened_scope_carries_its_three_seeded_views() {
        settings.create(scope, Map.of(
            "max_planned_iterations", 10,
            "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10,
            "warn_memberships_per_iteration", 9));

        assertThat(selectors.inScope(scope).stream().map(s -> s.token).toList())
            .as("the scope-opening call is one act, and it seeds the three views the "
                + "platform's object model names — item, iteration and milestone. A "
                + "caller opening a scope has no verb to declare a view with, so the "
                + "seeding is what makes any subsequent write reachable at all")
            .containsExactlyInAnyOrderElementsOf(Selector.VIEWS);
    }

    // ==================================================================
    // Probe 2 — the trace of an absent seeding: writes go through
    // ==================================================================

    @Test
    void an_item_and_an_iteration_can_be_created_immediately_after_opening() {
        settings.create(scope, Map.of(
            "max_planned_iterations", 10,
            "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10,
            "warn_memberships_per_iteration", 9));

        // Both calls go through without a further declaration. Without the
        // seeding, each would refuse with SELECTOR_UNDECLARED — the trace of
        // an absent view is a refusal on the first write that reaches it,
        // and the writes here are the same two the surface offers on a
        // freshly opened scope.
        Map<String, Object> item = items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "the first item",
            Field.STATUS.canonicalName(), vocabulary.requireStatus(scope, openStatus).name));
        Map<String, Object> iteration = iterations.create(scope, Map.of(
            Field.MOTTO.canonicalName(), "the first iteration",
            Field.DESCRIPTION.canonicalName(), "what it holds"));

        assertThat(item.get(Field.NUMBER.canonicalName())).isNotNull();
        assertThat(iteration.get(Field.NUMBER.canonicalName())).isNotNull();
    }

    // ==================================================================
    // Probe 3 — one counter per view, no other
    // ==================================================================

    @Test
    void the_three_views_number_from_one_each_in_a_fresh_scope() {
        settings.create(scope, Map.of(
            "max_planned_iterations", 10,
            "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10,
            "warn_memberships_per_iteration", 9));

        Map<String, Object> item = items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "an item",
            Field.STATUS.canonicalName(), vocabulary.requireStatus(scope, openStatus).name));
        Map<String, Object> iteration = iterations.create(scope, Map.of(
            Field.MOTTO.canonicalName(), "a motto",
            Field.DESCRIPTION.canonicalName(), "a description"));
        Map<String, Object> milestone = milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), "a goal"));

        // Three views, three counters. Each starts at 1, and the address
        // carries the view — so item/1, iteration/1 and milestone/1 are
        // three different addresses that name three different objects, and
        // there is no scope-wide row that would collapse them to one number
        // line.
        assertThat(item.get(Field.NUMBER.canonicalName()))
            .as("the item's number is 1 — the item selector's own counter, first "
                + "allocation")
            .isEqualTo(1L);
        assertThat(iteration.get(Field.NUMBER.canonicalName()))
            .as("the iteration's is 1 — same starting number, different address space, "
                + "because item and iteration name different kinds of thing")
            .isEqualTo(1L);
        assertThat(milestone.get(Field.NUMBER.canonicalName()))
            .as("and the milestone's is 1 — three counters, one act to open them all")
            .isEqualTo(1L);

        // A second item and a second iteration keep to their own counters.
        Map<String, Object> secondItem = items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "a second item",
            Field.STATUS.canonicalName(), vocabulary.requireStatus(scope, openStatus).name));
        Map<String, Object> secondIteration = iterations.create(scope, Map.of(
            Field.MOTTO.canonicalName(), "later",
            Field.DESCRIPTION.canonicalName(), "held after"));

        assertThat(secondItem.get(Field.NUMBER.canonicalName())).isEqualTo(2L);
        assertThat(secondIteration.get(Field.NUMBER.canonicalName())).isEqualTo(2L);
    }
}
