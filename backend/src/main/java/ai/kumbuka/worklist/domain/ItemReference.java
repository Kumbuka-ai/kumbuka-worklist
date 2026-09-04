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
 * One external pointer of an item, at its position in the list.
 *
 * <p><strong>References are an ordered list and not one field.</strong> The
 * single free-text column this replaces was the predecessor's shape and it is
 * the wrong one for a measured reason: in the estate being migrated that one
 * field came to hold, simultaneously, the item's rationale, a withdrawn
 * decision, a build source path and a warning that the path was wrong. A list
 * separates the pointers, and the item's description takes the prose that was
 * never a reference in the first place.
 *
 * <p><strong>An entry has no type and no resolution.</strong> A pointer to a
 * dispatch object, a pointer to a document, a URL and a citation are all the
 * same kind of thing to this service: a string it positions and does not
 * follow. Typing them would make the service responsible for something on the
 * other side of a boundary it deliberately does not cross.
 *
 * <h2>The entry has an identity, and the ordinal is not it</h2>
 *
 * {@link #id} is the key. {@link #ordinal} is the reader's order and carries
 * no address, so a reorder moves nothing a caller is holding.
 *
 * <p>The two are separate because <strong>a positional key and a withdrawal
 * status exclude each other.</strong> Withdraw two entries from a list of five
 * and the ordinals 3 and 4 carry tombstones; let the list grow back to five
 * and exactly those ordinals have to be reissued. Under a positional key the
 * write either collides with the tombstone or overwrites it — and an
 * overwritten tombstone is not preservation, it is a free slot that READS like
 * preservation, which is worse than a delete because it is invisible.
 *
 * <p>So density is a property of the LIVING entries: one living entry per
 * ordinal within an item, as a partial unique index, with withdrawn rows
 * exempt. A withdrawn row keeps whatever ordinal it had and is never consulted
 * for order.
 *
 * <p><strong>Withdrawal rather than deletion</strong>, as everywhere in this
 * schema: nothing holds a DELETE privilege, so a list that gets shorter marks
 * its tail withdrawn and leaves it standing.
 */
@Entity
@Table(name = "item_reference", schema = "worklist")
public class ItemReference extends TenantScoped {

    /** The entry is part of the list. */
    public static final String ASSERTED = "asserted";
    /** The entry was part of it once and is not any more. */
    public static final String WITHDRAWN = "withdrawn";

    /**
     * The entry's own identity, and the only thing that addresses it.
     *
     * <p>Not the ordinal, and not the pair of item and ordinal. See the class
     * comment: a positional key cannot coexist with a tombstone.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    public UUID id;

    /** The item this pointer hangs off. Immutable: an entry does not migrate. */
    @Column(name = "item_id", nullable = false, updatable = false)
    public UUID itemId;

    /**
     * The reader's order. Dense across the LIVING entries and meaningless on a
     * withdrawn one, which keeps whatever it had.
     */
    @Column(name = "ordinal", nullable = false)
    public int ordinal;

    /** What a reader sees instead of the raw target. Optional. */
    @Column(name = "label")
    public String label;

    /** The pointer itself. Positioned, never followed. */
    @Column(name = "target", nullable = false)
    public String target;

    @Column(name = "status", nullable = false)
    public String status = ASSERTED;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false)
    public Instant updatedAt;
}
