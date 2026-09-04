package ai.kumbuka.worklist.repository;

import ai.kumbuka.worklist.domain.NumberSpace;
import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Every statement issued against the selector and number-space tables.
 *
 * <p>Separate from {@link ItemRepository} because the registry above it is
 * separate: a selector is declared deliberately, and the number space is
 * locked on a path that runs once per acceptance rather than on every write.
 *
 * <p>As there, refusals stay above. A missing number space and a withdrawn
 * selector are two different things a caller is told, and this layer does not
 * know which question was asked.
 */
@ApplicationScoped
@TenantBound
public class SelectorRepository {

    private static final String P_SCOPE = "scope";

    private static final String P_TOKEN = "token";

    @Inject EntityManager em;

    /** The selector of that token in a scope, or null. */
    @Transactional
    public Selector find(UUID scopeId, String token) {
        try {
            return em.createQuery(
                    "SELECT s FROM Selector s WHERE s.scopeId = :scope AND s.token = :token",
                    Selector.class)
                .setParameter(P_SCOPE, scopeId)
                .setParameter(P_TOKEN, token)
                .getSingleResult();
        } catch (NoResultException absent) {
            return null;
        }
    }

    /** Every selector of a scope, declared and withdrawn alike, by token. */
    @Transactional
    public List<Selector> inScope(UUID scopeId) {
        return em.createQuery(
                "SELECT s FROM Selector s WHERE s.scopeId = :scope ORDER BY s.token",
                Selector.class)
            .setParameter(P_SCOPE, scopeId)
            .getResultList();
    }

    /**
     * The selector's number space, locked for the caller's transaction, or
     * null when it has none.
     *
     * <p>The lock is what makes two concurrent acceptances serialise rather
     * than collide, and taking it in the accepting transaction is what makes a
     * rolled-back acceptance give its number back.
     *
     * <p>Looked up by the selector rather than found by key: the counter's own
     * key is a surrogate now, because the scope-wide counter has no selector
     * to be keyed by.
     */
    @Transactional
    public NumberSpace lockSpace(UUID selectorId) {
        return single(spaceQuery("s.selectorId = :selector")
            .setParameter("selector", selectorId)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE));
    }

    /** The selector's number space without a lock, for a read that only reports it. */
    @Transactional
    public NumberSpace space(UUID selectorId) {
        return single(spaceQuery("s.selectorId = :selector")
            .setParameter("selector", selectorId));
    }

    /**
     * The scope-wide counter, locked, or null when the scope has none.
     *
     * <p>It exists beside the per-selector ones at all times and is advanced
     * by every allocation whatever the scope's mode says. That is what makes
     * the mode a setting rather than a migration: switching it is a read
     * against a counter that was maintained all along, and not a
     * reconstruction from rows that no longer say what was handed out.
     */
    @Transactional
    public NumberSpace lockScopeWideSpace(UUID scopeId) {
        return single(spaceQuery("s.scopeId = :scope AND s.selectorId IS NULL")
            .setParameter(P_SCOPE, scopeId)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE));
    }

    /** The scope-wide counter without a lock, for a read that only reports it. */
    @Transactional
    public NumberSpace scopeWideSpace(UUID scopeId) {
        return single(spaceQuery("s.scopeId = :scope AND s.selectorId IS NULL")
            .setParameter(P_SCOPE, scopeId));
    }

    private TypedQuery<NumberSpace> spaceQuery(String predicate) {
        return em.createQuery(
            "SELECT s FROM NumberSpace s WHERE " + predicate, NumberSpace.class);
    }

    private static NumberSpace single(TypedQuery<NumberSpace> query) {
        try {
            return query.getSingleResult();
        } catch (NoResultException absent) {
            return null;
        }
    }

    /** Inserts a selector and flushes, so its unique constraint answers here. */
    @Transactional
    public Selector insert(Selector selector) {
        em.persist(selector);
        em.flush();
        return selector;
    }

    /** Inserts a number space and flushes, for the same reason. */
    @Transactional
    public NumberSpace insert(NumberSpace space) {
        em.persist(space);
        em.flush();
        return space;
    }

    /** Flushes a status or mark change so the table's constraints answer here. */
    @Transactional
    public void flush() {
        em.flush();
    }
}
