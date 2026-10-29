/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.rest;

import ai.kumbuka.worklist.surface.AddressParser;
import ai.kumbuka.worklist.surface.RefusalCode;
import ai.kumbuka.worklist.surface.SurfaceException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

/**
 * Refuses a path under a reserved scope: {@code declaration} and {@code
 * whoami} are the names of the REST surface's two literal paths, and no scope
 * (C3.2). A path that goes on below one of them is {@code ADDRESS_MALFORMED}
 * 400 with no {@code data}, the trailing slash included.
 *
 * <p>It runs before the request is matched to a resource, so the name is
 * refused before the route and its method are looked up, and after the
 * surface header, which is decided before anything else (C14.5). The two
 * literal paths themselves pass. A caller with no identity is left to the
 * refusal of the resource, as for the header.
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION + 60)
public class ReservedScopeFilter implements ContainerRequestFilter {

    /** The path the REST surface is served under, as a request's path begins. */
    private static final String REST_ROOT = "api";

    @Inject SecurityIdentity identity;

    @Override
    public void filter(ContainerRequestContext request) {
        String path = request.getUriInfo().getPath();
        String[] segments = (path.startsWith("/") ? path.substring(1) : path).split("/", -1);
        if (segments.length < 3 || !REST_ROOT.equals(segments[0]) || identity.isAnonymous()) {
            return;
        }
        if (AddressParser.RESERVED_SCOPES.contains(segments[1])) {
            throw new SurfaceException(RefusalCode.ADDRESS_MALFORMED);
        }
    }
}
