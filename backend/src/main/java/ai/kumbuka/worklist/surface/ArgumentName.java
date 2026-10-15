/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

/**
 * The name of an argument this service declares, as a placeholder of a text.
 *
 * <p>One of the five types a message bundle parameter may take
 * (`code-conventions/service-construction.md`, texts addressed to a caller,
 * rule 2). An instance comes only from {@link SurfaceDeclaration}: the
 * constructor is private, and {@link #declared(String)} answers a name only
 * when the declaration holds it. A name that arrived with a call and is no
 * declared name cannot become one, so a text cannot repeat it (DEC-0043 R18).
 */
public final class ArgumentName {

    private final String name;

    private ArgumentName(String name) {
        this.name = name;
    }

    /** Used by the declaration, and only there, to bring a declared name into being. */
    static ArgumentName of(String name) {
        return new ArgumentName(name);
    }

    /**
     * The declared argument of this name.
     *
     * @throws IllegalArgumentException for a name the declaration does not hold
     */
    public static ArgumentName declared(String name) {
        return SurfaceDeclaration.argument(name)
            .orElseThrow(() -> new IllegalArgumentException("an undeclared argument name"));
    }

    public String name() {
        return name;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ArgumentName that && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
