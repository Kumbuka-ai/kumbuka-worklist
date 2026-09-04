package ai.kumbuka.worklist.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One typed edge between two items.
 *
 * <p>The predecessor holds this in a cell as a comma-separated list of row
 * numbers with <strong>no relationship type at all</strong>, and its checks
 * run only inside a validation verb. Two things change here and they are not
 * the same change.
 *
 * <p><strong>The edge is a row, so a dangling reference cannot be
 * written.</strong> A foreign key on both ends makes it impossible rather
 * than findable, which is the stronger outcome: the violation class stops
 * being a thing that can happen.
 *
 * <p><strong>The edge carries a type, so it can be asked whether it
 * blocks.</strong> The previous shape of this domain left the type out
 * deliberately — the moment types exist, something has to interpret them, and
 * the vocabulary needs its own definition and its own guard. {@link
 * RelationType} is that vocabulary and {@link RelationType#blocks} is that
 * interpretation, so the deferral is discharged rather than repeated.
 *
 * <p><strong>The key is the triple</strong> from, to and type. Two items may
 * therefore carry two edges of different types and never two of the same,
 * which is a rule the untyped predecessor edge could not state.
 *
 * <p>Relations are DIRECTED and stored ONCE. The inverse is a query and never
 * a second row — the same conclusion OSLC arrived at after deprecating its own
 * back-links.
 *
 * <p><strong>What does not follow: a cycle is still possible.</strong> No
 * constraint expresses "this graph is acyclic", and the walk that answers it
 * is a domain check with a red probe of its own. It is not built here.
 *
 * <p><strong>Withdrawal rather than deletion</strong>, here as everywhere in
 * this schema. An edge that is no longer asserted keeps its row and changes
 * its status, and that is what lets "no verb deletes" be held by the GRANTS
 * rather than by a rule somebody has to remember.
 */
@Entity
@Table(name = "item_relation", schema = "worklist")
@IdClass(ItemRelation.Key.class)
public class ItemRelation extends TenantScoped {

    /** The edge is asserted. */
    public static final String ASSERTED = "asserted";
    /** The edge was asserted once and is not any more. */
    public static final String WITHDRAWN = "withdrawn";

    @Id
    @Column(name = "from_item_id", nullable = false)
    public UUID fromItemId;

    @Id
    @Column(name = "to_item_id", nullable = false)
    public UUID toItemId;

    @Id
    @Column(name = "relation_type_id", nullable = false)
    public UUID relationTypeId;

    @Column(name = "status", nullable = false)
    public String status = ASSERTED;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false)
    public Instant updatedAt;

    /**
     * The composite key: from, to, and the type.
     *
     * <p>The tenancy axis is deliberately NOT part of it. An item id is
     * already unique across tenants, and a tenant-qualified key would suggest
     * that two tenants could hold the same edge — which the composite foreign
     * keys in V4 make impossible in the other direction anyway.
     *
     * <p>The type IS part of it, and that is the difference from the untyped
     * edge this replaces: without it, asserting a second kind of relation
     * between the same two items would overwrite the first.
     */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        public UUID fromItemId;
        public UUID toItemId;
        public UUID relationTypeId;

        public Key() {
            // Required by the persistence provider.
        }

        public Key(UUID fromItemId, UUID toItemId, UUID relationTypeId) {
            this.fromItemId = fromItemId;
            this.toItemId = toItemId;
            this.relationTypeId = relationTypeId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(fromItemId, key.fromItemId)
                && Objects.equals(toItemId, key.toItemId)
                && Objects.equals(relationTypeId, key.relationTypeId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(fromItemId, toItemId, relationTypeId);
        }
    }
}
