-- ===========================================================================
-- V9: the workstream at the item and at the milestone; the milestone's
-- number space split from a scope-wide counter to a per-workstream one.
--
-- V8 built the table and gave every existing scope a `default` workstream.
-- This migration hangs the reference on both tables, backfills every
-- existing row against that default, and adds the composite foreign key
-- that binds the reference to the SAME tenant it belongs to. It does NOT
-- narrow the columns to NOT NULL — V10 does that in a separate file, so
-- one applied migration is never rewritten to strengthen a constraint the
-- code did not require when the migration ran.
--
-- WHY THE COLUMN COMES NULLABLE AND IS BACKFILLED IN THE SAME MIGRATION
--
-- A NOT NULL column without a stored default is what a caller reads a
-- migration and expects for a per-row mandatory reference. The domain
-- default here is SCOPE-SPECIFIC — the default workstream of the row's own
-- scope — and that is not a value a column default can express. So the
-- column comes nullable, every existing row is backfilled to its scope's
-- default in this file, and V10 turns the invariant on once every row
-- carries a value.
--
-- WHY THE MILESTONE NUMBER SPACE IS SPLIT
--
-- The milestone's counter was per-scope, and the ratification of 2026-09-08
-- puts it per-workstream: existing milestones stay milestones of the
-- default (product) workstream and keep their numbers, and new workstreams
-- open with a counter of their own at zero. This migration adds the
-- workstream axis to `number_space`, points the existing milestone counter
-- at the default workstream of its scope, and rewrites the uniqueness so
-- the (selector, workstream) pair identifies a counter row.
--
-- The `item` and `iteration` counters remain scope-wide — their addresses
-- carry the view and the scope, not the workstream. Only the milestone's
-- number space depends on the fourth axis.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- PART 1 — `workstream_id` ON THE ITEM AND ON THE MILESTONE.
--
-- Nullable so it can be added without stopping every write against these
-- tables while the backfill runs. The composite foreign key is added AFTER
-- the backfill, because a FK on an empty column would refuse the very
-- populating UPDATE.
-- ---------------------------------------------------------------------------

ALTER TABLE worklist.item        ADD COLUMN workstream_id UUID;
ALTER TABLE worklist.milestone   ADD COLUMN workstream_id UUID;


-- ---------------------------------------------------------------------------
-- PART 2 — BACKFILL: every existing row falls to its scope's default.
--
-- The default was created by V8 for every scope registered in
-- `scope_setting`, so the reference below always resolves. RLS binds the
-- migrator too, so the loop binds `app.tenant_id` per scope before the
-- UPDATE runs — exactly as PART 3 of V8 did for the seed inserts.
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    scope_row     RECORD;
    default_id    UUID;
BEGIN
    FOR scope_row IN
        SELECT tenant_id, scope_id FROM worklist.scope_setting
    LOOP
        PERFORM set_config('app.tenant_id', scope_row.tenant_id::text, true);

        SELECT id INTO default_id
        FROM worklist.workstream
        WHERE tenant_id  = scope_row.tenant_id
          AND scope_id   = scope_row.scope_id
          AND is_default = true;

        IF default_id IS NULL THEN
            -- V8 created the row, so its absence here is a defect worth a
            -- loud failure rather than a repair. Reporting rather than
            -- fabricating; a fabricated default would be a row without a
            -- number and without a place in the counter.
            RAISE EXCEPTION
                'scope % has no default workstream; V8 should have created one. '
                'Refusing to fabricate one from V9 — the two migrations are the '
                'declaration and the pointer respectively, and the pointer has '
                'nothing to point at',
                scope_row.scope_id;
        END IF;

        UPDATE worklist.item
        SET workstream_id = default_id
        WHERE tenant_id     = scope_row.tenant_id
          AND scope_id      = scope_row.scope_id
          AND workstream_id IS NULL;

        UPDATE worklist.milestone
        SET workstream_id = default_id
        WHERE tenant_id     = scope_row.tenant_id
          AND scope_id      = scope_row.scope_id
          AND workstream_id IS NULL;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END $$;


