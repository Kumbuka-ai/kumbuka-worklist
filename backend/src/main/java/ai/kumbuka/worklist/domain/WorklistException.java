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
 * <p>The first two below are the substrate's. The rest arrived with the item
 * domain, and each one names a refusal something in this service actually
 * raises — the planning layer's and the claim lease's are still absent for
 * the same reason as before, that enumerating a refusal nothing can raise is
 * guessing.
 */
public class WorklistException extends RuntimeException {

    public enum Reason {
        /** The scope could not be resolved against the platform's read contract. */
        SCOPE_UNRESOLVED,
        /** The session settings the read contract needs were not bound. */
        SESSION_NOT_BOUND,

        /**
         * An argument named a field that does not exist. The offenders are the
         * argument names, because a refusal that does not say WHICH name it
         * did not recognise leaves the caller to diff two vocabularies by eye.
         */
        UNKNOWN_FIELD,

        /**
         * A known field that a caller may not set carried a value other than
         * the one it already has. Echoing a read answer back is fine; changing
         * an id or a timestamp through it is not.
         */
        FIELD_NOT_SETTABLE,

        /**
         * A value outside what its field accepts — a status that is not one of
         * the six, a component tag that is not a lower-case token.
         */
        INVALID_VALUE,

        /**
         * The conflict token was stale or absent. The refusal carries the
         * CURRENT token, so a caller can re-read, re-apply and retry without
         * a second round trip to find out what it should have sent.
         */
        CONFLICT,

        /** No item of that id in this scope. */
        ITEM_UNKNOWN,

        /**
         * The selector was never declared. It is NOT created here: a service
         * that declares a selector on first use answers a misspelt address by
         * inventing a second address space.
         */
        SELECTOR_UNDECLARED,

        /** The selector exists and has been withdrawn, so nothing new is accepted under it. */
        SELECTOR_WITHDRAWN,

        /** A term of that token on that axis was never declared in this scope. */
        TERM_UNDECLARED,

        /**
         * The item was already accepted, so it carries its identifier, and
         * that is allocated once.
         */
        ALREADY_ACCEPTED,

        /**
         * A high-water mark may be carried forward and never back. Moving it
         * back would hand out numbers that are already in use, which is the
         * one thing the mark exists to prevent.
         */
        MARK_REGRESSION
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
