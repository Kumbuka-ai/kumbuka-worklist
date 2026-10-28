/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.payload;

import ai.kumbuka.worklist.surface.AddressParser;
import ai.kumbuka.worklist.surface.NoticeTexts;
import ai.kumbuka.worklist.surface.VerbInput;
import ai.kumbuka.worklist.surface.VerbSurface;

import java.util.Map;

/**
 * The wire shapes of the verb surface.
 *
 * <p>The shapes themselves are the records of this package, one per answer
 * (C7.1); this class holds the two translations between them and the verb
 * surface.
 *
 * <p><strong>No shape here carries a subject.</strong> Authorship is derived from
 * the token and never accepted from a caller, so a field for it would be a field
 * the server has to ignore — and a field the server ignores is one a client will
 * eventually rely on.
 *
 * <h2>Why an answer is an address beside a field map, and not one flat object</h2>
 *
 * The field map is the domain's projection under its own canonical names, and the
 * address is the surface's. Merging them would put an adapter's key into the
 * namespace a caller writes back on the next update — where the domain would
 * either refuse it as unknown or, worse, silently accept a key that means
 * something to nobody. Keeping them apart is what makes read-modify-write safe:
 * everything under {@code fields} may be sent back untouched.
 */
public final class Payloads {

    private Payloads() {
    }

    // ----------------------------------------------------------------------
    // Wire shape to verb input
    // ----------------------------------------------------------------------
    //
    // The translation runs in this direction and only in this direction. The
    // adapter knows the surface; the surface does not know the adapter, which is
    // what keeps the two out of an import cycle. A missing body is not refused
    // here: the caller hands the surface its absence, and the surface refuses it
    // after the scope has been resolved -- refusing it here would move that
    // answer in front of a check the check order puts first.

    /** The fields behind a create or an update. */
    public static VerbInput.Fields fields(Map<String, Object> body) {
        return new VerbInput.Fields(body);
    }

    // ----------------------------------------------------------------------
    // Verb answer to wire shape
    // ----------------------------------------------------------------------

    /** One object, at its address. */
    public static ObjectPayload of(String scope, VerbSurface.Result result) {
        return new ObjectPayload(
            AddressParser.render(scope, result.address()), worded(result.fields()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> worded(Map<String, Object> fields) {
        return (Map<String, Object>) NoticeTexts.worded(fields);
    }

    /** A set of them. */
    public static ListingPayload of(String scope, VerbSurface.Listing listing) {
        return new ListingPayload(listing.objects().stream()
            .map(result -> of(scope, result))
            .toList(), listing.next());
    }
}
