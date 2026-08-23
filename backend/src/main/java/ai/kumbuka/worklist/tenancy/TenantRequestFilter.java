package ai.kumbuka.worklist.tenancy;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

import java.util.UUID;

/**
 * Pins the tenant for the duration of a request and unbinds it after the
 * response, including on the exception path.
 *
 * <p>Runs after authentication ({@code AUTHENTICATION + 100}) so the security
 * identity is established before the tenant is resolved against it.
 *
 * <p>The database GUC is not set here. It belongs inside the transaction,
 * which opens later, per transactional method — see
 * {@link TenantBindingInterceptor}.
 */
@Provider
@Priority(Priorities.AUTHENTICATION + 100)
public class TenantRequestFilter implements ContainerRequestFilter, ContainerResponseFilter {

    static final String CONTEXT_HANDLE = "ai.kumbuka.worklist.tenancy.bound-handle";

    @Inject TenantContext context;
    @Inject TenantResolver resolver;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        UUID tenant = resolver.currentTenant();
        requestContext.setProperty(CONTEXT_HANDLE, context.bind(tenant));
    }

    @Override
    public void filter(ContainerRequestContext requestContext,
                       ContainerResponseContext responseContext) {
        AutoCloseable handle = (AutoCloseable) requestContext.getProperty(CONTEXT_HANDLE);
        if (handle == null) {
            return;
        }
        try {
            handle.close();
        } catch (Exception e) {
            // A failed unbind leaves a foreign tenant on a pooled thread.
            // There is no quiet recovery from that, so it becomes a 500.
            throw new IllegalStateException("tenant unbind failed", e);
        } finally {
            requestContext.removeProperty(CONTEXT_HANDLE);
        }
    }
}
