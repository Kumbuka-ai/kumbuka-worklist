package ai.kumbuka.worklist.tenancy;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.UUID;

/**
 * The substrate's tenant resolver: one configured tenant for the whole
 * deployment.
 *
 * <p>The service authenticates against the tenant realm, so an authenticated
 * caller is by construction a caller of this deployment's tenant. Deriving a
 * per-request tenant from an organisation claim is the multi-tenant edition's
 * business and is deliberately absent here: the substrate establishes the
 * axis and its enforcement, not the directory that populates it. Everything
 * downstream — the ORM filter, the GUC, the policy — is indifferent to which
 * of the two produced the value, which is why the second can be added later
 * without touching any of them.
 */
@ApplicationScoped
public class ConfiguredTenantResolver implements TenantResolver {

    @ConfigProperty(name = "worklist.tenant-id")
    UUID tenantId;

    @Override
    public UUID currentTenant() {
        return tenantId;
    }
}
