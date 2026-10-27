/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.tenancy;

import io.quarkus.hibernate.orm.PersistenceUnitExtension;
import io.quarkus.hibernate.orm.runtime.tenant.TenantResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;

/**
 * Hibernate's per-session tenant lookup — layer 1 of the enforcement model.
 *
 * <p>Delegates to {@link TenantContext#current()} and never to
 * {@link ai.kumbuka.worklist.tenancy.TenantResolver} directly, so a
 * programmatic bind steers the ORM filter and the database GUC to the same
 * value.
 *
 * <p>Implements Quarkus' Hibernate integration interface, which is a
 * different type of the same simple name as this service's own resolver SPI.
 */
@PersistenceUnitExtension
@ApplicationScoped
public class HibernateTenantResolver implements TenantResolver {

    @Inject TenantContext context;

    @Override
    public @Nullable String getDefaultTenantId() {
        // No fallback. Every session must arrive with a bound tenant: a
        // default here would be the one value that silently makes an
        // unbound session look like a legitimate one.
        return null;
    }

    @Override
    public String resolveTenantId() {
        return context.current().toString();
    }
}
