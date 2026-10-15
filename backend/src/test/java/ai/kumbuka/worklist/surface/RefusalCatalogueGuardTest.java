/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The start-up check of DEC-0043 R12, held in every build.
 *
 * <p>{@link RefusalCatalogueGuard} runs {@link RefusalCatalogue#requireDeclared}
 * at start-up over {@link RefusalCode}. This class runs the same check over the
 * real codes, where it must pass, and over a set with one planted code that the
 * bundles do not word, where it must refuse and name the code.
 */
class RefusalCatalogueGuardTest {

    /** The real catalogue's first code, and one nobody declared a text for. */
    enum Planted {
        NOT_FOUND,
        PLANTED_UNDECLARED
    }

    @Test
    void the_catalogue_declares_a_message_and_a_remedy_for_every_code() {
        RefusalCatalogue.requireDeclared(RefusalCode.class);
    }

    @Test
    void the_check_refuses_a_code_without_a_text() {
        assertThatThrownBy(() -> RefusalCatalogue.requireDeclared(Planted.class))
            .as("RED STATE, observed: a planted code that no bundle words must stop the "
                + "service from starting, and the refusal names it")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("missing:RefusalMessages.plantedUndeclared")
            .hasMessageContaining("missing:RefusalRemedies.plantedUndeclared");
    }

    @Test
    void the_name_of_a_code_in_the_bundles_is_its_lower_camel_case() {
        assertThat(RefusalCatalogue.methodName(RefusalCode.SELECTOR_DECLARED_NOT_WRITTEN))
            .isEqualTo("selectorDeclaredNotWritten");
    }
}
