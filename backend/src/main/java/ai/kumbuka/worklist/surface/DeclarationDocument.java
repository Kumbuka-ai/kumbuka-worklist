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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * The declaration as the REST surface offers it (ADR-0039): one document, read
 * from {@link SurfaceDeclaration} and worded by the message bundles, with a
 * version that is a hash over its own content.
 *
 * <p>Nothing here is a second copy of the declaration. Every member is read
 * from the declaration or the catalogue at the moment the document is built,
 * so a verb, an argument or a text changed there changes the document and its
 * version with it.
 */
public final class DeclarationDocument {

    /** The member the version travels under. */
    public static final String VERSION = "version";

    /** The member every text of the declaration travels under. */
    private static final String DESCRIPTION = "description";

    /** The scheme this service declares the verbs of. */
    public static final String SCHEME = "worklist";

    private DeclarationDocument() {
    }

    /** The document, its version included. */
    public static Map<String, Object> document() {
        Map<String, Object> content = content(
            MessageBundles.get(ToolDescriptions.class),
            MessageBundles.get(ArgumentDescriptions.class),
            MessageBundles.get(ValueDescriptions.class));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("scheme", SCHEME);
        document.put(VERSION, versionOf(content));
        document.putAll(content);
        return document;
    }

    /**
     * The content the version is taken over: the verbs and the catalogue of
     * reasons, worded by the bundles given.
     */
    static Map<String, Object> content(ToolDescriptions tools, ArgumentDescriptions arguments,
                                       ValueDescriptions values) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("verbs", SurfaceDeclaration.verbs().stream()
            .map(verb -> verb(verb, tools, arguments, values))
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

    private static Map<String, Object> reason(RefusalCode code, Properties messages,
                                              Properties remedies) {
        String name = RefusalCatalogue.methodName(code);
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("code", code.name());
        reason.put("status", code.status());
        reason.put("message", messages.getProperty(name));
        reason.put("remedy", remedies.getProperty(name));
        return reason;
    }

    private static Map<String, Object> verb(SurfaceDeclaration.Verb verb, ToolDescriptions tools,
                                            ArgumentDescriptions arguments,
                                            ValueDescriptions values) {
        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put("identity", SCHEME + "_" + verb.name().name());
        declared.put("verb", verb.name().name());
        declared.put("effect_class", verb.effectClass().name());
        declared.put(DESCRIPTION, verb.description().apply(tools));
        declared.put("arguments", verb.arguments().stream()
            .map(argument -> argument(argument, arguments, values))
            .toList());
        declared.put("answer", verb.answer().name());
        declared.put("reasons", verb.reasons().stream().map(RefusalCode::name).toList());
        return declared;
    }

    private static Map<String, Object> argument(SurfaceDeclaration.Argument argument,
                                                ArgumentDescriptions arguments,
                                                ValueDescriptions values) {
        Map<String, Object> arg = new LinkedHashMap<>();
        arg.put("name", argument.name().name());
        arg.put("type", argument.type().json());
        arg.put("required", argument.required());
        arg.put("default", argument.defaultValue());
        arg.put("values", argument.values().stream()
            .map(value -> Map.<String, Object>of("value", value.name(),
                DESCRIPTION, value.description().apply(values)))
            .toList());
        arg.put(DESCRIPTION, argument.description().apply(arguments));
        return arg;
    }

    /**
     * The version: the SHA-256 of the content written canonically -- object
     * members sorted by name, no whitespace -- as lower-case hex.
     */
    static String versionOf(Map<String, Object> content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical(content).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("SHA-256 is part of every JVM", absent);
        }
    }

    /** JSON with object members sorted by name and no insignificant whitespace. */
    static String canonical(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Map<?, ?> map) {
            writeObject(out, map);
        } else if (value instanceof List<?> list) {
            writeArray(out, list);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else {
            string(out, value.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeObject(StringBuilder out, Map<?, ?> map) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : new TreeMap<>((Map<String, Object>) map).entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            string(out, e.getKey());
            out.append(':');
            write(out, e.getValue());
        }
        out.append('}');
    }

    private static void writeArray(StringBuilder out, List<?> list) {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            write(out, list.get(i));
        }
        out.append(']');
    }

    private static void string(StringBuilder out, String text) {
        out.append('"');
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
