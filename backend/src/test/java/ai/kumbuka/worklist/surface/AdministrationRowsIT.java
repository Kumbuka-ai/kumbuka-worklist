/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.surface;

import ai.kumbuka.worklist.domain.AttributeDefinition;
import ai.kumbuka.worklist.domain.VocabularyRegistry;
import ai.kumbuka.worklist.tenancy.Db;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The administration surface writes the rows the registry writes (REA-0011
 * S4 rollback, acceptance K9): the same declarations, made once over REST in
 * one scope and once through {@link VocabularyRegistry} directly in another,
 * leave rows that agree in every column but the ones that name the row or its
 * scope -- the surrogate, the scope, the two instants -- and a surrogate
 * another row points at is compared by what it points at.
 *
 * <p>Read by SQL under the service's own role, around the ORM, so that what
 * is compared is what the store holds.
 *
 * <h2>The red probe</h2>
 *
 * With the surface writing a column of its own -- {@code declareStatus}
 * passing {@code verified} for {@code reviewed} -- the status rows would differ
 * in {@code reviewed}, and {@link #k9_the_surface_writes_the_rows_the_registry_writes}
 * would fail at the comparison of {@code item_status}. Read from the code, not
 * run: the run that wrote this class had no database.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class AdministrationRowsIT {

    /** The columns that name the row or its scope, or record when it was written. */
    private static final String UNCOMPARED = " - 'pk' - 'id' - 'scope_id' - 'created_at' "
        + "- 'updated_at'";

    @Inject TestIdentityAssociation identity;
    @Inject VocabularyRegistry vocabulary;

    private UUID surfaceScope;
    private UUID registryScope;
    private String slug;

    @BeforeEach
    void twoScopes() {
        SurfaceFixture.stage();
        SurfaceFixture.asMember(identity);
        surfaceScope = UUID.randomUUID();
        registryScope = UUID.randomUUID();
        slug = SurfaceFixture.publishScope("rows-" + surfaceScope.toString().substring(0, 8),
            surfaceScope, "project", false);
    }

    @Test
    void k9_the_surface_writes_the_rows_the_registry_writes() throws SQLException {
        post("statuses", Map.of("name", "planned", "rank", 1, "actionable", true));
        post("statuses", Map.of("name", "obsolete", "rank", 2, "closed", true));
        post("statuses", Map.of("name", "verified-done", "rank", 3, "closed", true,
            "successful", true, "verified", true));
        post("attributes", Map.of("key", "type", "name", "Type", "type", "choice", "rank", 1,
            "sortable", true));
        post("attributes/type/options", Map.of("name", "task", "rank", 1));
        post("relation-types", Map.of("name", "depends_on", "blocks", true, "rank", 1));
        post("relation-types", Map.of("name", "duplicate_of", "rank", 2,
            "target_status", "obsolete"));

        vocabulary.declareStatus(registryScope, "planned", 1, true, false, false, false);
        vocabulary.declareStatus(registryScope, "obsolete", 2, false, false, true, false);
        vocabulary.declareStatus(registryScope, "verified-done", 3, false, false, true, true,
            false, false, true);
        vocabulary.declareAttribute(registryScope, "type", "Type", AttributeDefinition.CHOICE,
            1, true);
        vocabulary.declareOption(registryScope, "type", "task", 1);
        vocabulary.declareRelationType(registryScope, "depends_on", true, 1);
        vocabulary.declareRelationType(registryScope, "duplicate_of", false, 2, "obsolete");

        for (String query : List.of(
                "SELECT (to_jsonb(t)" + UNCOMPARED + ")::text FROM worklist.item_status t "
                    + "WHERE t.scope_id = ? ORDER BY t.name",
                "SELECT (to_jsonb(t)" + UNCOMPARED + ")::text FROM worklist.attribute_definition t "
                    + "WHERE t.scope_id = ? ORDER BY t.key",
                "SELECT (to_jsonb(t)" + UNCOMPARED + " - 'definition_pk' - 'definition_id' "
                    + "|| jsonb_build_object('definition', d.key))::text "
                    + "FROM worklist.attribute_option t "
                    + "JOIN worklist.attribute_definition d ON d.pk = t.definition_pk "
                    + "WHERE t.scope_id = ? ORDER BY t.name",
                "SELECT (to_jsonb(t)" + UNCOMPARED + " - 'target_status_pk' "
                    + "|| jsonb_build_object('target_status', s.name))::text "
                    + "FROM worklist.relation_type t "
                    + "LEFT JOIN worklist.item_status s ON s.pk = t.target_status_pk "
                    + "WHERE t.scope_id = ? ORDER BY t.name")) {
            List<String> overTheSurface = rows(query, surfaceScope);
            assertThat(overTheSurface).as("the surface wrote rows: %s", query).isNotEmpty();
            assertThat(overTheSurface)
                .as("the rows the surface writes agree with the registry's in every column")
                .isEqualTo(rows(query, registryScope));
        }
    }

    private void post(String collection, Map<String, Object> body) {
        int status = given().contentType(ContentType.JSON).body(body)
            .post("/api/" + slug + "/admin/" + collection).statusCode();
        assertThat(status).as("%s %s", collection, body).isEqualTo(200);
    }

    private static List<String> rows(String query, UUID scope) throws SQLException {
        List<String> rows = new ArrayList<>();
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, UUID.fromString(SubstrateDatabaseResource.TENANT_ID));
            try (var st = c.prepareStatement(query)) {
                st.setObject(1, scope);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        rows.add(rs.getString(1));
                    }
                }
            }
            c.rollback();
        }
        return rows;
    }
}
