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
 * <h2>The core is small, and everything else is a declaration</h2>
 *
 * The fields below are the item's fixed core: what the SERVICE reasons about.
 * Cluster, type, priority, size and the component tags were fields of their
 * own in the predecessor and in the previous shape of this domain; they are
 * declared attributes now and travel under the single name
 * {@link #ATTRIBUTES}. A sixth of them is a declaration rather than an entry
 * in this enum, which is the whole point of the change.
 *
 * <h2>Why a declared value travels as its identity</h2>
 *
 * {@link #STATUS} carries the id of a declared status and not its display
 * name, and the entries of {@link #RELATIONS} carry the id of a declared
 * relation type. That follows from the rule that a declared value has an
 * identity separate from its name: the name is what a reader sees and may be
 * changed at will, so a caller writing one would be writing something that is
 * allowed to move under it.
 *
 * <p>{@link #SELECTOR} is the one declared thing that travels as its token,
 * and it is not an inconsistency: a selector token IS half of an address,
 * immutable by construction and already written into commit messages and
 * documents everywhere. There is nothing for it to move under.
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

    /** The tenancy unit the item belongs to. Fixed when the item is created. */
    SCOPE("scope", false),

    /**
     * The declared head of the address, as its token — {@code FEAT}, not a
     * uuid. Set by acceptance into an address space, never by an update.
     */
    SELECTOR("selector", false),

    /** The number allocated within that address space. Set by acceptance. */
    NUMBER("number", false),

    // --- what a caller characterises -------------------------------------

    /** One line, the item's handle in every listing. */
    TITLE("title", true),

    /**
     * What the item is and why it matters — never how it will be done. The
     * design lives in the document a reference points at.
     */
    DESCRIPTION("description", true),

    /**
     * The identity of a status the scope declared.
     *
     * <p>Not a literal out of a fixed set. Which statuses exist, what they
     * are called and which of the four predicates each one carries are the
     * scope's declaration; this service's business is that the value IS
     * declared.
     */
    STATUS("status", true),

    /**
     * The declared attributes, as a map from a definition's stable key to its
     * value.
     *
     * <p>Keyed by the KEY and not by the identity, unlike the stored column:
     * the key is immutable and unique in its scope, so it is what a caller can
     * hold on to, while the storage form keys by identity so that a rename of
     * the key would not be a data migration either.
     */
    ATTRIBUTES("attributes", true),

    /**
     * The external pointers, as an ordered list of entries carrying an
     * optional {@code label} and a {@code target}.
     *
     * <p>A list and not one field. The single free-text column this replaces
     * came to hold, in the estate being migrated, an item's rationale, a
     * withdrawn decision, a build source path and a warning that the path was
     * wrong — all at once.
     */
    REFERENCES("references", true),

    /**
     * The typed edges out of this item, as a list of entries carrying a
     * {@code type} — the identity of a declared relation type — and an
     * {@code item}, the identity of the other end.
     *
     * <p>Set as a whole. An edge that leaves the set is withdrawn and never
     * deleted, and one that re-enters is asserted again on the row that was
     * already there.
     */
    RELATIONS("relations", true),

    // --- the planning axis, read here and written elsewhere ---------------

    /**
     * The milestone this item serves, or null — including the three marker
     * rows, which are milestones in the table and positions on the axis.
     *
     * <p>Read-only through the item verbs. Setting it is a planning act, and
     * the planning layer's verbs are a separate piece of work; the field is
     * here because an answer that omitted it would be an item read that does
     * not say what the item is for.
     */
    MILESTONE("milestone", false),

    // --- what the service derives ----------------------------------------

    /** Set once, at the insert. */
    CREATED_AT("created_at", false),

    /**
     * Moved by an effective change and by nothing else. A write that changes
     * no value leaves it where it is — see {@link ItemService#update}.
     */
    CHANGED_AT("changed_at", false),

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
                    + ", of which " + settableNames() + " may be set. A scope's own "
                    + "attributes are not fields: they travel inside `attributes`, "
                    + "under the key they were declared with. Nothing was written: "
                    + "an argument this service does not recognise is refused rather "
                    + "than dropped, because a dropped argument makes a write that "
                    + "changed nothing look like one that succeeded",
                unknown);
        }
        return resolved;
    }
}
