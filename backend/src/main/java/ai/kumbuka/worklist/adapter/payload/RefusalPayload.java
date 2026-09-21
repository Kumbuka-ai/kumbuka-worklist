package ai.kumbuka.worklist.adapter.payload;

import ai.kumbuka.worklist.domain.WorklistException;
import ai.kumbuka.worklist.surface.SurfaceException;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
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
 * <h2>The not-found class</h2>
 *
 * DEC-0042: "An address of an object that does not exist, an address in a
 * scope the caller cannot see, and an address whose scheme the router cannot
 * route are refused with the single code {@code NOT_FOUND}, in the same
 * envelope, with no {@code data} and no message that distinguishes them,
 * whether a service or the router produced the answer."
 *
 * <p>So the domain's own reasons for that class do not reach the wire. They
 * stay in {@link WorklistException.Reason} and stay useful — a log line, a
 * test, a reader of this service — and {@link #NOT_FOUND_REASONS} is where
 * they are collapsed, once, for both surfaces. The alternative, renaming them
 * all to one reason, would lose the distinction inside the service as well,
 * and the distinction is what makes the code legible.
 *
 * <p>{@link #NOT_FOUND_MESSAGE} is transcribed from the router's
 * {@code RouterException.NOT_FOUND_MESSAGE}, character for character, and not
 * imported: this service does not compile against the router and must not.
 * A message that differed would separate a service's not-found from the
 * router's on the wire, which is the form-level oracle ADR-0011 exists
 * against — and it would separate them in exactly the way nobody notices,
 * because both answers would still be a 404 saying nothing is there.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RefusalPayload(String reason, String message, Object data) {

    /** The one code of the not-found class. */
    public static final String NOT_FOUND = "NOT_FOUND";

    /**
     * The one message of the not-found class, whatever produced it.
     *
     * <p>It names a remedy, because the same remedy is true of every case in
     * the class: check the address, and check the scope.
     */
    public static final String NOT_FOUND_MESSAGE =
        "nothing is addressed here. Check the address, and that you are a member of "
            + "the scope it names.";

    /**
     * The domain reasons that are the not-found class on the wire.
     *
     * <p>Each one says either "no such object in this scope" or "no such scope
     * for this subject", and DEC-0042 gives the two the same answer so that
     * neither can be told from the other.
     *
     * <p>What is deliberately NOT here, and why, since the boundary is the
     * whole content of this set:
     *
     * <ul>
     *   <li>{@code ITERATION_ABSENT} — the scope exists, is visible, and is
     *       working no iteration; {@code advance} raises it when there is none
     *       left to promote. Both are a state of an object the caller can see,
     *       and a caller told "not found" would go looking for an iteration
     *       instead of planning one.</li>
     *   <li>{@code CLAIM_ABSENT} and {@code SETTING_ABSENT} — the same shape:
     *       the item is there, the lease or the settings row is not.</li>
     *   <li>{@code SELECTOR_UNDECLARED}, {@code VALUE_UNDECLARED},
     *       {@code VIEW_UNKNOWN} — vocabulary. A view the platform does not
     *       have is decidable without knowing any scope; a selector this scope
     *       has not declared is answered only behind scope visibility, so
     *       neither is an enumeration oracle and neither is an absent
     *       object.</li>
     * </ul>
     */
    public static final Set<WorklistException.Reason> NOT_FOUND_REASONS = Set.of(
        WorklistException.Reason.SCOPE_UNRESOLVED,
        WorklistException.Reason.ITEM_UNKNOWN,
        WorklistException.Reason.MILESTONE_UNKNOWN,
        WorklistException.Reason.ITERATION_UNKNOWN,
        WorklistException.Reason.MEMBERSHIP_UNKNOWN,
        WorklistException.Reason.WORKSTREAM_UNKNOWN,
        WorklistException.Reason.RELATION_UNKNOWN);

    /** The member {@code offenders} travels under, inside {@code data}. */
    public static final String OFFENDERS = "offenders";

    /**
     * A domain refusal, in the envelope.
     *
     * <p>The not-found class is collapsed here and nowhere else, which is what
     * makes "the same on both address forms" a property of the construction:
     * a collection call and an item call reach this one method.
     */
    public static RefusalPayload of(WorklistException e) {
        if (NOT_FOUND_REASONS.contains(e.reason())) {
            // No data and no message of its own. Both are what the class must
            // not carry — the offenders would name the very object whose
            // existence the answer is refusing to confirm.
            return new RefusalPayload(NOT_FOUND, NOT_FOUND_MESSAGE, null);
        }
        return new RefusalPayload(e.reason().name(), e.getMessage(), dataOf(e.offenders()));
    }

    /**
     * A surface refusal, in the same envelope.
     *
     * <p>Surface refusals carry no offenders today: they are statements about
     * the call rather than about objects, and the message names what is wrong
     * with it. If one ever does, it goes under {@code data} like every other.
     */
    public static RefusalPayload of(SurfaceException e) {
        return new RefusalPayload(e.reason().name(), e.getMessage(), null);
    }

    /**
     * The offenders, under {@code data} — never beside {@code reason}.
     *
     * <p>DEC-0042 puts "machine-readable detail, where a refusal has any,
     * under {@code data}", and a top-level member of the envelope's own is a
     * second shape a caller has to learn. Absent rather than empty when there
     * are none: an empty list is a member, and a caller reading the key set
     * would see a difference where the node says there is none.
     */
    private static Object dataOf(List<String> offenders) {
        return offenders.isEmpty() ? null : Map.of(OFFENDERS, offenders);
    }
}
