package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.StringUuidConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.TenantId;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * The high-water mark of one address space.
 *
 * <h2>Both counters exist at all times</h2>
 *
 * One row per selector, for the per-selector position of the allocation mode,
 * and one row per scope with a NULL {@link #selectorId}, for the scope-wide
 * position. The allocator reads the row the mode names and advances BOTH.
 *
 * <p><strong>That is what makes the mode a setting rather than a
 * migration.</strong> Switching it is a read against a counter that was
 * maintained all along; if only the active counter were kept, switching would
 * mean reconstructing the other one from rows that no longer say what was
 * handed out.
 *
 * <p>The key is therefore a surrogate and not the selector. A null selector is
 * not a missing value here — it is the row that belongs to no selector because
 * it belongs to all of them — and a unique constraint over a nullable column
 * would admit any number of such rows, because in SQL two nulls are not equal.
 * V4 uses two partial unique indexes instead.
 *
 * <h2>Why the mark is stored rather than computed</h2>
 *
 * {@code max(number) + 1} over the existing items is the obvious
 * implementation and it answers a different question. It reports the highest
 * number IN USE; the mark records the highest number HANDED OUT, and those
 * two sets differ by every number that was allocated and then lost —
 * allocated in a transaction that rolled back, or skipped by a mark that was
 * carried forward during an import.
 *
 * <p>Nothing in this schema deletes, so a row cannot vanish and take its
 * number back into circulation. That closes one way of losing the difference
 * and not the other, and the other is enough: a burnt number handed out twice
 * means two items answer to one address, and every reference to that address
 * silently becomes ambiguous. So the mark is a row, moved forward in the same
 * transaction that allocates.
 *
 * <h2>Settable, and only forward</h2>
 *
 * The predecessor's corpus will be imported one day, carrying numbers
 * allocated long before this table existed. An import that could not tell the
 * mark where the corpus had got to would start allocating from 1 into an
 * address space that is already occupied. So the mark can be carried
 * FORWARD — never back, because moving it back is precisely the act of
 * handing out numbers that are already in use.
 */
@Entity
@Table(name = "number_space", schema = "worklist")
public class NumberSpace {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    public UUID id;

    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "scope_id", nullable = false)
    public UUID scopeId;

    /**
     * The selector whose address space this is, or null for the scope-wide
     * counter.
     *
     * <p>Immutable: a counter does not migrate between address spaces, and a
     * per-selector row that became the scope-wide one would take a mark with
     * it that was never handed out at that level.
     */
    @Column(name = "selector_id", updatable = false)
    public UUID selectorId;

    /**
     * The highest number ever handed out in this space. Zero means none has
     * been, so the first allocation is 1.
     */
    @Column(name = "high_water_mark", nullable = false)
    public long highWaterMark;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false)
    public Instant updatedAt;
}
