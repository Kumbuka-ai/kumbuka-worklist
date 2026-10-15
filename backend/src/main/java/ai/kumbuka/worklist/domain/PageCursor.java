/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The way from one page of a {@code query} to the next.
 *
 * <p>A keyset cursor: it carries the sort values of the last object the page
 * held, and the next page starts strictly after that position in the sort. No
 * offset, so an object inserted between two pages shifts nothing that was
 * already answered, and no lookup of the object, so an object whose filtered
 * field changed in between still marks the place.
 *
 * <h2>What it is bound to</h2>
 *
 * Version {@value #VERSION} encodes the scope, the view, a fingerprint of the
 * filter, the sort and its direction beside the position. A cursor is only
 * true of the query that handed it out: read against another scope it would
 * place a walk at a position of someone else's order; against another filter
 * or sort it would skip or repeat objects without saying so. Each of these is
 * refused as {@code CURSOR_FOREIGN} rather than applied. What is not bound is
 * the page size, which a walk may change at any page.
 *
 * <p>The scope and the filter travel as fingerprints, the first sixteen hex
 * digits of a SHA-256, so the cursor names no identity of the platform. There
 * is no signature and no secret: a caller who forges a cursor for their own
 * query reaches nothing the query would not answer anyway.
 *
 * <p>Opaque to a caller on purpose: what it encodes is this service's to
 * change, and a caller who builds one by hand breaks when it does.
 */
public final class PageCursor {

    private static final String VERSION = "v1";
    private static final String SEPARATOR = "|";
    private static final int FIELDS = 8;
    private static final int FINGERPRINT_HEX = 16;

    private PageCursor() {
    }

    /**
     * Where an object stands in a sort: the value of the sort's own field, and
     * the number every sort ends on.
     *
     * @param lead the number itself for {@link SortKey#ID}, the creation
     *             instant for {@link SortKey#CREATED_AT}, the rank for
     *             {@link SortKey#RANK}
     */
    public record Position(Object lead, long number) {

        /** The position of an object with these values, in this sort. */
        public static Position of(SortKey sort, long number, Instant createdAt, int rank) {
            return new Position(switch (sort) {
                case ID -> number;
                case CREATED_AT -> createdAt;
                case RANK -> rank;
            }, number);
        }
    }

    /** The cursor that continues after this position, bound to the request. */
    static String encode(PageRequest request, Position at) {
        String plain = String.join(SEPARATOR, VERSION, fingerprint(request.scopeId().toString()),
            request.view(), fingerprint(request.filters()), request.sort().wireName(),
            request.direction().wireName(), String.valueOf(at.lead()), String.valueOf(at.number()));
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The position a cursor continues after, checked against the query it is
     * passed back to; null for no cursor.
     *
     * @throws WorklistException {@code CURSOR_MALFORMED} for a cursor this
     *         service did not hand out, {@code CURSOR_FOREIGN} for one handed
     *         out by a query other than this one
     */
    static Position decode(String cursor, UUID scopeId, String view,
                           Map<QueryFilter, Object> filters, SortKey sort,
                           SortKey.Direction direction) {
        if (cursor == null) {
            return null;
        }
        String[] parts = plain(cursor).split("\\|", -1);
        if (parts.length != FIELDS || !VERSION.equals(parts[0])) {
            throw malformed();
        }
        refuseForeign(parts[1], fingerprint(scopeId.toString()));
        refuseForeign(parts[2], view);
        refuseForeign(parts[3], fingerprint(filters));
        refuseForeign(parts[4], sort.wireName());
        refuseForeign(parts[5], direction.wireName());
        return position(sort, parts[6], parts[7]).orElseThrow(PageCursor::malformed);
    }

    private static Optional<Position> position(SortKey sort, String lead, String number) {
        try {
            long n = Long.parseLong(number);
            Object value = switch (sort) {
                case ID -> Long.parseLong(lead);
                case CREATED_AT -> Instant.parse(lead);
                case RANK -> Integer.parseInt(lead);
            };
            if (n < 1 || sort == SortKey.ID && !value.equals(n)) {
                return Optional.empty();
            }
            return Optional.of(new Position(value, n));
        } catch (NumberFormatException | DateTimeParseException unreadable) {
            return Optional.empty();
        }
    }

    private static void refuseForeign(String carried, String asked) {
        if (!carried.equals(asked)) {
            throw new WorklistException(
                WorklistException.Reason.CURSOR_FOREIGN,
                List.of(QuerySpec.AFTER_ARGUMENT));
        }
    }

    /** The refusal of a cursor this service did not hand out, or of no cursor at all. */
    static WorklistException malformed() {
        return new WorklistException(
            WorklistException.Reason.CURSOR_MALFORMED,
            List.of(QuerySpec.AFTER_ARGUMENT));
    }

    /**
     * The filter in one canonical text, in declaration order, so that the same
     * filter written in another order or another spelling of the same value
     * has the same fingerprint.
     */
    private static String fingerprint(Map<QueryFilter, Object> filters) {
        StringBuilder canonical = new StringBuilder();
        for (QueryFilter filter : QueryFilter.values()) {
            if (filters.containsKey(filter)) {
                canonical.append(filter.name()).append('=').append(filters.get(filter)).append('\n');
            }
        }
        return fingerprint(canonical.toString());
    }

    private static String fingerprint(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, FINGERPRINT_HEX);
        } catch (NoSuchAlgorithmException absent) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException(absent);
        }
    }

    private static String plain(String cursor) {
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            return "";
        }
    }
}
