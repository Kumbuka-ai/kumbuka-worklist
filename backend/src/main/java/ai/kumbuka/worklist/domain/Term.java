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
import java.util.List;
import java.util.UUID;

/**
 * One value of one vocabulary, held by the scope that declared it.
 *
 * <p>The predecessor hard-codes four vocabularies — cluster, type, priority,
 * size — and gates every row against them. That makes a customer's own way of
 * characterising work into a release of this service, and it is the wrong
 * place for it: this service's business is that a value IS in the declared
 * vocabulary of its scope, never which values are.
 *
 * <p><strong>The axis is not data and the values are.</strong> Each axis is a
 * distinct column on the item, so a fifth axis is a schema change whatever
 * this table says; the four are therefore a check constraint over literals.
 * What a scope may declare freely is the tokens on them.
 *
 * <p>Withdrawal is a status here for a reason the selector does not share: a
 * term that was written onto items has to stay resolvable, or those items
 * become unreadable in their own history.
 */
@Entity
@Table(name = "term", schema = "worklist")
public class Term {

    /** The four axes, as the check constraint in V4 has them. */
    public static final String CLUSTER = "cluster";
    public static final String TYPE = "type";
    public static final String PRIORITY = "priority";
    public static final String SIZE = "size";

    /** In one place, so a caller can be told what it could have said. */
    public static final List<String> AXES = List.of(CLUSTER, TYPE, PRIORITY, SIZE);

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

    /** One of {@link #AXES}. Immutable: a term does not migrate between axes. */
    @Column(name = "axis", nullable = false, updatable = false)
    public String axis;

    /** {@code SEC}, {@code P1}, {@code L}. Immutable, like a selector token. */
    @Column(name = "token", nullable = false, updatable = false)
    public String token;

    /**
     * The scope's own ranking within its axis.
     *
     * <p>Data rather than code for the same reason as the token: a service
     * that knows {@code S} is smaller than {@code M} has to be released when
     * somebody starts using {@code XS}. Nothing reads it yet — sorting is the
     * reading surface's, which is a separate piece of work — and it is here
     * because a rank recorded later is a judgement backfilled by whoever
     * happens to be there rather than by whoever declared the term.
     */
    @Column(name = "ordinal", nullable = false)
    public int ordinal;

    @Column(name = "status", nullable = false)
    public String status = DECLARED;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false)
    public Instant updatedAt;
}