-- ---------------------------------------------------------------------------
-- PART 3 — THE COMPOSITE FOREIGN KEYS.
--
-- The same shape as the item-to-selector FK: (tenant_id, workstream_id) →
-- `workstream (tenant_id, id)`, so a reference can only point at a
-- workstream of its OWN tenant. A plain `REFERENCES workstream(id)` would
-- be checked with row-level security bypassed and would happily bind a row
-- to a foreign tenant's workstream.
-- ---------------------------------------------------------------------------

ALTER TABLE worklist.item
    ADD CONSTRAINT fk_item_workstream FOREIGN KEY (tenant_id, workstream_id)
        REFERENCES worklist.workstream (tenant_id, id);

ALTER TABLE worklist.milestone
    ADD CONSTRAINT fk_milestone_workstream FOREIGN KEY (tenant_id, workstream_id)
        REFERENCES worklist.workstream (tenant_id, id);


-- ---------------------------------------------------------------------------
-- PART 4 — MILESTONE NUMBER SPACE, PER WORKSTREAM.
--
-- `number_space` gains a nullable `workstream_id`. For the milestone
-- selector's counters, the column is populated by the same backfill loop
-- as PART 2; for the item and iteration selectors it stays null, and the
-- domain enforces the invariant "milestone's counter carries a workstream,
-- others do not".
--
-- The old `uq_number_space_selector` was `(tenant, scope, selector)`. It
-- is replaced by TWO partial unique indexes — one for the scope-wide
-- counters (workstream_id IS NULL) and one for the per-workstream ones
-- (workstream_id IS NOT NULL). Between them they say: one row per
-- selector, or one row per (selector, workstream), never both.
-- ---------------------------------------------------------------------------

ALTER TABLE worklist.number_space ADD COLUMN workstream_id UUID;

DO $$
DECLARE
    scope_row     RECORD;
    default_id    UUID;
    milestone_sel UUID;
BEGIN
    FOR scope_row IN
        SELECT tenant_id, scope_id FROM worklist.scope_setting
    LOOP
        PERFORM set_config('app.tenant_id', scope_row.tenant_id::text, true);

        SELECT id INTO default_id
        FROM worklist.workstream
        WHERE tenant_id  = scope_row.tenant_id
          AND scope_id   = scope_row.scope_id
          AND is_default = true;

        SELECT id INTO milestone_sel
        FROM worklist.selector
        WHERE tenant_id = scope_row.tenant_id
          AND scope_id  = scope_row.scope_id
          AND token     = 'milestone';

        IF default_id IS NULL OR milestone_sel IS NULL THEN
            RAISE EXCEPTION
                'scope % is missing its default workstream or its milestone selector, '
                'so the milestone counter cannot be re-pointed. Refusing rather than '
                'silently leaving a counter row scope-wide — half the migration is worse '
                'than none of it',
                scope_row.scope_id;
        END IF;

        UPDATE worklist.number_space
        SET workstream_id = default_id
        WHERE tenant_id     = scope_row.tenant_id
          AND scope_id      = scope_row.scope_id
          AND selector_id   = milestone_sel
          AND workstream_id IS NULL;
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END $$;

ALTER TABLE worklist.number_space
    ADD CONSTRAINT fk_number_space_workstream FOREIGN KEY (tenant_id, workstream_id)
        REFERENCES worklist.workstream (tenant_id, id);

DROP INDEX IF EXISTS worklist.uq_number_space_selector;

CREATE UNIQUE INDEX uq_number_space_selector_wide
    ON worklist.number_space (tenant_id, scope_id, selector_id)
    WHERE workstream_id IS NULL;

CREATE UNIQUE INDEX uq_number_space_selector_per_workstream
    ON worklist.number_space (tenant_id, scope_id, selector_id, workstream_id)
    WHERE workstream_id IS NOT NULL;


-- ---------------------------------------------------------------------------
-- No GRANT is issued here. The runtime role already holds SELECT, INSERT,
-- UPDATE on `worklist.item`, `worklist.milestone` and `worklist.number_space`
-- without a column list (V2, V4). A table-level privilege covers every
-- column the table ever acquires, so an added column needs no additional
-- grant. `ServiceRolePrivilegeIT` asserts the exact privilege set in both
-- directions.
-- ---------------------------------------------------------------------------
