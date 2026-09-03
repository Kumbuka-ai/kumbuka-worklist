package ai.kumbuka.worklist.repository;

import ai.kumbuka.worklist.domain.Item;
import ai.kumbuka.worklist.domain.ItemDependency;
import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.Term;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Every statement issued against the item tables.
 *
 * <p>The class exists so that "JPA lives in one package" is a sentence a test
 * can check. Before it, the entity manager was reachable from the domain
 * service, both registries and the platform directory, and the boundary was
 * maintained by searching the tree — which is not a boundary.
 *
 * <h2>What is here and what is deliberately not</h2>
 *
 * Queries and writes are here. <strong>Refusals are not.</strong> A lookup
 * that finds nothing returns null, and the caller decides whether that is
 * {@code ITEM_UNKNOWN}, an ordinary absence, or a token that simply has no
 * name yet. Those are three different things a caller is told, and telling
 * them apart needs why the row was asked for.
 *
 * <p>The methods carry {@code @Transactional} and the class is
 * {@link TenantBound}, matching the callers rather than replacing them. Every
 * entry point is already inside a transaction, so the annotation joins that
 * one and starts none; what it buys is that the guard over tenant-bound
 * classes covers this one too, and a future caller that forgot its own
 * transaction fails loudly here instead of reading under no tenant at all.
 */
@ApplicationScoped
@TenantBound
public class ItemRepository {

    private static final String P_SCOPE = "scope";

    private static final String P_ITEM = "item";

    @Inject EntityManager em;

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * Every item of a scope, oldest first.
     *
     * <p>Ordering by creation and not by the sort key of the contract: the
     * sort key ranks by milestone and cluster, the milestone is the planning
     * layer's, and a partial implementation of a documented order is worse
     * than an obviously different one.
     */
    @Transactional
    public List<Item> inScope(UUID scopeId) {
        return em.createQuery(
                "SELECT i FROM Item i WHERE i.scopeId = :scope ORDER BY i.createdAt, i.id",
                Item.class)
            .setParameter(P_SCOPE, scopeId)
            .getResultList();
    }

    /** The item of that id, or null. Scope membership is checked by the caller. */
    @Transactional
    public Item byId(UUID itemId) {
        return itemId == null ? null : em.find(Item.class, itemId);
    }

    /** The selector of that id, or null. */
    @Transactional
    public Selector selectorById(UUID selectorId) {
        return selectorId == null ? null : em.find(Selector.class, selectorId);
    }

    /** The term of that id, or null. */
    @Transactional
    public Term termById(UUID termId) {
        return termId == null ? null : em.find(Term.class, termId);
    }

    /** Every dependency edge of an item, asserted and withdrawn alike. */
    @Transactional
    public List<ItemDependency> edgesOf(UUID itemId) {
        return em.createQuery(
                "SELECT d FROM ItemDependency d WHERE d.itemId = :item", ItemDependency.class)
            .setParameter(P_ITEM, itemId)
            .getResultList();
    }

    /**
     * The asserted edges only, sorted. A withdrawn edge is history, not a
     * dependency.
     *
     * <p>Sorted in the query, so that the answer is stable across reads.
     * Without that, a caller who re-sent a read answer would present the same
     * set in another order, and a comparison would report a change the caller
     * never made — the item would take a fresh modification date and a rotated
     * token for a write that changed nothing.
     */
    @Transactional
    public List<UUID> assertedDependencies(UUID itemId) {
        return em.createQuery(
                "SELECT d.dependsOnId FROM ItemDependency d "
                    + "WHERE d.itemId = :item AND d.status = :status "
                    + "ORDER BY d.dependsOnId", UUID.class)
            .setParameter(P_ITEM, itemId)
            .setParameter("status", ItemDependency.ASSERTED)
            .getResultList();
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /**
     * Inserts an item and flushes, so a constraint the table holds is reported
     * at the call site rather than at commit — which is on the far side of the
     * typed refusal model.
     */
    @Transactional
    public Item insert(Item item) {
        em.persist(item);
        em.flush();
        return item;
    }

    /** Inserts a dependency edge. Flushed by the caller, once, after the set. */
    @Transactional
    public void insertEdge(ItemDependency edge) {
        em.persist(edge);
    }

    /** Flushes pending changes for the same reason {@link #insert} does. */
    @Transactional
    public void flush() {
        em.flush();
    }

    /**
     * Flushes, then re-reads the row.
     *
     * <p>Both, and in this order. The columns the database fills — the
     * modification date above all — are not in the persistence context until
     * the statement has run, and a projection taken before the refresh would
     * report the values the caller sent rather than the ones that were stored.
     */
    @Transactional
    public void flushAndRefresh(Item item) {
        em.flush();
        em.refresh(item);
    }
}
