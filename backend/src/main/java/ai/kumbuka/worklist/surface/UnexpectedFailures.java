/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The one place an {@code UNEXPECTED_FAILURE} is minted, so that its reference
 * is always in the log (C8.3), and the one place this service logs at ERROR
 * (C13.3).
 *
 * <p>The refusal tells the caller to report the reference under {@code
 * data.reference}, and that is a promise about the service: the reference is
 * written to the log with the failure, on every path that raises it. So the
 * mint and the log line are one act, and nobody adding a catch site can
 * separate them.
 *
 * <p>The line carries the reference, the call where a declared one was
 * reached, and the class names of the exception chain -- never the exception
 * object (DEC-0051, C13.2). A stack trace and an exception's message carry
 * whatever the failing code held, a caller's value included, and a log
 * shipper carries them out of the container past a boundary built as a
 * missing grant. The class names say what failed; the reference finds the
 * line.
 */
public final class UnexpectedFailures {

    private static final Logger LOG = Logger.getLogger(UnexpectedFailures.class);

    /** How deep the chain of causes is followed: a cycle must not hang the log line. */
    private static final int CHAIN_LIMIT = 8;

    private UnexpectedFailures() {
    }

    /**
     * Records the failure and answers the reference the caller is given.
     *
     * @param verb  the verb the call named, or null where the request reached
     *              none; logged only where the declaration holds it, since
     *              any other spelling is what a caller sent
     * @param cause what went wrong
     */
    public static String reference(@Nullable String verb, Throwable cause) {
        String named = verb == null ? "this request"
            : SurfaceDeclaration.verbName(verb).map(VerbName::toString).orElse("this request");
        String reference = UUID.randomUUID().toString();
        LOG.errorf("unexpected failure on %s, reference %s, cause %s", named, reference,
            classNames(cause));
        return reference;
    }

    /** The class names of an exception and its causes, outermost first. */
    static String classNames(Throwable cause) {
        List<String> names = new ArrayList<>();
        Throwable at = cause;
        while (at != null && names.size() < CHAIN_LIMIT) {
            names.add(at.getClass().getName());
            at = at.getCause() == at ? null : at.getCause();
        }
        return String.join(" <- ", names);
    }
}
