package ai.kumbuka.worklist.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ai.kumbuka.worklist.domain.Addressed.ITEM;
import static ai.kumbuka.worklist.domain.Addressed.ITERATION;
import static ai.kumbuka.worklist.domain.Addressed.MEMBERSHIP;
import static ai.kumbuka.worklist.domain.Addressed.MILESTONE;
import static ai.kumbuka.worklist.domain.Addressed.SETTING;

/**
 * Every field of every object this service holds, under the one name it has.
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
 * <h2>One catalogue for five kinds of object, keyed by what is addressed</h2>
 *
 * The planning layer could have had a second enum of its own, and that would
 * have been a second convention: two places to decide what a canonical name
 * is, two resolvers, two refusal messages, and eventually two answers to
 * whether the comparison is case-sensitive. Instead a field declares WHICH
 * objects carry it, as {@link Addressed} values, and the resolver is told
 * what is being addressed.
 *
 * <p>That is what keeps {@code motto} from being a field an item can be
 * created with. Without the address, the name would resolve, the value would
 * reach a switch with no case for it, and the caller would get an internal
 * error where a typed refusal belongs.
 *
 * <p>A handful of names appear on more than one object and mean the same
 * thing on each — {@code id}, {@code scope}, {@code title}. One name that
 * does NOT is the membership's status: it travels as
 * {@link #MEMBERSHIP_STATUS} rather than {@code status}, because "done in
 * this iteration" and "finished" are different assertions and a shared
 * spelling is how they get confused in a call.
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

    // --- carried by everything -------------------------------------------

    /** The row's identity, from the insert onward. Never set by a caller. */
    ID("id", Set.of(ITEM, MILESTONE, ITERATION, MEMBERSHIP, SETTING), Set.of()),

    /** The tenancy unit the row belongs to. Fixed when the row is created. */
    SCOPE("scope", Set.of(ITEM, MILESTONE, ITERATION, MEMBERSHIP, SETTING), Set.of()),

    /** Set once, at the insert. */
    CREATED_AT("created_at",
        Set.of(ITEM, MILESTONE, ITERATION, MEMBERSHIP, SETTING), Set.of()),

    /**
     * Opaque, from the moment of reading. A write carries the token it read
     * and is refused if the object has moved on since.
     *
     * <p>It is a field rather than a parameter beside the fields because that
     * is what makes the round trip work: the map a caller read already
     * carries it, so sending the map back is sending the token back.
     *
     * <p>On a {@link Addressed#MEMBERSHIP} this is the ITERATION'S token, not
     * one of the membership's own. The membership is addressed at its own
     * address and belongs to the iteration's aggregate; a token of its own
     * would mean a reorder of twelve memberships presented twelve tokens.
     */
    CONFLICT_TOKEN("conflict_token",
        Set.of(ITEM, MILESTONE, ITERATION, MEMBERSHIP, SETTING), Set.of()),

    /**
     * Moved by an effective change and by nothing else, on everything but the
     * item.
     *
     * <p>The item carries {@link #CHANGED_AT} instead, as the target schema
     * names it. The inconsistency is known and is not levelled here: a
     * uniform name would mean rewriting a column V4 created, and what it
     * would buy is tidiness.
     */
    UPDATED_AT("updated_at",
        Set.of(MILESTONE, ITERATION, MEMBERSHIP, SETTING), Set.of()),

    // --- the item's address ----------------------------------------------

    /**
     * The declared head of the address, as its token — {@code FEAT}, not a
     * uuid. Set by acceptance into an address space, never by an update.
     */
    SELECTOR("selector", Set.of(ITEM), Set.of()),

    /**
     * The number allocated within an address space.
     *
     * <p>On an item it is allocated by acceptance; on a milestone and an
     * iteration it is allocated by creation, from the scope's own high-water
     * mark. Never given by a caller on any of the three: a number a caller
     * could choose is a number that can be handed out twice.
     */
    NUMBER("number", Set.of(ITEM, MILESTONE, ITERATION), Set.of()),

    // --- what a caller characterises -------------------------------------

    /** One line, the object's handle in every listing. */
    TITLE("title", Set.of(ITEM, MILESTONE), Set.of(ITEM, MILESTONE)),

    /**
     * On an item: what it is and why it matters — never how it will be done.
     * The design lives in the document a reference points at.
     *
     * <p>On an iteration: what it contains and what it does not. Mandatory
     * there, and not decoration — it is the only machine-readable criterion
     * by which an agent can refuse an item as out of scope.
     */
    DESCRIPTION("description", Set.of(ITEM, ITERATION), Set.of(ITEM, ITERATION)),

    /**
     * On an item, the identity of a status the SCOPE declared. On a
     * milestone, one of three values the platform fixes.
     *
     * <p>The asymmetry is the concept's rather than an oversight. An item's
     * status is a scope's way of describing its own work and the four
     * predicates hang off it; a milestone is planned, active or closed, and
     * those three are the platform's planning mechanism.
     */
    STATUS("status", Set.of(ITEM, MILESTONE), Set.of(ITEM, MILESTONE)),

    /**
     * The declared attributes, as a map from a definition's stable key to its
     * value.
     *
     * <p>Keyed by the KEY and not by the identity, unlike the stored column:
     * the key is immutable and unique in its scope, so it is what a caller can
     * hold on to, while the storage form keys by identity so that a rename of
     * the key would not be a data migration either.
     */
    ATTRIBUTES("attributes", Set.of(ITEM), Set.of(ITEM)),

    /**
     * The external pointers, as an ordered list of entries carrying an
     * optional {@code label} and a {@code target}.
     *
     * <p>A list and not one field. The single free-text column this replaces
     * came to hold, in the estate being migrated, an item's rationale, a
     * withdrawn decision, a build source path and a warning that the path was
     * wrong — all at once.
     */
    REFERENCES("references", Set.of(ITEM), Set.of(ITEM)),

    /**
     * The typed edges out of this item, as a list of entries carrying a
     * {@code type} — the identity of a declared relation type — and an
     * {@code item}, the identity of the other end.
     *
     * <p>Set as a whole. An edge that leaves the set is withdrawn and never
     * deleted, and one that re-enters is asserted again on the row that was
     * already there.
     */
    RELATIONS("relations", Set.of(ITEM), Set.of(ITEM)),

    // --- the goal axis ----------------------------------------------------

    /**
     * The milestone an item serves, or null — including the three marker
     * rows, which are milestones in the table and positions on the axis.
     *
     * <p>Read-only through every verb this service carries, and that is a
     * FINDING rather than a design: no verb assigns it. See
     * {@code MilestoneService} for what is missing and why it was not
     * invented here.
     */
    MILESTONE_ID("milestone", Set.of(ITEM), Set.of()),

    /**
     * Whether a milestone is a goal or one of the three positions on the axis
     * that never carry one.
     *
     * <p>Settable, and constrained: a marker carries neither a vision nor a
     * mission, which is checked here as a typed refusal before the database
     * checks it as a constraint violation.
     */
    KIND("kind", Set.of(MILESTONE), Set.of(MILESTONE)),

    /** The north star in a sentence. Refused on a marker. */
    VISION("vision", Set.of(MILESTONE), Set.of(MILESTONE)),

    /** What the milestone contains and why it is cut where it is. Refused on a marker. */
    MISSION("mission", Set.of(MILESTONE), Set.of(MILESTONE)),

    /** The order of an axis, and never the alphabetical one. */
    RANK("rank", Set.of(MILESTONE, ITERATION), Set.of(MILESTONE, ITERATION)),

    // --- the time axis ----------------------------------------------------

    /** What an iteration is about, in a phrase. Mandatory. */
    MOTTO("motto", Set.of(ITERATION), Set.of(ITERATION)),

    /**
     * When an iteration was closed, or null while it is open.
     *
     * <p>Read-only, and set by {@code close} alone. A fact rather than a
     * status: what "complete" means is a question about memberships, and
     * answering it from a timestamp would be the stored copy the concept
     * refuses.
     */
    CLOSED_AT("closed_at", Set.of(ITERATION), Set.of()),

    /**
     * The membership sequence of an iteration, as the item identities in the
     * order they are to be worked.
     *
     * <p>Addressed at the ITERATION, which is what the verb catalogue's
     * mapping table says and what the aggregate rule requires: a reorder
     * writes the iteration and presents its one token, however many rows move.
     *
     * <p>Position is derived from this list and is never given: a caller that
     * could write a position could write two rows to the same one.
     */
    ORDER("order", Set.of(ITERATION), Set.of(ITERATION)),

    // --- the membership ---------------------------------------------------

    /** The iteration half of a membership's identity. */
    ITERATION_ID("iteration", Set.of(MEMBERSHIP), Set.of()),

    /** The item half of a membership's identity, and the address a caller holds. */
    ITEM_ID("item", Set.of(MEMBERSHIP), Set.of()),

    /**
     * The membership's place in the iteration's sequence.
     *
     * <p>Read-only: it is derived from {@link #ORDER}, which is addressed at
     * the iteration. A membership is addressed by its item and never by its
     * position, so a reorder moves nothing a caller is holding.
     */
    POSITION("position", Set.of(MEMBERSHIP), Set.of()),

    /**
     * How far this iteration has got with this item, as one of the four
     * states {@link IterationMembership} declares.
     *
     * <p><strong>Deliberately not spelled {@code status}.</strong> Done on a
     * membership means completed IN THIS ITERATION; done on an item means
     * finished. The concept requires the two to carry different field names
     * so that they cannot be confused in a call, and this is that
     * requirement.
     */
    MEMBERSHIP_STATUS("membership_status", Set.of(MEMBERSHIP), Set.of(MEMBERSHIP)),

    // --- the scope's own settings -----------------------------------------

    /** Whether the item allocator draws per selector or scope-wide. */
    ALLOCATION_MODE("allocation_mode", Set.of(SETTING), Set.of(SETTING)),

    /**
     * The iteration being worked, or null.
     *
     * <p>Read-only through {@code update}: it is moved by {@code advance},
     * which is the verb that says what the act IS. A settable pointer would
     * let a caller make an iteration current without promoting it, and the
     * two would then be different states that read the same.
     */
    CURRENT_ITERATION("current_iteration", Set.of(SETTING), Set.of()),

    /** The hard limit on iterations open at once. Refuses. */
    MAX_PLANNED_ITERATIONS("max_planned_iterations", Set.of(SETTING), Set.of(SETTING)),

    /** The advisory threshold below that limit. Warns and admits. */
    WARN_PLANNED_ITERATIONS("warn_planned_iterations", Set.of(SETTING), Set.of(SETTING)),

    /** The hard limit on memberships of one iteration. Refuses. */
    MAX_MEMBERSHIPS_PER_ITERATION("max_memberships_per_iteration",
        Set.of(SETTING), Set.of(SETTING)),

    /** The advisory threshold below that limit. Warns and admits. */
    WARN_MEMBERSHIPS_PER_ITERATION("warn_memberships_per_iteration",
        Set.of(SETTING), Set.of(SETTING)),

    /** The declared default column set of a reader's first view. */
    DEFAULT_COLUMNS("default_columns", Set.of(SETTING), Set.of(SETTING)),

    // --- what the service derives ----------------------------------------

    /**
     * The item's modification timestamp. Moved by an effective change and by
     * nothing else — a write that changes no value leaves it where it is.
     */
    CHANGED_AT("changed_at", Set.of(ITEM), Set.of()),

    /**
     * What a write admitted and wants noticed: the cardinality thresholds a
     * scope set for itself, reached but not exceeded.
     *
     * <p>Always present and usually empty, rather than present only when
     * there is something to say. A key that appears and disappears is a key
     * every reader has to test for, and the first reader who forgets reads a
     * warning as an absence.
     *
     * <p>It is a statement about the WRITE and not about the row, which is
     * why sending it back means nothing and is never a refusal — the one
     * other field treated that way is {@link #CONFLICT_TOKEN}, for the
     * opposite reason.
     */
    WARNINGS("warnings", Set.of(MILESTONE, ITERATION, MEMBERSHIP, SETTING), Set.of());

    private final String canonicalName;
    private final Set<Addressed> carriedBy;
    private final Set<Addressed> settableOn;

    Field(String canonicalName, Set<Addressed> carriedBy, Set<Addressed> settableOn) {
        this.canonicalName = canonicalName;
        this.carriedBy = carriedBy;
        this.settableOn = settableOn;
    }

    /** The one name this field has, in both directions. */
    public String canonicalName() {
        return canonicalName;
    }

    /** Whether the addressed object has this field at all. */
    public boolean carriedBy(Addressed addressed) {
        return carriedBy.contains(addressed);
    }

    /** Whether a caller may change it there, as opposed to merely echo it back. */
    public boolean settableOn(Addressed addressed) {
        return settableOn.contains(addressed);
    }

    /** Every field the addressed object carries, in declaration order. */
    public static List<Field> of(Addressed addressed) {
        return Arrays.stream(values()).filter(f -> f.carriedBy(addressed)).toList();
    }

    /** Every settable name there, for a refusal that has to say what WAS possible. */
    public static List<String> settableNames(Addressed addressed) {
        return Arrays.stream(values())
            .filter(f -> f.settableOn(addressed))
            .map(Field::canonicalName)
            .toList();
    }

    /**
     * The field of that name on that object, or empty.
     *
     * <p>Deliberately case-SENSITIVE. Accepting {@code Title} for
     * {@code title} would reintroduce the defect this enum exists against in
     * a friendlier costume: two spellings would work, callers would settle on
     * different ones, and the day one of them stopped being accepted would be
     * a mystery. One name means one name.
     */
    public static Optional<Field> byCanonicalName(Addressed addressed, String name) {
        return Arrays.stream(values())
            .filter(f -> f.carriedBy(addressed) && f.canonicalName.equals(name))
            .findFirst();
    }

    /**
     * Resolve a caller's map to a typed one, naming every argument it could
     * not resolve.
     *
     * <p>All of them, not the first: a caller that misspelt three fields
     * should learn that in one round trip rather than three. The message
     * lists what was possible, because a refusal that only states the rule
     * sends the reader looking for the vocabulary it just failed to match.
     *
     * <p>A name that exists on ANOTHER object is unknown here, and the
     * message says which object was addressed. That is the case worth getting
     * right: {@code motto} is a real field of this service, and sending it to
     * an item has to read as "not on an item" rather than as a typo.
     */
    public static Map<Field, Object> resolve(Addressed addressed, Map<String, ?> arguments) {
        Map<Field, Object> resolved = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();

        for (Map.Entry<String, ?> entry : arguments.entrySet()) {
            Optional<Field> field = byCanonicalName(addressed, entry.getKey());
            if (field.isEmpty()) {
                unknown.add(entry.getKey());
            } else {
                resolved.put(field.get(), entry.getValue());
            }
        }

        if (!unknown.isEmpty()) {
            throw new WorklistException(
                WorklistException.Reason.UNKNOWN_FIELD,
                "no field of a " + addressed.description() + " is named " + unknown
                    + ". Its fields are "
                    + of(addressed).stream().map(Field::canonicalName).toList()
                    + ", of which " + settableNames(addressed) + " may be set. A scope's "
                    + "own attributes are not fields: they travel inside `attributes`, "
                    + "under the key they were declared with. Nothing was written: "
                    + "an argument this service does not recognise is refused rather "
                    + "than dropped, because a dropped argument makes a write that "
                    + "changed nothing look like one that succeeded",
                unknown);
        }
        return resolved;
    }
}
