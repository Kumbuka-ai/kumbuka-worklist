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
 * One option of a {@code choice} or {@code multi_choice} attribute.
 *
 * <p>A declared value like any other, so an option can be renamed and
 * withdrawn without touching a single item: what an item stores in its
 * document column is this row's {@link #id}, never its {@link #name}. The
 * predecessor cannot do that, because there the value IS the identifier, and
 * renaming a cluster would rewrite several hundred items and invalidate every
 * reference to it in prose.
 *
 * <p><strong>Two options may share a display name.</strong> That is not an
 * oversight: the concept makes the name a display property, which argues for
 * permitting it, while a reader confronted with two identical labels argues
 * against — and the question is carried as an open point rather than settled.
 * A uniqueness rule added before it is answered could never be switched on
 * again once two rows had shared a name, so the more expensive mistake is the
 * one that looks tidier.
 */
@Entity
@Table(name = "attribute_option", schema = "worklist")
public class AttributeOption {

    public static final String DECLARED = "declared";
    public static final String WITHDRAWN = "withdrawn";

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

    /** The declaration this option belongs to. Immutable: an option does not migrate. */
    @Column(name = "definition_id", nullable = false, updatable = false)
    public UUID definitionId;

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "description")
    public String description;

    /** The rank of the vocabulary — {@code S} before {@code M} before {@code L}. */
    @Column(name = "rank", nullable = false)
    public int rank;

    @Column(name = "status", nullable = false)
    public String status = DECLARED;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false)
    public Instant updatedAt;
}
