/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.adapter.rest;

import ai.kumbuka.worklist.surface.AddressParser;
import ai.kumbuka.worklist.surface.RefusalCode;
import ai.kumbuka.worklist.surface.SurfaceDeclaration;
import ai.kumbuka.worklist.surface.SurfaceException;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The routes of the REST surface: one check of them, held in every build.
 *
 * <p><strong>The routes and the declaration are one set</strong> (ADR-0039):
 * every verb the REST surface serves is declared, and every declared verb is
 * served. This test took the check over from the start-up guard that held it
 * in the adapter, so there is one route check and not two.
 *
 * <p><strong>Each route has the HTTP form of its declared effect class</strong>,
 * so the router, which turns an effect class into an HTTP form (ADR-0039),
 * reaches the route the service serves. The form of each class is written here
 * from the router's definitions, not read from either side: GET on an object
 * or a collection, POST on a collection to create, PATCH on an object, a custom
 * method in colon notation by POST on an object or a collection, and a graph
 * verb on the membership's own path (C3.4).
 *
 * <p>Three clauses of the service contract stand beside it. C3.2: the REST
 * surface has two literal paths, {@code /api/declaration} and {@code
 * /api/whoami}, and every other path is a template under {@code /api/{scope}};
 * the names of the two literal paths are reserved, so a scope named so is out
 * of the address grammar. C3.3: a method or verb not carried at an address
 * answers {@code VERB_NOT_CARRIED} 405, and {@code data.accepts} lists the
 * verbs that address carries.
 */
class RestRouteFormTest {

    // -----------------------------------------------------------------------
    // The routes and the declaration
    // -----------------------------------------------------------------------

    @Test
    void every_served_verb_is_declared_and_every_declared_verb_served() {
        assertThat(routeOffences(served(), declared()))
            .as("a verb served without a declaration is one no caller can learn; a declared "
                + "verb without a route is one the router reaches and the service refuses")
            .isEmpty();
    }

    @Test
    void the_check_reports_a_verb_whose_declaration_is_missing() {
        List<String> withoutClaim = declared().stream()
            .filter(verb -> !verb.equals("claim")).toList();
        assertThat(routeOffences(served(), withoutClaim))
            .as("RED STATE, observed: a served verb with its declaration removed must be "
                + "reported by name")
            .containsExactly("undeclared: claim");
    }

    /** The verbs one set has and the other lacks, named. */
    static List<String> routeOffences(Set<String> served, List<String> declared) {
        List<String> offences = new ArrayList<>();
        new TreeSet<>(served).stream().filter(verb -> !declared.contains(verb))
            .forEach(verb -> offences.add("undeclared: " + verb));
        new TreeSet<>(declared).stream().filter(verb -> !served.contains(verb))
            .forEach(verb -> offences.add("unrouted: " + verb));
        return offences;
    }

    /** The verbs the REST surface carries: what its depths accept, together. */
    private static Set<String> served() {
        Set<String> served = new LinkedHashSet<>();
        for (CustomMethod.Depth depth : CustomMethod.Depth.values()) {
            served.addAll(CustomMethod.accepts(depth));
        }
        return served;
    }

    private static List<String> declared() {
        return SurfaceDeclaration.verbs().stream().map(v -> v.name().name()).toList();
    }

    // -----------------------------------------------------------------------
    // The form of each route
    // -----------------------------------------------------------------------

    /**
     * The resource method that carries each verb routed by HTTP method. The
     * verbs in colon notation are read from {@link CustomMethod}.
     */
    private static final Map<String, List<String>> BY_METHOD = Map.of(
        "create", List.of("collectionPost"),
        "query", List.of("collectionGet"),
        "read", List.of("read", "readMembership"),
        "update", List.of("update", "updateMembership"),
        "plan", List.of("plan"),
        "unplan", List.of("unplan"));

