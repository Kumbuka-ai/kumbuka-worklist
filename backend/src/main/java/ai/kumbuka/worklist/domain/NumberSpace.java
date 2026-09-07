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
import java.util.UUID;

/**
 * The high-water mark of one address space.
 *
 * <h2>One counter per selector</h2>
 *
 * One row per selector. Each view — item, iteration, milestone — has its own
 * counter, and that row is the one position. There is no scope-wide counter
 * beside them: the address form carries the view, so {@code .../item/1},
 * {@code .../iteration/1} and {@code .../milestone/1} are three different
 * addresses already, and a bare number that had to disambiguate itself
 * across them would solve a problem the address form does not have.
 *
 * <p>The key is a surrogate rather than the selector so the ORM key stays
 * outside the tenancy axis, which the shared superclass owns and no caller
 * names.
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
public class NumberSpace extends TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    public UUID id;

    /**
     * The selector whose address space this is.
     *
     * <p>Immutable and never null: a counter belongs to one selector and
     * does not migrate between address spaces.
     */
    @Column(name = "selector_id", updatable = false, nullable = false)
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
