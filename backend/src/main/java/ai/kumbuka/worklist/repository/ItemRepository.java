/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.repository;

import ai.kumbuka.worklist.domain.Item;
import ai.kumbuka.worklist.domain.ItemReference;
import ai.kumbuka.worklist.domain.ItemRelation;
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

    private static final String P_STATUS = "status";

    /** The filter keys {@link #inScope(UUID, java.util.Map, Item, int)} narrows on. */
    public static final String BY_STATUS = "status_pk";
    public static final String BY_MILESTONE = "milestone_number";
    public static final String BY_WORKSTREAM = "workstream_number";

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
     *
     * <p>Ties on the creation instant are broken by the number, which is unique
     * in a scope and allocated in creation order — not by the id, which is
     * random and would order an imported batch sharing one instant at random.
     * The paged read below orders the same way, so a walk over its pages and
     * this whole-set read answer the same sequence.
     */
    @Transactional
    public List<Item> inScope(UUID scopeId) {
        return em.createQuery(
                "SELECT i FROM Item i WHERE i.scopeId = :scope ORDER BY i.createdAt, i.number",
                Item.class)
            .setParameter(P_SCOPE, scopeId)
            .getResultList();
    }

    /**
     * One page of the items of a scope that match the given filter, oldest
     * first, continuing after a given item.
     *
     * <p>Kept separate from {@link #inScope(UUID)} so the calling paths are
     * obviously two: one for the whole set and one for a narrowed page.
     *
     * <p><strong>Enumerated columns only</strong>, each an equality on a key the
     * domain resolved from the caller's name, number or token:
     * {@link #BY_STATUS}, {@link #BY_MILESTONE}, {@link #BY_WORKSTREAM}. A key
     * not among them is a defect of the caller above and fails loudly rather
     * than being dropped, because a dropped filter answers the whole set.
     *
     * <p>The continuation is a keyset on the order itself, {@code (created_at,
     * number)}: the page starts strictly after the given item's position,
     * whether or not that item still passes the filter.
     *
     * <p>At most {@code limit + 1} rows are returned. The extra row is not
     * answered; it is what the domain reads to know that more follows, without
     * a second query.
     *
     * @param after the last item of the previous page, or null for the first
     */
    @Transactional
    public List<Item> inScope(UUID scopeId, java.util.Map<String, Object> filter, Item after,
                              int limit) {
        StringBuilder jpql = new StringBuilder(
            "SELECT i FROM Item i WHERE i.scopeId = :scope");
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        params.put(P_SCOPE, scopeId);

        for (java.util.Map.Entry<String, Object> entry : filter.entrySet()) {
            String param = "f_" + params.size();
            String column = switch (entry.getKey()) {
                case BY_STATUS -> "i.statusPk";
                case BY_MILESTONE -> "i.milestoneNumber";
                case BY_WORKSTREAM -> "i.workstreamNumber";
                default -> throw new IllegalArgumentException(
                    "the filter key '" + entry.getKey() + "' is not one this query "
                        + "narrows on — the domain must refuse it above rather than "
                        + "sending it here");
            };
            jpql.append(" AND ").append(column).append(" = :").append(param);
            params.put(param, entry.getValue());
        }
        if (after != null) {
            jpql.append(" AND (i.createdAt > :afterAt"
                + " OR (i.createdAt = :afterAt AND i.number > :afterNumber))");
            params.put("afterAt", after.createdAt);
            params.put("afterNumber", after.number);
        }
        jpql.append(" ORDER BY i.createdAt, i.number");

        var query = em.createQuery(jpql.toString(), Item.class);
        params.forEach(query::setParameter);
        return query.setMaxResults(limit + 1).getResultList();
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
     * <p>An item's number is unique within its scope (V18), so scope and
     * number name one item; the view selector takes no part in it any more.
     */
    @Transactional
    public Item byAddress(UUID scopeId, long number) {
        return em.createQuery(
                "SELECT i FROM Item i WHERE i.scopeId = :scope AND i.number = :number",
                Item.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter("number", number)
            .getResultStream()
            .findFirst()
            .orElse(null);
    }

    /** Every relation out of an item, asserted and withdrawn alike. */
    @Transactional
    public List<ItemRelation> edgesOf(Item item) {
        return em.createQuery(
                "SELECT r FROM ItemRelation r WHERE r.scopeId = :scope "
                    + "AND r.fromItemNumber = :item", ItemRelation.class)
            .setParameter(P_SCOPE, item.scopeId)
            .setParameter(P_ITEM, item.number)
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
    public List<ItemRelation> assertedRelations(Item item) {
        return em.createQuery(
                "SELECT r FROM ItemRelation r "
                    + "WHERE r.scopeId = :" + P_SCOPE
                    + " AND r.fromItemNumber = :" + P_ITEM
                    + " AND r.status = :" + P_STATUS
                    + " ORDER BY r.toItemNumber, r.relationTypePk", ItemRelation.class)
            .setParameter(P_SCOPE, item.scopeId)
            .setParameter(P_ITEM, item.number)
            .setParameter(P_STATUS, ItemRelation.ASSERTED)
            .getResultList();
    }

    /** Every external pointer of an item, asserted and withdrawn alike. */
    @Transactional
    public List<ItemReference> referencesOf(Item item) {
        return em.createQuery(
                "SELECT r FROM ItemReference r WHERE r.scopeId = :scope "
                    + "AND r.itemNumber = :item ORDER BY r.ordinal", ItemReference.class)
            .setParameter(P_SCOPE, item.scopeId)
            .setParameter(P_ITEM, item.number)
            .getResultList();
    }

    /** The asserted pointers only, in the reader's order. */
    @Transactional
    public List<ItemReference> assertedReferences(Item item) {
        return em.createQuery(
                "SELECT r FROM ItemReference r "
                    + "WHERE r.scopeId = :" + P_SCOPE
                    + " AND r.itemNumber = :" + P_ITEM
                    + " AND r.status = :" + P_STATUS + " ORDER BY r.ordinal",
                ItemReference.class)
            .setParameter(P_SCOPE, item.scopeId)
            .setParameter(P_ITEM, item.number)
            .setParameter(P_STATUS, ItemReference.ASSERTED)
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
