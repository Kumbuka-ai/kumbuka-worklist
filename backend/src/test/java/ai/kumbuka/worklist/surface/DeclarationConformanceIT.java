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
 * <p>Compared per verb: the identity, the effect class in the router's names,
 * the shape of the answer, every argument (name, type, requiredness, default,
 * value set) and the set of reasons a refusal may name. Beside it: every text
 * is present, every optional argument names its default and what null does
 * (DEC-0043 R17), every reason of a verb is in the catalogue with its status,
 * message and remedy, and the version is a hash over the content -- recomputed
 * here with a canonical writer of this probe's own -- and travels as the
 * entity tag.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@CounterProbe
class DeclarationConformanceIT {

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void member() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_declaration_carries_every_verb_as_the_transcription_holds_it() {
        Map<String, Object> document = declaration().jsonPath().getMap("$");
        List<Map<String, Object>> verbs = (List<Map<String, Object>>) document.get("verbs");
        SoftAssertions soft = new SoftAssertions();

        soft.assertThat(document.get("scheme")).isEqualTo(DeclarationSpecification.SCHEME);
        soft.assertThat(verbs.stream().map(v -> v.get("identity")).toList())
            .as("the verbs, by their stable identity worklist_<verb>, in order")
            .containsExactlyElementsOf(DeclarationSpecification.VERBS.stream()
                .map(DeclarationSpecification.Verb::identity).toList());

        Map<String, DeclarationSpecification.Verb> expected =
            DeclarationSpecification.byIdentity();
        for (Map<String, Object> verb : verbs) {
            String id = String.valueOf(verb.get("identity"));
            DeclarationSpecification.Verb spec = expected.get(id);
            if (spec == null) {
                continue;
            }
            soft.assertThat(verb.get("effect_class")).as("%s effect class", id)
                .isEqualTo(spec.effectClass());
            soft.assertThat(DeclarationSpecification.EFFECT_CLASSES)
                .as("%s: the effect class is one the router knows", id)
                .contains(String.valueOf(verb.get("effect_class")));
            soft.assertThat(verb.get("answer")).as("%s answer", id)
                .isEqualTo(spec.answer());
            soft.assertThat(String.valueOf(verb.get("description")))
                .as("%s carries its description", id).isNotBlank();
            soft.assertThat(new LinkedHashSet<>((List<String>) verb.get("reasons")))
                .as("%s reasons", id)
                .isEqualTo(new LinkedHashSet<>(spec.reasons()));

            List<Map<String, Object>> args = (List<Map<String, Object>>) verb.get("arguments");
            soft.assertThat(args.stream().map(a -> a.get("name")).toList())
                .as("%s argument names, in order", id)
                .containsExactlyElementsOf(spec.args().stream()
                    .map(DeclarationSpecification.Arg::name).toList());
            for (int i = 0; i < Math.min(args.size(), spec.args().size()); i++) {
                Map<String, Object> arg = args.get(i);
                DeclarationSpecification.Arg want = spec.args().get(i);
                String where = id + "." + want.name();
                soft.assertThat(arg.get("type")).as("%s type", where).isEqualTo(want.type());
                soft.assertThat(arg.get("required")).as("%s required", where)
                    .isEqualTo(want.required());
                soft.assertThat(arg.get("default")).as("%s default", where)
                    .isEqualTo(want.defaultValue());
                List<Map<String, Object>> values = (List<Map<String, Object>>) arg.get("values");
                soft.assertThat(values.stream().map(v -> v.get("value")).toList())
                    .as("%s value set", where).containsExactlyElementsOf(want.values());
                values.forEach(v -> soft.assertThat(String.valueOf(v.get("description")))
                    .as("%s value %s is described", where, v.get("value")).isNotBlank());
                String description = String.valueOf(arg.get("description"));
                soft.assertThat(description).as("%s is described", where).isNotBlank();
                if (!want.required()) {
                    soft.assertThat(description).as("%s names its default", where)
                        .contains("Default");
                    soft.assertThat(description).as("%s says what null does", where)
                        .contains("null");
                }
            }
        }
        soft.assertAll();
    }

    @Test
    @SuppressWarnings("unchecked")
    void every_reason_a_verb_names_is_in_the_catalogue_with_its_texts() {
        Map<String, Object> document = declaration().jsonPath().getMap("$");
        Map<String, Map<String, Object>> catalogue = new LinkedHashMap<>();
        ((List<Map<String, Object>>) document.get("reasons"))
            .forEach(r -> catalogue.put(String.valueOf(r.get("code")), r));
        SoftAssertions soft = new SoftAssertions();
        Set<String> named = new LinkedHashSet<>();
        DeclarationSpecification.VERBS.forEach(v -> named.addAll(v.reasons()));
        soft.assertThat(catalogue.keySet()).as("the catalogue holds every reason a verb names")
            .containsAll(named);
        catalogue.forEach((code, reason) -> {
            soft.assertThat(reason.get("status")).as("%s status", code).isInstanceOf(Integer.class);
            soft.assertThat(String.valueOf(reason.get("message"))).as("%s message", code)
                .isNotBlank().isNotEqualTo("null");
            soft.assertThat(String.valueOf(reason.get("remedy"))).as("%s remedy", code)
                .isNotBlank().isNotEqualTo("null");
        });
        soft.assertThat(catalogue.get("NOT_FOUND"))
            .as("the not-found class answers 404, as on every hop")
            .containsEntry("status", 404);
        soft.assertAll();
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_version_is_a_hash_over_the_content_and_travels_as_the_entity_tag() throws Exception {
        ExtractableResponse<Response> first = declaration();
        ExtractableResponse<Response> second = declaration();
        Map<String, Object> document = first.jsonPath().getMap("$");
        String version = String.valueOf(document.get("version"));

        assertThat(version).as("a SHA-256 in lower-case hex").matches(HEX64);
        assertThat(second.jsonPath().getString("version"))
            .as("the same declaration answers the same version").isEqualTo(version);
        assertThat(first.header("ETag")).as("and the version is the entity tag")
            .contains(version);

        Map<String, Object> content = new LinkedHashMap<>(document);
        content.remove("scheme");
        content.remove("version");
        String recomputed = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(canonical(content).getBytes(StandardCharsets.UTF_8)));
        assertThat(version)
            .as("the version is the hash of the verbs and the reasons written canonically, so "
                + "any change to either changes it")
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
