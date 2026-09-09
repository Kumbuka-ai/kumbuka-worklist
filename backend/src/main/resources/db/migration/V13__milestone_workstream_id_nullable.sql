-- ===========================================================================
-- V13: milestone.workstream_id becomes nullable.
--
-- V10 narrowed the column to NOT NULL while the milestone belonged to a
-- workstream. V12 retracted that edge (TAR-0002 §4, REQ-0148 obsolete)
-- but left the narrowing in place, on the theory the column was harmless
-- as long as callers still populated it. The theory failed at the first
-- bootstrap: `bootstrap-scope.sql` seeds three milestone markers without
-- a workstream, and the seed refuses under NOT NULL — which means NO
-- FRESH SCOPE can be opened, not even `jbaconsult` for the second import
-- and no future tenant.
--
-- A dead column that must still be populated is not a dead column. The
-- narrowing has to fall for the column to be truly dead.
--
-- WHY ONLY MILESTONE
--
-- Measured 2026-09-09: V10 narrows `item.workstream_id` and
-- `milestone.workstream_id`, NOT `number_space.workstream_id`.
-- `number_space.workstream_id` has been nullable since V9 introduced it,
-- and V12 left it that way. So this migration touches only milestone.
--
-- `item.workstream_id` stays NOT NULL: it expresses a different
-- invariant (an item belongs to a workstream), unrelated to the retracted
-- milestone-workstream edge.
--
-- WHY NO GRANT IS ISSUED HERE
--
-- Same reason V5, V6, V7, V10, V11, V12 hand out none. The runtime role
-- holds SELECT, INSERT, UPDATE on `worklist.milestone` at the table
-- level from V4; a table-level privilege covers every constraint the
-- table acquires. `ServiceRolePrivilegeIT` asserts the exact privilege
-- set in both directions.
-- ===========================================================================

ALTER TABLE worklist.milestone
    ALTER COLUMN workstream_id DROP NOT NULL;

COMMENT ON COLUMN worklist.milestone.workstream_id IS
    'DEAD as of V12 (2026-09-09): the milestone-workstream edge was '
    'retracted (TAR-0002 §4, REQ-0148 obsolete). Nullable as of V13 '
    '(2026-09-09): the NOT NULL narrowing from V10 fell because it '
    'prevented bootstrap-scope.sql from seeding the milestone markers '
    'without a workstream, which blocked opening any fresh scope. The '
    'column is now both semantically dead and syntactically optional. '
    'Dropped together with fk_milestone_workstream in the next '
    'migration that opens this table for another reason.';