    @Test
    void every_route_has_the_form_of_its_effect_class() {
        Map<String, String> forms = formsOfTheRoutes();
        assertThat(formOffences(forms))
            .as("a route whose HTTP form is not the one its effect class names is one the "
                + "router cannot reach")
            .isEmpty();
        assertThat(forms.keySet()).as("every declared call has its route")
            .containsExactlyInAnyOrderElementsOf(declared());
    }

    @Test
    void the_check_reports_a_route_of_another_form() {
        Map<String, String> planted = new LinkedHashMap<>(formsOfTheRoutes());
        planted.put("update", "POST ITEM");
        assertThat(formOffences(planted))
            .as("RED STATE, observed: update, a FIELD_WRITE, served by POST must be reported")
            .containsExactly("update is declared FIELD_WRITE and served as POST ITEM");
    }

    /** The calls whose route form differs from the one their effect class names. */
    static List<String> formOffences(Map<String, String> forms) {
        List<String> offences = new ArrayList<>();
        for (SurfaceDeclaration.Verb verb : SurfaceDeclaration.verbs()) {
            String expected = switch (verb.effectClass()) {
                case ITEM_READ -> "GET ITEM";
                case COLLECTION_READ -> "GET COLLECTION";
                case CREATE -> "POST COLLECTION";
                case FIELD_WRITE -> "PATCH ITEM";
                case ITEM_TRANSITION -> "POST ITEM :call";
                case COLLECTION_COMPOUND -> "POST COLLECTION :call";
                case GRAPH_ADD_OR_REMOVE -> verb.name().name().startsWith("un")
                    ? "DELETE MEMBERSHIP" : "POST MEMBERSHIP";
            };
            String served = forms.get(verb.name().name());
            if (served != null && !served.equals(expected)) {
                offences.add(verb.name().name() + " is declared " + verb.effectClass()
                    + " and served as " + served);
            }
        }
        return offences;
    }

    /**
     * Each verb's route form, as the resource carries it: the HTTP method and
     * the depth of the first route that serves it, and a colon verb as POST at
     * its depth.
     */
    private static Map<String, String> formsOfTheRoutes() {
        Map<String, String> forms = new LinkedHashMap<>();
        BY_METHOD.forEach((verb, methods) -> forms.put(verb, formOf(methods.get(0))));
        for (CustomMethod route : CustomMethod.values()) {
            if (route.kind() == CustomMethod.Kind.CARRIED) {
                forms.put(route.verb(), "POST " + route.depth() + " :call");
            }
        }
        return forms;
    }

    private static String formOf(String javaMethod) {
        Method method = Arrays.stream(WorklistResource.class.getDeclaredMethods())
            .filter(m -> m.getName().equals(javaMethod)).findFirst().orElseThrow();
        String http = method.isAnnotationPresent(GET.class) ? "GET"
            : method.isAnnotationPresent(POST.class) ? "POST"
            : method.isAnnotationPresent(PATCH.class) ? "PATCH"
            : method.isAnnotationPresent(DELETE.class) ? "DELETE" : "none";
        int segments = method.getAnnotation(Path.class).value().split("/").length;
        String depth = switch (segments) {
            case 1 -> "COLLECTION";
            case 2 -> "ITEM";
            default -> "MEMBERSHIP";
        };
        return http + " " + depth;
    }

    // -----------------------------------------------------------------------
    // C3.2: two literal paths, and their names reserved
    // -----------------------------------------------------------------------

    /** The resources of the REST surface, by the class a test can load. */
    private static final List<Class<?>> RESOURCES = List.of(WorklistResource.class,
        DeclarationResource.class, WhoamiResource.class);

    /** C3.2: the two literal paths, as the contract names them. */
    private static final Set<String> LITERAL = Set.of("/api/declaration", "/api/whoami");

    @Test
    void the_rest_surface_has_two_literal_paths_and_templates_under_a_scope() {
        assertThat(pathOffences(pathsOf(RESOURCES)))
            .as("C3.2: /api/declaration and /api/whoami are the only literal paths; every "
                + "other path addresses a scope")
            .isEmpty();
    }

