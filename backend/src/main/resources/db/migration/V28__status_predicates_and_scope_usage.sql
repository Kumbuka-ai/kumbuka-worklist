-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V28: three more predicates of a status, and what a scope declares it uses.
--
-- THE THREE PREDICATES (DEC-0037 C2, TAR-0002 B3 and Annex A1)
--
-- `reviewed`  is the item valid and not a duplicate?
-- `approved`  is it approved to be worked?
-- `verified`  has the outcome of the business incident been confirmed?
--
-- Like the four before them they are declared on the status word and never
-- stored on the item. Each is answered only by an item of a scope that uses
-- it; an item of any other scope answers no value for it, not false.
--
-- WHAT A SCOPE USES (DEC-0037 C5)
--
-- `scope_usage` holds one row per scope and per optional thing the scope
-- uses: one of the three predicates above, or one of the properties
-- `priority` and `severity`. No row means: not used. The declarations are
-- additive rows (REA-0011 S3), so an image rollback leaves rows nothing reads.
--
-- The consistency rules over one status -- `verified` requires `closed` and
-- `successful`; in a scope that uses `reviewed` or `approved`, an
-- `actionable` status is also `reviewed` and `approved` -- are checked by
-- the domain when a status or a usage is declared. The second one is a
-- statement across two tables, which no check constraint expresses.
--
-- ADDITIVE, AND N-1 COMPATIBLE (DEC-0018)
--
-- The three columns carry DEFAULT false, so the insert of the image before
-- this one, which names none of them, still stores a row, and every status
-- that exists reads as it did. The table is new and the image before this
-- one does not know it. Nothing is filled retroactively, nothing is dropped,
-- renamed or narrowed. The runtime role already holds SELECT, INSERT and
-- UPDATE on `item_status`, which covers the new columns.
-- ===========================================================================

ALTER TABLE worklist.item_status
    ADD COLUMN reviewed  BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN approved  BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN verified  BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE worklist.scope_usage (
    tenant_id    UUID         NOT NULL,
    scope_id     UUID         NOT NULL,
    name         TEXT         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    PRIMARY KEY (tenant_id, scope_id, name),

    CONSTRAINT ck_scope_usage_name
        CHECK (name IN ('reviewed', 'approved', 'verified', 'priority', 'severity'))
);

ALTER TABLE worklist.scope_usage ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.scope_usage FORCE  ROW LEVEL SECURITY;

CREATE POLICY scope_usage_tenant_isolation ON worklist.scope_usage
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- The three privileges every table of this schema carries. No DELETE: a
-- usage once declared is not withdrawn by any verb. No TRUNCATE, no TRIGGER,
-- no REFERENCES.
GRANT SELECT, INSERT, UPDATE ON worklist.scope_usage TO kumbuka_worklist;
