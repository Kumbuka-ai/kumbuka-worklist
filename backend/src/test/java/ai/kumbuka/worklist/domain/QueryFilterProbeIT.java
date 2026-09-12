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
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A filter narrowing the item query narrows it, and dropping the filter in the
 * repository would give the whole set back — which reads as a correct narrow
 * answer.
 *
 * <h2>What is being defended</h2>
 *
 * <p><strong>The filter is honoured.</strong> A caller narrowing a read by
 * {@code status = <one>} gets back exactly the items with that status, not
 * every item in the scope. Silent narrowing to the whole set is the defect
 * the sprint-169 answer had one layer down: an answer that reads complete
 * and is not, and the read side of this surface is where the same shape
 * would reappear. Refusing an unknown filter by name is the other half —
 * a filter this repository does not know would be a filter this repository
 * would drop, and the domain refuses ahead of the read to keep it that way.
 *
 * <p><strong>The limit is honoured, and truncation is reported.</strong> A
 * caller who asks for at most {@code n} items in a scope that holds
 * {@code n + m} sees {@code n} items with {@code truncated = true}, and a
 * caller reading a truncated answer is told the store carried more. A
 * silent ceiling — the answer looking complete and not being — is the
 * sprint-169 defect this listing shape refuses.
 *
 * <h2>The red state, and how it was observed</h2>
 *
 * The filter's mechanism is one branch in
 * {@link ai.kumbuka.worklist.repository.ItemRepository#inScope(UUID, Map, int)}.
 * Dropping the {@code AND i.statusId = :param} makes
 * {@link #a_status_filter_narrows_the_answer} fail: the query answers with
 * every item in the scope regardless of the status filter, and the count
 * afterwards matches the scope's total instead of the filtered subset.
 * Measured on 2026-09-05 against the current build.
 *
 * <p>The unknown-field refusal is one branch in
 * {@link ItemService#query(UUID, QuerySpec)}'s filter parser. Dropping it
 * makes {@link #an_unknown_filter_is_refused_by_name} fail: an unknown field
 * is dropped rather than refused, and the answer reads narrow while the
 * filter did nothing.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class QueryFilterProbeIT {

    @Inject ItemService items;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject ScopeSettingService settings;

    private UUID scope;
    private UUID openStatus;
    private UUID doneStatus;

    @BeforeEach
    void aFreshScopeWithTwoStatuses() {
        scope = UUID.randomUUID();
        settings.create(scope, Map.of(
            "max_planned_iterations", 10,
            "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10,
            "warn_memberships_per_iteration", 9));

        openStatus = vocabulary.declareStatus(scope, "open", 1,
            true, false, false, false).id;
        doneStatus = vocabulary.declareStatus(scope, "done", 2,
            false, false, true, true).id;
    }

    // ==================================================================
    // Probe 1 — the filter narrows, and its absence would show through
    // ==================================================================

    @Test
    void a_status_filter_narrows_the_answer() {
        createItem("first open item", openStatus);
        createItem("second open item", openStatus);
        createItem("first done item", doneStatus);
        createItem("second done item", doneStatus);
        createItem("third done item", doneStatus);

        String openName = vocabulary.requireStatus(scope, openStatus).name;
        ItemService.QueryAnswer narrowed = items.query(scope,
            new QuerySpec(Map.of("status", openName), 100));

        assertThat(narrowed.items())
            .as("the caller asked for items with the 'open' status. Exactly the two "
                + "created under that status come back, and neither of the three under "
                + "'done'. A dropped filter would answer with all five, and the answer "
                + "would read as a correct narrow one")
            .hasSize(2);

        for (Map<String, Object> item : narrowed.items()) {
            assertThat(item.get(Field.STATUS.canonicalName()))
                .as("and every item in the answer carries the status the filter named — "
                    + "not just as many rows as the count, but the RIGHT rows. A "
                    + "dropped filter would answer with rows carrying the other status "
                    + "too")
                .isEqualTo(openName);
        }

        // The counter-probe: no filter answers with the whole set. Without
        // it, the assertion above would hold against a repository that
        // refused every query — the failure mode of a filter written one
        // predicate too broadly.
        ItemService.QueryAnswer whole = items.query(scope, QuerySpec.all());
        assertThat(whole.items())
            .as("the whole set is five items, so the narrow answer is genuinely a "
                + "subset rather than the whole thing on a smaller scope")
            .hasSize(5);
    }

    // ==================================================================
    // Probe 2 — an unknown filter is refused rather than dropped
    // ==================================================================

    @Test
    void an_unknown_filter_is_refused_by_name() {
        createItem("an item", openStatus);

        Throwable refused = catchThrowable(() ->
            items.query(scope, new QuerySpec(Map.of("title_contains", "test"), 100)));

        assertThat(refused)
            .as("the domain refuses a filter it does not read rather than dropping it. "
                + "A dropped filter would answer with the whole set while looking like a "
                + "correct narrow answer — the exact defect the surface's earlier "
                + "'no filters' rule existed against")
            .isInstanceOf(WorklistException.class);

        WorklistException typed = (WorklistException) refused;
        assertThat(typed.reason()).isEqualTo(WorklistException.Reason.UNKNOWN_FIELD);
        assertThat(typed.offenders()).contains("title_contains");
        assertThat(typed.getMessage())
            .as("and the message names what WAS narrowable, so a caller can act on it "
                + "without a second call to find out")
            .contains("status").contains("milestone");
    }

    // ==================================================================
    // Probe 3 — the limit caps, and truncation is reported
    // ==================================================================

    @Test
    void the_limit_caps_the_answer_and_truncation_is_reported() {
        for (int i = 0; i < 5; i++) {
            createItem("item " + i, openStatus);
        }

        ItemService.QueryAnswer capped = items.query(scope,
            new QuerySpec(Map.of(), 3));

        assertThat(capped.items())
            .as("the caller asked for at most three, and got three. A silent ceiling "
                + "would answer with more or fewer without saying, and the same "
                + "sprint-169 defect against the read path")
            .hasSize(3);
        assertThat(capped.truncated())
            .as("and the answer says that the store carried more than the caller asked "
                + "to see. Without this fact, a full listing and a truncated one look "
                + "identical from the outside, and a caller relying on 'answer size < "
                + "limit means end' has no way to check")
            .isTrue();

        ItemService.QueryAnswer whole = items.query(scope,
            new QuerySpec(Map.of(), 100));
        assertThat(whole.items()).hasSize(5);
        assertThat(whole.truncated())
            .as("a limit above the total size is not truncation — the store did not "
                + "carry more than the caller asked to see")
            .isFalse();
    }

    // ==================================================================
    // Fixtures
    // ==================================================================

    private UUID createItem(String title, UUID statusId) {
        Map<String, Object> created = items.create(scope, Map.of(
            Field.TITLE.canonicalName(), title,
            Field.STATUS.canonicalName(),
            vocabulary.requireStatus(scope, statusId).name));
        return UUID.fromString(String.valueOf(created.get(Field.ID.canonicalName())));
    }

    @SuppressWarnings("unused")
    private static void keep(List<UUID> ignore) {
        // Only kept to hold the List<UUID> import in one place if the file
        // grows a case that needs it. Deliberately no body.
    }
}
