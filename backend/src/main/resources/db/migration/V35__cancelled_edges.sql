-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V35: references and relations are cancelled, never deleted (REA-0011 D15).
--
-- `unrelate` cancels an edge, and an `update` that leaves a reference or a
-- relation out of its list cancels that one: the row stays, its status moves
-- to `withdrawn` -- the stored word of the cancelled state, unchanged since
-- V4, which the partial unique index of V20 already reads -- and it records
-- when it ended, who ended it and why.
--
-- WHAT A ROW HOLDS NOW
--
-- Three columns on `item_relation` and on `item_reference`, each nullable:
-- `ended_at`, the instant of the cancellation by the service's clock;
-- `ended_by`, the calling subject as the service derived it from the token,
-- in the form `item.creator` and `item_addendum.author` hold it; and
-- `end_reason`, the reason `unrelate` takes. `update` carries no reason, so a
-- row it cancels keeps `end_reason` empty. The reason is as uncapped as the
-- description of an item (V7), and never blank where it is given.
--
-- An edge cancelled and asserted again by `relate` is the same row, since the
-- key (from, to, type) admits no second one: it is active again, and its end
-- columns keep the trace of its last cancellation until the next one
-- overwrites them. A read answers them only for a cancelled row.
--
-- NO BACKFILL
--
-- A row withdrawn before this migration stays cancelled, with empty end
-- columns: when, by whom and why were never recorded, and nothing here makes
-- them up.
--
-- GRANTS
--
-- None. The runtime role holds SELECT, INSERT and UPDATE on both tables since
-- V4, as table privileges, so the new columns are covered; DELETE stays as V33
-- granted it, for `discard` of a draft alone.
--
-- ADDITIVE, AND N-1 COMPATIBLE AS HELD BY THE AUTHOR (DEC-0018)
--
-- Six nullable columns and two checks on them, and nothing else. No column,
-- constraint, index or policy that exists is changed, dropped or renamed, and
-- every existing row satisfies the checks, whose columns it holds empty. The
-- image before this one names none of the columns: it inserts rows with them
-- empty, moves the status as it did, and reads a cancelled row as the
-- withdrawn row it always knew, so it answers active edges as before.
-- ===========================================================================

ALTER TABLE worklist.item_relation
    ADD COLUMN ended_at   TIMESTAMPTZ,
    ADD COLUMN ended_by   TEXT,
    ADD COLUMN end_reason TEXT;

ALTER TABLE worklist.item_relation
    ADD CONSTRAINT ck_item_relation_end_reason
        CHECK (end_reason IS NULL OR length(btrim(end_reason)) > 0);

ALTER TABLE worklist.item_reference
    ADD COLUMN ended_at   TIMESTAMPTZ,
    ADD COLUMN ended_by   TEXT,
    ADD COLUMN end_reason TEXT;

ALTER TABLE worklist.item_reference
    ADD CONSTRAINT ck_item_reference_end_reason
        CHECK (end_reason IS NULL OR length(btrim(end_reason)) > 0);
