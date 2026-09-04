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
 * One declared type of edge, and whether it blocks.
 *
 * <p>{@link #blocks} is the ONE property of a type the platform reasons
 * about. Everything else the type means belongs to the scope, and the
 * platform never asks.
 *
 * <p>That single boolean is what makes readiness answerable at all. The
 * predecessor's dependency column carries no type, so every machine reader
 * has to guess whether an edge blocks — and a reader that guesses "blocking"
 * resolves at least one real edge wrongly. The previous shape of this domain
 * deferred the type deliberately, on the ground that the moment types exist
 * something has to interpret them. This is that interpretation, and it is
 * exactly one column wide.
 *
 * <p><strong>Whether a type may be declared blocking after items already
 * carry it is an open point</strong>: flipping the property retroactively
 * changes the readiness of items nobody touched. The field is ordinary and
 * updatable here, and the answer belongs where the verb lives rather than in
 * a mapping.
 */
@Entity
@Table(name = "relation_type", schema = "worklist")
public class RelationType {

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

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "description")
    public String description;

    @Column(name = "rank", nullable = false)
    public int rank;

    /** The only property of a type the platform reasons about. */
    @Column(name = "blocks", nullable = false)
    public boolean blocks;

    @Column(name = "status", nullable = false)
    public String status = DECLARED;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false)
    public Instant updatedAt;
}
