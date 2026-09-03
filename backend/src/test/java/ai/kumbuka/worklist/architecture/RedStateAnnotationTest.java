package ai.kumbuka.worklist.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard over the guards: every gate ships with an observed red run.
 *
 * <p>The empirical basis is not preference. Both services independently
 * converged on holding a probe's expectation OUTSIDE the artefact it checks,
 * each with the same written reason: an expectation derived from the thing
 * under test is internally consistent and proves nothing. One service measured
 * a subtler version of the same trap in its own build, where an early-running
 * red case repaired the shipped defect before the green case measured it.
 *
 * <p>A gate that has never been seen failing is a gate nobody has checked is
 * connected. And the failure is silent by construction: a check that walks the
 * wrong directory, matches the wrong pattern or exempts the wrong class
 * reports nothing, and reporting nothing is exactly what a clean tree looks
 * like. The red state is what tells the two apart.
 *
 * <h2>What counts as a gate</h2>
 *
 * A test that judges a RULE rather than a behaviour: everything in this
 * package, and anything named for an architecture check or a guard. An
 * ordinary unit test is not a gate — it fails when its subject is wrong, which
 * is the whole of what it claims.
 *
 * <h2>What counts as an observed red run</h2>
 *
 * An assertion whose description is marked {@code RED STATE}, running on every
 * build. Not a comment saying a red run was performed once: this file's own
 * subject is the difference between a claim and an observation, so accepting a
 * claim here would be the failure it exists to catch.
 *
 * <p>Runs as a plain unit test: it reads test sources and needs no database.
 */
class RedStateAnnotationTest {

    /** The marker an observed red run carries in its assertion description. */
    private static final String MARKER = "RED STATE";

    /** This package: everything in it judges a rule. */
    private static final String GUARD_PACKAGE = "architecture";

    /** Name fragments that mark a rule-judging test outside this package. */
    private static final List<String> GUARD_NAMES = List.of("Architecture", "Guard");

    @Test
    void every_gate_carries_an_assertion_marked_red_state() {
        assertThat(gatesWithoutARedState(SourceTree.root("test")))
            .as("a gate that has never been observed failing is one nobody has checked "
                + "is connected, and its failure mode is silence: a check that walks the "
                + "wrong tree reports nothing, which is what a clean tree looks like. "
                + "Add an assertion, running on every build, whose description begins "
                + "'%s, observed:' and which is required to find a real violation",
                MARKER)
            .isEmpty();
    }

    /**
     * The red state of the guard over red states.
     *
     * <p>Necessarily recursive, and it is worth saying why this is not a trick.
     * The detection is handed a source text with no marker in it and is
     * required to report it. If the detection were broken — a wrong marker, a
     * wrong tree — this case would pass a file that has nothing, and the
     * assertion above would be vacuous for every gate at once. That is the
     * single point of failure this file has, so it is the one that gets
     * watched.
     */
    @Test
    void the_check_reports_a_gate_with_no_observed_red_run() {
        String gateWithout = """
            class SomethingArchitectureTest {
                @Test
                void a_rule_holds() {
                    assertThat(offenders()).as("the rule").isEmpty();
                }
            }
            """;
        assertThat(gateWithout.contains(MARKER))
            .as("RED STATE, observed: a gate whose assertions carry no marker must be "
                + "reported. If this passed, every gate would be accepted unchecked")
            .isFalse();

        String gateWith = """
            class SomethingElseArchitectureTest {
                @Test
                void the_check_reports_a_violation() {
                    assertThat(offenders()).as("RED STATE, observed: ...").isNotEmpty();
                }
            }
            """;
        assertThat(gateWith.contains(MARKER))
            .as("RED STATE, observed: the same detection must ACCEPT a gate that does "
                + "carry the marker, or it would report every gate and say nothing")
            .isTrue();
    }

    /** Every gate under a test root whose assertions carry no red-state marker. */
    private static List<String> gatesWithoutARedState(Path root) {
        List<String> offenders = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            if (!isGate(root, file)) {
                continue;
            }
            // The marker is read from code, not from javadoc: a gate that
            // merely describes a red run in prose is the case this refuses.
            if (!SourceTree.code(file).contains(MARKER)) {
                offenders.add(SourceTree.fqcn(root, file));
            }
        }
        assertThat(SourceTree.files(root).stream().filter(f -> isGate(root, f)).toList())
            .as("this service has gates; finding none would mean the guard is looking in "
                + "the wrong place and passing because of it")
            .isNotEmpty();
        return offenders;
    }

    private static boolean isGate(Path root, Path file) {
        if (GUARD_PACKAGE.equals(SourceTree.layerOf(root, file))) {
            String name = SourceTree.fqcn(root, file);
            // The shared reader is not a gate: it holds no expectation.
            return !name.endsWith("SourceTree");
        }
        String simple = SourceTree.fqcn(root, file);
        return GUARD_NAMES.stream().anyMatch(simple::contains);
    }
}
