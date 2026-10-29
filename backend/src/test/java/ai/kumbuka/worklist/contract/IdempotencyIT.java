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
import ai.kumbuka.worklist.platform.PlatformFixture;
import ai.kumbuka.worklist.surface.Mcp;
import ai.kumbuka.worklist.surface.SurfaceFixture;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code create} takes an idempotency key (C10.4): on REST the header
 * {@code Idempotency-Key}, on MCP the argument {@code idempotency_key}.
 *
 * <ul>
 *   <li>A repeat with the same values answers the object the first call made,
 *       and no second object comes into being.</li>
 *   <li>A repeat with other values, or in another view, is refused with
 *       {@code IDEMPOTENCY_KEY_REUSED} 409 and creates nothing.</li>
 *   <li>A repeat after the object it produced is gone is refused the same way;
 *       the object is not made again (decision 204.29 on O-10).</li>
 *   <li>After 24 hours the key speaks for nothing, and the call creates.</li>
 * </ul>
 *
 * <p>The object is removed by the database's superuser: no verb of this
 * service removes one, which is the reason the ledger cannot rely on a removal
 * it would see.
 *
 * <p>RED STATE, observed 2026-10-29 with the repeat path made to create anew
 * where the object is gone ({@code IdempotencyService.firstAnswer} answering
 * empty in place of the refusal):
 * {@link #a_repeat_after_the_object_is_gone_is_refused_and_creates_nothing}
 * turned red, the repeat over MCP answering as a success, with no
 * {@code isError}, where it is refused; with the refusal back it is green.
 * With the fields map dropping {@code null} at the top only, as at
 * {@code 0fb4862}, {@link #a_repeat_that_spells_an_absent_field_as_null_is_the_same_call}
 * turned red: the repeat was refused as {@code IDEMPOTENCY_KEY_REUSED}, its
 * digest reading the null label as another value.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class IdempotencyIT {

    private static final String SCOPE = "idempotency-probe";
    private static final UUID SCOPE_ID = UUID.fromString("1d3e5f70-2a4c-4e6a-8b0d-9c1e2f3a4b5c");
    private static final String KEY_HEADER = "Idempotency-Key";

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
            selectors.open(SCOPE_ID, Selector.ITERATION);
            vocabulary.declareStatus(SCOPE_ID, "open", 1, true, false, false, false);
            workstreams.requireDefault(SCOPE_ID);
            staged = true;
        }
    }

    @Test
    void a_repeat_with_the_same_values_answers_the_first_object_on_both_surfaces() {
        String key = key();
        Map<String, Object> fields = Map.of("title", "made once", "status", "open");
        long before = items();

        Map<String, Object> first = Mcp.answer(mcpCreate(Selector.ITEM, fields, key));
        Map<String, Object> again = Mcp.answer(mcpCreate(Selector.ITEM, fields, key));
        assertThat(again.get("address")).as("the repeat answers the first object")
            .isEqualTo(first.get("address"));
        assertThat(again.get("conflict_token")).isEqualTo(first.get("conflict_token"));

        String restKey = key();
        Response restFirst = restCreate(fields, restKey);
        Response restAgain = restCreate(fields, restKey);
        assertThat(restFirst.statusCode()).isEqualTo(201);
        assertThat(restAgain.statusCode()).as("the first result, again: %s", restAgain.asString())
            .isEqualTo(201);
        assertThat(restAgain.jsonPath().getString("address"))
            .isEqualTo(restFirst.jsonPath().getString("address"));
        assertThat(restAgain.header("Location")).isEqualTo(restFirst.header("Location"));

        assertThat(items()).as("two keys, two objects, four calls").isEqualTo(before + 2);
    }

    @Test
    void a_repeat_that_spells_an_absent_field_as_null_is_the_same_call() {
        String key = key();
        Map<String, Object> plain = Map.of("title", "null is absent", "status", "open",
            "references", List.of(Map.of("target", "https://example.org/a")));
        Map<String, Object> labelNull = new HashMap<>(Map.of("target", "https://example.org/a"));
        labelNull.put("label", null);
        Map<String, Object> nulled = new HashMap<>(plain);
        nulled.put("description", null);
        nulled.put("references", List.of(labelNull));

        Map<String, Object> first = Mcp.answer(mcpCreate(Selector.ITEM, plain, key));
        Map<String, Object> again = Mcp.answer(mcpCreate(Selector.ITEM, nulled, key));
        assertThat(again.get("address")).isEqualTo(first.get("address"));
    }

    @Test
    void a_repeat_with_other_values_or_in_another_view_is_refused_and_creates_nothing() {
        String key = key();
        Mcp.answer(mcpCreate(Selector.ITEM, Map.of("title", "first", "status", "open"), key));
        long before = items();

        Response otherValues = mcpCreate(Selector.ITEM,
            Map.of("title", "second", "status", "open"), key);
        Response otherView = mcpCreate(Selector.ITERATION,
            Map.of("title", "an iteration", "motto", "m", "description", "d"), key);
        Response overRest = restCreate(Map.of("title", "third", "status", "open"), key);

        assertThat(Mcp.reason(otherValues)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(Mcp.reason(otherView)).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(overRest.statusCode()).isEqualTo(409);
        assertThat(overRest.jsonPath().getString("reason")).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(overRest.jsonPath().getMap("data"))
            .as("no data beyond attempted (C8.7)").containsOnlyKeys("attempted");
        assertThat(items()).as("nothing was carried out").isEqualTo(before);
    }

    @Test
    void a_repeat_after_the_object_is_gone_is_refused_and_creates_nothing() {
        String key = key();
        Map<String, Object> fields = Map.of("title", "to be removed", "status", "open");
        Map<String, Object> first = Mcp.answer(mcpCreate(Selector.ITEM, fields, key));
        String id = String.valueOf(fieldsOf(first).get("id"));
        PlatformFixture.run("DELETE FROM worklist.item WHERE id = '" + id + "'");
        long before = items();

        Response again = mcpCreate(Selector.ITEM, fields, key);
        assertThat(Mcp.refusal(again).get("reason"))
            .as("an object that is gone is not made again: %s", again.asString())
            .isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(items()).isEqualTo(before);
    }

    @Test
    void after_twenty_four_hours_the_key_speaks_for_nothing_and_the_call_creates() {
        String key = key();
        Map<String, Object> fields = Map.of("title", "an old key", "status", "open");
        Map<String, Object> first = Mcp.answer(mcpCreate(Selector.ITEM, fields, key));
        PlatformFixture.run("UPDATE worklist.idempotency_key "
            + "SET first_seen_at = now() - interval '24 hours 1 second' "
            + "WHERE idempotency_key = '" + key + "'");

        Map<String, Object> later = Mcp.answer(mcpCreate(Selector.ITEM, fields, key));
        assertThat(later.get("address")).as("a new object").isNotEqualTo(first.get("address"));
        Map<String, Object> repeat = Mcp.answer(mcpCreate(Selector.ITEM, fields, key));
        assertThat(repeat.get("address")).as("and the key now speaks for it")
            .isEqualTo(later.get("address"));
    }

    @Test
    void the_ledger_holds_a_digest_and_an_identity_and_nothing_the_caller_wrote() {
        String key = key();
        String title = "a title the ledger never holds " + UUID.randomUUID();
        Map<String, Object> first = Mcp.answer(mcpCreate(Selector.ITEM,
            Map.of("title", title, "status", "open"), key));

        List<Map<String, Object>> rows = select("SELECT * FROM worklist.idempotency_key "
            + "WHERE idempotency_key = '" + key + "'");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("argument_digest")).asString().startsWith("sha256:");
        assertThat(row.get("produced_view")).isEqualTo(Selector.ITEM);
        assertThat(String.valueOf(row.get("produced_id")))
            .isEqualTo(String.valueOf(fieldsOf(first).get("id")));
        assertThat(row.values()).as("no column carries the title")
            .noneMatch(value -> String.valueOf(value).contains(title));
        assertThat(row).as("and none names the caller")
            .doesNotContainKeys("caller_subject", "subject", "actor");
    }

    @Test
    void a_key_out_of_form_is_refused_before_anything_is_written() {
        Map<String, Object> fields = Map.of("title", "out of form", "status", "open");
        long before = items();
        assertThat(Mcp.reason(mcpCreate(Selector.ITEM, fields, " "))).isEqualTo("ARGUMENT_INVALID");
        assertThat(Mcp.reason(mcpCreate(Selector.ITEM, fields, "k".repeat(256))))
            .isEqualTo("ARGUMENT_INVALID");
        Map<String, Object> arguments = createArguments(Selector.ITEM, fields);
        arguments.put("idempotency_key", 7);
        assertThat(Mcp.reason(Mcp.call("worklist_create", arguments)))
            .isEqualTo("ARGUMENT_INVALID");
        assertThat(Mcp.refusal(mcpCreate(Selector.ITEM, fields, " ")).get("data"))
            .isEqualTo(Map.of("attempted", "worklist_create", "offenders",
                List.of("idempotency_key")));
        assertThat(items()).isEqualTo(before);
    }

    // ======================================================================

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static Map<String, Object> createArguments(String view, Map<String, Object> fields) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("scope", SCOPE);
        arguments.put("selector", view);
        arguments.put("fields", fields);
        return arguments;
    }

    private static Response mcpCreate(String view, Map<String, Object> fields, String key) {
        Map<String, Object> arguments = createArguments(view, fields);
        arguments.put("idempotency_key", key);
        return Mcp.call("worklist_create", arguments);
    }

    private static Response restCreate(Map<String, Object> fields, String key) {
        return given().contentType(ContentType.JSON).header(KEY_HEADER, key).body(fields)
            .post("/api/" + SCOPE + "/" + Selector.ITEM);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldsOf(Map<String, Object> answer) {
        return (Map<String, Object>) answer.get("fields");
    }

    /** How many items the scope holds, counted by the superuser past every policy. */
    private static long items() {
        return ((Number) select("SELECT count(*) AS n FROM worklist.item WHERE scope_id = '"
            + SCOPE_ID + "'").get(0).get("n")).longValue();
    }

    /** Rows read as the database's superuser, which the service never is. */
    private static List<Map<String, Object>> select(String query) {
        var config = ConfigProvider.getConfig();
        try (Connection c = DriverManager.getConnection(
                config.getValue("test.db.url", String.class),
                config.getValue("test.db.admin.username", String.class),
                config.getValue("test.db.admin.password", String.class));
             Statement s = c.createStatement();
             ResultSet rows = s.executeQuery(query)) {
            List<Map<String, Object>> out = new ArrayList<>();
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columns; i++) {
                    row.put(rows.getMetaData().getColumnName(i), rows.getObject(i));
                }
                out.add(row);
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("the ledger could not be read: " + e.getMessage(), e);
        }
    }
}
