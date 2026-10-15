/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

/**
 * Refuses to bring the service up on a refusal catalogue it cannot serve
 * (DEC-0043 R12).
 *
 * <p>Every {@link RefusalCode} must have a message and a remedy in the bundles,
 * every bundle method must belong to a code, and every code must render. A
 * guard at start-up rather than a test alone, because a test asserts the
 * catalogue that was committed and this asserts the catalogue that was
 * deployed. A service that came up with a code it cannot word would serve every
 * call correctly until the first refusal of that kind, and would then answer
 * the rule a caller broke with a defect of its own.
 *
 * <p>{@code RefusalCatalogueTest} holds the same check with a planted,
 * undeclared code, so that the guard's red state is observed in every build.
 */
@ApplicationScoped
public class RefusalCatalogueGuard {

    private static final Logger LOG = Logger.getLogger(RefusalCatalogueGuard.class);

    void onStart(@Observes StartupEvent event) {
        RefusalCatalogue.requireDeclared(RefusalCode.class);
        VerbName anyVerb = SurfaceDeclaration.carried().get(0);
        for (RefusalCode code : RefusalCode.values()) {
            RefusalCatalogue.message(code,
                new RefusalCatalogue.Context(anyVerb, RefusalCatalogue.boundOf(code)));
        }
        LOG.infof("refusal catalogue served: %d codes", RefusalCode.values().length);
    }
}
