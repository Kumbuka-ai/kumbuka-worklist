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

    /** The declared status of that identity, or null. */
    @Transactional
    public ItemStatus statusById(UUID id) {
        return id == null ? null : em.find(ItemStatus.class, id);
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

    /** The definition of that identity, or null. */
    @Transactional
    public AttributeDefinition definitionById(UUID id) {
        return id == null ? null : em.find(AttributeDefinition.class, id);
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

    /** The option of that identity, or null. */
    @Transactional
    public AttributeOption optionById(UUID id) {
        return id == null ? null : em.find(AttributeOption.class, id);
    }

    /** Every option of a definition, by rank then name. */
    @Transactional
    public List<AttributeOption> optionsOf(UUID definitionId) {
        return em.createQuery(
                "SELECT o FROM AttributeOption o WHERE o.definitionId = :definition "
                    + "ORDER BY o.rank, o.name", AttributeOption.class)
            .setParameter("definition", definitionId)
            .getResultList();
    }

    // ------------------------------------------------------------------
    // Relation types
    // ------------------------------------------------------------------

    /** The relation type of that identity, or null. */
    @Transactional
    public RelationType relationTypeById(UUID id) {
        return id == null ? null : em.find(RelationType.class, id);
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
}
