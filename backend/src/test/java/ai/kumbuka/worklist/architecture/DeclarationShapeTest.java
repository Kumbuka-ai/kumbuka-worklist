/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.architecture;

import ai.kumbuka.worklist.CounterProbe;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape of a service declaration, against a schema held in the test tree
 * (C5.3, C5.4, C5.5).
 *
 * <p>The schema is {@code contract/declaration-shape.schema.json}, written
 * from the contract and from nothing this service produces; its
 * {@code $comment} names the commit of the contract it was written from and
 * the members it admits beyond the enumeration. It closes every level the
 * contract enumerates, so a member the contract does not name is reported as
 * much as one it requires and that is missing. The {@code shape_version} is
 * pinned to the one value C5.5 names for every service, so the expectation
 * stands here and not in the code that writes the declaration.
 *
 * <p>Two rules are beyond what the schema subset says and are checked here:
 * an argument's {@code default} is a value of the argument's own JSON type,
 * never its text (C5.4); and no member on any level carries {@code null} --
 * a member without a value is left out, and the answers of this service are
 * written that way (C7.2), so a {@code null} in the declaration is a member
 * its version was taken over and the wire never carries.
 *
 * <h2>Where the served declaration is read</h2>
 *
 * The declaration carries the texts of its message bundles, which only the
 * running service renders. So this class holds the schema and the check and
 * observes the check refusing planted declarations on every build; the
 * declaration the service serves is checked by {@link #violations(Map)} from
 * {@code DeclarationAccessIT}, which reads it from {@code GET /api/declaration}.
 *
 * <p>The validator reads the keywords the schema uses and no others; a
 * keyword it does not read is refused rather than ignored, so the schema
 * cannot say more than is checked.
 */
@CounterProbe
public class DeclarationShapeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final List<String> KEYWORDS = List.of("$comment", "type", "required",
        "properties", "additionalProperties", "items", "minItems", "enum", "const", "pattern");

    /** The violations of one declaration, by JSON path; empty where it conforms. */
    public static List<String> violations(Map<String, Object> declaration) {
        List<String> found = new ArrayList<>();
        nulls(declaration, "$", found);
        check(schema(), declaration, "$", found);
        defaultsInTheirOwnType(declaration, found);
        return found;
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_declaration_of_the_contract_shape_conforms() {
        assertThat(violations(conforming()))
            .as("a declaration written to C5.3 and C5.4 must pass the schema, or the schema "
                + "says something the contract does not")
            .isEmpty();

        Map<String, Object> bounded = conforming();
        Map<String, Object> pageSize = argument(bounded);
        pageSize.put("name", "page_size");
        pageSize.put("type", "integer");
        pageSize.put("required", false);
        pageSize.put("default", 50);
        pageSize.put("minimum", 1);
        pageSize.put("maximum", 200);
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("name", "tags");
        tags.put("type", "array");
        tags.put("required", false);
        tags.put("placement", "TOP");
        tags.put("min_items", 1);
        ((List<Object>) call(bounded).get("arguments")).add(tags);
        assertThat(violations(bounded))
            .as("bounds in the declaration's spelling are members of an argument (C5.4)")
            .isEmpty();
    }

    /** The red state, observed on every build: one planted defect per rule. */
    @Test
    void the_check_reports_each_way_a_declaration_leaves_the_contract_shape() {
        Map<String, Object> lowerPlacement = conforming();
        argument(lowerPlacement).put("placement", "top");
        assertThat(violations(lowerPlacement))
            .as("RED STATE, observed: a placement written in lower case")
            .containsExactly(
                "$.calls[0].arguments[0].placement: 'top' is not one of [TOP, FIELDS]");

        Map<String, Object> textDefault = conforming();
        Map<String, Object> pageSize = argument(textDefault);
        pageSize.put("type", "integer");
        pageSize.put("required", false);
        pageSize.put("default", "50");
        assertThat(violations(textDefault))
            .as("RED STATE, observed: an integer argument whose default is its text")
            .containsExactly("$.calls[0].arguments[0].default: a string where the argument "
                + "is integer");

        Map<String, Object> nullDefault = conforming();
        argument(nullDefault).put("default", null);
        assertThat(violations(nullDefault))
            .as("RED STATE, observed: an argument without a fallback that declares one as null")
            .containsExactly("$.calls[0].arguments[0].default: null; a member without a "
                + "value is left out");

        Map<String, Object> nullAtTheTop = conforming();
        nullAtTheTop.put("technical_address_form", null);
        assertThat(violations(nullAtTheTop))
            .as("RED STATE, observed: a null member on the top level")
            .containsExactly("$.technical_address_form: null; a member without a value is "
                + "left out");

        Map<String, Object> verbs = conforming();
        verbs.remove("calls");
        verbs.put("verbs", List.of());
        assertThat(violations(verbs))
            .as("RED STATE, observed: the envelope of the earlier shape, verbs for calls")
            .containsExactly("$: missing 'calls'", "$: 'verbs' is not a member of this level");

        Map<String, Object> bareVersion = conforming();
        bareVersion.put("version", "0".repeat(64));
        assertThat(violations(bareVersion))
            .as("RED STATE, observed: a version as bare hex")
            .containsExactly("$.version: '" + "0".repeat(64) + "' does not match "
                + "^sha256:[0-9a-f]{64}$");

        Map<String, Object> otherShape = conforming();
        otherShape.put("shape_version", "2");
        assertThat(violations(otherShape))
            .as("RED STATE, observed: a shape version other than the one C5.5 names")
            .containsExactly("$.shape_version: '2' is not '3'");

        Map<String, Object> earlierReason = conforming();
        earlierReason.put("reasons", List.of(Map.of("code", "NOT_FOUND", "status", 404)));
        assertThat(violations(earlierReason))
            .as("RED STATE, observed: a reason in the earlier form, code and status")
            .contains("$.reasons[0]: missing 'reason'",
                "$.reasons[0]: 'status' is not a member of this level");

        Map<String, Object> schemaSpelling = conforming();
        argument(schemaSpelling).put("minItems", 1);
        assertThat(violations(schemaSpelling))
            .as("RED STATE, observed: a bound in the spelling of JSON Schema, which the tool "
                + "schema keeps and the declaration does not (C5.4)")
            .containsExactly("$.calls[0].arguments[0]: 'minItems' is not a member of this "
                + "level");

        Map<String, Object> textBound = conforming();
        argument(textBound).put("maximum", "200");
        assertThat(violations(textBound))
            .as("RED STATE, observed: a bound written as text")
            .containsExactly("$.calls[0].arguments[0].maximum: string where the schema says "
                + "integer");

        Map<String, Object> describedValue = conforming();
        argument(describedValue).put("values", List.of(Map.of("value", "id")));
        assertThat(violations(describedValue))
            .as("RED STATE, observed: values as objects rather than a list of names")
            .containsExactly("$.calls[0].arguments[0].values[0]: object where the schema "
                + "says string");
    }

    // ======================================================================
    // The check
    // ======================================================================

    @SuppressWarnings("unchecked")
    private static void check(Map<String, Object> schema, Object value, String path,
                              List<String> found) {
        for (String keyword : schema.keySet()) {
            if (!KEYWORDS.contains(keyword) && !keyword.equals("default")) {
                throw new IllegalStateException("the schema uses '" + keyword + "', which "
                    + "this check does not read");
            }
        }
        if (value == null) {
            return;
        }
        Object type = schema.get("type");
        if (type != null && !typeOf(value).equals(type)) {
            found.add(path + ": " + typeOf(value) + " where the schema says " + type);
            return;
        }
        if (schema.get("enum") instanceof List<?> allowed && !allowed.contains(value)) {
            found.add(path + ": '" + value + "' is not one of " + allowed);
        }
        if (schema.containsKey("const") && !schema.get("const").equals(value)) {
            found.add(path + ": '" + value + "' is not '" + schema.get("const") + "'");
        }
        if (schema.get("pattern") instanceof String pattern && value instanceof String text
                && !Pattern.compile(pattern).matcher(text).find()) {
            found.add(path + ": '" + text + "' does not match " + pattern);
        }
        if (value instanceof Map<?, ?> object) {
            Map<String, Object> members = (Map<String, Object>) object;
            for (Object required : (List<Object>) schema.getOrDefault("required", List.of())) {
                if (!members.containsKey(required)) {
                    found.add(path + ": missing '" + required + "'");
                }
            }
            Map<String, Object> properties =
                (Map<String, Object>) schema.getOrDefault("properties", Map.of());
            for (Map.Entry<String, Object> member : members.entrySet()) {
                Object declared = properties.get(member.getKey());
                if (declared != null) {
                    check((Map<String, Object>) declared, member.getValue(),
                        path + "." + member.getKey(), found);
                } else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                    found.add(path + ": '" + member.getKey() + "' is not a member of this level");
                }
            }
        }
        if (value instanceof List<?> list) {
            if (schema.get("minItems") instanceof Number min && list.size() < min.intValue()) {
                found.add(path + ": fewer than " + min + " items");
            }
            if (schema.get("items") instanceof Map<?, ?> items) {
                for (int i = 0; i < list.size(); i++) {
                    check((Map<String, Object>) items, list.get(i), path + "[" + i + "]", found);
                }
            }
        }
    }

    /** No member on any level carries null: one without a value is left out. */
    private static void nulls(Object value, String path, List<String> found) {
        if (value instanceof Map<?, ?> object) {
            object.forEach((name, member) -> {
                if (member == null) {
                    found.add(path + "." + name + ": null; a member without a value is left out");
                } else {
                    nulls(member, path + "." + name, found);
                }
            });
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                nulls(list.get(i), path + "[" + i + "]", found);
            }
        }
    }

    /** An argument's default is a value of its own type, never its text (C5.4). */
    @SuppressWarnings("unchecked")
    private static void defaultsInTheirOwnType(Map<String, Object> declaration,
                                               List<String> found) {
        if (!(declaration.get("calls") instanceof List<?> calls)) {
            return;
        }
        for (int c = 0; c < calls.size(); c++) {
            if (!(calls.get(c) instanceof Map<?, ?> call)
                    || !(call.get("arguments") instanceof List<?> arguments)) {
                continue;
            }
            for (int a = 0; a < arguments.size(); a++) {
                Map<String, Object> argument = (Map<String, Object>) arguments.get(a);
                if (argument.get("default") == null) {
                    continue;
                }
                String actual = typeOf(argument.get("default"));
                if (!actual.equals(argument.get("type"))) {
                    found.add("$.calls[" + c + "].arguments[" + a + "].default: a " + actual
                        + " where the argument is " + argument.get("type"));
                }
            }
        }
    }

    private static String typeOf(Object value) {
        return switch (value) {
            case null -> "null";
            case Map<?, ?> ignored -> "object";
            case List<?> ignored -> "array";
            case String ignored -> "string";
            case Boolean ignored -> "boolean";
            case Integer ignored -> "integer";
            case Long ignored -> "integer";
            case Number ignored -> "number";
            default -> value.getClass().getSimpleName();
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema() {
        try (InputStream in = DeclarationShapeTest.class.getClassLoader()
                .getResourceAsStream("contract/declaration-shape.schema.json")) {
            assertThat(in).as("the schema must be on the test classpath").isNotNull();
            return JSON.readValue(in, Map.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ======================================================================
    // A declaration of the contract shape, built here for the red states
    // ======================================================================

    private static Map<String, Object> conforming() {
        Map<String, Object> argument = new LinkedHashMap<>();
        argument.put("name", "address");
        argument.put("type", "string");
        argument.put("required", true);
        argument.put("placement", "TOP");
        argument.put("description", "the address");
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("identity", "thing_read");
        call.put("call", "thing_read");
        call.put("rest_name", "read");
        call.put("effect_class", "ITEM_READ");
        call.put("description", "reads");
        call.put("arguments", new ArrayList<>(List.of(argument)));
        call.put("answer", Map.of("shape", "OBJECT", "members", List.of("address")));
        call.put("refusals", List.of("NOT_FOUND"));
        Map<String, Object> declaration = new LinkedHashMap<>();
        declaration.put("version", "sha256:" + "0".repeat(64));
        declaration.put("shape_version", "3");
        declaration.put("service", "thing");
        declaration.put("scheme", "thing");
        declaration.put("address_form", "thing://<scope>/<selector>/<number>");
        declaration.put("effect_classes", List.of("ITEM_READ"));
        declaration.put("calls", new ArrayList<>(List.of(call)));
        declaration.put("reasons", List.of(Map.of("reason", "NOT_FOUND",
            "message_pattern", "nothing is there.",
            "messages", List.of(Map.of("key", "notFound", "template", "nothing is there.")),
            "remedy", "Read the address again.")));
        return declaration;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> call(Map<String, Object> declaration) {
        return ((List<Map<String, Object>>) declaration.get("calls")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> argument(Map<String, Object> declaration) {
        return ((List<Map<String, Object>>) call(declaration).get("arguments")).get(0);
    }
}
