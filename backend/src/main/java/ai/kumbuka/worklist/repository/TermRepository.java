package ai.kumbuka.worklist.repository;

import ai.kumbuka.worklist.domain.Term;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Every statement issued against the term table.
 *
 * <p>Separate from the other two for the same reason they are separate from
 * each other: the vocabulary is declared on its own path, and the guard that
 * binds the domain vocabulary to the specification reads this registry rather
 * than the item domain.
 *
 * <p>Refusals stay above. "No such axis" and "no such value on this axis" are
 * different answers, and which one is owed depends on what was asked.
 */
@ApplicationScoped
@TenantBound
public class TermRepository {

    private static final String P_SCOPE = "scope";

    private static final String P_AXIS = "axis";

    @Inject EntityManager em;

    /** The value of that token on that axis in a scope, or null. */
    @Transactional
    public Term find(UUID scopeId, String axis, String token) {
        try {
            return em.createQuery(
                    "SELECT t FROM Term t WHERE t.scopeId = :scope AND t.axis = :axis "
                        + "AND t.token = :token", Term.class)
                .setParameter(P_SCOPE, scopeId)
                .setParameter(P_AXIS, axis)
                .setParameter("token", token)
                .getSingleResult();
        } catch (NoResultException absent) {
            return null;
        }
    }

    /** Every value on an axis in a scope, by rank then token. */
    @Transactional
    public List<Term> onAxis(UUID scopeId, String axis) {
        return em.createQuery(
                "SELECT t FROM Term t WHERE t.scopeId = :scope AND t.axis = :axis "
                    + "ORDER BY t.ordinal, t.token", Term.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_AXIS, axis)
            .getResultList();
    }

    /** Inserts a value and flushes, so its unique constraint answers here. */
    @Transactional
    public Term insert(Term term) {
        em.persist(term);
        em.flush();
        return term;
    }

    /** Flushes a status change so the table's constraints answer here. */
    @Transactional
    public void flush() {
        em.flush();
    }
}
