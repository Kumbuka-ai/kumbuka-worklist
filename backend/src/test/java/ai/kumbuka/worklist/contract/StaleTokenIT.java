/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.contract;

import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.domain.VocabularyRegistry;
import ai.kumbuka.worklist.domain.WorkstreamService;
import ai.kumbuka.worklist.surface.Mcp;
import ai.kumbuka.worklist.surface.SurfaceFixture;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * A stale conflict token is answered with the object as it stands (DEC-0041,
 * C8.7): {@code data} carries its complete {@code address}, its current
 * {@code fields} and {@code conflict_token}, and the {@code service} that
 * holds it, so that the caller can redo its change without a read of its own.
 * On both surfaces, and on a membership as on an object.
 *
 * <p>The code is still {@code CONFLICT} and the member {@code offenders} still
 * names the token: both are what callers read today, and the rename to
 * {@code CONFLICT_TOKEN_STALE} is a step of its own.
 *
 * <p>RED STATE, observed 2026-10-29 with both adapters answering a stale token
 * by the token alone ({@code RefusalPayload.of} in place of {@code
 * RefusalPayload.stale}), as at {@code 0fb4862}: every case turned red,
 * {@code data} carrying no {@code address}, {@code fields} or {@code service}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class StaleTokenIT {

    private static final String SCOPE = "stale-probe";
    private static final UUID SCOPE_ID = UUID.fromString("7c8d9eaf-b0c1-4d2e-8f3a-4b5c6d7e8f90");

    private static boolean staged;

    @Inject TestIdentityAssociation identity;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject WorkstreamService workstreams;

    @BeforeEach
    void stageOnce() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);
        if (!staged) {
            SurfaceFixture.publishScope(SCOPE, SCOPE_ID, "project", false);
            selectors.open(SCOPE_ID, Selector.ITEM);
            vocabulary.declareStatus(SCOPE_ID, "open", 1, true, false, false, false);
            vocabulary.declareStatus(SCOPE_ID, "done", 2, false, false, true, true);
            workstreams.requireDefault(SCOPE_ID);
            staged = true;
        }
    }

    @Test
    void a_stale_token_over_mcp_carries_the_object_as_it_stands() {
        Map<String, Object> created = created("stale over MCP");
        String address = String.valueOf(created.get("address"));
        Map<String, Object> refusal = Mcp.refusal(Mcp.call("worklist_update", Map.of(
            "address", address, "conflict_token", "stale", "fields", Map.of("title", "x"))));
        Map<String, Object> now = Mcp.answer(Mcp.call("worklist_read", Map.of("address", address)));

        assertStale(refusal, now, "worklist_update");
    }

    @Test
    void a_stale_token_over_rest_carries_the_object_as_it_stands() {
        Map<String, Object> created = created("stale over REST");
        String address = String.valueOf(created.get("address"));
        String path = "/api/" + address.substring("worklist://".length());
        Response refused = given().contentType(ContentType.JSON).header("If-Match", "\"stale\"")
            .body(Map.of("title", "x")).patch(path);
        Map<String, Object> now = given().get(path).jsonPath().getMap("$");

        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(refused.statusCode()).isEqualTo(412);
        soft.assertAll();
        assertStale(refused.jsonPath().getMap("$"), now, "update");
    }

    @Test
    void a_stale_token_on_a_transition_carries_the_object_too() {
        Map<String, Object> created = created("stale on withdraw");
        String address = String.valueOf(created.get("address"));
        Map<String, Object> refusal = Mcp.refusal(Mcp.call("worklist_withdraw", Map.of(
            "address", address, "conflict_token", "stale", "status", "done")));
        Map<String, Object> now = Mcp.answer(Mcp.call("worklist_read", Map.of("address", address)));

        assertStale(refusal, now, "worklist_withdraw");
    }

    // ======================================================================

    private static Map<String, Object> created(String title) {
        return Mcp.answer(Mcp.call("worklist_create", Map.of("scope", SCOPE,
            "selector", Selector.ITEM, "fields", Map.of("title", title, "status", "open"))));
    }

    @SuppressWarnings("unchecked")
    private static void assertStale(Map<String, Object> refusal, Map<String, Object> now,
                                    String attempted) {
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(refusal.get("reason")).isEqualTo("CONFLICT");
        Map<String, Object> data = (Map<String, Object>) refusal.get("data");
        soft.assertThat(data).as("the members of a stale token (C8.7): %s", refusal)
            .containsOnlyKeys("attempted", "address", "fields", "conflict_token", "service",
                "offenders");
        soft.assertThat(data.get("attempted")).isEqualTo(attempted);
        soft.assertThat(data.get("address")).as("complete, as a read answers it")
            .isEqualTo(now.get("address"));
        soft.assertThat(data.get("fields")).as("the object as a read answers it")
            .isEqualTo(now.get("fields"));
        soft.assertThat(data.get("conflict_token")).as("the token a read answers")
            .isEqualTo(now.get("conflict_token"));
        soft.assertThat(data.get("service")).isEqualTo("worklist");
        soft.assertThat(data.get("offenders")).isEqualTo(List.of("conflict_token"));
        soft.assertAll();
    }
}
