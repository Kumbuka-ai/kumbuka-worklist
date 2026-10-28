-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V24: a counter is keyed by its view, not by a selector row -- expand.
--
-- The four views `item`, `iteration`, `milestone` and `workstream` are
-- platform vocabulary: the service fixes them in `Selector.VIEWS`, no scope
-- chooses them, and no surface can declare a fifth. Storing them as four rows
-- per scope in `worklist.selector` repeats a constant and calls it a
-- declaration. From this release the service reads a scope's counters by
-- (tenant, scope, view) and never reads `worklist.selector` again.
--
-- This is the EXPAND half of a two-release change:
--
--   1. `number_space.view` is added, filled from the selector each counter
--      hangs on, bounded to the four views and made unique per scope.
--   2. A scope that carries a selector row without its counter gets the
--      counter, its mark carried from the highest number its view holds. The
--      bootstrap script opened the `workstream` selector without one, so
--      every scope bootstrapped since V8 has that gap; with existence now
--      read off the counter, leaving it would turn a resolving workstream
--      address into a refusal.
--   3. A trigger fills the half a writer leaves out: `view` from
--      `selector_pk` for the previous image and for the vendored copies of
--      the bootstrap script, which name only the selector; `selector_pk`
--      from `view` for this image, which names only the view. Both keys
--      are therefore written on every insert, whichever image runs.
--
-- What stays, and is the CONTRACT half of a later release: `worklist.selector`,
-- `number_space.selector_pk` with its NOT NULL, `fk_number_space_selector_pk`,
-- `uq_number_space_selector`, `idx_selector_scope`, the trigger
-- `selector_address_is_immutable` with its function, and the trigger and
-- function this migration adds. The image of this release still writes the
-- selector rows, so the previous image keeps finding them after a rollback.
--
-- N-1 COMPATIBLE AS HELD BY THE AUTHOR. No column is dropped or renamed. The
-- new column is NOT NULL, which is a narrowing constraint, and it is safe
-- for the previous image only because the trigger fills it on every insert
-- that leaves it out: the previous image maps no `view` and inserts a
-- counter with its selector key alone. The previous image reads no column
-- this migration touches.
--
-- TWO STATES STOP THIS MIGRATION, loudly and before anything is changed:
-- a withdrawn selector, and a selector whose token is none of the four
-- views. This image no longer reads a selector's status or token, so either
-- would change an answer -- a withdrawn view would accept again, a fifth
-- token would lose the counter it had. Neither can be written through any
-- surface; one present here was written by hand and is the operator's to
-- resolve before this release can run.
--
-- RLS: the migration runs with `app.tenant_id` bound by
-- `TenantMigrationCallback`, so every statement sees the deployment's tenant
-- and only that one. A counter of another tenant would stay unfilled, and the
-- SET NOT NULL below -- which reads the whole table regardless of policy --
-- refuses it rather than leaving it behind.
--
-- NO GRANT IS ISSUED HERE: no table is added, and the runtime role already
-- holds SELECT, INSERT, UPDATE on `number_space` and INSERT on `selector`
-- (V4), which is everything the trigger does on its behalf.
-- ===========================================================================


-- ---------------------------------------------------------------------------
-- 0. The two states that stop it.
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM worklist.selector WHERE status <> 'declared') THEN
        RAISE EXCEPTION
            'worklist.selector holds a withdrawn view. From V24 the service no longer '
            'reads a selector''s status, so a withdrawn view would accept writes again. '
            'Resolve it before this release.';
    END IF;
    IF EXISTS (SELECT 1 FROM worklist.selector
                WHERE token NOT IN ('item', 'iteration', 'milestone', 'workstream')) THEN
        RAISE EXCEPTION
            'worklist.selector holds a token that names none of the four views. From '
            'V24 a counter is keyed by its view, and this one would have none. Resolve '
            'it before this release.';
    END IF;
END $$;


