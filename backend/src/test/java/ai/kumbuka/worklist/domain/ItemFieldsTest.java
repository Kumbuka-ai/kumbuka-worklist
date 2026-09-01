package ai.kumbuka.worklist.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * The value handling under the field naming, without a database.
 *
 * <p>Everything here decides whether two values are the same value, and that
 * one question carries two guarantees: that a write changing nothing writes
 * nothing, and that a read answer sent straight back is accepted rather than
 * discarded. It is worth testing on its own, at unit speed, rather than only
 * through the store — a failure here should say which conversion is wrong,
 * not that an amendment behaved oddly.
 */
class ItemFieldsTest {

    // ------------------------------------------------------------------
    // The canonical naming.
    // ------------------------------------------------------------------

    @Test
    void an_unknown_name_is_refused_and_named() {
        WorklistException refusal = (WorklistException) catchThrowable(() ->
            Field.resolve(Map.of("Titel", "the predecessor's spelling")));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.UNKNOWN_FIELD);
        assertThat(refusal.offenders()).containsExactly("Titel");
    }

    /**
     * Case matters, and deliberately.
     *
     * <p>Accepting {@code Title} for {@code title} would reintroduce the
     * two-spellings defect in a friendlier costume: both would work, callers
     * would settle on different ones, and the day one stopped being accepted
     * would be a mystery.
     */
    @Test
    void the_name_is_matched_case_sensitively() {
        assertThat(Field.byCanonicalName("title")).contains(Field.TITLE);
        assertThat(Field.byCanonicalName("Title")).isEmpty();
    }

    @Test
    void the_settable_names_are_the_ones_a_caller_may_change() {
        assertThat(Field.settableNames())
            .contains("title", "status", "component", "depends_on", "reference")
            .doesNotContain("id", "number", "selector", "created_at", "conflict_token");
    }

    // ------------------------------------------------------------------
    // Text.
    // ------------------------------------------------------------------

    @Test
    void blank_text_is_the_same_absence_as_null() {
        assertThat(ItemFields.text(Field.REFERENCE, null)).isNull();
        assertThat(ItemFields.text(Field.REFERENCE, "   ")).isNull();
        assertThat(ItemFields.text(Field.REFERENCE, " on file ")).isEqualTo("on file");
    }

    /**
     * A scalar field stays strict about its type, unlike a list element.
     *
     * <p>A caller echoing a list back may hold its elements as uuids or as
     * the strings they became in transit, so those are rendered. A number
     * where a title belongs is a real mistake and is refused.
     */
    @Test
    void a_scalar_field_refuses_a_value_that_is_not_text() {
        WorklistException refusal = (WorklistException) catchThrowable(() ->
            ItemFields.text(Field.TITLE, 42));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly("title");
    }

    // ------------------------------------------------------------------
    // The set-valued fields.
    // ------------------------------------------------------------------

    /**
     * Sorted, distinct, and blank-free — which is what makes re-sending a
     * read answer in another order NOT a change.
     */
    @Test
    void tokens_are_normalised_to_a_sorted_distinct_set() {
        assertThat(ItemFields.tokens(Field.COMPONENT, List.of("ee-srv", "e2e", "e2e", " ")))
            .containsExactly("e2e", "ee-srv");
        assertThat(ItemFields.tokens(Field.COMPONENT, new String[] {"b", "a"}))
            .as("an array and a list are the same value — a caller that read the answer "
                + "out of the entity has one, out of a payload the other")
            .containsExactly("a", "b");
        assertThat(ItemFields.tokens(Field.COMPONENT, null)).isEmpty();
    }

    @Test
    void a_set_field_refuses_a_value_that_is_not_a_collection() {
        WorklistException refusal = (WorklistException) catchThrowable(() ->
            ItemFields.tokens(Field.COMPONENT, "e2e"));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly("component");
    }

    @Test
    void component_tags_are_lower_case_tokens_and_a_malformed_one_is_named() {
        assertThat(ItemFields.componentTokens(List.of("ee-srv", "e2e", "none")))
            .containsExactly("e2e", "ee-srv", "none");

        WorklistException refusal = (WorklistException) catchThrowable(() ->
            ItemFields.componentTokens(List.of("e2e", "EE_SRV", "-leading")));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders())
            .as("both malformed tags are reported, so a caller learns them in one round trip")
            .containsExactlyInAnyOrder("EE_SRV", "-leading");
    }

    /** Ids accept a uuid or its rendering, and refuse anything that is neither. */
    @Test
    void ids_accept_both_shapes_and_refuse_what_is_not_an_id() {
        UUID one = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID two = UUID.fromString("00000000-0000-0000-0000-00000000000b");

        assertThat(ItemFields.ids(Field.DEPENDS_ON, List.of(two, one.toString(), one)))
            .as("sorted and distinct, whichever shape each element arrived in")
            .containsExactly(one, two);

        WorklistException refusal = (WorklistException) catchThrowable(() ->
            ItemFields.ids(Field.DEPENDS_ON, List.of("47")));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.getMessage())
            .as("and the message says why a number is not one: the predecessor's running "
                + "number does not exist here")
            .contains("47");
    }

    // ------------------------------------------------------------------
    // The comparison the read-only fields are checked with.
    // ------------------------------------------------------------------

    @Test
    void a_read_only_value_compares_equal_across_its_renderings() {
        UUID id = UUID.randomUUID();
        Instant when = Instant.parse("2026-09-01T12:00:00Z");

        assertThat(ItemFields.unchangedAsText(id, id.toString()))
            .as("a caller echoing a read answer may have carried the uuid through as a "
                + "string, and treating that as a change would make every honest round "
                + "trip a refusal")
            .isTrue();
        assertThat(ItemFields.unchangedAsText(when, when.toString())).isTrue();
        assertThat(ItemFields.unchangedAsText(null, null)).isTrue();
        assertThat(ItemFields.unchangedAsText(id, UUID.randomUUID().toString())).isFalse();
        assertThat(ItemFields.unchangedAsText(null, "something")).isFalse();
    }

    // ------------------------------------------------------------------
    // The two patterns, and the inputs that made them worth writing out.
    // ------------------------------------------------------------------

    @Test
    void the_selector_token_pattern_accepts_the_corpus_and_rejects_the_rest() {
        assertThat(Selector.TOKEN.matcher("FEAT").matches()).isTrue();
        assertThat(Selector.TOKEN.matcher("F").matches()).isTrue();
        assertThat(Selector.TOKEN.matcher("D-GTM").matches()).isTrue();
        assertThat(Selector.TOKEN.matcher("CHORE09").matches()).isTrue();

        assertThat(Selector.TOKEN.matcher("1FEAT").matches())
            .as("a leading digit — the number is the other half of the address")
            .isFalse();
        assertThat(Selector.TOKEN.matcher("FEAT-").matches()).isFalse();
        assertThat(Selector.TOKEN.matcher("D--GTM").matches()).isFalse();
        assertThat(Selector.TOKEN.matcher("").matches()).isFalse();
        assertThat(Selector.TOKEN.matcher("FEAT 51").matches()).isFalse();
    }

    /**
     * The pattern decides a long non-match in one pass.
     *
     * <p>Written the obvious way — {@code [A-Za-z0-9]*(-[A-Za-z0-9]+)*} —
     * Java's engine recurses once per repetition of the outer group, and an
     * input with thousands of them overflows the stack rather than returning
     * false. The possessive form commits to what it consumes and never
     * revisits it.
     *
     * <p>The timeout is what makes this a gate rather than a remark: it is
     * enormously more than the pattern needs and enormously less than the
     * failure takes.
     */
    @Test
    void the_selector_token_pattern_decides_a_long_input_without_recursing() {
        String pathological = "A" + "-b".repeat(50_000) + "!";

        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () ->
            assertThat(Selector.TOKEN.matcher(pathological).matches())
                .as("a long input that cannot match must be refused, quickly and without "
                    + "exhausting the stack")
                .isFalse());
    }

    /**
     * The whitespace check is a search rather than a whole-string match.
     *
     * <p>{@code token.matches(".*\\s.*")} answers the same question and costs
     * quadratic time on a token with no whitespace: the leading {@code .*}
     * takes everything, then backs off a character at a time and rescans the
     * tail from each position.
     */
    @Test
    void the_whitespace_check_finds_whitespace_anywhere_in_one_pass() {
        assertThat(Term.WHITESPACE.matcher("SEC").find()).isFalse();
        assertThat(Term.WHITESPACE.matcher("two words").find()).isTrue();
        assertThat(Term.WHITESPACE.matcher("trailing ").find()).isTrue();

        String long_ = "x".repeat(200_000);
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () ->
            assertThat(Term.WHITESPACE.matcher(long_).find()).isFalse());
    }

    // ------------------------------------------------------------------
    // The dependency key.
    // ------------------------------------------------------------------

    /**
     * The composite key's identity, which the persistence provider relies on
     * to tell one edge from another.
     *
     * <p>Worth its own case because nothing else exercises it directly: a
     * broken {@code equals} would show up as an edge that will not update, or
     * one that updates the wrong row, and neither failure would point here.
     */
    @Test
    void the_dependency_key_identifies_an_edge_by_both_ends() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();

        ItemDependency.Key key = new ItemDependency.Key(from, to);

        assertThat(key)
            .isEqualTo(new ItemDependency.Key(from, to))
            .hasSameHashCodeAs(new ItemDependency.Key(from, to))
            .isNotEqualTo(new ItemDependency.Key(to, from))
            .as("direction is part of the identity: A depends on B is not B depends on A")
            .isNotEqualTo(new ItemDependency.Key(from, UUID.randomUUID()))
            .isNotEqualTo(null)
            .isNotEqualTo("not a key");
        assertThat(key).isEqualTo(key);
        assertThat(new ItemDependency.Key()).isNotEqualTo(key);
    }
}
