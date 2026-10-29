/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.realm;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.HashMap;
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
 * <p>The port is mapped at random. The issuer URLs are derived from the
 * mapped port once the container is up and handed to Quarkus as runtime
 * configuration, together with {@code quarkus.oidc.auth-server-url}. Keycloak
 * derives a token's {@code iss} from the host and port the token was
 * requested through, so the test requests its tokens through exactly these
 * URLs, which are also the ones the service discovers its tenant through.
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

    /** A user of the tenant realm that holds no realm role at all. */
    public static final String ROLELESS_USER = "probe-roleless";
    public static final String OPERATOR_USER = "probe-operator";
    public static final String USER_PASSWORD = "probe-password";

    /** Config key under which {@link #start()} reports the tenant realm's issuer. */
    public static final String TENANT_ISSUER_KEY = "test.keycloak.tenant-issuer";
    /** Config key under which {@link #start()} reports the operator realm's issuer. */
    public static final String OPERATOR_ISSUER_KEY = "test.keycloak.operator-issuer";

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
        keycloak.start();

        String baseUrl = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
        String tenantIssuer = baseUrl + "/realms/" + TENANT_REALM;

        Map<String, String> cfg = new HashMap<>();
        cfg.put("quarkus.oidc.auth-server-url", tenantIssuer);
        cfg.put(TENANT_ISSUER_KEY, tenantIssuer);
        cfg.put(OPERATOR_ISSUER_KEY, baseUrl + "/realms/" + OPERATOR_REALM);
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
