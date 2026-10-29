/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.CounterProbe;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The counter-probe of ADR-0039: the running service's {@code GET
 * /api/declaration} against {@link DeclarationSpecification}, an independent
 * transcription in the test tree that imports nothing from the production
 * packages.
 *
 * <p>Compared on the top level: the shape version, the service, the scheme,
 * the address form and the effect classes. Per call: the identity and call
 * name, the REST name, the effect class in the router's names, the shape of
 * the answer with its members, every argument (name, type, requiredness,
 * placement, default in its own type, fixed values with a description each)
 * and the set of reasons a refusal may name. Beside it: every text is present,
 * every optional argument names its default and what null does (DEC-0043
 * R17), every reason of a call is in the catalogue with its message templates
 * and remedy, and the version is {@code sha256:} and the hash over everything
 * else -- recomputed here with a canonical writer of this probe's own -- and
 * travels as the entity tag.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@CounterProbe
class DeclarationConformanceIT {

    private static final Pattern VERSION = Pattern.compile("sha256:[0-9a-f]{64}");

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void member() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);
    }

    @Test
    void the_top_level_names_the_shape_the_service_and_its_address_form() {
        Map<String, Object> document = declaration().jsonPath().getMap("$");
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(document.get("shape_version")).isEqualTo(
            DeclarationSpecification.SHAPE_VERSION);
        soft.assertThat(document.get("service")).isEqualTo(DeclarationSpecification.SCHEME);
        soft.assertThat(document.get("scheme")).isEqualTo(DeclarationSpecification.SCHEME);
        soft.assertThat(document.get("address_form"))
            .isEqualTo(DeclarationSpecification.ADDRESS_FORM);
        soft.assertThat(document).as("worklist has no technical address")
            .doesNotContainKey("technical_address_form");
        soft.assertThat(document.get("effect_classes"))
            .isEqualTo(DeclarationSpecification.DECLARED_EFFECT_CLASSES);
        soft.assertThat(DeclarationSpecification.EFFECT_CLASSES)
            .as("every declared effect class is one the router knows")
            .containsAll(DeclarationSpecification.DECLARED_EFFECT_CLASSES);
        soft.assertAll();
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_declaration_carries_every_call_as_the_transcription_holds_it() {
        Map<String, Object> document = declaration().jsonPath().getMap("$");
        List<Map<String, Object>> calls = (List<Map<String, Object>>) document.get("calls");
        SoftAssertions soft = new SoftAssertions();

        soft.assertThat(calls.stream().map(v -> v.get("identity")).toList())
            .as("the calls, by their stable identity worklist_<verb>, in order")
            .containsExactlyElementsOf(DeclarationSpecification.VERBS.stream()
                .map(DeclarationSpecification.Verb::identity).toList());

        Map<String, DeclarationSpecification.Verb> expected =
            DeclarationSpecification.byIdentity();
        for (Map<String, Object> call : calls) {
            String id = String.valueOf(call.get("identity"));
            DeclarationSpecification.Verb spec = expected.get(id);
            if (spec == null) {
                continue;
            }
            soft.assertThat(call.get("call")).as("%s call name", id).isEqualTo(id);
            soft.assertThat(call.get("rest_name")).as("%s REST name", id)
                .isEqualTo(spec.restName());
            soft.assertThat(call.get("effect_class")).as("%s effect class", id)
                .isEqualTo(spec.effectClass());
            soft.assertThat(call.get("answer")).as("%s answer", id)
                .isEqualTo(Map.of("shape", spec.answer(),
                    "members", DeclarationSpecification.ANSWER_MEMBERS.get(spec.answer())));
            soft.assertThat(String.valueOf(call.get("description")))
                .as("%s carries its description", id).isNotBlank();
            soft.assertThat(new LinkedHashSet<>((List<String>) call.get("refusals")))
                .as("%s refusals", id)
                .isEqualTo(new LinkedHashSet<>(spec.reasons()));
            soft.assertThat(call).as("%s carries no member of a later step", id)
                .doesNotContainKeys("transition", "next_step", "verb", "reasons");

            List<Map<String, Object>> args = (List<Map<String, Object>>) call.get("arguments");
            soft.assertThat(args.stream().map(a -> a.get("name")).toList())
                .as("%s argument names, in order", id)
                .containsExactlyElementsOf(spec.args().stream()
                    .map(DeclarationSpecification.Arg::name).toList());
            for (int i = 0; i < Math.min(args.size(), spec.args().size()); i++) {
                argument(soft, id, args.get(i), spec.args().get(i));
            }
        }
        soft.assertAll();
    }

    /** The bounds an argument is held to, by the member of C5.4 each travels under. */
    private static Map<String, Integer> bounds(DeclarationSpecification.Arg want) {
        Map<String, Integer> bounds = new java.util.LinkedHashMap<>();
        bounds.put("minimum", want.minimum());
        bounds.put("maximum", want.maximum());
        return bounds;
    }

    @SuppressWarnings("unchecked")
    private static void argument(SoftAssertions soft, String id, Map<String, Object> arg,
                                 DeclarationSpecification.Arg want) {
        String where = id + "." + want.name();
        soft.assertThat(arg.get("type")).as("%s type", where).isEqualTo(want.type());
        soft.assertThat(arg.get("required")).as("%s required", where)
            .isEqualTo(want.required());
        soft.assertThat(arg.get("placement")).as("%s placement", where)
            .isEqualTo(DeclarationSpecification.PLACEMENT);
        if (want.defaultValue() == null) {
            soft.assertThat(arg).as("%s declares no default", where)
                .doesNotContainKey("default");
        } else {
            soft.assertThat(arg.get("default")).as("%s default, in its own type", where)
                .isEqualTo(want.defaultValue());
        }
        for (Map.Entry<String, Integer> bound : bounds(want).entrySet()) {
            if (bound.getValue() == null) {
                soft.assertThat(arg).as("%s declares no %s", where, bound.getKey())
                    .doesNotContainKey(bound.getKey());
            } else {
                soft.assertThat(arg.get(bound.getKey())).as("%s %s", where, bound.getKey())
                    .isEqualTo(bound.getValue());
            }
        }
        if (want.values().isEmpty()) {
            soft.assertThat(arg).as("%s has no fixed values", where)
                .doesNotContainKeys("values", "value_descriptions");
        } else {
            soft.assertThat((List<String>) arg.get("values")).as("%s values", where)
                .containsExactlyElementsOf(want.values());
            List<Map<String, Object>> described =
                (List<Map<String, Object>>) arg.get("value_descriptions");
            soft.assertThat(described.stream().map(v -> v.get("value")).toList())
                .as("%s every value is described, in order", where)
                .containsExactlyElementsOf(want.values());
            described.forEach(v -> soft.assertThat(String.valueOf(v.get("description")))
                .as("%s value %s is described", where, v.get("value")).isNotBlank());
        }
        String description = String.valueOf(arg.get("description"));
        soft.assertThat(description).as("%s is described", where).isNotBlank();
        if (!want.required()) {
            soft.assertThat(description).as("%s names its default", where)
                .contains("Default");
            soft.assertThat(description).as("%s says what null does", where)
                .contains("null");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void every_reason_a_call_names_is_in_the_catalogue_with_its_texts() {
        Map<String, Object> document = declaration().jsonPath().getMap("$");
        Map<String, Map<String, Object>> catalogue = new LinkedHashMap<>();
        ((List<Map<String, Object>>) document.get("reasons"))
            .forEach(r -> catalogue.put(String.valueOf(r.get("reason")), r));
        SoftAssertions soft = new SoftAssertions();
        Set<String> named = new LinkedHashSet<>();
        DeclarationSpecification.VERBS.forEach(v -> named.addAll(v.reasons()));
        soft.assertThat(catalogue.keySet()).as("the catalogue holds every reason a call names")
            .containsAll(named);
        catalogue.forEach((code, reason) -> {
            String pattern = String.valueOf(reason.get("message_pattern"));
            soft.assertThat(pattern).as("%s message pattern", code)
                .isNotBlank().isNotEqualTo("null");
            List<Map<String, Object>> messages = (List<Map<String, Object>>) reason.get("messages");
            soft.assertThat(messages).as("%s messages", code).isNotEmpty();
            soft.assertThat(messages.get(0).get("template"))
                .as("%s: the pattern is the first message's template", code).isEqualTo(pattern);
            messages.forEach(m -> soft.assertThat(String.valueOf(m.get("key")))
                .as("%s message key", code).isNotBlank().isNotEqualTo("null"));
            soft.assertThat(String.valueOf(reason.get("remedy"))).as("%s remedy", code)
                .isNotBlank().isNotEqualTo("null");
        });
        soft.assertAll();
    }

    @Test
    void the_version_is_a_hash_over_the_content_and_travels_as_the_entity_tag() throws Exception {
        ExtractableResponse<Response> first = declaration();
        ExtractableResponse<Response> second = declaration();
        Map<String, Object> document = first.jsonPath().getMap("$");
        String version = String.valueOf(document.get("version"));

        assertThat(version).as("sha256: and a SHA-256 in lower-case hex").matches(VERSION);
        assertThat(second.jsonPath().getString("version"))
            .as("the same declaration answers the same version").isEqualTo(version);
        assertThat(first.header("ETag")).as("and the version is the entity tag")
            .contains(version);

        Map<String, Object> content = new LinkedHashMap<>(document);
        content.remove("version");
        String recomputed = "sha256:" + HexFormat.of().formatHex(MessageDigest
            .getInstance("SHA-256").digest(canonical(content).getBytes(StandardCharsets.UTF_8)));
        assertThat(version)
            .as("the version is the hash of everything but itself, the shape version and the "
                + "scheme included, written canonically, so any change to any of it changes it")
            .isEqualTo(recomputed);
    }

    // ======================================================================
    // Reading
    // ======================================================================

    private static ExtractableResponse<Response> declaration() {
        return given().when().get("/api/declaration").then().statusCode(200).extract();
    }

    /** This probe's own canonical JSON: object members sorted, no whitespace. */
    @SuppressWarnings("unchecked")
    private static String canonical(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            new TreeMap<>((Map<String, Object>) map).forEach((k, v) -> {
                if (out.length() > 1) {
                    out.append(',');
                }
                out.append(quoted(k)).append(':').append(canonical(v));
            });
            return out.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                out.append(i > 0 ? "," : "").append(canonical(list.get(i)));
            }
            return out.append(']').toString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return quoted(value.toString());
    }

    private static String quoted(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c < 0x20 ? String.format("\\u%04x", (int) c)
                    : String.valueOf(c));
            }
        }
        return out.append('"').toString();
    }
}
