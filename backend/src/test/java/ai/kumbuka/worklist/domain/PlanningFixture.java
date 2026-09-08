package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.Db;
import org.eclipse.microprofile.config.ConfigProvider;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * The one fixture of the planning probes that goes around the service, and
 * the reason it does.
 *
 * <p>{@code item.milestone_id} is now settable through {@code item.update},
 * with an existence check the item verb enforces. The probes below still
 * write it over JDBC because they plant milestones and iterations that the
 * planning cases then read: driving the assignment through the item verb
 * would run the same check the item probes cover, and re-checking it here
 * would be probing the same guard twice.
 *
 * <p>It is held in one place, named for what it is, rather than copied into
 * each probe class.
 */
final class PlanningFixture {

    private PlanningFixture() {
    }

    /** The tenant the ORM binds, read from the same setting the service runs on. */
    static UUID boundTenant() {
        return UUID.fromString(
            ConfigProvider.getConfig().getValue("worklist.tenant-id", String.class));
    }

    /** Point an item at a milestone, over JDBC, under the runtime role. */
    static UUID pointAtMilestone(UUID itemId, UUID milestoneId) {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, boundTenant());
            try (var st = c.prepareStatement(
                    "UPDATE worklist.item SET milestone_id = ? WHERE id = ?")) {
                st.setObject(1, milestoneId);
                st.setObject(2, itemId);
                st.executeUpdate();
            }
            c.commit();
        } catch (SQLException notWritable) {
            throw new IllegalStateException(
                "the milestone fixture could not write item " + itemId, notWritable);
        }
        return itemId;
    }

    /** How many membership rows an iteration holds, terminal ones included. */
    static int membershipRowsOf(UUID iterationId) throws SQLException {
        try (Connection c = Db.asService()) {
            Db.bindTenant(c, boundTenant());
            try (var st = c.prepareStatement(
                    "SELECT count(*) FROM worklist.iteration_membership "
                        + "WHERE iteration_id = ?")) {
                st.setObject(1, iterationId);
                try (ResultSet rows = st.executeQuery()) {
                    rows.next();
                    return rows.getInt(1);
                }
            }
        }
    }

    /** Whether a table of this schema carries a {@code conflict_token} column. */
    static boolean hasConflictToken(Connection c, String table) throws SQLException {
        try (var st = c.prepareStatement(
                "SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_schema = 'worklist' AND table_name = ? "
                    + "AND column_name = 'conflict_token'")) {
            st.setString(1, table);
            try (ResultSet rows = st.executeQuery()) {
                rows.next();
                return rows.getInt(1) == 1;
            }
        }
    }
}
