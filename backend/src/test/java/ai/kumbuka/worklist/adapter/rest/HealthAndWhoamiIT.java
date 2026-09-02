package ai.kumbuka.worklist.adapter.rest;

import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * The two things this service exposes, and the difference between them.
 *
 * <p>Acceptance criterion 7 has two halves and they are deliberately not the
 * same shape. The health endpoint answers WITHOUT a token, because an
 * orchestrator has none and a health check that needed one would report a
 * service as unhealthy the moment its identity provider went down — turning
 * one outage into two. {@code /api/whoami} answers only WITH one, and only
 * with one from the tenant realm.
 *
 * <p>The realm half of that statement is made in {@link
 * ai.kumbuka.worklist.realm.RealmBindingIT}, which starts a Keycloak carrying
 * two realms and shows the second one refused. What is left for here is the
 * floor underneath it: that the protected path is genuinely protected. This
 * class runs with the identity tenant DISABLED, which is the default test
 * profile, so a 401 here is the security layer refusing an unauthenticated
 * call rather than a token being checked.
 *
 * <p>Both are worth their own assertions because the failure modes are
 * opposite. A health endpoint that has quietly become authenticated fails a
 * deployment; a whoami that has quietly become open fails a tenant.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class HealthAndWhoamiIT {

    @Test
    void the_health_endpoint_answers_without_a_token() {
        given()
            .when().get("/q/health")
            .then()
            .statusCode(200)
            // UP rather than merely a 200: the readiness check includes the
            // datasource, so this is also the statement that the service
            // reached the database it migrated a moment earlier.
            .body("status", is("UP"));
    }

    @Test
    void the_readiness_check_reports_the_datasource() {
        given()
            .when().get("/q/health/ready")
            .then()
            .statusCode(200)
            .body("status", is("UP"));
    }

    @Test
    void whoami_is_not_reachable_without_authentication() {
        given()
            .when().get("/api/whoami")
            .then()
            .statusCode(401);
    }
}
