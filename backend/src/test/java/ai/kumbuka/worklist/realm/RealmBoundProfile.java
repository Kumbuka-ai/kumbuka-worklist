/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
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
 * <p>The issuer is deliberately NOT set here. {@code auth-server-url} is a
 * runtime property, so {@link TwoRealmKeycloakResource} supplies it from the
 * container's mapped port once the container is up. That keeps the port
 * dynamic: a fixed host port collides whenever another job on the same host
 * holds it.
 */
public class RealmBoundProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            "quarkus.oidc.tenant-enabled", "true",
            "quarkus.oidc.client-id", TwoRealmKeycloakResource.CLIENT_ID,
            "quarkus.oidc.credentials.secret", TwoRealmKeycloakResource.CLIENT_SECRET,
            "quarkus.oidc.token.audience", TwoRealmKeycloakResource.CLIENT_ID,
            "quarkus.oidc.tls.verification", "none");
    }
}
