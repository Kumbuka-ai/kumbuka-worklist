/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.architecture;

import ai.kumbuka.worklist.surface.PlatformLog;
import ai.kumbuka.worklist.tenancy.TenancyLog;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A log line takes only the platform's vocabulary (DEC-0051, C4; C13.2).
 *
 * <h2>What it holds</h2>
 *
 * Every parameter of every method of the two facades is one of
 * {@link #ALLOWED} -- never a {@code String}, a number, a uuid or any other
 * type a request could fill. And none of the types a facade takes can be
 * built from such a value: none is a record, none has a public constructor,
 * and none has a public static method that takes a {@code String}, a number, a
 * {@code UUID} or a {@code Duration}, or a container of one, and returns the
 * type -- {@code valueOf} of an enum apart. So a value a caller chose cannot
 * be turned into a parameter outside the package that holds the value.
 *
 * <p>The list is this test's own, written from the decision, and not read
 * off the facades: a list derived from the thing it checks would agree with
 * it whatever it said.
 *
 * <p>By reflection over the compiled facades, so that what is checked is what
 * runs, not a reading of its source.
 */
class LogFacadeTypeTest {

    /** The facades. */
    static final List<Class<?>> FACADES = List.of(PlatformLog.class, TenancyLog.class);

    /**
     * The simple names of the types a facade's parameter may take: the verb,
     * the reason code (the domain's own, or a type carrying one), a status, a
     * technical address, a scope id, a tenant id, a count, a duration measured
     * between two points, the reference and the cause of an unforeseen
     * failure, and the enum of start and configuration problems.
     */
    static final Set<String> ALLOWED = Set.of("VerbName", "Reason", "RefusalCode", "Status",
        "TechnicalAddress", "ScopeId", "TenantId", "Count", "Elapsed", "FailureReference",
        "FailureCause", "StartProblem");

    /** Below this the facades are not the ones this check thinks it reads. */
    private static final int MINIMUM_METHODS = 12;

    @Test
    void every_parameter_is_one_of_the_platform_s_types() {
        assertThat(FACADES.stream().mapToLong(facade -> methodsOf(facade).size()).sum())
            .as("the facades' methods must have been read. A check over no methods passes "
                + "for every reason including the wrong ones")
            .isGreaterThanOrEqualTo(MINIMUM_METHODS);

        List<String> offences = new ArrayList<>();
        for (Class<?> facade : FACADES) {
            offences.addAll(offendingParameters(facade));
        }
        assertThat(offences)
            .as("a parameter of type String, a number or a uuid carries whatever its caller "
                + "put in it, which is how a value a caller chose reaches a log line "
                + "(DEC-0051). Take one of the platform's types, built from what the "
                + "service holds")
            .isEmpty();
    }

    @Test
    void no_parameter_type_can_be_built_from_a_text_a_number_or_a_uuid() {
        Set<Class<?>> types = parameterTypes(FACADES);
        assertThat(types)
            .as("the facades' parameter types must have been found")
            .hasSizeGreaterThanOrEqualTo(5);

        List<String> offences = new ArrayList<>();
        for (Class<?> type : types) {
            offences.addAll(openings(type));
        }
        assertThat(offences)
            .as("a type a public constructor or a public factory from a String, a number, a "
                + "uuid or a duration can make is that value by another name. Make the "
                + "constructor private and build the type from what the service holds")
            .isEmpty();
    }

    /**
     * The red state, observed on every build: a facade method with a
     * {@code String} parameter, and an allowed type with a public factory
     * from a {@code String}, one from a {@code UUID}, one from a
     * {@code List<String>} and one from an {@code Optional<UUID>}. Each is
     * reported, while the real facades above come back without an offence.
     */
    @Test
    void the_checks_report_each_offence_they_exist_for() {
        assertThat(offendingParameters(PlantedLog.class))
            .as("RED STATE, observed: a facade method taking a String is reported, the "
                + "methods taking an allowed type are not")
            .containsExactly("PlantedLog.said(java.lang.String)");
        assertThat(openings(TechnicalAddress.class))
            .as("RED STATE, observed: an allowed type with a public factory from a String "
                + "is reported")
            .containsExactly("TechnicalAddress.of(java.lang.String) builds one from a value "
                + "a caller could choose");
        assertThat(openings(ScopeId.class))
            .as("RED STATE, observed: an allowed type with a public factory from a UUID "
                + "is reported")
            .containsExactly("ScopeId.of(java.util.UUID) builds one from a value a caller "
                + "could choose");
        assertThat(openings(Status.class))
            .as("RED STATE, observed: an allowed type with a public factory from a list of "
                + "Strings is reported")
            .containsExactly("Status.of(java.util.List<java.lang.String>) builds one from a "
                + "value a caller could choose");
        assertThat(openings(TenantId.class))
            .as("RED STATE, observed: an allowed type with a public factory from an optional "
                + "UUID is reported")
            .containsExactly("TenantId.of(java.util.Optional<java.util.UUID>) builds one from "
                + "a value a caller could choose");
    }

    /** A facade as a careless change would write it; never implemented, never called. */
    interface PlantedLog {

        void said(String text);

        void at(TechnicalAddress address);

        void in(ScopeId scope);
    }

    /** An allowed name with a factory from a text. */
    static final class TechnicalAddress {

        private final String text;

        private TechnicalAddress(String text) {
            this.text = text;
        }

        public static TechnicalAddress of(String text) {
            return new TechnicalAddress(text);
        }

        @Override
        public String toString() {
            return text;
        }
    }

    /** An allowed name with a factory from a uuid. */
    static final class ScopeId {

        private final UUID id;

        private ScopeId(UUID id) {
            this.id = id;
        }

        public static ScopeId of(UUID id) {
            return new ScopeId(id);
        }

        @Override
        public String toString() {
            return id.toString();
        }
    }

    /** An allowed name with a factory from a list of texts. */
    static final class Status {

        private final List<String> texts;

        private Status(List<String> texts) {
            this.texts = texts;
        }

        public static Status of(List<String> texts) {
            return new Status(texts);
        }

        @Override
        public String toString() {
            return String.join(",", texts);
        }
    }

    /** An allowed name with a factory from an optional uuid. */
    static final class TenantId {

        private final UUID id;

        private TenantId(UUID id) {
            this.id = id;
        }

        public static TenantId of(Optional<UUID> id) {
            return new TenantId(id.orElseThrow());
        }

        @Override
        public String toString() {
            return id.toString();
        }
    }

    // ======================================================================

    private static List<Method> methodsOf(Class<?> facade) {
        return Arrays.stream(facade.getDeclaredMethods())
            .filter(method -> !method.isSynthetic())
            .toList();
    }

    private static List<String> offendingParameters(Class<?> facade) {
        List<String> offences = new ArrayList<>();
        for (Method method : methodsOf(facade)) {
            for (Class<?> parameter : method.getParameterTypes()) {
                if (!ALLOWED.contains(parameter.getSimpleName())) {
                    offences.add(facade.getSimpleName() + "." + method.getName() + "("
                        + parameter.getName() + ")");
                }
            }
        }
        return offences;
    }

    private static Set<Class<?>> parameterTypes(List<Class<?>> facades) {
        Set<Class<?>> types = new LinkedHashSet<>();
        for (Class<?> facade : facades) {
            for (Method method : methodsOf(facade)) {
                types.addAll(Arrays.asList(method.getParameterTypes()));
            }
        }
        return types;
    }

    /** The ways to build a parameter type from a value a caller could have chosen. */
    private static List<String> openings(Class<?> type) {
        List<String> openings = new ArrayList<>();
        String name = type.getSimpleName();
        if (type.isRecord()) {
            openings.add(name + " is a record, and a record's canonical constructor is public");
        }
        if (!type.isEnum() && !Modifier.isFinal(type.getModifiers())) {
            openings.add(name + " is neither final nor an enum");
        }
        if (type.isEnum()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    openings.add(name + " carries a value in " + field.getName());
                }
            }
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (Modifier.isPublic(constructor.getModifiers())) {
                openings.add(name + " has a public constructor");
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())
                    && Modifier.isStatic(method.getModifiers())
                    && type.isAssignableFrom(method.getReturnType())
                    && !(type.isEnum() && "valueOf".equals(method.getName()))) {
                Class<?>[] parameters = method.getParameterTypes();
                Type[] declared = method.getGenericParameterTypes();
                for (int i = 0; i < parameters.length; i++) {
                    if (callerChosen(parameters[i]) || holdsCallerChosen(declared[i])) {
                        openings.add(name + "." + method.getName() + "("
                            + declared[i].getTypeName()
                            + ") builds one from a value a caller could choose");
                    }
                }
            }
        }
        return openings;
    }

    /**
     * A text, a number, a uuid, a duration -- or anything that would take
     * one: an {@code Object}, a character sequence, an array of either.
     */
    private static boolean callerChosen(Class<?> parameter) {
        if (parameter.isArray()) {
            return callerChosen(parameter.getComponentType());
        }
        return parameter == Object.class
            || CharSequence.class.isAssignableFrom(parameter)
            || (parameter.isPrimitive() && parameter != boolean.class)
            || Number.class.isAssignableFrom(parameter)
            || parameter == Character.class
            || parameter == UUID.class
            || parameter == Duration.class;
    }

    /**
     * A container of such a value: an {@code Iterable}, a {@code Map}, an
     * {@code Optional}, a {@code Stream} or any other generic type one of whose
     * type arguments a caller could choose, at any depth.
     *
     * <p>A wildcard without a bound and a type variable are not caller-chosen.
     * {@code PlatformLog.Count.of(Collection<?>)} must stay allowed: it reads
     * the size of the collection and none of its members, so what the members
     * are cannot reach a line. A bounded wildcard is judged by its bounds.
     */
    private static boolean holdsCallerChosen(Type declared) {
        if (declared instanceof ParameterizedType generic) {
            return Arrays.stream(generic.getActualTypeArguments())
                .anyMatch(LogFacadeTypeTest::typeArgumentCallerChosen);
        }
        if (declared instanceof GenericArrayType array) {
            return holdsCallerChosen(array.getGenericComponentType());
        }
        return false;
    }

    private static boolean typeArgumentCallerChosen(Type argument) {
        if (argument instanceof Class<?> type) {
            return callerChosen(type);
        }
        if (argument instanceof ParameterizedType generic) {
            return callerChosen((Class<?>) generic.getRawType()) || holdsCallerChosen(generic);
        }
        if (argument instanceof GenericArrayType array) {
            return typeArgumentCallerChosen(array.getGenericComponentType());
        }
        if (argument instanceof WildcardType wildcard) {
            return Arrays.stream(wildcard.getUpperBounds())
                    .filter(bound -> bound != Object.class)
                    .anyMatch(LogFacadeTypeTest::typeArgumentCallerChosen)
                || Arrays.stream(wildcard.getLowerBounds())
                    .anyMatch(LogFacadeTypeTest::typeArgumentCallerChosen);
        }
        return false;
    }
}
