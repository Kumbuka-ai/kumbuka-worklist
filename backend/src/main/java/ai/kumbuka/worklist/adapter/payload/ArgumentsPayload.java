/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.payload;

import ai.kumbuka.worklist.surface.RefusalCode;
import ai.kumbuka.worklist.surface.SurfaceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The body of a REST request, read and checked for form (C1.2).
 *
 * <p>Every check here is decidable without knowing a scope -- the body is a
 * JSON object, a name is one the verb declares, a value has the JSON type its
 * argument takes -- so each is decided before any scope is resolved, and its
 * refusal is the same whether the scope is there or not (C1.3). What the
 * values mean is the verb surface's and the domain's.
 *
 * @param values the members of the body as they arrived, in their order
 */
public record ArgumentsPayload(Map<String, Object> values) {

    /** The shape a JSON object body is read into. */
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() { };

    /**
     * A JSON object body, every member as it arrived; absent where the body
     * is absent, because some verbs take one and some do not, and which is
     * which is the verb's business rather than the transport's.
     *
     * @throws SurfaceException {@code PAYLOAD_MALFORMED} for a body that is no
     *         JSON object
     */
    public static Optional<ArgumentsPayload> object(ObjectMapper json, @Nullable String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> read = json.readValue(body, OBJECT);
            return read == null ? Optional.empty()
                : Optional.of(new ArgumentsPayload(Collections.unmodifiableMap(read)));
        } catch (JsonProcessingException e) {
            // The parser's own message quotes the body back, so it stays in
            // here (DEC-0043 R18).
            throw new SurfaceException(RefusalCode.PAYLOAD_MALFORMED);
        }
    }

    /**
     * The arguments of a body, under the names the verb declares.
     *
     * <p>A name the verb does not declare is refused as {@code
     * ARGUMENT_UNKNOWN} with the declared names under {@code accepts}
     * (DEC-0043 R4, R19), so nothing is accepted and dropped. A name that
     * arrived as JSON {@code null} is left out, which makes it the same call
     * as one that never sent it (R17). An absent body is an empty one.
     */
    public static ArgumentsPayload declared(ObjectMapper json, @Nullable String body,
                                            String... declared) {
        Map<String, Object> present = new LinkedHashMap<>();
        Optional<ArgumentsPayload> read = object(json, body);
        if (read.isEmpty()) {
            return new ArgumentsPayload(present);
        }
        List<String> names = List.of(declared);
        Map<String, Object> in = read.get().values();
        if (!names.containsAll(in.keySet())) {
            throw SurfaceException.unknown(names);
        }
        in.forEach((name, value) -> {
            if (value != null) {
                present.put(name, value);
            }
        });
        return new ArgumentsPayload(Collections.unmodifiableMap(present));
    }

    /** Whether no argument arrived with a value. */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * An argument that takes text, where it arrived.
     *
     * @throws SurfaceException {@code ARGUMENT_INVALID} for a value of another type
     */
    public Optional<String> text(String name) {
        Object value = values.get(name);
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String text)) {
            throw SurfaceException.invalid(name);
        }
        return Optional.of(text);
    }

    /**
     * An argument that takes a whole number, where it arrived.
     *
     * @throws SurfaceException {@code ARGUMENT_INVALID} for a value of another type
     */
    public Optional<Long> integer(String name) {
        Object value = values.get(name);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Integer || value instanceof Long) {
            return Optional.of(((Number) value).longValue());
        }
        throw SurfaceException.invalid(name);
    }
}
