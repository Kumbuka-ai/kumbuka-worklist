/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.payload;

import ai.kumbuka.worklist.domain.WorklistException;
import ai.kumbuka.worklist.surface.AddressParser;
import ai.kumbuka.worklist.surface.AdministrationSurface;
import ai.kumbuka.worklist.surface.PlatformLog;
import ai.kumbuka.worklist.surface.RefusalCatalogue;
import ai.kumbuka.worklist.surface.RefusalCode;
import ai.kumbuka.worklist.surface.SurfaceDeclaration;
import ai.kumbuka.worklist.surface.SurfaceException;
import ai.kumbuka.worklist.surface.VerbName;
import ai.kumbuka.worklist.surface.VerbSurface;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The one refusal envelope, from DEC-0042: {@code reason}, {@code message},
 * and machine-readable detail under {@code data} where a refusal has any.
 *
 * <p>One record for both expositions, because the node fixes one envelope for
 * both — "on the protocol tool error and on the REST error body, on a
 * service's own adapter and on the router". Two records would be two places
 * for it to drift, and the drift would be invisible: each surface would keep
 * answering, just not the same way.
 *
 * <h2>Where the words come from</h2>
 *
 * The message is the refusal catalogue's for the code ({@link RefusalCatalogue}),
 * worded once in the message bundles; neither exception carries text. The
 * members of {@code data} are declared argument paths and verb names:
 * a spelling the declaration lacks can only be something a call sent, so it is
 * left out and the omission is logged as the defect it is (DEC-0043 R18).
 *
 * <h2>The not-found class</h2>
 *
 * DEC-0042: "An address of an object that does not exist, an address in a
 * scope the caller cannot see, and an address whose scheme the router cannot
 * route are refused with the single code {@code NOT_FOUND}, in the same
 * envelope, with no {@code data} and no message that distinguishes them,
 * whether a service or the router produced the answer." The domain's reasons
 * for that class are collapsed in {@link RefusalCode#of}, once, for both
 * surfaces; the catalogue words {@code NOT_FOUND} character for character as
 * the router's {@code RouterException.NOT_FOUND_MESSAGE} does.
 *
 * @param data the machine-readable detail; absent where the refusal has none,
 *             and then left out of the envelope rather than sent empty
 */
public record RefusalPayload(
    @JsonProperty("reason") String reason,
    @JsonProperty("message") String message,
    @JsonProperty("data") @Nullable RefusalDataPayload data) {

    /** The service a stale token's object is held by, as its addresses spell it. */
    private static final String SERVICE = AddressParser.SCHEME;

    /**
     * A domain refusal, in the envelope.
     *
     * @param attempted the call as the caller's surface names it (C6.2);
     *                  absent where the request had not become a call
     */
    public static RefusalPayload of(WorklistException e, @Nullable String attempted) {
        RefusalCode code = RefusalCode.of(e.reason());
        if (code == RefusalCode.NOT_FOUND) {
            // No data, not even attempted (C8.4): the offenders would name
            // the very object whose existence the answer is refusing to
            // confirm, and any member would set it apart from an unroutable
            // address.
            return new RefusalPayload(code.name(), message(code, null), null);
        }
        return new RefusalPayload(code.name(), message(code, null),
            dataOf(code, attempted, e.offenders(), e.accepts(),
                e.currentToken().orElse(null)));
    }

    /**
     * A stale conflict token, with the object as it stands now (DEC-0041,
     * C8.7): its address, its fields and its token, and the service that holds
     * it, so that a caller can redo its change on what is there without a read
     * of its own. The adapter reads the object after the refused write has
     * rolled back; where that read cannot be made, {@link #of(WorklistException,
     * String)} answers with the token alone.
     *
     * @param attempted the call as the caller's surface names it (C6.2)
     * @param current   the object at the address the call named, read now
     */
    public static RefusalPayload stale(WorklistException e, @Nullable String attempted,
                                       ObjectPayload current) {
        RefusalCode code = RefusalCode.of(e.reason());
        String held = current.conflictToken();
        String token = held != null ? held : e.currentToken().orElse(null);
        return new RefusalPayload(code.name(), message(code, null),
            new RefusalDataPayload(attempted, current.address(), current.fields(),
                present(declared(code, e.offenders())), null, token, SERVICE, null,
                null, null, null));
    }

    /**
     * A refusal by state (C8.7, DEC-0043): the object's state and the calls
     * open on it, as its own read answers them now that the refused write has
     * rolled back, and, where the refusal concerns objects under it, their
     * addresses under {@code children}. The adapter reads the object; where
     * that read cannot be made, {@link #of(WorklistException, String)}
     * answers with the code alone.
     *
     * @param attempted the call as the caller's surface names it (C6.2)
     * @param scope     the scope as the call named it, which the addresses of
     *                  the open calls carry
     * @param current   the refused object, read now
     * @param toolNames whether the open calls are named by their tool names
     */
    public static RefusalPayload byState(WorklistException e, @Nullable String attempted,
                                         String scope, VerbSurface.Result current,
                                         boolean toolNames) {
        RefusalCode code = RefusalCode.of(e.reason());
        return new RefusalPayload(code.name(), message(code, null),
            new RefusalDataPayload(attempted, null, null, null, null, null, null, null,
                current.state(), Payloads.next(scope, current.next(), toolNames),
                present(e.children())));
    }

    /** Whether a domain refusal names the state of its object and the calls open on it. */
    public static boolean isByState(WorklistException e) {
        return BY_STATE.contains(RefusalCode.of(e.reason()));
    }

    /** The codes of a refusal by state, which name the state and the open calls (C8.7). */
    private static final Set<RefusalCode> BY_STATE =
        EnumSet.of(RefusalCode.TRANSITION_REFUSED, RefusalCode.ITERATION_INCOMPLETE);

    /**
     * A surface refusal, in the same envelope.
     *
     * @param attempted the call as the caller's surface names it (C6.2);
     *                  absent where the request had not become a call
     */
    public static RefusalPayload of(SurfaceException e, @Nullable String attempted) {
        RefusalCode code = e.reason();
        String message = message(code,
            e.attempted().map(VerbSurface::declaredVerb).orElse(null));
        if (code == RefusalCode.NOT_FOUND) {
            return new RefusalPayload(code.name(), message, null);
        }
        return new RefusalPayload(code.name(), message,
            dataOf(code, attempted, e.offenders(), e.accepts(), null));
    }

    /**
     * An unanticipated failure (DEC-0043 R13): it names no rule, says that it
     * is a defect, and carries a reference the log line shares. Nothing the
     * failure itself said reaches the caller.
     *
     * @param attempted the call as the caller's surface names it (C6.2);
     *                  absent where the request had not become a call
     */
    public static RefusalPayload unexpected(String reference, @Nullable String attempted) {
        RefusalCode code = RefusalCode.UNEXPECTED_FAILURE;
        return new RefusalPayload(code.name(), message(code, null),
            new RefusalDataPayload(attempted, null, null, null, null, null, null, reference,
                null, null, null));
    }

    /** @param attempted the verb the text names; absent where it names none */
    private static String message(RefusalCode code, @Nullable VerbName attempted) {
        return RefusalCatalogue.message(code,
            new RefusalCatalogue.Context(attempted, RefusalCatalogue.boundOf(code).orElse(null)));
    }

    /**
     * The detail under {@code data}, made of declared paths only; absent where
     * there is none. The envelope then carries no {@code data} rather than an
     * empty one: an empty object is a member, and a caller reading the key set
     * would see a difference where the node says there is none.
     *
     * @param attempted     the call as the caller's surface names it; absent
     *                      where the request had not become a call
     * @param conflictToken the object's current token; absent except for a stale one
     */
    private static @Nullable RefusalDataPayload dataOf(RefusalCode code,
                                                       @Nullable String attempted,
                                                       List<String> offenders,
                                                       List<String> accepts,
                                                       @Nullable String conflictToken) {
        RefusalDataPayload data = new RefusalDataPayload(attempted, null, null,
            present(declared(code, offenders)), present(declared(code, accepts)),
            conflictToken, null, null, null, null, null);
        return data.isEmpty() ? null : data;
    }

    /**
     * The spellings a refusal may name: the declared names of the verbs, and
     * the arguments of the administration surface, which the declaration does
     * not carry (C1.6) and which are written once on that surface.
     */
    private static List<String> declared(RefusalCode code, List<String> spellings) {
        List<String> names = spellings.stream().filter(RefusalPayload::isDeclared).toList();
        if (names.size() != spellings.size()) {
            // A throw site named something the declaration lacks. The spelling
            // stays out of the answer and out of this line (DEC-0051): the
            // line counts the left-out paths, and says nothing of them.
            List<String> undeclared = spellings.stream()
                .filter(spelling -> !isDeclared(spelling))
                .toList();
            PlatformLog.undeclaredPathsLeftOut(PlatformLog.Reason.of(code),
                PlatformLog.Count.of(undeclared));
        }
        return names;
    }

    private static boolean isDeclared(String spelling) {
        return !SurfaceDeclaration.declaredNames(List.of(spelling)).isEmpty()
            || AdministrationSurface.ARGUMENTS.contains(spelling);
    }

    /** @return the paths; absent where there are none, so the member is left out */
    private static @Nullable List<String> present(List<String> paths) {
        return paths.isEmpty() ? null : paths;
    }
}
