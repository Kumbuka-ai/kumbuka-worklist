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
                SELECT scope_id, tenant_id, slug, archived, kind, locked, can_write
                FROM platform.scope_access
                WHERE slug = :slug
                """)
            .setParameter("slug", slug)
            .getResultList();

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rowOf(rows.get(0)));
    }

    /**
     * The access row a scope id points at, as the bound subject sees it, or
     * empty.
     *
     * <p>The reverse of {@link #findBySlug}. The projections need it because
     * the wire form of {@code scope} is the slug and the domain works in
     * {@code scopeId} — turning one back into the other on the read is what
     * lets the response present the label without the domain forgetting the
     * identity.
     */
    @Transactional
    public Optional<ScopeAccessRow> findByScopeId(UUID scopeId) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT scope_id, tenant_id, slug, archived, kind, locked, can_write
                FROM platform.scope_access
                WHERE scope_id = :scopeId
                """)
            .setParameter("scopeId", scopeId)
            .getResultList();

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rowOf(rows.get(0)));
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
     * One row of the view, read off its columns in their published order.
     *
     * <p>Kept in one place because the two queries above project the same
     * seven columns, and two hand-written projections of one row is where a
     * column added to the view reaches one caller and not the other.
     */
    private static ScopeAccessRow rowOf(Object[] row) {
        return new ScopeAccessRow(
            (UUID) row[0],
            (UUID) row[1],
            (String) row[2],
            (Boolean) row[3],
            (String) row[4],
            (Boolean) row[5],
            (Boolean) row[6]);
    }

    /**
     * One row of the read contract, as it comes off the view.
     *
     * <p>Distinct from the directory's own {@code ScopeAccess} on purpose.
     * Keeping them apart is what lets the published shape of the view change
     * without the type the domain reads changing with it.
     *
     * <p>The last three arrived with V24 of the core (pinned at v0.10.0) and
     * are the reason this service can answer three questions it previously
     * could not: which KIND of scope it was handed, whether the scope's
     * content is LOCKED, and whether the calling subject may WRITE through a
     * service channel at all. Before V24 the view filtered on
     * {@code kind = 'project'} and published none of the three, so a private
     * scope was simply invisible here and a write into a scope nobody may
     * write went through.
     *
     * @param kind     {@code project}, {@code private} or {@code global}
     * @param locked   the content lock — a frozen scope, as distinct from an
     *                 {@code archived} (retired) one
     * @param canWrite the calling subject's write right OVER A SERVICE
     *                 CHANNEL. V24 derives it as
     *                 {@code NOT locked AND (kind = 'private' OR NOT muted)},
     *                 so a locked scope always arrives with it false — which
     *                 is why the directory judges {@code locked} first and the
     *                 more specific refusal is reachable at all.
     */
    public record ScopeAccessRow(UUID scopeId, UUID tenantId, String slug, boolean archived,
                                 String kind, boolean locked, boolean canWrite) {
    }
}
