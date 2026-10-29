/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.rest;

import ai.kumbuka.worklist.surface.SurfaceDeclaration;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The start-up check of ADR-0039, held in every build: each verb the REST
 * surface serves is declared, and each declared verb is served.
 */
class RouteDeclarationGuardTest {

    private static List<String> declared() {
        return SurfaceDeclaration.verbs().stream().map(v -> v.name().name()).toList();
    }

    @Test
    void every_served_verb_is_declared_and_every_declared_verb_served() {
        assertThatCode(() -> RouteDeclarationGuard.requireDeclared(
            RouteDeclarationGuard.served(), declared())).doesNotThrowAnyException();
    }

    @Test
    void the_check_refuses_a_verb_whose_declaration_is_missing() {
        List<String> withoutClaim = declared().stream()
            .filter(verb -> !verb.equals("claim"))
            .toList();
        assertThatThrownBy(() -> RouteDeclarationGuard.requireDeclared(
                RouteDeclarationGuard.served(), withoutClaim))
            .as("RED STATE, observed: a served verb with its declaration removed must stop "
                + "the service from starting, and the refusal names the verb")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("undeclared=[claim]");
    }
}
