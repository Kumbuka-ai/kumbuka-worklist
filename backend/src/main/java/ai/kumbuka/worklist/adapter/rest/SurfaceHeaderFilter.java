/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.rest;

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

import java.util.List;

/**
 * Refuses a {@code Kumbuka-Surface} header the REST surface cannot take:
 * another value than {@code assistant}, or the header twice, is
 * {@code HEADER_INVALID} 400 with no {@code data} (C14.5).
 *
 * <p>It runs before the request is matched to a resource, so it is decided
 * before anything else the request could be refused for: the route and its
 * method, the address, the scope, the body and the object. It runs after
 * authentication: a caller with no identity is left to the refusal of the
 * resource, which is the one for an unauthenticated call.
 *
 * <p>The header is read on every route of the REST surface; what
 * {@code assistant} changes in an answer is the resource's (C14.4). The MCP
 * adapter is not under {@code /api} and never sees this filter.
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION + 50)
public class SurfaceHeaderFilter implements ContainerRequestFilter {

    /** The path the REST surface is served under, as a request's path begins. */
    private static final String REST_ROOT = "api/";

    @Inject SecurityIdentity identity;

    @Override
    public void filter(ContainerRequestContext request) {
        String path = request.getUriInfo().getPath();
        String relative = path.startsWith("/") ? path.substring(1) : path;
        if (!relative.startsWith(REST_ROOT) || identity.isAnonymous()) {
            return;
        }
        List<String> named = request.getHeaders().get(WorklistResource.SURFACE);
        if (named != null && !named.isEmpty()
                && !List.of(WorklistResource.ASSISTANT).equals(named)) {
            throw new SurfaceException(RefusalCode.HEADER_INVALID);
        }
    }
}
