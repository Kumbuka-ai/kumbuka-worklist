package ai.kumbuka.worklist.tenancy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/**
 * A thread-local stack of bound tenants. The bottom of the stack resolves to
 * {@link TenantResolver}; an explicit {@link #bind(UUID)} pushes on top and
 * the returned handle pops it.
 *
 * <p>The bean is application-scoped and the state is thread-local, so one
 * instance serves every worker thread. The close-returning idiom is what
 * keeps that correct across thread reuse: a pooled worker must not inherit
 * the previous request's tenant, and a request filter that unbinds in a
 * {@code finally} is the only thing standing between it and that.
 *
 * <p>This class is the only place that reads {@link TenantResolver}.
 */
@ApplicationScoped
public class ThreadLocalTenantContext implements TenantContext {

    @Inject TenantResolver resolver;

    private final ThreadLocal<Deque<UUID>> stack = ThreadLocal.withInitial(ArrayDeque::new);

    @Override
    public UUID current() {
        UUID bound = stack.get().peek();
        if (bound != null) {
            return bound;
        }
        UUID resolved = resolver.currentTenant();
        if (resolved == null) {
            throw new IllegalStateException(
                "TenantResolver returned null — every request must resolve a tenant");
        }
        return resolved;
    }

    @Override
    public AutoCloseable bind(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        stack.get().push(tenantId);
        return new Unbind(tenantId);
    }

    private final class Unbind implements AutoCloseable {
        private final UUID expected;
        private boolean closed;

        Unbind(UUID expected) {
            this.expected = expected;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            Deque<UUID> dq = stack.get();
            UUID top = dq.peek();
            if (top == null || !top.equals(expected)) {
                // Out-of-order unbind. Re-pushing would corrupt the stack and
                // leave a foreign tenant bound on a pooled thread, so this
                // fails loudly instead of repairing itself into a leak.
                //
                // The handle is NOT marked closed here, and the order of these
                // two statements is the whole point. Marking first would spend
                // the handle on the attempt that failed: the caller unwinds in
                // the right order afterwards, this close is then a no-op, and
                // the binding stays on the thread for every later request to
                // inherit. The failure would have caused the leak it exists to
                // prevent.
                throw new IllegalStateException(
                    "tenant bind/unbind out of order: expected=" + expected + " top=" + top);
            }
            closed = true;
            dq.pop();
            if (dq.isEmpty()) {
                stack.remove();
            }
        }
    }
}
