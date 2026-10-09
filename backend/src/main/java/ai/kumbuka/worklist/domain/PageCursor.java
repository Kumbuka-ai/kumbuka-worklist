/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * The way from one page of a {@code query} to the next.
 *
 * <p>It names the view and the number of the last object the page carried, and
 * the next page starts after that object in the view's order. A number rather
 * than the platform's identity, because the identity is not something a caller
 * reads back on this surface; and a number is enough, because no object of any
 * view is ever deleted, so the object a cursor names always still exists.
 *
 * <p>Opaque to a caller on purpose: what it encodes is this service's to change,
 * and a caller who builds one by hand is a caller who breaks when it does. It is
 * read back strictly — a cursor this read did not hand out, or one handed out by
 * another view, is refused by name rather than read as "from the beginning",
 * which would answer the first page again and look like the next one.
 */
public final class PageCursor {

    private static final String VERSION = "v1";

    private PageCursor() {
    }

    /** The cursor that continues after the object of this number. */
    public static String encode(String view, long number) {
        String plain = VERSION + ":" + view + ":" + number;
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(plain.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The number a cursor continues after, or null for no cursor.
     *
     * @throws WorklistException {@code INVALID_VALUE} naming {@code cursor}
     */
    static Long decode(String view, String cursor) {
        if (cursor == null) {
            return null;
        }
        String[] parts = plain(cursor).split(":", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0]) || !view.equals(parts[1])
                || !parts[2].matches("[1-9]\\d{0,17}")) {
            throw refused(cursor, "it is not a cursor the " + view + " view handed out");
        }
        return Long.valueOf(parts[2]);
    }

    /** Refused because the view holds no object of the number a cursor names. */
    static WorklistException unplaced(String view, long after) {
        return refused(encode(view, after),
            "it continues after " + view + " " + after + ", which this scope does not hold");
    }

    private static String plain(String cursor) {
        try {
            return new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            return "";
        }
    }

    private static WorklistException refused(String cursor, String why) {
        return new WorklistException(
            WorklistException.Reason.INVALID_VALUE,
            "the cursor '" + cursor + "' cannot be read: " + why + ". Pass back the "
                + "'next_cursor' of the previous answer as it came, on the same view",
            List.of(QuerySpec.CURSOR));
    }
}
