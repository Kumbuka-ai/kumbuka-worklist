/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.tenancy;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * An empty PostgreSQL of the production major, for a probe that drives the
 * migration set without the service.
 *
 * <p>Two probes need a database of their own and cannot use the ordinary
 * test datasource. {@link MigrationCallbackWitnessIT} varies which callbacks
 * are registered, which is build-time configuration a running application
 * cannot change. {@code ServiceRoleAttributeGuardIT} stages a service role
 * with the attributes the migration must refuse, which a booted service would
 * already have migrated past. Both run Flyway directly, against this
 * container.
 *
 * <p>The one class of the test tree that starts a database container apart
 * from {@link SubstrateDatabaseResource} (C22.3): an integration test that
 * starts its own database does it through here, which is how the test
 * taxonomy tells it from one that runs against nothing.
 */
public final class MigrationHarness implements AutoCloseable {

    private final PostgreSQLContainer<?> postgres;

    private MigrationHarness(PostgreSQLContainer<?> postgres) {
        this.postgres = postgres;
    }

    /** Starts an empty PostgreSQL of the production major. */
    public static MigrationHarness start() {
        PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(SubstrateDatabaseResource.POSTGRES_IMAGE)
                .withDatabaseName("kumbuka")
                .withUsername("postgres_admin")
                .withPassword("test-only-admin-password");
        postgres.start();
        return new MigrationHarness(postgres);
    }

    /** The JDBC url of the database {@code kumbuka}. */
    public String jdbcUrl() {
        return postgres.getJdbcUrl();
    }

    /** The container superuser. */
    public String username() {
        return postgres.getUsername();
    }

    public String password() {
        return postgres.getPassword();
    }

    @Override
    public void close() {
        postgres.stop();
    }
}
