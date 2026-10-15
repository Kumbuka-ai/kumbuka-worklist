/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import io.quarkus.qute.i18n.MessageBundles;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The catalogue of refusals: every {@link RefusalCode} with its message and its
 * remedy (DEC-0043 R12).
 *
 * <p><strong>Binding by name.</strong> The message of a code is the method of
 * {@link RefusalMessages} named after the code in lower camel case, its remedy
 * the method of the same name in {@link RefusalRemedies}. Methods whose name
 * starts with {@link #PROTOCOL} word the errors of the protocol and belong to
 * no code. {@link #requireDeclared} holds the two sets against each other in
 * both directions, and the service does not start while they differ.
 *
 * <p><strong>Placeholders by type.</strong> A bundle method's parameters take
 * only the five placeholder types; the catalogue hands each parameter the value
 * of its type from the {@link Context} of the refusal. A context carries
 * nothing a call sent: the verb is a declared {@link VerbName}, the bound a
 * declared {@link Bound}.
 *
 * <p>On the wire a refusal's message is its message followed by its remedy,
 * so that the caller reads what was found and then the act to perform
 * (DEC-0042).
 */
public final class RefusalCatalogue {

    /** The prefix of the bundle methods that word the protocol's own errors. */
    public static final String PROTOCOL = "protocol";

    private RefusalCatalogue() {
    }

    /**
     * What a refusal's texts may draw on.
     *
     * @param attempted the verb the refusal concerns, where the text names it
     * @param bound     the bound the text names, where it names one
     */
    public record Context(VerbName attempted, Bound bound) {

        public static final Context NONE = new Context(null, null);
    }

    /** The message and the remedy of one code, joined as the wire carries them. */
    public static String message(RefusalCode code, Context context) {
        String name = methodName(code);
        return render(RefusalMessages.class, name, context) + " "
            + render(RefusalRemedies.class, name, context);
    }

    /** The bound a code's text names, read from the declaration. */
    public static Bound boundOf(RefusalCode code) {
        return code == RefusalCode.PAGE_SIZE_REJECTED ? SurfaceDeclaration.PAGE_SIZE_MAX : null;
    }

    /** A protocol error, message and remedy joined. */
    public static String protocol(String name) {
        return render(RefusalMessages.class, name, Context.NONE) + " "
            + render(RefusalRemedies.class, name, Context.NONE);
    }

    /** The bundle method name of a code: {@code NOT_FOUND} is {@code notFound}. */
    public static String methodName(RefusalCode code) {
        return camel(code.name());
    }

    /**
     * Refuses a catalogue in which a code lacks a message or a remedy, or a
     * bundle words a code the catalogue lacks.
     *
     * <p>The codes are a parameter so that the probe can hand in a set with a
     * planted code; the service hands in {@link RefusalCode}.
     *
     * @throws IllegalStateException naming the codes and methods that differ
     */
    public static void requireDeclared(Class<? extends Enum<?>> codes) {
        Set<String> declared = new LinkedHashSet<>();
        for (Enum<?> code : codes.getEnumConstants()) {
            declared.add(camel(code.name()));
        }
        List<String> faults = new ArrayList<>();
        for (Class<?> bundle : List.of(RefusalMessages.class, RefusalRemedies.class)) {
            Set<String> worded = new LinkedHashSet<>();
            for (Method method : bundle.getMethods()) {
                if (!method.getName().startsWith(PROTOCOL)) {
                    worded.add(method.getName());
                }
            }
            for (String code : declared) {
                if (!worded.contains(code)) {
                    faults.add("missing:" + bundle.getSimpleName() + "." + code);
                }
            }
            for (String method : worded) {
                if (!declared.contains(method)) {
                    faults.add("undeclared:" + bundle.getSimpleName() + "." + method);
                }
            }
        }
        if (!faults.isEmpty()) {
            throw new IllegalStateException("the refusal catalogue is incomplete: " + faults);
        }
    }


    /** A constant's name in lower camel case: {@code NOT_FOUND} is {@code notFound}. */
    private static String camel(String constant) {
        String[] words = constant.toLowerCase(Locale.ROOT).split("_");
        StringBuilder name = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) {
            name.append(Character.toUpperCase(words[i].charAt(0))).append(words[i].substring(1));
        }
        return name.toString();
    }

    private static String render(Class<?> bundle, String name, Context context) {
        Method method = Arrays.stream(bundle.getMethods())
            .filter(m -> m.getName().equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                bundle.getSimpleName() + " lacks " + name));
        Object[] arguments = new Object[method.getParameterCount()];
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            arguments[i] = valueOf(types[i], context, name);
        }
        try {
            return (String) method.invoke(MessageBundles.get(bundle), arguments);
        } catch (IllegalAccessException | InvocationTargetException failed) {
            throw new IllegalStateException(bundle.getSimpleName() + "." + name
                + " could not be rendered", failed);
        }
    }

    private static Object valueOf(Class<?> type, Context context, String name) {
        Object value = null;
        if (type == VerbName.class) {
            value = context.attempted();
        } else if (type == Bound.class) {
            value = context.bound();
        }
        if (value == null) {
            throw new IllegalStateException(name + " names a " + type.getSimpleName()
                + " and the refusal carries none");
        }
        return value;
    }
}
