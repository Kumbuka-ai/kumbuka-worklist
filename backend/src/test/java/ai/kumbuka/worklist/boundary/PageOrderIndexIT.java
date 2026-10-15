/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.boundary;

import ai.kumbuka.worklist.tenancy.Db;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every view's table carries an index in the creation order a query pages by,
 * {@code (tenant_id, scope_id, created_at, number)}, read from the catalogue of
 * the migrated database.
 *
 * <p>The number order needs no index of its own: the unique constraint on
 * {@code (tenant_id, scope_id, number)} that every view carries serves it, and
 * {@code ForeignKeyShapeGuardIT} already holds those.
 *
 * <p>RED STATE, observed 2026-10-11: with V21 removed from the migration set
 * (and the build cleaned, so the compiled copy went too), all four tables were
 * reported without the index.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class PageOrderIndexIT {

    private static final List<String> VIEWS = List.of("item", "iteration", "milestone", "workstream");

    /** The key columns of every index on one table of the schema, in key order. */
    private static final String INDEXES = """
        SELECT (SELECT array_to_string(array_agg(a.attname ORDER BY k.ord), ',')
                  FROM unnest(i.indkey) WITH ORDINALITY k(attnum, ord)
                  JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum)
        FROM pg_index i
        JOIN pg_class t ON t.oid = i.indrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'worklist' AND t.relname = ?
        """;

    @Test
    void every_view_has_an_index_in_its_creation_order() throws SQLException {
        List<String> without = new ArrayList<>();
        try (Connection c = Db.asService()) {
            for (String view : VIEWS) {
                if (!indexesOf(c, view).contains("tenant_id,scope_id,created_at,number")) {
                    without.add(view);
                }
            }
        }
        assertThat(without)
            .as("a page in creation order reads its rows through this index; without it "
                + "every page reads and sorts the whole scope")
            .isEmpty();
    }

    private static List<String> indexesOf(Connection c, String table) throws SQLException {
        List<String> keys = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(INDEXES)) {
            s.setString(1, table);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    keys.add(rs.getString(1));
                }
            }
        }
        return keys;
    }
}
