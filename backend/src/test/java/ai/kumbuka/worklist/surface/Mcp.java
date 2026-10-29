/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import io.restassured.http.ContentType;
import io.restassured.response.Response;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service's own assistant surface, as the probes of the protocol and of
 * the surface header call it: one JSON-RPC request at {@code POST /mcp}, and
 * the parts of its answer a probe compares.
 */
public final class Mcp {

    private Mcp() {
    }

    /** One JSON-RPC request with an id; the HTTP status of a request is always 200. */
    public static Response rpc(String method, Map<String, Object> params) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", method);
        envelope.put("params", params);
        Response answer = given().contentType(ContentType.JSON).body(envelope).post("/mcp");
        assertThat(answer.statusCode()).as("JSON-RPC answers 200: %s", answer.asString())
            .isEqualTo(200);
        return answer;
    }

    /** One tool call. */
    public static Response call(String tool, Map<String, Object> arguments) {
        return rpc("tools/call", Map.of("name", tool, "arguments", arguments));
    }

    /** The answer of a call that was carried out. */
    public static Map<String, Object> answer(Response call) {
        assertThat(call.jsonPath().getBoolean("result.isError")).as("%s", call.asString())
            .isFalse();
        return call.jsonPath().getMap("result.structuredContent");
    }

    /** The envelope of a refused call. */
    public static Map<String, Object> refusal(Response call) {
        assertThat(call.jsonPath().getBoolean("result.isError")).as("%s", call.asString())
            .isTrue();
        return call.jsonPath().getMap("result.structuredContent");
    }

    /** The reason of a refused call. */
    public static String reason(Response call) {
        return String.valueOf(refusal(call).get("reason"));
    }
}
