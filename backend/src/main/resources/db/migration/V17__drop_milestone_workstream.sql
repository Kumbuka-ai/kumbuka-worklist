-- ===========================================================================
-- V17: drop the dead milestone-workstream columns.
--
-- V12 (2026-09-09) retracted the milestone-workstream edge (TAR-0002
-- section 4: a milestone belongs to no workstream) and marked
-- `milestone.workstream_id` and `number_space.workstream_id` DEAD, stating
-- that the columns and their two composite foreign keys would be dropped
-- later. V13 announced the same for the milestone's column when it lifted
-- the NOT NULL. This migration carries out that announced drop. V12 and
-- V13 are applied and are not edited; this file is where the drop happens.
--
-- WHY THIS IS SAFE FOR THE RUNNING IMAGE
--
-- The contract half of an expand/contract pair. The image that stopped
-- mapping, selecting and writing both columns shipped first (the
-- milestone's workstream left the surface, and a write on it is refused),
-- so the image this schema meets no longer names either column. An image
-- older than that one still selects them; this migration must not reach a
-- database such an image runs against.
--
-- WHAT DOES NOT CHANGE
--
--   * `item.workstream_id`, its NOT NULL and `fk_item_workstream` stay:
--     an item belongs to exactly one workstream, an invariant the
--     retraction never touched.
--   * `uq_number_space_selector (tenant, scope, selector)` (V12) stays; it
--     does not name the dropped column.
--
-- WHY NO GRANT IS ISSUED HERE
--
-- Dropping a column takes no privilege away from and gives none to the
-- runtime role: it holds SELECT, INSERT, UPDATE on both tables at the
-- table level from V4. `ServiceRolePrivilegeIT` asserts the exact
-- privilege set in both directions.
-- ===========================================================================

ALTER TABLE worklist.milestone    DROP CONSTRAINT fk_milestone_workstream;
ALTER TABLE worklist.number_space DROP CONSTRAINT fk_number_space_workstream;
ALTER TABLE worklist.milestone    DROP COLUMN workstream_id;
ALTER TABLE worklist.number_space DROP COLUMN workstream_id;
