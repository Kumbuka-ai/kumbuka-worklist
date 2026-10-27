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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The foreign keys of the migrated schema are named {@code fk_<table>_<target>}
 * (C18.3), read off the catalogue of the running database.
 *
 * <p>{@code MigrationNamingTest} reads the migration text and cannot tell a
 * name that still lives from one that went with its column. This case asks
 * the database: every foreign key of the schema, by the table it belongs to
 * and the table it references, and none named otherwise. The expectation is
 * the rule, not a list of names.
 *
 * <p>RED STATE, observed against the schema before V24: the five keys V19
 * named against the rule were reported, {@code fk_item_status_pk},
 * {@code fk_attribute_option_definition_pk}, {@code fk_item_relation_from_number},
 * {@code fk_item_relation_to_number} and {@code fk_item_relation_type_pk}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ForeignKeyNameIT {

    @Test
    void every_foreign_key_names_its_table_and_its_target() throws SQLException {
        List<String> offenders = new ArrayList<>();
        int keys = 0;
        try (Connection c = Db.asAdmin();
             var st = c.prepareStatement("""
                 SELECT k.conname, t.relname, r.relname
                 FROM pg_constraint k
                 JOIN pg_class t ON t.oid = k.conrelid
                 JOIN pg_class r ON r.oid = k.confrelid
                 JOIN pg_namespace n ON n.oid = t.relnamespace
                 WHERE k.contype = 'f' AND n.nspname = 'worklist'
                 """);
             ResultSet rows = st.executeQuery()) {
            while (rows.next()) {
                keys++;
                String name = rows.getString(1);
                String prefix = "fk_" + rows.getString(2) + "_" + rows.getString(3);
                if (!name.startsWith(prefix)) {
                    offenders.add(name + " (expected " + prefix + "…)");
                }
            }
        }
        assertThat(keys).as("the schema has foreign keys; none read is a wrong query").isPositive();
        assertThat(offenders)
            .as("a foreign key names its table and the table it references (C18.3)")
            .isEmpty();
    }
}
