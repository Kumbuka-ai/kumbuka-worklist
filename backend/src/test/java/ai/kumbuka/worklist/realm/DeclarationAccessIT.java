/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.realm;

import ai.kumbuka.worklist.architecture.DeclarationShapeTest;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who reads the declaration, where, and in which shape (C5.1, C5.2, C5.3).
 *
 * <p>Against a real identity provider, because the statement is about a real
 * token: a user of the tenant realm who holds no realm role at all, so its
 * token is a valid platform token with no role of any kind. That token reads
 * the declaration; no token reads nothing and meets the protocol's challenge;
 * and the declaration is served at {@code /api/declaration} and nowhere else.
 *
 * <p>The shape of what is served is checked against the schema in the test
 * tree, through {@link DeclarationShapeTest#violations}: the declaration
 * carries the texts of the message bundles, which only the running service
 * renders.
 *
 * <p>RED STATE, observed 2026-10-29 against the tree before this change: the
 * declaration of the earlier shape broke the schema at the top level, with
 * {@code verbs} for {@code calls}, a bare hexadecimal version and no
 * {@code shape_version}, and 43 arguments carried {@code "default": null}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = TwoRealmKeycloakResource.class, restrictToAnnotatedClass = true)
@TestProfile(RealmBoundProfile.class)
class DeclarationAccessIT {

    private static final String DECLARATION = "/api/declaration";

    @Test
    @SuppressWarnings("unchecked")
    void a_valid_token_with_no_role_reads_the_declaration() throws Exception {
        String token = rolelessToken();
        Map<String, Object> claims = new ObjectMapper().readValue(
            Base64.getUrlDecoder().decode(token.split("\\.")[1]), Map.class);
        Object realmRoles = claims.get("realm_access") instanceof Map<?, ?> access
            ? access.get("roles") : null;
        assertThat(realmRoles == null ? List.of() : (List<String>) realmRoles)
            .as("the probe's token carries no role of the realm, so what it reads it reads "
                + "with no role")
            .doesNotContain("member");

        Response read = given()
            .header("Authorization", "Bearer " + token)
            .when().get(DECLARATION);

        assertThat(read.statusCode())
            .as("the declaration needs a valid platform token and no role (C5.2)")
            .isEqualTo(200);
        assertThat(read.header("ETag"))
            .as("the ETag is the declaration's version (C5.1)")
            .isEqualTo("\"" + read.jsonPath().getString("version") + "\"");
    }

    @Test
    void no_token_reads_no_declaration_and_meets_the_challenge() {
        Response read = given().when().get(DECLARATION);
        assertThat(read.statusCode())
            .as("the declaration is behind authentication like every route of the surface")
            .isEqualTo(401);
        assertThat(read.header("WWW-Authenticate"))
            .as("and the refusal carries the bearer challenge of the protocol")
            .startsWith("Bearer");
        assertThat(read.asString().getBytes(StandardCharsets.UTF_8))
            .as("and nothing of the declaration")
            .doesNotContain("shape_version".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void the_declaration_is_served_at_one_path_only() {
        for (String elsewhere : List.of("/mcp/declaration", "/declaration",
                "/api/declaration.json")) {
            assertThat(given()
                    .header("Authorization", "Bearer " + rolelessToken())
                    .when().get(elsewhere).asString())
                .as("the declaration is served at /api/declaration and nowhere else (C5.1); %s "
                    + "answers no copy of it", elsewhere)
                .doesNotContain("shape_version");
        }
    }

    @Test
    void the_served_declaration_has_the_contract_shape() {
        Map<String, Object> declaration = given()
            .header("Authorization", "Bearer " + rolelessToken())
            .when().get(DECLARATION)
            .then().statusCode(200)
            .extract().jsonPath().getMap("$");

        assertThat(DeclarationShapeTest.violations(declaration))
            .as("the declaration, as served, against the schema of C5.3 to C5.5 held in the "
                + "test tree")
            .isEmpty();
    }

    private static String rolelessToken() {
        return RealmBindingIT.tokenFrom(
            RealmBindingIT.issuer(TwoRealmKeycloakResource.TENANT_ISSUER_KEY),
            TwoRealmKeycloakResource.ROLELESS_USER);
    }
}
