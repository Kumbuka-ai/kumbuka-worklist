package ai.kumbuka.worklist.realm;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Keycloak carrying two realms: the tenant realm the service is bound to,
 * and an operator realm it is not.
 *
 * <p><strong>Why two realms and not one realm plus a forged token.</strong> A
 * token with a fabricated issuer is also a token with an invalid signature,
 * so refusing it demonstrates only that signature checking works. The
 * question here is different and sharper: does the service refuse a token
 * that is <em>completely valid</em> — properly signed, unexpired, carrying
 * the right audience and the right client id — and differs from an accepted
 * one in nothing but which realm minted it?
 *
 * <p>The second realm is the operator realm on purpose, rather than an
 * arbitrary one. Steering access moved from provider-plane identity to tenant
 * identity, and the operator realm is exactly the credential that used to
 * open these doors. So this is not a generic issuer check; it is the
 * migration, observed.
 *
 * <p>The port is fixed rather than mapped at random. The issuer URL must be
 * known at build time so the test profile can point the identity tenant at
 * this container, and a random port is not known then.
 */
public class TwoRealmKeycloakResource implements QuarkusTestResourceLifecycleManager {

    public static final String KEYCLOAK_IMAGE = "quay.io/keycloak/keycloak:26.0";

    /** The realm the service accepts. */
    public static final String TENANT_REALM = "kumbuka";
    /** The realm the service must refuse. Provider-plane identity. */
    public static final String OPERATOR_REALM = "kumbuka-ops";

    public static final String CLIENT_ID = "kumbuka-worklist";
    public static final String CLIENT_SECRET = "test-only-not-a-secret";
    public static final String TENANT_USER = "probe-member";
    public static final String OPERATOR_USER = "probe-operator";
    public static final String USER_PASSWORD = "probe-password";

    /**
     * A fixed port, and a different one from the sibling service's 38180.
     *
     * <p>Fixed because the issuer URL must be known at build time so the test
     * profile can point the identity tenant at this container, and a randomly
     * mapped port is not known then. Different because the two repositories
     * are built from the same template and a developer checking one against
     * the other would otherwise have two suites fighting over one port, with
     * a failure that reads as a broken realm import.
     */
    public static final int HOST_PORT = 38280;
    public static final String BASE_URL = "http://localhost:" + HOST_PORT;
    public static final String TENANT_ISSUER = BASE_URL + "/realms/" + TENANT_REALM;
    public static final String OPERATOR_ISSUER = BASE_URL + "/realms/" + OPERATOR_REALM;

    private static GenericContainer<?> keycloak;

    @Override
    public Map<String, String> start() {
        keycloak = new GenericContainer<>(KEYCLOAK_IMAGE)
            .withExposedPorts(8080)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin")
            .withCommand("start-dev", "--import-realm")
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("tenant-realm.json"),
                "/opt/keycloak/data/import/tenant-realm.json")
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("operator-realm.json"),
                "/opt/keycloak/data/import/operator-realm.json")
            // Waiting on the SECOND realm's discovery document: the first one
            // being up says nothing about whether the second import finished,
            // and a probe that raced it would report a refusal caused by a
            // missing realm as if it were the boundary holding.
            .waitingFor(Wait.forHttp("/realms/" + OPERATOR_REALM + "/.well-known/openid-configuration")
                .withStartupTimeout(Duration.ofMinutes(3)));
        keycloak.setPortBindings(List.of(HOST_PORT + ":8080"));
        keycloak.start();

        Map<String, String> cfg = new HashMap<>();
        cfg.put("test.keycloak.tenant-issuer", TENANT_ISSUER);
        cfg.put("test.keycloak.operator-issuer", OPERATOR_ISSUER);
        return cfg;
    }

    @Override
    public void stop() {
        if (keycloak != null) {
            keycloak.stop();
            keycloak = null;
        }
    }
}
