package ai.kumbuka.worklist.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape of a refusal reason: {@code <SUBJECT>_<STATE>}, the subject always
 * named, and no {@code NOT_} prefix.
 *
 * <p>The two services diverged on this without system. One reason is spelled
 * identically in both, arrived at independently; two others differ. One of
 * those differences is cosmetic. The other is not: a reason that names no
 * subject is a regression in a model whose whole purpose is to name what went
 * wrong. "Conflict" tells a caller that something clashed and leaves them to
 * guess with what.
 *
 * <h2>Why the reasons are read from the enum</h2>
 *
 * The refusal model exists so that a new reason is a compile error rather than
 * a silent 500 — the HTTP mapping is a switch with no default. This guard is
 * loaded from the same enum that switch is over, so a reason cannot be added
 * anywhere this does not see it.
 *
 * <h2>The grandfathered one</h2>
 *
 * One existing reason breaks the form. It is not corrected here and the
 * omission is deliberate: a reason name travels in the refusal payload, which
 * is the published contract, and renaming one is a contract change that is not
 * a run's to make. It sits in {@link #GRANDFATHERED} with what is wrong with
 * it, so the rule binds every reason added from now on while the debt stays
 * visible rather than silent.
 *
 * <p>Runs as a plain unit test: it reads enum constants and needs no database.
 */
class RefusalReasonFormTest {

    /**
     * Words that are not subjects, however they are spelled.
     *
     * <p>A leading quantifier is the failure the rule is about: it says that
     * something is absent without saying what, which is exactly the sentence
     * the typed refusal model exists to replace.
     */
    private static final Set<String> NOT_A_SUBJECT = Set.of("NOT", "NOTHING", "NO", "NONE");

    /**
     * The reasons that predate the rule, each with what is wrong with it.
     *
     * <p>Recorded so that the rule is not weakened to accommodate them. Every
     * entry is a change to a published name, which needs a decision about the
     * contract rather than a build that quietly renames it.
     */
    private static final Map<String, String> GRANDFATHERED = new LinkedHashMap<>(Map.of(
        "CONFLICT",
        "names no subject: it tells a caller that something clashed and leaves them "
            + "to guess with what — the difference from the sibling service that the "
            + "reasoning document calls the one that is not cosmetic"));

    @Test
    void every_refusal_reason_names_a_subject_and_a_state() {
        assertThat(malformed(reasonsOf(SourceTree.root("main"))))
            .as("a refusal reason is read by a caller deciding what to do next, and the "
                + "subject is the half that tells them where to look. Spell it "
                + "<SUBJECT>_<STATE>, name the subject, and do not lead with the "
                + "negation. One existing reason breaks this and is listed in "
                + "GRANDFATHERED with the reason it is not being renamed here — a "
                + "reason name is part of the published refusal payload")
            .isEmpty();
    }

    /**
     * Every grandfathered entry names a reason that still exists.
     *
     * <p>Without this, a reason that was corrected would leave an exemption
     * matching nothing — and an exemption that exempts nothing is
     * indistinguishable from a rule nobody needs.
     */
    @Test
    void every_grandfathered_entry_names_a_reason_that_still_exists() {
        assertThat(reasonsOf(SourceTree.root("main")))
            .as("a grandfathered reason that no longer exists is a debt already paid. "
                + "Remove the entry so the list keeps meaning what it says")
            .containsAll(GRANDFATHERED.keySet());
    }

    /**
     * The red state, observed on every build.
     *
     * <p>The grandfathered names are themselves the observation: they are real
     * reasons, in the tree, that the detection reports when the exemption is
     * not applied. So the check is watched finding a violation of each kind it
     * claims to find, without a fixture having to imitate one.
     */
    @Test
    void the_check_reports_each_kind_of_malformed_reason() {
        List<String> found = malformedIgnoringExemptions(List.copyOf(GRANDFATHERED.keySet()));
        assertThat(found)
            .as("RED STATE, observed: the grandfathered reasons are real names in this "
                + "service, and the detection must report every one of them when the "
                + "exemption is not applied. An empty list here would mean the green "
                + "assertion above is measuring nothing")
            .containsExactlyInAnyOrderElementsOf(GRANDFATHERED.keySet());

        assertThat(malformedIgnoringExemptions(List.of("NOT_FOUND", "NOTHING_TO_CLAIM")))
            .as("RED STATE, observed: the other two shapes must be reported as well — a "
                + "reason leading with the negation, and one leading with a quantifier. "
                + "Both are real names in the sibling service, so the detection is "
                + "watched on names somebody actually chose rather than invented ones")
            .containsExactly("NOT_FOUND", "NOTHING_TO_CLAIM");
    }

    /** Every refusal reason this service declares, from the enums themselves. */
    private static List<String> reasonsOf(Path root) {
        List<String> reasons = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            String fqcn = SourceTree.fqcn(root, file);
            if (!fqcn.endsWith("Exception")) {
                continue;
            }
            Class<?> reason = reasonEnumOf(fqcn);
            assertThat(reason)
                .as("%s must declare a Reason enum: an exception type without one is "
                    + "outside the typed refusal model, which is the thing this checks",
                    fqcn)
                .isNotNull();
            for (Object constant : reason.getEnumConstants()) {
                reasons.add(((Enum<?>) constant).name());
            }
        }
        assertThat(reasons)
            .as("this service declares refusal reasons; finding none would mean the "
                + "guard is looking in the wrong place and passing because of it")
            .isNotEmpty();
        return reasons;
    }

    /**
     * The nested {@code Reason} enum of an exception type, or null.
     *
     * <p>Loaded without initialising: annotation and constant reflection needs
     * the class linked, not initialised, and initialising application classes
     * from a unit test would drag in CDI.
     */
    private static Class<?> reasonEnumOf(String fqcn) {
        try {
            Class<?> loaded = Class.forName(fqcn + "$Reason", false,
                RefusalReasonFormTest.class.getClassLoader());
            return loaded.isEnum() ? loaded : null;
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }

    private static List<String> malformed(List<String> reasons) {
        return reasons.stream().filter(name -> !GRANDFATHERED.containsKey(name))
            .filter(RefusalReasonFormTest::breaksTheForm)
            .toList();
    }

    /** The same detection with no exemption applied, for the red state. */
    private static List<String> malformedIgnoringExemptions(List<String> reasons) {
        return reasons.stream().filter(RefusalReasonFormTest::breaksTheForm).toList();
    }

    /**
     * Whether a name breaks the form.
     *
     * <p>Two segments at least, because {@code <SUBJECT>_<STATE>} has two, and
     * a first segment that is a word rather than a quantifier. This does not
     * try to decide whether the subject is the RIGHT one — that needs to know
     * what the refusal is about, and a guard that guessed at it would be one
     * that can be argued with rather than obeyed.
     */
    private static boolean breaksTheForm(String name) {
        String[] parts = name.split("_");
        return parts.length < 2 || NOT_A_SUBJECT.contains(parts[0]);
    }
}
