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
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Every view pages by keyset in the database, and a walk over pages answers
 * every object once — across a tie in the sort, across an insert between two
 * pages, and without reading more than one row beyond the page.
 *
 * <h2>What is being defended</h2>
 *
 * <ul>
 * <li>A tie in the sort's own field — {@code created_at} is the transaction's
 *     start, so a batch written in one transaction shares it; {@code rank} is 0
 *     until someone ranks — is broken by the number. A keyset on the field
 *     alone loses every object of the tie that falls behind a page
 *     boundary.</li>
 * <li>An object created while a caller walks is answered at most once. An
 *     offset counts it on the next page and answers the object before it a
 *     second time; a keyset starts after the position the cursor carries and
 *     cannot.</li>
 * <li>A page reads at most {@code page_size + 1} rows of its view. The axes
 *     used to load their whole set and page in memory.</li>
 * <li>A cursor continues only the query that handed it out: of another scope,
 *     view, filter, sort or direction it is {@code CURSOR_FOREIGN}.</li>
 * </ul>
 *
 * <h2>The red state, and how it was observed</h2>
 *
 * Each rule was removed on its own and this class run (2026-10-11), and each
 * removal turned the named probes red: the number as second key dropped from
 * the keyset comparison — the two tie probes; the keyset comparison replaced
 * by an offset of the pages already read — the insert probe; the bound of
 * {@code page_size + 1} dropped — the read-bound probe on all four views; each
 * binding of the cursor skipped in turn — the matching foreign-cursor probe.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class KeysetPagingIT {

    @Inject ItemService items;
    @Inject IterationService iterations;
    @Inject MilestoneService milestones;
    @Inject WorkstreamService workstreams;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject ScopeSettingService settings;
    @Inject SessionFactory sessions;

    private UUID scope;

    @BeforeEach
    void aFreshScope() {
        scope = aScope();
    }

    // ==================================================================
    // A tie in the sort field
    // ==================================================================

    @Test
    void a_creation_tie_across_page_boundaries_loses_and_repeats_nothing() throws SQLException {
        List<Long> numbers = createItems(scope, 7);
        sameCreationInstant("item", scope);

        assertThat(walk(SortKey.CREATED_AT, SortKey.Direction.ASC, Selector.ITEM, items::query))
            .as("seven items created at one instant, two per page: every one once, the "
                + "tie broken by the number")
            .containsExactlyElementsOf(numbers);
        assertThat(walk(SortKey.CREATED_AT, SortKey.Direction.DESC, Selector.ITEM, items::query))
            .as("and the same backwards")
            .containsExactlyElementsOf(numbers.reversed());
    }

    @Test
    void a_rank_tie_across_page_boundaries_loses_and_repeats_nothing() {
        List<Long> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.add((Long) milestones.create(scope, Map.of("title", "goal " + i)).get("number"));
        }

        assertThat(walk(SortKey.RANK, SortKey.Direction.ASC, Selector.MILESTONE, milestones::query))
            .as("five milestones of one rank, two per page: every one once, by number")
            .containsExactlyElementsOf(created);
    }

    // ==================================================================
    // An insert between two pages
    // ==================================================================

    @Test
    void an_item_created_during_a_walk_repeats_nothing() {
        List<Long> before = createItems(scope, 5);

        List<Long> seen = new ArrayList<>();
        Page page = items.query(scope, request(Selector.ITEM, SortKey.CREATED_AT,
            SortKey.Direction.DESC, null));
        seen.addAll(numbers(page));
        long added = createItems(scope, 1).getFirst();
        while (page.next() != null) {
            page = items.query(scope, request(Selector.ITEM, SortKey.CREATED_AT,
                SortKey.Direction.DESC, page.next()));
            seen.addAll(numbers(page));
        }

        assertThat(new LinkedHashSet<>(seen))
            .as("newest first, an item created after the first page sorts ahead of the "
                + "position the walk stands at. An offset would count it and answer the "
                + "last item of the first page again")
            .hasSameSizeAs(seen);
        assertThat(seen)
            .as("every item that existed when the walk began is answered")
            .containsAll(before)
            .doesNotContain(added);
    }

    // ==================================================================
    // The read bound
    // ==================================================================

    @Test
    void a_page_reads_at_most_one_row_beyond_its_size_on_every_view() {
        createItems(scope, 6);
        for (int i = 0; i < 6; i++) {
            milestones.create(scope, Map.of("title", "goal " + i));
            iterations.create(scope, Map.of("title", "it " + i, "motto", "m", "description", "d"));
            workstreams.declare(scope, "stream-" + i, "stream " + i);
        }

        assertReadBound(Item.class, Selector.ITEM, items::query);
        assertReadBound(Milestone.class, Selector.MILESTONE, milestones::query);
        assertReadBound(Iteration.class, Selector.ITERATION, iterations::query);
        assertReadBound(Workstream.class, Selector.WORKSTREAM, workstreams::query);
    }

    private void assertReadBound(Class<?> entity, String view,
                                 BiFunction<UUID, PageRequest, Page> query) {
        var stats = sessions.getStatistics();
        stats.setStatisticsEnabled(true);
        long before = stats.getEntityStatistics(entity.getName()).getLoadCount();
        Page page = query.apply(scope, request(view, SortKey.ID, SortKey.Direction.ASC, null));
        long loaded = stats.getEntityStatistics(entity.getName()).getLoadCount() - before;

        assertThat(page.objects()).hasSize(2);
        assertThat(loaded)
            .as("a page of two on the %s view reads the two and the one that says more "
                + "follows, and never the whole view", view)
            .isLessThanOrEqualTo(3);
    }

    // ==================================================================
    // The cursor's binding
    // ==================================================================

    @Test
    void a_cursor_is_foreign_to_another_scope_view_filter_sort_and_direction() {
        createItems(scope, 3);
        for (int i = 0; i < 3; i++) {
            milestones.create(scope, Map.of("title", "goal " + i));
        }
        UUID other = aScope();
        createItems(other, 3);

        String cursor = items.query(scope, request(Selector.ITEM, SortKey.ID,
            SortKey.Direction.ASC, null)).next();
        assertThat(cursor).isNotNull();

        assertForeign(() -> spec(Map.of(), SortKey.ID, SortKey.Direction.ASC, cursor)
            .pageFor(other, Selector.ITEM), "another scope");
        assertForeign(() -> spec(Map.of(), SortKey.ID, SortKey.Direction.ASC, cursor)
            .pageFor(scope, Selector.MILESTONE), "another view");
        assertForeign(() -> spec(Map.of("status", "open"), SortKey.ID, SortKey.Direction.ASC,
            cursor).pageFor(scope, Selector.ITEM), "another filter");
        assertForeign(() -> spec(Map.of(), SortKey.CREATED_AT, SortKey.Direction.ASC, cursor)
            .pageFor(scope, Selector.ITEM), "another sort");
        assertForeign(() -> spec(Map.of(), SortKey.ID, SortKey.Direction.DESC, cursor)
            .pageFor(scope, Selector.ITEM), "another direction");

        assertThat(spec(Map.of(), SortKey.ID, SortKey.Direction.ASC, cursor)
            .pageFor(scope, Selector.ITEM).after())
            .as("and on its own query it places the walk")
            .isNotNull();
    }

    private static void assertForeign(Runnable call, String what) {
        Throwable refused = catchThrowable(call::run);
        assertThat(refused).as("a cursor read against %s", what)
            .isInstanceOf(WorklistException.class);
        WorklistException typed = (WorklistException) refused;
        assertThat(typed.reason()).as(what).isEqualTo(WorklistException.Reason.CURSOR_FOREIGN);
        assertThat(typed.offenders()).as(what).containsExactly(QuerySpec.AFTER_ARGUMENT);
    }

    // ==================================================================
    // Fixtures
    // ==================================================================

    private UUID aScope() {
        UUID id = UUID.randomUUID();
        settings.create(id, Map.of(
            "max_planned_iterations", 10,
            "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10,
            "warn_memberships_per_iteration", 9));
        for (String view : Selector.VIEWS) {
            selectors.declare(id, view);
        }
        vocabulary.declareStatus(id, "open", 1, true, false, false, false);
        workstreams.requireDefault(id);
        return id;
    }

    private List<Long> createItems(UUID in, int count) {
        List<Long> numbers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            numbers.add((Long) items.create(in, Map.of("title", "item " + i, "status", "open"))
                .get("number"));
        }
        return numbers;
    }

    /** Every row of the view in this scope gets one creation instant. */
    private static void sameCreationInstant(String table, UUID in) throws SQLException {
        try (Connection admin = Db.asAdmin();
             PreparedStatement update = admin.prepareStatement("UPDATE worklist." + table
                 + " SET created_at = timestamptz '2026-10-11 12:00:00+00' WHERE scope_id = ?")) {
            update.setObject(1, in);
            assertThat(update.executeUpdate()).isPositive();
        }
    }

    private List<Long> walk(SortKey sort, SortKey.Direction direction, String view,
                            BiFunction<UUID, PageRequest, Page> query) {
        List<Long> seen = new ArrayList<>();
        String after = null;
        int pages = 0;
        do {
            Page page = query.apply(scope, request(view, sort, direction, after));
            assertThat(page.objects()).hasSizeLessThanOrEqualTo(2);
            seen.addAll(numbers(page));
            after = page.next();
            assertThat(++pages).as("the pages end").isLessThanOrEqualTo(20);
        } while (after != null);
        return seen;
    }

    private PageRequest request(String view, SortKey sort, SortKey.Direction direction,
                                String after) {
        return spec(Map.of(), sort, direction, after).pageFor(scope, view);
    }

    private static QuerySpec spec(Map<String, Object> filter, SortKey sort,
                                  SortKey.Direction direction, String after) {
        return new QuerySpec(filter, 2, after, sort.wireName(), direction.wireName(), List.of(),
            SortKey.ALL);
    }

    private static List<Long> numbers(Page page) {
        return page.objects().stream().map(o -> ((Number) o.get("number")).longValue()).toList();
    }
}
