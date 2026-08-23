package ai.kumbuka.worklist.tenancy;

import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Binds {@code app.tenant_id} before every migration, so that a migration
 * carrying DML — a seed, a backfill — passes the row-level-security policies
 * instead of failing closed against them.
 *
 * <p>Pure DDL needs no binding, because row-level security filters DML only.
 * The callback is here for the migrations that are not pure DDL, and it is
 * here BEFORE the first of them rather than after: under
 * {@code FORCE ROW LEVEL SECURITY} a forgotten binding does not raise. The
 * insert simply affects no rows, the migration succeeds, and the seed is
 * missing in a way that surfaces much later and somewhere else.
 *
 * <p>Configuration is read through {@code ConfigProvider} rather than
 * injected: this runs during boot, before CDI is fully available.
 *
 * <h2>How this callback reaches Flyway</h2>
 *
 * Through {@code quarkus.flyway.callbacks} in application.properties, and
 * through nothing else. The Quarkus Flyway extension resolves callbacks from
 * that configuration key by class name and instantiates them REFLECTIVELY,
 * with the no-argument constructor. It does not discover them as CDI beans.
 *
 * <p>That distinction is worth a paragraph because getting it wrong is
 * silent: a callback written as an {@code @ApplicationScoped} bean and left
 * out of the configuration is simply never registered, the migrations run
 * without it, and nothing anywhere reports a callback that did not fire. So
 * this class is a plain class, it holds no injection point, and the probe
 * that observes it firing is the only thing standing between the code and
 * that silence.
 */
public class TenantMigrationCallback extends BaseCallback {

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_EACH_MIGRATE;
    }

    @Override
    public void handle(Event event, Context context) {
        String tenantId = ConfigProvider.getConfig().getValue("worklist.tenant-id", String.class);
        // is_local = true scopes the binding to the transaction Flyway runs
        // the migration in, so it cannot outlive the migration on a pooled
        // connection. Parameterised rather than concatenated: a configured
        // value is still an input.
        try (PreparedStatement st = context.getConnection()
                .prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            st.setString(1, tenantId);
            st.execute();
        } catch (SQLException e) {
            throw new IllegalStateException(
                "failed to bind app.tenant_id before a Flyway migration", e);
        }
    }
}
