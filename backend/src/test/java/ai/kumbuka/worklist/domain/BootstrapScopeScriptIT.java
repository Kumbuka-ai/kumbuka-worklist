package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.Db;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code db/bootstrap/bootstrap-scope.sql} declares a scope against the schema
 * the migrations of this build produce, and a second run changes nothing.
 *
 * <h2>What is being defended</h2>
 *
 * <p>The script lives outside {@code db/migration}, so Flyway never runs it
 * and no migration test reads it. A migration that drops a column the script
 * names leaves every other test green while the one path its own header calls
 * the way to declare a scope fails on its first statement. This probe runs
 * the shipped file the way that header prescribes: through psql, as the
 * migrator, with the tenancy axis bound ahead of the file.
 *
 * <p>After the run the scope carries the four views, the vocabulary the
 * script declares and the default workstream, and the service, running under
 * its own role, can create an item in it. A second run exits cleanly and
 * leaves every row it touched as it was.
 *
 * <h2>The red state, and how it was observed</h2>
 *
 * <p>With the script restored to its pre-V20 joins over {@code selector.id},
 * {@code number_space.selector_id} and {@code attribute_option.definition_id},
 * the first run exits non-zero and both cases fail, the psql error naming the
 * missing column. Measured 2026-10-02 against the V20 schema.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class BootstrapScopeScriptIT {

    private static final String SCRIPT = "db/bootstrap/bootstrap-scope.sql";
    private static final String TENANT = SubstrateDatabaseResource.TENANT_ID;

    @Inject ItemService items;

    @Test
    void the_script_declares_a_scope_in_which_the_service_can_create_an_item()
            throws Exception {
        assertThat(highestAppliedMigration())
            .as("the probe runs against the schema of the highest migration this "
                + "build ships, which is the one the script has to match")
            .isEqualTo(highestShippedMigration());

        UUID scope = UUID.randomUUID();
        Container.ExecResult run = bootstrap(scope);
        assertThat(run.getExitCode())
            .as("bootstrap-scope.sql ran against the migrated schema; psql said:%n%s",
                run.getStderr())
            .isZero();

        Map<String, List<String>> declared = snapshot(scope);
        assertThat(declared.get("selectors"))
            .as("the four views")
            .containsExactly("item", "iteration", "milestone", "workstream");
        assertThat(declared.get("statuses"))
            .containsExactly("new", "open", "done", "dropped", "obsolete");
        assertThat(declared.get("attributes"))
            .as("each declared attribute with the number of its options")
            .containsExactly("cluster:8", "type:5", "priority:3", "size:3", "component:0");
        assertThat(declared.get("relation_types")).containsExactly("depends_on");
        assertThat(declared.get("workstreams")).containsExactly("1:default:true");
        assertThat(declared.get("counters"))
            .as("the counters the script opens, by the selector they count for")
            .containsExactly("item:0", "iteration:0", "milestone:0");

        Map<String, Object> item = items.create(scope, Map.of(
            Field.TITLE.canonicalName(), "the first item of a bootstrapped scope",
            Field.STATUS.canonicalName(), "open"));
        assertThat(item.get(Field.NUMBER.canonicalName()))
            .as("the service, under its own role, allocates from the item counter "
                + "the script opened")
            .isEqualTo(1L);
    }

    @Test
    void a_second_run_changes_nothing() throws Exception {
        UUID scope = UUID.randomUUID();
        Container.ExecResult first = bootstrap(scope);
        assertThat(first.getExitCode())
            .as("first run; psql said:%n%s", first.getStderr())
            .isZero();
        Map<String, List<String>> afterFirst = snapshot(scope);

        Container.ExecResult second = bootstrap(scope);
        assertThat(second.getExitCode())
            .as("second run; psql said:%n%s", second.getStderr())
            .isZero();
        assertThat(snapshot(scope))
            .as("every insert carries ON CONFLICT DO NOTHING and the one update "
                + "is a GREATEST, so a repeat leaves the scope as it was")
            .isEqualTo(afterFirst);
    }

    /** The shipped script, with the tenancy axis bound ahead of it as its header says. */
    private static Container.ExecResult bootstrap(UUID scope)
            throws IOException, InterruptedException {
        String script;
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(SCRIPT)) {
            assertThat(in).as("the script is on the classpath at " + SCRIPT).isNotNull();
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return SubstrateDatabaseResource.psqlAsMigrator(
            "SET app.tenant_id = '" + TENANT + "';\n" + script,
            "tenant_id=" + TENANT, "scope_id=" + scope);
    }

    /**
     * What the scope carries, read as the administrator so the read itself
     * is not filtered by the policy it does not need to probe.
     */
    private static Map<String, List<String>> snapshot(UUID scope) throws SQLException {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        try (Connection c = Db.asAdmin()) {
            rows.put("settings", column(c, scope, """
                SELECT max_planned_iterations || '/' || warn_planned_iterations || '/'
                       || max_memberships_per_iteration || '/' || warn_memberships_per_iteration
                  FROM worklist.scope_setting WHERE tenant_id = ?::uuid AND scope_id = ?::uuid
                """));
            rows.put("selectors", column(c, scope, """
                SELECT token FROM worklist.selector
                 WHERE tenant_id = ?::uuid AND scope_id = ?::uuid ORDER BY token
                """));
            rows.put("statuses", column(c, scope, """
                SELECT name FROM worklist.item_status
                 WHERE tenant_id = ?::uuid AND scope_id = ?::uuid ORDER BY rank
                """));
            rows.put("attributes", column(c, scope, """
                SELECT d.key || ':' || count(o.pk)
                  FROM worklist.attribute_definition d
                  LEFT JOIN worklist.attribute_option o
                         ON o.tenant_id = d.tenant_id AND o.definition_pk = d.pk
                 WHERE d.tenant_id = ?::uuid AND d.scope_id = ?::uuid
                 GROUP BY d.key, d.rank ORDER BY d.rank
                """));
            rows.put("options", column(c, scope, """
                SELECT d.key || ':' || o.name || ':' || o.rank
                  FROM worklist.attribute_option o
                  JOIN worklist.attribute_definition d
                    ON d.tenant_id = o.tenant_id AND d.pk = o.definition_pk
                 WHERE o.tenant_id = ?::uuid AND o.scope_id = ?::uuid
                 ORDER BY d.rank, o.rank
                """));
            rows.put("relation_types", column(c, scope, """
                SELECT name FROM worklist.relation_type
                 WHERE tenant_id = ?::uuid AND scope_id = ?::uuid ORDER BY rank
                """));
            rows.put("workstreams", column(c, scope, """
                SELECT number || ':' || token || ':' || is_default FROM worklist.workstream
                 WHERE tenant_id = ?::uuid AND scope_id = ?::uuid ORDER BY number
                """));
            rows.put("counters", column(c, scope, """
                SELECT s.token || ':' || n.high_water_mark
                  FROM worklist.number_space n
                  JOIN worklist.selector s ON s.tenant_id = n.tenant_id AND s.pk = n.selector_pk
                 WHERE n.tenant_id = ?::uuid AND n.scope_id = ?::uuid ORDER BY s.token
                """));
            c.rollback();
        }
        return rows;
    }

    private static List<String> column(Connection c, UUID scope, String sql)
            throws SQLException {
        List<String> values = new ArrayList<>();
        try (var st = c.prepareStatement(sql)) {
            st.setString(1, TENANT);
            st.setString(2, scope.toString());
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    values.add(rs.getString(1));
                }
            }
        }
        return values;
    }

    private static int highestAppliedMigration() throws SQLException {
        try (Connection c = Db.asAdmin();
             var st = c.prepareStatement("""
                 SELECT max(version::int) FROM worklist.flyway_schema_history
                  WHERE success AND version IS NOT NULL
                 """);
             ResultSet rs = st.executeQuery()) {
            rs.next();
            int version = rs.getInt(1);
            c.rollback();
            return version;
        }
    }

    /** The highest {@code V<n>__} this build ships under {@code db/migration}. */
    private static int highestShippedMigration() throws IOException {
        Path direct = Paths.get("src", "main", "resources", "db", "migration");
        Path dir = Files.isDirectory(direct)
            ? direct : Paths.get("backend").resolve(direct);
        try (Stream<Path> found = Files.list(dir)) {
            return found.map(f -> f.getFileName().toString())
                .filter(name -> name.matches("V\\d+__.*\\.sql"))
                .mapToInt(name -> Integer.parseInt(name.substring(1, name.indexOf("__"))))
                .max()
                .orElseThrow();
        }
    }
}
