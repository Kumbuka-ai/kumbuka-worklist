package ai.kumbuka.worklist.domain;

import java.util.List;

/**
 * A typed refusal, naming what was refused and why.
 *
 * <p>Every refusal in this service carries a {@link Reason} rather than only a
 * message. A caller that has to match on prose is a caller that breaks when
 * somebody improves the wording, and an adapter that cannot tell "you may not
 * do that yet" from "that does not exist" cannot map either onto its own
 * protocol without guessing.
 *
 * <p>The message is for a human and names the specifics; the reason is for a
 * caller and is stable.
 *
 * <p>The set below is the substrate's, and it is short because the substrate
 * refuses few things. The verbs of the worklist — the status machine over the
 * declared vocabulary, the relations, the planning layer, the claim lease —
 * arrive with the domain half and bring their own reasons. Enumerating them
 * here in advance would be guessing at refusals nothing can yet raise.
 */
public class WorklistException extends RuntimeException {

    public enum Reason {
        /** The scope could not be resolved against the platform's read contract. */
        SCOPE_UNRESOLVED,
        /** The session settings the read contract needs were not bound. */
        SESSION_NOT_BOUND
    }

    private final transient Reason reason;
    private final transient List<String> offenders;

    public WorklistException(Reason reason, String message) {
        this(reason, message, List.of());
    }

    public WorklistException(Reason reason, String message, List<String> offenders) {
        super(message);
        this.reason = reason;
        this.offenders = List.copyOf(offenders);
    }

    public Reason reason() {
        return reason;
    }

    /**
     * The objects that caused the refusal, where naming them is the point.
     *
     * <p>A refusal that only states the rule sends the reader back to the
     * store to work out what it was talking about. The whole value of
     * checking where the check runs is that the answer is right there.
     */
    public List<String> offenders() {
        return offenders;
    }
}
