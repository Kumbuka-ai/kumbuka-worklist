/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.tenancy;

import ai.kumbuka.worklist.fixture.LogLines;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The migration line of the tenancy facade: the callback's marker and the
 * tenant it bound, as the template in the memory service writes it.
 *
 * <p>The expected text is written here, from the template, and not composed
 * from the constants of the facade or the callback.
 */
class TenancyLogTest {

    private static final UUID TENANT = UUID.fromString("3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f");

    @Test
    void a_binding_is_one_info_line_with_the_marker_and_the_tenant() {
        List<LogRecord> lines = LogLines.of(TenancyLog.class, () ->
            TenancyLog.bindingApplied(TenancyLog.TenantId.of(TENANT)));

        assertThat(lines)
            .as("RED STATE, observed: with the logger call in bindingApplied commented out, "
                + "no line was written. One INFO line, the marker first, then the tenant")
            .singleElement()
            .satisfies(line -> {
                assertThat(line.getLevel().intValue()).isEqualTo(Level.INFO.intValue());
                assertThat(LogLines.text(line))
                    .isEqualTo("tenant binding applied: app.tenant_id=" + TENANT
                        + " before a migration");
            });
    }
}
