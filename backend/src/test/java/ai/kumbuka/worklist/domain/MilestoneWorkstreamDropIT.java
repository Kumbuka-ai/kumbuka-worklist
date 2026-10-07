package ai.kumbuka.worklist.domain;

import ai.kumbuka.worklist.tenancy.Db;
import ai.kumbuka.worklist.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V17 drops the dead milestone-workstream columns, and only those.
 *
 * <p>V12 retracted the milestone-workstream edge (TAR-0002 section 4) and
 * marked {@code milestone.workstream_id} and {@code number_space.workstream_id}
 * DEAD; V17 drops both together with their composite foreign keys. The
 * item's workstream reference expresses a different invariant — an item
 * belongs to exactly one workstream — and must survive; since V20 it is held
 * by {@code item.workstream_number} alone. A drop that took it
 * along would leave every item without its workstream, and nothing else in
 * the suite reads the catalogue to notice.
 *
 * <p>Read from the catalogue as the admin, so a column the service role
 * cannot see still counts as present.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class MilestoneWorkstreamDropIT {

    @Test
    void the_item_keeps_its_workstream_reference() throws SQLException {
        assertThat(hasColumn("item", "workstream_number"))
            .as("the item's workstream carries the edge TAR-0002 section 4 keeps; V17 "
                + "must not drop it, and since V20 it is held by number (ADR-0042)")
            .isTrue();
        assertThat(hasConstraint("item", "fk_item_workstream_number"))
            .as("the item's workstream reference stays a tenant-bound key")
            .isTrue();
    }

    @Test
    void the_milestone_and_its_counter_carry_no_workstream_column() throws SQLException {
        assertThat(hasColumn("milestone", "workstream_id"))
            .as("milestone.workstream_id was DEAD since V12 and V17 drops it")
            .isFalse();
        assertThat(hasColumn("number_space", "workstream_id"))
            .as("number_space.workstream_id was DEAD since V12 and V17 drops it")
            .isFalse();
        assertThat(hasConstraint("milestone", "fk_milestone_workstream"))
            .as("the foreign key on the dropped milestone column goes with it")
            .isFalse();
        assertThat(hasConstraint("number_space", "fk_number_space_workstream"))
            .as("the foreign key on the dropped counter column goes with it")
            .isFalse();
    }

    private static boolean hasColumn(String table, String column) throws SQLException {
        try (Connection c = Db.asAdmin();
             var st = c.prepareStatement("""
                 SELECT 1 FROM information_schema.columns
                 WHERE table_schema = 'worklist' AND table_name = ? AND column_name = ?
                 """)) {
            st.setString(1, table);
            st.setString(2, column);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean hasConstraint(String table, String constraint) throws SQLException {
        try (Connection c = Db.asAdmin();
             var st = c.prepareStatement("""
                 SELECT 1 FROM pg_constraint
                 WHERE conrelid = ('worklist.' || ?)::regclass AND conname = ?
                 """)) {
            st.setString(1, table);
            st.setString(2, constraint);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next();
            }
        }
    }
}
