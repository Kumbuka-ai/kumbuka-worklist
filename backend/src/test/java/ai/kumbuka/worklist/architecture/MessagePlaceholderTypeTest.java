/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.architecture;

import ai.kumbuka.worklist.fixture.ProseBundleFixture;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A placeholder takes only the platform's vocabulary
 * ({@code code-conventions/service-construction.md}, texts addressed to a
 * caller, rule 2).
 *
 * <p>By reflection over every message bundle interface: each parameter of each
 * method is one of {@code ArgumentName}, {@code ArgumentPath}, {@code VerbName},
 * {@code ReasonCode} and {@code Bound}, or a {@code List} of one of them --
 * never a {@code String}, never anything else. The five types are made only
 * from the service's declaration, so a text cannot carry what a call sent
 * (DEC-0043 R18), and this check is what keeps a {@code String} parameter from
 * opening that door again.
 *
 * <p>The types are named here by their simple names and not imported: the
 * expectation is this rule's, not the production code's.
 */
class MessagePlaceholderTypeTest {

    private static final String SURFACE = "ai.kumbuka.worklist.surface.";

    private static final Set<String> PLACEHOLDERS = Set.of(
        SURFACE + "ArgumentName", SURFACE + "ArgumentPath", SURFACE + "VerbName",
        SURFACE + "ReasonCode", SURFACE + "Bound");

    @Test
    void every_placeholder_is_of_a_type_made_from_the_declaration() {
        List<Class<?>> bundles = MessageBundleTest.bundles();
        assertThat(bundles).as("the bundles are found").isNotEmpty();
        List<String> offenders = new ArrayList<>();
        bundles.forEach(bundle -> offenders.addAll(untyped(bundle)));
        assertThat(offenders)
            .as("a placeholder takes ArgumentName, ArgumentPath, VerbName, ReasonCode or "
                + "Bound, or a List of one of them, so that a text can name the platform's "
                + "vocabulary and never a caller's input")
            .isEmpty();
    }

    @Test
    void the_placeholders_this_service_names_are_reached() {
        long typed = MessageBundleTest.bundles().stream()
            .flatMap(b -> java.util.Arrays.stream(b.getDeclaredMethods()))
            .mapToLong(Method::getParameterCount)
            .sum();
        assertThat(typed)
            .as("the bundles carry placeholders; none at all would leave the check above "
                + "with nothing to look at")
            .isPositive();
    }

    @Test
    void the_check_reports_a_string_placeholder() {
        assertThat(untyped(ProseBundleFixture.class))
            .as("RED STATE, observed: a String parameter must be reported, or the check "
                + "above would pass whatever the bundles take")
            .containsExactly("ProseBundleFixture.refused(java.lang.String)");
    }

    private static List<String> untyped(Class<?> bundle) {
        List<String> offenders = new ArrayList<>();
        for (Method method : bundle.getDeclaredMethods()) {
            for (Type type : method.getGenericParameterTypes()) {
                if (!admitted(type)) {
                    offenders.add(bundle.getSimpleName() + "." + method.getName() + "("
                        + type.getTypeName() + ")");
                }
            }
        }
        return offenders;
    }

    private static boolean admitted(Type type) {
        if (type instanceof Class<?> plain) {
            return PLACEHOLDERS.contains(plain.getName());
        }
        if (type instanceof ParameterizedType generic
                && generic.getRawType() == List.class) {
            Type element = generic.getActualTypeArguments()[0];
            return element instanceof Class<?> plain && PLACEHOLDERS.contains(plain.getName());
        }
        return false;
    }
}
