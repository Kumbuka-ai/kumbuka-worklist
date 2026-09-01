package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * The vocabularies of a scope — cluster, type, priority, size — as data.
 *
 * <p>The predecessor holds all four as constants in its own source and gates
 * every row against them, which makes a customer's way of characterising work
 * into a release of this service. Here they are rows, declared per scope, and
 * this service's business is only that a value IS in the declared vocabulary.
 *
 * <p>The AXIS is still structure: each axis is a column on the item, so a
 * fifth is a schema change either way, and {@link Term#AXES} is where the
 * four are named.
 */
@ApplicationScoped
@TenantBound
public class TermRegistry {

    private static final Logger LOG = Logger.getLogger(TermRegistry.class);

    @Inject EntityManager em;

    /**
     * Declare a value on an axis.
     *
     * <p>Idempotent for the same reason declaring a selector is: the caller
     * is stating that the value should exist, and a retry after a timeout
     * should not have to distinguish "created" from "already there".
     */
    @Transactional
    public Term declare(UUID scopeId, String axis, String token, int ordinal) {
        requireAxis(axis);
        if (token == null || token.isBlank() || token.matches(".*\\s.*")) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "a term token carries no whitespace and is not empty. Refused on axis "
                    + axis + ": " + token,
                List.of(String.valueOf(token)));
        }

        Term existing = find(scopeId, axis, token);
        if (existing != null) {
            return existing;
        }

        Term term = new Term();
        term.scopeId = scopeId;
        term.axis = axis;
        term.token = token;
        term.ordinal = ordinal;
        em.persist(term);
        em.flush();

        LOG.infof("term %s declared on axis %s in scope %s", token, axis, scopeId);
        return term;
    }

    /**
     * Withdraw a value: it may not be set on anything new, and everything
     * already carrying it stays readable.
     *
     * <p>That second half is why this is a status and not a delete. An item
     * characterised as {@code SEC} two years ago has to keep saying so, or
     * its own history stops being legible.
     */
    @Transactional
    public Term withdraw(UUID scopeId, String axis, String token) {
        Term term = require(scopeId, axis, token);
        if (Term.WITHDRAWN.equals(term.status)) {
            return term;
        }
        term.status = Term.WITHDRAWN;
        em.flush();
        LOG.infof("term %s withdrawn on axis %s in scope %s", token, axis, scopeId);
        return term;
    }

    /** Every value on an axis in a scope, by rank then token. */
    @Transactional
    public List<Term> onAxis(UUID scopeId, String axis) {
        requireAxis(axis);
        return em.createQuery(
                "SELECT t FROM Term t WHERE t.scopeId = :scope AND t.axis = :axis "
                    + "ORDER BY t.ordinal, t.token", Term.class)
            .setParameter("scope", scopeId)
            .setParameter("axis", axis)
            .getResultList();
    }

    /**
     * The term of that token on that axis, or a typed refusal naming both.
     *
     * <p>Both, because the same token can legitimately exist on two axes and
     * a refusal that named only the token would look wrong to a caller who
     * can see the token right there in another vocabulary.
     */
    @Transactional
    public Term require(UUID scopeId, String axis, String token) {
        requireAxis(axis);
        Term term = find(scopeId, axis, token);
        if (term == null) {
            throw new WorklistException(
                WorklistException.Reason.TERM_UNDECLARED,
                "no term " + token + " is declared on axis " + axis + " in scope " + scopeId
                    + ". The vocabularies of a scope are its own data — declare the term "
                    + "before characterising an item with it",
                List.of(axis, String.valueOf(token)));
        }
        return term;
    }

    /**
     * The axis a term column belongs to, for the field of that name.
     *
     * <p>Here rather than in {@link Field} because it is a fact about the
     * vocabulary and not about the naming: the field enum says what a caller
     * may write, and this says which vocabulary answers for it.
     */
    public static String axisOf(Field field) {
        return switch (field) {
            case CLUSTER -> Term.CLUSTER;
            case TYPE -> Term.TYPE;
            case PRIORITY -> Term.PRIORITY;
            case SIZE -> Term.SIZE;
            default -> throw new IllegalArgumentException(
                field.canonicalName() + " is not a vocabulary field");
        };
    }

    private static void requireAxis(String axis) {
        if (!Term.AXES.contains(axis)) {
            throw new WorklistException(
                WorklistException.Reason.INVALID_VALUE,
                "there is no axis " + axis + ". The axes are " + Term.AXES
                    + " — they are structure rather than data, because each one is a "
                    + "column of its own on the item",
                List.of(String.valueOf(axis)));
        }
    }

    private Term find(UUID scopeId, String axis, String token) {
        try {
            return em.createQuery(
                    "SELECT t FROM Term t WHERE t.scopeId = :scope AND t.axis = :axis "
                        + "AND t.token = :token", Term.class)
                .setParameter("scope", scopeId)
                .setParameter("axis", axis)
                .setParameter("token", token)
                .getSingleResult();
        } catch (NoResultException absent) {
            return null;
        }
    }
}
