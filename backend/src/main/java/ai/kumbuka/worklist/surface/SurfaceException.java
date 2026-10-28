/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * A typed refusal the surface itself produces, as distinct from one the domain
 * produces.
 *
 * <p>The two are kept apart deliberately. {@code WorklistException} says
 * something about an item, an iteration or a milestone — its state, its
 * vocabulary, its conflict token — and the domain is the only place that knows
 * those things. This one says something about the <em>call</em>: the address
 * does not parse, the scheme does not carry the verb, a writing verb arrived on
 * a collection. None of that needs an object to exist, and most of it is
 * decided before one is looked for.
 *
 * <p>It carries a {@link RefusalCode} and the detail of {@code data}, and no
 * text: the message and the remedy are the catalogue's, read from the message
 * bundles when the refusal reaches the edge ({@link RefusalCatalogue}). The
 * status travels with the code, because the choice is part of the published
 * contract and a mapper is exactly where a choice gets quietly changed.
 */
public class SurfaceException extends RuntimeException {

    private final transient RefusalCode reason;
    /** The {@code Allow} header value; absent where the status requires none. */
    private final transient @Nullable String allow;
    private final transient List<String> offenders;
    private final transient List<String> accepts;
    /** The declared verb the refusal's text names; absent where it names none. */
    private final transient @Nullable String attempted;

    public SurfaceException(RefusalCode reason) {
        this(reason, null);
    }

    /**
     * @param allow the {@code Allow} header value, where the status requires
     *              one. 405 without it is a refusal that does not say what
     *              would have worked. Absent where the status requires none.
     */
    public SurfaceException(RefusalCode reason, @Nullable String allow) {
        this(reason, allow, List.of(), List.of(), null);
    }

    /**
     * A refusal with detail under {@code data}.
     *
     * @param offenders the declared argument paths the refusal concerns
     * @param accepts   the names the call declares, carried by a refusal of a
     *                  name the call does not declare
     * @param allow     the {@code Allow} header value; absent where the status
     *                  requires none
     * @param attempted the declared verb the refusal's text names; absent where
     *                  it names none
     */
    public SurfaceException(RefusalCode reason, @Nullable String allow, List<String> offenders,
                            List<String> accepts, @Nullable String attempted) {
        super(reason.name());
        this.reason = reason;
        this.allow = allow;
        this.offenders = List.copyOf(offenders);
        this.accepts = List.copyOf(accepts);
        this.attempted = attempted;
    }

    /** A refusal whose text names the verb the call attempted. */
    public static SurfaceException of(RefusalCode reason, String attempted) {
        return new SurfaceException(reason, null, List.of(), List.of(), attempted);
    }

    /** Mandatory arguments that did not arrive, by their declared names. */
    public static SurfaceException missing(String... arguments) {
        return new SurfaceException(RefusalCode.ARGUMENT_MISSING, null, List.of(arguments),
            List.of(), null);
    }

    /** A value the argument does not admit, by the argument's declared path. */
    public static SurfaceException invalid(String argument) {
        return new SurfaceException(RefusalCode.ARGUMENT_INVALID, null, List.of(argument),
            List.of(), null);
    }

    /** A value the argument does not admit, answered with the names it does admit. */
    public static SurfaceException invalid(String argument, List<String> accepts) {
        return new SurfaceException(RefusalCode.ARGUMENT_INVALID, null, List.of(argument),
            accepts, null);
    }

    /** Names the call does not declare, answered with the ones it does. */
    public static SurfaceException unknown(List<String> accepts) {
        return new SurfaceException(RefusalCode.ARGUMENT_UNKNOWN, null, List.of(), accepts,
            null);
    }

    public RefusalCode reason() {
        return reason;
    }

    /** The {@code Allow} header value, where the status requires one. */
    public Optional<String> allow() {
        return Optional.ofNullable(allow);
    }

    public List<String> offenders() {
        return offenders;
    }

    public List<String> accepts() {
        return accepts;
    }

    /** The declared verb the refusal's text names, where it names one. */
    public Optional<String> attempted() {
        return Optional.ofNullable(attempted);
    }
}
