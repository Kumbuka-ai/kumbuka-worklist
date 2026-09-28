package ai.kumbuka.worklist.repository;

import ai.kumbuka.worklist.domain.Workstream;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Every statement issued against the {@code workstream} table.
 *
 * <p>Separate from {@link SelectorRepository} because a workstream is one
 * of four views but is not itself a selector; it is a first-class row with
 * an address of its own, whose counter happens to be one of the four the
 * selector registry holds.
 */
@ApplicationScoped
@TenantBound
public class WorkstreamRepository {

    private static final String P_SCOPE = "scope";
    private static final String P_TOKEN = "token";
    private static final String P_ID    = "id";

    @Inject EntityManager em;

    /** The workstream of that id in a scope, or null. */
    @Transactional
    public Workstream find(UUID scopeId, UUID id) {
        try {
            return em.createQuery(
                    "SELECT w FROM Workstream w WHERE w.scopeId = :scope AND w.id = :id",
                    Workstream.class)
                .setParameter(P_SCOPE, scopeId)
                .setParameter(P_ID, id)
                .getSingleResult();
        } catch (NoResultException absent) {
            return null;
        }
    }

    /** The workstream of that token in a scope, or null. */
    @Transactional
    public Workstream findByToken(UUID scopeId, String token) {
        try {
            return em.createQuery(
                    "SELECT w FROM Workstream w WHERE w.scopeId = :scope AND w.token = :token",
                    Workstream.class)
                .setParameter(P_SCOPE, scopeId)
                .setParameter(P_TOKEN, token)
                .getSingleResult();
        } catch (NoResultException absent) {
            return null;
        }
    }

    /** The workstream of that number in a scope, or null. */
    @Transactional
    public Workstream findByNumber(UUID scopeId, long number) {
        try {
            return em.createQuery(
                    "SELECT w FROM Workstream w WHERE w.scopeId = :scope AND w.number = :n",
                    Workstream.class)
                .setParameter(P_SCOPE, scopeId)
                .setParameter("n", number)
                .getSingleResult();
        } catch (NoResultException absent) {
            return null;
        }
    }

    /** The default workstream of that scope, or null before V8 has seeded it. */
    @Transactional
    public Workstream findDefault(UUID scopeId) {
        try {
            return em.createQuery(
                    "SELECT w FROM Workstream w WHERE w.scopeId = :scope "
                        + "AND w.isDefault = true",
                    Workstream.class)
                .setParameter(P_SCOPE, scopeId)
                .getSingleResult();
        } catch (NoResultException absent) {
            return null;
        }
    }

    /** Every workstream of a scope, in the axis's own order (by number). */
    @Transactional
    public List<Workstream> inScope(UUID scopeId) {
        return em.createQuery(
                "SELECT w FROM Workstream w WHERE w.scopeId = :scope ORDER BY w.number",
                Workstream.class)
            .setParameter(P_SCOPE, scopeId)
            .getResultList();
    }

    /**
     * True when this workstream has an item pointing at it.
     *
     * <p>Items and nothing else. A milestone was counted here too until
     * 2026-09-28, and the count was already meaningless: V12 retracted the
     * milestone-workstream edge (TAR-0002 section 4), so a milestone row's
     * {@code workstream_id} says nothing about where the milestone belongs.
     * What the count is FOR is the rename refusal — a token may be corrected
     * while nothing points into it and is fixed once something does — and a
     * dead value pointing nowhere is not something that can break.
     */
    @Transactional
    public boolean hasReferences(UUID scopeId, UUID workstreamId) {
        Long items = em.createQuery(
                "SELECT COUNT(i) FROM Item i "
                    + "WHERE i.scopeId = :scope AND i.workstreamId = :ws", Long.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter("ws", workstreamId)
            .getSingleResult();
        return items != null && items > 0;
    }

    /** Insert and flush, so table constraints answer here rather than at commit. */
    @Transactional
    public Workstream insert(Workstream workstream) {
        em.persist(workstream);
        em.flush();
        return workstream;
    }

    /** Flush a change so the table's constraints answer here rather than at commit. */
    @Transactional
    public void flush() {
        em.flush();
    }

    /** Refresh a row so database-generated fields (timestamps, tokens) are visible. */
    @Transactional
    public void refresh(Workstream workstream) {
        em.refresh(workstream);
    }
}
