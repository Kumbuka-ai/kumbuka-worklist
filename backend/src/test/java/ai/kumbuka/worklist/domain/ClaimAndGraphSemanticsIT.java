package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The edge behaviour of the six verbs SPRINT_171.2 built.
 *
 * <p>The happy paths sit in {@link ClaimExclusivityIT} (claim, release) and in
 * the two surface probes ({@code SurfaceCoverageIT} over REST,
 * {@code McpProjectionIT} over MCP). This class walks the OTHER branches — the
 * two different refusals {@code claim_next} tells apart, the three refusals
 * {@code relate} carries, {@code unrelate}'s missing-edge answer and the two
 * shapes of {@code validate}'s report (an empty findings list AND a blocking
 * cycle it walked). Together they close the coverage the ratified concept
 * fixes about the six.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ClaimAndGraphSemanticsIT {

    private UUID scope;
    private UUID openStatus;

    @Inject ItemService items;
    @Inject ClaimService claims;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;

    @BeforeEach
    void aScopeOfItsOwn() {
        scope = UUID.randomUUID();
        selectors.declare(scope, Selector.ITEM);
        openStatus = vocabulary.declareStatus(scope, "open", 1,
            true, false, false, false).id;
    }

    // ====================================================================
    // claim_next: the two refusals it tells apart
    // ====================================================================

    /**
     * A scope with nothing to draw answers {@code ITEM_UNKNOWN}, not
     * {@code DRAW_EMPTY}. The remedy is different: add work, not wait.
     */
    @Test
    void claim_next_on_an_empty_scope_answers_item_unknown() {
        WorklistException refusal = refusalFrom(() ->
            claims.claimNext(scope, "drawer", Duration.ofMinutes(30)));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.ITEM_UNKNOWN);
    }

    /**
     * A scope whose only item is already held answers {@code DRAW_EMPTY}. The
     * caller reads that as "wait", never as "there is nothing here at all".
     */
    @Test
    void claim_next_when_every_item_is_held_answers_draw_empty() {
        UUID item = createItem("held item");
        claims.claim(scope, item, "first-holder", Duration.ofMinutes(30));

        WorklistException refusal = refusalFrom(() ->
            claims.claimNext(scope, "second-drawer", Duration.ofMinutes(30)));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.DRAW_EMPTY);
    }

    /**
     * With one unclaimed item, the draw takes it and reports its address.
     */
    @Test
    void claim_next_takes_the_one_addressable_item_that_is_free() {
        UUID item = createItem("free item");

        Map<String, Object> drawn = claims.claimNext(scope, "drawer",
            Duration.ofMinutes(30));

        assertThat(drawn.get(Field.ID.canonicalName())).isEqualTo(item);
        assertThat(drawn.get(ClaimService.F_RECEIPT))
            .as("a receipt travels back with the answer, the same as claim mints")
            .isNotNull();
    }

    // ====================================================================
    // relate: the three refusals, and the two write shapes
    // ====================================================================

    @Test
    void relate_asserts_an_edge_that_did_not_exist_and_reasserts_one_that_did() {
        UUID from = createItem("source");
        UUID to = createItem("target");
        vocabulary.declareRelationType(scope, "carries", false, 1);

        String token = tokenOf(from);
        token = tokenOfProjection(items.relate(scope, from, addressOf(to), "carries", token));

        // Re-asserting an existing edge writes nothing and does not stamp
        // the aggregate again — the token stays the same across the second
        // call.
        String reasserted = tokenOfProjection(
            items.relate(scope, from, addressOf(to), "carries", token));
        assertThat(reasserted)
            .as("relate is idempotent under the triple: reasserting an existing edge "
                + "writes nothing, so the item is not stamped and the token does not "
                + "move")
            .isEqualTo(token);
    }

    @Test
    void relate_refuses_an_edge_from_an_item_to_itself() {
        UUID item = createItem("the only item");
        vocabulary.declareRelationType(scope, "carries", false, 1);

        WorklistException refusal = refusalFrom(() ->
            items.relate(scope, item, addressOf(item), "carries", tokenOf(item)));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
    }

    @Test
    void relate_refuses_a_target_not_in_the_scope() {
        UUID from = createItem("source");
        vocabulary.declareRelationType(scope, "carries", false, 1);

        WorklistException refusal = refusalFrom(() ->
            items.relate(scope, from,
                "worklist://" + scope + "/item/9999999", "carries", tokenOf(from)));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.ITEM_UNKNOWN);
    }

    @Test
    void relate_moves_a_withdrawn_edge_back_to_asserted() {
        UUID from = createItem("source");
        UUID to = createItem("target");
        vocabulary.declareRelationType(scope, "carries", false, 1);

        String token = tokenOf(from);
        token = tokenOfProjection(items.relate(scope, from, addressOf(to), "carries", token));
        token = tokenOfProjection(
            items.unrelate(scope, from, addressOf(to), "carries", token));

        // The row is now withdrawn. Re-asserting it moves the status back
        // and stamps the aggregate — the token rotates.
        String afterReassert = tokenOfProjection(
            items.relate(scope, from, addressOf(to), "carries", token));
        assertThat(afterReassert)
            .as("re-asserting a withdrawn edge is a write: the row moves from withdrawn "
                + "to asserted, and the aggregate is stamped so the token rotates")
            .isNotEqualTo(token);
    }

    // ====================================================================
    // unrelate: RELATION_UNKNOWN for absence and for withdrawn alike
    // ====================================================================

    @Test
    void unrelate_refuses_an_edge_that_was_never_asserted() {
        UUID from = createItem("source");
        UUID to = createItem("target");
        vocabulary.declareRelationType(scope, "carries", false, 1);

        WorklistException refusal = refusalFrom(() ->
            items.unrelate(scope, from, addressOf(to), "carries", tokenOf(from)));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.RELATION_UNKNOWN);
    }

    @Test
    void unrelate_refuses_an_edge_that_has_already_been_withdrawn() {
        UUID from = createItem("source");
        UUID to = createItem("target");
        vocabulary.declareRelationType(scope, "carries", false, 1);

        String token = tokenOf(from);
        token = tokenOfProjection(items.relate(scope, from, addressOf(to), "carries", token));
        String withdrawnToken = tokenOfProjection(
            items.unrelate(scope, from, addressOf(to), "carries", token));

        WorklistException refusal = refusalFrom(() ->
            items.unrelate(scope, from, addressOf(to), "carries", withdrawnToken));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.RELATION_UNKNOWN);
    }

    // ====================================================================
    // validate: the two report shapes, empty and one blocking cycle
    // ====================================================================

    @Test
    void validate_over_a_scope_without_cycles_reports_consistent() {
        createItem("lonely item");

        Map<String, Object> report = items.validate(scope);

        assertThat(report.get("consistent")).isEqualTo(true);
        assertThat((List<?>) report.get("findings")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void validate_reports_a_blocking_cycle_when_one_stands_in_the_scope() {
        UUID a = createItem("a");
        UUID b = createItem("b");
        vocabulary.declareRelationType(scope, "blocks", true, 1);

        // a blocks b, b blocks a — a two-node cycle over the blocking edge.
        items.relate(scope, a, addressOf(b), "blocks", tokenOf(a));
        items.relate(scope, b, addressOf(a), "blocks", tokenOf(b));

        Map<String, Object> report = items.validate(scope);

        assertThat(report.get("consistent")).isEqualTo(false);
        List<Map<String, Object>> findings =
            (List<Map<String, Object>>) report.get("findings");
        assertThat(findings)
            .as("a blocking cycle stands and the walker reports it: one finding for "
                + "the whole ring, deduplicated by the SET of nodes it covers")
            .hasSize(1);
        assertThat(findings.get(0).get("kind")).isEqualTo("blocking_cycle");
        List<UUID> ring = (List<UUID>) findings.get(0).get("items");
        assertThat(ring)
            .as("the ring covers both nodes")
            .containsExactlyInAnyOrder(a, b);
    }

    // ====================================================================
    // Fixtures
    // ====================================================================

    private UUID createItem(String title) {
        Map<String, Object> created = items.create(scope, Map.of(
            Field.TITLE.canonicalName(), title,
            Field.STATUS.canonicalName(),
            vocabulary.requireStatus(scope, openStatus).name));
        return UUID.fromString(String.valueOf(created.get(Field.ID.canonicalName())));
    }

    /** The canonical address of the item, as the wire form of a relation target. */
    private String addressOf(UUID itemId) {
        Map<String, Object> read = items.read(scope, itemId);
        Object number = read.get(Field.NUMBER.canonicalName());
        Object slug = read.get(Field.SCOPE.canonicalName());
        String scopeSlug = slug == null ? String.valueOf(scope) : String.valueOf(slug);
        return "worklist://" + scopeSlug + "/item/" + number;
    }

    private String tokenOf(UUID itemId) {
        Map<String, Object> read = items.read(scope, itemId);
        return String.valueOf(read.get(Field.CONFLICT_TOKEN.canonicalName()));
    }

    private static String tokenOfProjection(Map<String, Object> projection) {
        return String.valueOf(projection.get(Field.CONFLICT_TOKEN.canonicalName()));
    }

    private static WorklistException refusalFrom(ThrowingRunnable call) {
        Throwable thrown = catchThrowable(() -> {
            try {
                call.run();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(thrown).isInstanceOf(WorklistException.class);
        return (WorklistException) thrown;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
