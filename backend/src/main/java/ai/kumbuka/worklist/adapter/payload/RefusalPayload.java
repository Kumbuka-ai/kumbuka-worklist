/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.payload;

import ai.kumbuka.worklist.domain.WorklistException;
import ai.kumbuka.worklist.surface.RefusalCatalogue;
import ai.kumbuka.worklist.surface.RefusalCode;
import ai.kumbuka.worklist.surface.SurfaceDeclaration;
import ai.kumbuka.worklist.surface.SurfaceException;
import ai.kumbuka.worklist.surface.VerbName;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefusalPayload(String reason, String message, Object data) {

    private static final Logger LOG = Logger.getLogger(RefusalPayload.class);

    /** The member {@code offenders} travels under, inside {@code data}. */
    public static final String OFFENDERS = "offenders";

    /** The member the declared names travel under, inside {@code data} (DEC-0042). */
    public static final String ACCEPTS = "accepts";

    /** The member a reference to an unexpected failure travels under, inside {@code data}. */
    public static final String REFERENCE = "reference";

    /** A domain refusal, in the envelope. */
    public static RefusalPayload of(WorklistException e) {
        RefusalCode code = RefusalCode.of(e.reason());
        if (code == RefusalCode.NOT_FOUND) {
            // No data: the offenders would name the very object whose
            // existence the answer is refusing to confirm.
            return new RefusalPayload(code.name(), message(code, null), null);
        }
        Object data = dataOf(code, e.offenders(), e.accepts());
        if (e.currentToken() != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> withState = data == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>((Map<String, Object>) data);
            withState.put(CONFLICT_TOKEN, e.currentToken());
            data = withState;
        }
        return new RefusalPayload(code.name(), message(code, null), data);
    }

    /** The member the object's current conflict token travels under on a {@code CONFLICT}. */
    public static final String CONFLICT_TOKEN = "conflict_token";

    /** A surface refusal, in the same envelope. */
    public static RefusalPayload of(SurfaceException e) {
        return new RefusalPayload(e.reason().name(), message(e.reason(), e.attempted()),
            dataOf(e.reason(), e.offenders(), e.accepts()));
    }

    /**
     * An unanticipated failure (DEC-0043 R13): it names no rule, says that it
     * is a defect, and carries a reference the log line shares. Nothing the
     * failure itself said reaches the caller.
     */
    public static RefusalPayload unexpected(String reference) {
        RefusalCode code = RefusalCode.UNEXPECTED_FAILURE;
        return new RefusalPayload(code.name(), message(code, null), Map.of(REFERENCE, reference));
    }

    private static String message(RefusalCode code, String attempted) {
        VerbName verb = attempted == null ? null : VerbName.declared(attempted);
        return RefusalCatalogue.message(code,
            new RefusalCatalogue.Context(verb, RefusalCatalogue.boundOf(code)));
    }

    /**
     * The detail under {@code data}, made of declared paths only. Absent rather
     * than empty when there is none: an empty map is a member, and a caller
     * reading the key set would see a difference where the node says there is
     * none.
     */
    private static Object dataOf(RefusalCode code, List<String> offenders, List<String> accepts) {
        Map<String, Object> data = new LinkedHashMap<>();
        put(data, OFFENDERS, declared(code, offenders));
        put(data, ACCEPTS, declared(code, accepts));
        return data.isEmpty() ? null : data;
    }

    private static List<String> declared(RefusalCode code, List<String> spellings) {
        List<String> names = SurfaceDeclaration.declaredNames(spellings);
        if (names.size() != spellings.size()) {
            // A throw site named something the declaration lacks. The spelling
            // stays out of the answer and out of this line (DEC-0051).
            LOG.warnf("refusal %s named %d undeclared path(s); they were left out", code,
                spellings.size() - names.size());
        }
        return names;
    }

    private static void put(Map<String, Object> data, String member, List<String> paths) {
        if (!paths.isEmpty()) {
            data.put(member, paths);
        }
    }
}
