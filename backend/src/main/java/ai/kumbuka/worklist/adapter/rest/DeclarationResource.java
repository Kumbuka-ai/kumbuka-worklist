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
 * <p>{@code GET /api/declaration} answers, per verb, the identity
 * {@code worklist_<verb>}, the effect class in the router's own names, the
 * arguments with their names, types, requiredness, defaults and value sets,
 * the shape of the answer, the reason codes a refusal of the verb may name,
 * and the texts the verb addresses to a caller; beside them the catalogue of
 * every reason with its status, message and remedy. The document carries a
 * version, a hash over its own content, and the same value travels as the
 * entity tag, so that a reader can tell two declarations apart without
 * comparing them.
 *
 * <p>The path follows {@code /api/whoami}: a literal segment under
 * {@code /api}, which JAX-RS matches before the {@code /api/{scope}} template,
 * so no scope address is shadowed -- a scope named {@code declaration} is still
 * addressed at {@code /api/declaration/<view>}. Authenticated like every other
 * route of this service; nothing in it is a tenant's.
 */
@Path("/api/declaration")
@Authenticated
@Produces(MediaType.APPLICATION_JSON)
public class DeclarationResource {

    @GET
    public Response declaration() {
        Map<String, Object> document = DeclarationDocument.document();
        return Response.ok(document)
            .tag(new EntityTag(String.valueOf(document.get(DeclarationDocument.VERSION))))
            .build();
    }
}
