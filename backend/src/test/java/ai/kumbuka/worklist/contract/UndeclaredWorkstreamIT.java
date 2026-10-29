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
 * A workstream token on {@code create} that the scope does not declare is a
 * value outside the scope's vocabulary: {@code VALUE_UNDECLARED} 422 naming
 * {@code fields.workstream} (C8.7), on both surfaces. It is not the not-found
 * answer, which is about an address and carries no {@code data}: a mistyped
 * token answered that way read as a question of scope membership, with
 * nothing naming the field.
 *
 * <p>RED STATE, observed 2026-10-29 with {@code ItemService.workstreamByToken}
 * planted to refuse an unknown token with {@code WORKSTREAM_UNKNOWN}, the
 * not-found reason it once raised: both cases turned red, answering
 * {@code NOT_FOUND} 404 with no {@code data}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class UndeclaredWorkstreamIT {

    private static final String SCOPE = "workstream-token-probe";
    private static final UUID SCOPE_ID = UUID.fromString("8e9fa0b1-c2d3-4e4f-9a0b-1c2d3e4f5a6b");
    private static final Map<String, Object> FIELDS = Map.of("title", "aimed at nothing",
        "status", "open", "workstream", "nowhere");

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
            workstreams.requireDefault(SCOPE_ID);
            staged = true;
        }
    }

    @Test
    void an_undeclared_workstream_on_create_over_mcp_is_a_value_the_scope_lacks() {
        Map<String, Object> refusal = Mcp.refusal(Mcp.call("worklist_create", Map.of(
            "scope", SCOPE, "selector", Selector.ITEM, "fields", FIELDS)));
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(refusal.get("reason")).isEqualTo("VALUE_UNDECLARED");
        soft.assertThat(refusal.get("data")).isEqualTo(Map.of("attempted", "worklist_create",
            "offenders", List.of("fields.workstream")));
        soft.assertAll();
    }

    @Test
    void an_undeclared_workstream_on_create_over_rest_is_a_value_the_scope_lacks() {
        Response refused = given().contentType(ContentType.JSON).body(FIELDS)
            .post("/api/" + SCOPE + "/" + Selector.ITEM);
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(refused.statusCode()).isEqualTo(422);
        soft.assertThat(refused.jsonPath().getString("reason")).isEqualTo("VALUE_UNDECLARED");
        soft.assertThat(refused.jsonPath().getMap("data")).isEqualTo(Map.of(
            "attempted", "create", "offenders", List.of("fields.workstream")));
        soft.assertAll();
    }
}
