/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestIdentityAssociation;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service built with {@code kumbuka.local-mcp.enabled=true}, set
 * explicitly: the assistant surface is there, with the tool list of a build
 * that does not name the setting, which every other test runs (C4.6).
 *
 * <p>RED STATE, observed 2026-10-29 with the annotation's {@code stringValue}
 * set to {@code "on"} for the run: the adapter left the application, {@code
 * /mcp} found no route and this class turned red at the status of the
 * request.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestProfile(LocalMcpOnIT.On.class)
class LocalMcpOnIT {

    /** The build with the setting named and on. */
    public static class On implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kumbuka.local-mcp.enabled", "true");
        }
    }

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);
    }

    @Test
    void the_assistant_surface_offers_the_carried_calls() {
        List<String> served = Mcp.rpc("tools/list", Map.of()).jsonPath()
            .getList("result.tools.name", String.class);
        assertThat(served).containsExactlyInAnyOrderElementsOf(
            VerbSurfaceSpecification.verbsOf(VerbSurfaceSpecification.CARRIED).stream()
                .map(verb -> "worklist_" + verb).toList());
    }
}
