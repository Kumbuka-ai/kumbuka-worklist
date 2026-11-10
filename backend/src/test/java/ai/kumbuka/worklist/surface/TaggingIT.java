/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.Selector;
import ai.kumbuka.worklist.domain.SelectorRegistry;
import ai.kumbuka.worklist.domain.VocabularyRegistry;
import ai.kumbuka.worklist.platform.PlatformFixture;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two tag verbs, the field {@code tags} and the filter {@code tag}, end to
 * end over REST and MCP (sprint/210).
 *
 * <p>The vocabulary is staged in {@code platform.tag} of the substrate, the way
 * the platform holds it, and read by the service through
 * {@code platform.tag_access} only. One scope of its own, so that the tags and
 * items of this class meet no other class's.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaggingIT {

    private static final String SCOPE = "tagging";
    private static final UUID SCOPE_ID = UUID.fromString("00000000-0000-0000-0000-0000000002a1");

    private static final String OPEN = "tag-open";
    private static final String DONE = "tag-done";

    private static final String ALPHA = "alpha";
    private static final String BETA = "component:beta";
    private static final String RETIRED = "retired";

    @Inject TestIdentityAssociation identity;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;

    @BeforeAll
    static void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.publishEmptyScope(SCOPE, SCOPE_ID);
        SurfaceFixture.registerMutedMember();
        declareTag(SCOPE_ID, ALPHA, "declared");
        declareTag(SCOPE_ID, BETA, "declared");
        declareTag(SCOPE_ID, RETIRED, "declared");
    }

    @BeforeEach
    void openTheScope() {
        SurfaceFixture.asMember(identity);
        selectors.open(SCOPE_ID, Selector.ITEM);
        selectors.open(SCOPE_ID, Selector.MILESTONE);
        if (vocabulary.statusByName(SCOPE_ID, OPEN).isEmpty()) {
            vocabulary.declareStatus(SCOPE_ID, OPEN, 1, true, false, false, false);
            vocabulary.declareStatus(SCOPE_ID, DONE, 2, false, false, true, true);
        }
    }

    // =======================================================================
    // tag_attach, read with tags, tag_detach -- and the two no-ops
    // =======================================================================

    @Test
    void attach_shows_the_tag_in_read_sorted_and_detach_removes_it() {
        long item = createItem("tagged twice");

        attach(item, BETA).then().statusCode(200);
        Response answered = attach(item, ALPHA);
        answered.then().statusCode(200);
        assertThat(answered.jsonPath().getString("address"))
            .as("the answer carries the item's address")
            .isEqualTo("worklist://" + SCOPE + "/item/" + item);
        assertThat(answered.jsonPath().getList("fields.tags", String.class))
            .as("and the complete list of its tags, ascending")
            .containsExactly(ALPHA, BETA);

        assertThat(read(item).jsonPath().getList("fields.tags", String.class))
            .as("read carries the tags, ascending")
            .containsExactly(ALPHA, BETA);

        Response detached = detach(item, ALPHA);
        detached.then().statusCode(200);
        assertThat(detached.jsonPath().getList("fields.tags", String.class))
            .containsExactly(BETA);
        assertThat(read(item).jsonPath().getList("fields.tags", String.class))
            .containsExactly(BETA);
    }

    @Test
    void an_item_without_tags_reads_an_empty_list() {
        long item = createItem("untagged");
        assertThat(read(item).jsonPath().getList("fields.tags", String.class))
            .as("tags is always there, and empty where the item carries none")
            .isEmpty();
    }

    @Test
    void attaching_a_tag_the_item_carries_succeeds_and_writes_nothing() {
        long item = createItem("attached twice");
        attach(item, ALPHA).then().statusCode(200);

        Response again = attach(item, ALPHA);
        again.then().statusCode(200);
        assertThat(again.jsonPath().getList("fields.tags", String.class))
            .as("a second attach of the same tag is a successful no-op")
            .containsExactly(ALPHA);
    }

    @Test
    void detaching_a_tag_the_item_lacks_succeeds_and_writes_nothing() {
        long item = createItem("never tagged");

        Response detached = detach(item, ALPHA);
        detached.then().statusCode(200);
        assertThat(detached.jsonPath().getList("fields.tags", String.class))
            .as("detaching an assignment that does not stand is a successful no-op")
            .isEmpty();
    }

    /** Acceptance (d): the assignment is a relation, not a field of the item. */
    @Test
    void attach_and_detach_leave_the_conflict_token_and_the_change_time_as_they_are() {
        long item = createItem("token stays");
        Response before = read(item);
        String token = before.jsonPath().getString("fields.conflict_token");
        String changedAt = before.jsonPath().getString("fields.changed_at");

        Response attached = attach(item, ALPHA);
        attached.then().statusCode(200);
        assertThat(attached.jsonPath().getString("fields.conflict_token"))
            .as("tag_attach leaves the conflict token as it was")
            .isEqualTo(token);
        assertThat(attached.jsonPath().getString("fields.changed_at"))
            .as("and the change time")
            .isEqualTo(changedAt);
        assertThat(attached.getHeader("ETag")).as("and the entity tag")
            .contains(token);

        Response detached = detach(item, ALPHA);
        assertThat(detached.jsonPath().getString("fields.conflict_token"))
            .as("tag_detach leaves the conflict token as it was").isEqualTo(token);
        assertThat(detached.jsonPath().getString("fields.changed_at")).isEqualTo(changedAt);

        // And the token read before the tags moved still writes.
        given().contentType(ContentType.JSON).header("If-Match", "\"" + token + "\"")
            .body(Map.of("title", "token stays, renamed"))
            .when().patch("/api/" + SCOPE + "/item/" + item)
            .then().statusCode(200);
    }

    @Test
    void an_item_in_a_closed_status_takes_tags() {
        long item = createItem("finished");
        String token = read(item).jsonPath().getString("fields.conflict_token");
        given().contentType(ContentType.JSON).header("If-Match", "\"" + token + "\"")
            .body(Map.of("status", DONE))
            .when().patch("/api/" + SCOPE + "/item/" + item)
            .then().statusCode(200);

        attach(item, ALPHA).then().statusCode(200);
        assertThat(read(item).jsonPath().getList("fields.tags", String.class))
            .containsExactly(ALPHA);
    }

    // =======================================================================
    // The refusals
    // =======================================================================

    @Test
    void an_unknown_token_is_refused_as_a_value_the_scope_lacks_naming_tag() {
        long item = createItem("unknown token");

        Response refused = attach(item, "no-such-tag");
        refused.then().statusCode(422);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("VALUE_UNDECLARED");
        assertThat(refused.jsonPath().getList("data.offenders", String.class))
            .containsExactly("tag");

        Response overMcp = Mcp.call("worklist_tag_attach",
            Map.of("address", address(item), "tag", "no-such-tag"));
        assertThat(Mcp.reason(overMcp)).isEqualTo("VALUE_UNDECLARED");
    }

    @Test
    void a_withdrawn_token_takes_no_new_assignment_and_still_detaches() {
        long carrier = createItem("carries the retired tag");
        attach(carrier, RETIRED).then().statusCode(200);
        withdrawTag(SCOPE_ID, RETIRED);
        try {
            long fresh = createItem("asks for the retired tag");
            Response refused = attach(fresh, RETIRED);
            refused.then().statusCode(409);
            assertThat(refused.jsonPath().getString("reason")).isEqualTo("TAG_WITHDRAWN");
            assertThat(refused.jsonPath().getString("message"))
                .as("the message says the tag is withdrawn")
                .contains("withdrawn");

            assertThat(read(carrier).jsonPath().getList("fields.tags", String.class))
                .as("an item that carries a withdrawn tag keeps reading it")
                .containsExactly(RETIRED);
            assertThat(query(Map.of("filter.tag", RETIRED)))
                .as("and the filter still finds it")
                .contains(carrier);

            detach(carrier, RETIRED).then().statusCode(200);
            assertThat(read(carrier).jsonPath().getList("fields.tags", String.class))
                .as("a withdrawn tag detaches like any other")
                .isEmpty();
        } finally {
            redeclareTag(SCOPE_ID, RETIRED);
        }
    }

    @Test
    void a_subject_without_the_write_right_is_refused_with_the_right_refusal() {
        long item = createItem("read-only for the muted member");

        SurfaceFixture.asMutedMember(identity);
        Response attached = attach(item, ALPHA);
        Response detached = detach(item, ALPHA);
        Response overMcp = Mcp.call("worklist_tag_attach",
            Map.of("address", address(item), "tag", ALPHA));
        Response readable = read(item);
        SurfaceFixture.asMember(identity);

        attached.then().statusCode(403);
        assertThat(attached.jsonPath().getString("reason")).isEqualTo("SCOPE_READ_ONLY");
        assertThat(detached.jsonPath().getString("reason")).isEqualTo("SCOPE_READ_ONLY");
        assertThat(Mcp.reason(overMcp)).isEqualTo("SCOPE_READ_ONLY");
        readable.then().statusCode(200);
        assertThat(read(item).jsonPath().getList("fields.tags", String.class))
            .as("and nothing was attached").isEmpty();
    }

    /** Acceptance (j): a milestone address is refused, typed, over both surfaces. */
    @Test
    void tag_attach_on_a_milestone_address_is_refused_typed() {
        Response milestone = given().contentType(ContentType.JSON)
            .body(Map.of("title", "a tagged goal", "vision", "the north star"))
            .when().post("/api/" + SCOPE + "/" + Selector.MILESTONE);
        milestone.then().statusCode(201);
        long number = milestone.jsonPath().getLong("fields.number");

        Response overRest = given().contentType(ContentType.JSON).body(Map.of("tag", ALPHA))
            .when().post("/api/" + SCOPE + "/" + Selector.MILESTONE + "/" + number + ":tag_attach");
        overRest.then().statusCode(422);
        assertThat(overRest.jsonPath().getString("reason")).isEqualTo("TAG_ITEM_ONLY");
        assertThat(overRest.jsonPath().getString("message"))
            .as("the message says only items carry tags")
            .contains("only items carry tags");

        Response overMcp = Mcp.call("worklist_tag_attach", Map.of(
            "address", "worklist://" + SCOPE + "/" + Selector.MILESTONE + "/" + number,
            "tag", ALPHA));
        assertThat(Mcp.reason(overMcp)).isEqualTo("TAG_ITEM_ONLY");

        Response detach = Mcp.call("worklist_tag_detach", Map.of(
            "address", "worklist://" + SCOPE + "/" + Selector.ITERATION + "/current",
            "tag", ALPHA));
        assertThat(Mcp.reason(detach)).as("and an iteration address, for tag_detach")
            .isEqualTo("TAG_ITEM_ONLY");
    }

    // =======================================================================
    // The filter
    // =======================================================================

    @Test
    void the_filter_tag_answers_the_items_that_carry_it() {
        long tagged = createItem("carries alpha");
        long other = createItem("carries beta");
        attach(tagged, ALPHA).then().statusCode(200);
        attach(other, BETA).then().statusCode(200);

        List<Long> found = query(Map.of("filter.tag", ALPHA));
        assertThat(found).contains(tagged).doesNotContain(other);

        Map<String, Object> overMcp = Mcp.answer(Mcp.call("worklist_query", Map.of(
            "scope", SCOPE, "selector", Selector.ITEM, "filter", Map.of("tag", ALPHA))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> objects = (List<Map<String, Object>>) overMcp.get("objects");
        assertThat(objects).extracting(o -> String.valueOf(o.get("address")))
            .contains(address(tagged)).doesNotContain(address(other));
    }

    /** Acceptance (k): tag and status narrow together. */
    @Test
    void the_filter_tag_combines_with_status() {
        long openTagged = createItem("open and tagged");
        long doneTagged = createItem("done and tagged");
        long openUntagged = createItem("open, untagged");
        attach(openTagged, BETA).then().statusCode(200);
        attach(doneTagged, BETA).then().statusCode(200);
        String token = read(doneTagged).jsonPath().getString("fields.conflict_token");
        given().contentType(ContentType.JSON).header("If-Match", "\"" + token + "\"")
            .body(Map.of("status", DONE))
            .when().patch("/api/" + SCOPE + "/item/" + doneTagged)
            .then().statusCode(200);

        List<Long> found = query(Map.of("filter.tag", BETA, "filter.status", OPEN));
        assertThat(found)
            .as("tag and status combine by AND")
            .contains(openTagged)
            .doesNotContain(doneTagged, openUntagged);
    }

    @Test
    void the_filter_tag_with_an_unknown_token_answers_an_empty_page() {
        createItem("there is something to list");
        given().queryParam("filter.tag", "no-such-tag")
            .when().get("/api/" + SCOPE + "/" + Selector.ITEM)
            .then().statusCode(200)
            .body("objects.size()", org.hamcrest.Matchers.is(0));
    }

    // =======================================================================
    // Fixtures
    // =======================================================================

    private static long createItem(String title) {
        Response created = given().contentType(ContentType.JSON)
            .body(Map.of("title", title, "status", OPEN))
            .when().post("/api/" + SCOPE + "/" + Selector.ITEM);
        created.then().statusCode(201);
        return created.jsonPath().getLong("fields.number");
    }

    private static Response read(long item) {
        return given().when().get("/api/" + SCOPE + "/" + Selector.ITEM + "/" + item);
    }

    private static Response attach(long item, String tag) {
        return given().contentType(ContentType.JSON).body(Map.of("tag", tag))
            .when().post("/api/" + SCOPE + "/" + Selector.ITEM + "/" + item + ":tag_attach");
    }

    private static Response detach(long item, String tag) {
        return given().contentType(ContentType.JSON).body(Map.of("tag", tag))
            .when().post("/api/" + SCOPE + "/" + Selector.ITEM + "/" + item + ":tag_detach");
    }

    /** The numbers of the items one query answers, on its first page. */
    private static List<Long> query(Map<String, Object> parameters) {
        Response listed = given().queryParams(parameters).queryParam("page_size", 200)
            .when().get("/api/" + SCOPE + "/" + Selector.ITEM);
        listed.then().statusCode(200);
        return listed.jsonPath().getList("objects.fields.number", Long.class);
    }

    private static String address(long item) {
        return "worklist://" + SCOPE + "/" + Selector.ITEM + "/" + item;
    }

    /** A tag in the platform's vocabulary of one scope of the probe tenant. */
    static void declareTag(UUID scope, String token, String status) {
        PlatformFixture.run("INSERT INTO platform.tag (tenant_id, scope_id, token, status, "
            + "created_by) SELECT '" + SubstrateDatabaseResource.TENANT_ID + "', '" + scope
            + "', '" + token + "', '" + status + "', 'probe-admin' WHERE NOT EXISTS (SELECT 1 "
            + "FROM platform.tag WHERE scope_id = '" + scope + "' AND token = '" + token + "')");
    }

    private static void withdrawTag(UUID scope, String token) {
        PlatformFixture.run("UPDATE platform.tag SET status = 'withdrawn', withdrawn_at = now() "
            + "WHERE scope_id = '" + scope + "' AND token = '" + token + "'");
    }

    private static void redeclareTag(UUID scope, String token) {
        PlatformFixture.run("UPDATE platform.tag SET status = 'declared', withdrawn_at = NULL "
            + "WHERE scope_id = '" + scope + "' AND token = '" + token + "'");
    }
}
