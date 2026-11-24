-- Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
-- SPDX-License-Identifier: AGPL-3.0-only
-- This file is part of Kumbuka and is licensed under the GNU Affero
-- General Public License v3.0 only. See the LICENSE file in the
-- repository root for the full licence text.

-- ===========================================================================
-- V34: the addenda of an item (REA-0011 D13).
--
-- Once an item is submitted its content -- title, description, acceptance
-- criteria -- is frozen. A correction or a comment to a living item is an
-- addendum: an append-only text on the item with its author, the delegated
-- identity the author acted through, and the time. `addendum` writes one in
-- the phases open and in_progress; a draft is still edited, and a closed item
-- takes nothing.
--
-- WHAT A ROW HOLDS
--
-- The item, by its key (tenant, scope, number) as every key onto an item is
-- held; the text, never blank and as uncapped as the description it corrects;
-- the author, the calling subject as the service derived it from the token,
-- in the form `item.creator` holds it; the delegated identity, where the
-- service's call context carries one, and empty otherwise; and the time of
-- the write. The row's own surrogate `pk` is the store's, orders two addenda
-- of the same instant, and is never part of an answer.
--
-- APPEND-ONLY BY GRANT
--
-- The runtime role holds SELECT and INSERT on this table and nothing else: no
-- UPDATE, no DELETE. An addendum is never changed or removed; a second one is
-- a new row beside the first. A draft has no addenda, so `discard`, which
-- deletes a draft, has nothing to delete here.
--
-- ADDITIVE, AND N-1 COMPATIBLE AS HELD BY THE AUTHOR (DEC-0018)
--
-- A new table, its index, its checks, its policy and its grants, and nothing
-- else. No column, table or policy that exists is changed, and nothing is
-- filled retroactively. The image before this one does not know the table and
-- reads and writes items unchanged against this schema.
-- ===========================================================================

CREATE TABLE worklist.item_addendum (
    pk                  BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           UUID         NOT NULL,
    scope_id            UUID         NOT NULL,
    item_number         BIGINT       NOT NULL,

    text                TEXT         NOT NULL,
    author              TEXT         NOT NULL,
    delegated_identity  TEXT,
    written_at          TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_item_addendum_text   CHECK (length(btrim(text)) > 0),
    CONSTRAINT ck_item_addendum_author CHECK (length(btrim(author)) > 0),

    CONSTRAINT fk_item_addendum_item FOREIGN KEY (tenant_id, scope_id, item_number)
        REFERENCES worklist.item (tenant_id, scope_id, number)
);

-- The read of an item's addenda narrows on the item and answers in the order
-- they were written: by time, and by the surrogate where two share an instant.
CREATE INDEX idx_item_addendum_item
    ON worklist.item_addendum (tenant_id, scope_id, item_number, written_at, pk);

ALTER TABLE worklist.item_addendum ENABLE ROW LEVEL SECURITY;
ALTER TABLE worklist.item_addendum FORCE  ROW LEVEL SECURITY;

CREATE POLICY item_addendum_tenant_isolation ON worklist.item_addendum
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- SELECT and INSERT, and nothing else: no UPDATE and no DELETE, so the runtime
-- role can neither change nor remove an addendum. No TRUNCATE, no TRIGGER, no
-- REFERENCES.
GRANT SELECT, INSERT ON worklist.item_addendum TO kumbuka_worklist;
