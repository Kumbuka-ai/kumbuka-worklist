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
 * not that an update behaved oddly.
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
            .contains("title", "description", "status", "attributes", "references",
                "relations")
            .doesNotContain("id", "number", "selector", "milestone", "created_at",
                "conflict_token");
    }

    /**
     * A scope's own attributes are not fields, and that is the change the
     * whole declaration mechanism rests on.
     *
     * <p>{@code cluster}, {@code type}, {@code priority} and {@code size} were
     * four entries in this enum, so a fifth axis was a code change. They
     * travel inside {@code attributes} now, under the key they were declared
     * with, and a sixth of them is a row.
     */
    @Test
    void a_declared_attribute_is_not_a_field_of_its_own() {
        assertThat(Field.byCanonicalName("cluster")).isEmpty();
        assertThat(Field.byCanonicalName("size")).isEmpty();
        assertThat(Field.byCanonicalName("component")).isEmpty();
        assertThat(Field.byCanonicalName("attributes")).contains(Field.ATTRIBUTES);
    }

    // ------------------------------------------------------------------
    // Text.
    // ------------------------------------------------------------------

    @Test
    void blank_text_is_the_same_absence_as_null() {
        assertThat(ItemFields.text(Field.DESCRIPTION, null)).isNull();
        assertThat(ItemFields.text(Field.DESCRIPTION, "   ")).isNull();
        assertThat(ItemFields.text(Field.DESCRIPTION, " on file ")).isEqualTo("on file");
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
        assertThat(ItemFields.tokens(Field.ATTRIBUTES, List.of("b", "a", "a", " ")))
            .containsExactly("a", "b");
        assertThat(ItemFields.tokens(Field.ATTRIBUTES, new String[] {"b", "a"}))
            .as("an array and a list are the same value — a caller that read the answer "
                + "out of the entity has one, out of a payload the other")
            .containsExactly("a", "b");
        assertThat(ItemFields.tokens(Field.ATTRIBUTES, null)).isEmpty();
    }

    @Test
    void a_set_field_refuses_a_value_that_is_not_a_collection() {
        WorklistException refusal = (WorklistException) catchThrowable(() ->
            ItemFields.tokens(Field.RELATIONS, "not a list"));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly("relations");
    }

    /**
     * The declared attributes are a map, sorted by key and free of absences.
     *
     * <p>The order a map arrives in says nothing, so two answers differing
     * only in it would compare unequal and a round trip would look like a
     * change. A key present with a null value is the absence of the attribute
     * and is dropped, so that clearing one and never having set it are the
     * same state rather than two that read alike.
     */
    @Test
    void attributes_are_normalised_to_a_sorted_map_without_absences() {
        Map<String, Object> given = new java.util.LinkedHashMap<>();
        given.put("size", "L");
        given.put("cluster", "CORE");
        given.put("priority", null);

        assertThat(ItemFields.attributes(given))
            .containsExactly(Map.entry("cluster", "CORE"), Map.entry("size", "L"));
        assertThat(ItemFields.attributes(null)).isEmpty();

        WorklistException refusal = (WorklistException) catchThrowable(() ->
            ItemFields.attributes(List.of("not a map")));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly("attributes");
    }

    /**
     * The reference list keeps its order and normalises each entry.
     *
     * <p>The order is the reader's and is part of the value, so it is NOT
     * sorted away — unlike the relation set, where the order carries nothing.
     * That asymmetry is the whole difference between a list and a set, and
     * getting it the wrong way round would either lose the reader's order or
     * report a reordering as a change.
     */
    @Test
    void references_keep_their_order_and_a_blank_label_is_an_absent_one() {
        List<Map<String, Object>> normalised = ItemFields.references(List.of(
            Map.of("label", " the design ", "target", " docs/a.md "),
            Map.of("label", "   ", "target", "docs/b.md")));

        assertThat(normalised).hasSize(2);
        assertThat(normalised.get(0))
            .containsEntry("label", "the design")
            .containsEntry("target", "docs/a.md");
        assertThat(normalised.get(1).get("label"))
            .as("a blank label is the same absence as no label — two entries differing "
                + "only in which spelling of nothing they carry would compare unequal")
            .isNull();

        WorklistException refusal = (WorklistException) catchThrowable(() ->
            ItemFields.references(List.of(Map.of("label", "no target"))));
        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.offenders()).containsExactly("references");
    }

    /**
     * The relation set is sorted, distinct, and typed on both halves.
     *
     * <p>Sorted because it is a set: a caller re-sending a read answer would
     * otherwise present the same edges in another order and the comparison
     * would report a change nobody made.
     */
    @Test
    void relations_are_normalised_to_a_sorted_distinct_set_of_typed_edges() {
        UUID type = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID one = UUID.fromString("00000000-0000-0000-0000-00000000000a");
        UUID two = UUID.fromString("00000000-0000-0000-0000-00000000000b");

        List<Map<String, Object>> normalised = ItemFields.relations(List.of(
            Map.of("type", type.toString(), "item", two),
            Map.of("type", type, "item", one.toString()),
            Map.of("type", type, "item", two)));

        assertThat(normalised)
            .as("sorted by target then type, distinct, whichever shape each half arrived in")
            .containsExactly(
                Map.of("type", type, "item", one),
                Map.of("type", type, "item", two));

        WorklistException typeless = (WorklistException) catchThrowable(() ->
            ItemFields.relations(List.of(Map.of("item", one.toString()))));
        assertThat(typeless.reason())
            .as("an edge without a type is the predecessor's untyped one, and every "
                + "machine reader of it has to guess whether it blocks")
            .isEqualTo(WorklistException.Reason.INVALID_VALUE);
    }

    /** An identity accepts a uuid or its rendering, and refuses what is neither. */
    @Test
    void an_identity_accepts_both_shapes_and_refuses_what_is_not_one() {
        UUID one = UUID.fromString("00000000-0000-0000-0000-00000000000a");

        assertThat(ItemFields.id(Field.STATUS, one)).isEqualTo(one);
        assertThat(ItemFields.id(Field.STATUS, one.toString()))
            .as("whichever shape it arrived in — a caller echoing a read answer may have "
                + "carried the uuid through JSON as a string")
            .isEqualTo(one);
        assertThat(ItemFields.id(Field.STATUS, null)).isNull();

        WorklistException refusal = (WorklistException) catchThrowable(() ->
            ItemFields.id(Field.STATUS, "47"));

        assertThat(refusal.reason()).isEqualTo(WorklistException.Reason.INVALID_VALUE);
        assertThat(refusal.getMessage())
            .as("and the message says why: a declared value has an identity separate from "
                + "its name, and the identity is what an item stores")
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
        assertThat(Selector.TOKEN_PATTERN.matcher("FEAT").matches()).isTrue();
        assertThat(Selector.TOKEN_PATTERN.matcher("F").matches()).isTrue();
        assertThat(Selector.TOKEN_PATTERN.matcher("D-GTM").matches()).isTrue();
        assertThat(Selector.TOKEN_PATTERN.matcher("CHORE09").matches()).isTrue();

        assertThat(Selector.TOKEN_PATTERN.matcher("1FEAT").matches())
            .as("a leading digit — the number is the other half of the address")
            .isFalse();
        assertThat(Selector.TOKEN_PATTERN.matcher("FEAT-").matches()).isFalse();
        assertThat(Selector.TOKEN_PATTERN.matcher("D--GTM").matches()).isFalse();
        assertThat(Selector.TOKEN_PATTERN.matcher("").matches()).isFalse();
        assertThat(Selector.TOKEN_PATTERN.matcher("FEAT 51").matches()).isFalse();
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
            assertThat(Selector.TOKEN_PATTERN.matcher(pathological).matches())
                .as("a long input that cannot match must be refused, quickly and without "
                    + "exhausting the stack")
                .isFalse());
    }

    /**
     * The attribute key pattern accepts a token and rejects a sentence.
     *
     * <p>This is what the whitespace check on a vocabulary token became. The
     * question is the same — is this a token somebody can address an
     * attribute by — and the answer is stricter, because a key appears in a
     * machine answer and in a caller's argument map rather than only in a
     * display.
     *
     * <p>The pattern is possessive for the reason the selector's is, and the
     * timeout is what makes that a gate rather than a remark: it is enormously
     * more than the pattern needs and enormously less than the failure takes.
     */
    @Test
    void the_attribute_key_pattern_accepts_a_token_and_decides_a_long_input_in_one_pass() {
        assertThat(AttributeDefinition.KEY_PATTERN.matcher("cluster").matches()).isTrue();
        assertThat(AttributeDefinition.KEY_PATTERN.matcher("story_points").matches()).isTrue();
        assertThat(AttributeDefinition.KEY_PATTERN.matcher("t2").matches()).isTrue();

        assertThat(AttributeDefinition.KEY_PATTERN.matcher("two words").matches())
            .as("a key is a token — it travels in an argument map")
            .isFalse();
        assertThat(AttributeDefinition.KEY_PATTERN.matcher("Cluster").matches()).isFalse();
        assertThat(AttributeDefinition.KEY_PATTERN.matcher("1st").matches()).isFalse();
        assertThat(AttributeDefinition.KEY_PATTERN.matcher("trailing_").matches()).isFalse();
        assertThat(AttributeDefinition.KEY_PATTERN.matcher("").matches()).isFalse();

        String pathological = "a" + "_b".repeat(50_000) + "!";
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () ->
            assertThat(AttributeDefinition.KEY_PATTERN.matcher(pathological).matches())
                .as("a long input that cannot match must be refused, quickly and without "
                    + "exhausting the stack")
                .isFalse());
    }

    // ------------------------------------------------------------------
    // The composite keys.
    // ------------------------------------------------------------------

    /**
     * The relation key's identity, which the persistence provider relies on
     * to tell one edge from another.
     *
     * <p>Worth its own case because nothing else exercises it directly: a
     * broken {@code equals} would show up as an edge that will not update, or
     * one that updates the wrong row, and neither failure would point here.
     *
     * <p><strong>The type is part of it</strong>, and that is the difference
     * from the untyped edge this replaces: without it, asserting a second kind
     * of relation between the same two items would overwrite the first.
     */
    @Test
    void the_relation_key_identifies_an_edge_by_both_ends_and_its_type() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();
        UUID type = UUID.randomUUID();

        ItemRelation.Key key = new ItemRelation.Key(from, to, type);

        assertThat(key)
            .isEqualTo(new ItemRelation.Key(from, to, type))
            .hasSameHashCodeAs(new ItemRelation.Key(from, to, type))
            .isNotEqualTo(new ItemRelation.Key(to, from, type))
            .as("direction is part of the identity: A blocks B is not B blocks A")
            .isNotEqualTo(new ItemRelation.Key(from, UUID.randomUUID(), type))
            .as("and so is the type: two items may carry two edges of different types")
            .isNotEqualTo(new ItemRelation.Key(from, to, UUID.randomUUID()))
            .isNotEqualTo(null)
            .isNotEqualTo("not a key");
        assertThat(key).isEqualTo(key);
        assertThat(new ItemRelation.Key()).isNotEqualTo(key);
    }

    /**
     * The reference key's identity: the item and the position within it.
     *
     * <p>An ordinal is only unique within an item, so both halves are needed —
     * and a key on the ordinal alone would make position 0 of every item the
     * same row to the persistence provider.
     */
    @Test
    void the_reference_key_identifies_an_entry_by_its_item_and_its_position() {
        UUID item = UUID.randomUUID();

        ItemReference.Key key = new ItemReference.Key(item, 1);

        assertThat(key)
            .isEqualTo(new ItemReference.Key(item, 1))
            .hasSameHashCodeAs(new ItemReference.Key(item, 1))
            .isNotEqualTo(new ItemReference.Key(item, 2))
            .isNotEqualTo(new ItemReference.Key(UUID.randomUUID(), 1))
            .isNotEqualTo(null)
            .isNotEqualTo("not a key");
        assertThat(key).isEqualTo(key);
        assertThat(new ItemReference.Key()).isNotEqualTo(key);
    }
}
