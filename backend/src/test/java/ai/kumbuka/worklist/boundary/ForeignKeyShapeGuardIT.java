package ai.kumbuka.worklist.boundary;

import ai.kumbuka.worklist.tenancy.Db;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0042, read off the catalogue: the shape of every key and of every uuid
 * column in this schema.
 *
 * <ol>
 *   <li>Every foreign key carries {@code tenant_id} on both sides, at the
 *       same position: a key is checked with row-level security bypassed.</li>
 *   <li>A key onto a primary object — item, iteration, milestone,
 *       workstream — targets exactly {@code (tenant_id, scope_id, number)},
 *       and the referencing row's own {@code scope_id} sits opposite the
 *       target's, so the two rows share a scope.</li>
 *   <li>Every other key targets exactly {@code (tenant_id, pk)}.</li>
 *   <li>A uuid column exists only as {@code id} of the four primary tables
 *       and as {@code tenant_id} and {@code scope_id}.</li>
 * </ol>
 *
 * <p>The expectations — which tables are primary, which uuid columns are
 * admitted — are written here from ADR-0042, not read from the schema they
 * judge. Read as the administrator, so nothing the service role cannot see
 * escapes the check.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ForeignKeyShapeGuardIT {

    private static final Set<String> PRIMARY = Set.of("item", "iteration", "milestone", "workstream");

    /** Every key, as: table, constraint, its columns, target table, target columns. */
    private static final String KEYS = """
        SELECT c.conrelid::regclass::text, c.conname,
               (SELECT array_to_string(array_agg(a.attname ORDER BY k.ord), ',')
                  FROM unnest(c.conkey) WITH ORDINALITY k(attnum, ord)
                  JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.attnum),
               t.relname,
               (SELECT array_to_string(array_agg(a.attname ORDER BY k.ord), ',')
                  FROM unnest(c.confkey) WITH ORDINALITY k(attnum, ord)
                  JOIN pg_attribute a ON a.attrelid = c.confrelid AND a.attnum = k.attnum)
        FROM pg_constraint c
        JOIN pg_namespace n ON n.oid = c.connamespace
        JOIN pg_class t ON t.oid = c.confrelid
        WHERE n.nspname = 'worklist' AND c.contype = 'f'
        """;

    private static final String UUID_COLUMNS = """
        SELECT c.table_name, c.column_name
        FROM information_schema.columns c
        JOIN information_schema.tables t USING (table_schema, table_name)
        WHERE c.table_schema = 'worklist' AND c.data_type = 'uuid'
          AND t.table_type = 'BASE TABLE'
        """;

    @Test
    void every_key_has_the_shape_adr_0042_gives_it() throws SQLException {
        try (Connection c = Db.asAdmin()) {
            List<String> keys = keys(c);
            assertThat(keys)
                .as("the schema declares keys; finding none would mean the check reads the "
                    + "wrong schema and passes because of it")
                .hasSizeGreaterThanOrEqualTo(13);
            assertThat(misshapenKeys(c))
                .as("every key carries the tenant, a key onto item, iteration, milestone or "
                    + "workstream targets (tenant_id, scope_id, number) from the row's own "
                    + "scope, and every other key targets (tenant_id, pk)")
                .isEmpty();
        }
    }

    @Test
    void a_uuid_column_is_an_outward_identity_or_a_platform_one() throws SQLException {
        try (Connection c = Db.asAdmin()) {
            assertThat(inadmissibleUuidColumns(c))
                .as("a uuid survives only as the id of a primary object and as tenant_id and "
                    + "scope_id; every reference between rows runs on a number or a surrogate")
                .isEmpty();
        }
    }

    @Test
    void the_checks_report_a_planted_uuid_reference() throws SQLException {
        try (Connection m = Db.asMigrator()) {
            try (Statement s = m.createStatement()) {
                s.execute("""
                    CREATE TABLE worklist.planted_reference (
                        tenant_id uuid NOT NULL, scope_id uuid NOT NULL, item_id uuid NOT NULL,
                        FOREIGN KEY (tenant_id, item_id) REFERENCES worklist.item (tenant_id, id))
                    """);
            }
            List<String> keys = misshapenKeys(m);
            List<String> columns = inadmissibleUuidColumns(m);
            m.rollback();
            assertThat(keys)
                .as("RED STATE, observed: a key onto item.id planted in the schema must be "
                    + "reported, or the key check passes whatever the schema holds")
                .anyMatch(key -> key.startsWith("worklist.planted_reference."));
            assertThat(columns)
                .as("RED STATE, observed: the uuid column carrying it must be reported too")
                .contains("planted_reference.item_id");
        }
    }

    private static List<String> misshapenKeys(Connection c) throws SQLException {
        List<String> offenders = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(KEYS)) {
            while (rs.next()) {
                String name = rs.getString(1) + "." + rs.getString(2);
                List<String> own = List.of(rs.getString(3).split(","));
                String target = rs.getString(4);
                List<String> targets = List.of(rs.getString(5).split(","));
                if (!shapeHolds(own, target, targets)) {
                    offenders.add(name + " (" + own + " -> " + target + targets + ")");
                }
            }
        }
        return offenders;
    }

    private static boolean shapeHolds(List<String> own, String target, List<String> targets) {
        int tenant = targets.indexOf("tenant_id");
        if (tenant < 0 || !"tenant_id".equals(own.get(tenant))) {
            return false;
        }
        if (PRIMARY.contains(target)) {
            int scope = targets.indexOf("scope_id");
            return targets.size() == 3 && targets.contains("number")
                && scope >= 0 && "scope_id".equals(own.get(scope));
        }
        return targets.size() == 2 && targets.contains("pk");
    }

    private static List<String> inadmissibleUuidColumns(Connection c) throws SQLException {
        List<String> offenders = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(UUID_COLUMNS)) {
            while (rs.next()) {
                String table = rs.getString(1);
                String column = rs.getString(2);
                boolean admitted = "tenant_id".equals(column) || "scope_id".equals(column)
                    || ("id".equals(column) && PRIMARY.contains(table));
                if (!admitted) {
                    offenders.add(table + "." + column);
                }
            }
        }
        return offenders;
    }

    private static List<String> keys(Connection c) throws SQLException {
        List<String> all = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(KEYS)) {
            while (rs.next()) {
                all.add(rs.getString(1) + "." + rs.getString(2));
            }
        }
        return all;
    }
}
