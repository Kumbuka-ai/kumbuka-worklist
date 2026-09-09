-- ===========================================================================
-- V12: Roll back the milestone-workstream edge.
--
-- The edge between milestone and workstream was never ratified: TAR-0002
-- section 4 states plainly that "none of the three carries an edge to
-- another", and REQ-0148 is marked obsolete for the same reason (the
-- stock refuted it — twelve items aimed at one milestone lie in three
-- different bodies of work, and they are the conceptual, the building
-- and the operating half of a single rebuild).
--
-- V9 (part 4) and V11 built the edge in the database. This migration
-- retracts it while staying additive: no column dropped, no index
-- renamed, no applied file edited.
--
-- WHAT CHANGES
--
--   1. The milestone number space returns to scope-wide. The counter
--      row for the milestone selector loses its `workstream_id`, and
--      the two partial unique indexes that expressed "one row per
--      selector OR one row per (selector, workstream)" give way to the
--      single scope-wide uniqueness `(tenant, scope, selector)`.
--
--   2. The uniqueness of milestone numbers returns to per-scope.
--      V11's `uq_milestone_number_per_workstream` is dropped and V4's
--      old `uq_milestone_number (tenant, scope, number)` is restored.
--
-- WHAT DOES NOT CHANGE
--
--   * `milestone.workstream_id`, `number_space.workstream_id`, and the
--     two composite foreign keys `fk_milestone_workstream` and
--     `fk_number_space_workstream` all remain. Dropping them would
--     bring the compatibility with the running image down for no
--     gain; a foreign key on a dead column is harmless. The COMMENT
--     statements below mark both columns as dead and name the wave
--     that will drop them.
--
--   * The item's `workstream_id` and its NOT NULL constraint stay:
--     they express a different invariant (an item belongs to a
--     workstream), which the rollback does not touch.
--
-- WHY NO GRANT IS ISSUED HERE
--
-- Same reason V5, V6, V7, V10 and V11 hand out none. The runtime role
-- holds SELECT, INSERT, UPDATE on `worklist.milestone` and
-- `worklist.number_space` at the table level from V4; a table-level
-- privilege covers every constraint and index the table acquires.
-- `ServiceRolePrivilegeIT` asserts the exact privilege set in both
-- directions.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- PART 1 — MILESTONE NUMBER UNIQUENESS: back to per-scope.
--
-- V11 replaced `uq_milestone_number (tenant, scope, number)` with
-- `uq_milestone_number_per_workstream (tenant, scope, workstream_id, number)`.
-- With the workstream axis retracted, two workstreams no longer share a
-- number, and the scope-wide form is the invariant again.
--
-- Order matters: drop the per-workstream form first so a subsequent duplicate
-- becomes visible during the CREATE. If two milestone rows in the same scope
-- carry the same number (which V11 legitimised for different workstreams),
-- the CREATE will refuse loud rather than silently drop one. Refusal here is
-- the correct outcome — the operator resolves the collision before the
-- migration proceeds.
-- ---------------------------------------------------------------------------

ALTER TABLE worklist.milestone
    DROP CONSTRAINT uq_milestone_number_per_workstream;

ALTER TABLE worklist.milestone
    ADD CONSTRAINT uq_milestone_number
        UNIQUE (tenant_id, scope_id, number);


-- ---------------------------------------------------------------------------
-- PART 2 — MILESTONE NUMBER SPACE COUNTERS: back to scope-wide.
--
-- The milestone counter rows lose their `workstream_id`. RLS binds the
-- migrator too, so the loop binds `app.tenant_id` per scope before each
-- UPDATE — exactly as V8 part 3 and V9 part 2/4 did for their DML.
--
-- Order matters again: DROP the two partial indexes FIRST, then UPDATE, then
-- CREATE the scope-wide index. If UPDATE ran first, moving a per-workstream
-- counter row into the wide bucket would collide with the wide index that
-- already covers `item` and `iteration` selectors (both scope-wide). Dropping
-- the two partial indexes before the UPDATE makes the intermediate state
-- consistent with the write.
-- ---------------------------------------------------------------------------

DROP INDEX IF EXISTS worklist.uq_number_space_selector_wide;
DROP INDEX IF EXISTS worklist.uq_number_space_selector_per_workstream;

DO $$
DECLARE
    scope_row     RECORD;
    milestone_sel UUID;
BEGIN
    FOR scope_row IN
        SELECT tenant_id, scope_id FROM worklist.scope_setting
    LOOP
        PERFORM set_config('app.tenant_id', scope_row.tenant_id::text, true);

        SELECT id INTO milestone_sel
        FROM worklist.selector
        WHERE tenant_id = scope_row.tenant_id
          AND scope_id  = scope_row.scope_id
          AND token     = 'milestone';

        IF milestone_sel IS NULL THEN
            -- V6 declared the milestone selector for every scope; its
            -- absence is a defect, not a shape this migration repairs.
            RAISE EXCEPTION
                'scope % has no milestone selector; V6 should have declared one. '
                'Refusing to fabricate a counter for a selector that does not exist',
                scope_row.scope_id;
        END IF;

        UPDATE worklist.number_space
        SET workstream_id = NULL
        WHERE tenant_id     = scope_row.tenant_id
          AND scope_id      = scope_row.scope_id
          AND selector_id   = milestone_sel;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END $$;

CREATE UNIQUE INDEX uq_number_space_selector
    ON worklist.number_space (tenant_id, scope_id, selector_id);


-- ---------------------------------------------------------------------------
-- PART 3 — MARK THE DEAD COLUMNS.
--
-- The columns stay so an older image can still read this database and
-- the SELECT arity does not shift. They mean nothing after this migration.
-- A reader who finds them without a note would assume they meant something,
-- so the note lives on the column itself and travels with the schema.
--
-- The next migration that opens `milestone` or `number_space` for another
-- reason drops both columns and the two composite foreign keys that hang
-- on them.
-- ---------------------------------------------------------------------------

COMMENT ON COLUMN worklist.milestone.workstream_id IS
    'DEAD as of V12 (2026-09-09): the milestone-workstream edge was '
    'retracted (TAR-0002 section 4, REQ-0148 obsolete). The column is '
    'left in place so an older image can still read this table; a new '
    'row may leave it null or copy the caller-supplied value, but no '
    'invariant reads it. Dropped together with fk_milestone_workstream '
    'in the next migration that opens this table for another reason.';

COMMENT ON COLUMN worklist.number_space.workstream_id IS
    'DEAD as of V12 (2026-09-09): the milestone counter returned to '
    'scope-wide, so no selector uses this column any more. Left in '
    'place so an older image can still read this table. Dropped '
    'together with fk_number_space_workstream in the next migration '
    'that opens this table for another reason.';
