-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V29: the acceptance criteria of an item (REA-0011 S12, D14).
--
-- An item carries its acceptance criteria as part of its content, each with
-- an identifier the service allocates beneath the item and never issues
-- again. The address of a criterion is
-- `worklist://<scope>/item/<n>/criterion/<k>`; a criterion carries its text
-- and nothing else.
--
-- WHAT A ROW HOLDS
--
-- The item, by its key (tenant, scope, number) as every key onto an item is
-- held (ADR-0042); the identifier `number`, the `k` of the address; the text;
-- and whether the criterion stands (`asserted`) or was taken back
-- (`withdrawn`). The row's own surrogate `pk` is the store's and is never
-- part of an answer.
--
-- THE IDENTIFIER IS UNIQUE OVER EVERY ROW OF THE ITEM, WITHDRAWN ONES TOO
--
-- This is the difference from `item_reference`, whose ordinal is dense over
-- the living entries only. A criterion's number is an identity that a later
-- record books against, so a number a withdrawn row holds is spent: the
-- unique index below runs over all rows and has no partial predicate. The
-- service allocates the next number after the highest one the item ever
-- issued; the item's conflict token orders two writers on one item, and
-- this index is the second line.
--
-- Withdrawal rather than deletion, as everywhere in this schema: nothing
-- holds a DELETE privilege, so a criterion that leaves the list is marked
-- withdrawn and stays standing.
--
-- ADDITIVE, AND N-1 COMPATIBLE AS HELD BY THE AUTHOR (DEC-0018)
--
-- A new table, its index, its policy and its grants, and nothing else. No
-- column, table or policy that exists is changed, and nothing is filled
-- retroactively: criteria that stand as prose in a description stay there.
-- The image before this one does not know the table and reads and writes
-- items unchanged against this schema.
-- ===========================================================================

CREATE TABLE worklist.item_criterion (
    pk           BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id    UUID         NOT NULL,
    scope_id     UUID         NOT NULL,
    item_number  BIGINT       NOT NULL,

    number       BIGINT       NOT NULL,
    text         TEXT         NOT NULL,
    status       TEXT         NOT NULL DEFAULT 'asserted',

    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_item_criterion_number CHECK (number >= 1),
    CONSTRAINT ck_item_criterion_text   CHECK (length(btrim(text)) > 0),
    CONSTRAINT ck_item_criterion_status CHECK (status IN ('asserted', 'withdrawn')),

    CONSTRAINT fk_item_criterion_item FOREIGN KEY (tenant_id, scope_id, item_number)
        REFERENCES worklist.item (tenant_id, scope_id, number)
);

-- One number per item, over every row: a withdrawn criterion keeps its
-- number, and no later criterion of the item takes it. The index also serves
-- the read of an item's criteria, which narrows on its leading columns.
CREATE UNIQUE INDEX uq_item_criterion_number
    ON worklist.item_criterion (tenant_id, scope_id, item_number, number);

ALTER TABLE worklist.item_criterion ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.item_criterion FORCE  ROW LEVEL SECURITY;

CREATE POLICY item_criterion_tenant_isolation ON worklist.item_criterion
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- The three privileges every table of this schema carries. No DELETE: a
-- criterion is withdrawn, never removed. No TRUNCATE, no TRIGGER, no
-- REFERENCES.
GRANT SELECT, INSERT, UPDATE ON worklist.item_criterion TO kumbuka_worklist;
