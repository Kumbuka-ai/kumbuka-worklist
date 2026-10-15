/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

/**
 * A bound the service declares -- the largest page, the default page, the
 * longest title -- as a placeholder of a text.
 *
 * <p>One of the five placeholder types. The instances are the constants of
 * {@link SurfaceDeclaration}; the constructor is private, so a number that
 * arrived with a call never becomes a bound a text repeats.
 */
public final class Bound {

    private final long value;

    private Bound(long value) {
        this.value = value;
    }

    /** Used by the declaration, and only there, to bring a declared bound into being. */
    static Bound of(long value) {
        return new Bound(value);
    }

    public long value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Bound that && value == that.value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
