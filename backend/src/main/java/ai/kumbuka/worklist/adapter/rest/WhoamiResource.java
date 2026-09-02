package ai.kumbuka.worklist.adapter.rest;

import ai.kumbuka.worklist.tenancy.TenantContext;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;

/**
 * The substrate's only authenticated surface: it answers who the caller is
 * and which tenant the request was bound to.
 *
 * <p>It exists because the realm binding is otherwise unprovable. A service
 * with no protected path accepts and rejects nothing, and "the service is
 * bound to the tenant realm" would be a configuration line nobody has ever
 * seen act. Here a token from the tenant realm reaches the body, and a token
 * from any other issuer does not get this far — the refusal happens in the
 * authentication layer, before this class is entered.
 *
 * <p>It is deliberately not a verb of the worklist. It reads no item, it
 * writes nothing, and it survives into the domain half only if it is still
 * the cheapest way to observe the same fact.
 */
@Path("/api/whoami")
@Authenticated
public class WhoamiResource {

    /**
     * The endpoint logs that it was reached and nothing about who reached it.
     * The subject is exactly the field the convention keeps out of this
     * stream: correlation runs through a request id, never through a subject
     * id, because a second aggregatable record of who did what would be the
     * circumvention of not collecting it.
     */
    private static final Logger LOG = Logger.getLogger(WhoamiResource.class);

    @Inject SecurityIdentity identity;
    @Inject TenantContext tenantContext;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> whoami() {
        LOG.debug("whoami");
        return Map.of(
            "subject", identity.getPrincipal().getName(),
            "tenant", tenantContext.current().toString());
    }
}
