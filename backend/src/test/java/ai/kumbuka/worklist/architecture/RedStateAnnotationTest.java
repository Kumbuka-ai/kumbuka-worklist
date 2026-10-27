/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

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
 * <p>The marker is read in the description itself: a string literal handed to
 * {@code .as(}, over as many lines and concatenations as the description runs.
 * The same words in a comment, in a field, or in a literal that no assertion
 * carries are not an observed red run and do not count.
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

    /** Where an assertion description begins. */
    private static final Pattern DESCRIPTION = Pattern.compile("\\.as\\s*\\(");

    @Test
    void every_gate_carries_an_assertion_marked_red_state() {
        Path root = SourceTree.root("test");
        Map<String, String> sources = new LinkedHashMap<>();
        for (Path file : SourceTree.files(root)) {
            sources.put(SourceTree.fqcn(root, file), SourceTree.text(file));
        }
        assertThat(gatesWithoutARedState(sources))
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
     * Planted sources go through the same method as the tree: one gate with no
     * marker, one with the marker only outside an assertion description, and
     * one with it in a description that runs over several lines. If the
     * detection were broken — a wrong marker, a wrong reading of where the
     * description is — this case would accept the first two, and the assertion
     * above would be vacuous for every gate at once. That is the single point
     * of failure this file has, so it is the one that gets watched.
     */
    @Test
    void the_check_reports_a_gate_with_no_observed_red_run() {
        String gates = SourceTree.BASE + "." + GUARD_PACKAGE + ".";
        Map<String, String> planted = new LinkedHashMap<>();
        planted.put(gates + "WithoutTest", """
            class WithoutTest {
                @Test
                void a_rule_holds() {
                    assertThat(offenders()).as("the rule").isEmpty();
                }
            }
            """);
        planted.put(gates + "OutsideTest", """
            class OutsideTest {
                private static final String NOTE = "RED STATE, observed: once, by hand";

                /* RED STATE, observed: in a comment */
                @Test
                void a_rule_holds() {
                    assertThat(offenders()).as("the rule").isEmpty();
                }
            }
            """);
        planted.put(gates + "WithTest", """
            class WithTest {
                @Test
                void the_check_reports_a_violation() {
                    assertThat(offenders(planted()))
                        .as("a planted offence, "
                            + "RED STATE, observed: ...")
                        .isNotEmpty();
                }
            }
            """);
        planted.put(SourceTree.BASE + ".domain.PlainTest", """
            class PlainTest {
                @Test
                void adds() {
                    assertThat(1 + 1).as("the sum").isEqualTo(2);
                }
            }
            """);
        assertThat(gatesWithoutARedState(planted))
            .as("RED STATE, observed: a gate with no marker, and one whose marker stands "
                + "outside an assertion description, are reported; one whose description "
                + "carries it over two lines is accepted, and a plain test is no gate")
            .containsExactly(gates + "WithoutTest", gates + "OutsideTest");
    }

    /** Every gate among the sources, by name, whose assertions carry no red-state marker. */
    private static List<String> gatesWithoutARedState(Map<String, String> sources) {
        List<String> offenders = new ArrayList<>();
        int gates = 0;
        for (Map.Entry<String, String> source : sources.entrySet()) {
            if (!isGate(source.getKey())) {
                continue;
            }
            gates++;
            // The marker is read from code, not from javadoc: a gate that
            // merely describes a red run in prose is the case this refuses.
            boolean marked = descriptions(SourceTree.code(source.getValue())).stream()
                .anyMatch(description -> description.contains(MARKER));
            if (!marked) {
                offenders.add(source.getKey());
            }
        }
        assertThat(gates)
            .as("this service has gates; finding none would mean the guard is looking in "
                + "the wrong place and passing because of it")
            .isPositive();
        return offenders;
    }

    private static boolean isGate(String fqcn) {
        String pkg = fqcn.substring(0, fqcn.lastIndexOf('.'));
        if (pkg.equals(SourceTree.BASE + "." + GUARD_PACKAGE)) {
            // The shared reader is not a gate: it holds no expectation.
            return !fqcn.endsWith("SourceTree");
        }
        return GUARD_NAMES.stream().anyMatch(fqcn::contains);
    }

    /**
     * The text of every assertion description in a piece of code: the string
     * literals and text blocks in the argument of each {@code .as(}, joined.
     * Literals are stepped over while looking for {@code .as(}, so a source a
     * gate plants as text does not count as the gate's own description.
     */
    static List<String> descriptions(String code) {
        List<String> found = new ArrayList<>();
        int i = 0;
        while (i < code.length()) {
            int literalEnd = endOfLiteral(code, i);
            if (literalEnd > i) {
                i = literalEnd;
            } else if (DESCRIPTION.matcher(code).region(i, code.length()).lookingAt()) {
                i = description(code, code.indexOf('(', i) + 1, found);
            } else {
                i++;
            }
        }
        return found;
    }

    /** Reads one description from after its opening parenthesis; answers where it ends. */
    private static int description(String code, int start, List<String> found) {
        StringBuilder text = new StringBuilder();
        int depth = 1;
        int i = start;
        while (i < code.length() && depth > 0) {
            int literalEnd = endOfLiteral(code, i);
            if (literalEnd > i) {
                if (code.charAt(i) == '"') {
                    int quotes = code.startsWith("\"\"\"", i) ? 3 : 1;
                    text.append(code, i + quotes, Math.max(i + quotes, literalEnd - quotes));
                }
                i = literalEnd;
                continue;
            }
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            i++;
        }
        found.add(text.toString());
        return i;
    }

    /**
     * Where a string, text block or character literal starting at {@code i}
     * ends, one past its closing quote; {@code i} itself where none starts.
     */
    private static int endOfLiteral(String code, int i) {
        char c = code.charAt(i);
        if (code.startsWith("\"\"\"", i)) {
            int close = code.indexOf("\"\"\"", i + 3);
            return close < 0 ? code.length() : close + 3;
        }
        if (c != '"' && c != '\'') {
            return i;
        }
        int j = i + 1;
        while (j < code.length() && code.charAt(j) != c && code.charAt(j) != '\n') {
            j += code.charAt(j) == '\\' ? 2 : 1;
        }
        return Math.min(j + 1, code.length());
    }
}
