/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.rest;

import ai.kumbuka.worklist.surface.DeclarationDocument;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * The service's declaration, offered on its REST surface (ADR-0039).
 *
 * <p>{@code GET /api/declaration} answers the declaration in shape 3 of the
 * service contract (C5.3, C5.4): per call the identity and call name
 * {@code worklist_<verb>}, the REST name, the effect class in the router's own
 * names, the arguments with their names, types, requiredness, placement,
 * defaults and fixed values, the shape of the answer with its members, the
 * reason codes a refusal of the call may name, and the texts the call
 * addresses to a caller; beside them the catalogue of every reason with its
 * message templates and remedy. The document carries a
 * version, a hash over its own content, and the same value travels as the
 * entity tag, so that a reader can tell two declarations apart without
 * comparing them.
 *
 * <p>The path follows {@code /api/whoami}: a literal segment under
 * {@code /api}, which JAX-RS matches before the {@code /api/{scope}} template.
 * The name is reserved and is never the name of a scope (C3.2): a path below
 * it is refused before any route is looked up ({@link ReservedScopeFilter}).
 * Authenticated like every other
 * route of this service; nothing in it is a tenant's.
 */
@Path("/api/declaration")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class DeclarationResource {

    /**
     * The document as every answer is written. It carries no member without a
     * value, so the mapper, which leaves such a member out (C7.2), writes the
     * content the version is the hash of.
     */
    @GET
    public Response declaration() {
        Map<String, Object> document = DeclarationDocument.document();
        return Response.ok(document)
            .tag(new EntityTag(String.valueOf(document.get(DeclarationDocument.VERSION))))
            .build();
    }
}
