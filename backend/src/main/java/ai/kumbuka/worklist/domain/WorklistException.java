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
         * A value outside what its field accepts — an attribute key that is
         * not a token, a reference entry with no target, a relation entry with
         * no type.
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

        /**
         * The token is well formed and names none of the three views.
         *
         * <p>Kept apart from {@link #SELECTOR_UNDECLARED}, which says that a
         * view exists and this scope has not declared it yet — a state a
         * caller fixes by declaring. This one says the token could never name
         * anything here, whatever the scope does, and no declaration fixes
         * it. Collapsing the two would send a caller off to declare a fourth
         * view that cannot exist.
         */
        VIEW_UNKNOWN,

        /**
         * A declared value of that identity does not exist in this scope: a
         * status, an attribute, one of its options, or a relation type.
         *
         * <p>One reason for all four, because the four are one kind of object
         * — the schema carries them in separate tables only because their
         * platform properties differ, and a caller told "that value is not
         * declared here" needs no fifth word for which table it was in. The
         * message names the table's subject and the identity; the reason names
         * the class of refusal.
         */
        VALUE_UNDECLARED,

        /**
         * The intake gate was reached and this scheme has no carrier for the
         * business identifier it is supposed to allocate.
         *
         * <p>It replaces {@code ALREADY_ACCEPTED}, which said that an item
         * carried its identifier already and that one is allocated once. That
         * sentence stopped being the truth when the selector became the view:
         * the address is allocated with the object, and the family that made
         * {@code FEAT-51} an identifier is no longer an address space. Keeping
         * the old reason would have answered a caller with a statement about
         * an allocation that no longer happens.
         *
         * <p>Replacing a refusal reason is a change to a published name, and
         * it is admissible here for a reason that is measured rather than
         * assumed: nothing consumes this surface — the estate still runs the
         * predecessor and this store holds no row. It would not be admissible
         * later.
         */
        IDENTIFIER_UNDECIDED,

        /**
         * A high-water mark may be carried forward and never back. Moving it
         * back would hand out numbers that are already in use, which is the
         * one thing the mark exists to prevent.
         */
        MARK_REGRESSION,

        // --- the planning layer ---------------------------------------
        //
        // These arrived with the planning verbs, and not before. The class
        // comment above says why: enumerating a refusal nothing can raise is
        // guessing, and a reason with no thrower is a reason nobody has
        // checked the wording of.

        /** No milestone of that id in this scope. */
        MILESTONE_UNKNOWN,

        /** No iteration of that id in this scope. */
        ITERATION_UNKNOWN,

        /** The item is not a member of that iteration. */
        MEMBERSHIP_UNKNOWN,

        /**
         * The item is already a member of that iteration.
         *
         * <p>Planning it again is refused rather than treated as a move: a
         * second membership would have to displace the first, and displacing
         * silently is how the predecessor lost a position somebody was
         * holding.
         */
        MEMBERSHIP_PRESENT,

        /**
         * The iteration is closed, and a closed iteration takes no further
         * writes.
         *
         * <p>Closing is the one act on this axis that is not reversible
         * through a verb. Its memberships stay readable, which is what makes
         * a closed iteration a record rather than a gap.
         */
        ITERATION_CLOSED,

        /**
         * The iteration still holds memberships that are neither done nor
         * dropped, and the refusal names them.
         *
         * <p>Closing over live memberships would decide for the operator what
         * happened to each one. Naming them is the point: a refusal that only
         * states the rule sends the reader back to the store to find out what
         * it was talking about.
         */
        ITERATION_INCOMPLETE,

        /**
         * There is no iteration for {@code advance} to promote.
         *
         * <p>Kept apart from "nothing to do because everything is terminal",
         * which is a call to close rather than a call to plan. Collapsing the
         * two makes a caller fall back silently to the wrong remedy.
         */
        ITERATION_ABSENT,

        /**
         * The item may not enter an iteration: it is not actionable, or it
         * carries no milestone, or its milestone is off the product path.
         *
         * <p>The refusal names the value it found, because all three cases
         * read the same from outside and the remedy for each is different.
         */
        ITEM_UNPLANNABLE,

        /**
         * The scope has no settings row, so its cardinality limits and its
         * allocation counters do not exist yet.
         *
         * <p>Not created on first use: V4 leaves the four cardinality columns
         * without defaults so that no layer can quietly pick them.
         */
        SETTING_ABSENT,

        /** The scope already has its settings row, and there is one per scope. */
        SETTING_PRESENT,

        /**
         * A cardinality limit the scope set for itself would be exceeded.
         *
         * <p>The refusal carries the limit, so that a caller can tell a
         * setting they may raise from a platform ceiling they may not.
         */
        CARDINALITY_EXCEEDED
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
