package ai.kumbuka.worklist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row per scope, and everything the scope decides.
 *
 * <p>A configuration object with a conflict token of its own rather than an
 * aggregate root with children: nothing hangs off it, and it is still a row
 * two callers can hold a view of at once.
 *
 * <h2>The four cardinality numbers are settings, not constants</h2>
 *
 * A hard limit on planned iterations and on memberships per iteration, and an
 * advisory warning below each. They are a scope's working style rather than a
 * platform property, which is why they are here and not in a Java constant —
 * and why V4 gives them NO defaults: a number written at the column would be
 * a platform constant wearing a setting's clothes.
 *
 * <p>The consequence is that a scope has to be opened before it can plan.
 * That is not an accident of this design; it is the same order in which a
 * selector has to be declared before an address can be allocated.
 *
 * <h2>The current iteration is a pointer and lives here</h2>
 *
 * Rather than a boolean on the iteration, which would allow two current ones
 * and would then need a partial unique index to forbid what a single nullable
 * pointer cannot express in the first place.
 *
 * <h2>The two high-water marks are here and they are not settings</h2>
 *
 * {@link #milestoneHighWaterMark} and {@link #iterationHighWaterMark} record
 * what has been HANDED OUT on each planning axis, which is not the same set
 * as what exists — a closed milestone stays in the table and a number burned
 * by a failed write stays burned.
 *
 * <p><strong>Advancing a mark does not rotate this row's token.</strong>
 * Creating an iteration is a write on the ITERATION aggregate; it advances
 * the mark as an allocator side effect, exactly as {@code accept} advances a
 * {@link NumberSpace} while rotating only the item's token. Rotating the
 * setting's token there would move a caller's token with no write of their
 * own in between — which is the defect measured in sprint 169, reproduced by
 * a service that had learnt from it.
 */
@Entity
@Table(name = "scope_setting", schema = "worklist")
public class ScopeSetting extends AggregateRoot {

    /** Each selector draws from its own counter. Two selectors may share a number. */
    public static final String PER_SELECTOR = "per_selector";
    /** The allocator draws from the scope-wide counter. A number is not repeated. */
    public static final String SCOPE_WIDE = "scope_wide";

    /** Every value {@link #allocationMode} admits. */
    public static final List<String> ALLOCATION_MODES = List.of(PER_SELECTOR, SCOPE_WIDE);

    /**
     * The row's identity, and deliberately not its key.
     *
     * <p>The table's primary key is {@code (tenant_id, scope_id)} and stays
     * that way — one row per scope is what the key says. This column exists
     * so that the entity has a key that does not draw in the tenancy axis,
     * which the shared superclass owns and no caller names. See V5 for the
     * measurement.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    public UUID id;

    /** {@link #PER_SELECTOR} or {@link #SCOPE_WIDE}. */
    @Column(name = "allocation_mode", nullable = false)
    public String allocationMode = PER_SELECTOR;

    /** The iteration being worked, or null. A pointer, unambiguous by construction. */
    @Column(name = "current_iteration_id")
    public UUID currentIterationId;

    /** The hard limit on iterations that are open at once. Refuses. */
    @Column(name = "max_planned_iterations", nullable = false)
    public int maxPlannedIterations;

    /** The advisory threshold below that limit. Warns and admits. */
    @Column(name = "warn_planned_iterations", nullable = false)
    public int warnPlannedIterations;

    /** The hard limit on memberships of one iteration. Refuses. */
    @Column(name = "max_memberships_per_iteration", nullable = false)
    public int maxMembershipsPerIteration;

    /** The advisory threshold below that limit. Warns and admits. */
    @Column(name = "warn_memberships_per_iteration", nullable = false)
    public int warnMembershipsPerIteration;

    /**
     * The declared default column set of a reader's first view.
     *
     * <p>Declared and not compiled into the console: otherwise the
     * predecessor's disease reappears one level up, with the vocabulary free
     * and the choice of what a reader sees code again. Nothing in this sprint
     * writes it; the column is mapped so that a read of the settings answers
     * with what the row holds.
     */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "default_columns", nullable = false, columnDefinition = "text[]")
    public String[] defaultColumns = new String[0];

    /** What the milestone allocator has handed out. Never carried backwards. */
    @Column(name = "milestone_high_water_mark", nullable = false)
    public long milestoneHighWaterMark;

    /** What the iteration allocator has handed out. Never carried backwards. */
    @Column(name = "iteration_high_water_mark", nullable = false)
    public long iterationHighWaterMark;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false)
    public Instant updatedAt;

    @Override
    protected String subject() {
        return "scope setting";
    }

    @Override
    protected void touch(Instant now) {
        updatedAt = now;
    }
}