    @Test
    void the_check_reports_a_third_literal_path_and_a_path_outside_api() {
        assertThat(pathOffences(List.of("/api/declaration", "/api/status",
                "/admin/{scope}", "/api/{scope}/{selector}")))
            .as("RED STATE, observed: a third literal path and a path outside /api must "
                + "each be reported")
            .containsExactly("/api/status", "/admin/{scope}");
    }

    @Test
    void the_names_of_the_literal_paths_are_no_scope() {
        assertThat(reservedOffences(LITERAL))
            .as("C3.2: a scope named like a literal path would answer under a path the "
                + "surface keeps for itself; the grammar refuses the name")
            .isEmpty();
        assertThat(AddressParser.RESERVED_SCOPES).as("and reserves no other name")
            .containsExactlyInAnyOrderElementsOf(LITERAL.stream()
                .map(path -> path.substring("/api/".length())).toList());
        assertThat(AddressParser.scope("declarations")).as("a longer name is a scope")
            .isEqualTo("declarations");
        assertThatThrownBy(() -> AddressParser.uri("worklist://whoami/item/1"))
            .as("and a complete address under a reserved name is no address")
            .isInstanceOfSatisfying(SurfaceException.class,
                e -> assertThat(e.reason()).isEqualTo(RefusalCode.ADDRESS_MALFORMED));
    }

    @Test
    void the_check_reports_a_literal_path_whose_name_is_still_a_scope() {
        assertThat(reservedOffences(Set.of("/api/status")))
            .as("RED STATE, observed: a literal path whose name the grammar takes as a scope "
                + "must be reported")
            .containsExactly("status");
    }

    /** The names of literal paths the address grammar still takes as a scope. */
    static List<String> reservedOffences(Set<String> literals) {
        List<String> offences = new ArrayList<>();
        for (String path : new TreeSet<>(literals)) {
            String name = path.substring("/api/".length());
            try {
                AddressParser.scope(name);
                offences.add(name);
            } catch (SurfaceException refused) {
                // refused by the grammar, as C3.2 asks
            }
        }
        return offences;
    }

    static List<String> pathOffences(List<String> paths) {
        List<String> offences = new ArrayList<>();
        for (String path : paths) {
            boolean literal = !path.contains("{");
            if (literal ? !LITERAL.contains(path) : !path.startsWith("/api/{scope}")) {
                offences.add(path);
            }
        }
        return offences;
    }

    /** Every path a resource answers at: the class's path, joined with each method's. */
    private static List<String> pathsOf(List<Class<?>> resources) {
        List<String> paths = new ArrayList<>();
        for (Class<?> resource : resources) {
            String base = resource.getAnnotation(Path.class).value();
            boolean any = false;
            for (Method method : resource.getDeclaredMethods()) {
                Path sub = method.getAnnotation(Path.class);
                if (sub != null) {
                    paths.add(base + "/" + sub.value());
                    any = true;
                }
            }
            if (!any) {
                paths.add(base);
            }
        }
        return paths;
    }

    // -----------------------------------------------------------------------
    // C3.3: what an address accepts
    // -----------------------------------------------------------------------

    @Test
    void a_method_not_carried_at_an_address_answers_405_listing_what_the_address_carries() {
        assertThat(RefusalCode.VERB_NOT_CARRIED.status()).as("C3.3").isEqualTo(405);
        assertThat(CustomMethod.accepts(CustomMethod.Depth.COLLECTION))
            .as("a collection carries create, query and its custom methods")
            .containsExactlyInAnyOrder("create", "query", "advance", "validate", "claim_next");
        assertThat(CustomMethod.accepts(CustomMethod.Depth.ITEM))
            .as("an object carries read, update and its custom methods")
            .containsExactlyInAnyOrder("read", "update", "accept", "withdraw", "close",
                "claim", "release", "relate", "unrelate");
        assertThat(CustomMethod.accepts(CustomMethod.Depth.MEMBERSHIP))
            .as("a membership carries read, update, plan and unplan")
            .containsExactlyInAnyOrder("read", "update", "plan", "unplan");
    }
}
