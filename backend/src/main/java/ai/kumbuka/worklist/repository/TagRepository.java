/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.repository;

import ai.kumbuka.worklist.domain.ItemTag;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every statement about tags: the platform's vocabulary, read through its
 * view, and the assignments this service keeps in {@code worklist.item_tag}.
 *
 * <p>As in {@link ItemRepository}, refusals stay above. A token that names no
 * tag is an empty answer here; whether that refuses a write or answers an
 * empty page is the caller's to say.
 *
 * <h2>The vocabulary is read through the view, natively</h2>
 *
 * {@code platform.tag_access} is the platform's, as {@code platform.scope_access}
 * is, and this service holds {@code SELECT} on it and nothing on
 * {@code platform.tag}. It publishes only the tags of scopes the bound
 * subject may see, so a token of a scope the caller cannot see is a token
 * that is not there. No entity maps the view, for the reason
 * {@link ScopeAccessRepository} gives: an entity would make it look like a
 * table this service could write.
 *
 * <p>The assignments are read through JPQL; only the view is native.
 */
@ApplicationScoped
@TenantBound
public class TagRepository {

    private static final String P_SCOPE = "scope";

    private static final String P_ITEM = "item";

    @Inject EntityManager em;

    /**
     * The tag of one token in one scope's vocabulary, as the bound subject
     * sees it, withdrawn tags included; empty where the scope declares no such
     * token or the subject cannot see the scope.
     */
    @Transactional
    public Optional<TagRow> inVocabulary(UUID scopeId, String token) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT tag_id, status
                FROM platform.tag_access
                WHERE scope_id = :scope AND token = :token
                """)
            .setParameter(P_SCOPE, scopeId)
            .setParameter("token", token)
            .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        return Optional.of(new TagRow((UUID) row[0], (String) row[1]));
    }

    /**
     * The tokens of these tags, ascending, withdrawn tags included.
     *
     * <p>Asked only with a non-empty list: an item without tags answers its
     * empty list without reading the view at all.
     */
    @Transactional
    public List<String> tokensOf(List<UUID> tagIds) {
        List<Object> tokens = em.createNativeQuery("""
                SELECT token
                FROM platform.tag_access
                WHERE tag_id IN (:tags)
                ORDER BY token
                """)
            .setParameter("tags", tagIds)
            .getResultList();
        return tokens.stream().map(String::valueOf).toList();
    }

    /** The tags one item carries, by the platform's identity. */
    @Transactional
    public List<UUID> attachedTo(UUID scopeId, long itemNumber) {
        return em.createQuery(
                "SELECT t.tagId FROM ItemTag t WHERE t.scopeId = :scope "
                    + "AND t.itemNumber = :item", UUID.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_ITEM, itemNumber)
            .getResultList();
    }

    /** The assignment of one tag to one item, if there is one. */
    @Transactional
    public Optional<ItemTag> find(UUID scopeId, long itemNumber, UUID tagId) {
        return Optional.ofNullable(em.find(ItemTag.class,
            new ItemTag.Key(scopeId, itemNumber, tagId)));
    }

    /** Inserts an assignment and flushes, so a constraint answers here. */
    @Transactional
    public void insert(ItemTag assignment) {
        em.persist(assignment);
        em.flush();
    }

    /**
     * Removes an assignment and flushes. The one DELETE this service issues;
     * V26 grants it on this table alone.
     */
    @Transactional
    public void remove(ItemTag assignment) {
        em.remove(assignment);
        em.flush();
    }

    /**
     * One tag of the vocabulary, as the view publishes it.
     *
     * @param status {@code declared} or {@code withdrawn}, as the platform
     *               spells them
     */
    public record TagRow(UUID tagId, String status) {

        /** The platform's word for a tag that takes no new assignment. */
        public static final String WITHDRAWN = "withdrawn";

        /** Whether the tag takes no new assignment. */
        public boolean withdrawn() {
            return WITHDRAWN.equals(status);
        }
    }
}
