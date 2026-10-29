/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.rest;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.MDC;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every call a request id (C14.2): the {@code Kumbuka-Request-Id} the
 * request carries where it is one UUID, a fresh one otherwise. The id stands
 * in the MDC as {@code request_id} for the duration of the call, so every log
 * line the call writes carries it, and goes back in the response header, on
 * an answer and on a refusal alike.
 *
 * <p>A value that is no UUID names nothing the platform minted and is caller
 * input (DEC-0043 R18): it is replaced, never adopted and never echoed. The
 * router mints the id it forwards (C14.1), so a call through the router
 * carries the router's.
 *
 * <p>Runs before the request is matched and before every other filter, so
 * that whatever refuses the call is logged and answered under its id. Both
 * surfaces pass here: the assistant surface is a resource of this
 * application like the REST one.
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION - 100)
public class RequestIdFilter implements ContainerRequestFilter, ContainerResponseFilter {

    /** The header the id arrives and leaves in. */
    static final String HEADER = "Kumbuka-Request-Id";

    /** The key of the id in the MDC, which the log format prints (C13.1). */
    static final String MDC_KEY = "request_id";

    /** The one form a request id takes: a UUID in its canonical text. */
    private static final Pattern UUID_FORM = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Override
    public void filter(ContainerRequestContext request) {
        String id = idOf(request.getHeaders().get(HEADER));
        request.setProperty(MDC_KEY, id);
        MDC.put(MDC_KEY, id);
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        Object id = request.getProperty(MDC_KEY);
        if (id != null) {
            response.getHeaders().putSingle(HEADER, id);
        }
        MDC.remove(MDC_KEY);
    }

    /** The id a request carries where it carries exactly one UUID; a fresh one otherwise. */
    static String idOf(@Nullable List<String> sent) {
        if (sent != null && sent.size() == 1 && UUID_FORM.matcher(sent.get(0)).matches()) {
            return sent.get(0).toLowerCase(Locale.ROOT);
        }
        return UUID.randomUUID().toString();
    }
}
