package ai.kumbuka.worklist.realm;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Map;

/**
 * Turns the identity tenant on, and points it at the test Keycloak's TENANT
 * realm.
 *
 * <p>{@code quarkus.oidc.tenant-enabled} is a build-time property. The
 * default test profile bakes it to false so the database probes need no
 * identity provider, and a test resource cannot flip a build-time property
 * back at runtime — only a profile can, because Quarkus re-augments the
 * application for it.
 *
 * <p>The issuer is written here as a constant rather than read from the
 * running container: {@code auth-server-url} is resolved during augmentation,
 * before the container's coordinates could be reported back. That is also why
 * {@link TwoRealmKeycloakResource} binds a fixed port.
 */
public class RealmBoundProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            "quarkus.oidc.tenant-enabled", "true",
            "quarkus.oidc.auth-server-url", TwoRealmKeycloakResource.TENANT_ISSUER,
            "quarkus.oidc.client-id", TwoRealmKeycloakResource.CLIENT_ID,
            "quarkus.oidc.credentials.secret", TwoRealmKeycloakResource.CLIENT_SECRET,
            "quarkus.oidc.token.audience", TwoRealmKeycloakResource.CLIENT_ID,
            "quarkus.oidc.tls.verification", "none");
    }
}
