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
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The administration surface over REST against the running database, under
 * the service's own role (REA-0011 S4, TAR-0002 B13, C1.6, C3.5).
 *
 * <p>Each case names the acceptance criterion of the order it covers. Every
 * case works in a scope of its own, published to the tenant and holding no
 * vocabulary row from a migration or a fixture: whatever the scope declares,
 * it declares through the calls under test. The expectations are written here
 * from the order and the nodes, never read from the service.
 *
 * <h2>The red probe</h2>
 *
 * With a writing call resolving the scope for reading -- the
 * {@code Access.ADMINISTER} of {@code AdministrationSurface.declareStatus}
 * turned into {@code Access.READ} --
 * {@link #k5_a_reader_without_the_write_right_is_refused_every_write_and_reads_the_model}
 * would fail at {@code declare_status}: the muted member's declaration would
 * pass the directory and answer 200 where 403 {@code ROLE_INSUFFICIENT} is
 * expected. Read from the code, not run here: the run that wrote this class
 * had no database. The same probe, run, turns {@code AdministrationSurfaceTest}
 * red.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class AdministrationSurfaceIT {

    @Inject TestIdentityAssociation identity;

    private String scope;

    @BeforeAll
    static void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.registerMutedMember();
    }

    @BeforeEach
    void aScopeOfItsOwn() {
        SurfaceFixture.asMember(identity);
        UUID id = UUID.randomUUID();
        scope = SurfaceFixture.publishScope("admin-" + id.toString().substring(0, 8), id,
            "project", false);
    }

    // ====================================================================
    // K1: a scope modelled through this surface alone takes work
    // ====================================================================

    @Test
    void k1_a_scope_modelled_only_through_the_administration_surface_takes_work() {
        model();

        Response created = given().contentType(ContentType.JSON)
            .body(Map.of("title", "built on the surface", "status", "planned",
                "workstream", "platform", "attributes", Map.of("type", "task")))
            .post("/api/" + scope + "/item");
        assertThat(created.statusCode()).as(created.asString()).isEqualTo(201);
        assertThat(created.jsonPath().getString("fields.phase")).isEqualTo("draft");
        String first = created.jsonPath().getString("address");

        Response submitted = transition(first, "submit", created, Map.of());
        assertThat(submitted.jsonPath().getString("fields.phase")).isEqualTo("open");

        Response other = given().contentType(ContentType.JSON)
            .body(Map.of("title", "what it waits on", "status", "planned",
                "workstream", "platform"))
            .post("/api/" + scope + "/item?submit=true");
        assertThat(other.statusCode()).as(other.asString()).isEqualTo(201);
        String second = other.jsonPath().getString("address");

        Response related = transition(first, "relate", submitted,
            Map.of("to_item", second, "type", "depends_on"));
        assertThat(related.jsonPath().getList("fields.relations.type", String.class))
            .containsExactly("depends_on");

        Response started = transition(first, "start", related, Map.of("status", "doing"));
        assertThat(started.jsonPath().getString("fields.phase")).isEqualTo("in_progress");
        assertThat(started.jsonPath().getString("fields.status")).isEqualTo("doing");

        Response closed = transition(first, "close", started,
            Map.of("status", "done", "reason", "finished"));
        assertThat(closed.jsonPath().getString("fields.phase")).isEqualTo("closed");
        assertThat(closed.jsonPath().getString("fields.status")).isEqualTo("done");

        Response duplicate = given().contentType(ContentType.JSON)
            .body(Map.of("title", "the same again", "status", "planned",
                "workstream", "platform"))
            .post("/api/" + scope + "/item?submit=true");
        Response closedAsDuplicate = transition(duplicate.jsonPath().getString("address"),
            "relate", duplicate, Map.of("to_item", second, "type", "duplicate_of"));
        assertThat(closedAsDuplicate.jsonPath().getString("fields.status"))
            .as("duplicate_of closes into the target status declared on this surface (D18)")
            .isEqualTo("obsolete");
        assertThat(closedAsDuplicate.jsonPath().getString("fields.phase")).isEqualTo("closed");
    }

    // ====================================================================
    // K3: a caller of the work surface reads the model; an undeclared value
    // is refused
    // ====================================================================

    @Test
    void k3_a_caller_reads_the_declared_statuses_types_and_relation_types() {
        model();

        Response statuses = given().get(admin("statuses"));
        assertThat(statuses.statusCode()).as(statuses.asString()).isEqualTo(200);
        assertThat(statuses.jsonPath().getList("objects.fields.name", String.class))
            .containsExactly("planned", "doing", "done", "obsolete");
        assertThat(statuses.jsonPath().getList("objects.fields.phase", String.class))
            .as("each status names the phase it is bound to (D11)")
            .containsExactly("open", "in_progress", "closed", "closed");
        Map<String, Object> done = statuses.jsonPath().getMap("objects[2].fields");
        assertThat(done).containsEntry("closed", true).containsEntry("successful", true)
            .containsEntry("in_progress", false).containsEntry("actionable", false);

        Response attributes = given().get(admin("attributes"));
        assertThat(attributes.jsonPath().getList("objects.fields.key", String.class))
            .containsExactly("type");
        assertThat(attributes.jsonPath().getList("objects[0].fields.options.name", String.class))
            .as("the types of an item are the options of its attribute type")
            .containsExactly("task", "bug");

        Response relations = given().get(admin("relation-types"));
        assertThat(relations.jsonPath().getList("objects.fields.name", String.class))
            .containsExactly("depends_on", "duplicate_of");
        assertThat(relations.jsonPath().getBoolean("objects[0].fields.blocks")).isTrue();
        assertThat(relations.jsonPath().getString("objects[1].fields.target_status"))
            .isEqualTo("obsolete");
    }

    @Test
    void k3_an_update_with_an_undeclared_status_type_or_relation_type_is_refused() {
        model();
        Response draft = given().contentType(ContentType.JSON)
            .body(Map.of("title", "a draft", "status", "planned", "workstream", "platform"))
            .post("/api/" + scope + "/item");
        Response target = given().contentType(ContentType.JSON)
            .body(Map.of("title", "another draft", "status", "planned", "workstream", "platform"))
            .post("/api/" + scope + "/item");
        String path = path(draft.jsonPath().getString("address"));
        String token = draft.jsonPath().getString("conflict_token");

        List<Map<String, Object>> undeclared = List.of(
            Map.of("status", "parked"),
            Map.of("attributes", Map.of("type", "epic")),
            Map.of("relations", List.of(Map.of("type", "mentions",
                "item", target.jsonPath().getString("address")))));
        for (Map<String, Object> fields : undeclared) {
            Response refused = given().contentType(ContentType.JSON)
                .header("If-Match", "\"" + token + "\"")
                .body(fields)
                .patch(path);
            assertThat(refused.statusCode()).as(refused.asString()).isEqualTo(422);
            assertThat(refused.jsonPath().getString("reason")).as("%s", fields)
                .isEqualTo("VALUE_UNDECLARED");
        }
    }

    // ====================================================================
    // K4: contradicting predicates are refused and write nothing
    // ====================================================================

    @Test
    void k4_a_status_with_contradicting_predicates_is_refused_and_writes_nothing() {
        Response refused = post("statuses", Map.of("name", "both at once", "rank", 1,
            "closed", true, "in_progress", true));
        assertThat(refused.statusCode()).as(refused.asString()).isEqualTo(422);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("INVALID_VALUE");

        assertThat(given().get(admin("statuses")).jsonPath().getList("objects"))
            .as("nothing was written").isEmpty();
    }

    // ====================================================================
    // K5: the write right guards every writing call; reading stays open;
    // a stranger meets the work surface's not-found
    // ====================================================================

    @Test
    void k5_a_reader_without_the_write_right_is_refused_every_write_and_reads_the_model() {
        model();
        SurfaceFixture.asMutedMember(identity);

        for (AdministrationSurfaceSpecification.Row row : AdministrationSurfaceSpecification.rows()) {
            Response answered = call(row);
            if (AdministrationSurfaceSpecification.READ.equals(row.access())) {
                assertThat(answered.statusCode()).as("%s: %s", row.call(), answered.asString())
                    .isEqualTo(200);
            } else {
                assertThat(answered.statusCode()).as("%s: %s", row.call(), answered.asString())
                    .isEqualTo(403);
                assertThat(answered.jsonPath().getString("reason")).as(row.call())
                    .isEqualTo("ROLE_INSUFFICIENT");
            }
        }

        SurfaceFixture.asMember(identity);
        assertThat(given().get(admin("statuses")).jsonPath()
                .getList("objects.fields.name", String.class))
            .as("none of the refused calls wrote").doesNotContain("probe");
    }

    @Test
    void k5_a_caller_without_access_gets_the_work_surface_s_answer_for_an_unknown_scope() {
        SurfaceFixture.asStranger(identity);
        Response onTheWorkSurface = given().get("/api/" + scope + "/item");
        assertThat(onTheWorkSurface.statusCode()).isEqualTo(404);

        for (AdministrationSurfaceSpecification.Row row : AdministrationSurfaceSpecification.rows()) {
            Response answered = call(row);
            assertThat(answered.statusCode()).as(row.call()).isEqualTo(404);
            assertThat(answered.asString()).as(row.call())
                .isEqualTo(onTheWorkSurface.asString());
        }
    }

    // ====================================================================
    // K6: duplicate_of without a valid target status is refused
    // ====================================================================

    @Test
    void k6_duplicate_of_without_a_valid_target_status_is_refused_and_writes_nothing() {
        post("statuses", Map.of("name", "done", "rank", 1, "closed", true, "successful", true));
        post("statuses", Map.of("name", "planned", "rank", 2, "actionable", true));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("absent", "ARGUMENT_MISSING");
        expected.put("never-declared", "VALUE_UNDECLARED");
        expected.put("planned", "INVALID_VALUE");
        expected.put("done", "INVALID_VALUE");
        expected.forEach((target, reason) -> {
            Map<String, Object> body = new LinkedHashMap<>(Map.of("name", "duplicate_of",
                "rank", 1));
            if (!"absent".equals(target)) {
                body.put("target_status", target);
            }
            Response refused = post("relation-types", body);
            assertThat(refused.jsonPath().getString("reason")).as(target).isEqualTo(reason);
            assertThat(refused.jsonPath().getList("data.offenders", String.class)).as(target)
                .containsExactly("target_status");
        });

        assertThat(given().get(admin("relation-types")).jsonPath().getList("objects"))
            .as("nothing was written").isEmpty();
    }

    /**
     * No call renames a relation type, {@code duplicate_of} least of all (D18):
     * a write aimed at a declared {@code duplicate_of} under the
     * administration path is refused -- as a method no route of the
     * administration surface takes (C3.3), or as an address whose selector
     * {@code admin} is never one (C3.5) -- and the type keeps its name.
     */
    @Test
    void k6_duplicate_of_is_never_renamed() {
        post("statuses", Map.of("name", "obsolete", "rank", 1, "closed", true));
        expectOk(post("relation-types", Map.of("name", "duplicate_of", "rank", 1,
            "target_status", "obsolete")));

        for (String method : List.of("PATCH", "PUT", "POST")) {
            Response refused = given().contentType(ContentType.JSON)
                .header("If-Match", "\"a-token\"")
                .body(Map.of("name", "duplicate"))
                .request(method, admin("relation-types/duplicate_of"));
            assertThat(refused.statusCode()).as("%s: %s", method, refused.asString())
                .isIn(400, 405);
            assertThat(refused.jsonPath().getString("reason")).as(method)
                .isIn("VERB_NOT_CARRIED", "ADDRESS_MALFORMED");
        }

        assertThat(given().get(admin("relation-types")).jsonPath()
                .getList("objects.fields.name", String.class))
            .as("the type keeps its name")
            .containsExactly("duplicate_of");
    }

    // ====================================================================
    // K7: the default column set is written and read back
    // ====================================================================

    @Test
    void k7_the_default_column_set_is_written_and_read_back_from_the_settings() {
        Response opened = post("settings", Map.of("default_columns", List.of("title")));
        assertThat(opened.statusCode()).as(opened.asString()).isEqualTo(200);

        Response changed = given().contentType(ContentType.JSON)
            .header("If-Match", "\"" + opened.jsonPath().getString("conflict_token") + "\"")
            .body(Map.of("default_columns", List.of("title", "status", "phase")))
            .patch(admin("settings"));
        assertThat(changed.statusCode()).as(changed.asString()).isEqualTo(200);
        assertThat(changed.getHeader("ETag")).contains(changed.jsonPath().getString("conflict_token"));

        Response read = given().get(admin("settings"));
        assertThat(read.jsonPath().getList("fields.default_columns", String.class))
            .containsExactly("title", "status", "phase");

        Response stale = given().contentType(ContentType.JSON)
            .header("If-Match", "\"" + opened.jsonPath().getString("conflict_token") + "\"")
            .body(Map.of("default_columns", List.of("title")))
            .patch(admin("settings"));
        assertThat(stale.statusCode()).as("the token of a read before the write is stale")
            .isEqualTo(412);
    }

    // ====================================================================
    // K8: a workstream behaves as through WorkstreamService
    // ====================================================================

    @Test
    void k8_a_workstream_is_declared_renamed_while_unused_and_refused_a_rename_once_used() {
        post("settings", Map.of());
        post("statuses", Map.of("name", "planned", "rank", 1, "actionable", true));

        Response declared = post("workstreams", Map.of("token", "alpha",
            "description", "the first body of work"));
        assertThat(declared.statusCode()).as(declared.asString()).isEqualTo(200);
        String at = admin("workstreams/" + declared.jsonPath().getString("fields.number"));

        Response renamed = patch(at, declared, Map.of("token", "beta"));
        assertThat(renamed.statusCode()).as(renamed.asString()).isEqualTo(200);
        assertThat(renamed.jsonPath().getString("fields.token")).isEqualTo("beta");

        Response item = given().contentType(ContentType.JSON)
            .body(Map.of("title", "points at beta", "status", "planned", "workstream", "beta"))
            .post("/api/" + scope + "/item");
        assertThat(item.statusCode()).as(item.asString()).isEqualTo(201);

        Response refused = patch(at, renamed, Map.of("token", "gamma"));
        assertThat(refused.statusCode()).as(refused.asString()).isEqualTo(409);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("WORKSTREAM_HAS_REFERENCES");

        Response described = patch(at, renamed, Map.of("description", "still the first"));
        assertThat(described.statusCode()).as(described.asString()).isEqualTo(200);

        Response withdrawn = given()
            .header("If-Match", "\"" + described.jsonPath().getString("conflict_token") + "\"")
            .delete(at);
        assertThat(withdrawn.statusCode()).as(withdrawn.asString()).isEqualTo(200);
        assertThat(withdrawn.jsonPath().getString("fields.status")).isEqualTo("withdrawn");
    }

    // ====================================================================

    /**
     * The model of K1, declared through this surface alone: the settings and
     * views, four statuses, the attribute type with two options, two relation
     * types, duplicate_of with its target, and one workstream.
     */
    private void model() {
        expectOk(post("settings", Map.of("default_columns", List.of("title", "status"))));
        expectOk(post("statuses", Map.of("name", "planned", "rank", 1, "actionable", true)));
        expectOk(post("statuses", Map.of("name", "doing", "rank", 2, "actionable", true,
            "in_progress", true)));
        expectOk(post("statuses", Map.of("name", "done", "rank", 3, "closed", true,
            "successful", true)));
        expectOk(post("statuses", Map.of("name", "obsolete", "rank", 4, "closed", true)));
        expectOk(post("attributes", Map.of("key", "type", "name", "Type", "type", "choice",
            "rank", 1)));
        expectOk(post("attributes/type/options", Map.of("name", "task", "rank", 1)));
        expectOk(post("attributes/type/options", Map.of("name", "bug", "rank", 2)));
        expectOk(post("relation-types", Map.of("name", "depends_on", "blocks", true,
            "rank", 1)));
        expectOk(post("relation-types", Map.of("name", "duplicate_of", "rank", 2,
            "target_status", "obsolete")));
        expectOk(post("workstreams", Map.of("token", "platform",
            "description", "the platform's body of work")));
    }

    /** One call of the declaration, with a body of the right form where it takes one. */
    private Response call(AdministrationSurfaceSpecification.Row row) {
        String path = row.path().replace("{scope}", scope).replace("{name}", "probe")
            .replace("{key}", "probe").replace("{number}", "1");
        RequestSpecification request = given().contentType(ContentType.JSON)
            .header("If-Match", "\"a-token\"");
        Map<String, Object> body = switch (row.call()) {
            case "declare_status" -> Map.of("name", "probe", "rank", 1);
            case "declare_usage" -> Map.of("name", "priority");
            case "declare_attribute" -> Map.of("key", "probe", "name", "probe", "type", "text",
                "rank", 1);
            case "declare_option" -> Map.of("name", "probe", "rank", 1);
            case "declare_relation_type" -> Map.of("name", "probe", "rank", 1);
            case "declare_workstream" -> Map.of("token", "probe", "description", "probe");
            case "change_workstream" -> Map.of("description", "probe");
            case "change_settings" -> Map.of("default_columns", List.of("title"));
            case "open_settings" -> Map.of();
            default -> null;
        };
        if (body != null) {
            request = request.body(body);
        }
        return request.request(row.method(), path);
    }

    private Response post(String collection, Map<String, Object> body) {
        return given().contentType(ContentType.JSON).body(body).post(admin(collection));
    }

    private static Response patch(String at, Response read, Map<String, Object> body) {
        return given().contentType(ContentType.JSON)
            .header("If-Match", "\"" + read.jsonPath().getString("conflict_token") + "\"")
            .body(body)
            .patch(at);
    }

    /** A transition or a graph call on an item, under the token of its last answer. */
    private Response transition(String address, String verb, Response last,
                                Map<String, Object> body) {
        Response answered = given().contentType(ContentType.JSON)
            .header("If-Match", "\"" + last.jsonPath().getString("conflict_token") + "\"")
            .body(body)
            .post(path(address) + ":" + verb);
        assertThat(answered.statusCode()).as("%s: %s", verb, answered.asString()).isEqualTo(200);
        return answered;
    }

    private String admin(String path) {
        return "/api/" + scope + "/admin/" + path;
    }

    private String path(String address) {
        return "/api/" + scope + "/item/" + address.substring(address.lastIndexOf('/') + 1);
    }

    private static void expectOk(Response response) {
        assertThat(response.statusCode()).as(response.asString()).isEqualTo(200);
    }
}
