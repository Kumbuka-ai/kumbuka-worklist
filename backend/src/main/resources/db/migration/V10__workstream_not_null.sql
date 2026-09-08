-- ===========================================================================
-- V10: narrow `workstream_id` to NOT NULL on `item` and `milestone`.
--
-- V9 added the column, backfilled every existing row to its scope's
-- default, and added the composite foreign key. This migration is the
-- second half of the two-step change the frame ratified: the invariant is
-- turned on once every row carries a value.
--
-- WHY IT IS A SEPARATE FILE AND NOT AN EDIT OF V9
--
-- An applied migration is not edited. V9's checksum is recorded in
-- `worklist.flyway_schema_history` at the first boot after V9 lands, and a
-- text edit there would fail the next boot. The narrowing lands as V10
-- because that is what forward-only additive migration looks like — the
-- shape V5 argued for and V6 and V7 held.
--
-- WHY THIS FILE HANDS OUT NO GRANT
--
-- Same reason V5, V6 and V7 hand out none. The runtime role holds the full
-- table-level privilege set on `worklist.item` and `worklist.milestone`,
-- and a table-level privilege covers every column and every constraint the
-- table acquires. `ServiceRolePrivilegeIT` reads the catalogue and would
-- surface a departure in either direction.
-- ===========================================================================

ALTER TABLE worklist.item
    ALTER COLUMN workstream_id SET NOT NULL;

ALTER TABLE worklist.milestone
    ALTER COLUMN workstream_id SET NOT NULL;
