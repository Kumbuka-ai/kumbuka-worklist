package ai.kumbuka.worklist.repository;

import ai.kumbuka.worklist.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The three statements the platform read contract is reached through.
 *
 * <p>The relation is not this service's. {@code platform.scope_access} is a
 * view published by the platform, on which this service holds {@code SELECT}
 * and nothing else, and the directory above translates its answer into the
 * typed refusals a caller sees. What lives here is only the fact that the
 * access happens through JPA, which is what the persistence boundary is about:
 * the layer is defined by the mechanism, not by who owns the table.
 *
 * <h2>Native, and why every statement here is</h2>
 *
 * There is no entity for the view and there should not be one — an entity
 * would make it look like a table this service maps and could write. The two
 * session settings are {@code set_config} and {@code current_setting}, which
 * have no JPQL expression at all. This is the enumerated native case the rule
 * set provides for, and the reason is written here rather than assumed.
 */
@ApplicationScoped
@TenantBound
public class ScopeAccessRepository {

    @Inject EntityManager em;

    /**
     * The access row for a slug, as the bound subject sees it, or empty.
     *
     * <p>Empty is returned rather than refused: whether "the subject may not
     * see it" is a refusal or an ordinary absence is the directory's
     * statement, and it needs the session check that precedes this call to
     * know which.
     */
    @Transactional
    public Optional<ScopeAccessRow> findBySlug(String slug) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT scope_id, tenant_id, slug, archived
                FROM platform.scope_access
                WHERE slug = :slug
                """)
            .setParameter("slug", slug)
            .getResultList();

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        return Optional.of(new ScopeAccessRow(
            (UUID) row[0],
            (UUID) row[1],
            (String) row[2],
            (Boolean) row[3]));
    }

    /**
     * Binds the calling subject for this transaction.
     *
     * <p>{@code is_local = true} is the whole safety property: the value resets
     * at commit or rollback and cannot ride a pooled connection into the next
     * caller's transaction.
     */
    @Transactional
    public void bindSubject(String subject) {
        em.createNativeQuery("SELECT set_config('app.subject', :v, true)")
            .setParameter("v", subject)
            .getSingleResult();
    }

    /** The bound tenant of this transaction, or null when nothing is bound. */
    @Transactional
    public Object boundTenant() {
        return em.createNativeQuery(
            "SELECT NULLIF(current_setting('app.tenant_id', true), '')").getSingleResult();
    }

    /** The bound subject of this transaction, or null when nothing is bound. */
    @Transactional
    public Object boundSubject() {
        return em.createNativeQuery(
            "SELECT NULLIF(current_setting('app.subject', true), '')").getSingleResult();
    }

    /**
     * One row of the read contract, as it comes off the view.
     *
     * <p>Distinct from the directory's own {@code ScopeAccess} on purpose. The
     * two carry the same four values today; keeping them apart is what lets
     * the published shape of the view change without the type the domain reads
     * changing with it.
     */
    public record ScopeAccessRow(UUID scopeId, UUID tenantId, String slug, boolean archived) {
    }
}
