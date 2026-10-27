/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.Db;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code attributes} column reads and writes the same bytes after the
 * move from the service's own JSON mapper to Hibernate's facility under
 * {@code format.global=ignore} (C17.3).
 *
 * <p>The row is planted as the previous image left it, in the text its own
 * mapper wrote — compact, keys in the order it wrote them — over every
 * attribute type a stored value can take: text, a whole and a fractional
 * number, a date, a boolean, a choice, a multi-choice set and a text list.
 * The case then reads it through this image, writes an unrelated field, and
 * sends the read attributes back. The column's text is compared with what was
 * planted after each step, so a mapper that read a value differently, or wrote
 * it back in another form, shows as a changed byte.
 *
 * <p>Run unchanged against worklist {@code b20d27f}, where the column went
 * through the service's own {@code DocumentColumnFormat}, both cases are green
 * as well: the two mappers read and write the same bytes.
 *
 * <p>RED STATE, observed with the integer planted as {@code 3.0}: the read
 * answered {@code "count"=3.0} and the case failed on the value it expected,
 * so a stored value read in another form does not pass unseen.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class AttributeColumnRoundTripIT {

    @Inject ItemService items;
    @Inject SelectorRegistry selectors;
    @Inject VocabularyRegistry vocabulary;
    @Inject ScopeSettingService settings;

    private UUID scope;
    private String openName;

    @BeforeEach
    void aScopeWithEveryStoredType() {
        scope = UUID.randomUUID();
        settings.create(scope, Map.of(
            "max_planned_iterations", 10, "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10, "warn_memberships_per_iteration", 9));
        openName = vocabulary.declareStatus(scope, "open", 1, true, false, false, false).name;
        vocabulary.declareAttribute(scope, "note", "Note", "text", 1, false);
        vocabulary.declareAttribute(scope, "count", "Count", "number", 2, false);
        vocabulary.declareAttribute(scope, "share", "Share", "number", 3, false);
        vocabulary.declareAttribute(scope, "due", "Due", "date", 4, false);
        vocabulary.declareAttribute(scope, "done", "Done", "boolean", 5, false);
        vocabulary.declareAttribute(scope, "size", "Size", "choice", 6, false);
        vocabulary.declareOption(scope, "size", "S", 1);
        vocabulary.declareAttribute(scope, "tags", "Tags", "multi_choice", 7, false);
        vocabulary.declareOption(scope, "tags", "blue", 1);
        vocabulary.declareOption(scope, "tags", "red", 2);
        vocabulary.declareAttribute(scope, "links", "Links", "text_list", 8, false);
    }

    @Test
    void a_stored_document_reads_and_writes_back_byte_equal() throws SQLException {
        UUID item = created();
        String planted = "{"
            + q(pk("note")) + ":\"ä \\\"quoted\\\" note\","
            + q(pk("count")) + ":3,"
            + q(pk("share")) + ":2.5,"
            + q(pk("due")) + ":\"2031-03-04\","
            + q(pk("done")) + ":true,"
            + q(pk("size")) + ":" + q(option("size", "S")) + ","
            + q(pk("tags")) + ":[" + q(option("tags", "blue")) + "," + q(option("tags", "red"))
            + "],"
            + q(pk("links")) + ":[\"https://a.example\",\"https://b.example\"]}";
        plant(item, planted);
        String stored = column(item);

        Map<String, Object> read = items.read(scope, item);
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes =
            (Map<String, Object>) read.get(Field.ATTRIBUTES.canonicalName());
        assertThat(attributes)
            .as("each stored value reads as the previous image read it")
            .containsEntry("note", "ä \"quoted\" note")
            .containsEntry("count", 3)
            .containsEntry("share", 2.5)
            .containsEntry("due", "2031-03-04")
            .containsEntry("done", true)
            .containsEntry("size", "S")
            .containsEntry("tags", List.of("blue", "red"))
            .containsEntry("links", List.of("https://a.example", "https://b.example"));

        Map<String, Object> retitled = items.update(scope, item, Map.of(
            Field.TITLE.canonicalName(), "another title",
            Field.CONFLICT_TOKEN.canonicalName(), read.get(Field.CONFLICT_TOKEN.canonicalName())));
        assertThat(column(item))
            .as("a write of another field leaves the column's bytes as they were")
            .isEqualTo(stored);

        Map<String, Object> resent = items.update(scope, item, Map.of(
            Field.ATTRIBUTES.canonicalName(), attributes,
            Field.CONFLICT_TOKEN.canonicalName(),
            retitled.get(Field.CONFLICT_TOKEN.canonicalName())));
        assertThat(resent.get(Field.CONFLICT_TOKEN.canonicalName()))
            .as("the attributes read, sent back, change nothing")
            .isEqualTo(retitled.get(Field.CONFLICT_TOKEN.canonicalName()));
        assertThat(column(item)).isEqualTo(stored);
    }

    @Test
    void a_document_this_image_writes_reads_back_equal() throws SQLException {
        UUID item = created();
        Map<String, Object> written = Map.of("note", "plain", "count", 7, "share", 0.25,
            "done", false, "size", "S", "tags", List.of("red"), "links", List.of("x"));
        Map<String, Object> read = items.read(scope, item);
        items.update(scope, item, Map.of(
            Field.ATTRIBUTES.canonicalName(), written,
            Field.CONFLICT_TOKEN.canonicalName(), read.get(Field.CONFLICT_TOKEN.canonicalName())));
        String stored = column(item);

        Map<String, Object> again = items.read(scope, item);
        assertThat(again.get(Field.ATTRIBUTES.canonicalName())).isEqualTo(written);
        assertThat(column(item)).isEqualTo(stored);
    }

    // -----------------------------------------------------------------------

    private UUID created() {
        Map<String, Object> answer = items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "attribute round trip",
            Field.STATUS.canonicalName(), openName));
        return UUID.fromString(String.valueOf(answer.get(Field.ID.canonicalName())));
    }

    private static String q(String text) {
        return "\"" + text + "\"";
    }

    private String pk(String key) throws SQLException {
        return text("SELECT pk::text FROM worklist.attribute_definition "
            + "WHERE scope_id = ? AND key = ?", scope, key);
    }

    private String option(String key, String name) throws SQLException {
        return text("SELECT o.pk::text FROM worklist.attribute_option o "
            + "JOIN worklist.attribute_definition d ON d.pk = o.definition_pk "
            + "WHERE d.scope_id = ? AND d.key = ? AND o.name = ?", scope, key, name);
    }

    private static void plant(UUID item, String document) throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, PlanningFixture.boundTenant());
            try (PreparedStatement st = c.prepareStatement(
                    "UPDATE worklist.item SET attributes = ?::jsonb WHERE id = ?")) {
                st.setString(1, document);
                st.setObject(2, item);
                assertThat(st.executeUpdate()).isEqualTo(1);
            }
            c.commit();
        }
    }

    private static String column(UUID item) throws SQLException {
        return text("SELECT attributes::text FROM worklist.item WHERE id = ?", item);
    }

    private static String text(String sql, Object... args) throws SQLException {
        try (Connection c = Db.asAdmin(); PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                st.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = st.executeQuery()) {
                assertThat(rs.next()).as("a row for: " + sql).isTrue();
                return rs.getString(1);
            }
        }
    }
}
