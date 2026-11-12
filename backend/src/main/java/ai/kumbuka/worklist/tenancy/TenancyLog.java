/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.tenancy;

import org.jboss.logging.Logger;

import java.util.UUID;

/**
 * Every log line of the tenancy aspect, and the only logger it holds
 * (DEC-0051, C4).
 *
 * <p>Part of the tenancy set, and like it imports nothing of its own service:
 * every type a line takes is declared here. A line carries the tenant the
 * installation is configured for and a constant naming a start problem, never
 * a value a caller chose and never a configuration value's text. Each line is
 * one method with a constant message; the methods are reachable from the
 * tenancy package only, and the types are built only there, from what the
 * aspect itself holds.
 *
 * <p>The request id travels through the MDC into the format, and the service
 * through the logger's category; neither is a parameter.
 */
public final class TenancyLog {

    /*
     * Each message is a literal inside its logger call, as in PlatformLog:
     * constant, and written once.
     */
    private static final Logger LOG = Logger.getLogger(TenancyLog.class);

    private TenancyLog() {
    }

    /**
     * INFO: one line per migration, when the callback has bound the tenant
     * before it runs. The line starts with the callback's marker.
     */
    static void bindingApplied(TenantId tenant) {
        LOG.infof(TenantMigrationCallback.MARKER + ": app.tenant_id=%s before a migration", tenant);
    }

    /**
     * WARN: one transaction asked to carry a second tenant, which can only be
     * a programming error.
     */
    static void rebinding(TenantId bound, TenantId requested) {
        LOG.warnf("tenant rebinding inside one transaction: %s -> %s", bound, requested);
    }

    /**
     * WARN, on a line of its own before the start fails (C13.3): the format
     * prints no exception (C13.1). The value that was wrong stays out of the
     * line.
     */
    static void startRefused(StartProblem problem) {
        LOG.warnf("start refused: %s", problem);
    }

    /** A tenant, as this installation holds it: configured or bound. */
    public static final class TenantId {

        private final UUID tenant;

        private TenantId(UUID tenant) {
            this.tenant = tenant;
        }

        static TenantId of(UUID tenant) {
            return new TenantId(tenant);
        }

        @Override
        public String toString() {
            return tenant.toString();
        }
    }

    /** What a start was refused for, without the value that was wrong. */
    public enum StartProblem {

        /** The tenant key carries no value at all, or a blank one. */
        TENANT_EMPTY,

        /** The tenant key is still the sentinel that stands in for an unset variable. */
        TENANT_SENTINEL,

        /** The tenant key holds something that is not a uuid. */
        TENANT_NOT_A_UUID
    }
}
