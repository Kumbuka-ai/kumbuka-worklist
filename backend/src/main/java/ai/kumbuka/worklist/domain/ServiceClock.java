/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

import java.time.Clock;

/**
 * The one clock of this service (C20.1).
 *
 * <p>Every part that reads the time injects {@link Clock} and reads it, so
 * that a test can hand the service a fixed time and every part of one call
 * reads the same one. This is the only class that builds the system clock;
 * {@code ClockUsageTest} refuses any other.
 */
@ApplicationScoped
public class ServiceClock {

    @Produces
    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }
}
