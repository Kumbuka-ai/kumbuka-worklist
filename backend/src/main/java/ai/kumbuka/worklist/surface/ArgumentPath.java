/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import java.util.Optional;

/**
 * The path of a declared argument -- a top-level argument such as
 * {@code scope}, or a member of one such as {@code filter.status} or
 * {@code fields.title} -- as a placeholder of a text and as a member of a
 * refusal's {@code data}.
 *
 * <p>One of the five placeholder types. An instance comes only from
 * {@link SurfaceDeclaration}, which holds every path a call can concern; a
 * path assembled from what a call sent is answered with an empty
 * {@link Optional} and never becomes one (DEC-0043 R18, R19).
 */
public final class ArgumentPath {

    private final String path;

    private ArgumentPath(String path) {
        this.path = path;
    }

    /** Used by the declaration, and only there, to bring a declared path into being. */
    static ArgumentPath of(String path) {
        return new ArgumentPath(path);
    }

    /** The declared path of this spelling, or empty for a spelling the declaration lacks. */
    public static Optional<ArgumentPath> declared(String path) {
        return SurfaceDeclaration.path(path);
    }

    public String path() {
        return path;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ArgumentPath that && path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }

    @Override
    public String toString() {
        return path;
    }
}
