package ai.kumbuka.worklist.platform;

import ai.kumbuka.worklist.repository.ScopeAccessRepository;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shape of the platform's read contract, as the core publishes it.
 *
 * <p>The service reads {@code platform.scope_access} and holds nothing else on
 * the platform's schema, so the column list IS the interface between the two.
 * A column dropped or renamed by the core is a silent break here: the native
 * query would fail at runtime, on the first request, in a deployment — and
 * nowhere earlier.
 *
 * <h2>What this establishes and what it cannot</h2>
 *
 * It establishes that this service reads the seven columns V24 declares, and
 * that it reads them under the real role and the real grant — the runtime role
 * holding {@code SELECT} on the view and nothing else. It does NOT establish
 * that the running core publishes them: the view here is staged by
 * {@link SubstrateDatabaseResource} from V24's text rather than created by the
 * core's own migration, because this service's suite does not start the core.
 * Measured against the core itself
 * ({@code Kumbuka-ai/kumbuka-server}, tag {@code v0.10.0},
 * {@code backend/server/src/main/resources/db/migration/V24__platform_read_contract.sql},
 * read 2026-09-21): the view selects
 * {@code scope_id, tenant_id, slug, archived, kind, locked, can_write}, in
 * that order, with no {@code kind = 'project'} filter.
 *
 * <p>The expected list is transcribed into
 * {@link SubstrateDatabaseResource#DIRECTORY_COLUMNS} from that migration and
 * is not read back off the staged view — a list read from the substrate would
 * agree with the substrate whatever the core says, which is the one thing a
 * contract check must not do.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ReadContractShapeIT {

    @Inject ScopeAccessRepository scopes;

    @BeforeAll
    static void grantDirectoryAccess() {
        PlatformFixture.grantDirectoryAccess();
    }

    @Test
    void the_view_publishes_the_seven_columns_of_v24() throws Exception {
        assertThat(columnsOfTheView())
            .as("the column list is the whole interface between this service and the "
                + "core. Three of these arrived with V24 and are what lets a private "
                + "scope, a locked one and a read-only one be refused with three "
                + "different sentences instead of being invisible or accepted")
            .containsExactlyElementsOf(SubstrateDatabaseResource.DIRECTORY_COLUMNS);
    }

    /**
     * The service reads all seven, under its own role.
     *
     * <p>The counter-probe for the column list: a view carrying the columns
     * and a service reading four of them would pass the case above and still
     * be unable to refuse anything. This one goes through the repository, so
     * what it reads is what the projection actually selects.
     */
    @Test
    void the_service_reads_every_one_of_them_through_its_own_projection() {
        scopes.bindSubject(SubstrateDatabaseResource.PROBE_SUBJECT);

        ScopeAccessRepository.ScopeAccessRow row =
            scopes.findBySlug(SubstrateDatabaseResource.PROBE_SCOPE_SLUG).orElseThrow();

        assertThat(row.slug()).isEqualTo(SubstrateDatabaseResource.PROBE_SCOPE_SLUG);
        assertThat(row.kind())
            .as("the kind is what a private scope is refused on, and a projection that "
                + "did not read it would leave that refusal unreachable")
            .isEqualTo("project");
        assertThat(row.locked())
            .as("locked is published beside archived because the two are different "
                + "refusals: archived is retired, locked is frozen")
            .isFalse();
        assertThat(row.canWrite())
            .as("and the write right, which is the core's answer and not this "
                + "service's to compute")
            .isTrue();
    }

    /** The view's columns, in the order it declares them. */
    private static List<String> columnsOfTheView() throws Exception {
        var config = ConfigProvider.getConfig();
        List<String> columns = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(
                config.getValue("test.db.url", String.class),
                config.getValue("quarkus.datasource.username", String.class),
                config.getValue("quarkus.datasource.password", String.class));
             Statement s = c.createStatement();
             // Read as the SERVICE role, so a column the service cannot see
             // does not count as published to it. Ordered by the catalogue's
             // own ordinal, which is the order the view declares.
             ResultSet rs = s.executeQuery("""
                 SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'platform' AND table_name = 'scope_access'
                 ORDER BY ordinal_position
                 """)) {
            while (rs.next()) {
                columns.add(rs.getString(1));
            }
        }
        return columns;
    }
}
