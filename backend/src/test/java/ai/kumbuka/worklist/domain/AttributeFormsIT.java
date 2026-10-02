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
 * The two forms of {@code item.attributes} under ADR-0042 stage R2.
 *
 * <p>The column was keyed by definition uuid with option uuids as values, and
 * stage R3 rewrites it to the surrogates. This image has to write the uuid
 * form while the vocabulary still carries uuids — the image before it reads
 * nothing else — and has to read the surrogate form, because it is the image
 * an R3 store is rolled back to.
 *
 * <p>The stored form is read and planted as the administrator, outside the
 * service, so the probe observes the column itself rather than the service's
 * account of it. The expected values are the vocabulary rows' own uuids and
 * surrogates, read from the catalogue, not from anything the service answered.
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
    void the_writer_stores_the_uuid_form_while_the_vocabulary_carries_uuids() throws SQLException {
        UUID item = created(Map.of("size", "S"));

        assertThat(text("SELECT (attributes = jsonb_build_object(?::text, ?::text))::text "
                + "FROM worklist.item WHERE id = ?",
                definitionUuid("size").toString(), optionUuid("size", "S").toString(), item))
            .as("the option was named by its name; the column holds the definition's "
                + "uuid as key and the option's uuid as value, the form the image before "
                + "this one reads")
            .isEqualTo("true");
    }

    @Test
    void the_reader_reads_the_surrogate_form_a_stage_r3_store_holds() throws SQLException {
        UUID item = created(Map.of("size", "S"));
        plantStored(item, "jsonb_build_object(?::text, ?::text, ?::text, jsonb_build_array(?::text))",
            definitionPk("size"), optionPk("size", "M"),
            definitionPk("tags"), optionPk("tags", "blue"));

        Map<String, Object> attributes = attributesOf(items.read(scope, item));
        assertThat(attributes.get("size"))
            .as("a value stored as the option's surrogate is read and answered as the "
                + "option, here by its uuid while the store carries one")
            .isEqualTo(optionUuid("size", "M").toString());
        assertThat(attributes.get("tags"))
            .as("a multi_choice value stored as surrogates is read the same way")
            .isEqualTo(List.of(optionUuid("tags", "blue").toString()));
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

    private void plantStored(UUID item, String expression, Object... args) throws SQLException {
        try (Connection c = Db.asAdmin();
             PreparedStatement st = c.prepareStatement(
                 "UPDATE worklist.item SET attributes = " + expression + " WHERE id = ?")) {
            int i = 1;
            for (Object arg : args) {
                st.setString(i++, String.valueOf(arg));
            }
            st.setObject(i, item);
            st.executeUpdate();
            c.commit();
        }
    }

    private UUID definitionUuid(String key) throws SQLException {
        return UUID.fromString(text("SELECT id::text FROM worklist.attribute_definition "
            + "WHERE scope_id = ? AND key = ?", scope, key));
    }

    private long definitionPk(String key) throws SQLException {
        return Long.parseLong(text("SELECT pk::text FROM worklist.attribute_definition "
            + "WHERE scope_id = ? AND key = ?", scope, key));
    }

    private UUID optionUuid(String key, String name) throws SQLException {
        return UUID.fromString(text("SELECT o.id::text FROM worklist.attribute_option o "
            + "JOIN worklist.attribute_definition d ON d.pk = o.definition_pk "
            + "WHERE d.scope_id = ? AND d.key = ? AND o.name = ?", scope, key, name));
    }

    private long optionPk(String key, String name) throws SQLException {
        return Long.parseLong(text("SELECT o.pk::text FROM worklist.attribute_option o "
            + "JOIN worklist.attribute_definition d ON d.pk = o.definition_pk "
            + "WHERE d.scope_id = ? AND d.key = ? AND o.name = ?", scope, key, name));
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
