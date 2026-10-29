/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import io.quarkus.qute.i18n.MessageBundles;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * The declaration as the REST surface offers it (ADR-0039), in shape 3 of the
 * service contract (C5.3 to C5.5): one document, read from
 * {@link SurfaceDeclaration} and worded by the message bundles, with a version
 * that is a hash over its own content.
 *
 * <p>Nothing here is a second copy of the declaration. Every member is read
 * from the declaration or the catalogue at the moment the document is built,
 * so a verb, an argument or a text changed there changes the document and its
 * version with it.
 *
 * <p>No member is written without a value. An argument with no fallback
 * declares no {@code default}, a set of values stands only where there is
 * one, and nothing travels as {@code null}: the document is written by the
 * same mapper as every other answer, which leaves such a member out (C7.2),
 * and a member that was there for the version and gone on the wire would make
 * the version the hash of something nobody was served.
 */
public final class DeclarationDocument {

    /** The member the version travels under. */
    public static final String VERSION = "version";

    /**
     * The revision of the declaration's own shape (C5.5).
     *
     * <p>Not the service version. A consumer that takes this declaration needs
     * to know when the SHAPE changed -- a member added, a member's meaning
     * changed -- and the service's version moves for reasons that have nothing
     * to do with it.
     */
    public static final String SHAPE_VERSION = "3";

    /** The scheme this service declares the verbs of, and the service's own name. */
    public static final String SCHEME = "worklist";

    /** The form of an address of this scheme. The service has no technical address. */
    public static final String ADDRESS_FORM = "worklist://<scope>/<selector>/<number>";

    /** The member every text of the declaration travels under. */
    private static final String DESCRIPTION = "description";

    private DeclarationDocument() {
    }

    /** The document, its version first. */
    public static Map<String, Object> document() {
        Map<String, Object> content = content(
            MessageBundles.get(ToolDescriptions.class),
            MessageBundles.get(ArgumentDescriptions.class),
            MessageBundles.get(ValueDescriptions.class));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put(VERSION, CanonicalJson.version(content));
        document.putAll(content);
        return Collections.unmodifiableMap(document);
    }

    /**
     * The content the version is taken over: everything but the version
     * itself, the shape version and the scheme included, worded by the bundles
     * given.
     */
    static Map<String, Object> content(ToolDescriptions tools, ArgumentDescriptions arguments,
                                       ValueDescriptions values) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("shape_version", SHAPE_VERSION);
        content.put("service", SCHEME);
        content.put("scheme", SCHEME);
        content.put("address_form", ADDRESS_FORM);
        content.put("effect_classes", Arrays.stream(SurfaceDeclaration.EffectClass.values())
            .map(Enum::name).toList());
        content.put("calls", SurfaceDeclaration.verbs().stream()
            .map(verb -> call(verb, tools, arguments, values))
            .toList());
        Properties messages = patterns("refusals");
        Properties remedies = patterns("remedies");
        content.put("reasons", Arrays.stream(RefusalCode.values())
            .map(code -> reason(code, messages, remedies))
            .toList());
        return content;
    }

    /**
     * The patterns of one bundle as its file holds them, placeholders
     * unrendered: the declaration states the shape of a text, and the values
     * are filled in where a refusal is raised.
     */
    private static Properties patterns(String bundle) {
        Properties patterns = new Properties();
        try (InputStream in = DeclarationDocument.class.getClassLoader()
                .getResourceAsStream("messages/" + bundle + ".properties")) {
            if (in == null) {
                throw new IllegalStateException("a bundle file is absent");
            }
            patterns.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
        return patterns;
    }

    /**
     * One reason in the reference's form: the code, its message as a pattern
     * and as the one keyed template the bundle holds for it, and its remedy.
     */
    private static Map<String, Object> reason(RefusalCode code, Properties messages,
                                              Properties remedies) {
        String key = RefusalCatalogue.methodName(code);
        String template = messages.getProperty(key);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("key", key);
        message.put("template", template);
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("reason", code.name());
        reason.put("message_pattern", template);
        reason.put("messages", List.of(message));
        reason.put("remedy", remedies.getProperty(key));
        return reason;
    }

    private static Map<String, Object> call(SurfaceDeclaration.Verb verb, ToolDescriptions tools,
                                            ArgumentDescriptions arguments,
                                            ValueDescriptions values) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("identity", SurfaceDeclaration.call(verb));
        call.put("call", SurfaceDeclaration.call(verb));
        call.put("rest_name", verb.name().name());
        call.put("effect_class", verb.effectClass().name());
        call.put(DESCRIPTION, verb.description().apply(tools));
        call.put("arguments", verb.arguments().stream()
            .map(argument -> argument(argument, arguments, values))
            .toList());
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("shape", verb.answer().name());
        answer.put("members", verb.answer().members());
        call.put("answer", answer);
        call.put("refusals", verb.reasons().stream().map(RefusalCode::name).toList());
        return call;
    }

    private static Map<String, Object> argument(SurfaceDeclaration.Argument argument,
                                                ArgumentDescriptions arguments,
                                                ValueDescriptions values) {
        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put("name", argument.name().name());
        declared.put("type", argument.type().json());
        declared.put("required", argument.required());
        declared.put("placement", argument.placement().name());
        String fallback = argument.defaultValue();
        if (fallback != null) {
            // In its own JSON type (C5.4); an argument whose absence means
            // "unset" declares none.
            declared.put("default", typed(argument.type(), fallback));
        }
        declared.put(DESCRIPTION, argument.description().apply(arguments));
        if (!argument.values().isEmpty()) {
            declared.put("values", argument.values().stream()
                .map(SurfaceDeclaration.Value::name).toList());
            declared.put("value_descriptions", argument.values().stream()
                .map(value -> {
                    Map<String, Object> described = new LinkedHashMap<>();
                    described.put("value", value.name());
                    described.put(DESCRIPTION, value.description().apply(values));
                    return described;
                })
                .toList());
        }
        return declared;
    }

    /** A fallback as a value of the argument's own type: a number or text. */
    private static Object typed(SurfaceDeclaration.ArgumentType type, String fallback) {
        return switch (type) {
            case INTEGER -> Long.parseLong(fallback);
            case STRING -> fallback;
            case OBJECT -> throw new IllegalStateException(
                "an object argument declares no fallback: " + fallback);
        };
    }
}
