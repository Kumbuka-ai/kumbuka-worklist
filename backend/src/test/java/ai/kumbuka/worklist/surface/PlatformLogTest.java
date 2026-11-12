/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.Item;
import ai.kumbuka.worklist.domain.Milestone;
import ai.kumbuka.worklist.fixture.LogLines;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The lines of a milestone demotion and of an edge, as the platform facade
 * writes them: by the numbers the service allocated and the scope's id
 * (DEC-0051, C13.2).
 *
 * <p>The expected texts are written here from the order that brought the
 * lines back, not read off the facade.
 */
class PlatformLogTest {

    private static final UUID SCOPE = UUID.fromString("0f6a2b3c-4d5e-4f60-8a1b-2c3d4e5f6a7b");

    /** DEBUG is below the configured level of the service's category, so it is lifted here. */
    private final Logger category = Logger.getLogger(PlatformLog.class.getName());
    private Level configured;

    @BeforeEach
    void debugOn() {
        configured = category.getLevel();
        category.setLevel(Level.FINE);
    }

    @AfterEach
    void levelBack() {
        category.setLevel(configured);
    }

    @Test
    void a_demotion_is_one_debug_line_by_the_demoted_number_and_the_scope_id() {
        Milestone demoted = new Milestone();
        demoted.number = 7;

        List<LogRecord> lines = LogLines.of(PlatformLog.class, () ->
            PlatformLog.milestoneDemoted(PlatformLog.TechnicalAddress.of(demoted),
                PlatformLog.ScopeId.of(SCOPE)));

        assertThat(lines)
            .as("RED STATE, observed: with the logger call in milestoneDemoted commented "
                + "out, no line was written. One DEBUG line, by the number and the scope id")
            .singleElement()
            .satisfies(line -> {
                assertThat(line.getLevel().intValue()).isEqualTo(Level.FINE.intValue());
                assertThat(LogLines.text(line))
                    .isEqualTo("milestone 7 demoted in scope " + SCOPE);
            });
    }

    @Test
    void an_edge_s_lines_carry_its_source_and_its_target() {
        Item source = new Item();
        source.number = 11L;
        Item target = new Item();
        target.number = 12L;
        PlatformLog.TechnicalAddress from = PlatformLog.TechnicalAddress.of(source);
        PlatformLog.TechnicalAddress to = PlatformLog.TechnicalAddress.of(target);
        PlatformLog.ScopeId scope = PlatformLog.ScopeId.of(SCOPE);

        List<LogRecord> lines = LogLines.of(PlatformLog.class, () -> {
            PlatformLog.relationAsserted(from, to, scope);
            PlatformLog.related(from, to, scope);
            PlatformLog.relationWithdrawn(from, to, scope);
            PlatformLog.unrelated(from, to, scope);
        });

        assertThat(lines).extracting(LogLines::text)
            .as("both ends of the edge, by number, in every line of relate and unrelate")
            .containsExactly(
                "relation asserted from 11 to 12 in scope " + SCOPE,
                "relate on 11 to 12 in scope " + SCOPE,
                "relation withdrawn from 11 to 12 in scope " + SCOPE,
                "unrelate on 11 to 12 in scope " + SCOPE);
    }
}
