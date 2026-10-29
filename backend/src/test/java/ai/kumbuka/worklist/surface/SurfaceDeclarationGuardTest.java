/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The start-up guard of the declaration, observed refusing (C5.6).
 *
 * <p>A start-up check nobody has watched fail is a description of one. So each
 * check {@code requireServable} makes is run against a declaration that breaks
 * it, and the refusal is read. The checks take names and texts handed in, so
 * this runs without the service. {@code requireServable} itself is run over the
 * real verbs with bundles that answer one text for every method: once with a
 * text, which passes, and once with a blank one for a single method, which is
 * refused under the call that method describes. The document the guard builds
 * is checked the same way: the real one passes, and one whose projection lost a
 * text or doubled a call -- which the verbs cannot show -- is refused under that
 * call. That the served texts pass is observed wherever the service boots, since
 * the guard runs on every start.
 */
class SurfaceDeclarationGuardTest {

    @Test
    void two_calls_under_one_name_refuse_the_start() {
        assertThatThrownBy(() -> SurfaceDeclaration.requireDistinctNames(
            List.of("worklist_read", "worklist_claim", "worklist_read")))
            .as("RED STATE, observed: a duplicate call name refuses the start, and names it")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("worklist_read");
    }

    @Test
    void a_description_over_the_budget_refuses_the_start() {
        assertThatCode(() -> SurfaceDeclaration.requireWithinBudget("worklist_something",
            "x".repeat(SurfaceDeclaration.DESCRIPTION_BUDGET)))
            .as("a description of exactly the budget is admitted")
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> SurfaceDeclaration.requireWithinBudget("worklist_something",
            "x".repeat(SurfaceDeclaration.DESCRIPTION_BUDGET + 1)))
            .as("RED STATE, observed: a description one character over the budget refuses "
                + "the start, and names the call")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("worklist_something")
            .hasMessageContaining("501");
    }

    @Test
    void a_call_with_no_description_refuses_the_start() {
        assertThatThrownBy(() -> SurfaceDeclaration.requireDescribed("worklist_something", " "))
            .as("RED STATE, observed: a call declared with a blank description refuses the "
                + "start, and names the call")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("worklist_something");
        assertThatThrownBy(() -> SurfaceDeclaration.requireDescribed("worklist_something", null))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_call_with_no_arguments_refuses_the_start() {
        assertThatThrownBy(() ->
            SurfaceDeclaration.requireDescribedArguments("worklist_nothing", Map.of()))
            .as("RED STATE, observed: a call with no arguments refuses the start")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no arguments");
    }

    @Test
    void an_argument_with_no_description_refuses_the_start() {
        Map<String, String> arguments = new LinkedHashMap<>();
        arguments.put("scope", "The scope's name.");
        arguments.put("address", "  ");
        assertThatThrownBy(() ->
            SurfaceDeclaration.requireDescribedArguments("worklist_something", arguments))
            .as("RED STATE, observed: an argument with no description refuses the start, "
                + "and names the argument")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("'address'");
    }

    @Test
    void a_refusal_outside_the_catalogue_refuses_the_start() {
        assertThatThrownBy(() -> SurfaceDeclaration.requireCatalogued("worklist_something",
            List.of("NOT_FOUND", "NOT_A_REASON"), List.of("NOT_FOUND", "CONFLICT")))
            .as("RED STATE, observed: a call naming a refusal the catalogue lacks refuses the "
                + "start, and names the refusal")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("[NOT_A_REASON]");
    }

    @Test
    void an_argument_declared_twice_refuses_the_start() {
        assertThatThrownBy(() -> SurfaceDeclaration.requireDistinctArguments("worklist_something",
            List.of("address", "conflict_token", "address")))
            .as("RED STATE, observed: a call declaring one argument twice refuses the start, "
                + "and names the argument")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("'address' twice");
    }

    @Test
    void the_real_verbs_pass_with_texts_and_are_refused_with_one_text_missing() {
        assertThatCode(() -> SurfaceDeclaration.requireServable(
                bundle(ToolDescriptions.class, null), bundle(ArgumentDescriptions.class, null)))
            .as("every verb is declared once, with arguments, each argument with a text")
            .doesNotThrowAnyException();

        assertThatThrownBy(() -> SurfaceDeclaration.requireServable(
                bundle(ToolDescriptions.class, "claimNext"),
                bundle(ArgumentDescriptions.class, null)))
            .as("RED STATE, observed: the verb whose text is blank is refused by its call name")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("worklist_claim_next");

        assertThatThrownBy(() -> SurfaceDeclaration.requireServable(
                bundle(ToolDescriptions.class, null),
                bundle(ArgumentDescriptions.class, "receipt")))
            .as("RED STATE, observed: the argument whose text is blank is refused by name")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("worklist_release")
            .hasMessageContaining("'receipt'");
    }

    @Test
    void the_document_is_checked_as_served_and_refused_where_its_projection_is_broken() {
        Map<String, Object> document = document();
        assertThatCode(() -> DeclarationDocument.requireServable(document))
            .as("the document built from the real verbs is servable")
            .doesNotThrowAnyException();

        Map<String, Object> blank = withCalls(document, calls -> {
            Map<String, Object> call = new LinkedHashMap<>(calls.get(2));
            call.put("description", "");
            calls.set(2, call);
        });
        assertThatThrownBy(() -> DeclarationDocument.requireServable(blank))
            .as("RED STATE, observed: a call the document carries with an empty description is "
                + "refused by its call name, though its verb is described")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(String.valueOf(calls(document).get(2).get("call")))
            .hasMessageContaining("without a description");

        Map<String, Object> doubled = withCalls(document, calls -> calls.add(calls.get(0)));
        assertThatThrownBy(() -> DeclarationDocument.requireServable(doubled))
            .as("RED STATE, observed: a call the document carries twice is refused by its name, "
                + "though the verbs declare it once")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("'" + calls(document).get(0).get("call") + "'");

        Map<String, Object> twiceArgued = withCalls(document, calls -> {
            Map<String, Object> call = new LinkedHashMap<>(calls.get(0));
            List<Object> arguments = new ArrayList<>((List<?>) call.get("arguments"));
            arguments.add(arguments.get(0));
            call.put("arguments", arguments);
            calls.set(0, call);
        });
        assertThatThrownBy(() -> DeclarationDocument.requireServable(twiceArgued))
            .as("RED STATE, observed: an argument a call carries twice is refused by the call "
                + "and the argument; read into a map by name, the two were one and passed")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(String.valueOf(calls(document).get(0).get("call")))
            .hasMessageContaining("twice");

        Map<String, Object> uncatalogued = new LinkedHashMap<>(document);
        uncatalogued.put("reasons", List.of());
        assertThatThrownBy(() -> DeclarationDocument.requireServable(uncatalogued))
            .as("RED STATE, observed: a refusal a call names that the document's reasons lack "
                + "is refused")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not declare");
    }

    /** The document as the service builds it, worded by bundles that answer a text. */
    private static Map<String, Object> document() {
        return DeclarationDocument.content(bundle(ToolDescriptions.class, null),
            bundle(ArgumentDescriptions.class, null), bundle(ValueDescriptions.class, null));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> calls(Map<String, Object> document) {
        return (List<Map<String, Object>>) document.get("calls");
    }

    /** A copy of the document whose list of calls is changed as given. */
    private static Map<String, Object> withCalls(Map<String, Object> document,
                                                 java.util.function.Consumer<
                                                     List<Map<String, Object>>> change) {
        List<Map<String, Object>> calls = new ArrayList<>(calls(document));
        change.accept(calls);
        Map<String, Object> copy = new LinkedHashMap<>(document);
        copy.put("calls", calls);
        return copy;
    }

    /** A bundle that answers a text for every method, and a blank one for the method named. */
    private static <T> T bundle(Class<T> type, String blank) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
            (proxy, method, args) -> method.getName().equals(blank) ? " " : "a text"));
    }
}
