package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.StringUuidConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;

import java.util.UUID;

/**
 * The tenancy pair, which every table in this schema carries without
 * exception.
 *
 * <p>{@code tenant_id} is the axis row-level security filters on and
 * {@code scope_id} is the unit of tenancy the row belongs to. Both are on the
 * ROW rather than reachable through a join, and that is not a convenience: a
 * policy filters on a column of the table it is filtering and cannot follow a
 * foreign key, so a satellite whose tenant were only derivable from its parent
 * would have no predicate to be filtered by.
 *
 * <p>Held in one place because the mapping has to be identical everywhere. The
 * two columns spelled slightly differently on one table would leave that table
 * outside the completeness probe, which reads the catalog for the column NAME
 * — and a table the probe does not recognise is one nobody checks.
 *
 * <p>The timestamps are deliberately NOT here. Most tables carry
 * {@code updated_at}; the item carries {@code changed_at}, as the target
 * schema names it. Putting them in a shared parent would mean overriding the
 * one that differs, which is a longer way to say the same thing and hides
 * which table is the exception.
 */
@MappedSuperclass
public abstract class TenantScoped {

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

    /**
     * The platform scope this row belongs to. Stored, never resolved from
     * here: the platform publishes a read contract for scope access, and
     * consuming it is a runtime read rather than a schema-level reference.
     */
    @Column(name = "scope_id", nullable = false)
    public UUID scopeId;
}
