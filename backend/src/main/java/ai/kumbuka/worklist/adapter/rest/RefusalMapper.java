/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.rest;

import ai.kumbuka.worklist.adapter.payload.RefusalPayload;
import ai.kumbuka.worklist.domain.WorklistException;
import ai.kumbuka.worklist.surface.RefusalCode;
import ai.kumbuka.worklist.surface.SurfaceException;
import ai.kumbuka.worklist.surface.UnexpectedFailures;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Turns the two families of typed refusal into HTTP, and keeps them apart.
 *
 * <h2>The status is not a judgement made here</h2>
 *
 * For a surface refusal the status travels on the reason itself. For a domain
 * refusal it is decided by the table below — one entry per reason, no default
 * branch that swallows a new one. A {@code switch} over an enum with no default
 * is what makes an added reason a compile error rather than a silent 500, and
 * this is exactly the place where a silent 500 would be indefensible: the reasons
 * are the published contract of the surface.
 *
 * <h2>The status is decided here; the reason code is not</h2>
 *
 * What goes in the body — the reason code, the message, the {@code data} — is
 * {@link RefusalPayload}'s, because DEC-0042 fixes one envelope for this
 * surface and the protocol surface alike and the two must not each build their
 * own. What is decided here is the HTTP status, which the protocol surface has
 * no equivalent of.
 *
 * <p>That split is why the not-found group below still names seven reasons
 * while the wire carries one code for all of them: the status table is the
 * service's own reading of its refusals, and the envelope is the platform's.
 *
 * <h2>Three statuses that look wrong and are not</h2>
 *
 * {@code SCOPE_UNRESOLVED} answers <strong>404</strong> rather than 403. The
 * directory answers for the bound subject only and existence in its answer IS the
 * permission, so a 403 would confirm that a scope exists to a caller who may not
 * see it — turning the error path into a scope enumerator. This is the standing
 * rule "404 and never 403", applied where it was always meant to apply.
 *
 * <p>{@code CONFLICT} answers <strong>412</strong> and not 409. The token
 * travels as {@code If-Match}, and 412 is the answer HTTP defines for a
 * precondition that did not hold — a client library retries or refreshes on it
 * without being taught anything about this scheme. 409 is kept for the states
 * that are conflicts of the object rather than of the caller's copy of it.
 *
 * <p>{@code IDENTIFIER_UNDECIDED} answers <strong>501</strong>. The call is
 * well-formed, addressed at a real item, and correct; what is missing is on our
 * side. A 4xx would tell the caller to change something, and there is nothing
 * they could change.
 */
@Provider
public class RefusalMapper implements ExceptionMapper<SurfaceException> {

    /**
     * The one place every refusal that reaches a REST caller passes through; the
     * MCP adapter logs the refusals it answers in the same words at the same
     * level, and a defect is logged by {@link UnexpectedFailures} on both.
     *
     * <p>Logging here rather than at the throw sites is not tidiness: every site
     * is a chance to do it differently, and the one that gets forgotten is the
     * one somebody needed.
     *
     * <p><strong>DEBUG and not WARN, deliberately.</strong> A malformed address
     * arrives on every client typo, and a refusal log at WARN would make this
     * service's operational log writable by whoever calls it — flood the surface
     * with broken addresses and you fill the log. What belongs at WARN is a
     * statement about the deployment, and those already live where they are
     * decided: the scope directory warns on an unresolved scope, and the selector
     * registry warns on nothing a caller can provoke.
     *
     * <p>The subject is absent, as everywhere in this service. Correlation runs
     * through a request id; a second aggregatable record of who was refused what
     * is how not-collecting-behavioural-data gets circumvented without anybody
     * deciding to.
     */
    private static final Logger LOG = Logger.getLogger(RefusalMapper.class);

    /**
     * Mapped per exception type rather than over {@code RuntimeException}.
     *
     * <p>A mapper registered for the supertype is chosen for every runtime
     * exception the framework raises too — the 405 a wrong method produces, the
     * 415 a missing content type produces — and re-throwing them from inside a
     * mapper turns each into a 500. The framework's own refusals are part of this
     * surface's contract, so they must reach the caller as themselves.
     */
    @Override
    public Response toResponse(SurfaceException e) {
        LOG.debugf("surface refusal: %s -> %d", e.reason().name(), e.reason().status());

        Response.ResponseBuilder response = Response.status(e.reason().status())
            .type(MediaType.APPLICATION_JSON)
            .entity(RefusalPayload.of(e));

        // A 405 without Allow refuses without saying what would have worked,
        // which is the one thing the status is required to carry.
        e.allow().ifPresent(allow -> response.header(HttpHeaders.ALLOW, allow));
        return response.build();
    }

