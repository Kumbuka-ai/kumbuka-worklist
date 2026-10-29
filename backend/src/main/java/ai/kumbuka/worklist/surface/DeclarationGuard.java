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

/**
 * Refuses to bring the service up on a declaration it cannot serve (C5.6).
 *
 * <p>A guard at start-up rather than a test, because a test asserts the
 * declaration that was committed and this asserts the declaration that was
 * deployed: a build that packaged an older class or a bundle that lost a text
 * differs exactly there. The checks are {@link SurfaceDeclaration#requireServable};
 * {@code SurfaceDeclarationGuardTest} observes each of them refusing.
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
        LOG.infof("declaration served: %d calls, %d reasons", SurfaceDeclaration.verbs().size(),
            RefusalCode.values().length);
    }
}
