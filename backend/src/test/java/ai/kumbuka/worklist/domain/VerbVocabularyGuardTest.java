package ai.kumbuka.worklist.domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard on the verb names, in both directions.
 *
 * <h2>What it holds, and why a guard is needed for a name</h2>
 *
 * The platform carries ONE verb vocabulary across its services, and this
 * store's public methods are the words of it that this scheme carries — spelled
 * identically, not mapped. Identity is the whole mechanism: it makes the
 * caller-facing surface the union of the transitions rather than their sum, and
 * it is what lets the address say which service is meant instead of the verb.
 *
 * <p>A name has no compiler and no runtime behind it. Renaming {@code update}
 * to something service-private breaks nothing, compiles, passes every other
 * test in this repository, and is discovered only by the next reader who
 * assumed the shared meaning. That is precisely the class of defect a probe
 * has to carry, because nothing else will.
 *
 * <p>This is not hypothetical here. An earlier build of {@link ItemStore}
 * carried six deliberately service-private names, chosen to satisfy a rule
 * that no verb name exist in two services. That rule is retired; the names
 * outlived it by a sprint. This guard exists so the second drift is loud.
 *
 * <h2>Why the expectation is transcribed and not derived</h2>
 *
 * {@link #PLATFORM_VOCABULARY} and {@link #CARRIED_BY_THIS_SCHEME} are written
 * out by hand, from the ratified catalogue. Deriving either from
 * {@code ItemStore} would produce a test that agrees with whatever the class
 * says — internally consistent and evidence of nothing. A transcription can
 * disagree with the code, which is the only property that makes it a probe.
 *
 * <p>The cost is that the catalogue and this list can drift apart, and that is
 * the honest trade: this file is the place where the platform vocabulary
 * enters this repository, and it is one edit to keep it current.
 *
 * <h2>Both directions, and why neither alone is enough</h2>
 *
 * A verb too FEW — a method reverted to a service-private name, or removed —
 * is the drift this exists against. A verb too MANY is the other half: a new
 * public method invented under some name nobody checked is how a
 * service-private vocabulary grows back one method at a time. The set
 * comparison catches both and names the offender either way.
 *
 * <p>Runs as a plain unit test: reflection over one class, no database.
 */
class VerbVocabularyGuardTest {

    /**
     * The platform verb set, transcribed from section 2 of the catalogue.
     *
     * <p>Twenty-four, grouped as the catalogue groups them. Its purpose here
     * is narrow and it is not the main assertion: it catches a name that was
     * added to {@link #CARRIED_BY_THIS_SCHEME} without being a platform verb
     * at all. Without it, a fantasy name added in both places at once would
     * pass, and the guard would be a check that a list matches itself.
     */
    private static final Set<String> PLATFORM_VOCABULARY = Set.of(
        // Object lifecycle
        "create", "read", "update", "append", "withdraw", "query",
        // Commitment gates
        "send", "accept",
        // Work assignment
        "claim", "claim_next", "release",
        // Termination
        "close", "consume", "abandon", "block", "resume",
        // Graph
        "relate", "unrelate",
        // Planning
        "plan", "unplan", "advance",
        // Scheme-specific and platform
        "digest", "scopes", "validate");

    /**
     * The verbs this scheme carries TODAY, and therefore exactly the public
     * methods {@link ItemStore} has.
     *
     * <p>Six of the twenty-four. The catalogue's mapping table for this scheme
     * names more — the draw, the graph, the planning verbs, {@code validate} —
     * and none of them is built. They belong here when they are built and not
     * before: a verb listed here with no method behind it would make this
     * guard red on a truthful class, and a guard that is red for being ahead
     * of the code gets suppressed rather than fixed.
     *
     * <p>{@code close} is deliberately absent for a different reason, and it
     * stays absent: an item's terminality is reached through {@code update}
     * against scope-declared status vocabulary, and {@code close} on this
     * scheme addresses the iteration or the milestone. That asymmetry against
     * the sibling service is a decision, not a gap.
     */
    private static final Set<String> CARRIED_BY_THIS_SCHEME = Set.of(
        "create", "read", "update", "withdraw", "query", "accept");

    /**
     * Nothing may be claimed as carried that the platform does not have.
     *
     * <p>The check that keeps the main assertion from being self-referential.
     */
    @Test
    void every_verb_this_scheme_claims_to_carry_is_a_platform_verb() {
        assertThat(PLATFORM_VOCABULARY)
            .as("a verb this scheme carries is one of the platform's twenty-four. A name "
                + "that is not in that set is a service-private verb wearing the "
                + "vocabulary's clothes, and adding it here as well as to the class "
                + "would make this whole guard a list compared with itself")
            .containsAll(CARRIED_BY_THIS_SCHEME);
    }

    /**
     * The invariant: the public methods ARE the carried verbs. Exactly.
     *
     * <p>Compared as sets rather than as two containment checks, so that the
     * failure names what is extra and what is missing in one message. A caller
     * fixing one direction at a time is a caller running the suite twice to
     * learn what one run could have said.
     */
    @Test
    void the_public_methods_of_the_item_store_are_exactly_the_carried_verbs() {
        assertThat(publicVerbsOf(ItemStore.class))
            .as("the public methods of ItemStore are the platform verbs this scheme "
                + "carries, spelled identically — %s. A method missing from that set is "
                + "a verb drifted back to a service-private name, which breaks nothing "
                + "and is found by nobody; a method beyond it is a service-private "
                + "vocabulary growing back one method at a time",
                new TreeSet<>(CARRIED_BY_THIS_SCHEME))
            .isEqualTo(new TreeSet<>(CARRIED_BY_THIS_SCHEME));
    }

    /**
     * The public instance methods of a class, as verb names on the wire.
     *
     * <p>Static and synthetic methods are out: a static method reaches no
     * entity manager and is not a verb, and synthetic ones are the compiler's
     * and the coverage agent's rather than anybody's vocabulary.
     */
    private static Set<String> publicVerbsOf(Class<?> clazz) {
        Set<String> verbs = new TreeSet<>();
        for (Method method : clazz.getDeclaredMethods()) {
            int modifiers = method.getModifiers();
            if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers)
                || method.isSynthetic() || method.isBridge()) {
                continue;
            }
            verbs.add(wireNameOf(method.getName()));
        }
        return verbs;
    }

    /**
     * A Java method name as the vocabulary spells it.
     *
     * <p>The two forms coincide for every verb this scheme carries today, so
     * this does nothing yet. It is here because exactly one platform verb —
     * {@code claim_next} — is not a legal Java identifier, and the run that
     * builds the draw would otherwise find this guard red against a correctly
     * named {@code claimNext} and be tempted to loosen the guard rather than
     * add the mapping.
     */
    private static String wireNameOf(String methodName) {
        StringBuilder wire = new StringBuilder();
        for (char c : methodName.toCharArray()) {
            if (Character.isUpperCase(c)) {
                wire.append('_').append(Character.toLowerCase(c));
            } else {
                wire.append(c);
            }
        }
        return wire.toString();
    }

    /**
     * The list above is not a tally of the catalogue's own count — it IS one,
     * and the count is load-bearing enough to say out loud.
     *
     * <p>Twenty-four is the number after the two reductions the catalogue
     * records: the draw-candidate read folded into {@code query}, and the two
     * failure verbs folded into {@code abandon}. A transcription that had
     * quietly picked up a twenty-fifth would be a transcription of something
     * else.
     */
    @Test
    void the_transcribed_vocabulary_is_the_twenty_four_the_catalogue_settled_on() {
        assertThat(PLATFORM_VOCABULARY)
            .as("the catalogue settles on twenty-four verbs after two reductions. A "
                + "different count here means this transcription is of a different "
                + "revision than the one that was ratified")
            .hasSize(24);
    }

    /**
     * The names are wire names, and a wire name is lower case and unspaced.
     *
     * <p>Cheap, and it catches the transcription slip that would otherwise be
     * invisible: a verb written {@code claimNext} in the list above would
     * never match a method, and the failure would read as a missing method
     * rather than as a mistyped expectation.
     */
    @Test
    void every_transcribed_verb_is_spelled_as_a_wire_name() {
        List<String> malformed = PLATFORM_VOCABULARY.stream()
            .filter(verb -> !verb.equals(verb.toLowerCase(java.util.Locale.ROOT))
                || verb.isBlank())
            .sorted()
            .toList();

        assertThat(malformed)
            .as("a verb of the vocabulary is spelled as it travels: lower case, words "
                + "joined by underscores. A camel-cased entry here would fail against a "
                + "correctly named method and the message would blame the code")
            .isEmpty();
    }
}
