/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.architecture;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every text that tells a caller what to do is phrased positively
 * (DEC-0043 R21; {@code code-conventions/service-construction.md}, texts
 * addressed to a caller, rule 4).
 *
 * <p>The lint reads the files of the instructing texts -- the remedies, the
 * tool descriptions, the argument descriptions and the value descriptions --
 * and refuses the words of R21, as whole words and whatever their case: not,
 * n't, never, no, none, nothing, nobody, neither, nor, cannot, without, avoid,
 * unless, instead of, refrain. It keeps no list of exceptions. Messages and
 * notices may state a finding in the negative and are not linted.
 *
 * <p>The pattern is kumbuka-dispatch's, taken as written there, which states
 * it as the binding form for every service that copies this lint: the phrase
 * is matched as {@code \binstead\s+of\b}, so a line break or a second space
 * between its words does not hide it, and the contraction as {@code n't\b},
 * at the end of any word. The files it reads keep this service's names, in
 * the plural; the reference's are in the singular.
 *
 * <p>It reads at least {@link #FLOOR} texts, so that a bundle read empty or
 * not found at all cannot pass as a bundle with nothing to refuse. The
 * reference asks for more than 100 of its 139 texts; this service has 94, and
 * the floor is the same ratio, 100/139 of 94, rounded up, as kumbuka-memory
 * set its own.
 *
 * <p>This service declares no next-step texts: its answers carry no
 * {@code next} yet. The day they do, their file joins the list below.
 */
class PositivePhrasingLintTest {

    /** The bundles whose texts instruct. */
    private static final List<String> INSTRUCTING = List.of(
        "remedies", "tools", "arguments", "values");

    /** The words of DEC-0043 R21, whole and in any case; "n't" ends a word. */
    private static final Pattern FORBIDDEN = Pattern.compile(
        "\\b(not|never|no|none|nothing|nobody|neither|nor|cannot|without|avoid|unless|"
            + "refrain)\\b|n't\\b|\\binstead\\s+of\\b",
        Pattern.CASE_INSENSITIVE);

    /** The fewest instructing texts the lint must have read. */
    static final int FLOOR = 68;

    @Test
    void every_instructing_text_states_the_act_to_perform() {
        List<String> offenders = new ArrayList<>();
        int read = 0;
        for (String bundle : INSTRUCTING) {
            Properties texts = MessageBundleTest.texts(bundle);
            assertThat(texts.stringPropertyNames())
                .as("%s carries texts; an empty file would pass the lint unread", bundle)
                .isNotEmpty();
            read += texts.size();
            for (String key : texts.stringPropertyNames()) {
                offenders.addAll(negations(bundle + "." + key, texts.getProperty(key)));
            }
        }
        assertThat(read).as("the lint reads every instructing text").isGreaterThan(FLOOR);
        assertThat(offenders)
            .as("an instruction names the act to perform; a prohibition names the act it "
                + "forbids, and a model takes that act up. Rephrase as what to do")
            .isEmpty();
    }

    @Test
    void the_lint_reports_a_prohibition() {
        assertThat(negations("planted", "Do not retry; wait instead of polling."))
            .as("RED STATE, observed: 'not' and 'instead of' must be reported")
            .hasSize(2);
        assertThat(negations("planted", "Don't retry."))
            .as("RED STATE, observed: a contraction ending in n't must be reported")
            .hasSize(1);
        assertThat(negations("planted", "Send the notice, the knot and the nothingness."))
            .as("and a word that merely contains one of them is a whole other word")
            .isEmpty();
        assertThat(negations("planted", "Read the item instead\nof claiming it again."))
            .as("RED STATE, observed: the phrase across a line break is reported")
            .hasSize(1);
        assertThat(negations("planted", "Read the item instead  of claiming it again."))
            .as("RED STATE, observed: and across a second space")
            .hasSize(1);
    }

    private static List<String> negations(String where, String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = FORBIDDEN.matcher(text);
        while (matcher.find()) {
            found.add(where + ": '" + matcher.group() + "' in: " + text);
        }
        return found;
    }
}
