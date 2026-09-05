package ai.kumbuka.worklist.repository;

import ai.kumbuka.worklist.domain.Item;
import ai.kumbuka.worklist.domain.ItemReference;
import ai.kumbuka.worklist.domain.ItemRelation;
import ai.kumbuka.worklist.domain.Selector;
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
     * <p>Ordering by creation and not by the sort key of the contract. That
     * sort ranks by milestone and by a declared attribute, and ordering by a
     * declared attribute is a capability a scope declares rather than a
     * property every attribute has for free — the containment index answers
     * filters and does not order. A partial implementation of a documented
     * order is worse than an obviously different one.
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

    /**
     * The item at one address, or null.
     *
     * <p>The store is keyed by a surrogate id and the surface addresses by
     * number, so something has to turn the second into the first. It is here
     * rather than in the surface because a lookup by a stored value is a read
     * of this schema, and a surface that reconstructed it by walking the
     * scope's items would be a second reader of the same index — one that gets
     * slower with the corpus and answers the same question worse.
     *
     * <p><strong>The selector is part of the query and not an assumption.</strong>
     * Under scope-wide allocation the number alone would already identify the
     * row, and matching on the selector as well is what makes an address whose
     * view does not fit the object a not-found instead of a second address
     * resolving to it. Uniqueness in this store is the triple scope, selector
     * and number under both allocation modes, and this reads it as the triple.
     */
    @Transactional
    public Item byAddress(UUID scopeId, UUID selectorId, long number) {
        return em.createQuery(
                "SELECT i FROM Item i WHERE i.scopeId = :scope "
                    + "AND i.selectorId = :selector AND i.number = :number", Item.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter("selector", selectorId)
            .setParameter("number", number)
            .getResultStream()
            .findFirst()
            .orElse(null);
    }

    /** The selector of that id, or null. */
    @Transactional
    public Selector selectorById(UUID selectorId) {
        return selectorId == null ? null : em.find(Selector.class, selectorId);
    }

    /** Every relation out of an item, asserted and withdrawn alike. */
    @Transactional
    public List<ItemRelation> edgesOf(UUID itemId) {
        return em.createQuery(
                "SELECT r FROM ItemRelation r WHERE r.fromItemId = :item",
                ItemRelation.class)
            .setParameter(P_ITEM, itemId)
            .getResultList();
    }

    /**
     * The asserted relations only, sorted. A withdrawn edge is history, not a
     * relation.
     *
     * <p>Sorted in the query, so that the answer is stable across reads.
     * Without that, a caller who re-sent a read answer would present the same
     * set in another order, and a comparison would report a change the caller
     * never made — the item would take a fresh modification date and a rotated
     * token for a write that changed nothing.
     *
     * <p>By target then type, which is the order the caller-facing
     * normalisation uses too. Two orders would be two places for the same
     * comparison to disagree.
     */
    @Transactional
    public List<ItemRelation> assertedRelations(UUID itemId) {
        return em.createQuery(
                "SELECT r FROM ItemRelation r "
                    + "WHERE r.fromItemId = :item AND r.status = :status "
                    + "ORDER BY r.toItemId, r.relationTypeId", ItemRelation.class)
            .setParameter(P_ITEM, itemId)
            .setParameter("status", ItemRelation.ASSERTED)
            .getResultList();
    }

    /** Every external pointer of an item, asserted and withdrawn alike. */
    @Transactional
    public List<ItemReference> referencesOf(UUID itemId) {
        return em.createQuery(
                "SELECT r FROM ItemReference r WHERE r.itemId = :item ORDER BY r.ordinal",
                ItemReference.class)
            .setParameter(P_ITEM, itemId)
            .getResultList();
    }

    /** The asserted pointers only, in the reader's order. */
    @Transactional
    public List<ItemReference> assertedReferences(UUID itemId) {
        return em.createQuery(
                "SELECT r FROM ItemReference r "
                    + "WHERE r.itemId = :item AND r.status = :status ORDER BY r.ordinal",
                ItemReference.class)
            .setParameter(P_ITEM, itemId)
            .setParameter("status", ItemReference.ASSERTED)
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

    /** Inserts a relation. Flushed by the caller, once, after the whole set. */
    @Transactional
    public void insertEdge(ItemRelation edge) {
        em.persist(edge);
    }

    /**
     * Inserts a reference entry. Flushed by the caller, once, after the list.
     *
     * <p>There is no counterpart that removes one, and there cannot be: this
     * schema grants DELETE nowhere. A list that shrinks is rewritten in place
     * and its tail is carried by the entries that remain — see
     * {@code ItemService} on why the ordinal is dense.
     */
    @Transactional
    public void insertReference(ItemReference reference) {
        em.persist(reference);
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
