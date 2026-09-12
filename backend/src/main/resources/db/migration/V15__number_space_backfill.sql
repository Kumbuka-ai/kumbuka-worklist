-- ===========================================================================
-- V15: scope-wide number-space rows for `iteration` and `milestone`, wherever
-- they are missing.
--
-- WHY THIS FILE EXISTS
--
-- `SelectorRegistry.allocate` reads the scope-wide row of the selector's
-- number space (`workstream_id IS NULL`) and refuses typed when there is
-- none. `SelectorRegistry.declare` opens that row in the same transaction as
-- the selector, so a scope declared through the verb surface always has one.
--
-- The estate holds one scope that predates the verb surface. Its selectors
-- were seeded through `bootstrap-scope.sql`, and until this migration the
-- bootstrap opened only the counter of `workstream` (V8 backfill left one for
-- the milestone selector too, but V12 tied its own retraction to a per-scope
-- loop that requires the milestone selector to be present — which is a
-- necessary condition, not a sufficient one that shows the counter is now
-- scope-wide-shaped for every scope). Measured 2026-09-12 against the
-- `kumbuka` scope: `create` on `iteration` and on `milestone` refuses with
-- SELECTOR_UNDECLARED — the scope carries the selectors but no scope-wide
-- counter row for either.
--
-- This migration fills the missing rows without touching anything else, and
-- carries the high-water mark forward from whatever the store already knows.
--
-- WHAT IT DOES, EXACTLY
--
--   * For every scope registered in `worklist.scope_setting`, and for each
--     of the selectors `iteration` and `milestone`, ensure that a scope-wide
--     `number_space` row (`workstream_id IS NULL`) exists.
--
--   * The high-water mark is CARRIED FORWARD, never set back:
--       — from a per-workstream row of the same (tenant, scope, selector),
--         if V12's retraction left one behind (`MAX(high_water_mark)` over
--         all workstreams, so a scope carrying more than one is consolidated
--         to the highest);
--       — else from the highest `number` present in the respective planning
--         table (`iteration.number` or `milestone.number`), or `0` when the
--         table is empty for that scope.
--     A scope-wide row that already exists is left untouched: it is the
--     authoritative counter and this migration does not overwrite it.
--
--   * Per-workstream rows for these two selectors, if any, are dropped
--     AFTER the scope-wide row is in place. `uq_number_space_selector` (V12)
--     already enforces uniqueness over `(tenant, scope, selector)`, so a
--     per-workstream row for a selector whose counter is now scope-wide is
--     a duplicate that cannot survive the invariant.
--
-- WHAT IT DOES NOT DO
--
--   * It does NOT touch `workstream`'s counter. `bootstrap-scope.sql`
--     already seeds it, and this migration stays inside the two selectors
--     the estate actually reports missing counters for.
--
--   * It does NOT create the deferred allocation branch that SPRINT_180.5
--     attempted at runtime. Missing counters are a migration concern, not a
--     runtime one: the deferred branch loses the row lock at the moment two
--     concurrent first-allocations need it most.
--
--   * It does NOT touch selectors that were never declared for a scope.
--     `SelectorRegistry.declare` opens the counter with the selector, and
--     no counter without a selector row is a shape this migration produces.
--
-- RLS
--
-- RLS binds the migrator too, so the loop binds `app.tenant_id` per scope
-- before every DML — the same pattern V8 part 3, V9 parts 2/4 and V12 part 2
-- use.
--
-- NO GRANT IS ISSUED HERE
--
-- V4 granted SELECT, INSERT, UPDATE on `worklist.number_space` without a
-- column list, and a table-level privilege covers every row this migration
-- inserts. `DELETE` is not granted to the service role and stays absent —
-- the deletions below run as the migrator, and no service-role path deletes
-- from this table. `ServiceRolePrivilegeIT` asserts the exact privilege set
-- in both directions.
-- ===========================================================================


DO $$
DECLARE
    scope_row       RECORD;
    selector_id_val UUID;
    selector_token  TEXT;
    consolidated    BIGINT;
    highest_number  BIGINT;
    wide_present    BOOLEAN;
BEGIN
    FOR scope_row IN
        SELECT tenant_id, scope_id FROM worklist.scope_setting
    LOOP
        PERFORM set_config('app.tenant_id', scope_row.tenant_id::text, true);

        -- Two selectors, in a fixed order — the loop reads better than
        -- copying the block twice and drifting.
        FOREACH selector_token IN ARRAY ARRAY['iteration', 'milestone']
        LOOP
            SELECT id INTO selector_id_val
            FROM worklist.selector
            WHERE tenant_id = scope_row.tenant_id
              AND scope_id  = scope_row.scope_id
              AND token     = selector_token;

            -- A scope without one of these two selectors is not this
            -- migration's business — V6 declares them for every scope
            -- opened through bootstrap-scope.sql, and their absence is a
            -- defect a repair would silently paper over.
            CONTINUE WHEN selector_id_val IS NULL;

            -- Whether the scope-wide row is already there.
            SELECT EXISTS (
                SELECT 1
                FROM worklist.number_space
                WHERE tenant_id     = scope_row.tenant_id
                  AND scope_id      = scope_row.scope_id
                  AND selector_id   = selector_id_val
                  AND workstream_id IS NULL
            ) INTO wide_present;

            IF NOT wide_present THEN
                -- The mark is carried forward from the two possible
                -- sources, whichever gives the higher value. Neither is
                -- allowed to be null: coalesce keeps the arithmetic
                -- honest against an empty table.
                SELECT COALESCE(MAX(high_water_mark), 0) INTO consolidated
                FROM worklist.number_space
                WHERE tenant_id   = scope_row.tenant_id
                  AND scope_id    = scope_row.scope_id
                  AND selector_id = selector_id_val;

                IF selector_token = 'iteration' THEN
                    SELECT COALESCE(MAX(number), 0) INTO highest_number
                    FROM worklist.iteration
                    WHERE tenant_id = scope_row.tenant_id
                      AND scope_id  = scope_row.scope_id;
                ELSIF selector_token = 'milestone' THEN
                    SELECT COALESCE(MAX(number), 0) INTO highest_number
                    FROM worklist.milestone
                    WHERE tenant_id = scope_row.tenant_id
                      AND scope_id  = scope_row.scope_id;
                ELSE
                    highest_number := 0;
                END IF;

                consolidated := GREATEST(consolidated, highest_number);

                INSERT INTO worklist.number_space
                    (tenant_id, scope_id, selector_id, workstream_id, high_water_mark)
                VALUES
                    (scope_row.tenant_id, scope_row.scope_id,
                     selector_id_val, NULL, consolidated);
            END IF;

            -- The scope-wide row is now in place. Per-workstream rows for
            -- this selector, if any, are duplicates by `uq_number_space_
            -- selector` and are dropped. The DELETE runs whether the
            -- scope-wide row was just inserted or already stood — either
            -- way, per-workstream rows for these two selectors are the
            -- retracted shape and are no longer read.
            DELETE FROM worklist.number_space
            WHERE tenant_id     = scope_row.tenant_id
              AND scope_id      = scope_row.scope_id
              AND selector_id   = selector_id_val
              AND workstream_id IS NOT NULL;
        END LOOP;
    END LOOP;

    PERFORM set_config('app.tenant_id', '', true);
END $$;
