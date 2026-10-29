/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Refuses to start when the acting identity would not be the token's subject,
 * or when the audience is not checked at all (C15.2).
 *
 * <h2>The defect this blocks</h2>
 *
 * Quarkus resolves a service application's principal through
 * {@code upn -> preferred_username -> sub} unless it is pinned. In this
 * service the principal is the subject of every actor: it is recorded as the
 * author of a write and as the holder of a claim, and it is what a receipt is
 * bound to. An unpinned principal makes those an email address on one
 * deployment and a subject on the next, and a holder who stops being
 * recognised as the holder. Both fail quietly: the write succeeds, and the
 * next call of the same caller is refused as somebody else's.
 *
 * <p>The dispatch and memory services and the core carry the same guard for
 * the same reason, under the same name.
 *
 * <h2>Why the audience is checked here too</h2>
 *
 * An unset {@code quarkus.oidc.token.audience} does not fail; it means the
 * audience is not validated. A deployment that lost the value would accept a
 * token minted for anything at all, with a clean start and no error -- the
 * same failure shape as the principal claim, so it belongs in the same guard.
 * ADR-0034 gives the platform one audience and makes every recipient validate
 * it; what the value IS is a deployment's to configure, and what this asserts
 * is that there is one.
 *
 * <h2>The escape hatch, and why it is the tenant flag</h2>
 *
 * A disabled bearer tenant validates no token, so there is no principal to pin
 * and no audience to check. The default is {@code true}, so silence never
 * disarms the guard -- only an explicit statement that authentication is off
 * does, which is what a test that stands up no realm says.
 */
@ApplicationScoped
public class IdentityConfigurationGuard {

    private static final Logger LOG = Logger.getLogger(IdentityConfigurationGuard.class);

    /** The claim the acting identity must come from. */
    public static final String EXPECTED_PRINCIPAL_CLAIM = "sub";

    static final String PRINCIPAL_CLAIM_KEY = "quarkus.oidc.token.principal-claim";
    static final String AUDIENCE_KEY = "quarkus.oidc.token.audience";
    static final String TENANT_ENABLED_KEY = "quarkus.oidc.tenant-enabled";

    /**
     * Runs as early as an observer of the startup event can, so that the
     * refusal arrives before anything else the boot does can look like the
     * cause.
     */
    void refuseToStartOnAWrongIdentity(
            @Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent event) {
        verify(ConfigProvider.getConfig());
    }

    /**
     * The two settings, or a refusal naming the one that is wrong.
     *
     * <p>Takes the {@link Config} so that a test can run it against an
     * assembled configuration rather than only the one it booted with, which
     * is what lets the red state be observed without a second application.
     */
    public static void verify(Config config) {
        if (!config.getOptionalValue(TENANT_ENABLED_KEY, Boolean.class).orElse(true)) {
            return;
        }

        String claim = config.getOptionalValue(PRINCIPAL_CLAIM_KEY, String.class).orElse("");
        if (!EXPECTED_PRINCIPAL_CLAIM.equals(claim)) {
            throw logged(new IllegalStateException(
                "'" + PRINCIPAL_CLAIM_KEY + "' is '" + claim + "' and must be '"
                    + EXPECTED_PRINCIPAL_CLAIM + "'. The principal is the author of every "
                    + "write of this service and the holder of every claim; resolved from any "
                    + "other claim it becomes an identifier that can change, and a holder "
                    + "that changes is refused its own claim."));
        }

        Optional<String> audience = config.getOptionalValue(AUDIENCE_KEY, String.class);
        if (audience.isEmpty() || audience.get().isBlank()) {
            throw logged(new IllegalStateException(
                "'" + AUDIENCE_KEY + "' is not set, so no audience would be validated and a "
                    + "token minted for any other resource would be accepted here. ADR-0034 "
                    + "gives the platform one audience and has every recipient validate it "
                    + "against the identifier its own installation configures. Set "
                    + "WORKLIST_OIDC_AUDIENCE."));
        }
    }

    /**
     * Logs a refusal as one line, then answers the exception that stops the
     * start, unchanged: the reason stands in a line of this class, at WARN as a
     * configuration anomaly is (C13.3), and not only in the trace of whatever
     * the boot reports the failed start under.
     */
    private static IllegalStateException logged(IllegalStateException refusal) {
        LOG.warnf("start refused: %s", refusal.getMessage());
        return refusal;
    }
}
