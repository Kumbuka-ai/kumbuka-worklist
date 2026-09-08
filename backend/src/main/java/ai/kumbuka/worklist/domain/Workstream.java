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
import java.util.UUID;

/**
 * A workstream — the fourth view. An item lands here obligatorily; a
 * milestone lands here obligatorily; an iteration touches this axis not at
 * all.
 *
 * <h2>Governance mirrors the selector, and the reasons transfer</h2>
 *
 * A workstream is DECLARED, never minted by first use — a service that
 * created one on first use would let a typo open a second axis and leave
 * the two indistinguishable. It is WITHDRAWABLE and never deleted, so an
 * item or a milestone that once pointed at it keeps resolving. It is
 * RENAMABLE while nothing points at it and FIXED once something does — the
 * predicate is a two-table read of {@code item} and {@code milestone}, and
 * a trigger doing it would push the enforcement across a table boundary
 * the trigger does not own; the service enforces it in one place. It
 * carries a MANDATORY description, and a workstream without one is a
 * violation and not a report.
 *
 * <h2>The default is a stamped identity, not a magic token</h2>
 *
 * Every scope opens with a default workstream. It admits no rename and no
 * withdrawal — the service refuses both by the {@link #isDefault} flag —
 * and a partial unique index in V8 fixes that a scope has at most one.
 *
 * <h2>Numbering is the same mechanism as the other three views</h2>
 *
 * The address is {@code worklist://<scope>/workstream/<n>}. The counter
 * lives in the workstream selector's own {@code number_space} row and is
 * advanced through {@link SelectorRegistry#allocate}. The number belongs
 * to the row and is never reused.
 */
@Entity
@Table(name = "workstream", schema = "worklist")
public class Workstream extends AggregateRoot {

    /** A workstream that may still be used. */
    public static final String DECLARED = "declared";

    /** Withdrawn: resolvable for what already exists, closed to anything new. */
    public static final String WITHDRAWN = "withdrawn";

    /** Every value {@link #status} admits. */
    public static final List<String> STATUSES = List.of(DECLARED, WITHDRAWN);

    /**
     * The token of the auto-created default of every scope.
     *
     * <p>A default token is a matter of convention rather than of identity:
     * the row IS the default because its {@link #isDefault} flag is set,
     * not because its token happens to spell {@code default}. The flag is
     * what refusals key on — the token would be an unreliable identity
     * carrier because it is renamable up to the first pointer.
     */
    public static final String DEFAULT_TOKEN = "default";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    public UUID id;

    /**
     * Allocated from the workstream selector's number space and never reused.
     *
     * <p>The default carries {@code 1} by convention of V8; further
     * workstreams draw from the counter through
     * {@link SelectorRegistry#allocate}.
     */
    @Column(name = "number", nullable = false)
    public long number;

    /** The scope-local name; renamable while nothing points at this row. */
    @Column(name = "token", nullable = false)
    public String token;

    /**
     * What this workstream contains and why it is cut where it is.
     *
     * <p>Mandatory: a workstream without a description is a violation, not
     * a report. The service refuses an empty or whitespace-only value.
     */
    @Column(name = "description", nullable = false)
    public String description;

    /** {@link #DECLARED} or {@link #WITHDRAWN}. */
    @Column(name = "status", nullable = false)
    public String status = DECLARED;

    /**
     * Whether this row is the scope's auto-created default.
     *
     * <p>Refusals for rename and withdrawal key on this flag rather than on
     * the token, because the token is renamable up to the first pointer
     * and would therefore be an unreliable identity carrier.
     */
    @Column(name = "is_default", nullable = false)
    public boolean isDefault;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false)
    public Instant updatedAt;

    @Override
    protected String subject() {
        return "workstream";
    }

    @Override
    protected void touch(Instant now) {
        updatedAt = now;
    }
}
