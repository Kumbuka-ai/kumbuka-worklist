package ai.kumbuka.worklist.tenancy;

import java.util.UUID;

/**
 * The single source of truth for the effective tenant.
 *
 * <p>Both the Hibernate tenant lookup and the PostgreSQL GUC setter read
 * {@link #current()} and never {@link TenantResolver} directly, so a
 * programmatic {@link #bind(UUID)} steers the ORM filter and the database
 * policy together. A binding that moved only one of the two would be worse
 * than none: the query would carry one tenant's predicate and the policy the
 * other's, and the result — an empty set — looks exactly like correct
 * isolation.
 *
 * <p>Callers with no incoming request use {@link #bind(UUID)}: migrations,
 * background work, tests. Binds nest, and the returned handle pops exactly
 * the binding it pushed.
 *
 * <pre>{@code
 * try (var bound = tenantContext.bind(tenantId)) {
 *     // ORM filter and app.tenant_id both pinned to tenantId
 * }
 * }</pre>
 */
public interface TenantContext {

    /**
     * @return the effective tenant for this thread: the most recent
     *         {@link #bind(UUID)} not yet closed, or what the configured
     *         {@link TenantResolver} returns when nothing is bound. Never
     *         null.
     */
    UUID current();

    /**
     * Pin the effective tenant for the current thread until the returned
     * handle is closed.
     *
     * @param tenantId non-null tenant to bind
     * @return a handle that unbinds on close
     */
    AutoCloseable bind(UUID tenantId);
}
