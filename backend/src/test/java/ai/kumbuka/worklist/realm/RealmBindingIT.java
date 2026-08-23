package ai.kumbuka.worklist.realm;

import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The realm binding: a token from the tenant realm is accepted, and a token
 * from another realm is not.
 *
 * <p>The two tokens differ in exactly one thing. Both are signed by a real
 * Keycloak, both are unexpired, both name the client {@code kumbuka-worklist}
 * as their audience, both were obtained the same way by the same grant. Only
 * the issuer differs. So a refusal here can be attributed to the issuer and
 * to nothing else, which is what the assertion needs in order to mean
 * anything.
 *
 * <p>The realm that is refused is the operator realm — the credential that
 * used to authorise steering access before it moved to tenant identity.
 * Provider-plane identity is no longer an authorisation for a tenant's
 * exchanges, and this is where that stops being a sentence in a document.
 *
 * <p>The database resource is started alongside the identity provider because
 * the service does not boot without a schema, and the accepted request has to
 * reach a running application to be accepted by it.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@QuarkusTestResource(value = TwoRealmKeycloakResource.class, restrictToAnnotatedClass = true)
@TestProfile(RealmBoundProfile.class)
class RealmBindingIT {

    @Test
    void a_token_from_the_tenant_realm_is_accepted() {
        String token = tokenFrom(TwoRealmKeycloakResource.TENANT_ISSUER,
            TwoRealmKeycloakResource.TENANT_USER);

        given()
            .header("Authorization", "Bearer " + token)
            .when().get("/api/whoami")
            .then()
            .statusCode(200)
            // The subject is the token's stable identifier, not a display
            // name: authorship recorded against a name that can change is
            // authorship that stops matching what it wrote.
            .body("subject", notNullValue())
            .body("tenant", notNullValue());
    }

    @Test
    void a_token_from_another_realm_is_refused() {
        String tenantToken = tokenFrom(TwoRealmKeycloakResource.TENANT_ISSUER,
            TwoRealmKeycloakResource.TENANT_USER);
        String operatorToken = tokenFrom(TwoRealmKeycloakResource.OPERATOR_ISSUER,
            TwoRealmKeycloakResource.OPERATOR_USER);

        assertThat(operatorToken)
            .as("the two tokens must genuinely be different tokens — an identical string "
                + "would mean the second realm never minted one and the refusal below "
                + "would be about something else entirely")
            .isNotEqualTo(tenantToken);

        given()
            .header("Authorization", "Bearer " + operatorToken)
            .when().get("/api/whoami")
            .then()
            .statusCode(401);
    }

    @Test
    void an_unauthenticated_request_is_refused() {
        // The floor under the two assertions above: if the endpoint were open,
        // "accepted" would say nothing about the token that was presented.
        given()
            .when().get("/api/whoami")
            .then()
            .statusCode(401);
    }

    /**
     * A real access token from a real realm, by direct grant.
     *
     * <p>The grant is enabled on the test realms only. Driving the
     * authorization-code flow would add a browser to a test about issuers and
     * change nothing about the token that comes out of it.
     */
    private static String tokenFrom(String issuer, String username) {
        return given()
            .contentType(ContentType.URLENC)
            .formParam("grant_type", "password")
            .formParam("client_id", TwoRealmKeycloakResource.CLIENT_ID)
            .formParam("client_secret", TwoRealmKeycloakResource.CLIENT_SECRET)
            .formParam("username", username)
            .formParam("password", TwoRealmKeycloakResource.USER_PASSWORD)
            .when().post(issuer + "/protocol/openid-connect/token")
            .then().statusCode(200)
            .extract().path("access_token");
    }
}
