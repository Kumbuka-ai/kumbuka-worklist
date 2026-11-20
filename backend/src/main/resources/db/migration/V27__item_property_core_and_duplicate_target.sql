-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V27: the property core of an item, and the target status of `duplicate_of`.
--
-- THE SIX PROPERTIES (DEC-0037 C3, TAR-0002 Annex A2)
--
-- `creator`       the identity that created the item; the service writes it
--                 from the calling subject at the create and never again.
-- `contributor`   the identities responsible for the work the item needs, as
--                 a JSON array of text; any number, the caller's.
-- `parent_number` the item this item is part of, at most one, in the same
--                 scope; held by its number as every key onto an item is
--                 (ADR-0042). The chain is kept free of cycles by the domain,
--                 because a check constraint cannot walk it; the one cycle a
--                 row can express on its own, the item as its own parent, is
--                 refused here as well.
-- `priority`      one of high, medium, low, unassigned; the caller's.
-- `severity`      one of blocker, critical, major, normal, minor, unassigned;
--                 the caller's.
-- `close_date`    when the item closed; the service writes it when the item
--                 moves from a status that is not closed into one that is,
--                 and clears it when the item leaves a closed status again.
--
-- The scope's own declared attribute `priority` (bootstrap-scope.sql) is a
-- different thing under the item's `attributes` and stays as it is.
--
-- THE TARGET STATUS OF `duplicate_of` (REA-0011 D18)
--
-- The relation type named `duplicate_of` names one target status: the status
-- `relate` with that type closes the source item into. Only the type of that
-- name takes one, which the CHECK below holds for every path that declares a
-- type, SQL included. That the target is declared, not withdrawn, closed and
-- not successful, and of the type's own scope, is a question across two rows
-- and is asked by the domain at the moment of `relate`.
--
-- ADDITIVE, AND N-1 COMPATIBLE AS HELD BY THE AUTHOR (DEC-0018)
--
-- Columns are added and nothing else is changed. Every new column is
-- nullable and has no default, so the insert of the image before this one,
-- which names none of them, stores a row with all of them empty, and an item
-- created before this step reads back with no value for any of them. Nothing
-- is filled retroactively (REA-0011 section 4): an item created before this
-- step carries no `creator`. The CHECKs and keys below concern the new
-- columns only, so no row that exists can violate them. No grant changes:
-- the runtime role already holds SELECT, INSERT and UPDATE on both tables,
-- which covers their columns.
-- ===========================================================================

ALTER TABLE worklist.item
    ADD COLUMN creator        TEXT,
    ADD COLUMN contributor    JSONB,
    ADD COLUMN parent_number  BIGINT,
    ADD COLUMN priority       TEXT,
    ADD COLUMN severity       TEXT,
    ADD COLUMN close_date     TIMESTAMPTZ;

ALTER TABLE worklist.item
    ADD CONSTRAINT ck_item_priority
        CHECK (priority IN ('high', 'medium', 'low', 'unassigned')),
    ADD CONSTRAINT ck_item_severity
        CHECK (severity IN ('blocker', 'critical', 'major', 'normal', 'minor', 'unassigned')),
    ADD CONSTRAINT ck_item_contributor_array
        CHECK (contributor IS NULL OR jsonb_typeof(contributor) = 'array'),
    ADD CONSTRAINT ck_item_parent_other
        CHECK (parent_number IS NULL OR parent_number <> number),
    ADD CONSTRAINT fk_item_item_parent FOREIGN KEY (tenant_id, scope_id, parent_number)
        REFERENCES worklist.item (tenant_id, scope_id, number);

ALTER TABLE worklist.relation_type
    ADD COLUMN target_status_pk BIGINT;

ALTER TABLE worklist.relation_type
    ADD CONSTRAINT ck_relation_type_target_duplicate_of
        CHECK (target_status_pk IS NULL OR name = 'duplicate_of'),
    ADD CONSTRAINT fk_relation_type_item_status_target FOREIGN KEY (tenant_id, target_status_pk)
        REFERENCES worklist.item_status (tenant_id, pk);
