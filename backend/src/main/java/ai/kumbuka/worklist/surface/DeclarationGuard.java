/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import io.quarkus.qute.i18n.MessageBundles;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

import java.util.Map;

/**
 * Refuses to bring the service up on a declaration it cannot serve (C5.6).
 *
 * <p>A guard at start-up rather than a test, because a test asserts the
 * declaration that was committed and this asserts the declaration that was
 * deployed: a build that packaged an older class or a bundle that lost a text
 * differs exactly there.
 *
 * <p>Two stages. {@link SurfaceDeclaration#requireServable} checks the verbs
 * and the texts they are worded by; then the guard builds the document
 * {@code GET /api/declaration} serves, by the method the resource calls,
 * {@link DeclarationDocument#document()}, and {@link
 * DeclarationDocument#requireServable} checks that document: every call once,
 * each described within the budget, each argument described, every refusal a
 * call names among the document's reasons. The second stage is the one that
 * decides, since a fault in projecting the verbs into the document passes the
 * first. In this service the refusals and the catalogue are one enum, so the
 * last condition cannot fail for the real document; it holds the projection of
 * the two to each other. {@code SurfaceDeclarationGuardTest} observes each
 * check refusing.
 *
 * <p>That every served verb has a route and every route a declared verb is a
 * statement about the REST adapter and is checked there, by
 * {@code adapter.rest.RouteDeclarationGuard}.
 */
@ApplicationScoped
public class DeclarationGuard {

    private static final Logger LOG = Logger.getLogger(DeclarationGuard.class);

    void onStart(@Observes StartupEvent event) {
        SurfaceDeclaration.requireServable(MessageBundles.get(ToolDescriptions.class),
            MessageBundles.get(ArgumentDescriptions.class));
        Map<String, Object> document = DeclarationDocument.document();
        DeclarationDocument.requireServable(document);
        LOG.infof("declaration served: %s, %d calls, %d reasons",
            document.get(DeclarationDocument.VERSION), SurfaceDeclaration.verbs().size(),
            RefusalCode.values().length);
    }
}
