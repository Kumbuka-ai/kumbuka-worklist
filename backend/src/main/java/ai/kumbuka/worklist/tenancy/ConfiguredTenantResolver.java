/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.tenancy;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * The substrate's tenant resolver: one configured tenant for the whole
 * deployment.
 *
 * <p>The service authenticates against the tenant realm, so an authenticated
 * caller is by construction a caller of this deployment's tenant. Deriving a
 * per-request tenant from an organisation claim is the multi-tenant edition's
 * business and is deliberately absent here: the substrate establishes the
 * axis and its enforcement, not the directory that populates it. Everything
 * downstream — the ORM filter, the GUC, the policy — is indifferent to which
 * of the two produced the value, which is why the second can be added later
 * without touching any of them.
 */
@ApplicationScoped
public class ConfiguredTenantResolver implements TenantResolver {

    /**
     * The configured tenant, as the RAW string.
     *
     * <p>Not injected as a {@code UUID}, and the reason is in
     * {@link TenantConfigurationGuard}: the two unusable values this key can
     * actually carry — the sentinel, and the empty string an empty
     * environment variable produces — would both fail in Quarkus'
     * configuration validation, with a conversion message that names the key
     * and nothing else. Reading it raw is what lets the refusal say which
     * variable to set and why there is no default.
     *
     * <p>Absent only before the container has injected it; the guard refuses
     * an absent value as unconfigured.
     */
    @ConfigProperty(name = TenantConfigurationGuard.KEY)
    @Nullable String tenantId;

    @Override
    public UUID currentTenant() {
        // Judged and parsed by the same method the start-up guard calls, so
        // the two cannot disagree. Reaching here with an unusable value is
        // not possible in a started application — the guard refuses the start
        // — and the call is kept rather than cached because a resolver that
        // cached it would be a second place holding the axis.
        return TenantConfigurationGuard.requireUsable(tenantId);
    }
}
