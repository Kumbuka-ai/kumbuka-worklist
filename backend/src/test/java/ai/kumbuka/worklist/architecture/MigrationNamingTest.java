/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.worklist.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migrations are named {@code V<n>__<snake_case>.sql} or, for a step between
 * two versions, {@code V<n>_<m>__<snake_case>.sql}, run with the tenancy
 * callback before each, and name their indexes and constraints after the
 * table they belong to (C18.1, C18.3).
 *
 * <p>The name rule: {@code idx_<table>_…} for an index, {@code uq_<table>_…}
 * for a unique constraint or a unique index, {@code ck_<table>_…} for a
 * check, {@code fk_<table>_<target>…} for a foreign key. A primary key is none
 * of the four and is not read. The table a name belongs to is the one its
 * statement names: the table being created, the table altered, the table an
 * index is on.
 *
 * <p>A name an applied migration gave cannot be changed in that migration:
 * its checksum is recorded in every database it ran on. Those names stand in
 * {@link #APPLIED}, each with its migration, and a name the rule refuses in a
 * migration not listed there is a defect. Renaming one is a migration of its
 * own.
 */
class MigrationNamingTest {

    /** The schema the migrations write. */
    static final String SCHEMA = "worklist";

    /**
     * The file form of a versioned migration (C18.1), and of a step between
     * two versions already released: {@code V<n>_<m>__<snake_case>.sql}, which
     * Flyway reads as version {@code n.m}. A step exists because a version once
     * released cannot be renumbered, and a migration that must run before the
     * next one, on a database that stands at the one before, has no other place.
     */
    private static final Pattern FILE = Pattern.compile(
        "V[1-9]\\d*(?:_[1-9]\\d*)?__[a-z0-9]+(?:_[a-z0-9]+)*\\.sql");

    /**
     * Names applied migrations gave against the rule, as {@code V<n> name}.
     * Each stays because its migration ran: editing the file breaks every
     * database it ran on. The keys of V4 went with their columns in V20; the
     * five of V19 that live on are renamed by V24, whose new names this test
     * holds to the rule like any other.
     */
    static final Set<String> APPLIED = Set.of(
        "V4 fk_attribute_option_definition", "V4 fk_item_status", "V4 fk_item_relation_from",
        "V4 fk_item_relation_to", "V4 fk_item_relation_type", "V19 fk_item_status_pk",
        "V19 fk_attribute_option_definition_pk", "V19 fk_item_relation_from_number",
        "V19 fk_item_relation_to_number", "V19 fk_item_relation_type_pk");

    /** The statements that give a name, in the order they stand. */
    private static final Pattern NAMING = Pattern.compile(
        "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?" + SCHEMA + "\\.(?<created>\\w+)\\s*\\("
            + "|ALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(?:ONLY\\s+)?" + SCHEMA
            + "\\.(?<altered>\\w+)"
            + "|CONSTRAINT\\s+(?<constraint>\\w+)\\s+(?<kind>UNIQUE|CHECK|PRIMARY\\s+KEY|EXCLUDE"
            + "|FOREIGN\\s+KEY\\s*\\([^)]*\\)\\s*REFERENCES\\s+(?:" + SCHEMA
            + "\\.)?(?<target>\\w+))"
            + "|RENAME\\s+CONSTRAINT\\s+\\w+\\s+TO\\s+(?<renamed>\\w+)"
            + "|CREATE\\s+(?<unique>UNIQUE\\s+)?INDEX\\s+(?:CONCURRENTLY\\s+)?(?:IF\\s+NOT\\s+EXISTS"
            + "\\s+)?(?<index>\\w+)\\s+ON\\s+(?:ONLY\\s+)?" + SCHEMA + "\\.(?<indexed>\\w+)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Test
    void every_migration_file_has_the_versioned_form() {
        assertThat(SourceTree.migrations().stream().map(p -> p.getFileName().toString())
                .filter(name -> !FILE.matcher(name).matches()).toList())
            .as("Flyway reads a version and a description from the name; anything else is "
                + "skipped or misordered (C18.1). Name it V<n>__<snake_case>.sql, or "
                + "V<n>_<m>__<snake_case>.sql for a step between two versions")
            .isEmpty();
    }

    @Test
    void every_migration_runs_with_the_tenancy_callback_before_each() {
        String callback = SourceTree.code(SourceTree.root("main")
            .resolve(SourceTree.BASE.replace('.', '/'))
            .resolve("tenancy/TenantMigrationCallback.java"));
        assertThat(callback).as("the callback binds the tenant before each migration (C18.1)")
            .contains("Event.BEFORE_EACH_MIGRATE");
        assertThat(SourceTree.text(configuration()))
            .as("and is registered, which is the only way Flyway calls it")
            .contains("quarkus.flyway.callbacks=" + SourceTree.BASE
                + ".tenancy.TenantMigrationCallback");
    }

    @Test
    void every_index_and_constraint_is_named_after_its_table() {
        Map<String, String> migrations = new LinkedHashMap<>();
        for (Path migration : SourceTree.migrations()) {
            migrations.put(migration.getFileName().toString(), SourceTree.text(migration));
        }
        List<String> offences = offences(migrations);
        assertThat(offences.stream().filter(o -> !APPLIED.contains(o.substring(0,
                o.indexOf(':')))).toList())
            .as("a name says which table it belongs to and what it is, so a refusal of the "
                + "database names its table (C18.3). Name it idx_, uq_, ck_ or fk_, then the "
                + "table, then what it holds")
            .isEmpty();
        assertThat(APPLIED.stream().filter(applied -> offences.stream()
                .noneMatch(o -> o.startsWith(applied + ":"))).sorted().toList())
            .as("a name listed as applied against the rule that the migrations no longer give "
                + "is a list gone stale")
            .isEmpty();
    }

    /** The red state, observed on every build. */
    @Test
    void the_check_reports_each_kind_named_against_its_table() {
        Map<String, String> planted = new LinkedHashMap<>();
        planted.put("V30__planted.sql", """
            CREATE TABLE worklist.note (
                id BIGINT,
                owner_id BIGINT,
                CONSTRAINT note_once UNIQUE (id),
                CONSTRAINT ck_task_positive CHECK (id > 0),
                CONSTRAINT fk_note_owner FOREIGN KEY (owner_id) REFERENCES worklist.task (id),
                CONSTRAINT uq_note_id UNIQUE (id, owner_id)
            );
            -- CONSTRAINT commented_out CHECK (true)
            CREATE INDEX note_owner ON worklist.note (owner_id);
            CREATE INDEX idx_note_owner ON worklist.note (owner_id);
            ALTER TABLE worklist.note ADD CONSTRAINT note_check CHECK (id < 9);
            ALTER TABLE worklist.note RENAME CONSTRAINT uq_note_id TO note_pair;
            """);

        assertThat(offences(planted))
            .as("RED STATE, observed: a constraint without its kind, one under another table, "
                + "a foreign key that does not name its target, an index without its prefix and "
                + "a constraint added later must each be reported; a well-named one must not")
            .containsExactly(
                "V30 note_once: a unique constraint of note is named uq_note_…",
                "V30 ck_task_positive: a check of note is named ck_note_…",
                "V30 fk_note_owner: a foreign key of note to task is named fk_note_task…",
                "V30 note_owner: an index of note is named idx_note_…",
                "V30 note_check: a check of note is named ck_note_…",
                "V30 note_pair: a constraint of note is renamed to idx_, uq_, ck_ or fk_ note_…");
        assertThat(FILE.matcher("V7__Claim-And-Metadata.sql").matches())
            .as("RED STATE, observed: a description that is no snake_case is refused").isFalse();
        assertThat(FILE.matcher("V16_0__step.sql").matches())
            .as("RED STATE, observed: a step numbered 0 is refused, it would sort before its "
                + "version").isFalse();
        assertThat(FILE.matcher("V16.1__step.sql").matches())
            .as("RED STATE, observed: a step written with a dot is refused").isFalse();
        assertThat(FILE.matcher("V16_1__step.sql").matches())
            .as("a step between two versions is admitted").isTrue();
    }

    /** Every name the migrations give against the rule, as {@code V<n> name: why}. */
    static List<String> offences(Map<String, String> migrations) {
        List<String> offences = new ArrayList<>();
        for (Map.Entry<String, String> migration : migrations.entrySet()) {
            String version = migration.getKey().replaceFirst("__.*", "");
            Matcher m = NAMING.matcher(migration.getValue().replaceAll("--[^\\n]*", " "));
            String table = null;
            while (m.find()) {
                if (m.group("created") != null) {
                    table = m.group("created");
                } else if (m.group("altered") != null) {
                    table = m.group("altered");
                } else if (m.group("constraint") != null) {
                    String why = constraintOffence(table, m.group("constraint"),
                        kindOf(m.group("kind")), m.group("target"));
                    if (why != null) {
                        offences.add(version + " " + m.group("constraint") + ": " + why);
                    }
                } else if (m.group("renamed") != null) {
                    String renamed = m.group("renamed");
                    String of = table;
                    boolean named = Set.of("idx_", "uq_", "ck_", "fk_").stream()
                        .anyMatch(kind -> renamed.startsWith(kind + of + "_"));
                    if (!named) {
                        offences.add(version + " " + renamed + ": a constraint of " + table
                            + " is renamed to idx_, uq_, ck_ or fk_ " + table + "_…");
                    }
                } else {
                    String index = m.group("index");
                    String on = m.group("indexed");
                    boolean named = index.startsWith("idx_" + on + "_")
                        || (m.group("unique") != null && index.startsWith("uq_" + on + "_"));
                    if (!named) {
                        offences.add(version + " " + index + ": an index of " + on
                            + " is named idx_" + on + "_…");
                    }
                }
            }
        }
        return offences;
    }

    /** The kind a constraint's definition opens with, in one spelling. */
    private static String kindOf(String definition) {
        String kind = definition.toUpperCase().replaceAll("\\s+", " ");
        return kind.startsWith("FOREIGN KEY") ? "FOREIGN KEY" : kind;
    }

    private static String constraintOffence(String table, String name, String kind,
                                            String target) {
        return switch (kind) {
            case "UNIQUE" -> name.startsWith("uq_" + table + "_") ? null
                : "a unique constraint of " + table + " is named uq_" + table + "_…";
            case "CHECK" -> name.startsWith("ck_" + table + "_") ? null
                : "a check of " + table + " is named ck_" + table + "_…";
            case "FOREIGN KEY" -> target != null && name.startsWith("fk_" + table + "_" + target)
                ? null
                : "a foreign key of " + table + " to " + target + " is named fk_" + table + "_"
                    + target + "…";
            default -> null;
        };
    }

    private static Path configuration() {
        Path direct = Paths.get("src", "main", "resources", "application.properties");
        return direct.toFile().isFile() ? direct : Paths.get("backend").resolve(direct);
    }
}
