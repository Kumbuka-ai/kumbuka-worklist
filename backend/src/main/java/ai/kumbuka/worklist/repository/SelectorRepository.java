package ai.kumbuka.worklist.repository;

import ai.kumbuka.worklist.domain.NumberSpace;
import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
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
     */
    @Transactional
    public NumberSpace lockSpace(UUID selectorId) {
        return em.find(NumberSpace.class, selectorId, LockModeType.PESSIMISTIC_WRITE);
    }

    /** The selector's number space without a lock, for a read that only reports it. */
    @Transactional
    public NumberSpace space(UUID selectorId) {
        return em.find(NumberSpace.class, selectorId);
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
