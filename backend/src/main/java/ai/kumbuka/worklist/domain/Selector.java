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
import java.util.List;
import java.util.UUID;

/**
 * The declared head of an address — the {@code item} of
 * {@code worklist://kumbuka/item/562}.
 *
 * <h2>The selector is the view, and no longer the item's family</h2>
 *
 * A token names one of three <strong>views</strong> onto what a scope holds:
 * its items, its iterations, its milestones. It used to name the family of an
 * item — {@code FEAT}, {@code CHORE}, {@code BUG} — and each family was an
 * address space of its own. That is what changed: the families are no longer
 * a number space, one counter serves the whole scope, and what stands at the
 * head of an address now says which KIND of thing is at the other end of it.
 *
 * <p>The three are platform vocabulary and not a scope's declaration. A scope
 * declares which statuses and attributes its items carry; it does not declare
 * that iterations exist. {@link SelectorRegistry#declare} therefore refuses a
 * token outside {@link #VIEWS} — the check is in the domain rather than in a
 * constraint, because "these three and no others" is a statement about the
 * platform's object model, and a scope-local table is the wrong place to keep
 * one.
 *
 * <h2>Two properties survive the change unaltered, and both are refusals</h2>
 *
 * <p><strong>A selector is never created implicitly.</strong> Not by the first
 * item that mentions it, not by any verb other than the one whose entire
 * purpose is to declare it. That the set is now closed makes the rule easier
 * to keep and does not replace it: a scope still has to have its three rows
 * before an address under them resolves.
 *
 * <p><strong>A selector is never renamed.</strong> Every address ever issued
 * under one resolves through this row, so a rename breaks all of them at once
 * and without a sound. Withdrawal is a status instead, and a withdrawn
 * selector keeps its token so that the token cannot come to mean something
 * else. The database refuses a rename through a trigger the runtime role
 * cannot drop.
 */
@Entity
@Table(name = "selector", schema = "worklist")
public class Selector extends TenantScoped {

    /** A selector that may still be used. */
    public static final String DECLARED = "declared";
    /** Withdrawn: resolvable for what already exists, closed to anything new. */
    public static final String WITHDRAWN = "withdrawn";

    /** The view onto a scope's items. */
    public static final String ITEM = "item";

    /** The view onto its time axis. */
    public static final String ITERATION = "iteration";

    /** The view onto its goal axis. */
    public static final String MILESTONE = "milestone";

    /**
     * The three views, in the order the address space reads them.
     *
     * <p>A {@code List} and not a {@code Set}, because a refusal that names
     * the admissible values reads better in a fixed order than in whatever
     * order a hash produced — and this list appears in refusal messages a
     * caller has to act on.
     */
    public static final List<String> VIEWS = List.of(ITEM, ITERATION, MILESTONE);

    /**
     * The shape of a token: a leading letter, then alphanumerics and interior
     * hyphens, <strong>lower case throughout</strong>.
     *
     * <p><strong>The case is a decision made here and not a discovery.</strong>
     * ADR-0009 fixes the four address parts and says nothing about the case of
     * the selector; the constraint this pattern mirrors admitted upper case,
     * because the families it was written for were spelled {@code FEAT} and
     * {@code CHORE}. With the selector reduced to three fixed views the
     * question stops being open and is settled the way the rest of the address
     * settles it: the scope is a DNS label and is lower case, upper case is
     * rejected rather than folded, and the selector now reads the same way.
     * Folding would make {@code Item} and {@code item} resolve to one
     * selector, which is an identity statement arrived at by leniency.
     *
     * <p>Here rather than in the registry that uses it, because
     * {@code ck_selector_token} in V6 is the same expression and the two must
     * not drift: a Java check that accepted what the database rejects would
     * turn a refusal into a constraint violation, and the other way round
     * would let a token through that nothing can store.
     *
     * <p>The pattern stays a FORM check even though the admissible set is now
     * three literals. Form and vocabulary are two stages of the ratified check
     * order — a malformed token is decidable without knowing any scope, and
     * which tokens a deployment admits is not — and collapsing them here would
     * put the vocabulary stage in front of the visibility stage for this one
     * value.
     *
     * <p><strong>The quantifiers are possessive, and that is load-bearing.</strong>
     * Written as {@code [a-z0-9]*(-[a-z0-9]+)*} — the obvious form, and the one
     * the constraint carries because PostgreSQL's engine does not backtrack
     * this way — the two nested stars give Java's engine an exponential number
     * of ways to split a long non-matching input, and a token of a few dozen
     * characters is enough to hang the thread. Possessive quantifiers commit to
     * what they consume and never give it back, so a failure is decided in one
     * pass. The accepted language is identical.
     */
    public static final java.util.regex.Pattern TOKEN_PATTERN =
        java.util.regex.Pattern.compile("^[a-z][a-z0-9]*+(?:-[a-z0-9]++)*+$");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    public UUID id;


    /** {@code item}, {@code iteration}, {@code milestone}. Immutable. */
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
