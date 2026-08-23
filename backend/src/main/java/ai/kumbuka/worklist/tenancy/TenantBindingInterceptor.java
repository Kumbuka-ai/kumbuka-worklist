package ai.kumbuka.worklist.tenancy;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;

/**
 * Sets {@code app.tenant_id} once per transaction the application opens.
 *
 * <p><strong>The priority is load-bearing and is the whole content of this
 * class.</strong> CDI invokes lower-priority interceptors first, i.e.
 * outermost. Narayana's {@code @Transactional} interceptor sits at
 * {@code PLATFORM_BEFORE + 200}; anything below that runs BEFORE the
 * transaction is open, finds no envelope for {@code SET LOCAL}, and skips the
 * binding silently. Row-level security then hides every row and the surface
 * above returns empty results with no error anywhere — a failure that reads
 * as missing data rather than as broken isolation, and one that has cost this
 * codebase's predecessor a long hunt. {@code +300} runs after the transaction
 * opens and before the method body.
 */
@Interceptor
@TenantBound
@Priority(Interceptor.Priority.PLATFORM_BEFORE + 300)
public class TenantBindingInterceptor {

    @Inject TenantDatabaseBinding binding;

    @AroundInvoke
    public Object invoke(InvocationContext ctx) throws Exception {
        binding.bindCurrentTransaction();
        return ctx.proceed();
    }
}
