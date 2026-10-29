/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

/**
 * The name of a verb of the worklist scheme, as a placeholder of a text.
 *
 * <p>One of the five placeholder types. An instance comes only from
 * {@link SurfaceDeclaration}, which holds the verbs this service carries and
 * the verbs of the scheme it refuses by name; a verb name a call invented
 * never becomes one.
 */
public final class VerbName {

    private final String name;

    private VerbName(String name) {
        this.name = name;
    }

    /** Used by the declaration, and only there, to bring a declared verb name into being. */
    static VerbName of(String name) {
        return new VerbName(name);
    }

    /**
     * The declared verb of this name.
     *
     * @throws IllegalArgumentException for a name the declaration does not hold
     */
    public static VerbName declared(String name) {
        return SurfaceDeclaration.verbName(name)
            .orElseThrow(() -> new IllegalArgumentException("an undeclared verb name"));
    }

    public String name() {
        return name;
    }

    /**
     * The name the assistant surface calls this verb by: the scheme and an
     * underscore before the verb (C4.2). The REST surface calls it by
     * {@link #name()}; which of the two a caller hears is the surface it came
     * through (C6.2).
     */
    public String toolName() {
        return DeclarationDocument.SCHEME + "_" + name;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VerbName that && name.equals(that.name);
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
