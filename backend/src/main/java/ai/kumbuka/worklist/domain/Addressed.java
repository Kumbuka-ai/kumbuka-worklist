package ai.kumbuka.worklist.domain;

/**
 * What a verb is addressed at.
 *
 * <p>The platform carries one verb vocabulary, and it is the ADDRESS that
 * says which object is meant rather than the verb: {@code update} on an
 * iteration and {@code update} on a membership are the same word aimed at two
 * different things. This enum is that address, as far as the field catalogue
 * is concerned.
 *
 * <p>It exists so that {@link Field} can be ONE catalogue rather than five.
 * Without it, {@code motto} would be a field an item could be created with —
 * accepted by the name check, refused only later by a switch with no case for
 * it, which is an internal error rather than a typed refusal. With it, a
 * field is unknown ON THE THING BEING ADDRESSED, and the refusal says so.
 *
 * <p>{@link #MEMBERSHIP} is on this list and is not an aggregate root. It is
 * addressed at its own address and presents its iteration's conflict token —
 * addressing and token ownership are two different things, and this is where
 * they come apart. See {@link IterationMembership}.
 */
public enum Addressed {

    /** An entry in what a scope intends to do. */
    ITEM("item"),

    /** A position on the goal axis, including the three markers. */
    MILESTONE("milestone"),

    /** A position on the time axis. */
    ITERATION("iteration"),

    /** One item's membership of one iteration. */
    MEMBERSHIP("membership"),

    /** The scope's own settings, as one row. */
    SETTING("scope setting");

    private final String description;

    Addressed(String description) {
        this.description = description;
    }

    /** How a refusal names this thing. Prose, for a human reading the message. */
    public String description() {
        return description;
    }
}
