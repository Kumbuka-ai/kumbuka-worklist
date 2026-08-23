package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * The ORM path to {@link Item} — layer 1 of the enforcement model, given
 * something to enforce on.
 *
 * <p><strong>This is not the caller surface and is not the beginning of the
 * domain.</strong> The substrate's caller surface is
 * {@link ai.kumbuka.worklist.api.WhoamiResource} and the health endpoint, and
 * nothing here is reachable over HTTP. The worklist's verbs — state a call-in,
 * characterise it, plan it, claim it, work it, terminate it — arrive with the
 * domain half and will not be built out of these two methods.
 *
 * <p>It exists because layer 1 is otherwise unobservable. Hibernate's
 * {@code @TenantId} filter rewrites every statement the ORM builds, and that
 * claim can only be watched holding against a statement the ORM actually
 * built. Without a path through the ORM, every probe in this repository would
 * be raw SQL, every one of them would be measuring layer 2, and the sentence
 * "there are two layers" would be a description of the configuration rather
 * than an observation.
 *
 * <p>{@code @TenantBound} at class level, so the database GUC is set inside
 * the transaction for every method: the two layers move together or the ORM
 * filter and the policy disagree about who is asking, and the result — an
 * empty set — looks exactly like correct isolation.
 */
@ApplicationScoped
@TenantBound
public class ItemStore {

    /**
     * Bound by the same convention as every logger here: an address, a scope
     * id, a count. Never a title — that is the caller's content, and the
     * operator boundary of this service is a missing GRANT that a log shipper
     * would carry content straight past.
     */
    private static final Logger LOG = Logger.getLogger(ItemStore.class);

    @Inject EntityManager em;

    /**
     * State an item in a scope.
     *
     * <p>The tenant is not a parameter. It comes from the bound tenant
     * context, which is also what the policy checks the incoming row against —
     * an item whose tenant a caller could name would be an item a caller could
     * plant across the boundary.
     */
    @Transactional
    public Item create(UUID scopeId, String title) {
        Item item = new Item();
        item.scopeId = scopeId;
        item.title = title;
        em.persist(item);
        em.flush();
        LOG.debugf("item stated in scope %s", scopeId);
        return item;
    }

    /** Every item of the bound tenant in one scope, oldest first. */
    @Transactional
    public List<Item> inScope(UUID scopeId) {
        return em.createQuery(
                "SELECT i FROM Item i WHERE i.scopeId = :scope ORDER BY i.createdAt", Item.class)
            .setParameter("scope", scopeId)
            .getResultList();
    }
}
