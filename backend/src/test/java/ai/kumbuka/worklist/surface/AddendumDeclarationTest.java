/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code addendum} in the declaration the router builds its forms from, and
 * the texts that say what holds (REA-0011 D1, D13).
 *
 * <p>The served document is compared field by field with its transcription in
 * {@code DeclarationConformanceIT}; this asks the declaration itself, and the
 * texts as the message bundles hold them. The expectations are written here
 * from the order.
 */
class AddendumDeclarationTest {

    @Test
    void addendum_is_a_write_on_an_item_address_without_a_token_taking_a_text() {
        SurfaceDeclaration.Verb addendum = SurfaceDeclaration.verb("addendum").orElseThrow();

        assertThat(SurfaceDeclaration.call(addendum)).isEqualTo("worklist_addendum");
        assertThat(addendum.argumentNames())
            .as("the item's address and the text; no conflict token")
            .containsExactly("address", "text");
        assertThat(addendum.arguments()).extracting(a -> a.type().json())
            .containsExactly("string", "string");
        assertThat(addendum.arguments()).allSatisfy(a -> assertThat(a.required()).isTrue());
        assertThat(addendum.effectClass())
            .isEqualTo(SurfaceDeclaration.EffectClass.ITEM_TRANSITION);
        assertThat(addendum.answer()).isEqualTo(SurfaceDeclaration.Answer.OBJECT);
        assertThat(addendum.reasons())
            .contains(RefusalCode.TRANSITION_REFUSED, RefusalCode.INVALID_VALUE,
                RefusalCode.VERB_UNCARRIED, RefusalCode.SCOPE_READ_ONLY,
                RefusalCode.ARGUMENT_MISSING, RefusalCode.NOT_FOUND)
            .doesNotContain(RefusalCode.CONFLICT_TOKEN_MISSING, RefusalCode.CONFLICT);
        assertThat(SurfaceDeclaration.declaredNames(List.of("text", "fields.addenda")))
            .as("each is a name a refusal may give")
            .containsExactly("text", "fields.addenda");
    }

    /**
     * The description says what holds: append-only, open on an item that is
     * open or in progress, the author from the caller's identity, the item's
     * token and change time unmoved, and a draft corrected through update.
     */
    @Test
    void the_description_of_addendum_says_what_holds() {
        String tool = text("tool", "addendum");
        assertThat(tool)
            .contains("append-only")
            .contains("open or in progress")
            .contains("your own identity as author")
            .contains("conflict_token and changed_at stay")
            .contains("A draft is corrected with update");
        assertThat(tool.length())
            .as("within the description budget")
            .isLessThanOrEqualTo(SurfaceDeclaration.DESCRIPTION_BUDGET);
        assertThat(text("next", "addendum")).contains("append-only");
        assertThat(text("argument", "text")).contains("any length");
        assertThat(text("argument", "fields"))
            .as("fields names the phases and the addenda read-only")
            .contains("title, description and criteria in a draft")
            .contains("addenda, the texts addendum writes");
    }

    @Test
    void the_description_of_update_names_the_field_classes_and_the_addendum() {
        assertThat(text("tool", "update"))
            .contains("title, description and criteria change in a draft")
            .contains("type and workstream also when open")
            .contains("a frozen field takes an addendum");
    }

    private static String text(String bundle, String key) {
        Path direct = Paths.get("src", "main", "resources", "messages", bundle + ".properties");
        Path file = Files.exists(direct) ? direct : Paths.get("backend").resolve(direct);
        Properties texts = new Properties();
        try (Reader in = Files.newBufferedReader(file)) {
            texts.load(in);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
        return texts.getProperty(key);
    }
}
