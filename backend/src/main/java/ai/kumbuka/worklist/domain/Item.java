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
 * One item: an entry in what a scope intends to do.
 *
 * <p>The object in the list is an <strong>Item</strong> (target state section
 * 3.1). Not "row", which is a property of the Markdown store being replaced,
 * and not "issue", which two foreign products already occupy.
 *
 * <p><strong>This is the substrate's shape of an item and not the domain's.</strong>
 * The target state's core carries a status, and around it a declared attribute
 * set, typed relations and planning membership. None of that is here: the
 * vocabulary mechanism that gives a status value its meaning does not exist
 * yet, and a status column written before it would carry whatever the first
 * writer assumed. What is here is the identity, the tenancy axis, the scope
 * the item belongs to, and a title — the smallest thing that is genuinely an
 * item rather than a scaffold, which is what the isolation probes need to
 * hang on.
 *
 * <p>{@code scopeId} is stored and never resolved from here. The platform
 * publishes a read contract for scope access; consuming it is a runtime read
 * through {@link ai.kumbuka.worklist.platform.ScopeDirectory}, not a
 * schema-level reference, which is why there is no foreign key and no join.
 */
@Entity
@Table(name = "item", schema = "worklist")
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    public UUID id;

    /**
     * The tenancy axis — layer 1 of the enforcement model.
     *
     * <p>Typed as String because Quarkus' Hibernate tenant-resolver SPI is
     * String-only, and converted to the {@code uuid} column by
     * {@link StringUuidConverter}.
     */
    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    /** The platform scope this item belongs to. Stored, never resolved from here. */
    @Column(name = "scope_id", nullable = false)
    public UUID scopeId;

    /** One line, human readable (target state section 3.3). */
    @Column(name = "title", nullable = false)
    public String title;

    // --- technical fields, server-derived ---------------------------------

    /**
     * Written by the database default and read back, never sent.
     *
     * <p>{@code @Generated} is what tells Hibernate to fetch the value rather
     * than to supply one: without it the entity would carry whatever it last
     * saw, and an insert would try to write a null into a not-null column.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    /**
     * Set at insert and, for now, not maintained on update.
     *
     * <p>The substrate has no verb that updates an item, so there is nothing
     * for a maintenance trigger to fire on yet and one written now could not
     * be observed working. It arrives with the first mutating verb in the
     * domain half, together with the probe that watches it move. Mapping it
     * as {@code @Generated} on UPDATE today would announce a behaviour the
     * schema does not have.
     */
    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    public Instant updatedAt;
}
