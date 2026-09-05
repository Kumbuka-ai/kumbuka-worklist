package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A scope is opened by {@code scope_setting.create}, and that call seeds the
 * three views. An item and an iteration created afterwards carry SEPARATE
 * numbers, because each axis is a class of its own.
 *
 * <h2>What is being defended</h2>
 *
 * <p><strong>Openness through one act.</strong> Before V7 a scope had to have
 * its three views declared separately by a caller who knew — a fixture,
 * because nothing else reached the declaration path. Under V7 the settings
 * row IS the scope-opening act, and it seeds the three views. The point of
 * the change is that a caller opening a scope has no verb to declare a view
 * with and no reason to know one exists; forcing the three declarations
 * would be a call the surface cannot support.
 *
 * <p><strong>Separated counters under the class reading.</strong> V7 flipped
 * the allocation mode's default back to {@code per_selector} — a decision V6
 * took the other way under the family reading of the selector, and V7
 * corrected. Under the class reading, {@code item/1} and {@code iteration/1}
 * name different addresses because they name different kinds of thing, and
 * the address carries the view precisely so this works. A single scope-wide
 * counter would number them {@code 1} and {@code 2} — no wrong answer, but
 * a wasted address space that made a bare number ambiguous everywhere it
 * appears without a view.
 *
 * <h2>The red state, and how it was observed</h2>
 *
 * The seeding is one loop in {@link ScopeSettingService#create}. Removing it
 * makes {@link #a_freshly_opened_scope_carries_its_three_seeded_views} fail:
 * the item view is not declared under the fresh scope, and
 * {@link ItemService#create} refuses with {@code SELECTOR_UNDECLARED} before
 * reaching the allocation. Measured on 2026-09-05 against the current build.
 *
 * <p>The separated-counters half's red state is measured against the mode
 * itself. Setting {@code allocation_mode = 'scope_wide'} on the settings row
 * makes {@link #each_axis_allocates_from_its_own_counter} fail:
 * {@link SelectorRegistry#allocate} reads the scope-wide counter under that
 * mode, so the iteration's number would be {@code 2} rather than {@code 1}
 * — a state that stays technically correct but is not what the view model
 * describes.
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
            Field.STATUS.canonicalName(), openStatus.toString()));
        Map<String, Object> iteration = iterations.create(scope, Map.of(
            Field.MOTTO.canonicalName(), "the first iteration",
            Field.DESCRIPTION.canonicalName(), "what it holds"));

        assertThat(item.get(Field.NUMBER.canonicalName())).isNotNull();
        assertThat(iteration.get(Field.NUMBER.canonicalName())).isNotNull();
    }

    // ==================================================================
    // Probe 3 — separated counters under the class reading
    // ==================================================================

    @Test
    void each_axis_allocates_from_its_own_counter() {
        settings.create(scope, Map.of(
            "max_planned_iterations", 10,
            "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10,
            "warn_memberships_per_iteration", 9));

        Map<String, Object> item = items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "an item",
            Field.STATUS.canonicalName(), openStatus.toString()));
        Map<String, Object> iteration = iterations.create(scope, Map.of(
            Field.MOTTO.canonicalName(), "a motto",
            Field.DESCRIPTION.canonicalName(), "a description"));
        Map<String, Object> milestone = milestones.create(scope, Map.of(
            Field.TITLE.canonicalName(), "a goal"));

        // Each axis is a class of its own — item, iteration, milestone — so
        // each starts at 1 under the per_selector default V7 restored. A
        // scope-wide counter would number them 1, 2, 3 in whatever order
        // they were created, and that would be the mode this scope has NOT
        // been set to.
        assertThat(item.get(Field.NUMBER.canonicalName()))
            .as("the item's number allocates from the item selector's own counter")
            .isEqualTo(1L);
        assertThat(iteration.get(Field.NUMBER.canonicalName()))
            .as("the iteration's from the iteration selector's — same starting number, "
                + "different address space, because item and iteration name different "
                + "kinds of thing")
            .isEqualTo(1L);
        assertThat(milestone.get(Field.NUMBER.canonicalName()))
            .as("and the milestone's from the milestone selector's. Three counters, "
                + "one act to open them all")
            .isEqualTo(1L);

        // A second item and a second iteration keep to their own counters.
        Map<String, Object> secondItem = items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "a second item",
            Field.STATUS.canonicalName(), openStatus.toString()));
        Map<String, Object> secondIteration = iterations.create(scope, Map.of(
            Field.MOTTO.canonicalName(), "later",
            Field.DESCRIPTION.canonicalName(), "held after"));

        assertThat(secondItem.get(Field.NUMBER.canonicalName())).isEqualTo(2L);
        assertThat(secondIteration.get(Field.NUMBER.canonicalName())).isEqualTo(2L);
    }

    // A field used nowhere else, to keep imports in one place if the file
    // grows a fourth case.
    @SuppressWarnings("unused")
    private static void unused(List<UUID> ignore) {
        // Only kept to hold the List<UUID> import if the file grows a case
        // that needs it. Deliberately no body.
    }
}
