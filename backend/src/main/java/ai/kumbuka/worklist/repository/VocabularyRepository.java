package ai.kumbuka.worklist.repository;

import ai.kumbuka.worklist.domain.AttributeDefinition;
import ai.kumbuka.worklist.domain.AttributeOption;
import ai.kumbuka.worklist.domain.ItemStatus;
import ai.kumbuka.worklist.domain.RelationType;
import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Every statement issued against the four declaration tables.
 *
 * <p>One repository for four tables, and that is deliberate. The concept
 * describes ONE kind of object — a declared value with an identity, a display
 * name, a rank and an optional description — and the schema carries three
 * tables for it only because the platform properties differ: a status carries
 * the four predicates, a relation type carries {@code blocks}, an option
 * carries neither and belongs to a definition. What survives of "one kind of
 * object" is the naming and the behaviour, and this class is where that
 * survival is visible: the same lookups, the same withdrawal rule, the same
 * ordering by rank then name.
 *
 * <p>Refusals stay above, as in the other two repositories. A lookup that
 * finds nothing returns null, and the caller decides whether that is an
 * undeclared value, an ordinary absence, or a key that has no declaration
 * yet.
 */
@ApplicationScoped
@TenantBound
public class VocabularyRepository {

    private static final String P_SCOPE = "scope";

    private static final String P_KEY = "key";

    @Inject EntityManager em;

    // ------------------------------------------------------------------
    // Statuses
    // ------------------------------------------------------------------

    /**
     * The declared status of that surrogate, or null.
     *
     * <p>The surrogate is what an item's {@code status_pk} holds (ADR-0042),
     * so this is the lookup a reference resolves through. The tenant is bound
     * by row-level security; scope membership is checked by the caller.
     */
    @Transactional
    public ItemStatus statusByPk(Long pk) {
        return pk == null ? null : byPk(ItemStatus.class, pk);
    }

    /**
     * The declared status a scope carries under that name, or null.
     *
     * <p>The wire form of status is the display name, and the write path
     * needs the reverse of {@link #statusByPk} to turn a name back into an
     * identity. Names are unique per scope on the declaration table, so at
     * most one row answers.
     */
    @Transactional
    public ItemStatus statusByName(UUID scopeId, String name) {
        if (scopeId == null || name == null || name.isBlank()) {
            return null;
        }
        try {
            return em.createQuery(
                    "SELECT s FROM ItemStatus s WHERE s.scopeId = :scope "
                        + "AND s.name = :name", ItemStatus.class)
                .setParameter(P_SCOPE, scopeId)
                .setParameter("name", name)
                .getSingleResult();
        } catch (NoResultException absent) {
            return null;
        }
    }

    /** Every status a scope declared, by rank then name. */
    @Transactional
    public List<ItemStatus> statusesIn(UUID scopeId) {
        return em.createQuery(
                "SELECT s FROM ItemStatus s WHERE s.scopeId = :scope "
                    + "ORDER BY s.rank, s.name", ItemStatus.class)
            .setParameter(P_SCOPE, scopeId)
            .getResultList();
    }

    // ------------------------------------------------------------------
    // Attribute definitions and their options
    // ------------------------------------------------------------------

    /** The definition of that key in a scope, or null. */
    @Transactional
    public AttributeDefinition definitionByKey(UUID scopeId, String key) {
        try {
            return em.createQuery(
                    "SELECT d FROM AttributeDefinition d WHERE d.scopeId = :scope "
                        + "AND d.key = :key", AttributeDefinition.class)
                .setParameter(P_SCOPE, scopeId)
                .setParameter(P_KEY, key)
                .getSingleResult();
        } catch (NoResultException absent) {
            return null;
        }
    }

    /** The definition of that surrogate, or null. */
    @Transactional
    public AttributeDefinition definitionByPk(Long pk) {
        return pk == null ? null : byPk(AttributeDefinition.class, pk);
    }

    /** Every definition a scope declared, by rank then key. */
    @Transactional
    public List<AttributeDefinition> definitionsIn(UUID scopeId) {
        return em.createQuery(
                "SELECT d FROM AttributeDefinition d WHERE d.scopeId = :scope "
                    + "ORDER BY d.rank, d.key", AttributeDefinition.class)
            .setParameter(P_SCOPE, scopeId)
            .getResultList();
    }

    /** The option of that surrogate, or null. */
    @Transactional
    public AttributeOption optionByPk(Long pk) {
        return pk == null ? null : byPk(AttributeOption.class, pk);
    }

    /**
     * The option of a definition under that name, or null.
     *
     * <p>Names are unique per definition since V18, so at most one row answers.
     */
    @Transactional
    public AttributeOption optionByName(Long definitionPk, String name) {
        try {
            return em.createQuery(
                    "SELECT o FROM AttributeOption o WHERE o.definitionPk = :definition "
                        + "AND o.name = :name", AttributeOption.class)
                .setParameter("definition", definitionPk)
                .setParameter("name", name)
                .getSingleResult();
        } catch (NoResultException absent) {
            return null;
        }
    }

    /** Every option of a definition, by rank then name. */
    @Transactional
    public List<AttributeOption> optionsOf(Long definitionPk) {
        return em.createQuery(
                "SELECT o FROM AttributeOption o WHERE o.definitionPk = :definition "
                    + "ORDER BY o.rank, o.name", AttributeOption.class)
            .setParameter("definition", definitionPk)
            .getResultList();
    }

    // ------------------------------------------------------------------
    // Relation types
    // ------------------------------------------------------------------

    /** The relation type of that surrogate, or null. */
    @Transactional
    public RelationType relationTypeByPk(Long pk) {
        return pk == null ? null : byPk(RelationType.class, pk);
    }

    /**
     * The relation type a scope carries under that name, or null.
     *
     * <p>Reverse of {@link #relationTypeByPk}. Names are unique per scope on
     * the declaration table, so at most one row answers.
     */
    @Transactional
    public RelationType relationTypeByName(UUID scopeId, String name) {
        if (scopeId == null || name == null || name.isBlank()) {
            return null;
        }
        try {
            return em.createQuery(
                    "SELECT r FROM RelationType r WHERE r.scopeId = :scope "
                        + "AND r.name = :name", RelationType.class)
                .setParameter(P_SCOPE, scopeId)
                .setParameter("name", name)
                .getSingleResult();
        } catch (NoResultException absent) {
            return null;
        }
    }

    /** Every relation type a scope declared, by rank then name. */
    @Transactional
    public List<RelationType> relationTypesIn(UUID scopeId) {
        return em.createQuery(
                "SELECT r FROM RelationType r WHERE r.scopeId = :scope "
                    + "ORDER BY r.rank, r.name", RelationType.class)
            .setParameter(P_SCOPE, scopeId)
            .getResultList();
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    /** Inserts a declared value and flushes, so its constraints answer here. */
    @Transactional
    public <T> T insert(T declaration) {
        em.persist(declaration);
        em.flush();
        return declaration;
    }

    /** Flushes a status or rank change so the table's constraints answer here. */
    @Transactional
    public void flush() {
        em.flush();
    }

    /** A declared value by its surrogate; the tenant is bound by row-level security. */
    private <T> T byPk(Class<T> type, Long pk) {
        return em.createQuery(
                "SELECT v FROM " + type.getSimpleName() + " v WHERE v.pk = :pk", type)
            .setParameter("pk", pk)
            .getResultStream()
            .findFirst()
            .orElse(null);
    }
}
