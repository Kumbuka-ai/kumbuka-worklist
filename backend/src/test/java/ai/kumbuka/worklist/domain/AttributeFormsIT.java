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
 * The form of {@code item.attributes} under ADR-0042, since stage R3.
 *
 * <p>The column keys an attribute by its definition's surrogate and holds an
 * option as the option's surrogate; outward, an option travels by its name in
 * both directions. Stage R2 bridged a uuid form and this one; since V20 there
 * is only this one.
 *
 * <p>The stored form is read as the administrator, outside the service, so
 * the probe observes the column itself. The expected surrogates are the
 * vocabulary rows' own, read from the catalogue.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class AttributeFormsIT {

    @Inject ItemService items;
    @Inject VocabularyRegistry vocabulary;
    @Inject ScopeSettingService settings;

    private UUID scope;
    private String openName;

    @BeforeEach
    void aScopeWithAChoiceAndAMultiChoice() {
        scope = UUID.randomUUID();
        settings.create(scope, Map.of(
            "max_planned_iterations", 10, "warn_planned_iterations", 9,
            "max_memberships_per_iteration", 10, "warn_memberships_per_iteration", 9));
        openName = vocabulary.declareStatus(scope, "open", 1, true, false, false, false).name;
        vocabulary.declareAttribute(scope, "size", "Size", "choice", 1, false);
        vocabulary.declareOption(scope, "size", "S", 1);
        vocabulary.declareOption(scope, "size", "M", 2);
        vocabulary.declareAttribute(scope, "tags", "Tags", "multi_choice", 2, false);
        vocabulary.declareOption(scope, "tags", "red", 1);
        vocabulary.declareOption(scope, "tags", "blue", 2);
    }

    @Test
    void the_column_holds_surrogates_and_the_answer_names_the_options() throws SQLException {
        UUID item = created(Map.of("size", "S", "tags", List.of("blue", "red")));

        assertThat(text("SELECT (attributes = jsonb_build_object(?::text, ?::text, ?::text, "
                + "jsonb_build_array(?::text, ?::text)))::text FROM worklist.item WHERE id = ?",
                definitionPk("size"), optionPk("size", "S"),
                definitionPk("tags"), optionPk("tags", "blue"), optionPk("tags", "red"), item))
            .as("the column keys by the definition's surrogate and holds each option as its "
                + "surrogate (ADR-0042); the multi_choice set is held sorted by name")
            .isEqualTo("true");

        Map<String, Object> attributes = attributesOf(items.read(scope, item));
        assertThat(attributes)
            .as("outward, an option travels by its name")
            .containsEntry("size", "S")
            .containsEntry("tags", List.of("blue", "red"));
    }

    @Test
    void a_read_answer_sent_back_unchanged_round_trips() {
        UUID item = created(Map.of("size", "M"));
        Map<String, Object> read = items.read(scope, item);

        Map<String, Object> again = items.update(scope, item, Map.of(
            Field.ATTRIBUTES.canonicalName(), attributesOf(read),
            Field.CONFLICT_TOKEN.canonicalName(), read.get(Field.CONFLICT_TOKEN.canonicalName())));
        assertThat(again.get(Field.CONFLICT_TOKEN.canonicalName()))
            .as("the names the answer carried name the same options, so nothing changed and "
                + "the token did not rotate")
            .isEqualTo(read.get(Field.CONFLICT_TOKEN.canonicalName()));
    }

    // ==================================================================
    // Fixtures.
    // ==================================================================

    private UUID created(Map<String, Object> attributes) {
        Map<String, Object> answer = items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "attribute forms",
            Field.STATUS.canonicalName(), openName,
            Field.ATTRIBUTES.canonicalName(), attributes));
        return UUID.fromString(String.valueOf(answer.get(Field.ID.canonicalName())));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributesOf(Map<String, Object> answer) {
        return (Map<String, Object>) answer.get(Field.ATTRIBUTES.canonicalName());
    }

    private String definitionPk(String key) throws SQLException {
        return text("SELECT pk::text FROM worklist.attribute_definition "
            + "WHERE scope_id = ? AND key = ?", scope, key);
    }

    private String optionPk(String key, String name) throws SQLException {
        return text("SELECT o.pk::text FROM worklist.attribute_option o "
            + "JOIN worklist.attribute_definition d ON d.pk = o.definition_pk "
            + "WHERE d.scope_id = ? AND d.key = ? AND o.name = ?", scope, key, name);
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
