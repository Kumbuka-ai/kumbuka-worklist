/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The field classes per phase (REA-0011 D13, Annex A2) and where the addendum
 * is open (Annex A1).
 *
 * <p>The table is written here from Annex A2, row by row, and never read from
 * {@link FieldClass}: a test that took its expectation from the enum would
 * agree with whatever the enum says.
 */
class FieldClassTest {

    /** Annex A2: per class, the phases in which a field of it may be written. */
    private static final Map<FieldClass, List<Phase>> ANNEX_A2 = Map.of(
        FieldClass.CONTENT, List.of(Phase.DRAFT),
        FieldClass.CLASSIFICATION, List.of(Phase.DRAFT, Phase.OPEN),
        FieldClass.PLANNING, List.of(Phase.DRAFT, Phase.OPEN, Phase.IN_PROGRESS));

    @Test
    void each_class_is_writable_in_exactly_the_phases_annex_a2_names() {
        for (Map.Entry<FieldClass, List<Phase>> row : ANNEX_A2.entrySet()) {
            for (Phase phase : Phase.values()) {
                assertThat(row.getKey().writableIn(phase))
                    .as("%s in %s", row.getKey(), phase)
                    .isEqualTo(row.getValue().contains(phase));
            }
        }
    }

    @Test
    void a_closed_item_takes_a_write_of_no_class() {
        for (FieldClass fieldClass : FieldClass.values()) {
            assertThat(fieldClass.writableIn(Phase.CLOSED)).as("%s", fieldClass).isFalse();
        }
    }

    @Test
    void title_description_and_criteria_are_content() {
        assertThat(List.of(Field.TITLE, Field.DESCRIPTION, Field.CRITERIA))
            .allSatisfy(field -> assertThat(FieldClass.of(field)).contains(FieldClass.CONTENT));
    }

    @Test
    void the_workstream_and_the_attribute_type_are_classification() {
        assertThat(FieldClass.of(Field.WORKSTREAM_ID)).contains(FieldClass.CLASSIFICATION);
        assertThat(FieldClass.ofAttribute("type")).isEqualTo(FieldClass.CLASSIFICATION);
    }

    @Test
    void the_other_fields_and_the_status_within_the_phase_are_planning() {
        assertThat(List.of(Field.STATUS, Field.MILESTONE_ID, Field.CONTRIBUTOR, Field.PARENT,
                Field.PRIORITY, Field.SEVERITY, Field.ATTRIBUTES))
            .allSatisfy(field -> assertThat(FieldClass.of(field)).contains(FieldClass.PLANNING));
        assertThat(FieldClass.ofAttribute("effort"))
            .as("a declared attribute other than type")
            .isEqualTo(FieldClass.PLANNING);
    }

    /** References and relations keep the rules they had; theirs are a step of their own. */
    @Test
    void references_and_relations_belong_to_no_class_yet() {
        assertThat(FieldClass.of(Field.REFERENCES)).isEmpty();
        assertThat(FieldClass.of(Field.RELATIONS)).isEmpty();
    }

    /** Annex A1: addendum in open and in_progress, in draft and closed not. */
    @Test
    void the_addendum_is_open_in_the_phases_open_and_in_progress_alone() {
        assertThat(FieldClass.addendumOpen(Phase.DRAFT)).isFalse();
        assertThat(FieldClass.addendumOpen(Phase.OPEN)).isTrue();
        assertThat(FieldClass.addendumOpen(Phase.IN_PROGRESS)).isTrue();
        assertThat(FieldClass.addendumOpen(Phase.CLOSED)).isFalse();
    }
}
