/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

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
 * <h2>The failure's class names, never its message</h2>
 *
 * The line carries the reference, the call where a declared one was reached,
 * and the class names of the exception chain -- never the exception object
 * and never its message (DEC-0051, C13.2). A stack trace and an exception's
 * message carry whatever the failing code held, a caller's value included,
 * and a log shipper carries them out of the container past a boundary built
 * as a missing grant. The class names say what failed; the reference finds
 * the line. So the failure is never handed to the logger; {@link FailureCause}
 * reads the class names off it, and is made here and nowhere else, as
 * {@link FailureReference} is.
 */
public final class UnexpectedFailures {

    /** How deep the chain of causes is followed: a cycle must not hang the log line. */
    private static final int CHAIN_LIMIT = 8;

    private UnexpectedFailures() {
    }

    /**
     * Logs an unforeseen failure once, at ERROR, and returns the reference it
     * was logged under.
     *
     * <p>The call is the one the declaration names, never a name a request
     * carried: a {@link VerbName} comes from the declaration only.
     *
     * @param call    the call the caller made; absent for a request that had
     *                become no call yet
     * @param failure what went wrong
     */
    public static String logged(@Nullable VerbName call, Throwable failure) {
        FailureReference reference = new FailureReference(UUID.randomUUID());
        FailureCause cause = new FailureCause(failure);
        if (call == null) {
            PlatformLog.unexpectedFailureBeforeACall(reference, cause);
        } else {
            PlatformLog.unexpectedFailure(call, reference, cause);
        }
        return reference.toString();
    }

    /** The reference a caller is told to report, minted for one failure. */
    public static final class FailureReference {

        private final UUID reference;

        private FailureReference(UUID reference) {
            this.reference = reference;
        }

        @Override
        public String toString() {
            return reference.toString();
        }
    }

    /**
     * The class names of a failure and its causes, outermost first. Read off
     * the chain by class and nothing else: no message, no stack frame.
     */
    public static final class FailureCause {

        private final String chain;

        private FailureCause(Throwable failure) {
            List<String> names = new ArrayList<>();
            Throwable at = failure;
            while (at != null && names.size() < CHAIN_LIMIT) {
                names.add(at.getClass().getName());
                at = at.getCause() == at ? null : at.getCause();
            }
            this.chain = String.join(" <- ", names);
        }

        @Override
        public String toString() {
            return chain;
        }
    }
}
