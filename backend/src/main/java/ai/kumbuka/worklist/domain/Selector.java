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
 * The declared head of an address — the {@code FEAT} of {@code FEAT-51}.
 *
 * <p>Two properties make it what it is, and both are refusals rather than
 * features.
 *
 * <p><strong>It is never created implicitly.</strong> Not by the first item
 * that mentions it, not by any verb other than the one whose entire purpose
 * is to declare it. A service that creates a selector on first use answers a
 * misspelt address by opening a second address space, and afterwards the two
 * are indistinguishable — both exist, both have items in them, and nothing
 * records that one was a typo.
 *
 * <p><strong>It is never renamed.</strong> Every address ever issued under a
 * selector — in a commit message, in another service's reference field, in
 * something a person wrote down — resolves through this row, so a rename
 * breaks all of them at once and without a sound. Withdrawal is a status
 * instead, and a withdrawn selector keeps its token so that the token cannot
 * come to mean something else.
 *
 * <p>Neither property is left to this class. The database refuses a rename
 * through a trigger the runtime role cannot drop, and the unique constraint
 * is what makes a withdrawn token stay occupied. What is here is the mapping.
 */
@Entity
@Table(name = "selector", schema = "worklist")
public class Selector {

    /** A selector that may still be used. */
    public static final String DECLARED = "declared";
    /** Withdrawn: resolvable for what already exists, closed to anything new. */
    public static final String WITHDRAWN = "withdrawn";

    /**
     * The shape of a token: a leading letter, then alphanumerics and interior
     * hyphens. {@code FEAT}, {@code CHORE}, {@code F}, {@code D-GTM} pass; a
     * leading digit, a trailing hyphen, a doubled hyphen and an empty token do
     * not.
     *
     * <p>Here rather than in the registry that uses it, because
     * {@code ck_selector_token} in V4 is the same expression and the two must
     * not drift: a Java check that accepted what the database rejects would
     * turn a refusal into a constraint violation, and the other way round
     * would let a token through that nothing can store.
     *
     * <p><strong>The quantifiers are possessive, and that is load-bearing.</strong>
     * Written as {@code [A-Za-z0-9]*(-[A-Za-z0-9]+)*} — the obvious form, and
     * the one V4 carries because PostgreSQL's engine does not backtrack this
     * way — the two nested stars give Java's engine an exponential number of
     * ways to split a long non-matching input, and a token of a few dozen
     * characters is enough to hang the thread. Possessive quantifiers commit
     * to what they consume and never give it back, so a failure is decided in
     * one pass. The accepted language is identical.
     */
    public static final java.util.regex.Pattern TOKEN_PATTERN =
        java.util.regex.Pattern.compile("^[A-Za-z][A-Za-z0-9]*+(?:-[A-Za-z0-9]++)*+$");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    public UUID id;

    /** The tenancy axis. String-typed for the resolver SPI; see {@link Item}. */
    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "scope_id", nullable = false)
    public UUID scopeId;

    /** {@code FEAT}, {@code CHORE}, {@code D-GTM}. Immutable. */
    @Column(name = "token", nullable = false, updatable = false)
    public String token;

    @Column(name = "status", nullable = false)
    public String status = DECLARED;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Generated(event = EventType.INSERT)
    @Column(name = "updated_at", nullable = false, insertable = false)
    public Instant updatedAt;
}
