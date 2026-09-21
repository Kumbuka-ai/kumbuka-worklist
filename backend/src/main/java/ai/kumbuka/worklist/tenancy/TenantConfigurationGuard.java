package ai.kumbuka.worklist.tenancy;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.UUID;

/**
 * Refuses to start when the deployment's tenant was not configured.
 *
 * <h2>Why this one value is worth a guard of its own</h2>
 *
 * Every other layer of the tenant boundary enforces the axis it is handed:
 * the ORM filter on {@code @TenantId}, the {@code app.tenant_id} binding, the
 * row-level-security policy that keys on it. A wrong tenant id passes all
 * three — they would work exactly as designed, on somebody else's data. It is
 * the one mistake in this service that no policy can catch, which is why
 * DEC-0023's "a mandatory parameter has no default" is enforced here rather
 * than trusted.
 *
 * <h2>Why the raw string, and not an injected {@code UUID}</h2>
 *
 * Three readings of the key differ, and only one of them answers the question
 * this guard asks:
 *
 * <ul>
 *   <li>A plain {@code ${WORKLIST_TENANT_ID}} with no default fails for an
 *       unset variable — but an EMPTY one is counted as supplied by SmallRye,
 *       so the expression resolves to the empty string rather than failing.
 *       Measured in the platform repository against smallrye-config 3.16.0 on
 *       2026-09-20: this is the shape {@code docker compose config} produces
 *       for an unset variable referenced in a compose file, and it is not
 *       hypothetical.</li>
 *   <li>Injected as {@code UUID}, the sentinel and the empty string both fail
 *       in Quarkus' own configuration validation, before this guard runs —
 *       with a conversion message that names the key and says nothing about
 *       which environment variable sets it or what a correct value looks
 *       like.</li>
 *   <li>Read raw and judged here, each case gets the sentence it needs.</li>
 * </ul>
 *
 * <p>So the value travels as a {@code String} through
 * {@link ConfiguredTenantResolver} as well, and this guard is what stands
 * between that string and the assumption everything downstream makes about
 * it: that it is a real tenant somebody chose.
 *
 * <h2>Reach, stated rather than implied</h2>
 *
 * This refuses a value that is absent, blank, still the sentinel, or not a
 * uuid. It cannot tell a well-formed tenant id that is WRONG from the right
 * one — no configuration guard can — and it does not pretend to: that is what
 * the deployment's own bootstrap is for.
 */
@ApplicationScoped
public class TenantConfigurationGuard {

    /**
     * What the key resolves to when the environment variable is not supplied.
     *
     * <p>Spelled as the variable, plus {@code _UNSET}, so an operator reading
     * it in a log line is told which variable to set without a lookup.
     */
    public static final String SENTINEL = "WORKLIST_TENANT_ID_UNSET";

    /** The configuration key, and the environment variable behind it. */
    public static final String KEY = "worklist.tenant-id";
    public static final String ENV_VAR = "WORKLIST_TENANT_ID";

    @ConfigProperty(name = KEY)
    String tenantId;

    void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent event) {
        requireUsable(tenantId);
    }

    /**
     * Refuses the value, or returns the tenant it names.
     *
     * <p>Static and returning the parsed value so that the judgement and the
     * parse are one act: {@link ConfiguredTenantResolver} calls the same
     * method rather than repeating the conditions, and a condition that exists
     * once cannot disagree with itself.
     *
     * @throws TenantNotConfigured when the value is absent, blank, the
     *                             sentinel, or not a uuid
     */
    public static UUID requireUsable(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new TenantNotConfigured(
                "it carries no value at all. An EMPTY environment variable is not an "
                    + "unset one — SmallRye counts it as supplied, so the default in "
                    + "application.properties does not apply and what reaches the "
                    + "service is a blank tenant.");
        }
        if (SENTINEL.equals(raw.trim())) {
            throw new TenantNotConfigured(
                "it is still the sentinel " + SENTINEL + ", which stands in for an "
                    + "environment variable nobody supplied. This service runs one "
                    + "tenant per deployment and will not guess which — every other "
                    + "layer of the boundary would enforce the guess perfectly.");
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException notAUuid) {
            throw new TenantNotConfigured(
                "'" + raw + "' is not a uuid, and a tenant id is one.");
        }
    }

    /** The deployment's tenant was not configured usably. */
    public static class TenantNotConfigured extends RuntimeException {
        public TenantNotConfigured(String detail) {
            super(ENV_VAR + " is unset: the configuration key '" + KEY + "' is mandatory "
                + "and carries no default, and " + detail + " Set " + ENV_VAR + " to the "
                + "uuid of the tenant this deployment serves and start again.");
        }
    }
}
