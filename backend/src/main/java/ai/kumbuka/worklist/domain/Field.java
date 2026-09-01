package ai.kumbuka.worklist.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every field of an item, under the one name it has.
 *
 * <h2>What this exists to prevent</h2>
 *
 * The predecessor service names a field twice: its write verbs take
 * lower-case parameters ({@code title}, {@code status}) while its read
 * answers carry capitalised column names ({@code Titel}, {@code Status}).
 * Read-modify-write is therefore a trap by construction — a caller that
 * fetches a row, changes one value and sends the object back has sent a map
 * of names the write path does not recognise.
 *
 * <p>That is not the interesting half. The interesting half is what happened
 * next: the write path DISCARDED the unrecognised names silently and wrote
 * the row anyway. So the caller got a success, every field kept its old
 * value, and the row came back carrying a fresh modification date and a
 * rotated conflict token. A false change trail and a false conflict signal,
 * from a call that changed nothing and was told it had.
 *
 * <p>Both halves are answered here rather than in a convention. There is one
 * name per field, this enum is where it is defined, and both directions read
 * it. An argument that is not in this enum is a {@link
 * WorklistException.Reason#UNKNOWN_FIELD} that NAMES the argument.
 *
 * <h2>Why read-only fields are not simply rejected</h2>
 *
 * A read answer carries {@link #ID}, {@link #CREATED_AT} and the rest, and a
 * caller that sends the whole answer back is doing the obvious thing. If
 * every read-only field in that map were a refusal, the canonical naming
 * would have bought nothing: the round trip would still be a trap, just a
 * loud one.
 *
 * <p>So a read-only field carrying the value it already has is accepted and
 * changes nothing, and a read-only field carrying a DIFFERENT value is
 * refused as {@link WorklistException.Reason#FIELD_NOT_SETTABLE}. The
 * distinction is exactly the one that matters: echoing state back is fine,
 * trying to set it is not, and neither is ever silent.
 */
public enum Field {

    // --- the identity and the address ------------------------------------

    /** The row's identity, from the insert onward. Never set by a caller. */
    ID("id", false),

    /** The tenancy unit the item belongs to. Fixed when the item is stated. */
    SCOPE("scope", false),

    /**
     * The declared head of the address, as its token — {@code FEAT}, not a
     * uuid. Set by admission into an address space, never by an amendment.
     */
    SELECTOR("selector", false),

    /** The number allocated within that address space. Set by admission. */
    NUMBER("number", false),

    // --- what a caller characterises -------------------------------------

    /** One line, human readable. */
    TITLE("title", true),

    /**
     * One of six values. {@code planned} is deliberately not among them: it
     * is derivable from iteration membership, the membership table is the
     * planning layer's, and a value that nothing maintains is worse than an
     * absent one.
     */
    STATUS("status", true),

    /** A declared term on the {@code cluster} axis, as its token. */
    CLUSTER("cluster", true),
    /** A declared term on the {@code type} axis, as its token. */
    TYPE("type", true),
    /** A declared term on the {@code priority} axis, as its token. */
    PRIORITY("priority", true),
    /** A declared term on the {@code size} axis, as its token. */
    SIZE("size", true),

    /**
     * The component tags — {@code e2e}, {@code ee-srv}, {@code none}. A list,
     * because the predecessor's single space-separated cell was a fact about
     * a Markdown cell and not about the tags.
     */
    COMPONENT("component", true),

    /** Free text. Null when nothing is on file; there is no filler token. */
    REFERENCE("reference", true),

    /**
     * The items this one depends on, as their ids. A list, and the edge is a
     * relation underneath, so a reference to an item that does not exist is
     * refused by the database rather than found later by an inventory walk.
     */
    DEPENDS_ON("depends_on", true),

    // --- what the service derives ----------------------------------------

    /** Set once, at the insert. */
    CREATED_AT("created_at", false),

    /**
     * Moved by an effective change and by nothing else. A write that changes
     * no value leaves it where it is — see {@link ItemStore#amend}.
     */
    UPDATED_AT("updated_at", false),

    /**
     * Opaque, from the moment of reading. A write carries the token it read
     * and is refused if the row has moved on since.
     *
     * <p>It is a field rather than a parameter beside the fields because that
     * is what makes the round trip work: the map a caller read already
     * carries it, so sending the map back is sending the token back.
     */
    CONFLICT_TOKEN("conflict_token", false);

    private final String canonicalName;
    private final boolean settable;

    Field(String canonicalName, boolean settable) {
        this.canonicalName = canonicalName;
        this.settable = settable;
    }

    /** The one name this field has, in both directions. */
    public String canonicalName() {
        return canonicalName;
    }

    /** Whether a caller may change it, as opposed to merely echo it back. */
    public boolean settable() {
        return settable;
    }

    /**
     * The field of that name, or empty.
     *
     * <p>Deliberately case-SENSITIVE. Accepting {@code Title} for
     * {@code title} would reintroduce the defect this enum exists against in
     * a friendlier costume: two spellings would work, callers would settle on
     * different ones, and the day one of them stopped being accepted would be
     * a mystery. One name means one name.
     */
    public static Optional<Field> byCanonicalName(String name) {
        return Arrays.stream(values())
            .filter(f -> f.canonicalName.equals(name))
            .findFirst();
    }

    /** Every settable name, for a refusal that has to say what WAS possible. */
    public static List<String> settableNames() {
        return Arrays.stream(values())
            .filter(Field::settable)
            .map(Field::canonicalName)
            .toList();
    }

    /**
     * Resolve a caller's map to a typed one, naming every argument it could
     * not resolve.
     *
     * <p>All of them, not the first: a caller that misspelt three fields
     * should learn that in one round trip rather than three. The message
     * lists what was possible, because a refusal that only states the rule
     * sends the reader looking for the vocabulary it just failed to match.
     */
    public static Map<Field, Object> resolve(Map<String, ?> arguments) {
        Map<Field, Object> resolved = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();

        for (Map.Entry<String, ?> entry : arguments.entrySet()) {
            Optional<Field> field = byCanonicalName(entry.getKey());
            if (field.isEmpty()) {
                unknown.add(entry.getKey());
            } else {
                resolved.put(field.get(), entry.getValue());
            }
        }

        if (!unknown.isEmpty()) {
            throw new WorklistException(
                WorklistException.Reason.UNKNOWN_FIELD,
                "no field is named " + unknown + ". An item's fields are "
                    + Arrays.stream(values()).map(Field::canonicalName).toList()
                    + ", of which " + settableNames() + " may be set. Nothing was written: "
                    + "an argument this service does not recognise is refused rather than "
                    + "dropped, because a dropped argument makes a write that changed "
                    + "nothing look like one that succeeded",
                unknown);
        }
        return resolved;
    }
}
