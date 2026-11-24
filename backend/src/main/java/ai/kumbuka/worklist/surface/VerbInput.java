/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the verb surface accepts, in a form that names no protocol.
 *
 * <p>These shapes exist because the surface must not import the adapter's wire
 * types. They are deliberately NOT the wire shapes renamed: a wire shape answers
 * to a published contract and changes when that contract does; these answer to
 * the verbs. Today most fields coincide, and that is fine — what matters is that
 * a change to one is not automatically a change to the other.
 *
 * <h2>Why a write arrives as a map and not as a record per object</h2>
 *
 * The field catalogue is one enum over five addressed things, and it is the
 * domain that resolves a name against what is being addressed and refuses what
 * it does not recognise, by name. A record per object here would be a second
 * catalogue: it would decide the field set at the surface, and a field added to
 * the domain would then be silently unreachable rather than newly available.
 *
 * <h2>Why the null check is not here</h2>
 *
 * A missing body is refused by the surface, after the scope has been resolved,
 * because the ratified check order answers "not found" for a scope the caller
 * may not see before it says anything about the body. So an adapter hands over
 * {@code null} rather than refusing early, and every record here is nullable at
 * the call site by design.
 */
public final class VerbInput {

    private VerbInput() {
    }

    /**
     * The canonical field map of a write, exactly as the caller wrote it.
     *
     * <p>Carried through untouched but for one reading: a member written as
     * JSON {@code null} is a member not sent (C7.4, DEC-0043 R17), at every
     * depth -- a field, a key of {@code attributes}, a member of an entry of
     * {@code references} -- so it is left out here, once, for both adapters.
     * An update then leaves the field as it is, and a create gives it the
     * value a create without it gives. An element of a list is a value, not a
     * member, and stays as it came. The one
     * thing the surface adds is the conflict token, and it adds it because the
     * token arrives in the transport ({@code If-Match}) while the domain reads
     * it as a field — see {@link #withToken}.
     */
    public record Fields(Map<String, Object> values) {

        public Fields {
            values = values == null ? Map.of() : present(values);
        }

        /** A map with the members whose value is {@code null} left out, at every depth. */
        private static Map<String, Object> present(Map<?, ?> given) {
            Map<String, Object> present = new LinkedHashMap<>();
            given.forEach((name, value) -> {
                if (value != null) {
                    present.put(String.valueOf(name), presentIn(value));
                }
            });
            return Collections.unmodifiableMap(present);
        }

        /** A value with the null members of every map it holds left out. */
        private static Object presentIn(Object value) {
            if (value instanceof Map<?, ?> map) {
                return present(map);
            }
            if (value instanceof List<?> list) {
                List<Object> elements = new ArrayList<>(list.size());
                list.forEach(element -> elements.add(element == null ? null : presentIn(element)));
                return Collections.unmodifiableList(elements);
            }
            return value;
        }

        /**
         * The same fields with the conflict token written in under its canonical
         * name.
         *
         * <p>The token travels in the transport and the domain reads it as one
         * of the fields, so somebody has to move it across. Here rather than in
         * each adapter: two adapters moving it are two chances to move it
         * differently, and the one that forgot would produce a write refused for
         * a missing token that the caller demonstrably sent.
         *
         * <p><strong>The transport wins over a token in the body.</strong> A
         * caller doing the obvious thing — read an object, change one value,
         * send the whole answer back — sends the token twice, and the two agree.
         * Where they disagree, the one in the request line is the one the caller
         * meant this time; the one in the body is a leftover of what they read.
         * Refusing the disagreement instead was considered and dropped: it would
         * fail the round trip that the canonical naming exists to make safe.
         */
        public Fields withToken(String canonicalName, String token) {
            Map<String, Object> merged = new LinkedHashMap<>(values);
            merged.put(canonicalName, token);
            return new Fields(merged);
        }
    }

    /**
     * An edge {@code relate} asserts, or {@code unrelate} withdraws.
     *
     * <p>The source is the address the verb runs against. The target and the
     * type both arrive as ids — a declared value travels as its identity
     * rather than as its display name, which is the same rule
     * {@link Fields}'s status carries.
     *
     * @param toItem the target item; absent where the body named none, which
     *               the surface refuses as ARGUMENT_MISSING
     * @param type   the relation type; absent where the body named none, which
     *               the surface refuses as ARGUMENT_MISSING
     */
    public record Edge(@Nullable String toItem, @Nullable String type) {
    }

    /**
     * A booking {@code book} records in an item's fulfilment ledger.
     *
     * <p>The entries are handed on as the list that arrived, and read by the
     * domain, which checks each against the criteria the item issued: an
     * entry is what an instance reported, and the check of what it names
     * needs the store.
     *
     * @param instance the address of the reporting instance; absent where the
     *                 call named none, which the surface refuses as
     *                 ARGUMENT_MISSING
     * @param entries  the entries as they arrived; absent where the call named
     *                 none, which the surface refuses as ARGUMENT_MISSING
     */
    public record Booking(@Nullable String instance, @Nullable List<?> entries) {
    }

    /**
     * The optional arguments of {@code close}: what an iteration produced,
     * and why an item closes and the status it closes into. Each is taken on
     * the path that has a place for it and refused on another.
     *
     * @param produced what an iteration produced; absent where the call named none
     * @param reason   why an item closes; absent where the call named none
     * @param status   the status an item closes into; absent where the call
     *                 named none
     */
    public record Closing(@Nullable String produced, @Nullable String reason,
                          @Nullable String status) {

        /** A close that names none of the three. */
        public static Closing none() {
            return new Closing(null, null, null);
        }
    }
}
