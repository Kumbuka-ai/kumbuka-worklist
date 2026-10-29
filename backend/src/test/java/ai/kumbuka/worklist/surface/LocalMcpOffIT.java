/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.adapter.mcp.McpAdapter;
import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.domain.VocabularyRegistry;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.arc.Arc;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service built with {@code kumbuka.local-mcp.enabled=false}: it starts,
 * the assistant surface is not in the application, and REST answers as it
 * does with the surface on (C4.6).
 *
 * <p>The switch is a build property, so the class it governs is left out of
 * the application rather than refusing at runtime: {@code /mcp} finds no
 * route, and is answered as every route no resource carries is (C8.3).
 *
 * <p>RED STATE, observed 2026-10-29 with the annotation removed from {@link
 * McpAdapter} for the run: {@code /mcp} answered 200 with the tool list and
 * this class turned red.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestProfile(LocalMcpOffIT.Off.class)
class LocalMcpOffIT {

    private static final UUID SCOPE_ID = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);

    /** The build without the service's own assistant surface. */
    public static class Off implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kumbuka.local-mcp.enabled", "false");
        }
    }

    @Inject TestIdentityAssociation identity;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);
        selectors.open(SCOPE_ID, Selector.ITEM);
    }

    @Test
    void the_assistant_surface_is_not_in_the_application() {
        Response rpc = given().contentType(ContentType.JSON)
            .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
            .post("/mcp");
        assertThat(rpc.statusCode()).as("no route serves /mcp").isEqualTo(404);
        assertThat(rpc.jsonPath().getString("reason")).isEqualTo("NOT_FOUND");

        assertThat(Arc.container().instance(McpAdapter.class).isAvailable())
            .as("the resource is not a bean of this build").isFalse();
        assertThat(Arc.container().instance(VerbSurface.class).isAvailable())
            .as("the verb surface both adapters call stays").isTrue();
    }

    @Test
    void a_write_and_a_read_over_rest_answer_as_with_the_surface_on() {
        String status = vocabulary
            .declareStatus(SCOPE_ID, "off-open", 1, true, false, false, false).name;
        Response created = given().contentType(ContentType.JSON)
            .body(Map.of("title", "without mcp", "status", status))
            .post(SurfaceFixture.collection(Selector.ITEM));
        assertThat(created.statusCode()).as(created.asString()).isEqualTo(201);
        String address = created.jsonPath().getString("address");
        String number = address.substring(address.lastIndexOf('/') + 1);
        assertThat(created.header("Location"))
            .endsWith(SurfaceFixture.item(Selector.ITEM, number));

        Response read = given().get(SurfaceFixture.item(Selector.ITEM, number));
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.jsonPath().getString("fields.title")).isEqualTo("without mcp");
    }

    @Test
    void an_undeclared_argument_is_refused_by_name_as_with_the_surface_on() {
        Map<String, Object> invented = Map.of("invented", 1);
        Response refused = given().contentType(ContentType.JSON).body(invented)
            .post(SurfaceFixture.item(Selector.ITEM, 1) + ":claim");

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(refused.jsonPath().getString("message")).doesNotContain("invented");
        assertThat(refused.jsonPath().getList("data.accepts", String.class))
            .containsExactly("duration_seconds");
    }
}