-- ---------------------------------------------------------------------------
-- 1. The column, filled from the selector each counter hangs on.
-- ---------------------------------------------------------------------------
ALTER TABLE worklist.number_space ADD COLUMN view TEXT;

UPDATE worklist.number_space n
   SET view = s.token
  FROM worklist.selector s
 WHERE s.tenant_id = n.tenant_id
   AND s.pk        = n.selector_pk;


-- ---------------------------------------------------------------------------
-- 2. The counters a selector row stands without.
--
-- The mark is the highest number the view holds in that scope, so the next
-- allocation lands above everything already issued; zero where it holds
-- nothing. Inserted with both keys named, so the trigger below has nothing
-- to fill.
-- ---------------------------------------------------------------------------
INSERT INTO worklist.number_space
    (tenant_id, scope_id, selector_pk, view, high_water_mark)
SELECT s.tenant_id, s.scope_id, s.pk, s.token,
       CASE s.token
           WHEN 'item' THEN (SELECT COALESCE(MAX(x.number), 0) FROM worklist.item x
                              WHERE x.tenant_id = s.tenant_id AND x.scope_id = s.scope_id)
           WHEN 'iteration' THEN (SELECT COALESCE(MAX(x.number), 0) FROM worklist.iteration x
                                   WHERE x.tenant_id = s.tenant_id AND x.scope_id = s.scope_id)
           WHEN 'milestone' THEN (SELECT COALESCE(MAX(x.number), 0) FROM worklist.milestone x
                                   WHERE x.tenant_id = s.tenant_id AND x.scope_id = s.scope_id)
           WHEN 'workstream' THEN (SELECT COALESCE(MAX(x.number), 0) FROM worklist.workstream x
                                    WHERE x.tenant_id = s.tenant_id AND x.scope_id = s.scope_id)
       END
  FROM worklist.selector s
 WHERE NOT EXISTS (
       SELECT 1 FROM worklist.number_space n
        WHERE n.tenant_id = s.tenant_id AND n.selector_pk = s.pk);


-- ---------------------------------------------------------------------------
-- 3. The trigger that writes the half a writer leaves out.
--
-- SECURITY INVOKER, so it resolves the selector under the writer's own tenant
-- binding; the search path is pinned so no caller can put a table of the same
-- name ahead of `worklist`.
--
-- Where neither key resolves -- a view with no selector row, a selector of
-- another tenant -- it leaves the column empty, and NOT NULL refuses the row.
-- The trigger fills; it never invents.
-- ---------------------------------------------------------------------------
CREATE FUNCTION worklist.number_space_fills_view_and_selector() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = worklist, pg_temp
AS $$
BEGIN
    IF NEW.view IS NULL AND NEW.selector_pk IS NOT NULL THEN
        SELECT s.token INTO NEW.view
          FROM worklist.selector s
         WHERE s.tenant_id = NEW.tenant_id AND s.pk = NEW.selector_pk;
    ELSIF NEW.selector_pk IS NULL AND NEW.view IS NOT NULL THEN
        SELECT s.pk INTO NEW.selector_pk
          FROM worklist.selector s
         WHERE s.tenant_id = NEW.tenant_id AND s.scope_id = NEW.scope_id
           AND s.token = NEW.view;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER number_space_fills_view_and_selector
    BEFORE INSERT ON worklist.number_space
    FOR EACH ROW EXECUTE FUNCTION worklist.number_space_fills_view_and_selector();


-- ---------------------------------------------------------------------------
-- 4. The key: present, one of the four, one counter per view and scope.
-- ---------------------------------------------------------------------------
ALTER TABLE worklist.number_space ALTER COLUMN view SET NOT NULL;

ALTER TABLE worklist.number_space ADD CONSTRAINT ck_number_space_view
    CHECK (view IN ('item', 'iteration', 'milestone', 'workstream'));

CREATE UNIQUE INDEX uq_number_space_view
    ON worklist.number_space (tenant_id, scope_id, view);
