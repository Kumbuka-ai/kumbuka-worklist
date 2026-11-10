/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.Addressed;
import ai.kumbuka.worklist.domain.Field;
import ai.kumbuka.worklist.domain.QueryFilter;
import ai.kumbuka.worklist.domain.Selector;
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
 * The tags in the declaration the router builds its forms from (ADR-0039,
 * shape 3; sprint/210): both verbs, the field {@code tags} and the filter
 * {@code tag}.
 *
 * <p>Shape 3 carries calls and their arguments; a field of an object and a
 * filter of a view are declared in the texts of the arguments that take them,
 * {@code fields} and {@code filter}, and as the argument paths a refusal may
 * name. So the field and the filter are asked of those, and the verbs of the
 * declaration itself. The served document is compared field by field with its
 * transcription in {@code DeclarationConformanceIT}.
 */
class TagDeclarationTest {

    @Test
    void both_verbs_are_declared_as_a_write_on_an_addressed_object_without_a_token() {
        for (String verb : List.of("tag_attach", "tag_detach")) {
            SurfaceDeclaration.Verb declared = SurfaceDeclaration.verb(verb).orElseThrow();
            assertThat(SurfaceDeclaration.call(declared))
                .as("the router's surface carries it as worklist_%s", verb)
                .isEqualTo("worklist_" + verb);
            assertThat(declared.argumentNames())
                .as("%s takes the complete address and the token, and no conflict token", verb)
                .containsExactly("address", "tag");
            assertThat(declared.effectClass())
                .as("%s is classed as claim and release are: a write on an addressed object "
                    + "that presents no conflict token", verb)
                .isEqualTo(SurfaceDeclaration.verb("claim").orElseThrow().effectClass())
                .isEqualTo(SurfaceDeclaration.EffectClass.ITEM_TRANSITION);
            assertThat(declared.answer()).isEqualTo(SurfaceDeclaration.Answer.OBJECT);
            assertThat(declared.reasons())
                .as("%s names the refusals it can give", verb)
                .contains(RefusalCode.TAG_ITEM_ONLY, RefusalCode.VALUE_UNDECLARED,
                    RefusalCode.SCOPE_READ_ONLY, RefusalCode.NOT_FOUND)
                .doesNotContain(RefusalCode.CONFLICT_TOKEN_MISSING, RefusalCode.CONFLICT);
        }
        assertThat(SurfaceDeclaration.verb("tag_attach").orElseThrow().reasons())
            .contains(RefusalCode.TAG_WITHDRAWN);
        assertThat(SurfaceDeclaration.verb("tag_detach").orElseThrow().reasons())
            .as("a withdrawn tag detaches")
            .doesNotContain(RefusalCode.TAG_WITHDRAWN);
    }

    @Test
    void the_field_tags_is_an_items_and_read_only() {
        assertThat(Field.byCanonicalName(Addressed.ITEM, "tags")).contains(Field.TAGS);
        assertThat(Field.TAGS.settableOn(Addressed.ITEM))
            .as("tags change through tag_attach and tag_detach, never through update")
            .isFalse();
        assertThat(Field.TAGS.carriedBy(Addressed.MILESTONE)
                || Field.TAGS.carriedBy(Addressed.ITERATION))
            .as("only items carry tags")
            .isFalse();
        assertThat(SurfaceDeclaration.declaredNames(List.of("fields.tags", "tag")))
            .as("both are names a refusal may give")
            .containsExactly("fields.tags", "tag");
        assertThat(argumentText("fields"))
            .as("the description of fields names tags")
            .contains("tags");
    }

    @Test
    void the_filter_tag_belongs_to_the_item_view_alone() {
        assertThat(QueryFilter.of(Selector.ITEM)).contains(QueryFilter.ITEM_TAG);
        assertThat(QueryFilter.ITEM_TAG.filterName()).isEqualTo("tag");
        for (String view : List.of(Selector.ITERATION, Selector.MILESTONE, Selector.WORKSTREAM)) {
            assertThat(QueryFilter.of(view)).as("the %s view", view)
                .doesNotContain(QueryFilter.ITEM_TAG);
        }
        assertThat(SurfaceDeclaration.declaredNames(List.of("filter.tag")))
            .containsExactly("filter.tag");
        assertThat(argumentText("filter"))
            .as("the description of filter names tag beside status, milestone and workstream")
            .contains("tag (", "status (", "milestone (", "workstream (");
    }

    private static String argumentText(String key) {
        Path direct = Paths.get("src", "main", "resources", "messages", "argument.properties");
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
