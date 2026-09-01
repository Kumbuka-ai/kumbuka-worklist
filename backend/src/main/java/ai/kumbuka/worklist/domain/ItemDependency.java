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
 * One item depends on another.
 *
 * <p>The predecessor holds this in a cell as a comma-separated list of row
 * numbers, and the contract accordingly lists a DANGLING REFERENCE as a
 * whole-inventory violation that a validation pass reports. Here the edge is
 * a row with a foreign key on both ends, so a dangling reference is not
 * something to find — it is something that cannot be written. That is the
 * stronger outcome, and it is the point of asking what a property would look
 * like if the store had always been a database.
 *
 * <p><strong>What does not follow: a cycle is still possible.</strong> No
 * constraint expresses "this graph is acyclic", and the walk that answers it
 * is a whole-inventory question that belongs with the other whole-inventory
 * checks — not on the write path, where re-validating the inventory is the
 * shape that once sealed the predecessor's writes against four rows it could
 * not migrate.
 *
 * <p><strong>No relationship type</strong>, and the contract's reason holds
 * unchanged: the moment types exist, something has to interpret them, and the
 * vocabulary needs its own definition and its own guard.
 *
 * <p><strong>Withdrawal rather than deletion</strong>, here as everywhere in
 * this schema. An edge that is no longer asserted keeps its row and changes
 * its status. The reason is not tidiness about edges: it is that "no verb
 * deletes" is held by the GRANTS — there is no DELETE privilege on any table
 * in this schema — and a single exception would cost the whole of it.
 */
@Entity
@Table(name = "item_dependency", schema = "worklist")
@IdClass(ItemDependency.Key.class)
public class ItemDependency {

    /** The edge is asserted. */
    public static final String ASSERTED = "asserted";
    /** The edge was asserted once and is not any more. */
    public static final String WITHDRAWN = "withdrawn";

    @Id
    @Column(name = "item_id", nullable = false)
    public UUID itemId;

    @Id
    @Column(name = "depends_on_id", nullable = false)
    public UUID dependsOnId;

    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "status", nullable = false)
    public String status = ASSERTED;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false)
    public Instant updatedAt;

    /**
     * The composite key.
     *
     * <p>The tenancy axis is deliberately NOT part of it. An item id is
     * already unique across tenants, and a tenant-qualified key would suggest
     * that two tenants could hold the same edge — which the composite foreign
     * keys in V4 make impossible in the other direction anyway.
     */
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        public UUID itemId;
        public UUID dependsOnId;

        public Key() {
            // Required by the persistence provider.
        }

        public Key(UUID itemId, UUID dependsOnId) {
            this.itemId = itemId;
            this.dependsOnId = dependsOnId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(itemId, key.itemId)
                && Objects.equals(dependsOnId, key.dependsOnId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(itemId, dependsOnId);
        }
    }
}
