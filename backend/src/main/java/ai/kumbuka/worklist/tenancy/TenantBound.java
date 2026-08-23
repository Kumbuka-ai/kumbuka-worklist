package ai.kumbuka.worklist.tenancy;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class or method as tenant-bound: {@link TenantBindingInterceptor}
 * sets the PostgreSQL session GUC {@code app.tenant_id} at the start of the
 * method, immediately after the transaction has been opened.
 *
 * <p>Applied at class level it covers every method, which is how the HTTP
 * surface is annotated — per-method discipline is the kind that is kept
 * until the day it is not.
 */
@InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface TenantBound {
    @Nonbinding boolean value() default true;
}
