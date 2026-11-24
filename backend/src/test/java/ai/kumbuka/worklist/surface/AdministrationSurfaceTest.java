/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.ScopeUsage;
import ai.kumbuka.worklist.platform.ScopeDirectory;
import jakarta.enterprise.inject.Vetoed;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The right each call of the administration surface asks the directory for
 * (REA-0011 S4 acceptance K5, C1.6): a read of the model the read right, every
 * call that writes {@link ScopeDirectory.Access#ADMINISTER}, which refuses a
 * subject without the write right with {@code ROLE_INSUFFICIENT}.
 *
 * <p>The expectation is the access column of {@code administration-surface.tsv},
 * written from the order, and not read from the class under test. The calls
 * are the public methods of the surface, each named in snake case, and they
 * must be the calls of the file exactly, so that a call added without a row
 * -- and without a decision about its right -- turns this red.
 *
 * <p>The directory is a stand-in that records the access it is asked for and
 * stops the call there: what follows the resolution is the domain's, and the
 * integration test {@code AdministrationSurfaceIT} runs it against the
 * database. Every call resolves the scope before it touches anything else,
 * which is what a stop at the directory observes.
 *
 * <h2>The red probe</h2>
 *
 * With one writing call resolving the scope for {@code READ} -- the
 * {@code Access.ADMINISTER} of {@code declareStatus} turned into
 * {@code Access.READ} -- {@link #every_call_asks_the_directory_for_the_right_its_row_declares}
 * reports {@code declare_status asked READ}.
 */
class AdministrationSurfaceTest {

    /** The text every string parameter of a probed call carries. */
    private static final String PROBE = "probe";

    /** Stops a call at the directory, once the access it asked for is recorded. */
    private static final class Stopped extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /**
     * The directory, recording what it is asked and answering nothing.
     * Vetoed, because the scope of the class it extends is inherited and the
     * integration tests share this tree: it must never become a second bean
     * of the directory.
     */
    @Vetoed
    private static final class RecordingDirectory extends ScopeDirectory {
        final List<Access> asked = new ArrayList<>();

        @Override
        public ScopeAccess resolve(String subject, String slug, Access access) {
            asked.add(access);
            throw new Stopped();
        }
    }

    @Test
    void the_public_calls_of_the_surface_are_the_calls_its_file_declares() {
        assertThat(new TreeSet<>(callsOf(publicCalls())))
            .as("every call of the administration surface is a row of its declaration, "
                + "and every row a call (C1.6)")
            .isEqualTo(new TreeSet<>(AdministrationSurfaceSpecification.calls()));
    }

    @Test
    void every_call_asks_the_directory_for_the_right_its_row_declares() {
        assertThat(accessOffences(askedAccess()))
            .as("a read of the model asks for the read right, and every call that writes "
                + "for the write right of the scope under ADMINISTER (K5)")
            .isEmpty();
    }

    /**
     * The red state, observed on every build: a writing call that asked for
     * READ and a read that asked for ADMINISTER are each reported.
     */
    @Test
    void the_check_reports_a_call_asking_for_another_right() {
        Map<String, ScopeDirectory.Access> planted = new LinkedHashMap<>();
        planted.put("declare_status", ScopeDirectory.Access.READ);
        planted.put("statuses", ScopeDirectory.Access.ADMINISTER);
        planted.put("withdraw_status", ScopeDirectory.Access.ADMINISTER);
        assertThat(accessOffences(planted))
            .as("RED STATE, observed: each call asking for a right other than its row's "
                + "must be reported")
            .containsExactly("declare_status asked READ", "statuses asked ADMINISTER");
    }

    /** The calls whose access differs from their row's, as {@code call asked ACCESS}. */
    static List<String> accessOffences(Map<String, ScopeDirectory.Access> asked) {
        List<String> offences = new ArrayList<>();
        asked.forEach((call, access) -> {
            ScopeDirectory.Access expected = AdministrationSurfaceSpecification
                .callsAsking(AdministrationSurfaceSpecification.READ).contains(call)
                ? ScopeDirectory.Access.READ : ScopeDirectory.Access.ADMINISTER;
            if (access != expected) {
                offences.add(call + " asked " + access);
            }
        });
        return offences;
    }

    /** Every public call of the surface, invoked once, with the access it asked for. */
    private static Map<String, ScopeDirectory.Access> askedAccess() {
        Map<String, ScopeDirectory.Access> asked = new LinkedHashMap<>();
        for (Method call : publicCalls()) {
            RecordingDirectory directory = new RecordingDirectory();
            AdministrationSurface surface = new AdministrationSurface();
            surface.scopes = directory;
            try {
                call.invoke(surface, argumentsFor(call));
            } catch (InvocationTargetException e) {
                assertThat(e.getCause())
                    .as("%s stops at the directory, so it resolved the scope before anything "
                        + "else", call.getName())
                    .isInstanceOf(Stopped.class);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
            assertThat(directory.asked).as("%s resolves the scope once", call.getName())
                .hasSize(1);
            asked.put(snake(call.getName()), directory.asked.get(0));
        }
        assertThat(asked).as("the probe invoked calls at all").isNotEmpty();
        return asked;
    }

    private static List<Method> publicCalls() {
        return Arrays.stream(AdministrationSurface.class.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()))
            .filter(m -> !Modifier.isStatic(m.getModifiers()))
            .toList();
    }

    private static List<String> callsOf(List<Method> methods) {
        return methods.stream().map(m -> snake(m.getName())).toList();
    }

    private static String snake(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    /** A well-formed value for each parameter type a call takes. */
    private static final Map<Class<?>, Object> PROBE_VALUES = Map.of(
        String.class, PROBE,
        long.class, 1L,
        int.class, 1,
        Map.class, Map.of(),
        ScopeUsage.Property.class, ScopeUsage.Property.PRIORITY,
        AdministrationSurface.StatusDeclaration.class,
        new AdministrationSurface.StatusDeclaration(PROBE, 1, true, false,
            false, false, false, false, false),
        AdministrationSurface.AttributeDeclaration.class,
        new AdministrationSurface.AttributeDeclaration(PROBE, PROBE, "text", 1, false),
        AdministrationSurface.RelationDeclaration.class,
        new AdministrationSurface.RelationDeclaration(PROBE, false, 1, null));

    /** A well-formed value for each parameter, so that no form check stops the call. */
    private static Object[] argumentsFor(Method call) {
        return Arrays.stream(call.getParameterTypes())
            .map(type -> Optional.ofNullable(PROBE_VALUES.get(type)).orElseThrow(
                () -> new IllegalStateException("no probe value for " + type)))
            .toArray();
    }
}
