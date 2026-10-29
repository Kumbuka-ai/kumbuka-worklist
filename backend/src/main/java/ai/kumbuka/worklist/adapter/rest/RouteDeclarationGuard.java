/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.rest;

import ai.kumbuka.worklist.surface.SurfaceDeclaration;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Refuses to bring the service up while a verb it serves has no declaration,
 * or a declared verb has no route (ADR-0039: "a missing form declaration in a
 * service is a start-up refusal of that service, not a degraded surface").
 *
 * <p>The REST surface routes the plain verbs by HTTP method and the others by
 * their {@link CustomMethod}; the declaration is {@link SurfaceDeclaration}.
 * The two are held against each other at start-up and the refusal names the
 * verbs that differ. The MCP surface needs no such check: its tool list is
 * generated from the declaration.
 *
 * <p>The checks of the declaration itself -- distinct names, a description
 * per call and argument, the budget -- are {@code surface.DeclarationGuard}'s.
 * This one stays in the adapter because it is about the adapter's routes,
 * until a route test of the REST surface takes it over.
 */
@ApplicationScoped
public class RouteDeclarationGuard {

    private static final Logger LOG = Logger.getLogger(RouteDeclarationGuard.class);

    /** The verbs this surface routes by HTTP method rather than by colon notation. */
    static final List<String> ROUTED_BY_METHOD = List.of(
        "create", "read", "update", "query", "plan", "unplan");

    void onStart(@Observes StartupEvent event) {
        requireDeclared(served(), SurfaceDeclaration.verbs().stream()
            .map(v -> v.name().name()).toList());
        LOG.infof("routes match the declaration: %d verbs", SurfaceDeclaration.verbs().size());
    }

    /** The verbs the REST surface carries. */
    static Set<String> served() {
        Set<String> served = new LinkedHashSet<>(ROUTED_BY_METHOD);
        Arrays.stream(CustomMethod.values())
            .filter(m -> m.kind() == CustomMethod.Kind.CARRIED)
            .forEach(m -> served.add(m.verb()));
        return served;
    }

    /**
     * @throws IllegalStateException naming each verb served without a
     *                               declaration and each declared verb without
     *                               a route
     */
    static void requireDeclared(Set<String> served, List<String> declared) {
        Set<String> undeclared = new TreeSet<>(served);
        undeclared.removeAll(declared);
        Set<String> unrouted = new TreeSet<>(declared);
        unrouted.removeAll(served);
        if (!undeclared.isEmpty() || !unrouted.isEmpty()) {
            throw new IllegalStateException("the declaration and the routes differ: undeclared="
                + undeclared + " unrouted=" + unrouted);
        }
    }
}