    /** The domain's refusals, on the same shape and the same discipline. */
    @Provider
    public static class Domain implements ExceptionMapper<WorklistException> {

        @Override
        public Response toResponse(WorklistException e) {
            int status = RefusalCode.of(e.reason()).status();

            // Every refusal at DEBUG, by its code (C13.3), a 5xx among them:
            // the anomaly behind SESSION_NOT_BOUND is logged at WARN where it
            // is found, and ERROR belongs to the unexpected failure alone.
            LOG.debugf("domain refusal: %s -> %d", e.reason().name(), status);

            return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(RefusalPayload.of(e))
                .build();
        }

    }

    /**
     * Every failure nobody anticipated, in the same envelope (DEC-0043 R13),
     * and the framework's own 404 and 405 (C8.3).
     *
     * <p>Registered for {@link Exception} and therefore chosen only where no
     * more specific mapper applies: the two typed refusals above keep theirs.
     * The framework's answers travel as {@link WebApplicationException}. A
     * route that does not exist is {@code NOT_FOUND}, byte for byte the one of
     * an absent object (C8.4); a method the route does not take is {@code
     * VERB_NOT_CARRIED} with {@code Allow} and the verbs carried at the
     * address (C3.3). Every other status of the framework -- a 415 for a body
     * of the wrong type, the security layer's 401 with its challenge -- is
     * handed back as it came: the authentication exception of DEC-0042.
     *
     * <p>The exception's own message never reaches the caller: whatever threw
     * it wrote it, and it can carry a value the call sent. The log line and
     * the answer share a reference instead, and the line names the exception
     * type only (DEC-0051).
     */
    @Provider
    public static class Unexpected implements ExceptionMapper<Exception> {

        @Context UriInfo uri;

        @Override
        public Response toResponse(Exception e) {
            if (e instanceof WebApplicationException framework) {
                Response given = framework.getResponse();
                return switch (given.getStatus()) {
                    case 404 -> refusal(new SurfaceException(RefusalCode.NOT_FOUND));
                    case 405 -> methodNotCarried(given, depthOf(uri.getPath()));
                    default -> given;
                };
            }
            return Response.status(RefusalCode.UNEXPECTED_FAILURE.status())
                .type(MediaType.APPLICATION_JSON)
                .entity(RefusalPayload.unexpected(UnexpectedFailures.reference(null, e)))
                .build();
        }

        /**
         * The framework's 405 in the envelope: the verbs carried at the
         * address where it is one, and the route's methods in {@code Allow}.
         * The framework's own {@code Allow} is taken where it sends one.
         */
        private static Response methodNotCarried(Response given,
                                                 Optional<CustomMethod.Depth> depth) {
            String allow = given.getHeaderString(HttpHeaders.ALLOW);
            if (allow == null) {
                allow = depth.map(CustomMethod::allow).orElse(null);
            }
            return refusal(new SurfaceException(RefusalCode.VERB_NOT_CARRIED, allow, List.of(),
                depth.map(CustomMethod::accepts).orElse(List.of()), null));
        }

        /** The depth of a REST path: a collection, an object, a membership, or none. */
        private static Optional<CustomMethod.Depth> depthOf(String path) {
            String[] segments = (path.startsWith("/") ? path.substring(1) : path).split("/", -1);
            if (segments.length < 3 || !"api".equals(segments[0])) {
                return Optional.empty();
            }
            return switch (segments.length) {
                case 3 -> Optional.of(CustomMethod.Depth.COLLECTION);
                case 4 -> Optional.of(CustomMethod.Depth.ITEM);
                case 5 -> Optional.of(CustomMethod.Depth.MEMBERSHIP);
                default -> Optional.empty();
            };
        }

        /** A surface refusal as the REST surface answers it, through the one mapping above. */
        private static Response refusal(SurfaceException refused) {
            return new RefusalMapper().toResponse(refused);
        }
    }
}
