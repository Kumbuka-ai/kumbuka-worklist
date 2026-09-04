package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.StringUuidConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.TenantId;
import org.hibernate.generator.EventType;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
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
 * <p>The service validates the FORM of a {@link #target} and never resolves
 * it. Nothing here participates in any derivation: readiness does not read
 * it, no predicate depends on it, and no guard blocks on it.
 *
 * <p>{@link #ordinal} is the reader's order and is dense on write — the list
 * is rewritten as a whole rather than patched, so a position is never left
 * with a hole in it.
 *
 * <p><strong>Withdrawal rather than deletion</strong>, as everywhere in this
 * schema: nothing holds a DELETE privilege, so a list that gets shorter
 * rewrites the positions it still has and withdraws the ones above them. The
 * study names this status only for the relation edge; it is here because the
 * same study states, of the whole schema, that withdrawal is a status
 * everywhere — and without it a reference list could only ever grow.
 */
@Entity
@Table(name = "item_reference", schema = "worklist")
@IdClass(ItemReference.Key.class)
public class ItemReference {

    /** The entry is part of the list. */
    public static final String ASSERTED = "asserted";
    /** The entry was part of it once and is not any more. */
    public static final String WITHDRAWN = "withdrawn";

    @Id
    @Column(name = "item_id", nullable = false)
    public UUID itemId;

    @Id
    @Column(name = "ordinal", nullable = false)
    public int ordinal;

    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "scope_id", nullable = false)
    public UUID scopeId;

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

    /**
     * The composite key: the item and the position within it.
     *
     * <p>The tenancy axis is deliberately NOT part of it, as on the relation
     * edge. An item id is already unique across tenants, so the pair
     * identifies an entry on its own; the table's own primary key in V4
     * carries {@code tenant_id} as well, because that is the column
     * row-level security filters on and it must be on the row.
     */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        public UUID itemId;
        public int ordinal;

        public Key() {
            // Required by the persistence provider.
        }

        public Key(UUID itemId, int ordinal) {
            this.itemId = itemId;
            this.ordinal = ordinal;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return ordinal == key.ordinal && Objects.equals(itemId, key.itemId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(itemId, ordinal);
        }
    }
}
