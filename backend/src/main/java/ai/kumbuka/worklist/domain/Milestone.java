package ai.kumbuka.worklist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One milestone: which goal an item serves.
 *
 * <p>The goal axis and the time axis hang INDEPENDENTLY on the item and there
 * is no edge between them. A milestone answers "which goal does this serve";
 * an {@link Iteration} answers "when is it being worked". They are not a
 * hierarchy, and neither contains the other.
 *
 * <h2>A milestone is an object, not a label</h2>
 *
 * Identity, number, title, status, a {@link #vision} of one line and a
 * {@link #mission} of some length. The vision is the north star in a
 * sentence; the mission is what the milestone actually contains and why it is
 * cut where it is cut. The predecessor carries the axis as a free string on
 * the item, which is why a milestone reference there is checkable against
 * nothing.
 *
 * <h2>The three markers are rows, and {@link #kind} is what tells them apart</h2>
 *
 * Not yet assessed, off the product path, and on the product path but covered
 * by no vision. The predecessor keeps them OUT of its milestone table and
 * therefore needs an exemption in its reference check, so that the third
 * marker carries a violation nobody can ever fix.
 *
 * <p>As rows they need no exemption: {@code item.milestone_id} always
 * resolves and the existence check has no special case. The cost is that a
 * marker must not carry a goal, which is one check constraint in V4, and that
 * nothing deletes it — which is the absent DELETE privilege rather than a
 * rule.
 *
 * <h2>At most one active, and setting one demotes the other</h2>
 *
 * A partial unique index holds it. {@code MilestoneService.update} demotes the
 * current active in the same write rather than refusing, because a refusal
 * would make the operator perform two writes to express one intention.
 */
@Entity
@Table(name = "milestone", schema = "worklist")
public class Milestone extends AggregateRoot {

    /** A real goal, as opposed to a position on the axis. */
    public static final String GOAL = "milestone";
    /** Marker: nothing has judged this item against the product path yet. */
    public static final String NOT_ASSESSED = "not_assessed";
    /** Marker: deliberately off the product path. */
    public static final String OFF_PATH = "off_path";
    /** Marker: on the product path, and covered by no vision. */
    public static final String NO_VISION = "no_vision";

    /** Every value {@link #kind} admits, in the order V4's check constraint lists them. */
    public static final List<String> KINDS = List.of(GOAL, NOT_ASSESSED, OFF_PATH, NO_VISION);

    /**
     * The kinds that are ON the product path.
     *
     * <p>{@link #OFF_PATH} is the one that is not, by its own name.
     * {@link #NOT_ASSESSED} is not on it either, and that is a reading rather
     * than a restatement: "not yet assessed" is the absence of the judgement,
     * and treating an unmade judgement as a positive one is how an item nobody
     * has looked at reaches an iteration.
     */
    public static final Set<String> ON_THE_PRODUCT_PATH = Set.of(GOAL, NO_VISION);

    /** Declared, and not yet the one being worked towards. */
    public static final String PLANNED = "planned";
    /** The one being worked towards. At most one per scope. */
    public static final String ACTIVE = "active";
    /** Reached or abandoned; still an object, so every reference still resolves. */
    public static final String CLOSED = "closed";

    /** Every value {@link #status} admits. */
    public static final List<String> STATUSES = List.of(PLANNED, ACTIVE, CLOSED);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    public UUID id;

    /**
     * Allocated from the scope's milestone mark and never reused.
     *
     * <p>A closed milestone stays in the table, so the allocator counts past
     * it; the mark records what was HANDED OUT, which is not the same set as
     * what exists.
     */
    @Column(name = "number", nullable = false)
    public long number;

    /** One line, the milestone's handle in every listing. */
    @Column(name = "title", nullable = false)
    public String title;

    /** {@link #GOAL} or one of the three markers. */
    @Column(name = "kind", nullable = false)
    public String kind = GOAL;

    /** {@link #PLANNED}, {@link #ACTIVE} or {@link #CLOSED}. */
    @Column(name = "status", nullable = false)
    public String status = PLANNED;

    /** The north star in a sentence. Null on a marker, and constrained to be. */
    @Column(name = "vision")
    public String vision;

    /** What the milestone contains and why it is cut where it is. Null on a marker. */
    @Column(name = "mission")
    public String mission;

    /** The order of the axis, and never the alphabetical one. */
    @Column(name = "rank", nullable = false)
    public int rank;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false)
    public Instant updatedAt;

    /** Whether this milestone lets an item be planned at all. */
    public boolean onTheProductPath() {
        return ON_THE_PRODUCT_PATH.contains(kind);
    }

    @Override
    protected String subject() {
        return "milestone";
    }

    @Override
    protected void touch(Instant now) {
        updatedAt = now;
    }
}
