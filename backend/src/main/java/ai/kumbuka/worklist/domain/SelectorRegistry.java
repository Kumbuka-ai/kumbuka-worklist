package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * The address spaces of a scope: which selectors exist, and what number comes
 * next in each.
 *
 * <p>Two things live here because they are the same thing seen twice. A
 * selector is the head of an address; a number space is the tail of the same
 * address; and the rule that makes both work — that an address is issued once
 * and never again — is one rule, kept in one place.
 *
 * <p>{@code @TenantBound} at class level, so the database session setting is
 * bound inside every transaction. Both layers of the enforcement model move
 * together or the ORM filter and the policy disagree about who is asking, and
 * the result — an empty set — looks exactly like correct isolation.
 */
@ApplicationScoped
@TenantBound
public class SelectorRegistry {

    /** An address, a scope id, a number. Never content, never an actor. */
    private static final Logger LOG = Logger.getLogger(SelectorRegistry.class);

    @Inject EntityManager em;

    /**
     * Declare a selector. This is the ONLY way one comes into existence.
     *
     * <p>No other method here or anywhere else inserts into this table, and
     * that is the whole design rather than an implementation choice: a
     * service that creates a selector on first use answers {@code FAET-1}
     * by opening a second address space, and afterwards nothing distinguishes
     * the typo from the intention — both exist, both have items under them.
     *
     * <p>Declaring one that already exists returns the existing one rather
     * than refusing. Declaration is a statement that the space should exist,
     * and it is either true or it was already true; a caller retrying after a
     * timeout should not have to tell those two apart.
     */
    @Transactional
    public Selector declare(UUID scopeId, String token) {
        if (token == null || !token.matches("^[A-Za-z][A-Za-z0-9]*(-[A-Za-z0-9]+)*$")) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "a selector token is a leading letter followed by alphanumerics and "
                    + "interior hyphens — FEAT, CHORE, D-GTM. Refused: " + token,
                List.of(String.valueOf(token)));
        }

        Selector existing = find(scopeId, token);
        if (existing != null) {
            return existing;
        }

        Selector selector = new Selector();
        selector.scopeId = scopeId;
        selector.token = token;
        em.persist(selector);
        em.flush();

        // The address space opens with the selector, at zero. Created here
        // rather than lazily on the first allocation, because a lazily
        // created mark is a mark that two concurrent first allocations both
        // try to create, and the loser sees a constraint violation rather
        // than a number.
        NumberSpace space = new NumberSpace();
        space.selectorId = selector.id;
        space.scopeId = scopeId;
        space.highWaterMark = 0L;
        em.persist(space);
        em.flush();

        LOG.infof("selector %s declared in scope %s", token, scopeId);
        return selector;
    }

    /**
     * Withdraw a selector: nothing new is admitted under it, and everything
     * already admitted keeps resolving.
     *
     * <p>The token stays occupied. That is the point — a released token could
     * be declared again to mean something else, and every address ever issued
     * under the first meaning would quietly resolve to the second.
     */
    @Transactional
    public Selector withdraw(UUID scopeId, String token) {
        Selector selector = require(scopeId, token);
        if (Selector.WITHDRAWN.equals(selector.status)) {
            return selector;
        }
        selector.status = Selector.WITHDRAWN;
        em.flush();
        LOG.infof("selector %s withdrawn in scope %s", token, scopeId);
        return selector;
    }

    /** Every selector of a scope, declared and withdrawn alike, by token. */
    @Transactional
    public List<Selector> inScope(UUID scopeId) {
        return em.createQuery(
                "SELECT s FROM Selector s WHERE s.scopeId = :scope ORDER BY s.token",
                Selector.class)
            .setParameter("scope", scopeId)
            .getResultList();
    }

    /**
     * The selector of that token, or a typed refusal naming it.
     *
     * <p>The refusal names the token AND says what to do about it, because
     * "no such selector" and "you have to declare it first" are the same fact
     * and only the second one is actionable.
     */
    @Transactional
    public Selector require(UUID scopeId, String token) {
        Selector selector = find(scopeId, token);
        if (selector == null) {
            throw new WorklistException(
                WorklistException.Reason.SELECTOR_UNDECLARED,
                "selector " + token + " is not declared in scope " + scopeId
                    + ". It is not created by using it: an address space that appeared "
                    + "on first use would make a misspelt selector indistinguishable "
                    + "from an intended one. Declare it first",
                List.of(String.valueOf(token)));
        }
        return selector;
    }

    /**
     * The next number in a selector's space, and the mark moved to match.
     *
     * <p>The row is locked for the length of the transaction, so two callers
     * allocating at once are serialised and get different numbers rather than
     * the same one twice. {@code PESSIMISTIC_WRITE} rather than a retry loop
     * because the contended case here is two agents working the same scope,
     * which is normal rather than exceptional.
     *
     * <p>A number allocated by a transaction that then rolls back is BURNT:
     * the mark rolls back with it, so it is handed out again. That is the one
     * place this differs from a sequence, and it is the safe direction —
     * a number is reused only when nothing ever saw it.
     */
    @Transactional
    public long allocate(UUID scopeId, Selector selector) {
        if (Selector.WITHDRAWN.equals(selector.status)) {
            throw new WorklistException(
                WorklistException.Reason.SELECTOR_WITHDRAWN,
                "selector " + selector.token + " is withdrawn in scope " + scopeId
                    + ", so no further address is issued under it. What was already "
                    + "issued keeps resolving",
                List.of(selector.token));
        }

        NumberSpace space = em.find(NumberSpace.class, selector.id,
            LockModeType.PESSIMISTIC_WRITE);
        if (space == null) {
            // A selector without its space is a row that predates the
            // declaration path above, or one written around it. Reported
            // rather than repaired: creating the missing space here would
            // silently accept the second case.
            throw new WorklistException(
                WorklistException.Reason.SELECTOR_UNDECLARED,
                "selector " + selector.token + " has no number space in scope " + scopeId
                    + ". A declared selector always has one; this selector was not "
                    + "declared through the declaring verb",
                List.of(selector.token));
        }

        space.highWaterMark = space.highWaterMark + 1;
        em.flush();
        LOG.debugf("number %d allocated under selector %s in scope %s",
            space.highWaterMark, selector.token, scopeId);
        return space.highWaterMark;
    }

    /**
     * Carry a high-water mark forward, for an import that arrives with
     * numbers already allocated elsewhere.
     *
     * <p>Forward only. Moving a mark back is not a smaller version of moving
     * it forward — it is the act of handing out numbers that are already in
     * use, which is the one thing the mark exists to prevent. The refusal
     * carries both values so the caller can see by how much it was wrong.
     */
    @Transactional
    public long carryMarkForward(UUID scopeId, String token, long mark) {
        Selector selector = require(scopeId, token);
        NumberSpace space = em.find(NumberSpace.class, selector.id,
            LockModeType.PESSIMISTIC_WRITE);

        if (space == null || mark < space.highWaterMark) {
            long current = space == null ? -1 : space.highWaterMark;
            throw new WorklistException(
                WorklistException.Reason.MARK_REGRESSION,
                "the high-water mark of selector " + token + " in scope " + scopeId
                    + " stands at " + current + " and may not be set to " + mark
                    + ". A mark is carried forward and never back: every number up to "
                    + "the mark has been handed out, and setting it lower hands the same "
                    + "numbers out a second time",
                List.of(token));
        }

        space.highWaterMark = mark;
        em.flush();
        LOG.infof("high-water mark of selector %s in scope %s carried to %d",
            token, scopeId, mark);
        return mark;
    }

    /** The current mark, for a caller that needs to know where a space stands. */
    @Transactional
    public long markOf(UUID scopeId, String token) {
        Selector selector = require(scopeId, token);
        NumberSpace space = em.find(NumberSpace.class, selector.id);
        return space == null ? 0L : space.highWaterMark;
    }

    private Selector find(UUID scopeId, String token) {
        try {
            return em.createQuery(
                    "SELECT s FROM Selector s WHERE s.scopeId = :scope AND s.token = :token",
                    Selector.class)
                .setParameter("scope", scopeId)
                .setParameter("token", token)
                .getSingleResult();
        } catch (NoResultException absent) {
            return null;
        }
    }
}
