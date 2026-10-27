/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Singleton;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

/**
 * A clock a test sets: the service reads the instant the test names, and
 * nothing moves it but the test.
 *
 * <p>An alternative to the producer of {@link ServiceClock}, enabled only by
 * {@link Profile}, so a test that does not ask for it reads the system clock
 * as the service does in production.
 */
@Alternative
@Singleton
public class SettableClock extends Clock {

    private volatile Instant now = Instant.parse("2031-03-04T05:06:07.123456Z");

    /** Sets the instant every later read answers. */
    public void set(Instant instant) {
        this.now = instant;
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("the service reads UTC only");
    }

    /** The profile that hands the service this clock in place of the system clock. */
    public static class Profile implements QuarkusTestProfile {

        @Override
        public Set<Class<?>> getEnabledAlternatives() {
            return Set.of(SettableClock.class);
        }
    }
}
