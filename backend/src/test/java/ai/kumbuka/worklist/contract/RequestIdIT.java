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
import ai.kumbuka.worklist.surface.SurfaceFixture;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every call carries a request id (C14.2): the service takes {@code
 * Kumbuka-Request-Id} where it is a UUID and mints one otherwise, holds it in
 * the MDC as {@code request_id} for the duration of the call, and returns it
 * in the response header, on an answer and on a refusal alike, on both
 * surfaces.
 *
 * <p>A value that is no UUID is caller input that names nothing the service
 * minted, so it is neither adopted nor echoed: the response carries a fresh
 * one. The log side is read from the records the call itself writes, by the
 * MDC each record carries, not from the formatted line.
 *
 * <p>RED STATE, observed 2026-10-29 against the tree without the filter: no
 * response carried the header, and the record of the creation carried no
 * {@code request_id}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class RequestIdIT {

    private static final String HEADER = "Kumbuka-Request-Id";
    private static final UUID SCOPE_ID = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);
    private static final Pattern UUID_FORM = Pattern.compile(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @Inject TestIdentityAssociation identity;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;

    private String status;

    private final List<ExtLogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord logged) {
            if (logged instanceof ExtLogRecord ext) {
                ext.copyMdc();
                records.add(ext);
            }
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing held
        }
    };
    private final Logger service = Logger.getLogger("ai.kumbuka.worklist");

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);
        selectors.open(SCOPE_ID, Selector.ITEM);
        status = vocabulary.declareStatus(SCOPE_ID, "request-id-open", 1, true, false, false,
            false).name;
        capture.setLevel(Level.ALL);
        service.addHandler(capture);
    }

    @AfterEach
    void release() {
        service.removeHandler(capture);
    }

    @Test
    void a_uuid_the_caller_sends_is_taken_and_returned() {
        String sent = UUID.randomUUID().toString();
        Response answered = given().header(HEADER, sent)
            .get(SurfaceFixture.collection(Selector.ITEM));
        assertThat(answered.statusCode()).as(answered.asString()).isEqualTo(200);
        assertThat(answered.header(HEADER)).isEqualTo(sent);

        Response refused = given().header(HEADER, sent)
            .get(SurfaceFixture.item(Selector.ITEM, 99_999));
        assertThat(refused.statusCode()).isEqualTo(404);
        assertThat(refused.header(HEADER)).as("a refusal carries it too").isEqualTo(sent);
    }

    @Test
    void anything_but_a_uuid_is_replaced_by_a_fresh_one() {
        for (String sent : List.of("zz-not-a-uuid", "1-1-1-1-1", "")) {
            Response answered = given().header(HEADER, sent)
                .get(SurfaceFixture.collection(Selector.ITEM));
            assertThat(answered.header(HEADER)).as("for %s", sent)
                .matches(UUID_FORM).isNotEqualTo(sent);
        }
        Response unsent = given().get(SurfaceFixture.collection(Selector.ITEM));
        assertThat(unsent.header(HEADER)).as("none sent, one minted").matches(UUID_FORM);
        assertThat(given().get(SurfaceFixture.collection(Selector.ITEM)).header(HEADER))
            .as("each call its own").isNotEqualTo(unsent.header(HEADER));
    }

    @Test
    void the_call_s_log_lines_carry_the_request_id() {
        String sent = UUID.randomUUID().toString();
        Response created = given().header(HEADER, sent).contentType(ContentType.JSON)
            .body(Map.of("title", "logged", "status", status))
            .post(SurfaceFixture.collection(Selector.ITEM));
        assertThat(created.statusCode()).as(created.asString()).isEqualTo(201);

        List<ExtLogRecord> creation = records.stream()
            .filter(r -> r.getFormattedMessage().startsWith("create "))
            .toList();
        assertThat(creation).as("the creation wrote its line").isNotEmpty();
        assertThat(creation).allSatisfy(r ->
            assertThat(r.getMdc("request_id")).as("the line's request_id").isEqualTo(sent));
    }

    @Test
    void the_assistant_surface_carries_it_too() {
        String sent = UUID.randomUUID().toString();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", "tools/call");
        envelope.put("params", Map.of("name", "worklist_query", "arguments",
            Map.of("scope", SurfaceFixture.SCOPE, "selector", Selector.ITEM)));
        Response answered = given().header(HEADER, sent).contentType(ContentType.JSON)
            .body(envelope).post("/mcp");
        assertThat(answered.statusCode()).isEqualTo(200);
        assertThat(answered.header(HEADER)).isEqualTo(sent);
        assertThat(given().contentType(ContentType.JSON).body(envelope).post("/mcp")
            .header(HEADER)).matches(UUID_FORM);
    }
}
