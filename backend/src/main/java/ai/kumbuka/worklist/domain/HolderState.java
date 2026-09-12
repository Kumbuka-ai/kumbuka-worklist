package ai.kumbuka.worklist.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * What the caller needs to know about who holds a claim.
 *
 * <p>Three states, deliberately, and none of them names a person. The rule is
 * a standing invariant of the platform: a holder in a single lease is
 * harmless, a holder in a hundred query results is the beginning of a list of
 * who works on what, and per-member evaluation is structurally not to be
 * offered. Reduced to the answer a caller can act on: does anybody hold it,
 * and is that this caller.
 *
 * <p>The wire names are lower case and stable across renames of the constant.
 * The value on the wire is a contract, and the Java identifier is a name for
 * readers of the code.
 */
public enum HolderState {

    /** No live claim currently effective — free for takeup. */
    NOBODY("nobody"),
    /** The caller holds the claim. */
    SELF("self"),
    /** Somebody else holds it. Who exactly is deliberately withheld. */
    OTHER("other");

    private final String wireName;

    HolderState(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    /**
     * The state as it presents to the caller behind {@code actor}, given the
     * stored actor subject (null when no claim stands or the claim has lapsed).
     */
    public static HolderState of(String storedActorSubject, String callerSubject) {
        if (storedActorSubject == null) {
            return NOBODY;
        }
        return storedActorSubject.equals(callerSubject) ? SELF : OTHER;
    }
}
