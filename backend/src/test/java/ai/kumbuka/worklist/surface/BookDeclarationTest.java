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
 * {@code book} and the option of {@code read} in the declaration the router
 * builds its forms from, and the texts that say what holds (K10).
 *
 * <p>The served document is compared field by field with its transcription in
 * {@code DeclarationConformanceIT}; this asks the declaration itself, and the
 * texts as the message bundles hold them. The expectations are written here
 * from the order.
 */
class BookDeclarationTest {

    @Test
    void book_is_a_write_on_an_item_address_without_a_token_taking_an_instance_and_a_list() {
        SurfaceDeclaration.Verb book = SurfaceDeclaration.verb("book").orElseThrow();

        assertThat(SurfaceDeclaration.call(book)).isEqualTo("worklist_book");
        assertThat(book.argumentNames())
            .as("the item's address, the reporting instance and the entries; no conflict token")
            .containsExactly("address", "instance", "entries");
        assertThat(book.arguments()).extracting(a -> a.type().json())
            .containsExactly("string", "string", "array");
        assertThat(book.arguments()).allSatisfy(a -> assertThat(a.required()).isTrue());
        assertThat(book.effectClass()).isEqualTo(SurfaceDeclaration.EffectClass.ITEM_TRANSITION);
        assertThat(book.answer()).isEqualTo(SurfaceDeclaration.Answer.OBJECT);
        assertThat(book.reasons())
            .contains(RefusalCode.INVALID_VALUE, RefusalCode.VERB_UNCARRIED,
                RefusalCode.SCOPE_READ_ONLY, RefusalCode.ARGUMENT_MISSING, RefusalCode.NOT_FOUND)
            .doesNotContain(RefusalCode.CONFLICT_TOKEN_MISSING, RefusalCode.CONFLICT);
        assertThat(SurfaceDeclaration.declaredNames(List.of("instance", "entries",
                "fields.fulfilment", "fulfilment")))
            .as("each is a name a refusal may give")
            .containsExactly("instance", "entries", "fields.fulfilment", "fulfilment");
    }

    @Test
    void read_takes_the_option_of_the_whole_ledger_with_latest_as_its_default() {
        SurfaceDeclaration.Argument option = SurfaceDeclaration.verb("read").orElseThrow()
            .arguments().stream().filter(a -> a.name().name().equals("fulfilment"))
            .findFirst().orElseThrow();

        assertThat(option.required()).isFalse();
        assertThat(option.defaultValue()).isEqualTo("latest");
        assertThat(option.values()).extracting(SurfaceDeclaration.Value::name)
            .containsExactly("latest", "all");
    }

    /**
     * K10: the description says what holds -- append-only, the three
     * statuses of the platform, the actor from the caller's identity, the
     * item unwritten, open on an item that is open, in progress or closed
     * and so on a closed item too (REA-0011 Annex A1, S13), and the
     * narrowing to the identity concluding an instance a later step's.
     */
    @Test
    void the_description_of_book_says_what_holds() {
        String tool = text("tool", "book");
        assertThat(tool)
            .contains("append-only")
            .contains("met, not_met or not_applicable")
            .contains("your own identity")
            .contains("conflict_token and changed_at stay")
            .contains("open, in progress or closed")
            .contains("a later step narrows a closed item to the identity concluding an instance");
        assertThat(tool.length())
            .as("within the description budget")
            .isLessThanOrEqualTo(SurfaceDeclaration.DESCRIPTION_BUDGET);
        assertThat(text("argument", "entries"))
            .contains("met, not_met or not_applicable")
            .contains("withdrew is taken too");
        assertThat(text("argument", "fields")).as("fields names the ledger read-only")
            .contains("fulfilment, the ledger book writes");
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
