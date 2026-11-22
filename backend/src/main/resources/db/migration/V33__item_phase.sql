-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V33: the phase of an item, and the phase every status is bound to.
--
-- THE PHASE OF AN ITEM (REA-0011 D11, TAR-0002 B20)
--
-- `item.phase` is one of draft, open, in_progress, closed, in a column of its
-- own. Only the transitions write it: `create` makes a draft, `submit`,
-- `start`, `return` and `close` move it, `discard` deletes a draft. A status
-- write never moves it, so a change to a scope's vocabulary moves no item
-- between phases.
--
-- THE DATA MIGRATION (REA-0011 S9, section 4)
--
-- Existing items are placed by their status: an item whose status answers
-- `closed` is in phase closed, every other item stays in phase open, the
-- column's default. No existing item becomes draft or in_progress; an item
-- whose status answers `in_progress` stays in phase open, as S9 places it.
-- The statement runs with `app.tenant_id` bound by `TenantMigrationCallback`,
-- so it sees the deployment's tenant and only that one, as every backfill of
-- this chain does (V24).
--
-- THE PHASE OF A STATUS (TAR-0002 B2, B3)
--
-- `item_status.phase` is the phase the status is bound to, stored and
-- generated from the status's predicates by the projection B3 fixes: a status
-- answering `closed` is bound to closed, one answering `in_progress` to
-- in_progress, every other to open. V4's `ck_item_status_terminal_or_running`
-- keeps the two predicates apart, so each status names exactly one phase, and
-- no status is bound to draft: a draft carries a status of the phase open.
-- Generated rather than written by the service, so that a status the image
-- before this one declares names its phase too.
--
-- DISCARD DELETES A DRAFT (REA-0011 D12)
--
-- `discard` removes a draft and what stands beside it: its references, its
-- criteria, its relations in both directions and its tags (V26 cascades). So
-- the runtime role is granted DELETE on `item` and on the three tables of an
-- item's satellites that do not cascade. A draft is never planned and never
-- booked, so the membership and the fulfilment ledger keep their grants. The
-- number a discarded draft carried stays spent: the number space's
-- high-water mark never moves back (TAR-0002 B1).
--
-- ADDITIVE, AND N-1 COMPATIBLE AS HELD BY THE AUTHOR (DEC-0018)
--
-- Two columns are added, one with a default and one generated, and grants
-- are added; nothing is dropped, renamed or narrowed. The CHECK concerns the
-- new column only, and every row satisfies it after the data migration. The
-- image before this one names neither column: its insert of an item stores
-- phase open, its insert of a status stores the phase generated from the
-- predicates it names, and it reads neither. An item created as a draft by
-- this image carries a status of the phase open and reads to that image as an
-- ordinary item.
-- ===========================================================================

ALTER TABLE worklist.item
    ADD COLUMN phase TEXT NOT NULL DEFAULT 'open';

ALTER TABLE worklist.item
    ADD CONSTRAINT ck_item_phase
        CHECK (phase IN ('draft', 'open', 'in_progress', 'closed'));

UPDATE worklist.item i
   SET phase = 'closed'
  FROM worklist.item_status s
 WHERE s.tenant_id = i.tenant_id
   AND s.pk = i.status_pk
   AND s.closed;

ALTER TABLE worklist.item_status
    ADD COLUMN phase TEXT NOT NULL GENERATED ALWAYS AS (
        CASE
            WHEN closed      THEN 'closed'
            WHEN in_progress THEN 'in_progress'
            ELSE 'open'
        END) STORED;

CREATE INDEX idx_item_phase ON worklist.item (tenant_id, scope_id, phase);

GRANT DELETE ON worklist.item           TO kumbuka_worklist;
GRANT DELETE ON worklist.item_reference TO kumbuka_worklist;
GRANT DELETE ON worklist.item_relation  TO kumbuka_worklist;
GRANT DELETE ON worklist.item_criterion TO kumbuka_worklist;
